package dev.frierendb.engine.exec;

import static dev.frierendb.engine.TestDatabases.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.Database;
import dev.frierendb.engine.TestDatabases;
import dev.frierendb.engine.sql.ReadOnlyViolationException;
import dev.frierendb.engine.sql.SqlException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuerySafetyTest {

    private static final QueryOptions SAFE = QueryOptions.bounded(100, Duration.ofSeconds(10));

    @TempDir
    Path dir;

    private Database db;

    @BeforeEach
    void setUp() {
        db = TestDatabases.university(dir);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private String count(String table) {
        return rows(db, "select count(*) from " + table).get(0).get(0);
    }

    @Test
    void readOnlyModeRejectsEveryModifyingStatement() {
        for (String sql : List.of(
                "insert into students values (9, 'x', 'y', 1.0)",
                "update students set name = 'x'",
                "delete from students",
                "drop table students",
                "create table t (a INT)")) {
            assertThrows(ReadOnlyViolationException.class, () -> db.query(sql, SAFE), sql);
        }
        assertEquals("6", count("students"));
        assertFalse(db.tableNames().contains("t"));
    }

    @Test
    void readOnlyModeAllowsSelectAndExplain() {
        assertEquals(6, db.query("select * from students", SAFE).rows().size());
        assertEquals(false, db.query("explain select * from students", SAFE).rows().isEmpty());
    }

    @Test
    void stackedStatementsAreRejectedByTheParserBeforeAnythingRuns() {
        SqlException e = assertThrows(SqlException.class,
                () -> db.query("select * from students; delete from students", SAFE));
        assertFalse(e instanceof ReadOnlyViolationException);
        assertThrows(SqlException.class, () -> db.query("select 1 from students -- ok\n; drop table students", SAFE));
        assertEquals("6", count("students"));
    }

    @Test
    void sqlInsideStringValuesIsJustData() {
        db.execute("insert into students values (50, 'x''; drop table students; --', 'CS', 1.0)");
        assertEquals("7", count("students"));
        assertEquals(List.of(List.of("x'; drop table students; --")),
                rows(db, "select name from students where sid = 50"));
    }

    @Test
    void rowCapTruncatesAndReportsIt() {
        QueryResult capped = db.query("select * from students order by sid", QueryOptions.bounded(2, Duration.ofSeconds(5)));
        assertEquals(2, capped.rows().size());
        assertTrue(capped.truncated());
        QueryResult exact = db.query("select * from students", QueryOptions.bounded(6, Duration.ofSeconds(5)));
        assertEquals(6, exact.rows().size());
        assertFalse(exact.truncated());
    }

    @Test
    void runawayCrossJoinIsStoppedByTheTimeout() {
        db.execute("create table big (n INT NOT NULL)");
        db.execute("insert into big values " + IntStream.range(0, 3000)
                .mapToObj(i -> "(" + i + ")").collect(Collectors.joining(", ")));
        long start = System.nanoTime();
        assertThrows(QueryLimitException.class, () -> db.query(
                "select count(*) from big a join big b on 1 = 1", QueryOptions.bounded(10, Duration.ofMillis(50))));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5, "the timeout must actually stop the query");
    }

    @Test
    void blockingOperatorsRespectTheMemoryCap() {
        db.execute("create table big (n INT NOT NULL)");
        db.execute("insert into big values " + IntStream.range(0, 50)
                .mapToObj(i -> "(" + i + ")").collect(Collectors.joining(", ")));
        QueryOptions tiny = new QueryOptions(100, Duration.ZERO, 10);
        assertThrows(QueryLimitException.class, () -> db.query("select n from big order by n", tiny));
        assertThrows(QueryLimitException.class, () -> db.query("select distinct n from big", tiny));
        assertThrows(QueryLimitException.class, () -> db.query("select n, count(*) from big group by n", tiny));
        assertEquals(10, db.query("select n from big limit 10", tiny).rows().size());
    }

    @Test
    void queriesStillWorkAfterALimitAbortedAPreviousOne() {
        db.execute("create table big (n INT NOT NULL)");
        db.execute("insert into big values " + IntStream.range(0, 2000)
                .mapToObj(i -> "(" + i + ")").collect(Collectors.joining(", ")));
        assertThrows(QueryLimitException.class, () -> db.query(
                "select count(*) from big a join big b on 1 = 1", QueryOptions.bounded(10, Duration.ofMillis(20))));
        assertEquals(List.of(List.of("2000")), rows(db, "select count(*) from big"));
    }
}
