package dev.frierendb.engine.sql;

import dev.frierendb.engine.types.Value;
import java.util.List;

public sealed interface Expr {

    enum UnaryOp { NEGATE, NOT }

    enum BinaryOp {
        ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO,
        EQ, NE, LT, LE, GT, GE,
        AND, OR, LIKE
    }

    enum AggregateFunction { COUNT, SUM, AVG, MIN, MAX }

    enum ScalarFunction {
        LOWER(1, 1), UPPER(1, 1), LENGTH(1, 1), ABS(1, 1), ROUND(1, 2), COALESCE(1, Integer.MAX_VALUE);

        private final int minArgs;
        private final int maxArgs;

        ScalarFunction(int minArgs, int maxArgs) {
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
        }

        public boolean acceptsArgumentCount(int count) {
            return count >= minArgs && count <= maxArgs;
        }
    }

    record Literal(Value value) implements Expr {}

    /** {@code table} is null when the reference is unqualified. */
    record ColumnRef(String table, String column) implements Expr {
        @Override
        public String toString() {
            return table == null ? column : table + "." + column;
        }
    }

    record Unary(UnaryOp op, Expr operand) implements Expr {}

    record Binary(BinaryOp op, Expr left, Expr right) implements Expr {}

    record IsNull(Expr operand, boolean negated) implements Expr {}

    record InList(Expr operand, List<Expr> items, boolean negated) implements Expr {
        public InList {
            items = List.copyOf(items);
        }
    }

    /** {@code argument} is null for COUNT(*). */
    record Aggregate(AggregateFunction function, Expr argument, boolean distinct) implements Expr {}

    record Call(ScalarFunction function, List<Expr> arguments) implements Expr {
        public Call {
            arguments = List.copyOf(arguments);
        }
    }
}
