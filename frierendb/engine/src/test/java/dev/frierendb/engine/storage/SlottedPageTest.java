package dev.frierendb.engine.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlottedPageTest {

    private SlottedPage page;

    @BeforeEach
    void setUp() {
        page = SlottedPage.format(new byte[PageFile.PAGE_SIZE]);
    }

    private static byte[] record(int length, int fill) {
        byte[] r = new byte[length];
        Arrays.fill(r, (byte) fill);
        return r;
    }

    @Test
    void insertsAndReadsBack() {
        int a = page.insert(record(10, 1));
        int b = page.insert(record(20, 2));
        assertEquals(0, a);
        assertEquals(1, b);
        assertArrayEquals(record(10, 1), page.get(a));
        assertArrayEquals(record(20, 2), page.get(b));
    }

    @Test
    void freshPageHasMaximalFreeSpace() {
        assertEquals(PageFile.PAGE_SIZE - SlottedPage.HEADER_SIZE, page.freeBytes());
    }

    @Test
    void largestRecordFitsExactlyOnce() {
        assertEquals(0, page.insert(record(SlottedPage.MAX_RECORD_SIZE, 5)));
        assertEquals(0, page.freeBytes());
        assertEquals(-1, page.insert(record(1, 1)));
    }

    @Test
    void oversizedOrEmptyRecordsAreRejected() {
        assertThrows(StorageException.class, () -> page.insert(record(SlottedPage.MAX_RECORD_SIZE + 1, 0)));
        assertThrows(StorageException.class, () -> page.insert(new byte[0]));
    }

    @Test
    void deletedSlotIsReused() {
        page.insert(record(10, 1));
        int b = page.insert(record(10, 2));
        page.delete(0);
        assertFalse(page.isLive(0));
        assertEquals(0, page.insert(record(8, 3)));
        assertArrayEquals(record(10, 2), page.get(b));
        assertEquals(2, page.slotCount());
    }

    @Test
    void readingDeletedSlotFails() {
        int slot = page.insert(record(4, 1));
        page.delete(slot);
        assertThrows(StorageException.class, () -> page.get(slot));
        assertThrows(StorageException.class, () -> page.delete(slot));
        assertThrows(StorageException.class, () -> page.get(99));
    }

    @Test
    void compactionReclaimsFragmentedSpace() {
        int[] slots = new int[8];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = page.insert(record(500, i));
        }
        for (int i = 0; i < slots.length; i += 2) {
            page.delete(slots[i]);
        }
        int big = page.insert(record(1900, 99));
        assertTrue(big >= 0, "four freed 500-byte holes must be merged to fit 1900 bytes");
        for (int i = 1; i < slots.length; i += 2) {
            assertArrayEquals(record(500, i), page.get(slots[i]));
        }
        assertArrayEquals(record(1900, 99), page.get(big));
    }

    @Test
    void updateShrinksInPlace() {
        int slot = page.insert(record(100, 1));
        assertTrue(page.update(slot, record(40, 2)));
        assertArrayEquals(record(40, 2), page.get(slot));
    }

    @Test
    void updateGrowsKeepingSlotAndNeighbours() {
        int a = page.insert(record(100, 1));
        int b = page.insert(record(100, 2));
        assertTrue(page.update(a, record(400, 3)));
        assertArrayEquals(record(400, 3), page.get(a));
        assertArrayEquals(record(100, 2), page.get(b));
    }

    @Test
    void updateReportsFailureWhenPageTooFull() {
        int a = page.insert(record(2000, 1));
        page.insert(record(2000, 2));
        assertFalse(page.update(a, record(3000, 3)));
        assertArrayEquals(record(2000, 1), page.get(a));
    }

    @Test
    void rejectsPageOfWrongSize() {
        assertThrows(StorageException.class, () -> new SlottedPage(new byte[10]));
    }
}
