package dev.frierendb.engine.exec;

import static dev.frierendb.engine.TestDatabases.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.Database;
import dev.frierendb.engine.sql.SqlException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlIndexPlanTest {

    @TempDir
    Path dir;

    private Database db;

    @BeforeEach
    void setUp() {
        db = Database.open(dir, 64);
        db.execute("CREATE TABLE t (id INT NOT NULL, a INT, d DOUBLE, s TEXT, b BOOLEAN)");
        db.execute("CREATE TABLE u (id INT NOT NULL, tid INT, note TEXT)");
        Random random = new Random(11);
        StringBuilder t = new StringBuilder("INSERT INTO t VALUES ");
        for (int i = 0; i < 1500; i++) {
            String a = random.nextInt(10) == 0 ? "NULL" : Integer.toString(random.nextInt(60) - 10);
            String d = random.nextInt(10) == 0 ? "NULL" : Double.toString(random.nextInt(400) / 8.0 - 5);
            String s = random.nextInt(10) == 0 ? "NULL" : "'k" + random.nextInt(80) + "'";
            String b = random.nextInt(10) == 0 ? "NULL" : Boolean.toString(random.nextBoolean());
            t.append(i == 0 ? "" : ", ").append("(").append(i).append(", ").append(a).append(", ").append(d)
                    .append(", ").append(s).append(", ").append(b).append(")");
        }
        db.execute(t.toString());
        StringBuilder u = new StringBuilder("INSERT INTO u VALUES ");
        for (int i = 0; i < 400; i++) {
            u.append(i == 0 ? "" : ", ").append("(").append(i).append(", ").append(random.nextInt(1700))
                    .append(", 'n").append(random.nextInt(20)).append("')");
        }
        db.execute(u.toString());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private String plan(String sql) {
        return String.join("\n", rows(db, "EXPLAIN " + sql).stream().map(r -> r.get(0)).toList());
    }

    private List<List<String>> sorted(String sql) {
        List<List<String>> result = new ArrayList<>(rows(db, sql));
        result.sort((x, y) -> x.toString().compareTo(y.toString()));
        return result;
    }

    @Test
    void equalityAndRangePredicatesOnAnIndexedColumnUseIndexScan() {
        db.execute("CREATE INDEX idx_a ON t (a)");
        db.execute("CREATE INDEX idx_s ON t (s)");
        db.execute("CREATE INDEX idx_d ON t (d)");
        assertTrue(plan("SELECT * FROM t WHERE a = 5").contains("IndexScan t using idx_a (a = 5)"));
        assertTrue(plan("SELECT * FROM t WHERE 5 = a").contains("IndexScan t using idx_a (a = 5)"));
        assertTrue(plan("SELECT * FROM t WHERE a >= 5 AND a < 9").contains("IndexScan t using idx_a (a >= 5 AND a < 9)"));
        assertTrue(plan("SELECT * FROM t WHERE 9 > a").contains("(a < 9)"));
        assertTrue(plan("SELECT * FROM t WHERE a BETWEEN 3 AND 7").contains("(a >= 3 AND a <= 7)"));
        assertTrue(plan("SELECT * FROM t WHERE s = 'k7'").contains("IndexScan t using idx_s (s = 'k7')"));
        assertTrue(plan("SELECT * FROM t WHERE d > 1").contains("(d > 1.0)"), "an INT constant is converted for a DOUBLE column");
        assertTrue(plan("SELECT * FROM t WHERE a > 2 + 3").contains("(a > 5)"), "constant expressions are folded");
        assertTrue(plan("SELECT * FROM t AS x WHERE x.a = 5").contains("IndexScan t AS x using idx_a"));
        assertTrue(plan("SELECT * FROM t WHERE a > 3 AND a > 8").contains("(a > 8)"), "the tighter bound wins");
    }

    @Test
    void theWholeWhereClauseIsStillAppliedAboveTheIndexScan() {
        db.execute("CREATE INDEX idx_a ON t (a)");
        String plan = plan("SELECT * FROM t WHERE a = 5 AND s = 'k1'");
        assertTrue(plan.contains("Filter"), plan);
        assertTrue(plan.contains("IndexScan"), plan);
    }

    @Test
    void anEqualityBeatsARangeAndMoreBoundsBeatFewer() {
        db.execute("CREATE INDEX idx_a ON t (a)");
        db.execute("CREATE INDEX idx_d ON t (d)");
        assertTrue(plan("SELECT * FROM t WHERE a > 0 AND d = 2.5").contains("using idx_d (d = 2.5)"));
        assertTrue(plan("SELECT * FROM t WHERE a > 0 AND d > 0 AND d < 9").contains("using idx_d"));
    }

    @Test
    void predicatesThatCannotUseAnIndexFallBackToAFullScan() {
        db.execute("CREATE INDEX idx_a ON t (a)");
        db.execute("CREATE INDEX idx_s ON t (s)");
        for (String where : List.of(
                "a = 5 OR a = 6",
                "NOT a = 5",
                "a <> 5",
                "a + 1 = 6",
                "a = id",
                "a = NULL",
                "a IS NULL",
                "a IN (1, 2)",
                "lower(s) = 'k1'",
                "s LIKE 'k1%'",
                "d = 1.5",
                "a = 2.5",
                "b = TRUE")) {
            String plan = plan("SELECT * FROM t WHERE " + where);
            assertFalse(plan.contains("IndexScan"), where + "\n" + plan);
            assertTrue(plan.contains("full table scan"), where);
        }
        assertTrue(plan("SELECT * FROM t").contains("full table scan"));
    }

    @Test
    void aTypeMismatchStillReportsTheSameErrorAsAScan() {
        db.execute("CREATE INDEX idx_a ON t (a)");
        assertThrows(SqlException.class, () -> rows(db, "SELECT * FROM t WHERE a = 'five'"));
        assertThrows(SqlException.class, () -> rows(db, "SELECT * FROM t WHERE a = 1 / 0"));
    }

    @Test
    void onlyTheFromTableOfAJoinIsNarrowed() {
        db.execute("CREATE INDEX idx_a ON t (a)");
        db.execute("CREATE INDEX idx_tid ON u (tid)");
        String plan = plan("SELECT t.id FROM t JOIN u ON u.tid = t.id WHERE t.a = 5 AND u.tid > 3");
        assertTrue(plan.contains("IndexScan t using idx_a"), plan);
        assertFalse(plan.contains("idx_tid"), plan);
        String left = plan("SELECT t.id FROM t LEFT JOIN u ON u.tid = t.id WHERE u.tid = 3");
        assertFalse(left.contains("IndexScan"), left);
    }

    @Test
    void indexedAndFullScanResultsAreIdenticalForRandomPredicates() {
        Random random = new Random(99);
        List<String> queries = new ArrayList<>();
        String[] numeric = {"a", "d"};
        String[] ops = {"=", "<", "<=", ">", ">="};
        for (int i = 0; i < 150; i++) {
            String column = numeric[random.nextInt(2)];
            String literal = column.equals("a")
                    ? Integer.toString(random.nextInt(80) - 15)
                    : (random.nextBoolean() ? Integer.toString(random.nextInt(60) - 8) : (random.nextInt(480) / 8.0 - 6) + "");
            String where = column + " " + ops[random.nextInt(ops.length)] + " " + literal;
            if (random.nextBoolean()) {
                where += " AND " + column + " " + ops[1 + random.nextInt(4)] + " " + (random.nextInt(70) - 12);
            }
            if (random.nextInt(4) == 0) {
                where += " AND s >= 'k" + random.nextInt(80) + "'";
            }
            if (random.nextInt(5) == 0) {
                where = "(" + where + ") OR id = " + random.nextInt(1500);
            }
            queries.add("SELECT id, a, d, s FROM t WHERE " + where);
        }
        for (int i = 0; i < 60; i++) {
            String key = "k" + random.nextInt(90);
            queries.add("SELECT id FROM t WHERE s " + ops[random.nextInt(ops.length)] + " '" + key + "'");
        }
        for (int i = 0; i < 40; i++) {
            int lo = random.nextInt(60) - 10;
            queries.add("SELECT t.id, u.id FROM t JOIN u ON u.tid = t.id WHERE t.a >= " + lo + " AND t.a <= " + (lo + random.nextInt(15)));
            queries.add("SELECT t.id, u.note FROM t LEFT JOIN u ON u.tid = t.id WHERE t.a = " + lo);
        }
        queries.add("SELECT a, count(*) FROM t WHERE a > 10 GROUP BY a");
        queries.add("SELECT DISTINCT s FROM t WHERE s > 'k50' AND s < 'k70'");
        queries.add("SELECT id FROM t WHERE a > 30 ORDER BY d DESC, id LIMIT 7");

        List<List<List<String>>> expected = new ArrayList<>();
        for (String q : queries) {
            expected.add(sorted(q));
        }
        db.execute("CREATE INDEX idx_a ON t (a)");
        db.execute("CREATE INDEX idx_d ON t (d)");
        db.execute("CREATE INDEX idx_s ON t (s)");
        db.execute("CREATE INDEX idx_tid ON u (tid)");
        int indexed = 0;
        for (int i = 0; i < queries.size(); i++) {
            assertEquals(expected.get(i), sorted(queries.get(i)), queries.get(i));
            if (plan(queries.get(i)).contains("IndexScan")) {
                indexed++;
            }
        }
        assertTrue(indexed > queries.size() / 2, "most of these queries should have used an index: " + indexed);
    }

    @Test
    void orderByStillSortsRowsThatAnIndexScanReturnedInKeyOrder() {
        db.execute("CREATE INDEX idx_a ON t (a)");
        List<List<String>> viaIndex = rows(db, "SELECT id FROM t WHERE a >= 20 ORDER BY id");
        List<List<String>> sortedIds = new ArrayList<>(viaIndex);
        sortedIds.sort((x, y) -> Integer.compare(Integer.parseInt(x.get(0)), Integer.parseInt(y.get(0))));
        assertEquals(sortedIds, viaIndex);
        assertFalse(viaIndex.isEmpty());
    }

    @Test
    void updateAndDeleteUseTheIndexAndKeepItConsistent() {
        Database oracle = Database.open(dir.resolve("oracle"), 64);
        oracle.execute("CREATE TABLE t (id INT NOT NULL, a INT, d DOUBLE, s TEXT, b BOOLEAN)");
        for (List<String> row : rows(db, "SELECT id, a, d, s, b FROM t ORDER BY id")) {
            oracle.execute("INSERT INTO t VALUES (" + row.get(0) + ", " + row.get(1) + ", " + row.get(2) + ", "
                    + (row.get(3).equals("NULL") ? "NULL" : "'" + row.get(3) + "'") + ", " + row.get(4) + ")");
        }
        db.execute("CREATE INDEX idx_a ON t (a)");
        db.execute("CREATE INDEX idx_s ON t (s)");
        Random random = new Random(5);
        for (int step = 0; step < 120; step++) {
            String statement = switch (random.nextInt(4)) {
                case 0 -> "UPDATE t SET a = " + random.nextInt(60) + " WHERE a = " + random.nextInt(50);
                case 1 -> "UPDATE t SET s = 'k" + random.nextInt(80) + "' WHERE a >= " + random.nextInt(60)
                        + " AND a < " + (random.nextInt(10) + 50);
                case 2 -> "DELETE FROM t WHERE a > " + (45 + random.nextInt(14));
                default -> "INSERT INTO t VALUES (" + (2000 + step) + ", " + random.nextInt(60) + ", 1.5, 'k"
                        + random.nextInt(80) + "', TRUE)";
            };
            assertEquals(oracle.execute(statement).affectedRows(), db.execute(statement).affectedRows(), statement);
        }
        List<List<String>> all = rows(oracle, "SELECT id, a, d, s, b FROM t ORDER BY id");
        assertEquals(all, rows(db, "SELECT id, a, d, s, b FROM t ORDER BY id"));
        for (int a = -2; a < 62; a += 3) {
            assertEquals(sortedOn(oracle, "SELECT id FROM t WHERE a + 0 = " + a),
                    sortedOn(db, "SELECT id FROM t WHERE a = " + a), "a = " + a);
        }
        for (int k = 0; k < 80; k += 7) {
            assertEquals(sortedOn(oracle, "SELECT id FROM t WHERE coalesce(s, s) >= 'k" + k + "' AND coalesce(s, s) < 'k" + (k + 9) + "'"),
                    sortedOn(db, "SELECT id FROM t WHERE s >= 'k" + k + "' AND s < 'k" + (k + 9) + "'"), "s from k" + k);
        }
        oracle.close();
    }

    private static List<List<String>> sortedOn(Database database, String sql) {
        List<List<String>> result = new ArrayList<>(rows(database, sql));
        Collections.sort(result, (x, y) -> x.toString().compareTo(y.toString()));
        return result;
    }
}
