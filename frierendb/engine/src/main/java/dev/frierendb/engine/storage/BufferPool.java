package dev.frierendb.engine.storage;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed-capacity page cache with LRU eviction. A pinned frame is never evicted.
 * All methods are synchronized, but callers must serialise access to a single page's bytes.
 */
public final class BufferPool {

    private record PageKey(PageFile file, int pageId) {}

    public final class Frame implements AutoCloseable {
        private final PageKey key;
        private final byte[] data;
        private boolean dirty;
        private int pins;

        private Frame(PageKey key, byte[] data) {
            this.key = key;
            this.data = data;
        }

        public byte[] data() {
            return data;
        }

        public int pageId() {
            return key.pageId();
        }

        public void markDirty() {
            synchronized (BufferPool.this) {
                dirty = true;
            }
        }

        @Override
        public void close() {
            unpin(this);
        }
    }

    private final int capacity;
    private final LinkedHashMap<PageKey, Frame> frames = new LinkedHashMap<>(16, 0.75f, true);
    private long hits;
    private long misses;

    public BufferPool(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /** Pins the page, loading it from disk on a miss. Close the frame to unpin it. */
    public synchronized Frame fetch(PageFile file, int pageId) {
        PageKey key = new PageKey(file, pageId);
        Frame frame = frames.get(key);
        if (frame != null) {
            hits++;
        } else {
            misses++;
            makeRoom();
            frame = new Frame(key, file.read(pageId));
            frames.put(key, frame);
        }
        frame.pins++;
        return frame;
    }

    /** Appends a new zeroed page to the file and returns it pinned. */
    public synchronized Frame allocate(PageFile file) {
        makeRoom();
        int pageId = file.allocate();
        Frame frame = new Frame(new PageKey(file, pageId), new byte[PageFile.PAGE_SIZE]);
        frames.put(frame.key, frame);
        frame.pins++;
        return frame;
    }

    public synchronized void flush(PageFile file) {
        for (Frame frame : frames.values()) {
            if (frame.key.file() == file) {
                writeIfDirty(frame);
            }
        }
        file.sync();
    }

    /** Drops all cached pages of a file after flushing them. Fails if any are still pinned. */
    public synchronized void evictAll(PageFile file) {
        flush(file);
        Iterator<Frame> it = frames.values().iterator();
        while (it.hasNext()) {
            Frame frame = it.next();
            if (frame.key.file() == file) {
                if (frame.pins > 0) {
                    throw new StorageException(
                            "Page " + frame.key.pageId() + " of " + file.path() + " is still pinned");
                }
                it.remove();
            }
        }
    }

    public synchronized long hits() {
        return hits;
    }

    public synchronized long misses() {
        return misses;
    }

    public synchronized int cachedPages() {
        return frames.size();
    }

    private synchronized void unpin(Frame frame) {
        if (frame.pins <= 0) {
            throw new StorageException("Page " + frame.key.pageId() + " is not pinned");
        }
        frame.pins--;
    }

    private void makeRoom() {
        if (frames.size() < capacity) {
            return;
        }
        for (Map.Entry<PageKey, Frame> entry : frames.entrySet()) {
            Frame candidate = entry.getValue();
            if (candidate.pins == 0) {
                writeIfDirty(candidate);
                frames.remove(entry.getKey());
                return;
            }
        }
        throw new StorageException("Buffer pool exhausted: all " + capacity + " pages are pinned");
    }

    private void writeIfDirty(Frame frame) {
        if (frame.dirty) {
            frame.key.file().write(frame.key.pageId(), frame.data);
            frame.dirty = false;
        }
    }
}
