package dev.frierendb.engine.exec;

import static dev.frierendb.engine.TestDatabases.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.Database;
import dev.frierendb.engine.FrierenException;
import dev.frierendb.engine.TestDatabases;
import dev.frierendb.engine.catalog.CatalogException;
import dev.frierendb.engine.catalog.IndexInfo;
import dev.frierendb.engine.sql.ReadOnlyViolationException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlIndexDdlTest {

    @TempDir
    Path dir;

    private Database db;

    @BeforeEach
    void setUp() {
        db = TestDatabases.university(dir);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void createAndDropIndexAreVisibleThroughTheDatabase() {
        db.execute("CREATE INDEX idx_gpa ON students (gpa)");
        db.execute("create index idx_major on students (major)");
        assertEquals(List.of(new IndexInfo("idx_gpa", "students", "gpa"),
                new IndexInfo("idx_major", "students", "major")), db.tableIndexes("students"));
        db.execute("DROP INDEX idx_gpa");
        assertEquals(List.of(new IndexInfo("idx_major", "students", "major")), db.tableIndexes("students"));
    }

    @Test
    void ifNotExistsAndIfExistsMakeRepeatsHarmless() {
        db.execute("CREATE INDEX idx_gpa ON students (gpa)");
        assertEquals(0, db.execute("CREATE INDEX IF NOT EXISTS idx_gpa ON students (gpa)").affectedRows());
        assertThrows(CatalogException.class, () -> db.execute("CREATE INDEX idx_gpa ON students (name)"));
        db.execute("DROP INDEX idx_gpa");
        assertEquals(0, db.execute("DROP INDEX IF EXISTS idx_gpa").affectedRows());
        assertThrows(CatalogException.class, () -> db.execute("DROP INDEX idx_gpa"));
    }

    @Test
    void unknownTablesAndColumnsAreRejected() {
        assertThrows(CatalogException.class, () -> db.execute("CREATE INDEX i ON nope (a)"));
        assertThrows(FrierenException.class, () -> db.execute("CREATE INDEX i ON students (nope)"));
        assertTrue(db.tableIndexes("students").isEmpty());
    }

    @Test
    void dataStatementsKeepWorkingOnAnIndexedTable() {
        db.execute("CREATE INDEX idx_gpa ON students (gpa)");
        db.execute("CREATE INDEX idx_name ON students (name)");
        assertEquals(1, db.execute("INSERT INTO students VALUES (7, 'Gus', 'CS', 3.9)").affectedRows());
        assertEquals(2, db.execute("UPDATE students SET gpa = 3.0 WHERE major = 'Math'").affectedRows());
        assertEquals(1, db.execute("DELETE FROM students WHERE name = 'Eve'").affectedRows());
        assertEquals(List.of(List.of("6")), rows(db, "select count(*) from students"));
    }

    @Test
    void aBadKeyInAMultiRowInsertStopsTheWholeStatement() {
        db.execute("CREATE INDEX idx_name ON students (name)");
        String tooLong = "n".repeat(300);
        assertThrows(FrierenException.class, () -> db.execute(
                "INSERT INTO students VALUES (8, 'Ok', 'CS', 1.0), (9, '" + tooLong + "', 'CS', 1.0)"));
        assertEquals(List.of(List.of("6")), rows(db, "select count(*) from students"));
    }

    @Test
    void indexesSurviveReopeningTheDatabase() {
        db.execute("CREATE INDEX idx_gpa ON students (gpa)");
        db.close();
        db = Database.open(dir, 8);
        assertEquals(List.of(new IndexInfo("idx_gpa", "students", "gpa")), db.tableIndexes("students"));
        assertThrows(CatalogException.class, () -> db.execute("CREATE INDEX idx_gpa ON students (gpa)"));
    }

    @Test
    void dropTableRemovesItsIndexesAndTheNameCanBeReused() {
        db.execute("CREATE INDEX idx_gpa ON students (gpa)");
        db.execute("DROP TABLE students");
        db.execute("CREATE TABLE students (sid INT, gpa DOUBLE)");
        db.execute("CREATE INDEX idx_gpa ON students (gpa)");
        assertEquals(1, db.tableIndexes("students").size());
    }

    @Test
    void indexStatementsAreNotReadOnly() {
        assertThrows(ReadOnlyViolationException.class,
                () -> db.query("CREATE INDEX i ON students (gpa)", QueryOptions.unlimited()));
        assertThrows(ReadOnlyViolationException.class,
                () -> db.query("DROP INDEX i", QueryOptions.unlimited()));
        assertTrue(db.tableIndexes("students").isEmpty());
    }
}
