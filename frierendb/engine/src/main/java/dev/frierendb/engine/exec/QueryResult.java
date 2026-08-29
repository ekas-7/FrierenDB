package dev.frierendb.engine.exec;

import dev.frierendb.engine.schema.Row;
import java.util.List;

/**
 * Outcome of one statement. {@code truncated} is true when a SELECT had more rows than the
 * caller allowed; {@code affectedRows} is non-zero only for INSERT, UPDATE and DELETE.
 */
public record QueryResult(List<String> columns, List<Row> rows, long affectedRows, boolean truncated) {

    public QueryResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    public static QueryResult rows(List<String> columns, List<Row> rows, boolean truncated) {
        return new QueryResult(columns, rows, 0, truncated);
    }

    public static QueryResult affected(long count) {
        return new QueryResult(List.of(), List.of(), count, false);
    }
}
