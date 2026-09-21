package dev.frierendb.engine.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageFileTest {

    @TempDir
    Path dir;

    private static byte[] filled(byte value) {
        byte[] data = new byte[PageFile.PAGE_SIZE];
        java.util.Arrays.fill(data, value);
        return data;
    }

    @Test
    void allocatedPagesAreZeroedAndNumberedSequentially() {
        try (PageFile file = PageFile.open(dir.resolve("t.pages"))) {
            assertEquals(0, file.allocate());
            assertEquals(1, file.allocate());
            assertEquals(2, file.pageCount());
            assertArrayEquals(new byte[PageFile.PAGE_SIZE], file.read(1));
        }
    }

    @Test
    void dataSurvivesReopen() {
        Path path = dir.resolve("t.pages");
        try (PageFile file = PageFile.open(path)) {
            file.allocate();
            file.write(0, filled((byte) 7));
            file.sync();
        }
        try (PageFile file = PageFile.open(path)) {
            assertEquals(1, file.pageCount());
            assertArrayEquals(filled((byte) 7), file.read(0));
        }
    }

    @Test
    void readingMissingPageFails() {
        try (PageFile file = PageFile.open(dir.resolve("t.pages"))) {
            assertThrows(StorageException.class, () -> file.read(0));
            assertThrows(StorageException.class, () -> file.read(-1));
        }
    }

    @Test
    void writingBeyondEndOrWrongSizeFails() {
        try (PageFile file = PageFile.open(dir.resolve("t.pages"))) {
            assertThrows(StorageException.class, () -> file.write(5, new byte[PageFile.PAGE_SIZE]));
            assertThrows(StorageException.class, () -> file.write(0, new byte[10]));
        }
    }

    @Test
    void rejectsFileThatIsNotWholePages() throws IOException {
        Path path = dir.resolve("broken.pages");
        Files.write(path, new byte[PageFile.PAGE_SIZE + 17]);
        assertThrows(StorageException.class, () -> PageFile.open(path));
    }

    @Test
    void truncateDropsTrailingPagesAndKeepsTheRest() {
        try (PageFile file = PageFile.open(dir.resolve("t.pages"))) {
            for (int i = 0; i < 5; i++) {
                byte[] page = new byte[PageFile.PAGE_SIZE];
                page[0] = (byte) (i + 1);
                file.write(i, page);
            }
            file.truncate(3);
            assertEquals(3, file.pageCount());
            assertEquals(3, file.read(2)[0]);
            assertThrows(StorageException.class, () -> file.read(3));
            file.truncate(3);
            assertEquals(3, file.allocate(), "the next page id follows the truncated end");
            assertThrows(StorageException.class, () -> file.truncate(10));
            assertThrows(StorageException.class, () -> file.truncate(-1));
        }
        try (PageFile reopened = PageFile.open(dir.resolve("t.pages"))) {
            assertEquals(4, reopened.pageCount());
        }
    }

    @Test
    void isOpenReflectsClose() {
        PageFile file = PageFile.open(dir.resolve("t.pages"));
        assertTrue(file.isOpen());
        file.close();
        assertFalse(file.isOpen());
    }
}
