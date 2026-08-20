package dev.frierendb.engine.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
