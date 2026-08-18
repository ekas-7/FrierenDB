package dev.frierendb.engine.schema;

import dev.frierendb.engine.types.DataType;
import java.util.Objects;

public record Column(String name, DataType type, boolean nullable) {

    public Column {
        Objects.requireNonNull(type, "type");
        if (name == null || name.isBlank()) {
            throw new SchemaException("Column name must not be blank");
        }
    }

    public static Column required(String name, DataType type) {
        return new Column(name, type, false);
    }

    public static Column optional(String name, DataType type) {
        return new Column(name, type, true);
    }
}
