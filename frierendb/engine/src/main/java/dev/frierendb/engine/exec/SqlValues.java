package dev.frierendb.engine.exec;

import dev.frierendb.engine.FrierenException;
import dev.frierendb.engine.sql.Expr.BinaryOp;
import dev.frierendb.engine.sql.SqlException;
import dev.frierendb.engine.types.Value;

/** SQL semantics for individual values: NULL propagation, numeric promotion, overflow checks. */
final class SqlValues {

    private SqlValues() {}

    /** Maps numerically equal INT and DOUBLE values to one representation so they hash alike. */
    static Value canonical(Value v) {
        if (v instanceof Value.DoubleValue d) {
            double x = d.value();
            if (x == Math.rint(x) && Math.abs(x) < 9.0e18) {
                return Value.of((long) x);
            }
        }
        return v;
    }

    static boolean isTrue(Value v) {
        return v instanceof Value.BoolValue b && b.value();
    }

    static Value requireBooleanOrNull(Value v, String context) {
        if (v.isNull() || v instanceof Value.BoolValue) {
            return v;
        }
        throw new SqlException(context + " must be a boolean expression but got " + v.type());
    }

    /** Returns NULL if either side is NULL, otherwise the boolean outcome of the comparison. */
    static Value compare(BinaryOp op, Value a, Value b) {
        if (a.isNull() || b.isNull()) {
            return Value.NULL;
        }
        int c;
        try {
            c = a.compareTo(b);
        } catch (FrierenException e) {
            throw new SqlException(e.getMessage(), e);
        }
        return Value.of(switch (op) {
            case EQ -> c == 0;
            case NE -> c != 0;
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            default -> throw new IllegalArgumentException("not a comparison: " + op);
        });
    }

    static Value arithmetic(BinaryOp op, Value a, Value b) {
        if (a.isNull() || b.isNull()) {
            return Value.NULL;
        }
        if (a instanceof Value.IntValue x && b instanceof Value.IntValue y) {
            return integerArithmetic(op, x.value(), y.value());
        }
        double x = toDouble(a);
        double y = toDouble(b);
        return switch (op) {
            case ADD -> Value.of(x + y);
            case SUBTRACT -> Value.of(x - y);
            case MULTIPLY -> Value.of(x * y);
            case DIVIDE -> {
                if (y == 0) {
                    throw new SqlException("Division by zero");
                }
                yield Value.of(x / y);
            }
            case MODULO -> {
                if (y == 0) {
                    throw new SqlException("Division by zero");
                }
                yield Value.of(x % y);
            }
            default -> throw new IllegalArgumentException("not arithmetic: " + op);
        };
    }

    private static Value integerArithmetic(BinaryOp op, long x, long y) {
        try {
            return switch (op) {
                case ADD -> Value.of(Math.addExact(x, y));
                case SUBTRACT -> Value.of(Math.subtractExact(x, y));
                case MULTIPLY -> Value.of(Math.multiplyExact(x, y));
                case DIVIDE -> {
                    if (y == 0) {
                        throw new SqlException("Division by zero");
                    }
                    if (x == Long.MIN_VALUE && y == -1) {
                        throw new ArithmeticException();
                    }
                    yield Value.of(x / y);
                }
                case MODULO -> {
                    if (y == 0) {
                        throw new SqlException("Division by zero");
                    }
                    yield Value.of(y == -1 ? 0L : x % y);
                }
                default -> throw new IllegalArgumentException("not arithmetic: " + op);
            };
        } catch (ArithmeticException e) {
            throw new SqlException("Integer overflow in " + x + " " + op + " " + y, e);
        }
    }

    static Value negate(Value v) {
        return switch (v) {
            case Value.NullValue n -> n;
            case Value.IntValue i -> {
                if (i.value() == Long.MIN_VALUE) {
                    throw new SqlException("Integer overflow in negation");
                }
                yield Value.of(-i.value());
            }
            case Value.DoubleValue d -> Value.of(-d.value());
            default -> throw new SqlException("Cannot negate a " + v.type() + " value");
        };
    }

    static double toDouble(Value v) {
        return switch (v) {
            case Value.IntValue i -> (double) i.value();
            case Value.DoubleValue d -> d.value();
            default -> throw new SqlException("Expected a number but got " + (v.isNull() ? "NULL" : v.type()));
        };
    }

    /** SQL LIKE with % and _ wildcards, case-sensitive, linear-space and free of backtracking blow-up. */
    static boolean like(String text, String pattern) {
        int t = 0;
        int p = 0;
        int starP = -1;
        int starT = 0;
        while (t < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '_' || pattern.charAt(p) == text.charAt(t))
                    && pattern.charAt(p) != '%') {
                t++;
                p++;
            } else if (p < pattern.length() && pattern.charAt(p) == '%') {
                starP = p++;
                starT = t;
            } else if (starP >= 0) {
                p = starP + 1;
                t = ++starT;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '%') {
            p++;
        }
        return p == pattern.length();
    }
}
