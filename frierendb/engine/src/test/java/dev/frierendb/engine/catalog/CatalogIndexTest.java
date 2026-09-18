package dev.frierendb.engine.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.FrierenException;
import dev.frierendb.engine.schema.Column;
import dev.frierendb.engine.schema.Row;
import dev.frierendb.engine.schema.Schema;
import dev.frierendb.engine.storage.BufferPool;
import dev.frierendb.engine.storage.RecordId;
import dev.frierendb.engine.types.DataType;
import dev.frierendb.engine.types.Value;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogIndexTest {

    @TempDir
    Path dir;

    private static final Schema PEOPLE = new Schema(List.of(
            new Column("id", DataType.INT, false),
            new Column("name", DataType.TEXT, true),
            new Column("score", DataType.DOUBLE, true)));

    private static Row row(long id, String name, Double score) {
        return PEOPLE.conform(List.of(Value.of(id),
                name == null ? Value.NULL : Value.of(name),
                score == null ? Value.NULL : Value.of(score)));
    }

    private static List<Long> ids(Iterator<Table.RowEntry> it) {
        List<Long> out = new ArrayList<>();
        it.forEachRemaining(e -> out.add(((Value.IntValue) e.row().get(0)).value()));
        return out;
    }

    @Test
    void creatingAnIndexBackfillsExistingRowsAndSkipsNulls() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            Table people = catalog.createTable("people", PEOPLE);
            people.insert(row(1, "ann", 2.5));
            people.insert(row(2, "bob", null));
            people.insert(row(3, "cy", 3.5));
            people.insert(row(4, "di", 2.5));
            IndexInfo info = catalog.createIndex("idx_score", "people", "SCORE");
            assertEquals(new IndexInfo("idx_score", "people", "score"), info);
            assertEquals(List.of(info), people.indexes());
            assertEquals(3, people.indexSize("idx_score"), "the NULL score is not indexed");
            assertEquals(List.of(1L, 4L), ids(people.indexScan("idx_score", Value.of(2.5), true, Value.of(2.5), true)));
            assertEquals(List.of(1L, 4L, 3L), ids(people.indexScan("idx_score", null, true, null, true)));
        }
    }

    @Test
    void insertUpdateAndDeleteKeepTheIndexInStepWithTheTable() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            Table people = catalog.createTable("people", PEOPLE);
            catalog.createIndex("idx_name", "people", "name");
            RecordId a = people.insert(row(1, "ann", 1.0));
            RecordId b = people.insert(row(2, "bob", 2.0));
            people.insert(row(3, null, 3.0));
            assertEquals(2, people.indexSize("idx_name"));

            RecordId movedB = people.update(b, row(2, "bobby", 2.0));
            assertEquals(List.of(), ids(people.indexScan("idx_name", Value.of("bob"), true, Value.of("bob"), true)));
            assertEquals(List.of(2L), ids(people.indexScan("idx_name", Value.of("bobby"), true, Value.of("bobby"), true)));

            people.update(movedB, row(2, null, 2.0));
            assertEquals(1, people.indexSize("idx_name"), "updating a key to NULL removes the entry");
            people.update(a, row(1, "ann", 9.9));
            assertEquals(List.of(1L), ids(people.indexScan("idx_name", Value.of("ann"), true, Value.of("ann"), true)));

            people.delete(a);
            assertEquals(0, people.indexSize("idx_name"));
        }
    }

    @Test
    void indexesSurviveReopenAndAreDroppedWithTheirTable() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            Table people = catalog.createTable("people", PEOPLE);
            for (int i = 0; i < 500; i++) {
                people.insert(row(i, "n" + i, (double) i));
            }
            catalog.createIndex("idx_id", "people", "id");
            catalog.createIndex("idx_score", "people", "score");
        }
        assertTrue(Files.exists(dir.resolve("people.idx_id.idx")));
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            Table people = catalog.table("people");
            assertEquals(List.of("idx_id", "idx_score"), people.indexes().stream().map(IndexInfo::name).toList());
            assertEquals(List.of(250L), ids(people.indexScan("idx_id", Value.of(250L), true, Value.of(250L), true)));
            assertEquals(500, people.indexSize("idx_score"));
            catalog.dropTable("people");
        }
        assertFalse(Files.exists(dir.resolve("people.idx_id.idx")));
        assertFalse(Files.exists(dir.resolve("people.idx_score.idx")));
    }

    @Test
    void dropIndexRemovesTheFileAndStopsMaintenance() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            Table people = catalog.createTable("people", PEOPLE);
            catalog.createIndex("idx_id", "people", "id");
            assertTrue(catalog.indexExists("IDX_ID"));
            catalog.dropIndex("idx_id");
            assertFalse(catalog.indexExists("idx_id"));
            assertFalse(Files.exists(dir.resolve("people.idx_id.idx")));
            people.insert(row(1, "ann", 1.0));
            assertTrue(people.indexes().isEmpty());
            assertThrows(CatalogException.class, () -> catalog.dropIndex("idx_id"));
        }
    }

    @Test
    void rejectsDuplicateNamesBadNamesAndUnknownTargets() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            catalog.createTable("people", PEOPLE);
            catalog.createTable("other", PEOPLE);
            catalog.createIndex("idx", "people", "id");
            assertThrows(CatalogException.class, () -> catalog.createIndex("IDX", "other", "id"));
            assertThrows(CatalogException.class, () -> catalog.createIndex("bad name", "people", "id"));
            assertThrows(CatalogException.class, () -> catalog.createIndex("i2", "missing", "id"));
            assertThrows(FrierenException.class, () -> catalog.createIndex("i3", "people", "missing"));
            assertEquals(1, catalog.table("people").indexes().size());
            assertTrue(catalog.table("other").indexes().isEmpty());
        }
    }

    @Test
    void anOversizedTextKeyIsRejectedBeforeTheRowIsStored() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            Table people = catalog.createTable("people", PEOPLE);
            catalog.createIndex("idx_name", "people", "name");
            people.insert(row(1, "ok", 1.0));
            assertThrows(FrierenException.class, () -> people.insert(row(2, "z".repeat(300), 1.0)));
            assertEquals(1, ids(people.scan()).size(), "the failed insert left no row behind");
            assertEquals(1, people.indexSize("idx_name"));
        }
    }

    @Test
    void failedIndexBuildLeavesNothingBehind() {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            Table people = catalog.createTable("people", PEOPLE);
            people.insert(row(1, "z".repeat(300), 1.0));
            assertThrows(FrierenException.class, () -> catalog.createIndex("idx_name", "people", "name"));
            assertTrue(people.indexes().isEmpty());
            assertFalse(catalog.indexExists("idx_name"));
            assertFalse(Files.exists(dir.resolve("people.idx_name.idx")));
        }
    }

    @Test
    void aVersionOneCatalogStillLoads() throws IOException {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            catalog.createTable("people", PEOPLE).insert(row(1, "ann", 1.0));
        }
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dir.resolve("catalog.bin")))) {
            out.writeInt(0x46524E43);
            out.writeInt(1);
            out.writeInt(1);
            out.writeUTF("people");
            out.writeInt(PEOPLE.size());
            for (Column column : PEOPLE.columns()) {
                out.writeUTF(column.name());
                out.writeUTF(column.type().name());
                out.writeBoolean(column.nullable());
            }
        }
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            Table people = catalog.table("people");
            assertTrue(people.indexes().isEmpty());
            assertEquals(1, ids(people.scan()).size());
            catalog.createIndex("idx_id", "people", "id");
        }
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            assertEquals(1, catalog.table("people").indexes().size());
        }
    }

    @Test
    void aMissingIndexFileIsReportedAtOpen() throws IOException {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            catalog.createTable("people", PEOPLE);
            catalog.createIndex("idx_id", "people", "id");
        }
        Files.delete(dir.resolve("people.idx_id.idx"));
        CatalogException e = assertThrows(CatalogException.class, () -> Catalog.open(dir, new BufferPool(16)));
        assertTrue(e.getMessage().contains("Index file missing"));
    }

    @Test
    void recreatingADroppedTableStartsEmptyEvenIfAStaleFileWasLeftBehind() throws IOException {
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            catalog.createTable("people", PEOPLE).insert(row(1, "ann", 1.0));
        }
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            catalog.dropTable("people");
        }
        Files.write(dir.resolve("people.tbl"), new byte[4096]);
        try (Catalog catalog = Catalog.open(dir, new BufferPool(16))) {
            Table people = catalog.createTable("people", PEOPLE);
            assertEquals(0, ids(people.scan()).size());
        }
    }
}
