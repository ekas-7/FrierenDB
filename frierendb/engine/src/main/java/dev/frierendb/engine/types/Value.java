package dev.frierendb.engine.types;

import dev.frierendb.engine.FrierenException;
import java.util.Objects;

public sealed interface Value extends Comparable<Value> {

    Value NULL = new NullValue();

    boolean isNull();

    DataType type();

    static Value of(long v) {
        return new IntValue(v);
    }

    static Value of(double v) {
        return new DoubleValue(v);
    }

    static Value of(String v) {
        return new TextValue(Objects.requireNonNull(v, "use Value.NULL for SQL NULL"));
    }

    static Value of(boolean v) {
        return new BoolValue(v);
    }

    /**
     * Total ordering used for sorting and indexing. NULL sorts before every other value.
     * INT and DOUBLE compare numerically; any other cross-type comparison is an error.
     */
    @Override
    default int compareTo(Value other) {
        if (isNull() || other.isNull()) {
            return Boolean.compare(!isNull(), !other.isNull());
        }
        return switch (this) {
            case IntValue a when other instanceof IntValue b -> Long.compare(a.value(), b.value());
            case IntValue a when other instanceof DoubleValue b -> Double.compare(a.value(), b.value());
            case DoubleValue a when other instanceof IntValue b -> Double.compare(a.value(), b.value());
            case DoubleValue a when other instanceof DoubleValue b -> Double.compare(a.value(), b.value());
            case TextValue a when other instanceof TextValue b -> a.value().compareTo(b.value());
            case BoolValue a when other instanceof BoolValue b -> Boolean.compare(a.value(), b.value());
            default -> throw new FrierenException(
                    "Cannot compare " + type() + " with " + other.type());
        };
    }

    record NullValue() implements Value {
        @Override
        public boolean isNull() {
            return true;
        }

        @Override
        public DataType type() {
            throw new FrierenException("NULL has no intrinsic type");
        }

        @Override
        public String toString() {
            return "NULL";
        }
    }

    record IntValue(long value) implements Value {
        @Override
        public boolean isNull() {
            return false;
        }

        @Override
        public DataType type() {
            return DataType.INT;
        }

        @Override
        public String toString() {
            return Long.toString(value);
        }
    }

    record DoubleValue(double value) implements Value {
        @Override
        public boolean isNull() {
            return false;
        }

        @Override
        public DataType type() {
            return DataType.DOUBLE;
        }

        @Override
        public String toString() {
            return Double.toString(value);
        }
    }

    record TextValue(String value) implements Value {
        @Override
        public boolean isNull() {
            return false;
        }

        @Override
        public DataType type() {
            return DataType.TEXT;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    record BoolValue(boolean value) implements Value {
        @Override
        public boolean isNull() {
            return false;
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public String toString() {
            return Boolean.toString(value);
        }
    }
}
