package dev.frierendb.engine.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fixed-capacity page cache with LRU eviction. A pinned frame is never evicted.
 * All methods are synchronized, but callers must serialise access to a single page's bytes.
 *
 * <p>With a {@link Wal} attached the pool also provides one transaction at a time, with
 * steal / no-force buffering:
 * <ul>
 *   <li>The first time a transaction fetches a page, the pool keeps a copy of its bytes (the
 *       before-image). Pages the transaction allocates are remembered as new.</li>
 *   <li>A page a transaction changed may be evicted before commit (steal), but only after an UPDATE
 *       record with its before- and after-image has been appended and the log forced. This is the
 *       write-ahead rule.</li>
 *   <li>{@link #commit()} logs the pages still in the pool that changed, appends COMMIT and forces
 *       the log. Data pages are not written then (no-force); they are written by eviction or
 *       {@link #checkpoint()}.</li>
 *   <li>{@link #rollback()} restores every page the transaction changed, on disk and in the cache,
 *       and drops the pages it allocated.</li>
 * </ul>
 * Pages are logged whole, so the log holds up to two page images for every page a transaction changes.
 * Without a log attached the pool behaves as a plain cache and {@link #begin()} is unavailable.
 */
public final class BufferPool {

    private record PageKey(PageFile file, int pageId) {}

    public final class Frame implements AutoCloseable {
        private final PageKey key;
        private final byte[] data;
        private boolean dirty;
        private int pins;
        private byte[] txBefore;
        private boolean txAllocated;

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
                modifications++;
            }
        }

        @Override
        public void close() {
            unpin(this);
        }
    }

    private final int capacity;
    private final LinkedHashMap<PageKey, Frame> frames = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<PageFile> writtenSinceSync = new HashSet<>();
    private final Map<String, PageFile> transactionFiles = new HashMap<>();
    private long hits;
    private long misses;
    private long modifications;
    private Wal wal;
    private long transactionId;

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
        trackForTransaction(frame, false);
        return frame;
    }

    /** Appends a new zeroed page to the file and returns it pinned. */
    public synchronized Frame allocate(PageFile file) {
        makeRoom();
        if (transactionId != 0) {
            // The file is about to grow. Log and force that first, so a crash can never leave an
            // extra page that no log record accounts for.
            transactionFiles.putIfAbsent(nameOf(file), file);
            wal.append(Wal.LogRecord.update(transactionId, nameOf(file), file.pageCount(), null,
                    new byte[PageFile.PAGE_SIZE]));
            wal.force();
        }
        int pageId = file.allocate();
        Frame frame = new Frame(new PageKey(file, pageId), new byte[PageFile.PAGE_SIZE]);
        frames.put(frame.key, frame);
        frame.pins++;
        trackForTransaction(frame, true);
        return frame;
    }

    public synchronized void flush(PageFile file) {
        for (Frame frame : frames.values()) {
            if (frame.key.file() == file) {
                writeFrame(frame);
            }
        }
        file.sync();
        writtenSinceSync.remove(file);
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

    // ---- transactions ----

    /** Attaches the log used by transactions. Must be called before {@link #begin()}. */
    public synchronized void attachWal(Wal wal) {
        if (transactionId != 0) {
            throw new StorageException("Cannot change the log during a transaction");
        }
        this.wal = wal;
    }

    public synchronized boolean inTransaction() {
        return transactionId != 0;
    }

    /** Number of page modifications so far; compare two readings to tell whether anything changed. */
    public synchronized long modifications() {
        return modifications;
    }

    /** Bytes currently in the log, or zero when no log is attached. */
    public synchronized long logSize() {
        return wal == null ? 0 : wal.size();
    }

    public synchronized void begin() {
        if (wal == null) {
            throw new StorageException("Transactions need a write-ahead log");
        }
        if (transactionId != 0) {
            throw new StorageException("A transaction is already active");
        }
        transactionId = wal.nextTxId();
    }

    /** Makes the transaction's changes durable by forcing the log; data pages stay in the cache. */
    public synchronized void commit() {
        requireTransaction();
        for (Frame frame : frames.values()) {
            logIfChanged(frame);
        }
        wal.append(Wal.LogRecord.commit(transactionId));
        wal.force();
        endTransaction();
    }

    /** Undoes every change of the transaction, on disk and in the cache, and ends it. */
    public synchronized void rollback() {
        requireTransaction();
        Map<PageFile, Integer> truncateTo = new HashMap<>();
        Set<PageFile> touched = new HashSet<>();

        // Pages still cached go back to the bytes they had when the transaction first fetched them.
        for (Frame frame : frames.values()) {
            if (frame.txAllocated) {
                truncateTo.merge(frame.key.file(), frame.key.pageId(), Math::min);
            } else if (frame.txBefore != null && !Arrays.equals(frame.txBefore, frame.data)) {
                System.arraycopy(frame.txBefore, 0, frame.data, 0, PageFile.PAGE_SIZE);
                frame.dirty = true;
            }
        }

        // Pages that were already written to disk under the write-ahead rule are undone from the log,
        // newest record first, so the oldest before-image is the last one written.
        List<Wal.LogRecord> mine = new ArrayList<>();
        for (Wal.LogRecord record : wal.contents().records()) {
            if (record.txId() == transactionId && record.type() == Wal.Type.UPDATE) {
                mine.add(record);
            }
        }
        for (int i = mine.size() - 1; i >= 0; i--) {
            Wal.LogRecord record = mine.get(i);
            PageFile file = transactionFiles.get(record.file());
            if (file == null) {
                throw new StorageException("Cannot undo a change to " + record.file() + ": the file is not open");
            }
            if (record.before() == null) {
                truncateTo.merge(file, record.pageId(), Math::min);
                continue;
            }
            file.write(record.pageId(), record.before());
            touched.add(file);
            Frame cached = frames.get(new PageKey(file, record.pageId()));
            if (cached != null) {
                System.arraycopy(record.before(), 0, cached.data, 0, PageFile.PAGE_SIZE);
            }
        }

        for (Map.Entry<PageFile, Integer> entry : truncateTo.entrySet()) {
            PageFile file = entry.getKey();
            int keep = Math.min(entry.getValue(), file.pageCount());
            Iterator<Frame> it = frames.values().iterator();
            while (it.hasNext()) {
                Frame frame = it.next();
                if (frame.key.file() == file && frame.key.pageId() >= keep) {
                    if (frame.pins > 0) {
                        throw new StorageException("Page " + frame.key.pageId() + " of " + file.path()
                                + " is still pinned during rollback");
                    }
                    it.remove();
                }
            }
            file.truncate(keep);
            touched.add(file);
        }

        for (PageFile file : touched) {
            file.sync();
        }
        wal.append(Wal.LogRecord.abort(transactionId));
        wal.force();
        endTransaction();
    }

    /**
     * Writes every dirty page, syncs the files, and empties the log. Not allowed during a transaction,
     * because its uncommitted pages must not reach disk without log records.
     */
    public synchronized void checkpoint() {
        if (transactionId != 0) {
            throw new StorageException("Cannot checkpoint during a transaction");
        }
        for (Frame frame : frames.values()) {
            writeFrame(frame);
        }
        for (PageFile file : writtenSinceSync) {
            if (file.isOpen()) {
                file.sync();
            }
        }
        writtenSinceSync.clear();
        if (wal != null) {
            wal.reset();
        }
    }

    private void requireTransaction() {
        if (transactionId == 0) {
            throw new StorageException("No transaction is active");
        }
    }

    private void endTransaction() {
        for (Frame frame : frames.values()) {
            frame.txBefore = null;
            frame.txAllocated = false;
        }
        transactionFiles.clear();
        transactionId = 0;
    }

    private void trackForTransaction(Frame frame, boolean allocated) {
        if (transactionId == 0) {
            return;
        }
        PageFile file = frame.key.file();
        transactionFiles.putIfAbsent(nameOf(file), file);
        if (frame.txBefore == null && !frame.txAllocated) {
            if (allocated) {
                frame.txAllocated = true;
            } else {
                frame.txBefore = frame.data.clone();
            }
        }
    }

    private static String nameOf(PageFile file) {
        return file.path().getFileName().toString();
    }

    /** Appends an UPDATE record if the transaction changed this page or allocated it. */
    private boolean logIfChanged(Frame frame) {
        if (frame.txAllocated) {
            wal.append(Wal.LogRecord.update(transactionId, nameOf(frame.key.file()), frame.key.pageId(),
                    null, frame.data));
            return true;
        }
        if (frame.txBefore != null && !Arrays.equals(frame.txBefore, frame.data)) {
            wal.append(Wal.LogRecord.update(transactionId, nameOf(frame.key.file()), frame.key.pageId(),
                    frame.txBefore, frame.data));
            return true;
        }
        return false;
    }

    /** Writes a dirty page to its file, first logging and forcing it if a transaction touched it. */
    private void writeFrame(Frame frame) {
        if (transactionId != 0 && (frame.dirty || frame.txAllocated) && logIfChanged(frame)) {
            wal.force();
        }
        if (frame.dirty) {
            frame.key.file().write(frame.key.pageId(), frame.data);
            frame.dirty = false;
            writtenSinceSync.add(frame.key.file());
        }
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
                writeFrame(candidate);
                frames.remove(entry.getKey());
                return;
            }
        }
        throw new StorageException("Buffer pool exhausted: all " + capacity + " pages are pinned");
    }
}
