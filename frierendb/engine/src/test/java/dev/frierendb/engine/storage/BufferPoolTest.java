package dev.frierendb.engine.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferPoolTest {

    @TempDir
    Path dir;

    private PageFile fileWithPages(int count) {
        PageFile file = PageFile.open(dir.resolve("pool.pages"));
        for (int i = 0; i < count; i++) {
            file.allocate();
        }
        return file;
    }

    @Test
    void repeatedFetchIsAHit() {
        try (PageFile file = fileWithPages(2)) {
            BufferPool pool = new BufferPool(2);
            pool.fetch(file, 0).close();
            pool.fetch(file, 0).close();
            assertEquals(1, pool.misses());
            assertEquals(1, pool.hits());
        }
    }

    @Test
    void evictsLeastRecentlyUsedUnpinnedPage() {
        try (PageFile file = fileWithPages(3)) {
            BufferPool pool = new BufferPool(2);
            pool.fetch(file, 0).close();
            pool.fetch(file, 1).close();
            pool.fetch(file, 0).close();
            pool.fetch(file, 2).close();
            long missesBefore = pool.misses();
            pool.fetch(file, 0).close();
            assertEquals(missesBefore, pool.misses(), "page 0 was most recently used and must stay cached");
            pool.fetch(file, 1).close();
            assertEquals(missesBefore + 1, pool.misses(), "page 1 was the LRU victim");
        }
    }

    @Test
    void dirtyPageIsWrittenBackOnEviction() {
        try (PageFile file = fileWithPages(2)) {
            BufferPool pool = new BufferPool(1);
            try (BufferPool.Frame frame = pool.fetch(file, 0)) {
                frame.data()[0] = 42;
                frame.markDirty();
            }
            pool.fetch(file, 1).close();
            assertEquals(42, file.read(0)[0]);
        }
    }

    @Test
    void cleanPageIsNotWritten() {
        try (PageFile file = fileWithPages(2)) {
            BufferPool pool = new BufferPool(1);
            try (BufferPool.Frame frame = pool.fetch(file, 0)) {
                frame.data()[0] = 42;
            }
            pool.fetch(file, 1).close();
            assertEquals(0, file.read(0)[0]);
        }
    }

    @Test
    void failsWhenEveryPageIsPinned() {
        try (PageFile file = fileWithPages(2)) {
            BufferPool pool = new BufferPool(1);
            try (BufferPool.Frame pinned = pool.fetch(file, 0)) {
                assertThrows(StorageException.class, () -> pool.fetch(file, 1));
                assertEquals(0, pinned.pageId());
            }
            pool.fetch(file, 1).close();
        }
    }

    @Test
    void flushPersistsDirtyPagesWithoutEvicting() {
        try (PageFile file = fileWithPages(1)) {
            BufferPool pool = new BufferPool(4);
            try (BufferPool.Frame frame = pool.fetch(file, 0)) {
                frame.data()[5] = 9;
                frame.markDirty();
            }
            pool.flush(file);
            assertEquals(9, file.read(0)[5]);
            assertEquals(1, pool.cachedPages());
        }
    }

    @Test
    void evictAllRefusesWhilePagesArePinned() {
        try (PageFile file = fileWithPages(1)) {
            BufferPool pool = new BufferPool(2);
            try (BufferPool.Frame pinned = pool.fetch(file, 0)) {
                assertThrows(StorageException.class, () -> pool.evictAll(file));
                assertEquals(0, pinned.pageId());
            }
            pool.evictAll(file);
            assertEquals(0, pool.cachedPages());
        }
    }

    @Test
    void allocateReturnsPinnedZeroedFrame() {
        try (PageFile file = fileWithPages(0)) {
            BufferPool pool = new BufferPool(2);
            try (BufferPool.Frame frame = pool.allocate(file)) {
                assertEquals(0, frame.pageId());
                assertEquals(0, frame.data()[0]);
            }
            assertEquals(1, file.pageCount());
        }
    }

    @Test
    void closingTwiceIsRejected() {
        try (PageFile file = fileWithPages(1)) {
            BufferPool pool = new BufferPool(1);
            BufferPool.Frame frame = pool.fetch(file, 0);
            frame.close();
            assertThrows(StorageException.class, frame::close);
        }
    }
}
