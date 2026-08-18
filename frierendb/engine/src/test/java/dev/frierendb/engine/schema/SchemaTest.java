package dev.frierendb.engine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.frierendb.engine.types.DataType;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaTest {

    @Test
    void looksUpColumnsCaseInsensitively() {
        Schema schema = new Schema(List.of(
                Column.required("Sid", DataType.INT), Column.required("Name", DataType.TEXT)));
        assertEquals(1, schema.requireIndexOf("NAME"));
        assertEquals(0, schema.requireIndexOf("sid"));
        assertEquals(true, schema.indexOf("missing").isEmpty());
    }

    @Test
    void rejectsDuplicateColumnNamesIgnoringCase() {
        assertThrows(SchemaException.class, () -> new Schema(List.of(
                Column.required("a", DataType.INT), Column.required("A", DataType.TEXT))));
    }

    @Test
    void rejectsEmptySchema() {
        assertThrows(SchemaException.class, () -> new Schema(List.of()));
    }

    @Test
    void rejectsBlankColumnName() {
        assertThrows(SchemaException.class, () -> Column.required(" ", DataType.INT));
    }

    @Test
    void unknownColumnLookupFailsWithName() {
        Schema schema = new Schema(List.of(Column.required("a", DataType.INT)));
        SchemaException e = assertThrows(SchemaException.class, () -> schema.requireIndexOf("zzz"));
        assertEquals("Unknown column: zzz", e.getMessage());
    }
}
