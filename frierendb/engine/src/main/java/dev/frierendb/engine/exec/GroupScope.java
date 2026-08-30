package dev.frierendb.engine.exec;

import dev.frierendb.engine.sql.Expr;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Describes the rows produced by aggregation: the group-by values followed by one value per
 * distinct aggregate. Expressions compiled in this scope may only refer to those positions.
 */
final class GroupScope {

    record AggregateSpec(Expr.Aggregate expression, RowFunction argument) {}

    private final List<Expr> groupExprs;
    private final int[] groupInputIndex;
    private final List<AggregateSpec> aggregates = new ArrayList<>();

    GroupScope(List<Expr> groupExprs, RowSchema input) {
        this.groupExprs = List.copyOf(groupExprs);
        this.groupInputIndex = new int[groupExprs.size()];
        for (int i = 0; i < groupInputIndex.length; i++) {
            groupInputIndex[i] = groupExprs.get(i) instanceof Expr.ColumnRef c
                    ? input.resolve(c.table(), c.column())
                    : -1;
        }
    }

    int groupCount() {
        return groupExprs.size();
    }

    List<AggregateSpec> aggregates() {
        return aggregates;
    }

    OptionalInt positionOfGroupExpression(Expr expr, RowSchema input) {
        for (int i = 0; i < groupExprs.size(); i++) {
            if (groupExprs.get(i).equals(expr)) {
                return OptionalInt.of(i);
            }
        }
        if (expr instanceof Expr.ColumnRef c) {
            int resolved = input.resolve(c.table(), c.column());
            for (int i = 0; i < groupInputIndex.length; i++) {
                if (groupInputIndex[i] == resolved) {
                    return OptionalInt.of(i);
                }
            }
        }
        return OptionalInt.empty();
    }

    /** Registers the aggregate if new and returns its position in the group row. */
    int positionOfAggregate(Expr.Aggregate aggregate, RowFunction argument) {
        for (int i = 0; i < aggregates.size(); i++) {
            if (aggregates.get(i).expression().equals(aggregate)) {
                return groupExprs.size() + i;
            }
        }
        aggregates.add(new AggregateSpec(aggregate, argument));
        return groupExprs.size() + aggregates.size() - 1;
    }
}
