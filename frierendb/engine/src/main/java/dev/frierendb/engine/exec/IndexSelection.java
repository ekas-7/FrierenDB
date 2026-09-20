package dev.frierendb.engine.exec;

import dev.frierendb.engine.catalog.IndexInfo;
import dev.frierendb.engine.catalog.Table;
import dev.frierendb.engine.schema.Column;
import dev.frierendb.engine.schema.Row;
import dev.frierendb.engine.sql.Expr;
import dev.frierendb.engine.types.DataType;
import dev.frierendb.engine.types.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Rule-based choice of an index for a WHERE clause. There are no statistics: if a usable index
 * exists it is used, preferring an equality match, then the index with the most bounds.
 *
 * <p>A conjunct is usable when it compares an indexed column of the table with a constant using
 * {@code =, <, <=, >, >=}. The chosen range only narrows which rows are read; the full WHERE
 * clause is always evaluated again on those rows, so a choice can never change a query's result.
 */
final class IndexSelection {

    private static final long EXACT_DOUBLE_LIMIT = 1L << 53;

    /** An index plus the key range to read from it. A null bound is unbounded. */
    record Choice(IndexInfo index, Value lo, boolean loInclusive, Value hi, boolean hiInclusive, String condition) {}

    private static final class Range {
        final IndexInfo index;
        final String column;
        final DataType type;
        Value lo;
        boolean loInclusive;
        Value hi;
        boolean hiInclusive;
        Value equal;

        Range(IndexInfo index, String column, DataType type) {
            this.index = index;
            this.column = column;
            this.type = type;
        }

        void bound(Expr.BinaryOp op, Value v) {
            switch (op) {
                case EQ -> {
                    if (equal == null) {
                        equal = v;
                    }
                }
                case GT, GE -> {
                    boolean inclusive = op == Expr.BinaryOp.GE;
                    int c = lo == null ? 1 : v.compareTo(lo);
                    if (c > 0 || c == 0 && !inclusive && loInclusive) {
                        lo = v;
                        loInclusive = inclusive;
                    }
                }
                case LT, LE -> {
                    boolean inclusive = op == Expr.BinaryOp.LE;
                    int c = hi == null ? -1 : v.compareTo(hi);
                    if (c < 0 || c == 0 && !inclusive && hiInclusive) {
                        hi = v;
                        hiInclusive = inclusive;
                    }
                }
                default -> throw new IllegalArgumentException("not a range operator: " + op);
            }
        }

        int score() {
            return equal != null ? 3 : (lo != null ? 1 : 0) + (hi != null ? 1 : 0);
        }

        Choice toChoice() {
            if (equal != null) {
                return new Choice(index, equal, true, equal, true, column + " = " + literal(equal));
            }
            List<String> parts = new ArrayList<>();
            if (lo != null) {
                parts.add(column + (loInclusive ? " >= " : " > ") + literal(lo));
            }
            if (hi != null) {
                parts.add(column + (hiInclusive ? " <= " : " < ") + literal(hi));
            }
            return new Choice(index, lo, loInclusive, hi, hiInclusive, String.join(" AND ", parts));
        }
    }

    private IndexSelection() {}

    /**
     * Chooses an index for {@code where} on {@code table}, which the query refers to as {@code alias}.
     * The caller must already have compiled {@code where}, so names are known to resolve.
     */
    static Optional<Choice> choose(Table table, String alias, Expr where) {
        if (where == null || table.indexes().isEmpty()) {
            return Optional.empty();
        }
        Map<String, Range> ranges = new LinkedHashMap<>();
        for (Expr conjunct : conjuncts(where)) {
            collect(table, alias, conjunct, ranges);
        }
        Range best = null;
        for (Range range : ranges.values()) {
            if (range.score() > 0 && (best == null || range.score() > best.score())) {
                best = range;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.toChoice());
    }

    private static void collect(Table table, String alias, Expr conjunct, Map<String, Range> ranges) {
        if (!(conjunct instanceof Expr.Binary b)) {
            return;
        }
        Expr.BinaryOp op = b.op();
        if (op != Expr.BinaryOp.EQ && op != Expr.BinaryOp.LT && op != Expr.BinaryOp.LE
                && op != Expr.BinaryOp.GT && op != Expr.BinaryOp.GE) {
            return;
        }
        Expr.ColumnRef ref;
        Expr constant;
        Expr.BinaryOp effective;
        if (b.left() instanceof Expr.ColumnRef left && isConstant(b.right())) {
            ref = left;
            constant = b.right();
            effective = op;
        } else if (b.right() instanceof Expr.ColumnRef right && isConstant(b.left())) {
            ref = right;
            constant = b.left();
            effective = flip(op);
        } else {
            return;
        }
        if (ref.table() != null && !ref.table().equalsIgnoreCase(alias)) {
            return;
        }
        OptionalInt ordinal = table.schema().indexOf(ref.column());
        if (ordinal.isEmpty()) {
            return;
        }
        Column column = table.schema().column(ordinal.getAsInt());
        Optional<IndexInfo> index = table.indexes().stream()
                .filter(i -> i.column().equalsIgnoreCase(column.name()))
                .findFirst();
        if (index.isEmpty()) {
            return;
        }
        Value value = evaluate(constant);
        Value bound = value == null ? null : coerce(column.type(), value);
        if (bound == null) {
            return;
        }
        ranges.computeIfAbsent(column.name().toLowerCase(Locale.ROOT),
                k -> new Range(index.get(), column.name(), column.type())).bound(effective, bound);
    }

    /** Evaluates a constant expression, or returns null if it fails, so the scan reports the error. */
    private static Value evaluate(Expr constant) {
        try {
            return new ExpressionCompiler(new RowSchema(List.of()), null).compile(constant).apply(new Row(List.of()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Converts a constant to the column's type when that is exact, or returns null when it is not
     * (NULL, a different type, or an integer too large to be a double without rounding).
     */
    private static Value coerce(DataType type, Value value) {
        if (value.isNull()) {
            return null;
        }
        return switch (type) {
            case INT -> value instanceof Value.IntValue ? value : null;
            case DOUBLE -> {
                if (value instanceof Value.DoubleValue) {
                    yield value;
                }
                if (value instanceof Value.IntValue i && Math.abs(i.value()) <= EXACT_DOUBLE_LIMIT) {
                    yield Value.of((double) i.value());
                }
                yield null;
            }
            case TEXT -> value instanceof Value.TextValue ? value : null;
            case BOOLEAN -> value instanceof Value.BoolValue ? value : null;
        };
    }

    private static boolean isConstant(Expr expr) {
        return switch (expr) {
            case Expr.Literal l -> true;
            case Expr.ColumnRef c -> false;
            case Expr.Aggregate a -> false;
            case Expr.Unary u -> isConstant(u.operand());
            case Expr.Binary b -> isConstant(b.left()) && isConstant(b.right());
            case Expr.IsNull n -> isConstant(n.operand());
            case Expr.InList in -> isConstant(in.operand()) && in.items().stream().allMatch(IndexSelection::isConstant);
            case Expr.Call c -> c.arguments().stream().allMatch(IndexSelection::isConstant);
        };
    }

    private static Expr.BinaryOp flip(Expr.BinaryOp op) {
        return switch (op) {
            case LT -> Expr.BinaryOp.GT;
            case LE -> Expr.BinaryOp.GE;
            case GT -> Expr.BinaryOp.LT;
            case GE -> Expr.BinaryOp.LE;
            default -> op;
        };
    }

    static List<Expr> conjuncts(Expr expr) {
        if (expr instanceof Expr.Binary b && b.op() == Expr.BinaryOp.AND) {
            List<Expr> all = new ArrayList<>(conjuncts(b.left()));
            all.addAll(conjuncts(b.right()));
            return all;
        }
        return List.of(expr);
    }

    private static String literal(Value v) {
        return v instanceof Value.TextValue t ? "'" + t.value().replace("'", "''") + "'" : v.toString();
    }
}
