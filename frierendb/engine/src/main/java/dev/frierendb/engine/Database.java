package dev.frierendb.engine;

import dev.frierendb.engine.catalog.Catalog;
import dev.frierendb.engine.exec.Executor;
import dev.frierendb.engine.exec.QueryOptions;
import dev.frierendb.engine.exec.QueryResult;
import dev.frierendb.engine.schema.Schema;
import dev.frierendb.engine.sql.Parser;
import dev.frierendb.engine.sql.ReadOnlyViolationException;
import dev.frierendb.engine.sql.Statement;
import dev.frierendb.engine.storage.BufferPool;
import java.nio.file.Path;
import java.util.List;

/**
 * An embedded database in a directory. Statements run one at a time. Data is flushed after every
 * modifying statement, but there is no write-ahead log yet, so a crash mid-statement can corrupt it.
 */
public final class Database implements AutoCloseable {

    private static final int DEFAULT_BUFFER_PAGES = 256;

    private final Catalog catalog;
    private final Executor executor;

    private Database(Catalog catalog) {
        this.catalog = catalog;
        this.executor = new Executor(catalog);
    }

    public static Database open(Path directory) {
        return open(directory, DEFAULT_BUFFER_PAGES);
    }

    public static Database open(Path directory, int bufferPages) {
        return new Database(Catalog.open(directory, new BufferPool(bufferPages)));
    }

    /** Runs any single statement without resource limits. */
    public synchronized QueryResult execute(String sql) {
        return executor.execute(Parser.parse(sql), QueryOptions.unlimited());
    }

    /**
     * Runs a SELECT or EXPLAIN under the given limits and rejects every other statement type.
     * The check is made on the parsed statement, so it cannot disagree with what would execute.
     */
    public synchronized QueryResult query(String sql, QueryOptions options) {
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
            executor.execute(statement, QueryOptions.unlimited());
        }
        return statements.size();
    }

    public synchronized List<String> tableNames() {
        return catalog.tableNames();
    }

    public synchronized Schema tableSchema(String table) {
        return catalog.table(table).schema();
    }

    @Override
    public synchronized void close() {
        catalog.close();
    }
}
