package dev.frierendb.engine.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.storage.Wal.LogRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalTest {

    @TempDir
    Path dir;

    private static byte[] page(int fill) {
        byte[] page = new byte[PageFile.PAGE_SIZE];
        Arrays.fill(page, (byte) fill);
        return page;
    }

    private Path log() {
        return dir.resolve(Wal.FILE_NAME);
    }

    @Test
    void aNewLogHoldsOnlyItsHeader() {
        try (Wal wal = Wal.open(log())) {
            assertEquals(8, wal.size());
            assertTrue(wal.contents().records().isEmpty());
            assertFalse(wal.contents().tornTail());
        }
    }

    @Test
    void recordsRoundTripWithAndWithoutABeforeImage() {
        try (Wal wal = Wal.open(log())) {
            wal.append(LogRecord.update(1, "people.tbl", 3, page(1), page(2)));
            wal.append(LogRecord.update(1, "people.idx_id.idx", 0, null, page(9)));
            wal.append(LogRecord.commit(1));
            wal.append(LogRecord.update(2, "people.tbl", 3, page(2), page(4)));
            wal.append(LogRecord.abort(2));
            wal.force();
            List<LogRecord> records = wal.contents().records();
            assertEquals(5, records.size());
            assertEquals(Wal.Type.UPDATE, records.get(0).type());
            assertEquals("people.tbl", records.get(0).file());
            assertEquals(3, records.get(0).pageId());
            assertArrayEquals(page(1), records.get(0).before());
            assertArrayEquals(page(2), records.get(0).after());
            assertNull(records.get(1).before());
            assertArrayEquals(page(9), records.get(1).after());
            assertEquals(Wal.Type.COMMIT, records.get(2).type());
            assertEquals(1, records.get(2).txId());
            assertEquals(Wal.Type.ABORT, records.get(4).type());
            assertEquals(2, records.get(4).txId());
        }
    }

    @Test
    void reopeningKeepsRecordsContinuesAppendingAndNumbersTransactionsAfterTheLargest() {
        try (Wal wal = Wal.open(log())) {
            assertEquals(1, wal.nextTxId());
            wal.append(LogRecord.commit(7));
            wal.force();
        }
        try (Wal wal = Wal.open(log())) {
            assertEquals(8, wal.nextTxId());
            wal.append(LogRecord.commit(8));
            assertEquals(2, wal.contents().records().size());
        }
    }

    @Test
    void aFrameCutShortIsIgnoredAndTrimmedOnOpen() throws IOException {
        long goodSize;
        try (Wal wal = Wal.open(log())) {
            wal.append(LogRecord.update(1, "a.tbl", 0, page(1), page(2)));
            wal.append(LogRecord.commit(1));
            wal.force();
            goodSize = wal.size();
            wal.append(LogRecord.update(2, "a.tbl", 0, page(2), page(3)));
            wal.force();
        }
        long fullSize = Files.size(log());
        for (long cut : new long[] {goodSize + 1, goodSize + 7, goodSize + 8, goodSize + 100, fullSize - 1}) {
            Path copy = dir.resolve("cut-" + cut + ".log");
            byte[] bytes = Files.readAllBytes(log());
            Files.write(copy, Arrays.copyOf(bytes, (int) cut));
            Wal.Contents contents = Wal.read(copy);
            assertEquals(2, contents.records().size(), "cut at " + cut);
            assertEquals(goodSize, contents.validBytes());
            assertTrue(contents.tornTail());
            try (Wal reopened = Wal.open(copy)) {
                assertEquals(goodSize, reopened.size());
                reopened.append(LogRecord.commit(5));
                assertEquals(3, reopened.contents().records().size());
            }
        }
    }

    @Test
    void aCorruptedByteStopsTheReadAtThatRecord() throws IOException {
        long firstEnd;
        try (Wal wal = Wal.open(log())) {
            wal.append(LogRecord.update(1, "a.tbl", 0, page(1), page(2)));
            firstEnd = wal.size();
            wal.append(LogRecord.update(1, "a.tbl", 1, page(1), page(2)));
            wal.append(LogRecord.commit(1));
            wal.force();
        }
        byte[] bytes = Files.readAllBytes(log());
        bytes[(int) firstEnd + 200] ^= 0x55;
        Files.write(log(), bytes);
        Wal.Contents contents = Wal.read(log());
        assertEquals(1, contents.records().size());
        assertEquals(firstEnd, contents.validBytes());
        assertTrue(contents.tornTail());
    }

    @Test
    void garbageAfterTheLastRecordIsIgnored() throws IOException {
        try (Wal wal = Wal.open(log())) {
            wal.append(LogRecord.commit(1));
            wal.force();
        }
        Files.write(log(), concat(Files.readAllBytes(log()), new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}));
        Wal.Contents contents = Wal.read(log());
        assertEquals(1, contents.records().size());
        assertTrue(contents.tornTail());
    }

    @Test
    void resetEmptiesTheLog() {
        try (Wal wal = Wal.open(log())) {
            wal.append(LogRecord.update(1, "a.tbl", 0, page(1), page(2)));
            wal.append(LogRecord.commit(1));
            wal.reset();
            assertEquals(8, wal.size());
            assertTrue(wal.contents().records().isEmpty());
            wal.append(LogRecord.commit(2));
            assertEquals(1, wal.contents().records().size());
        }
    }

    @Test
    void aMissingOrHeaderlessLogIsEmptyAndAForeignFileIsRefused() throws IOException {
        assertTrue(Wal.read(dir.resolve("absent.log")).records().isEmpty());
        Files.write(log(), new byte[] {1, 2, 3});
        try (Wal wal = Wal.open(log())) {
            assertEquals(8, wal.size());
        }
        Path foreign = dir.resolve("foreign.log");
        Files.write(foreign, new byte[64]);
        assertThrows(StorageException.class, () -> Wal.open(foreign));
        assertThrows(StorageException.class, () -> Wal.read(foreign));
    }

    @Test
    void rejectsBadNamesAndPageSizes() {
        try (Wal wal = Wal.open(log())) {
            assertThrows(StorageException.class, () -> wal.append(LogRecord.update(1, "", 0, null, page(1))));
            assertThrows(StorageException.class,
                    () -> wal.append(LogRecord.update(1, "x".repeat(256), 0, null, page(1))));
            assertThrows(StorageException.class,
                    () -> wal.append(LogRecord.update(1, "a.tbl", 0, null, new byte[10])));
            assertTrue(wal.contents().records().isEmpty(), "rejected records leave no trace");
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
