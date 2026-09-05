package dev.frierendb.agent.tools;

import dev.frierendb.engine.exec.QueryResult;
import java.util.stream.Collectors;

/** Renders query results as compact text for the model, bounding the size of every cell. */
public final class ResultFormatter {

    static final int MAX_CELL_CHARS = 200;

    private ResultFormatter() {}

    public static String format(QueryResult result) {
        StringBuilder out = new StringBuilder();
        out.append(String.join(" | ", result.columns())).append('\n');
        for (var row : result.rows()) {
            out.append(row.values().stream()
                    .map(v -> cell(v.toString()))
                    .collect(Collectors.joining(" | "))).append('\n');
        }
        out.append('(').append(result.rows().size()).append(result.rows().size() == 1 ? " row" : " rows");
        if (result.truncated()) {
            out.append("; more rows exist but were cut off by the row limit");
        }
        out.append(')');
        return out.toString();
    }

    private static String cell(String text) {
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= MAX_CELL_CHARS ? flat : flat.substring(0, MAX_CELL_CHARS) + "...";
    }
}
