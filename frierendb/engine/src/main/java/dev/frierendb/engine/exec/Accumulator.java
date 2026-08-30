package dev.frierendb.engine.exec;

import dev.frierendb.engine.FrierenException;
import dev.frierendb.engine.sql.Expr.AggregateFunction;
import dev.frierendb.engine.sql.SqlException;
import dev.frierendb.engine.types.Value;
import java.util.HashSet;
import java.util.Set;

/** Running state of one aggregate over one group. NULL inputs are ignored, as in SQL. */
interface Accumulator {

    void add(Value value);

    Value result();

    static Accumulator create(AggregateFunction function, boolean countStar, boolean distinct) {
        Accumulator base = switch (function) {
            case COUNT -> new Count(countStar);
            case SUM -> new Sum();
            case AVG -> new Avg();
            case MIN -> new Extreme(true);
            case MAX -> new Extreme(false);
        };
        return distinct ? new Distinct(base) : base;
    }

    final class Count implements Accumulator {
        private final boolean countStar;
        private long count;

        Count(boolean countStar) {
            this.countStar = countStar;
        }

        @Override
        public void add(Value value) {
            if (countStar || !value.isNull()) {
                count++;
            }
        }

        @Override
        public Value result() {
            return Value.of(count);
        }
    }

    final class Sum implements Accumulator {
        private long longSum;
        private double doubleSum;
        private boolean sawDouble;
        private boolean any;

        @Override
        public void add(Value value) {
            if (value.isNull()) {
                return;
            }
            any = true;
            doubleSum += SqlValues.toDouble(value);
            if (value instanceof Value.IntValue i) {
                try {
                    longSum = Math.addExact(longSum, i.value());
                } catch (ArithmeticException e) {
                    throw new SqlException("Integer overflow in SUM", e);
                }
            } else {
                sawDouble = true;
            }
        }

        @Override
        public Value result() {
            if (!any) {
                return Value.NULL;
            }
            return sawDouble ? Value.of(doubleSum) : Value.of(longSum);
        }
    }

    final class Avg implements Accumulator {
        private double sum;
        private long count;

        @Override
        public void add(Value value) {
            if (!value.isNull()) {
                sum += SqlValues.toDouble(value);
                count++;
            }
        }

        @Override
        public Value result() {
            return count == 0 ? Value.NULL : Value.of(sum / count);
        }
    }

    final class Extreme implements Accumulator {
        private final boolean min;
        private Value best = Value.NULL;

        Extreme(boolean min) {
            this.min = min;
        }

        @Override
        public void add(Value value) {
            if (value.isNull()) {
                return;
            }
            if (best.isNull()) {
                best = value;
                return;
            }
            int c;
            try {
                c = value.compareTo(best);
            } catch (FrierenException e) {
                throw new SqlException(e.getMessage(), e);
            }
            if (min ? c < 0 : c > 0) {
                best = value;
            }
        }

        @Override
        public Value result() {
            return best;
        }
    }

    final class Distinct implements Accumulator {
        private final Accumulator delegate;
        private final Set<Value> seen = new HashSet<>();

        Distinct(Accumulator delegate) {
            this.delegate = delegate;
        }

        @Override
        public void add(Value value) {
            if (value.isNull() || seen.add(SqlValues.canonical(value))) {
                delegate.add(value);
            }
        }

        @Override
        public Value result() {
            return delegate.result();
        }
    }
}
