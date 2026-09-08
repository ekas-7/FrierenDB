package dev.frierendb.agent.cli;

import dev.frierendb.engine.exec.QueryResult;
import java.util.ArrayList;
import java.util.List;

final class ResultPrinter {

    private static final int MAX_CELL = 60;

    private ResultPrinter() {}

    static String format(QueryResult result) {
        if (result.columns().isEmpty()) {
            return result.affectedRows() + " row(s) affected";
        }
        int cols = result.columns().size();
        List<String[]> cells = new ArrayList<>();
        for (var row : result.rows()) {
            String[] line = new String[cols];
            for (int i = 0; i < cols; i++) {
                line[i] = clip(row.get(i).isNull() ? "NULL" : display(row.get(i).toString()));
            }
            cells.add(line);
        }
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) {
            widths[i] = result.columns().get(i).length();
            for (String[] line : cells) {
                widths[i] = Math.max(widths[i], line[i].length());
            }
        }
        StringBuilder out = new StringBuilder();
        appendLine(out, result.columns().toArray(String[]::new), widths);
        StringBuilder rule = new StringBuilder();
        for (int i = 0; i < cols; i++) {
            rule.append(i == 0 ? "" : "-+-").append("-".repeat(widths[i]));
        }
        out.append(rule).append('\n');
        for (String[] line : cells) {
            appendLine(out, line, widths);
        }
        out.append('(').append(cells.size()).append(cells.size() == 1 ? " row" : " rows");
        out.append(result.truncated() ? ", truncated)" : ")");
        return out.toString();
    }

    private static void appendLine(StringBuilder out, String[] line, int[] widths) {
        for (int i = 0; i < line.length; i++) {
            if (i > 0) {
                out.append(" | ");
            }
            out.append(line[i]).append(" ".repeat(widths[i] - line[i].length()));
        }
        out.append('\n');
    }

    private static String display(String s) {
        return s.replace('\n', ' ');
    }

    private static String clip(String s) {
        return s.length() <= MAX_CELL ? s : s.substring(0, MAX_CELL - 3) + "...";
    }
}
