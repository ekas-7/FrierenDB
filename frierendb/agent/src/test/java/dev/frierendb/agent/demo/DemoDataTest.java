package dev.frierendb.agent.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.Database;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoDataTest {

    @TempDir
    Path dir;

    @Test
    void seedsOnceAndSurvivesReopen() {
        try (Database db = Database.open(dir)) {
            assertTrue(DemoData.seedIfEmpty(db));
            assertFalse(DemoData.seedIfEmpty(db));
            assertEquals(List.of("courses", "enrollments", "students"), db.tableNames().stream().sorted().toList());
        }
        try (Database db = Database.open(dir)) {
            assertFalse(DemoData.seedIfEmpty(db));
            assertEquals(20, db.execute("SELECT * FROM students").rows().size());
        }
    }
}
