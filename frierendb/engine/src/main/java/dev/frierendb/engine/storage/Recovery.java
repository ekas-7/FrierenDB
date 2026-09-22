package dev.frierendb.engine.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings the page files in a database directory to the state of the last committed transaction,
 * using the write-ahead log. It runs before any page file is opened.
 *
 * <ol>
 *   <li><b>Redo.</b> The after-image of every UPDATE belonging to a committed transaction is written
 *       back in log order, so changes that were committed but never reached the data files return.</li>
 *   <li><b>Undo.</b> Transactions with no COMMIT or ABORT record were cut off by the crash. Their
 *       before-images are written back, newest record first, and pages they allocated are cut off the
 *       end of the file; this removes changes that were evicted to disk before the crash.</li>
 *   <li>The files are synced and the log is emptied.</li>
 * </ol>
 * Transactions that ended with ABORT are skipped: their undo was completed and synced before the
 * ABORT record was written. Recovery is idempotent, so a crash during recovery is repaired by
 * running it again. The correctness argument relies on transactions never overlapping, which the
 * buffer pool guarantees.
 */
public final class Recovery {

    /** What recovery did. */
    public record Report(int committedTransactions, int unfinishedTransactions, int pagesRedone,
            int pagesUndone, boolean tornLogTail) {

        public boolean didWork() {
            return committedTransactions > 0 || unfinishedTransactions > 0 || tornLogTail;
        }
    }

    private Recovery() {}

    public static Report recover(Path dir) {
        Path logPath = dir.resolve(Wal.FILE_NAME);
        Wal.Contents contents = Wal.read(logPath);
        if (contents.records().isEmpty()) {
            if (contents.tornTail()) {
                emptyLog(logPath);
            }
            return new Report(0, 0, 0, 0, contents.tornTail());
        }

        Set<Long> committed = new HashSet<>();
        Set<Long> aborted = new HashSet<>();
        Set<Long> seen = new HashSet<>();
        for (Wal.LogRecord record : contents.records()) {
            seen.add(record.txId());
            if (record.type() == Wal.Type.COMMIT) {
                committed.add(record.txId());
            } else if (record.type() == Wal.Type.ABORT) {
                aborted.add(record.txId());
            }
        }
        Set<Long> unfinished = new HashSet<>(seen);
        unfinished.removeAll(committed);
        unfinished.removeAll(aborted);

        Map<String, PageFile> files = new HashMap<>();
        int redone = 0;
        int undone = 0;
        try {
            for (Wal.LogRecord record : contents.records()) {
                if (record.type() == Wal.Type.UPDATE && committed.contains(record.txId())) {
                    PageFile file = open(dir, files, record.file());
                    if (file != null) {
                        put(file, record.pageId(), record.after());
                        redone++;
                    }
                }
            }
            List<Wal.LogRecord> all = contents.records();
            for (int i = all.size() - 1; i >= 0; i--) {
                Wal.LogRecord record = all.get(i);
                if (record.type() != Wal.Type.UPDATE || !unfinished.contains(record.txId())) {
                    continue;
                }
                PageFile file = open(dir, files, record.file());
                if (file == null) {
                    continue;
                }
                if (record.before() == null) {
                    if (record.pageId() < file.pageCount()) {
                        file.truncate(record.pageId());
                    }
                } else {
                    put(file, record.pageId(), record.before());
                }
                undone++;
            }
            for (PageFile file : files.values()) {
                file.sync();
            }
        } finally {
            for (PageFile file : files.values()) {
                file.close();
            }
        }
        emptyLog(logPath);
        return new Report(committed.size(), unfinished.size(), redone, undone, contents.tornTail());
    }

    private static PageFile open(Path dir, Map<String, PageFile> files, String name) {
        PageFile existing = files.get(name);
        if (existing != null) {
            return existing;
        }
        if (!name.equals(Path.of(name).getFileName().toString())) {
            throw new StorageException("Write-ahead log names a file outside the database directory: " + name);
        }
        Path path = dir.resolve(name);
        if (!Files.exists(path)) {
            return null;
        }
        PageFile file = PageFile.open(path);
        files.put(name, file);
        return file;
    }

    /** Writes a page, growing the file with zero pages first if the log refers to a page beyond its end. */
    private static void put(PageFile file, int pageId, byte[] bytes) {
        while (file.pageCount() < pageId) {
            file.allocate();
        }
        file.write(pageId, bytes);
    }

    private static void emptyLog(Path logPath) {
        try (Wal wal = Wal.open(logPath)) {
            wal.reset();
        }
    }
}
