package dev.frierendb.engine.exec;

import dev.frierendb.engine.schema.Row;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

interface PlanNode {

    RowSchema schema();

    Iterator<Row> execute(ExecutionContext context);

    /** Appends a one-line description of this node, then its children, each indented by depth. */
    void describe(int depth, List<String> out);

    default List<String> explain() {
        List<String> lines = new ArrayList<>();
        describe(0, lines);
        return lines;
    }

    static String indent(int depth) {
        return "  ".repeat(depth);
    }
}
