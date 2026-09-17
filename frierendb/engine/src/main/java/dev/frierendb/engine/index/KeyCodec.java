package dev.frierendb.engine.index;

import dev.frierendb.engine.storage.StorageException;
import dev.frierendb.engine.types.DataType;
import dev.frierendb.engine.types.Value;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Binary form of an index key. INT and DOUBLE take 8 bytes, BOOLEAN 1 byte, and TEXT a 2-byte length
 * followed by UTF-8. TEXT keys are limited to {@value #MAX_TEXT_BYTES} bytes so that many entries
 * always fit on one page.
 */
final class KeyCodec {

    static final int MAX_TEXT_BYTES = 255;

    private KeyCodec() {}

    /** Throws if the key cannot be stored in an index of the given type. */
    static void check(DataType type, Value key) {
        if (key.isNull()) {
            throw new StorageException("NULL cannot be stored in an index");
        }
        boolean matches = switch (type) {
            case INT -> key instanceof Value.IntValue;
            case DOUBLE -> key instanceof Value.DoubleValue;
            case BOOLEAN -> key instanceof Value.BoolValue;
            case TEXT -> key instanceof Value.TextValue;
        };
        if (!matches) {
            throw new StorageException("Index key " + key + " is not of type " + type);
        }
        if (key instanceof Value.TextValue text) {
            int length = text.value().getBytes(StandardCharsets.UTF_8).length;
            if (length > MAX_TEXT_BYTES) {
                throw new StorageException("Index key is " + length + " bytes; TEXT index keys are limited to "
                        + MAX_TEXT_BYTES + " bytes");
            }
        }
    }

    static int size(DataType type, Value key) {
        return switch (type) {
            case INT, DOUBLE -> 8;
            case BOOLEAN -> 1;
            case TEXT -> 2 + ((Value.TextValue) key).value().getBytes(StandardCharsets.UTF_8).length;
        };
    }

    static void write(ByteBuffer out, DataType type, Value key) {
        switch (type) {
            case INT -> out.putLong(((Value.IntValue) key).value());
            case DOUBLE -> out.putDouble(((Value.DoubleValue) key).value());
            case BOOLEAN -> out.put((byte) (((Value.BoolValue) key).value() ? 1 : 0));
            case TEXT -> {
                byte[] bytes = ((Value.TextValue) key).value().getBytes(StandardCharsets.UTF_8);
                out.putShort((short) bytes.length);
                out.put(bytes);
            }
        }
    }

    static Value read(ByteBuffer in, DataType type) {
        return switch (type) {
            case INT -> Value.of(in.getLong());
            case DOUBLE -> Value.of(in.getDouble());
            case BOOLEAN -> Value.of(in.get() != 0);
            case TEXT -> {
                int length = in.getShort() & 0xFFFF;
                byte[] bytes = new byte[length];
                in.get(bytes);
                yield Value.of(new String(bytes, StandardCharsets.UTF_8));
            }
        };
    }
}
