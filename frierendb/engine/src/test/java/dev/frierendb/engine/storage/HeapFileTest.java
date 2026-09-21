package dev.frierendb.engine.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapFileTest {

    @TempDir
    Path dir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> scanAll(HeapFile heap) {
        List<String> out = new ArrayList<>();
        for (Iterator<HeapFile.Entry> it = heap.scan(); it.hasNext(); ) {
            out.add(new String(it.next().bytes(), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void insertGetDelete() {
        try (PageFile file = PageFile.open(dir.resolve("h.pages"))) {
            HeapFile heap = new HeapFile(file, new BufferPool(4));
            RecordId id = heap.insert(bytes("hello"));
            assertArrayEquals(bytes("hello"), heap.get(id));
            heap.delete(id);
            assertThrows(StorageException.class, () -> heap.get(id));
            assertTrue(scanAll(heap).isEmpty());
        }
    }

    @Test
    void manyRecordsSpanPagesAndScanReturnsAll() {
        try (PageFile file = PageFile.open(dir.resolve("h.pages"))) {
            HeapFile heap = new HeapFile(file, new BufferPool(3));
            Map<RecordId, String> expected = new HashMap<>();
            for (int i = 0; i < 2000; i++) {
                String value = "row-" + i + "-" + "x".repeat(i % 50);
                expected.put(heap.insert(bytes(value)), value);
            }
            assertTrue(heap.pageCount() > 3, "data must not fit in the 3-frame pool");
            Map<RecordId, String> scanned = new HashMap<>();
            for (Iterator<HeapFile.Entry> it = heap.scan(); it.hasNext(); ) {
                HeapFile.Entry e = it.next();
                scanned.put(e.id(), new String(e.bytes(), StandardCharsets.UTF_8));
            }
            assertEquals(expected, scanned);
        }
    }

    @Test
    void deletedSpaceIsReusedInsteadOfGrowingTheFile() {
        try (PageFile file = PageFile.open(dir.resolve("h.pages"))) {
            HeapFile heap = new HeapFile(file, new BufferPool(4));
            List<RecordId> ids = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                ids.add(heap.insert(new byte[200]));
            }
            int pages = heap.pageCount();
            ids.forEach(heap::delete);
            for (int i = 0; i < 100; i++) {
                heap.insert(new byte[200]);
            }
            assertEquals(pages, heap.pageCount());
        }
    }

    @Test
    void updateRelocatesWhenPageIsFull() {
        try (PageFile file = PageFile.open(dir.resolve("h.pages"))) {
            HeapFile heap = new HeapFile(file, new BufferPool(4));
            RecordId first = heap.insert(new byte[2000]);
            heap.insert(new byte[2000]);
            RecordId moved = heap.update(first, new byte[3000]);
            assertTrue(moved.pageId() != first.pageId() || moved.slot() != first.slot());
            assertEquals(3000, heap.get(moved).length);
            assertEquals(2, scanAll(heap).size());
        }
    }

    @Test
    void updateKeepsIdWhenRecordStillFits() {
        try (PageFile file = PageFile.open(dir.resolve("h.pages"))) {
            HeapFile heap = new HeapFile(file, new BufferPool(4));
            RecordId id = heap.insert(bytes("short"));
            assertEquals(id, heap.update(id, bytes("a bit longer text")));
            assertArrayEquals(bytes("a bit longer text"), heap.get(id));
        }
    }

    @Test
    void dataSurvivesFlushAndReopen() {
        Path path = dir.resolve("h.pages");
        RecordId id;
        try (PageFile file = PageFile.open(path)) {
            HeapFile heap = new HeapFile(file, new BufferPool(2));
            for (int i = 0; i < 300; i++) {
                heap.insert(bytes("filler-" + i));
            }
            id = heap.insert(bytes("needle"));
            heap.flush();
        }
        try (PageFile file = PageFile.open(path)) {
            HeapFile heap = new HeapFile(file, new BufferPool(2));
            assertArrayEquals(bytes("needle"), heap.get(id));
            assertEquals(301, scanAll(heap).size());
        }
    }

    @Test
    void unflushedDataIsLostOnReopenBecauseThereIsNoWriteAheadLogYet() {
        Path path = dir.resolve("h.pages");
        try (PageFile file = PageFile.open(path)) {
            HeapFile heap = new HeapFile(file, new BufferPool(8));
            heap.insert(bytes("never flushed"));
        }
        try (PageFile file = PageFile.open(path)) {
            HeapFile heap = new HeapFile(file, new BufferPool(8));
            assertTrue(scanAll(heap).isEmpty());
        }
    }

    @Test
    void oversizedRecordIsRejected() {
        try (PageFile file = PageFile.open(dir.resolve("h.pages"))) {
            HeapFile heap = new HeapFile(file, new BufferPool(2));
            assertThrows(StorageException.class,
                    () -> heap.insert(new byte[SlottedPage.MAX_RECORD_SIZE + 1]));
        }
    }

    @Test
    void scanDoesNotLeavePagesPinned() {
        try (PageFile file = PageFile.open(dir.resolve("h.pages"))) {
            BufferPool pool = new BufferPool(1);
            HeapFile heap = new HeapFile(file, pool);
            for (int i = 0; i < 500; i++) {
                heap.insert(bytes("r" + i));
            }
            Iterator<HeapFile.Entry> abandoned = heap.scan();
            abandoned.next();
            assertEquals(500, scanAll(heap).size());
        }
    }

    @Test
    void reloadRebuildsFreeSpaceFromTheCurrentPages() {
        try (PageFile file = PageFile.open(dir.resolve("h.pages"))) {
            BufferPool pool = new BufferPool(4);
            HeapFile heap = new HeapFile(file, pool);
            RecordId a = heap.insert(new byte[3000]);
            heap.insert(new byte[3000]);
            assertEquals(2, heap.pageCount());
            heap.delete(a);
            heap.reload();
            RecordId reused = heap.insert(new byte[3000]);
            assertEquals(a.pageId(), reused.pageId(), "space freed before the reload is found again");
            assertEquals(2, heap.pageCount());
        }
    }
}
