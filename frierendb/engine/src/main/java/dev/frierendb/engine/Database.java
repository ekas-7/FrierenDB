package dev.frierendb.engine;

import dev.frierendb.engine.catalog.Catalog;
import dev.frierendb.engine.catalog.IndexInfo;
import dev.frierendb.engine.exec.Executor;
import dev.frierendb.engine.exec.QueryOptions;
import dev.frierendb.engine.exec.QueryResult;
import dev.frierendb.engine.schema.Schema;
import dev.frierendb.engine.sql.Parser;
import dev.frierendb.engine.sql.ReadOnlyViolationException;
import dev.frierendb.engine.sql.Statement;
import dev.frierendb.engine.storage.BufferPool;
import dev.frierendb.engine.storage.Recovery;
import dev.frierendb.engine.storage.StorageException;
import dev.frierendb.engine.storage.Wal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * An embedded database in a directory, used through one session: statements run one at a time and
 * at most one transaction is open.
 *
 * <p><b>Durability and atomicity.</b> Opening the database first replays the write-ahead log, which
 * returns every committed transaction and removes the effects of one that was cut off by a crash.
 * Every INSERT, UPDATE and DELETE runs in a transaction: on its own it is automatically committed,
 * and if it fails part-way all of its changes are undone. {@code BEGIN} groups several statements;
 * {@code COMMIT} makes them durable together and {@code ROLLBACK} discards them. If a statement fails
 * inside an explicit transaction after it already changed data, the whole transaction is rolled back,
 * because a half-applied statement cannot be separated from the rest.
 *
 * <p><b>Limits.</b> CREATE and DROP (tables and indexes) are not transactional: they are refused inside
 * a transaction and run on their own after a checkpoint. There is a single session, so there is no
 * isolation to speak of; a read-only {@link #query} made while a transaction is open sees its
 * uncommitted changes.
 */
public final class Database implements AutoCloseable {

    private static final int DEFAULT_BUFFER_PAGES = 256;
    private static final long DEFAULT_CHECKPOINT_BYTES = 4L << 20;

    private final BufferPool pool;
    private final Wal wal;
    private final Catalog catalog;
    private final Executor executor;
    private final long checkpointBytes;
    private final Recovery.Report recovery;
    private boolean explicitTransaction;
    private boolean closed;

    private Database(BufferPool pool, Wal wal, Catalog catalog, long checkpointBytes, Recovery.Report recovery) {
        this.pool = pool;
        this.wal = wal;
        this.catalog = catalog;
        this.executor = new Executor(catalog);
        this.checkpointBytes = checkpointBytes;
        this.recovery = recovery;
    }

    public static Database open(Path directory) {
        return open(directory, DEFAULT_BUFFER_PAGES);
    }

    public static Database open(Path directory, int bufferPages) {
        return open(directory, bufferPages, DEFAULT_CHECKPOINT_BYTES);
    }

    /**
     * Opens the database, recovering from a crash if the log says one happened. The log is emptied by a
     * checkpoint once it grows beyond {@code checkpointBytes}.
     */
    public static Database open(Path directory, int bufferPages, long checkpointBytes) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new StorageException("Cannot create database directory " + directory, e);
        }
        Recovery.Report report = Recovery.recover(directory);
        BufferPool pool = new BufferPool(bufferPages);
        Wal wal = Wal.open(directory.resolve(Wal.FILE_NAME));
        try {
            pool.attachWal(wal);
            Catalog catalog = Catalog.open(directory, pool);
            return new Database(pool, wal, catalog, checkpointBytes, report);
        } catch (RuntimeException e) {
            wal.close();
            throw e;
        }
    }

    /** What crash recovery did when this database was opened. */
    public Recovery.Report recoveryReport() {
        return recovery;
    }

    public synchronized boolean inTransaction() {
        return explicitTransaction;
    }

    /** Runs any single statement without resource limits. */
    public synchronized QueryResult execute(String sql) {
        return run(Parser.parse(sql), QueryOptions.unlimited());
    }

    /**
     * Runs a SELECT or EXPLAIN under the given limits and rejects every other statement type,
     * including BEGIN, COMMIT and ROLLBACK. The check is made on the parsed statement, so it cannot
     * disagree with what would execute.
     */
    public synchronized QueryResult query(String sql, QueryOptions options) {
        requireOpen();
        Statement statement = Parser.parse(sql);
        if (!statement.isReadOnly()) {
            throw new ReadOnlyViolationException();
        }
        return executor.execute(statement, options);
    }

    /** Runs a semicolon-separated script and returns the number of statements executed. */
    public synchronized int executeScript(String script) {
        List<Statement> statements = Parser.parseScript(script);
        for (Statement statement : statements) {
            run(statement, QueryOptions.unlimited());
        }
        return statements.size();
    }

    public synchronized List<String> tableNames() {
        return catalog.tableNames();
    }

    public synchronized Schema tableSchema(String table) {
        return catalog.table(table).schema();
    }

    /** Definitions of the indexes on a table, ordered by index name. */
    public synchronized List<IndexInfo> tableIndexes(String table) {
        return catalog.table(table).indexes();
    }

    /** Bytes in the write-ahead log; an empty log is a few bytes of header. */
    public synchronized long logSize() {
        return pool.logSize();
    }

    /** Rolls back an open transaction, writes everything to the data files and closes them. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (explicitTransaction) {
                abort();
                explicitTransaction = false;
            }
            pool.checkpoint();
            catalog.close();
        } finally {
            wal.close();
        }
    }

    private QueryResult run(Statement statement, QueryOptions options) {
        requireOpen();
        return switch (statement) {
            case Statement.Begin b -> begin();
            case Statement.Commit c -> commit();
            case Statement.Rollback r -> rollback();
            case Statement.CreateTable c -> schemaChange(statement, options);
            case Statement.DropTable d -> schemaChange(statement, options);
            case Statement.CreateIndex c -> schemaChange(statement, options);
            case Statement.DropIndex d -> schemaChange(statement, options);
            case Statement.Insert i -> write(statement, options);
            case Statement.Update u -> write(statement, options);
            case Statement.Delete d -> write(statement, options);
            case Statement.Select s -> executor.execute(statement, options);
            case Statement.Explain e -> executor.execute(statement, options);
        };
    }

    private QueryResult begin() {
        if (explicitTransaction) {
            throw new TransactionException("A transaction is already in progress");
        }
        pool.begin();
        explicitTransaction = true;
        return QueryResult.affected(0);
    }

    private QueryResult commit() {
        requireExplicitTransaction("COMMIT");
        pool.commit();
        explicitTransaction = false;
        checkpointIfLogIsLarge();
        return QueryResult.affected(0);
    }

    private QueryResult rollback() {
        requireExplicitTransaction("ROLLBACK");
        abort();
        explicitTransaction = false;
        return QueryResult.affected(0);
    }

    /** Creating or dropping tables and indexes touches files outside the log, so it cannot be undone. */
    private QueryResult schemaChange(Statement statement, QueryOptions options) {
        if (explicitTransaction) {
            throw new TransactionException(
                    "CREATE and DROP cannot run inside a transaction; COMMIT or ROLLBACK first");
        }
        pool.checkpoint();
        QueryResult result = executor.execute(statement, options);
        catalog.flush();
        return result;
    }

    private QueryResult write(Statement statement, QueryOptions options) {
        if (explicitTransaction) {
            long before = pool.modifications();
            try {
                return executor.execute(statement, options);
            } catch (RuntimeException e) {
                if (pool.modifications() == before) {
                    throw e; // nothing was changed, so the transaction can carry on
                }
                abort();
                explicitTransaction = false;
                throw new TransactionException("The statement failed after changing data, so the whole "
                        + "transaction was rolled back: " + e.getMessage(), e);
            }
        }
        pool.begin();
        try {
            QueryResult result = executor.execute(statement, options);
            pool.commit();
            checkpointIfLogIsLarge();
            return result;
        } catch (RuntimeException e) {
            if (pool.inTransaction()) {
                try {
                    abort();
                } catch (RuntimeException abortFailure) {
                    e.addSuppressed(abortFailure);
                }
            }
            throw e;
        }
    }

    private void abort() {
        pool.rollback();
        catalog.reload();
    }

    private void checkpointIfLogIsLarge() {
        if (pool.logSize() > checkpointBytes) {
            pool.checkpoint();
        }
    }

    private void requireExplicitTransaction(String keyword) {
        if (!explicitTransaction) {
            throw new TransactionException(keyword + " without BEGIN: no transaction is in progress");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new FrierenException("The database is closed");
        }
    }
}
