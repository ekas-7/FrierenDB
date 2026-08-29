package dev.frierendb.engine.exec;

import dev.frierendb.engine.sql.SqlException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/** Column names of an intermediate row set, each tagged with the table alias it came from. */
record RowSchema(List<Binding> bindings) {

    record Binding(String table, String name) {}

    RowSchema {
        bindings = List.copyOf(bindings);
    }

    int size() {
        return bindings.size();
    }

    List<String> names() {
        return bindings.stream().map(Binding::name).toList();
    }

    RowSchema concat(RowSchema other) {
        List<Binding> all = new ArrayList<>(bindings);
        all.addAll(other.bindings);
        return new RowSchema(all);
    }

    /** Empty if no column matches; throws if the reference is ambiguous. */
    OptionalInt find(String table, String column) {
        int found = -1;
        for (int i = 0; i < bindings.size(); i++) {
            Binding b = bindings.get(i);
            boolean tableMatches = table == null || (b.table() != null && b.table().equalsIgnoreCase(table));
            if (tableMatches && b.name().equalsIgnoreCase(column)) {
                if (found >= 0) {
                    throw new SqlException("Ambiguous column reference: " + qualified(table, column));
                }
                found = i;
            }
        }
        return found < 0 ? OptionalInt.empty() : OptionalInt.of(found);
    }

    int resolve(String table, String column) {
        return find(table, column).orElseThrow(
                () -> new SqlException("Unknown column: " + qualified(table, column)));
    }

    private static String qualified(String table, String column) {
        return table == null ? column : table + "." + column;
    }
}
