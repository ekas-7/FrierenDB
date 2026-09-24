package dev.frierendb.engine;

import static dev.frierendb.engine.TestDatabases.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.storage.Recovery;
import dev.frierendb.engine.storage.Wal;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A crash is simulated by copying the database directory while the database is open and never
 * closing it: the copy holds exactly the bytes the operating system had been given, with data pages
 * possibly stale and the log forced up to the last commit.
 */
class CrashRecoveryTest {

    private static final String SNAPSHOT = "select id, v, w from t order by id";

    @TempDir
    Path root;

    private Path live;
    private Database db;
    private int copies;

    @BeforeEach
    void setUp() throws IOException {
        live = Files.createDirectory(root.resolve("live"));
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private void openLive(int bufferPages, long checkpointBytes) {
        db = Database.open(live, bufferPages, checkpointBytes);
    }

    private void createTable(int rowCount) {
        db.execute("CREATE TABLE t (id INT NOT NULL, v INT, w INT, s TEXT)");
        StringBuilder insert = new StringBuilder("INSERT INTO t VALUES ");
        for (int i = 0; i < rowCount; i++) {
            insert.append(i == 0 ? "" : ", ").append("(").append(i).append(", ").append(i % 40).append(", 0, '")
                    .append("s".repeat(30)).append("')");
        }
        db.execute(insert.toString());
        db.execute("CREATE INDEX idx_v ON t (v)");
    }

    private Path crash() throws IOException {
        Path copy = Files.createDirectory(root.resolve("crash-" + copies++));
        try (Stream<Path> files = Files.list(live)) {
            for (Path source : files.toList()) {
                Files.copy(source, copy.resolve(source.getFileName()));
            }
        }
        return copy;
    }

    private static void truncate(Path file, long length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(length);
        }
    }

    private static List<List<String>> recoveredSnapshot(Path directory, int bufferPages) {
        try (Database recovered = Database.open(directory, bufferPages)) {
            assertIndexMatchesTable(recovered);
            return rows(recovered, SNAPSHOT);
        }
    }

    private static void assertIndexMatchesTable(Database database) {
        for (int v = 0; v < 45; v += 4) {
            assertEquals(rows(database, "select count(*) from t where v + 0 = " + v),
                    rows(database, "select count(*) from t where v = " + v), "v = " + v);
        }
        assertEquals(rows(database, "select count(*) from t where v + 0 >= 20"),
                rows(database, "select count(*) from t where v >= 20"));
    }

    @Test
    void committedStatementsSurviveACrashThatLostTheDataPages() throws IOException {
        openLive(256, 1L << 30);
        createTable(500);
        db.execute("INSERT INTO t VALUES (1000, 5, 1, 'late')");
        db.execute("UPDATE t SET w = w + 1 WHERE id < 100");
        db.execute("DELETE FROM t WHERE id >= 400 AND id < 450");
        List<List<String>> expected = rows(db, SNAPSHOT);
        Path crashed = crash();

        try (Database recovered = Database.open(crashed, 256)) {
            Recovery.Report report = recovered.recoveryReport();
            assertTrue(report.didWork());
            assertTrue(report.committedTransactions() >= 3, report.toString());
            assertEquals(0, report.unfinishedTransactions());
            assertEquals(expected, rows(recovered, SNAPSHOT));
            assertIndexMatchesTable(recovered);
        }
    }

    @Test
    void anOpenTransactionIsUndoneEvenWhenItsPagesWereAlreadyWritten() throws IOException {
        openLive(8, 1L << 30);
        createTable(600);
        List<List<String>> before = rows(db, SNAPSHOT);
        db.execute("BEGIN");
        db.execute("UPDATE t SET v = v + 1, w = w + 7");
        db.execute("DELETE FROM t WHERE id % 3 = 0");
        StringBuilder insert = new StringBuilder("INSERT INTO t VALUES ");
        for (int i = 0; i < 200; i++) {
            insert.append(i == 0 ? "" : ", ").append("(").append(2000 + i).append(", ").append(i % 17)
                    .append(", 1, '").append("n".repeat(150)).append("')");
        }
        db.execute(insert.toString());
        Path crashed = crash();

        try (Database recovered = Database.open(crashed, 8)) {
            Recovery.Report report = recovered.recoveryReport();
            assertEquals(1, report.unfinishedTransactions());
            assertTrue(report.pagesUndone() > 0, "with a pool of 8 pages, changes were written before commit");
            assertEquals(before, rows(recovered, SNAPSHOT));
            assertIndexMatchesTable(recovered);
        }
    }

    @Test
    void aCommittedTransactionSurvivesEvenIfItsPagesWereStolenAndNeverFlushed() throws IOException {
        openLive(8, 1L << 30);
        createTable(600);
        db.execute("BEGIN");
        db.execute("UPDATE t SET w = w + 5");
        db.execute("DELETE FROM t WHERE id >= 500");
        db.execute("COMMIT");
        List<List<String>> expected = rows(db, SNAPSHOT);
        Path crashed = crash();

        try (Database recovered = Database.open(crashed, 8)) {
            assertEquals(expected, rows(recovered, SNAPSHOT));
            assertTrue(recovered.recoveryReport().committedTransactions() >= 1);
            assertIndexMatchesTable(recovered);
        }
    }

    @Test
    void aRecoveredDatabaseIsOrdinaryAndNeedsNoRecoveryTheSecondTime() throws IOException {
        openLive(8, 1L << 30);
        createTable(300);
        db.execute("BEGIN");
        db.execute("DELETE FROM t");
        Path crashed = crash();

        try (Database recovered = Database.open(crashed, 8)) {
            assertEquals("300", rows(recovered, "select count(*) from t").get(0).get(0));
            recovered.execute("INSERT INTO t VALUES (9999, 1, 1, 'after recovery')");
        }
        try (Database again = Database.open(crashed, 8)) {
            assertFalse(again.recoveryReport().didWork());
            assertEquals("301", rows(again, "select count(*) from t").get(0).get(0));
            assertIndexMatchesTable(again);
        }
    }

    @Test
    void recoveringTwiceInARowGivesTheSameResult() throws IOException {
        openLive(8, 1L << 30);
        createTable(400);
        db.execute("UPDATE t SET w = 3 WHERE id < 200");
        db.execute("BEGIN");
        db.execute("UPDATE t SET w = 9");
        Path crashed = crash();
        Path again = Files.createDirectory(root.resolve("again"));
        try (Stream<Path> files = Files.list(crashed)) {
            for (Path source : files.toList()) {
                Files.copy(source, again.resolve(source.getFileName()));
            }
        }
        // Recover by hand twice on the same files: the second pass must find nothing to do.
        Recovery.recover(crashed);
        Recovery.Report second = Recovery.recover(crashed);
        assertFalse(second.didWork());
        assertEquals(recoveredSnapshot(again, 8), recoveredSnapshot(crashed, 8));
    }

    @Test
    void theLogIsCheckpointedSoLongRunsDoNotNeedAnUnboundedLog() throws IOException {
        openLive(16, 50_000);
        createTable(200);
        for (int i = 0; i < 300; i++) {
            db.execute("INSERT INTO t VALUES (" + (5000 + i) + ", " + (i % 40) + ", 0, 'x')");
        }
        assertTrue(db.logSize() < 50_000 + 20_000, "log size " + db.logSize());
        List<List<String>> expected = rows(db, SNAPSHOT);
        assertEquals(500, expected.size());
        assertEquals(expected, recoveredSnapshot(crash(), 16));
    }

    @Test
    void everyWayTheLogCanBeCutLeavesAllOrNothingOfTheTransaction() throws IOException {
        openLive(256, 1L << 30);
        createTable(600);
        db.close();
        openLive(256, 1L << 30); // a clean start: empty log, data files fully written
        List<List<String>> before = rows(db, SNAPSHOT);

        db.execute("BEGIN");
        db.execute("UPDATE t SET w = w + 1 WHERE id < 300");
        db.execute("UPDATE t SET w = w + 10 WHERE id >= 300");
        db.execute("COMMIT");
        List<List<String>> after = rows(db, SNAPSHOT);
        assertFalse(before.equals(after));

        Path crashed = crash();
        Path log = crashed.resolve(Wal.FILE_NAME);
        Wal.Contents full = Wal.read(log);
        assertTrue(full.records().stream().noneMatch(r -> r.type() == Wal.Type.UPDATE && r.before() == null),
                "this scenario must not grow files, or a short log would be paired with a grown data file");
        assertEquals(Wal.Type.COMMIT, full.records().get(full.records().size() - 1).type());
        long length = Files.size(log);
        assertEquals(length, full.validBytes());

        List<Long> cuts = new ArrayList<>(List.of(0L, 3L, 8L, 9L, length - 1, length - 2, length - 8, length));
        for (long cut = 40; cut < length; cut += 251) {
            cuts.add(cut);
        }
        for (long cut : cuts) {
            Path attempt = Files.createDirectory(root.resolve("cut-" + cut));
            try (Stream<Path> files = Files.list(crashed)) {
                for (Path source : files.toList()) {
                    Files.copy(source, attempt.resolve(source.getFileName()));
                }
            }
            truncate(attempt.resolve(Wal.FILE_NAME), cut);
            List<List<String>> got = recoveredSnapshot(attempt, 256);
            assertEquals(cut == length ? after : before, got, "log cut at " + cut + " of " + length);
        }
    }

    /**
     * Random transactions on a tiny buffer pool, crashing at random moments. After each crash the
     * recovered database must equal the state after the last commit, as tracked by a plain map.
     */
    @Test
    void randomWorkloadsRecoverToTheLastCommittedState() throws IOException {
        for (long seed = 1; seed <= 3; seed++) {
            Path dir = Files.createDirectory(root.resolve("random-" + seed));
            Random random = new Random(seed);
            Map<Integer, int[]> committed = new TreeMap<>();
            try (Database database = Database.open(dir, 8, 40_000)) {
                database.execute("CREATE TABLE t (id INT NOT NULL, v INT, w INT, s TEXT)");
                database.execute("CREATE INDEX idx_v ON t (v)");
                int nextId = 0;
                for (int step = 0; step < 40; step++) {
                    boolean explicit = random.nextBoolean();
                    Map<Integer, int[]> working = copy(committed);
                    if (explicit) {
                        database.execute("BEGIN");
                    }
                    int statements = explicit ? 1 + random.nextInt(4) : 1;
                    for (int k = 0; k < statements; k++) {
                        nextId = randomStatement(database, working, random, nextId);
                    }
                    boolean crashHere = random.nextInt(4) == 0;
                    if (explicit && crashHere) {
                        // crash with the transaction still open: only `committed` may survive
                        verifyCrash(dir, committed, seed, step);
                    }
                    if (explicit) {
                        if (random.nextInt(3) == 0) {
                            database.execute("ROLLBACK");
                        } else {
                            database.execute("COMMIT");
                            committed = working;
                        }
                    } else {
                        committed = working;
                    }
                    if (!explicit && crashHere || explicit && random.nextInt(5) == 0) {
                        verifyCrash(dir, committed, seed, step);
                    }
                }
            }
        }
    }

    private int randomStatement(Database database, Map<Integer, int[]> state, Random random, int nextId) {
        switch (random.nextInt(4)) {
            case 0, 1 -> {
                int count = 1 + random.nextInt(25);
                StringBuilder sql = new StringBuilder("INSERT INTO t VALUES ");
                for (int i = 0; i < count; i++) {
                    int v = random.nextInt(40);
                    int pad = 20 + random.nextInt(200);
                    sql.append(i == 0 ? "" : ", ").append("(").append(nextId).append(", ").append(v).append(", 0, '")
                            .append("q".repeat(pad)).append("')");
                    state.put(nextId, new int[] {v, 0});
                    nextId++;
                }
                database.execute(sql.toString());
            }
            case 2 -> {
                int low = random.nextInt(Math.max(1, nextId));
                int high = low + random.nextInt(60);
                database.execute("UPDATE t SET v = v + 1, w = w + 2 WHERE id >= " + low + " AND id < " + high);
                for (Map.Entry<Integer, int[]> e : state.entrySet()) {
                    if (e.getKey() >= low && e.getKey() < high) {
                        e.getValue()[0]++;
                        e.getValue()[1] += 2;
                    }
                }
            }
            default -> {
                int low = random.nextInt(Math.max(1, nextId));
                int high = low + random.nextInt(40);
                database.execute("DELETE FROM t WHERE id >= " + low + " AND id < " + high);
                state.keySet().removeIf(id -> id >= low && id < high);
            }
        }
        return nextId;
    }

    private void verifyCrash(Path dir, Map<Integer, int[]> expected, long seed, int step)
            throws IOException {
        Path copy = Files.createDirectory(root.resolve("random-crash-" + seed + "-" + step + "-" + copies++));
        try (Stream<Path> files = Files.list(dir)) {
            for (Path source : files.toList()) {
                Files.copy(source, copy.resolve(source.getFileName()));
            }
        }
        List<List<String>> want = new ArrayList<>();
        expected.forEach((id, vw) -> want.add(List.of(String.valueOf(id), String.valueOf(vw[0]), String.valueOf(vw[1]))));
        assertEquals(want, recoveredSnapshot(copy, 8), "seed " + seed + " step " + step);
    }

    private static Map<Integer, int[]> copy(Map<Integer, int[]> source) {
        Map<Integer, int[]> result = new TreeMap<>();
        source.forEach((k, v) -> result.put(k, v.clone()));
        return result;
    }
}
