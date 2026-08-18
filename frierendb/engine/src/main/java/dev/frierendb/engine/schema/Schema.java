package dev.frierendb.engine.schema;

import dev.frierendb.engine.types.DataType;
import dev.frierendb.engine.types.Value;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

public final class Schema {

    private final List<Column> columns;
    private final Map<String, Integer> indexByName;

    public Schema(List<Column> columns) {
        if (columns.isEmpty()) {
            throw new SchemaException("A schema needs at least one column");
        }
        this.columns = List.copyOf(columns);
        this.indexByName = new HashMap<>();
        for (int i = 0; i < this.columns.size(); i++) {
            String key = normalize(this.columns.get(i).name());
            if (indexByName.put(key, i) != null) {
                throw new SchemaException("Duplicate column name: " + this.columns.get(i).name());
            }
        }
    }

    public List<Column> columns() {
        return columns;
    }

    public int size() {
        return columns.size();
    }

    public Column column(int index) {
        return columns.get(index);
    }

    public OptionalInt indexOf(String name) {
        Integer i = indexByName.get(normalize(name));
        return i == null ? OptionalInt.empty() : OptionalInt.of(i);
    }

    public int requireIndexOf(String name) {
        return indexOf(name).orElseThrow(() -> new SchemaException("Unknown column: " + name));
    }

    /** Verifies arity, nullability and types, widening INT to DOUBLE where the column asks for it. */
    public Row conform(List<Value> values) {
        if (values.size() != columns.size()) {
            throw new SchemaException(
                    "Expected " + columns.size() + " values but got " + values.size());
        }
        Value[] conformed = new Value[values.size()];
        for (int i = 0; i < conformed.length; i++) {
            conformed[i] = conformValue(columns.get(i), values.get(i));
        }
        return new Row(List.of(conformed));
    }

    private static Value conformValue(Column column, Value value) {
        if (value.isNull()) {
            if (!column.nullable()) {
                throw new SchemaException("Column " + column.name() + " does not accept NULL");
            }
            return value;
        }
        if (value.type() == column.type()) {
            return value;
        }
        if (column.type() == DataType.DOUBLE && value instanceof Value.IntValue intValue) {
            return Value.of((double) intValue.value());
        }
        throw new SchemaException(
                "Column " + column.name() + " expects " + column.type() + " but got " + value.type());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Schema other && columns.equals(other.columns);
    }

    @Override
    public int hashCode() {
        return columns.hashCode();
    }

    @Override
    public String toString() {
        return columns.toString();
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
