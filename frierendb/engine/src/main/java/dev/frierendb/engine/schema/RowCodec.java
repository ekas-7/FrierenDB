package dev.frierendb.engine.schema;

import dev.frierendb.engine.types.Value;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary row format: a null bitmap of ceil(n/8) bytes followed by each non-null value.
 * INT and DOUBLE use 8 bytes, BOOLEAN uses 1 byte, TEXT is a 4-byte length plus UTF-8 bytes.
 */
public final class RowCodec {

    private RowCodec() {}

    public static byte[] encode(Schema schema, Row row) {
        Row checked = schema.conform(row.values());
        byte[] bitmap = new byte[bitmapSize(schema.size())];
        int payload = 0;
        List<byte[]> texts = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            Value v = checked.get(i);
            if (v.isNull()) {
                bitmap[i / 8] |= (byte) (1 << (i % 8));
                continue;
            }
            switch (v) {
                case Value.IntValue ignored -> payload += Long.BYTES;
                case Value.DoubleValue ignored -> payload += Double.BYTES;
                case Value.BoolValue ignored -> payload += 1;
                case Value.TextValue t -> {
                    byte[] utf8 = t.value().getBytes(StandardCharsets.UTF_8);
                    texts.add(utf8);
                    payload += Integer.BYTES + utf8.length;
                }
                case Value.NullValue ignored -> throw new IllegalStateException("handled above");
            }
        }
        ByteBuffer buf = ByteBuffer.allocate(bitmap.length + payload);
        buf.put(bitmap);
        int textIndex = 0;
        for (Value v : checked.values()) {
            switch (v) {
                case Value.IntValue n -> buf.putLong(n.value());
                case Value.DoubleValue d -> buf.putDouble(d.value());
                case Value.BoolValue b -> buf.put((byte) (b.value() ? 1 : 0));
                case Value.TextValue ignored -> {
                    byte[] utf8 = texts.get(textIndex++);
                    buf.putInt(utf8.length);
                    buf.put(utf8);
                }
                case Value.NullValue ignored -> { }
            }
        }
        return buf.array();
    }

    public static Row decode(Schema schema, byte[] bytes) {
        try {
            return decodeUnchecked(schema, bytes);
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            throw new SchemaException("Corrupt row data", e);
        }
    }

    private static Row decodeUnchecked(Schema schema, byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        byte[] bitmap = new byte[bitmapSize(schema.size())];
        buf.get(bitmap);
        List<Value> values = new ArrayList<>(schema.size());
        for (int i = 0; i < schema.size(); i++) {
            boolean isNull = (bitmap[i / 8] & (1 << (i % 8))) != 0;
            if (isNull) {
                values.add(Value.NULL);
                continue;
            }
            values.add(switch (schema.column(i).type()) {
                case INT -> Value.of(buf.getLong());
                case DOUBLE -> Value.of(buf.getDouble());
                case BOOLEAN -> Value.of(buf.get() != 0);
                case TEXT -> Value.of(readText(buf));
            });
        }
        if (buf.hasRemaining()) {
            throw new SchemaException("Corrupt row data: " + buf.remaining() + " trailing bytes");
        }
        return new Row(values);
    }

    private static String readText(ByteBuffer buf) {
        int length = buf.getInt();
        if (length < 0 || length > buf.remaining()) {
            throw new IllegalArgumentException("Invalid text length " + length);
        }
        byte[] utf8 = new byte[length];
        buf.get(utf8);
        return new String(utf8, StandardCharsets.UTF_8);
    }

    private static int bitmapSize(int columns) {
        return (columns + 7) / 8;
    }
}
