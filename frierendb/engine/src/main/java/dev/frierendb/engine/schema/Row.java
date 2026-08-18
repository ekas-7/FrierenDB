package dev.frierendb.engine.schema;

import dev.frierendb.engine.types.Value;
import java.util.List;

public record Row(List<Value> values) {

    public Row {
        values = List.copyOf(values);
    }

    public static Row of(Value... values) {
        return new Row(List.of(values));
    }

    public Value get(int index) {
        return values.get(index);
    }

    public int size() {
        return values.size();
    }
}
