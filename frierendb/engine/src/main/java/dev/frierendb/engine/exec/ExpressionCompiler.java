package dev.frierendb.engine.exec;

import dev.frierendb.engine.schema.Row;
import dev.frierendb.engine.sql.Expr;
import dev.frierendb.engine.sql.Expr.BinaryOp;
import dev.frierendb.engine.sql.ExprFormatter;
import dev.frierendb.engine.sql.SqlException;
import dev.frierendb.engine.types.Value;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.OptionalInt;

/**
 * Turns expression trees into functions over rows, resolving column names once up front.
 * With a {@link GroupScope}, compiled functions read from aggregated rows instead of input rows.
 */
final class ExpressionCompiler {

    private final RowSchema input;
    private final GroupScope group;

    ExpressionCompiler(RowSchema input, GroupScope group) {
        this.input = input;
        this.group = group;
    }

    RowFunction compile(Expr expr) {
        if (group != null) {
            OptionalInt groupPosition = group.positionOfGroupExpression(expr, input);
            if (groupPosition.isPresent()) {
                int position = groupPosition.getAsInt();
                return row -> row.get(position);
            }
            if (expr instanceof Expr.Aggregate aggregate) {
                RowFunction argument = aggregate.argument() == null
                        ? null
                        : new ExpressionCompiler(input, null).compile(aggregate.argument());
                int position = group.positionOfAggregate(aggregate, argument);
                return row -> row.get(position);
            }
            if (expr instanceof Expr.ColumnRef c) {
                throw new SqlException("Column " + c + " must appear in the GROUP BY clause or be used in an aggregate function");
            }
        } else if (expr instanceof Expr.Aggregate aggregate) {
            throw new SqlException("Aggregate function " + ExprFormatter.format(aggregate)
                    + " is not allowed here; use it in the SELECT list or HAVING");
        }
        return switch (expr) {
            case Expr.Literal l -> {
                Value value = l.value();
                yield row -> value;
            }
            case Expr.ColumnRef c -> {
                int index = input.resolve(c.table(), c.column());
                yield row -> row.get(index);
            }
            case Expr.Unary u -> unary(u);
            case Expr.Binary b -> binary(b);
            case Expr.IsNull n -> {
                RowFunction operand = compile(n.operand());
                boolean negated = n.negated();
                yield row -> Value.of(operand.apply(row).isNull() != negated);
            }
            case Expr.InList in -> inList(in);
            case Expr.Aggregate a -> throw new IllegalStateException("handled above: " + a);
            case Expr.Call call -> call(call);
        };
    }

    private RowFunction unary(Expr.Unary u) {
        RowFunction operand = compile(u.operand());
        if (u.op() == Expr.UnaryOp.NEGATE) {
            return row -> SqlValues.negate(operand.apply(row));
        }
        return row -> {
            Value v = SqlValues.requireBooleanOrNull(operand.apply(row), "NOT operand");
            return v.isNull() ? v : Value.of(!SqlValues.isTrue(v));
        };
    }

    private RowFunction binary(Expr.Binary b) {
        RowFunction left = compile(b.left());
        RowFunction right = compile(b.right());
        BinaryOp op = b.op();
        return switch (op) {
            case AND -> row -> {
                Value l = SqlValues.requireBooleanOrNull(left.apply(row), "AND operand");
                if (l instanceof Value.BoolValue lb && !lb.value()) {
                    return l;
                }
                Value r = SqlValues.requireBooleanOrNull(right.apply(row), "AND operand");
                if (r instanceof Value.BoolValue rb && !rb.value()) {
                    return r;
                }
                return l.isNull() || r.isNull() ? Value.NULL : Value.of(true);
            };
            case OR -> row -> {
                Value l = SqlValues.requireBooleanOrNull(left.apply(row), "OR operand");
                if (SqlValues.isTrue(l)) {
                    return l;
                }
                Value r = SqlValues.requireBooleanOrNull(right.apply(row), "OR operand");
                if (SqlValues.isTrue(r)) {
                    return r;
                }
                return l.isNull() || r.isNull() ? Value.NULL : Value.of(false);
            };
            case EQ, NE, LT, LE, GT, GE -> row -> SqlValues.compare(op, left.apply(row), right.apply(row));
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO ->
                    row -> SqlValues.arithmetic(op, left.apply(row), right.apply(row));
            case LIKE -> row -> {
                Value text = left.apply(row);
                Value pattern = right.apply(row);
                if (text.isNull() || pattern.isNull()) {
                    return Value.NULL;
                }
                if (text instanceof Value.TextValue t && pattern instanceof Value.TextValue p) {
                    return Value.of(SqlValues.like(t.value(), p.value()));
                }
                throw new SqlException("LIKE needs text operands");
            };
        };
    }

    private RowFunction inList(Expr.InList in) {
        RowFunction operand = compile(in.operand());
        List<RowFunction> items = in.items().stream().map(this::compile).toList();
        boolean negated = in.negated();
        return row -> {
            Value v = operand.apply(row);
            if (v.isNull()) {
                return Value.NULL;
            }
            boolean sawNull = false;
            for (RowFunction item : items) {
                Value candidate = item.apply(row);
                Value eq = SqlValues.compare(BinaryOp.EQ, v, candidate);
                if (eq.isNull()) {
                    sawNull = true;
                } else if (SqlValues.isTrue(eq)) {
                    return Value.of(!negated);
                }
            }
            return sawNull ? Value.NULL : Value.of(negated);
        };
    }

    private RowFunction call(Expr.Call call) {
        List<RowFunction> args = call.arguments().stream().map(this::compile).toList();
        return switch (call.function()) {
            case LOWER -> text(args.get(0), String::toLowerCase);
            case UPPER -> text(args.get(0), String::toUpperCase);
            case LENGTH -> row -> {
                Value v = args.get(0).apply(row);
                if (v.isNull()) {
                    return v;
                }
                String text = requireText(v, "LENGTH");
                return Value.of((long) text.codePointCount(0, text.length()));
            };
            case ABS -> row -> abs(args.get(0).apply(row));
            case ROUND -> row -> round(args.get(0).apply(row), args.size() > 1 ? args.get(1).apply(row) : null);
            case COALESCE -> row -> {
                for (RowFunction arg : args) {
                    Value v = arg.apply(row);
                    if (!v.isNull()) {
                        return v;
                    }
                }
                return Value.NULL;
            };
        };
    }

    private static RowFunction text(RowFunction arg, java.util.function.UnaryOperator<String> f) {
        return row -> {
            Value v = arg.apply(row);
            return v.isNull() ? v : Value.of(f.apply(requireText(v, "LOWER/UPPER")));
        };
    }

    private static String requireText(Value v, String function) {
        if (v instanceof Value.TextValue t) {
            return t.value();
        }
        throw new SqlException(function + " needs a text argument but got " + v.type());
    }

    private static Value abs(Value v) {
        return switch (v) {
            case Value.NullValue n -> n;
            case Value.IntValue i -> {
                if (i.value() == Long.MIN_VALUE) {
                    throw new SqlException("Integer overflow in ABS");
                }
                yield Value.of(Math.abs(i.value()));
            }
            case Value.DoubleValue d -> Value.of(Math.abs(d.value()));
            default -> throw new SqlException("ABS needs a number but got " + v.type());
        };
    }

    private static Value round(Value v, Value digitsValue) {
        if (v.isNull() || (digitsValue != null && digitsValue.isNull())) {
            return Value.NULL;
        }
        int digits = 0;
        if (digitsValue != null) {
            if (!(digitsValue instanceof Value.IntValue d) || d.value() < -15 || d.value() > 15) {
                throw new SqlException("ROUND digits must be an integer between -15 and 15");
            }
            digits = (int) d.value();
        }
        return switch (v) {
            case Value.IntValue i -> digits >= 0
                    ? i
                    : Value.of(BigDecimal.valueOf(i.value()).setScale(digits, RoundingMode.HALF_UP).longValue());
            case Value.DoubleValue d -> Double.isNaN(d.value()) || Double.isInfinite(d.value())
                    ? d
                    : Value.of(BigDecimal.valueOf(d.value()).setScale(digits, RoundingMode.HALF_UP).doubleValue());
            default -> throw new SqlException("ROUND needs a number but got " + v.type());
        };
    }

    static boolean matches(RowFunction predicate, Row row, String context) {
        Value v = SqlValues.requireBooleanOrNull(predicate.apply(row), context);
        return SqlValues.isTrue(v);
    }
}
