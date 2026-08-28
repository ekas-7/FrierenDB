package dev.frierendb.engine.sql;

import dev.frierendb.engine.types.Value;
import java.util.stream.Collectors;

public final class ExprFormatter {

    private ExprFormatter() {}

    public static String format(Expr expr) {
        return switch (expr) {
            case Expr.Literal l -> formatLiteral(l.value());
            case Expr.ColumnRef c -> c.toString();
            case Expr.Unary u -> u.op() == Expr.UnaryOp.NOT
                    ? "NOT " + format(u.operand())
                    : "-" + format(u.operand());
            case Expr.Binary b -> "(" + format(b.left()) + " " + symbol(b.op()) + " " + format(b.right()) + ")";
            case Expr.IsNull n -> format(n.operand()) + (n.negated() ? " IS NOT NULL" : " IS NULL");
            case Expr.InList i -> format(i.operand()) + (i.negated() ? " NOT IN (" : " IN (")
                    + i.items().stream().map(ExprFormatter::format).collect(Collectors.joining(", ")) + ")";
            case Expr.Aggregate a -> a.function() + "("
                    + (a.distinct() ? "DISTINCT " : "")
                    + (a.argument() == null ? "*" : format(a.argument())) + ")";
            case Expr.Call c -> c.function() + "("
                    + c.arguments().stream().map(ExprFormatter::format).collect(Collectors.joining(", ")) + ")";
        };
    }

    private static String formatLiteral(Value value) {
        return value instanceof Value.TextValue t ? "'" + t.value().replace("'", "''") + "'" : value.toString();
    }

    private static String symbol(Expr.BinaryOp op) {
        return switch (op) {
            case ADD -> "+";
            case SUBTRACT -> "-";
            case MULTIPLY -> "*";
            case DIVIDE -> "/";
            case MODULO -> "%";
            case EQ -> "=";
            case NE -> "<>";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case AND -> "AND";
            case OR -> "OR";
            case LIKE -> "LIKE";
        };
    }
}
