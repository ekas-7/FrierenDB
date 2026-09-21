package dev.frierendb.engine.storage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * An unordered collection of variable-length records stored in slotted pages. The free space of
 * every page is cached in memory so inserts do not scan the file.
 */
public final class HeapFile {

    public record Entry(RecordId id, byte[] bytes) {}

    private final PageFile file;
    private final BufferPool pool;
    private final List<Integer> freeBytes = new ArrayList<>();

    public HeapFile(PageFile file, BufferPool pool) {
        this.file = file;
        this.pool = pool;
        reload();
    }

    /** Rebuilds the free-space cache from the pages on disk, after pages were restored by a rollback. */
    public synchronized void reload() {
        freeBytes.clear();
        for (int pageId = 0; pageId < file.pageCount(); pageId++) {
            try (BufferPool.Frame frame = pool.fetch(file, pageId)) {
                freeBytes.add(new SlottedPage(frame.data()).freeBytes());
            }
        }
    }

    public synchronized RecordId insert(byte[] record) {
        if (record.length > SlottedPage.MAX_RECORD_SIZE) {
            throw new StorageException("Record of " + record.length + " bytes exceeds the page limit of "
                    + SlottedPage.MAX_RECORD_SIZE);
        }
        for (int pageId = 0; pageId < freeBytes.size(); pageId++) {
            if (freeBytes.get(pageId) < record.length + SlottedPage.SLOT_SIZE) {
                continue;
            }
            try (BufferPool.Frame frame = pool.fetch(file, pageId)) {
                SlottedPage page = new SlottedPage(frame.data());
                int slot = page.insert(record);
                if (slot >= 0) {
                    frame.markDirty();
                    freeBytes.set(pageId, page.freeBytes());
                    return new RecordId(pageId, slot);
                }
                freeBytes.set(pageId, page.freeBytes());
            }
        }
        try (BufferPool.Frame frame = pool.allocate(file)) {
            SlottedPage page = SlottedPage.format(frame.data());
            int slot = page.insert(record);
            frame.markDirty();
            freeBytes.add(page.freeBytes());
            return new RecordId(frame.pageId(), slot);
        }
    }

    public synchronized byte[] get(RecordId id) {
        try (BufferPool.Frame frame = fetch(id)) {
            return new SlottedPage(frame.data()).get(id.slot());
        }
    }

    public synchronized void delete(RecordId id) {
        try (BufferPool.Frame frame = fetch(id)) {
            SlottedPage page = new SlottedPage(frame.data());
            page.delete(id.slot());
            frame.markDirty();
            freeBytes.set(id.pageId(), page.freeBytes());
        }
    }

    /** Updates a record, relocating it if its page is too full. Returns the record's current id. */
    public synchronized RecordId update(RecordId id, byte[] record) {
        try (BufferPool.Frame frame = fetch(id)) {
            SlottedPage page = new SlottedPage(frame.data());
            if (page.update(id.slot(), record)) {
                frame.markDirty();
                freeBytes.set(id.pageId(), page.freeBytes());
                return id;
            }
        }
        delete(id);
        return insert(record);
    }

    /** Scans the pages that exist when the scan starts, one pinned page at a time. */
    public Iterator<Entry> scan() {
        int pagesAtStart;
        synchronized (this) {
            pagesAtStart = file.pageCount();
        }
        return new Iterator<>() {
            private int pageId = 0;
            private int slot = 0;
            private Entry next = advance();

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public Entry next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                Entry current = next;
                next = advance();
                return current;
            }

            private Entry advance() {
                while (pageId < pagesAtStart) {
                    synchronized (HeapFile.this) {
                        try (BufferPool.Frame frame = pool.fetch(file, pageId)) {
                            SlottedPage page = new SlottedPage(frame.data());
                            while (slot < page.slotCount()) {
                                int current = slot++;
                                if (page.isLive(current)) {
                                    return new Entry(new RecordId(pageId, current), page.get(current));
                                }
                            }
                        }
                    }
                    pageId++;
                    slot = 0;
                }
                return null;
            }
        };
    }

    public synchronized int pageCount() {
        return file.pageCount();
    }

    public synchronized void flush() {
        pool.flush(file);
    }

    private BufferPool.Frame fetch(RecordId id) {
        if (id.pageId() < 0 || id.pageId() >= file.pageCount()) {
            throw new StorageException("Record " + id + " does not exist");
        }
        return pool.fetch(file, id.pageId());
    }
}
