package dev.frierendb.engine.types;

import java.util.Locale;
import java.util.Optional;

public enum DataType {
    INT,
    DOUBLE,
    TEXT,
    BOOLEAN;

    public static Optional<DataType> fromSqlName(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "INT", "INTEGER", "BIGINT" -> Optional.of(INT);
            case "DOUBLE", "FLOAT", "REAL" -> Optional.of(DOUBLE);
            case "TEXT", "VARCHAR", "STRING" -> Optional.of(TEXT);
            case "BOOLEAN", "BOOL" -> Optional.of(BOOLEAN);
            default -> Optional.empty();
        };
    }

    public boolean isNumeric() {
        return this == INT || this == DOUBLE;
    }
}
