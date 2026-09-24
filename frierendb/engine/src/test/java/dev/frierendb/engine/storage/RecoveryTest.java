package dev.frierendb.engine.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryTest {

    @TempDir
    Path root;

    private Path live;
    private PageFile file;
    private Wal wal;

    @BeforeEach
    void setUp() throws IOException {
        live = Files.createDirectory(root.resolve("live"));
        file = PageFile.open(live.resolve("t.pages"));
        wal = Wal.open(live.resolve(Wal.FILE_NAME));
    }

    @AfterEach
    void tearDown() {
        file.close();
        wal.close();
    }

    private BufferPool pool(int capacity) {
        BufferPool pool = new BufferPool(capacity);
        pool.attachWal(wal);
        return pool;
    }

    private void seed(BufferPool pool, int count, int base) {
        for (int i = 0; i < count; i++) {
            try (BufferPool.Frame frame = pool.allocate(file)) {
                frame.data()[0] = (byte) (base + i);
                frame.markDirty();
            }
        }
        pool.checkpoint();
    }

    private static void set(BufferPool pool, PageFile file, int pageId, int value) {
        try (BufferPool.Frame frame = pool.fetch(file, pageId)) {
            frame.data()[0] = (byte) value;
            frame.markDirty();
        }
    }

    /** Copies the files as they are right now, without closing anything: the state a crash would leave. */
    private Path crash(String name) throws IOException {
        Path copy = Files.createDirectory(root.resolve(name));
        try (Stream<Path> files = Files.list(live)) {
            for (Path source : files.toList()) {
                Files.copy(source, copy.resolve(source.getFileName()));
            }
        }
        return copy;
    }

    private static List<Integer> firstBytes(Path pageFile) {
        try (PageFile reopened = PageFile.open(pageFile)) {
            return java.util.stream.IntStream.range(0, reopened.pageCount())
                    .mapToObj(i -> (int) reopened.read(i)[0]).toList();
        }
    }

    @Test
    void aMissingOrEmptyLogNeedsNoRecovery() throws IOException {
        Path empty = Files.createDirectory(root.resolve("empty"));
        assertFalse(Recovery.recover(empty).didWork());
        Files.createFile(empty.resolve("t.pages"));
        assertFalse(Recovery.recover(empty).didWork());
        assertFalse(Recovery.recover(live).didWork());
    }

    @Test
    void aCommittedTransactionThatNeverReachedTheDataFilesIsRedone() throws IOException {
        BufferPool pool = pool(8);
        seed(pool, 3, 1);
        pool.begin();
        set(pool, file, 0, 50);
        set(pool, file, 2, 52);
        pool.commit();
        Path crashed = crash("crashed");
        assertEquals(List.of(1, 2, 3), firstBytes(crashed.resolve("t.pages")), "the data file is stale");
        Recovery.Report report = Recovery.recover(crashed);
        assertEquals(1, report.committedTransactions());
        assertEquals(0, report.unfinishedTransactions());
        assertEquals(2, report.pagesRedone());
        assertEquals(List.of(50, 2, 52), firstBytes(crashed.resolve("t.pages")));
        assertEquals(8, Files.size(crashed.resolve(Wal.FILE_NAME)), "the log is emptied");
    }

    @Test
    void anUnfinishedTransactionWhosePagesWereEvictedIsUndone() throws IOException {
        BufferPool pool = pool(2);
        seed(pool, 6, 1);
        pool.begin();
        for (int i = 0; i < 6; i++) {
            set(pool, file, i, 100 + i);
        }
        try (BufferPool.Frame frame = pool.allocate(file)) {
            frame.data()[0] = 77;
            frame.markDirty();
        }
        Path crashed = crash("crashed");
        List<Integer> before = firstBytes(crashed.resolve("t.pages"));
        assertTrue(before.stream().anyMatch(v -> v >= 100), "uncommitted changes did reach the data file: " + before);
        Recovery.Report report = Recovery.recover(crashed);
        assertEquals(0, report.committedTransactions());
        assertEquals(1, report.unfinishedTransactions());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), firstBytes(crashed.resolve("t.pages")),
                "original values are back and the allocated page is gone");
    }

    @Test
    void anAbortedTransactionIsSkippedWhileALaterCommittedOneIsRedone() throws IOException {
        BufferPool pool = pool(8);
        seed(pool, 3, 1);
        pool.begin();
        set(pool, file, 0, 90);
        set(pool, file, 1, 91);
        pool.rollback();
        pool.begin();
        set(pool, file, 1, 60);
        pool.commit();
        Path crashed = crash("crashed");
        Recovery.Report report = Recovery.recover(crashed);
        assertEquals(1, report.committedTransactions());
        assertEquals(0, report.unfinishedTransactions());
        assertEquals(List.of(1, 60, 3), firstBytes(crashed.resolve("t.pages")));
    }

    @Test
    void committedTransactionsAreReplayedInLogOrder() throws IOException {
        BufferPool pool = pool(8);
        seed(pool, 1, 1);
        for (int v : new int[] {10, 20, 30}) {
            pool.begin();
            set(pool, file, 0, v);
            pool.commit();
        }
        Path crashed = crash("crashed");
        assertEquals(3, Recovery.recover(crashed).committedTransactions());
        assertEquals(List.of(30), firstBytes(crashed.resolve("t.pages")));
    }

    @Test
    void everyPossibleCutOfTheLogLeavesTheTransactionAllOrNothing() throws IOException {
        BufferPool pool = pool(16);
        seed(pool, 4, 1);
        // The data file as it was before the transaction began: four pages.
        Path beforeGrowth = Files.copy(live.resolve("t.pages"), root.resolve("t.before"));
        pool.begin();
        for (int i = 0; i < 4; i++) {
            set(pool, file, i, 40 + i);
        }
        try (BufferPool.Frame frame = pool.allocate(file)) {
            frame.data()[0] = 99;
            frame.markDirty();
        }
        pool.commit();
        byte[] log = Files.readAllBytes(live.resolve(Wal.FILE_NAME));
        // The first record is the logged page allocation. It was forced before the file grew, so a
        // crash can only have happened with at least that much of the log on disk once the file had grown.
        int firstRecordEnd = 8 + 8 + java.nio.ByteBuffer.wrap(log).getInt(8);
        List<Integer> original = List.of(1, 2, 3, 4);
        List<Integer> committed = List.of(40, 41, 42, 43, 99);

        // Cut at every record boundary and at a few places inside every record: just after the
        // frame header, in the middle of the payload, and one byte short of the end.
        java.util.TreeSet<Integer> cuts = new java.util.TreeSet<>(List.of(0, 3, 7, 8, 9, 15));
        int offset = 8;
        while (offset < log.length) {
            int payload = java.nio.ByteBuffer.wrap(log).getInt(offset);
            int end = offset + 8 + payload;
            cuts.addAll(List.of(offset, offset + 1, offset + 8, offset + 8 + payload / 2, end - 1, end));
            offset = end;
        }
        assertTrue(cuts.size() > 30, "expected many cut points, got " + cuts.size());
        for (int length : cuts) {
            Path copy = Files.createDirectory(root.resolve("cut" + length));
            Path source = length < firstRecordEnd ? beforeGrowth : live.resolve("t.pages");
            Files.copy(source, copy.resolve("t.pages"));
            Files.write(copy.resolve(Wal.FILE_NAME), Arrays.copyOf(log, length));
            Recovery.recover(copy);
            List<Integer> expected = length >= log.length ? committed : original;
            assertEquals(expected, firstBytes(copy.resolve("t.pages")),
                    "log cut to " + length + " of " + log.length);
        }
    }

    @Test
    void recoveryTwiceIsTheSameAsOnce() throws IOException {
        BufferPool pool = pool(8);
        seed(pool, 2, 1);
        pool.begin();
        set(pool, file, 1, 55);
        pool.commit();
        Path crashed = crash("crashed");
        Recovery.recover(crashed);
        List<Integer> once = firstBytes(crashed.resolve("t.pages"));
        Recovery.Report second = Recovery.recover(crashed);
        assertFalse(second.didWork());
        assertEquals(once, firstBytes(crashed.resolve("t.pages")));
    }

    @Test
    void crashingDuringRecoveryIsRepairedByRunningItAgain() throws IOException {
        BufferPool pool = pool(2);
        seed(pool, 4, 1);
        pool.begin();
        for (int i = 0; i < 4; i++) {
            set(pool, file, i, 70 + i);
        }
        Path crashed = crash("crashed");
        // A first recovery that is interrupted leaves some pages restored and the log untouched.
        Path partial = Files.createDirectory(root.resolve("partial"));
        Files.copy(crashed.resolve(Wal.FILE_NAME), partial.resolve(Wal.FILE_NAME));
        Files.copy(crashed.resolve("t.pages"), partial.resolve("t.pages"));
        try (PageFile f = PageFile.open(partial.resolve("t.pages"))) {
            byte[] page = f.read(0);
            page[0] = 1;
            f.write(0, page);
        }
        Recovery.recover(partial);
        assertEquals(List.of(1, 2, 3, 4), firstBytes(partial.resolve("t.pages")));
    }

    @Test
    void recordsForFilesThatNoLongerExistAreSkippedAndForeignLogsRefused() throws IOException {
        BufferPool pool = pool(8);
        seed(pool, 1, 1);
        pool.begin();
        set(pool, file, 0, 9);
        pool.commit();
        Path crashed = crash("crashed");
        Files.delete(crashed.resolve("t.pages"));
        assertEquals(1, Recovery.recover(crashed).committedTransactions());
        assertFalse(Files.exists(crashed.resolve("t.pages")), "recovery does not resurrect a deleted file");

        Path foreign = Files.createDirectory(root.resolve("foreign"));
        Files.write(foreign.resolve(Wal.FILE_NAME), new byte[100]);
        assertThrows(StorageException.class, () -> Recovery.recover(foreign));
    }

    @Test
    void aLogNamingAFileOutsideTheDirectoryIsRefusedAndTouchesNothing() throws IOException {
        Path inner = Files.createDirectory(root.resolve("inner"));
        Path outside = root.resolve("escape.pages");
        byte[] page = new byte[PageFile.PAGE_SIZE];
        try (Wal log = Wal.open(inner.resolve(Wal.FILE_NAME))) {
            log.append(Wal.LogRecord.update(1, "../escape.pages", 0, page, page));
            log.append(Wal.LogRecord.commit(1));
            log.force();
        }
        assertThrows(StorageException.class, () -> Recovery.recover(inner));
        assertFalse(Files.exists(outside), "nothing was created outside the database directory");
    }

    @Test
    void aTornTailAfterTheLastCommitIsReportedAndIgnored() throws IOException {
        BufferPool pool = pool(8);
        seed(pool, 1, 1);
        pool.begin();
        set(pool, file, 0, 9);
        pool.commit();
        Path crashed = crash("crashed");
        Path log = crashed.resolve(Wal.FILE_NAME);
        byte[] bytes = Files.readAllBytes(log);
        byte[] withGarbage = Arrays.copyOf(bytes, bytes.length + 5);
        withGarbage[bytes.length] = 1;
        Files.write(log, withGarbage);
        Recovery.Report report = Recovery.recover(crashed);
        assertTrue(report.tornLogTail());
        assertEquals(List.of(9), firstBytes(crashed.resolve("t.pages")));
    }
}
