package dev.frierendb.engine.exec;

import static dev.frierendb.engine.TestDatabases.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.Database;
import dev.frierendb.engine.FrierenException;
import dev.frierendb.engine.TransactionException;
import dev.frierendb.engine.sql.ReadOnlyViolationException;
import dev.frierendb.engine.storage.StorageException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlTransactionTest {

    private static final int ROWS = 600;

    @TempDir
    Path dir;

    private Database db;

    @BeforeEach
    void setUp() {
        db = Database.open(dir, 8);
        db.execute("CREATE TABLE t (id INT NOT NULL, v INT, s TEXT)");
        StringBuilder insert = new StringBuilder("INSERT INTO t VALUES ");
        for (int i = 0; i < ROWS; i++) {
            insert.append(i == 0 ? "" : ", ").append("(").append(i).append(", ").append(i % 50).append(", '")
                    .append("row-").append(i).append("-").append("x".repeat(40)).append("')");
        }
        db.execute(insert.toString());
        db.execute("CREATE INDEX idx_v ON t (v)");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private String one(String sql) {
        return rows(db, sql).get(0).get(0);
    }

    /** Index and heap must agree: the same count whether or not the index is usable. */
    private void assertIndexMatchesTable() {
        for (int v = 0; v < 55; v += 7) {
            assertEquals(one("select count(*) from t where v + 0 = " + v), one("select count(*) from t where v = " + v),
                    "v = " + v);
        }
        assertEquals(one("select count(*) from t where v + 0 >= 10"), one("select count(*) from t where v >= 10"));
    }

    @Test
    void committedChangesAreVisibleAndSurviveReopening() {
        db.execute("BEGIN");
        assertTrue(db.inTransaction());
        db.execute("INSERT INTO t VALUES (1000, 7, 'new')");
        db.execute("UPDATE t SET s = 'changed' WHERE id = 5");
        db.execute("DELETE FROM t WHERE id = 6");
        db.execute("COMMIT");
        assertFalse(db.inTransaction());
        db.close();
        db = Database.open(dir, 8);
        assertEquals(String.valueOf(ROWS), one("select count(*) from t"));
        assertEquals("changed", one("select s from t where id = 5"));
        assertEquals("new", one("select s from t where id = 1000"));
        assertEquals("0", one("select count(*) from t where id = 6"));
        assertIndexMatchesTable();
    }

    @Test
    void rollbackUndoesInsertsUpdatesAndDeletesIncludingTheIndex() {
        List<List<String>> before = rows(db, "select id, v, s from t order by id");
        db.execute("BEGIN TRANSACTION");
        StringBuilder many = new StringBuilder("INSERT INTO t VALUES ");
        for (int i = 0; i < 300; i++) {
            many.append(i == 0 ? "" : ", ").append("(").append(5000 + i).append(", ").append(i % 9).append(", '")
                    .append("y".repeat(100)).append("')");
        }
        db.execute(many.toString());
        db.execute("UPDATE t SET v = v + 1, s = 'moved' WHERE v < 20");
        db.execute("DELETE FROM t WHERE id >= 300 AND id < 400");
        assertEquals("1", one("select count(*) from t where id = 5001"), "the transaction sees its own changes");
        db.execute("ROLLBACK");
        assertFalse(db.inTransaction());
        assertEquals(before, rows(db, "select id, v, s from t order by id"));
        assertIndexMatchesTable();
        db.execute("INSERT INTO t VALUES (9000, 3, 'after')");
        assertEquals(String.valueOf(ROWS + 1), one("select count(*) from t"));
        assertIndexMatchesTable();
    }

    @Test
    void rolledBackChangesStayGoneAfterReopening() {
        db.execute("BEGIN");
        db.execute("DELETE FROM t WHERE v < 25");
        db.execute("ROLLBACK");
        db.close();
        db = Database.open(dir, 8);
        assertEquals(String.valueOf(ROWS), one("select count(*) from t"));
        assertIndexMatchesTable();
    }

    @Test
    void spaceFreedInARolledBackTransactionIsNotLost() {
        db.execute("BEGIN");
        db.execute("DELETE FROM t");
        assertEquals("0", one("select count(*) from t"));
        db.execute("ROLLBACK");
        assertEquals(String.valueOf(ROWS), one("select count(*) from t"));
        db.execute("DELETE FROM t WHERE id < 300");
        db.execute("INSERT INTO t VALUES (7000, 1, 'fits into freed space')");
        assertEquals(String.valueOf(ROWS - 300 + 1), one("select count(*) from t"));
    }

    @Test
    void pagesAllocatedByARolledBackTransactionLeaveNoStaleFreeSpaceBehind() {
        db.execute("CREATE TABLE e (id INT NOT NULL, v INT, s TEXT)");
        db.execute("CREATE INDEX idx_e ON e (v)");
        db.execute("BEGIN");
        for (int i = 0; i < 60; i++) {
            db.execute("INSERT INTO e VALUES (" + i + ", " + i + ", '" + "p".repeat(300) + "')");
        }
        db.execute("ROLLBACK");
        assertEquals("0", one("select count(*) from e"));
        db.execute("INSERT INTO e VALUES (1, 1, 'first row after rollback')");
        assertEquals("1", one("select count(*) from e"));
        assertEquals("1", one("select count(*) from e where v = 1"));
        db.close();
        db = Database.open(dir, 8);
        assertEquals("first row after rollback", one("select s from e where v = 1"));
    }

    @Test
    void misuseOfTransactionControlIsReported() {
        assertThrows(TransactionException.class, () -> db.execute("COMMIT"));
        assertThrows(TransactionException.class, () -> db.execute("ROLLBACK"));
        db.execute("BEGIN");
        assertThrows(TransactionException.class, () -> db.execute("BEGIN"));
        assertTrue(db.inTransaction(), "the failed BEGIN did not end the transaction");
        db.execute("COMMIT");
    }

    @Test
    void createAndDropAreRefusedInsideATransactionWhichCarriesOn() {
        db.execute("BEGIN");
        db.execute("INSERT INTO t VALUES (2000, 1, 'kept')");
        assertThrows(TransactionException.class, () -> db.execute("CREATE TABLE u (a INT)"));
        assertThrows(TransactionException.class, () -> db.execute("DROP TABLE t"));
        assertThrows(TransactionException.class, () -> db.execute("CREATE INDEX i ON t (id)"));
        assertThrows(TransactionException.class, () -> db.execute("DROP INDEX idx_v"));
        assertTrue(db.inTransaction());
        db.execute("COMMIT");
        assertEquals("1", one("select count(*) from t where id = 2000"));
        db.execute("CREATE TABLE u (a INT)");
    }

    @Test
    void aFailureThatChangedNothingLeavesTheTransactionOpen() {
        db.execute("BEGIN");
        db.execute("INSERT INTO t VALUES (3000, 1, 'first')");
        assertThrows(FrierenException.class, () -> db.execute("UPDATE t SET nope = 1"));
        assertThrows(FrierenException.class, () -> db.execute("INSERT INTO t VALUES (1)"));
        assertThrows(FrierenException.class, () -> db.execute("DELETE FROM t WHERE v = 'text'"));
        assertTrue(db.inTransaction());
        db.execute("INSERT INTO t VALUES (3001, 1, 'second')");
        db.execute("COMMIT");
        assertEquals("2", one("select count(*) from t where id >= 3000"));
    }

    @Test
    void aFailureAfterChangingDataRollsBackTheWholeTransaction() {
        db.execute("BEGIN");
        db.execute("INSERT INTO t VALUES (3000, 1, 'earlier statement')");
        String tooBig = "z".repeat(5000);
        TransactionException e = assertThrows(TransactionException.class, () -> db.execute(
                "INSERT INTO t VALUES (3001, 1, 'ok'), (3002, 1, '" + tooBig + "')"));
        assertTrue(e.getMessage().contains("rolled back"), e.getMessage());
        assertTrue(e.getCause() instanceof StorageException);
        assertFalse(db.inTransaction());
        assertEquals(String.valueOf(ROWS), one("select count(*) from t"), "the earlier statement was undone too");
        assertThrows(TransactionException.class, () -> db.execute("COMMIT"));
        assertIndexMatchesTable();
    }

    @Test
    void aStatementThatFailsPartWayIsUndoneEntirely() {
        String tooBig = "z".repeat(5000);
        assertThrows(StorageException.class, () -> db.execute(
                "INSERT INTO t VALUES (4000, 1, 'a'), (4001, 1, 'b'), (4002, 1, '" + tooBig + "')"));
        assertEquals(String.valueOf(ROWS), one("select count(*) from t"));
        assertEquals("0", one("select count(*) from t where id >= 4000"));
        assertIndexMatchesTable();
        db.execute("INSERT INTO t VALUES (4000, 1, 'a')");
        assertEquals(String.valueOf(ROWS + 1), one("select count(*) from t"));
    }

    @Test
    void anUpdateThatCannotFitIsUndoneEntirely() {
        String tooBig = "z".repeat(5000);
        assertThrows(StorageException.class, () -> db.execute("UPDATE t SET s = '" + tooBig + "' WHERE id >= 10 AND id < 20"));
        assertEquals("0", one("select count(*) from t where s = '" + tooBig + "'"));
        assertEquals("row-12-" + "x".repeat(40), one("select s from t where id = 12"));
        assertIndexMatchesTable();
    }

    @Test
    void readOnlyQueriesCannotControlTransactionsButDoSeeOpenChanges() {
        assertThrows(ReadOnlyViolationException.class, () -> db.query("BEGIN", QueryOptions.unlimited()));
        assertThrows(ReadOnlyViolationException.class, () -> db.query("COMMIT", QueryOptions.unlimited()));
        assertThrows(ReadOnlyViolationException.class, () -> db.query("ROLLBACK", QueryOptions.unlimited()));
        assertFalse(db.inTransaction());
        db.execute("BEGIN");
        db.execute("INSERT INTO t VALUES (6000, 1, 'pending')");
        assertEquals("1", db.query("select count(*) from t where id = 6000", QueryOptions.unlimited())
                .rows().get(0).get(0).toString(), "single session: pending changes are visible to queries");
        db.execute("ROLLBACK");
    }

    @Test
    void closingWithAnOpenTransactionDiscardsIt() {
        db.execute("BEGIN");
        db.execute("DELETE FROM t");
        db.close();
        db = Database.open(dir, 8);
        assertEquals(String.valueOf(ROWS), one("select count(*) from t"));
        assertFalse(db.inTransaction());
    }

    @Test
    void scriptsCanContainTransactions() {
        db.executeScript("BEGIN; INSERT INTO t VALUES (8000, 1, 'a'); INSERT INTO t VALUES (8001, 1, 'b'); ROLLBACK;"
                + " BEGIN; INSERT INTO t VALUES (8002, 1, 'c'); COMMIT;");
        assertEquals("0", one("select count(*) from t where id in (8000, 8001)"));
        assertEquals("1", one("select count(*) from t where id = 8002"));
    }

    @Test
    void theLogIsCheckpointedOnceItGrowsPastTheThreshold() {
        db.close();
        db = Database.open(dir, 8, 60_000);
        long largest = 0;
        for (int i = 0; i < 200; i++) {
            db.execute("INSERT INTO t VALUES (" + (10_000 + i) + ", " + (i % 10) + ", 'x')");
            largest = Math.max(largest, db.logSize());
        }
        assertTrue(largest < 60_000 + 40_000, "the log stays near the threshold: " + largest);
        assertTrue(largest > 8, "commits do use the log");
        assertEquals(String.valueOf(ROWS + 200), one("select count(*) from t"));
        assertIndexMatchesTable();
    }

    @Test
    void closingEmptiesTheLog() {
        db.execute("INSERT INTO t VALUES (9100, 1, 'x')");
        assertTrue(db.logSize() > 8);
        db.close();
        db = Database.open(dir, 8);
        assertEquals(8, db.logSize());
        assertFalse(db.recoveryReport().didWork(), "a clean close needs no recovery");
    }

    @Test
    void useAfterCloseIsRejected() {
        db.close();
        assertThrows(FrierenException.class, () -> db.execute("select 1 from t"));
        assertThrows(FrierenException.class,
                () -> db.query("select id from t", QueryOptions.unlimited()));
        db.close();
        db = Database.open(dir, 8);
    }
}
