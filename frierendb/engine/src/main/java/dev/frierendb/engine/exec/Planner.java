package dev.frierendb.engine.exec;

import dev.frierendb.engine.catalog.Catalog;
import dev.frierendb.engine.catalog.Table;
import dev.frierendb.engine.sql.Expr;
import dev.frierendb.engine.sql.ExprFormatter;
import dev.frierendb.engine.sql.SqlException;
import dev.frierendb.engine.sql.Statement.Join;
import dev.frierendb.engine.sql.Statement.OrderItem;
import dev.frierendb.engine.sql.Statement.Projection;
import dev.frierendb.engine.sql.Statement.Select;
import dev.frierendb.engine.sql.Statement.SelectItem;
import dev.frierendb.engine.sql.Statement.Star;
import dev.frierendb.engine.sql.Statement.TableRef;
import dev.frierendb.engine.types.Value;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds an operator tree for a SELECT. Pipeline: scan, joins, WHERE, aggregation, HAVING,
 * projection (plus hidden sort columns), ORDER BY, DISTINCT, LIMIT.
 */
final class Planner {

    private record Output(Expr expr, String name) {}

    private final Catalog catalog;

    Planner(Catalog catalog) {
        this.catalog = catalog;
    }

    PlanNode plan(Select select) {
        Set<String> aliases = new HashSet<>();
        PlanNodes.Scan first = scan(select.from(), aliases);
        List<PlanNode> others = new ArrayList<>();
        for (Join join : select.joins()) {
            others.add(scan(join.table(), aliases));
        }
        RowSchema combined = first.schema();
        for (PlanNode other : others) {
            combined = combined.concat(other.schema());
        }
        RowFunction predicate = select.where() == null ? null : new ExpressionCompiler(combined, null).compile(select.where());

        // Only the FROM table is narrowed by an index: restricting it can never drop a row the
        // WHERE clause would keep, even when LEFT JOINs follow, and the full WHERE is rechecked below.
        PlanNode node = first;
        if (predicate != null) {
            node = IndexSelection.choose(first.table(), first.alias(), select.where())
                    .<PlanNode>map(choice -> new PlanNodes.IndexScan(first.table(), first.alias(), choice))
                    .orElse(first);
        }
        for (int i = 0; i < others.size(); i++) {
            node = join(node, others.get(i), select.joins().get(i));
        }
        RowSchema base = node.schema();
        if (predicate != null) {
            node = new PlanNodes.Filter(node, predicate, ExprFormatter.format(select.where()), "WHERE condition");
        }

        boolean aggregated = !select.groupBy().isEmpty() || usesAggregates(select);
        if (select.having() != null && !aggregated) {
            throw new SqlException("HAVING needs GROUP BY or an aggregate function");
        }
        List<Output> outputs = expandSelectList(select.items(), base, aggregated);

        GroupScope scope = aggregated ? new GroupScope(select.groupBy(), base) : null;
        ExpressionCompiler compiler = new ExpressionCompiler(base, scope);
        List<RowFunction> projected = new ArrayList<>();
        for (Output output : outputs) {
            projected.add(compiler.compile(output.expr()));
        }
        RowFunction having = select.having() == null ? null : compiler.compile(select.having());

        List<RowFunction> hidden = new ArrayList<>();
        int[] sortColumns = new int[select.orderBy().size()];
        boolean[] ascending = new boolean[sortColumns.length];
        for (int i = 0; i < sortColumns.length; i++) {
            OrderItem item = select.orderBy().get(i);
            ascending[i] = item.ascending();
            sortColumns[i] = resolveSortKey(item.expr(), outputs, compiler, hidden, select.distinct());
        }

        if (aggregated) {
            List<RowFunction> groupFns = new ArrayList<>();
            ExpressionCompiler plain = new ExpressionCompiler(base, null);
            for (Expr groupExpr : select.groupBy()) {
                groupFns.add(plain.compile(groupExpr));
            }
            node = aggregate(node, select.groupBy(), groupFns, scope);
            if (having != null) {
                node = new PlanNodes.Filter(node, having, ExprFormatter.format(select.having()), "HAVING condition");
            }
        }

        List<RowFunction> allProjected = new ArrayList<>(projected);
        allProjected.addAll(hidden);
        List<RowSchema.Binding> bindings = new ArrayList<>();
        outputs.forEach(o -> bindings.add(new RowSchema.Binding(null, o.name())));
        for (int i = 0; i < hidden.size(); i++) {
            bindings.add(new RowSchema.Binding(null, "?order" + i));
        }
        String projectText = "[" + outputs.stream().map(o -> ExprFormatter.format(o.expr())).collect(Collectors.joining(", ")) + "]";
        node = new PlanNodes.Project(node, allProjected, new RowSchema(bindings), projectText);

        if (sortColumns.length > 0) {
            String sortText = "[" + java.util.stream.IntStream.range(0, sortColumns.length)
                    .mapToObj(i -> ExprFormatter.format(select.orderBy().get(i).expr())
                            + (ascending[i] ? "" : " DESC"))
                    .collect(Collectors.joining(", ")) + "]";
            node = new PlanNodes.Sort(node, sortColumns, ascending, sortText);
        }
        if (!hidden.isEmpty()) {
            node = new PlanNodes.Strip(node, outputs.size());
        }
        if (select.distinct()) {
            node = new PlanNodes.Distinct(node);
        }
        if (select.limit() != null) {
            node = new PlanNodes.Limit(node, select.limit());
        }
        return node;
    }

    private PlanNodes.Scan scan(TableRef ref, Set<String> aliases) {
        Table table = catalog.table(ref.name());
        String alias = ref.effectiveName();
        if (!aliases.add(alias.toLowerCase(Locale.ROOT))) {
            throw new SqlException("Table name '" + alias + "' is used more than once; give each use a distinct alias");
        }
        return new PlanNodes.Scan(table, alias);
    }

    private PlanNode join(PlanNode left, PlanNode right, Join join) {
        RowSchema combined = left.schema().concat(right.schema());
        RowFunction condition = new ExpressionCompiler(combined, null).compile(join.on());
        List<Integer> leftKeys = new ArrayList<>();
        List<Integer> rightKeys = new ArrayList<>();
        for (Expr conjunct : IndexSelection.conjuncts(join.on())) {
            if (conjunct instanceof Expr.Binary b && b.op() == Expr.BinaryOp.EQ
                    && b.left() instanceof Expr.ColumnRef a && b.right() instanceof Expr.ColumnRef c) {
                OptionalInt aLeft = left.schema().find(a.table(), a.column());
                OptionalInt cRight = right.schema().find(c.table(), c.column());
                OptionalInt aRight = right.schema().find(a.table(), a.column());
                OptionalInt cLeft = left.schema().find(c.table(), c.column());
                if (aLeft.isPresent() && cRight.isPresent()) {
                    leftKeys.add(aLeft.getAsInt());
                    rightKeys.add(cRight.getAsInt());
                } else if (cLeft.isPresent() && aRight.isPresent()) {
                    leftKeys.add(cLeft.getAsInt());
                    rightKeys.add(aRight.getAsInt());
                }
            }
        }
        return new PlanNodes.Join(left, right, join.type(), condition,
                leftKeys.stream().mapToInt(Integer::intValue).toArray(),
                rightKeys.stream().mapToInt(Integer::intValue).toArray(),
                ExprFormatter.format(join.on()));
    }

    private PlanNode aggregate(PlanNode child, List<Expr> groupExprs, List<RowFunction> groupFns, GroupScope scope) {
        List<RowSchema.Binding> bindings = new ArrayList<>();
        groupExprs.forEach(e -> bindings.add(new RowSchema.Binding(null, ExprFormatter.format(e))));
        scope.aggregates().forEach(a -> bindings.add(new RowSchema.Binding(null, ExprFormatter.format(a.expression()))));
        String text = "group by [" + groupExprs.stream().map(ExprFormatter::format).collect(Collectors.joining(", "))
                + "] compute [" + scope.aggregates().stream()
                        .map(a -> ExprFormatter.format(a.expression())).collect(Collectors.joining(", ")) + "]";
        return new PlanNodes.Aggregate(child, groupFns, scope.aggregates(), new RowSchema(bindings), text);
    }

    private List<Output> expandSelectList(List<SelectItem> items, RowSchema base, boolean aggregated) {
        List<Output> outputs = new ArrayList<>();
        for (SelectItem item : items) {
            switch (item) {
                case Star star -> {
                    if (aggregated) {
                        throw new SqlException("SELECT * cannot be combined with GROUP BY or aggregate functions");
                    }
                    int before = outputs.size();
                    for (RowSchema.Binding b : base.bindings()) {
                        if (star.table() == null || (b.table() != null && b.table().equalsIgnoreCase(star.table()))) {
                            outputs.add(new Output(new Expr.ColumnRef(b.table(), b.name()), b.name()));
                        }
                    }
                    if (outputs.size() == before) {
                        throw new SqlException("Unknown table in select list: " + star.table());
                    }
                }
                case Projection p -> outputs.add(new Output(p.expr(), p.alias() != null
                        ? p.alias()
                        : p.expr() instanceof Expr.ColumnRef c ? c.column() : ExprFormatter.format(p.expr())));
            }
        }
        return outputs;
    }

    private int resolveSortKey(Expr expr, List<Output> outputs, ExpressionCompiler compiler,
            List<RowFunction> hidden, boolean distinct) {
        if (expr instanceof Expr.Literal l) {
            if (l.value() instanceof Value.IntValue position && position.value() >= 1 && position.value() <= outputs.size()) {
                return (int) position.value() - 1;
            }
            throw new SqlException("ORDER BY position must be an integer between 1 and " + outputs.size());
        }
        if (expr instanceof Expr.ColumnRef c && c.table() == null) {
            for (int i = 0; i < outputs.size(); i++) {
                if (outputs.get(i).name().equalsIgnoreCase(c.column())) {
                    return i;
                }
            }
        }
        for (int i = 0; i < outputs.size(); i++) {
            if (outputs.get(i).expr().equals(expr)) {
                return i;
            }
        }
        if (distinct) {
            throw new SqlException("With SELECT DISTINCT, ORDER BY expressions must appear in the select list: "
                    + ExprFormatter.format(expr));
        }
        hidden.add(compiler.compile(expr));
        return outputs.size() + hidden.size() - 1;
    }

    private static boolean usesAggregates(Select select) {
        for (SelectItem item : select.items()) {
            if (item instanceof Projection p && containsAggregate(p.expr())) {
                return true;
            }
        }
        if (select.having() != null && containsAggregate(select.having())) {
            return true;
        }
        return select.orderBy().stream().anyMatch(o -> containsAggregate(o.expr()));
    }

    static boolean containsAggregate(Expr expr) {
        return switch (expr) {
            case Expr.Aggregate a -> true;
            case Expr.Literal l -> false;
            case Expr.ColumnRef c -> false;
            case Expr.Unary u -> containsAggregate(u.operand());
            case Expr.Binary b -> containsAggregate(b.left()) || containsAggregate(b.right());
            case Expr.IsNull n -> containsAggregate(n.operand());
            case Expr.InList in -> containsAggregate(in.operand()) || in.items().stream().anyMatch(Planner::containsAggregate);
            case Expr.Call c -> c.arguments().stream().anyMatch(Planner::containsAggregate);
        };
    }
}
