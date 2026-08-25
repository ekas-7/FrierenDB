package dev.frierendb.engine.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.schema.Column;
import dev.frierendb.engine.schema.Row;
import dev.frierendb.engine.schema.Schema;
import dev.frierendb.engine.storage.BufferPool;
import dev.frierendb.engine.types.DataType;
import dev.frierendb.engine.types.Value;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogTest {

    @TempDir
    Path dir;

    private static final Schema STUDENTS = new Schema(List.of(
            Column.required("sid", DataType.INT),
            Column.required("name", DataType.TEXT),
            Column.optional("gpa", DataType.DOUBLE)));

    private static List<Row> rows(Table table) {
        List<Row> out = new ArrayList<>();
        for (Iterator<Table.RowEntry> it = table.scan(); it.hasNext(); ) {
            out.add(it.next().row());
        }
        return out;
    }

    @Test
    void createdTableStoresAndScansRows() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            Table students = catalog.createTable("Students", STUDENTS);
            students.insert(Row.of(Value.of(1), Value.of("Ada"), Value.of(3.9)));
            students.insert(Row.of(Value.of(2), Value.of("Bob"), Value.NULL));
            assertEquals(2, rows(students).size());
            assertEquals(List.of("students"), catalog.tableNames());
        }
    }

    @Test
    void tableLookupIsCaseInsensitive() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            catalog.createTable("Students", STUDENTS);
            assertTrue(catalog.exists("STUDENTS"));
            assertEquals("students", catalog.table("sTuDeNtS").name());
        }
    }

    @Test
    void schemaAndRowsSurviveReopen() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            catalog.createTable("students", STUDENTS)
                    .insert(Row.of(Value.of(1), Value.of("Ada"), Value.of(3.9)));
        }
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            Table students = catalog.table("students");
            assertEquals(STUDENTS, students.schema());
            assertEquals(List.of(Row.of(Value.of(1), Value.of("Ada"), Value.of(3.9))), rows(students));
        }
    }

    @Test
    void duplicateTableIsRejected() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            catalog.createTable("t", STUDENTS);
            assertThrows(CatalogException.class, () -> catalog.createTable("T", STUDENTS));
        }
    }

    @Test
    void unknownTableIsRejected() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            assertThrows(CatalogException.class, () -> catalog.table("nope"));
            assertThrows(CatalogException.class, () -> catalog.dropTable("nope"));
        }
    }

    @Test
    void dropTableRemovesDataAndDefinition() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            catalog.createTable("t", STUDENTS).insert(Row.of(Value.of(1), Value.of("x"), Value.NULL));
            catalog.dropTable("t");
            assertFalse(catalog.exists("t"));
            assertFalse(Files.exists(dir.resolve("t.tbl")));
        }
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            assertTrue(catalog.tableNames().isEmpty());
        }
    }

    @Test
    void tableNamesCannotEscapeTheDatabaseDirectory() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            for (String bad : List.of("../evil", "a/b", "a.b", "", "1abc", "with space", "x".repeat(65))) {
                assertThrows(CatalogException.class, () -> catalog.createTable(bad, STUDENTS), bad);
            }
        }
    }

    @Test
    void missingDataFileIsReportedOnOpen() throws IOException {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            catalog.createTable("t", STUDENTS);
        }
        Files.delete(dir.resolve("t.tbl"));
        assertThrows(CatalogException.class, () -> Catalog.open(dir, new BufferPool(8)));
    }

    @Test
    void corruptCatalogIsReportedOnOpen() throws IOException {
        Files.write(dir.resolve("catalog.bin"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(CatalogException.class, () -> Catalog.open(dir, new BufferPool(8)));
    }

    @Test
    void rowsViolatingSchemaAreRejected() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            Table students = catalog.createTable("t", STUDENTS);
            assertThrows(RuntimeException.class,
                    () -> students.insert(Row.of(Value.NULL, Value.of("x"), Value.NULL)));
        }
    }

    @Test
    void updateAndDeleteWorkThroughTheTable() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(8))) {
            Table t = catalog.createTable("t", STUDENTS);
            var id = t.insert(Row.of(Value.of(1), Value.of("Ada"), Value.NULL));
            t.update(id, Row.of(Value.of(1), Value.of("Ada L"), Value.of(4.0)));
            assertEquals(Value.of("Ada L"), t.get(id).get(1));
            t.delete(id);
            assertTrue(rows(t).isEmpty());
        }
    }
}
