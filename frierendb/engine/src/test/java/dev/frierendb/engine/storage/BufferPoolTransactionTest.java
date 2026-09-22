package dev.frierendb.engine.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.storage.Wal.LogRecord;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferPoolTransactionTest {

    @TempDir
    Path dir;

    private PageFile file;
    private Wal wal;

    @BeforeEach
    void setUp() {
        file = PageFile.open(dir.resolve("t.pages"));
        wal = Wal.open(dir.resolve(Wal.FILE_NAME));
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

    /** Creates {@code count} pages whose first byte is {@code base + pageId}, flushed to disk. */
    private void seed(BufferPool pool, int count, int base) {
        for (int i = 0; i < count; i++) {
            try (BufferPool.Frame frame = pool.allocate(file)) {
                frame.data()[0] = (byte) (base + i);
                frame.markDirty();
            }
        }
        pool.flush(file);
    }

    private static void set(BufferPool pool, PageFile file, int pageId, int value) {
        try (BufferPool.Frame frame = pool.fetch(file, pageId)) {
            frame.data()[0] = (byte) value;
            frame.markDirty();
        }
    }

    private static int get(BufferPool pool, PageFile file, int pageId) {
        try (BufferPool.Frame frame = pool.fetch(file, pageId)) {
            return frame.data()[0];
        }
    }

    private int onDisk(int pageId) {
        return file.read(pageId)[0];
    }

    @Test
    void beginNeedsALogAndCannotNest() {
        BufferPool plain = new BufferPool(4);
        assertThrows(StorageException.class, plain::begin);
        BufferPool pool = pool(4);
        assertThrows(StorageException.class, pool::commit);
        assertThrows(StorageException.class, pool::rollback);
        pool.begin();
        assertTrue(pool.inTransaction());
        assertThrows(StorageException.class, pool::begin);
        assertThrows(StorageException.class, pool::checkpoint);
        pool.commit();
        assertFalse(pool.inTransaction());
    }

    @Test
    void commitLogsOnlyThePagesThatChangedWithBothImages() {
        BufferPool pool = pool(8);
        seed(pool, 3, 1);
        wal.reset();
        pool.begin();
        get(pool, file, 0);
        set(pool, file, 1, 50);
        get(pool, file, 2);
        pool.commit();
        List<LogRecord> records = wal.contents().records();
        assertEquals(2, records.size(), "one changed page plus the commit record");
        LogRecord update = records.get(0);
        assertEquals(Wal.Type.UPDATE, update.type());
        assertEquals("t.pages", update.file());
        assertEquals(1, update.pageId());
        assertEquals(2, update.before()[0]);
        assertEquals(50, update.after()[0]);
        assertEquals(Wal.Type.COMMIT, records.get(1).type());
        assertEquals(onDisk(1), 2, "commit does not write data pages");
        assertEquals(50, get(pool, file, 1));
    }

    @Test
    void severalChangesToOnePageAreLoggedOnceWithTheOriginalAsBeforeImage() {
        BufferPool pool = pool(8);
        seed(pool, 1, 1);
        wal.reset();
        pool.begin();
        set(pool, file, 0, 10);
        set(pool, file, 0, 20);
        set(pool, file, 0, 30);
        pool.commit();
        List<LogRecord> records = wal.contents().records();
        assertEquals(2, records.size());
        assertEquals(1, records.get(0).before()[0]);
        assertEquals(30, records.get(0).after()[0]);
    }

    @Test
    void rollbackRestoresPagesThatNeverLeftTheCache() {
        BufferPool pool = pool(8);
        seed(pool, 3, 1);
        pool.begin();
        set(pool, file, 0, 99);
        set(pool, file, 2, 98);
        pool.rollback();
        assertEquals(1, get(pool, file, 0));
        assertEquals(3, get(pool, file, 2));
        assertEquals(1, onDisk(0));
        List<LogRecord> records = wal.contents().records();
        assertEquals(Wal.Type.ABORT, records.get(records.size() - 1).type());
    }

    @Test
    void pagesEvictedMidTransactionAreLoggedFirstAndUndoneByRollback() {
        BufferPool pool = pool(2);
        seed(pool, 6, 1);
        wal.reset();
        pool.begin();
        for (int i = 0; i < 6; i++) {
            set(pool, file, i, 100 + i);
        }
        int writtenEarly = 0;
        for (int i = 0; i < 6; i++) {
            if (onDisk(i) == 100 + i) {
                writtenEarly++;
                int page = i;
                boolean logged = wal.contents().records().stream().anyMatch(r ->
                        r.type() == Wal.Type.UPDATE && r.pageId() == page && r.before() != null
                                && r.before()[0] == 1 + page && r.after()[0] == 100 + page);
                assertTrue(logged, "page " + i + " reached disk, so its before- and after-image must already be in the log");
            }
        }
        assertTrue(writtenEarly >= 3, "a pool of two pages must have pushed most pages to disk: " + writtenEarly);
        pool.rollback();
        for (int i = 0; i < 6; i++) {
            assertEquals(1 + i, onDisk(i), "disk page " + i);
            assertEquals(1 + i, get(pool, file, i), "cached page " + i);
        }
    }

    @Test
    void aPageStolenThenFetchedAndChangedAgainIsRestoredToTheOriginal() {
        BufferPool pool = pool(1);
        seed(pool, 2, 1);
        pool.begin();
        set(pool, file, 0, 10);
        get(pool, file, 1);
        assertEquals(10, onDisk(0), "page 0 was evicted, so it was written");
        set(pool, file, 0, 20);
        pool.rollback();
        assertEquals(1, onDisk(0));
        assertEquals(1, get(pool, file, 0));
    }

    @Test
    void committedButUnflushedChangesSurviveALaterRollback() {
        BufferPool pool = pool(8);
        seed(pool, 1, 1);
        pool.begin();
        set(pool, file, 0, 7);
        pool.commit();
        assertEquals(1, onDisk(0), "the commit left the data page in the cache");
        pool.begin();
        set(pool, file, 0, 9);
        pool.rollback();
        assertEquals(7, get(pool, file, 0), "rollback returns to the committed state, not the disk state");
        pool.checkpoint();
        assertEquals(7, onDisk(0));
    }

    @Test
    void rollbackDropsPagesTheTransactionAllocated() {
        BufferPool pool = pool(2);
        seed(pool, 2, 1);
        pool.begin();
        for (int i = 0; i < 4; i++) {
            try (BufferPool.Frame frame = pool.allocate(file)) {
                frame.data()[0] = (byte) (50 + i);
                frame.markDirty();
            }
        }
        assertEquals(6, file.pageCount());
        pool.rollback();
        assertEquals(2, file.pageCount());
        assertEquals(2, onDisk(1));
        try (BufferPool.Frame frame = pool.allocate(file)) {
            assertEquals(2, frame.pageId(), "page numbers are reused after the rollback");
        }
    }

    @Test
    void allocatedPagesAreLoggedAsNewAndCommitted() {
        BufferPool pool = pool(8);
        pool.begin();
        try (BufferPool.Frame frame = pool.allocate(file)) {
            frame.data()[0] = 42;
            frame.markDirty();
        }
        pool.commit();
        List<LogRecord> records = wal.contents().records();
        LogRecord last = records.stream().filter(r -> r.type() == Wal.Type.UPDATE).reduce((a, b) -> b).orElseThrow();
        assertEquals(0, last.pageId());
        assertEquals(null, last.before());
        assertEquals(42, last.after()[0]);
    }

    @Test
    void checkpointWritesDirtyPagesAndEmptiesTheLog() {
        BufferPool pool = pool(8);
        seed(pool, 2, 1);
        pool.begin();
        set(pool, file, 0, 77);
        pool.commit();
        assertTrue(wal.size() > 8);
        assertEquals(1, onDisk(0));
        pool.checkpoint();
        assertEquals(77, onDisk(0));
        assertEquals(8, wal.size());
        assertEquals(8, pool.logSize());
    }

    @Test
    void modificationsCountsEveryMarkDirty() {
        BufferPool pool = pool(8);
        seed(pool, 1, 1);
        long before = pool.modifications();
        get(pool, file, 0);
        assertEquals(before, pool.modifications());
        set(pool, file, 0, 5);
        set(pool, file, 0, 6);
        assertEquals(before + 2, pool.modifications());
    }

    @Test
    void aPlainPoolStillBehavesAsBefore() {
        BufferPool plain = new BufferPool(2);
        seed(plain, 3, 1);
        set(plain, file, 0, 9);
        plain.flush(file);
        assertEquals(9, onDisk(0));
        assertEquals(0, plain.logSize());
    }
}
