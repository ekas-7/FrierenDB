package dev.frierendb.engine.exec;

import static dev.frierendb.engine.TestDatabases.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.frierendb.engine.Database;
import dev.frierendb.engine.TestDatabases;
import dev.frierendb.engine.catalog.CatalogException;
import dev.frierendb.engine.schema.SchemaException;
import dev.frierendb.engine.sql.SqlException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlWriteTest {

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

    private String count(String table) {
        return rows(db, "select count(*) from " + table).get(0).get(0);
    }

    @Test
    void insertWithColumnListLeavesOthersNull() {
        assertEquals(1, db.execute("insert into students (sid, name) values (7, 'Gus')").affectedRows());
        assertEquals(List.of(List.of("Gus", "NULL", "NULL")),
                rows(db, "select name, major, gpa from students where sid = 7"));
    }

    @Test
    void insertWidensIntegersAndEvaluatesConstantExpressions() {
        db.execute("insert into students values (8, 'Hal', 'CS', 4), (9, 'Ivy', 'CS', 1.5 + 1.5)");
        assertEquals(List.of(List.of("4.0"), List.of("3.0")),
                rows(db, "select gpa from students where sid >= 8 order by sid"));
    }

    @Test
    void multiRowInsertIsAllOrNothingWhenAValueIsInvalid() {
        assertThrows(SchemaException.class,
                () -> db.execute("insert into students values (8, 'Hal', 'CS', 3.0), (9, NULL, 'CS', 3.0)"));
        assertEquals("6", count("students"));
    }

    @Test
    void invalidInsertsAreRejected() {
        assertThrows(SqlException.class, () -> db.execute("insert into students values (1, 'x')"));
        assertThrows(SqlException.class, () -> db.execute("insert into students (sid, sid) values (1, 2)"));
        assertThrows(SchemaException.class, () -> db.execute("insert into students (nope) values (1)"));
        assertThrows(SchemaException.class, () -> db.execute("insert into students values ('a', 'x', 'y', 1.0)"));
        assertThrows(SqlException.class, () -> db.execute("insert into students values (sid, 'x', 'y', 1.0)"));
        assertThrows(SqlException.class, () -> db.execute("insert into students values (count(*), 'x', 'y', 1.0)"));
        assertEquals("6", count("students"));
    }

    @Test
    void updateModifiesMatchingRowsAndReportsCount() {
        assertEquals(1, db.execute("update students set gpa = gpa + 0.25 where sid = 5").affectedRows());
        assertEquals(List.of(List.of("2.75")), rows(db, "select gpa from students where sid = 5"));
        assertEquals(6, db.execute("update students set gpa = gpa + 0").affectedRows());
        assertEquals(List.of(List.of("NULL")), rows(db, "select gpa from students where sid = 4"));
    }

    @Test
    void updateAssignmentsReadTheOriginalRow() {
        db.execute("create table pair (a INT, b INT)");
        db.execute("insert into pair values (1, 2)");
        db.execute("update pair set a = b, b = a");
        assertEquals(List.of(List.of("2", "1")), rows(db, "select a, b from pair"));
    }

    @Test
    void updateDoesNotRevisitRowsItJustWrote() {
        assertEquals(6, db.execute("update students set sid = sid + 1000").affectedRows());
        assertEquals(List.of(List.of("1001"), List.of("1002")),
                rows(db, "select sid from students order by sid limit 2"));
        assertEquals("6", count("students"));
    }

    @Test
    void failedUpdateChangesNothing() {
        assertThrows(SchemaException.class, () -> db.execute("update students set name = NULL"));
        assertEquals(List.of(List.of("Ada")), rows(db, "select name from students where sid = 1"));
        assertThrows(SqlException.class, () -> db.execute("update students set gpa = 1, gpa = 2"));
        assertThrows(SchemaException.class, () -> db.execute("update students set nope = 1"));
    }

    @Test
    void growingRowsRelocatesThemWithoutLosingAny() {
        db.execute("create table docs (id INT NOT NULL, body TEXT)");
        db.execute("insert into docs values " + IntStream.range(0, 300)
                .mapToObj(i -> "(" + i + ", 'short')").collect(Collectors.joining(", ")));
        String big = "x".repeat(900);
        assertEquals(300, db.execute("update docs set body = '" + big + "'").affectedRows());
        assertEquals("300", count("docs"));
        assertEquals(List.of(List.of("900", "300")), rows(db, "select min(length(body)), count(*) from docs"));
    }

    @Test
    void deleteRemovesMatchingRows() {
        assertEquals(1, db.execute("delete from enrollments where grade is null").affectedRows());
        assertEquals("5", count("enrollments"));
        assertEquals(6, db.execute("delete from students").affectedRows());
        assertEquals("0", count("students"));
        assertEquals(0, db.execute("delete from students").affectedRows());
    }

    @Test
    void deletedSpaceCanBeReused() {
        db.execute("delete from students where sid > 2");
        db.execute("insert into students values (30, 'Zed', 'Art', 1.0)");
        assertEquals("3", count("students"));
    }

    @Test
    void createAndDropTables() {
        db.execute("create table if not exists students (x INT)");
        assertEquals(List.of("sid", "name", "major", "gpa"),
                db.query("select * from students", QueryOptions.unlimited()).columns());
        assertThrows(CatalogException.class, () -> db.execute("create table students (x INT)"));
        db.execute("drop table enrollments");
        assertThrows(CatalogException.class, () -> db.execute("select * from enrollments"));
        assertThrows(CatalogException.class, () -> db.execute("drop table enrollments"));
        db.execute("drop table if exists enrollments");
        assertFalse(db.tableNames().contains("enrollments"));
    }

    @Test
    void quotedTableNamesCannotEscapeTheDatabaseDirectory() {
        assertThrows(CatalogException.class, () -> db.execute("create table \"../evil\" (a INT)"));
        assertFalse(Files.exists(dir.getParent().resolve("evil.tbl")));
    }

    @Test
    void dataAndSchemaSurviveReopen() {
        db.execute("update students set gpa = 1.25 where sid = 1");
        db.close();
        db = Database.open(dir, 8);
        assertEquals(List.of(List.of("Ada", "1.25")), rows(db, "select name, gpa from students where sid = 1"));
        assertEquals("6", count("students"));
        assertEquals(List.of("courses", "enrollments", "students"), db.tableNames());
    }

    @Test
    void worksWhenDataIsMuchLargerThanTheBufferPool() {
        db.execute("create table nums (n INT NOT NULL)");
        for (int batch = 0; batch < 10; batch++) {
            int start = batch * 500;
            db.execute("insert into nums values " + IntStream.range(start, start + 500)
                    .mapToObj(i -> "(" + i + ")").collect(Collectors.joining(", ")));
        }
        assertEquals(List.of(List.of("5000", "12497500", "0", "4999")),
                rows(db, "select count(*), sum(n), min(n), max(n) from nums"));
        assertEquals(List.of(List.of("2500")), rows(db, "select count(*) from nums where n % 2 = 0"));
    }

    @Test
    void scriptRunsEveryStatementAndStopsAtTheFirstError() {
        assertEquals(2, db.executeScript("insert into courses values (13, 'Poetry', 'Art'); delete from courses where cid = 13;"));
        assertThrows(SqlException.class, () -> db.executeScript("insert into courses values (14, 'A', 'B'); select from;"));
        assertEquals("3", count("courses"));
    }
}
