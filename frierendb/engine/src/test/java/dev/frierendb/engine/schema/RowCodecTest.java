package dev.frierendb.engine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.frierendb.engine.types.DataType;
import dev.frierendb.engine.types.Value;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RowCodecTest {

    private static final Schema STUDENTS = new Schema(List.of(
            Column.required("sid", DataType.INT),
            Column.required("name", DataType.TEXT),
            Column.optional("gpa", DataType.DOUBLE),
            Column.optional("active", DataType.BOOLEAN)));

    @Test
    void roundTripsAllTypes() {
        Row row = Row.of(Value.of(7), Value.of("Ada"), Value.of(3.9), Value.of(true));
        assertEquals(row, RowCodec.decode(STUDENTS, RowCodec.encode(STUDENTS, row)));
    }

    @Test
    void roundTripsNulls() {
        Row row = Row.of(Value.of(1), Value.of("Bob"), Value.NULL, Value.NULL);
        assertEquals(row, RowCodec.decode(STUDENTS, RowCodec.encode(STUDENTS, row)));
    }

    @Test
    void roundTripsUnicodeAndEmptyText() {
        Row unicode = Row.of(Value.of(2), Value.of("Zoë 日本語 🎓"), Value.NULL, Value.NULL);
        Row empty = Row.of(Value.of(3), Value.of(""), Value.NULL, Value.NULL);
        assertEquals(unicode, RowCodec.decode(STUDENTS, RowCodec.encode(STUDENTS, unicode)));
        assertEquals(empty, RowCodec.decode(STUDENTS, RowCodec.encode(STUDENTS, empty)));
    }

    @Test
    void roundTripsWideSchemaWithManyNullBits() {
        List<Column> columns = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            columns.add(Column.optional("c" + i, DataType.INT));
        }
        Schema wide = new Schema(columns);
        Value[] values = new Value[20];
        for (int i = 0; i < 20; i++) {
            values[i] = i % 3 == 0 ? Value.NULL : Value.of(i);
        }
        Row row = Row.of(values);
        assertEquals(row, RowCodec.decode(wide, RowCodec.encode(wide, row)));
    }

    @Test
    void widensIntToDoubleColumn() {
        Row row = Row.of(Value.of(1), Value.of("Cy"), Value.of(4), Value.NULL);
        Row decoded = RowCodec.decode(STUDENTS, RowCodec.encode(STUDENTS, row));
        assertEquals(Value.of(4.0), decoded.get(2));
    }

    @Test
    void rejectsNullInRequiredColumn() {
        Row row = Row.of(Value.NULL, Value.of("x"), Value.NULL, Value.NULL);
        assertThrows(SchemaException.class, () -> RowCodec.encode(STUDENTS, row));
    }

    @Test
    void rejectsWrongType() {
        Row row = Row.of(Value.of("not an int"), Value.of("x"), Value.NULL, Value.NULL);
        assertThrows(SchemaException.class, () -> RowCodec.encode(STUDENTS, row));
    }

    @Test
    void rejectsWrongArity() {
        assertThrows(SchemaException.class,
                () -> RowCodec.encode(STUDENTS, Row.of(Value.of(1))));
    }

    @Test
    void rejectsTruncatedBytes() {
        byte[] bytes = RowCodec.encode(STUDENTS,
                Row.of(Value.of(1), Value.of("truncate me"), Value.NULL, Value.NULL));
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 3);
        assertThrows(SchemaException.class, () -> RowCodec.decode(STUDENTS, truncated));
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] bytes = RowCodec.encode(STUDENTS,
                Row.of(Value.of(1), Value.of("a"), Value.NULL, Value.NULL));
        byte[] padded = Arrays.copyOf(bytes, bytes.length + 2);
        assertThrows(SchemaException.class, () -> RowCodec.decode(STUDENTS, padded));
    }

    @Test
    void rejectsNegativeTextLength() {
        Schema textOnly = new Schema(List.of(Column.required("t", DataType.TEXT)));
        byte[] bytes = new byte[] {0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertThrows(SchemaException.class, () -> RowCodec.decode(textOnly, bytes));
    }
}
