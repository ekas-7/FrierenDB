package dev.frierendb.engine.exec;

import java.time.Duration;

/** Per-statement budget: a wall-clock deadline and a cap on rows held in memory by blocking operators. */
final class ExecutionContext {

    static final long DEFAULT_MAX_MATERIALIZED_ROWS = 1_000_000;

    private final long deadlineNanos;
    private final boolean hasDeadline;
    private final long maxMaterializedRows;
    private long materialized;
    private int ticks;

    ExecutionContext(Duration timeout, long maxMaterializedRows) {
        this.hasDeadline = timeout != null && !timeout.isZero() && !timeout.isNegative();
        this.deadlineNanos = hasDeadline ? System.nanoTime() + timeout.toNanos() : 0;
        this.maxMaterializedRows = maxMaterializedRows;
    }

    static ExecutionContext unlimited() {
        return new ExecutionContext(null, DEFAULT_MAX_MATERIALIZED_ROWS);
    }

    /** Called once per row produced by scans and join loops. */
    void tick() {
        if (hasDeadline && (++ticks & 0x3FF) == 0 && System.nanoTime() - deadlineNanos > 0) {
            throw new QueryLimitException("Query exceeded its time limit");
        }
    }

    void checkDeadline() {
        if (hasDeadline && System.nanoTime() - deadlineNanos > 0) {
            throw new QueryLimitException("Query exceeded its time limit");
        }
    }

    void materialize(long rows) {
        materialized += rows;
        if (materialized > maxMaterializedRows) {
            throw new QueryLimitException(
                    "Query needs more than " + maxMaterializedRows + " intermediate rows in memory");
        }
    }
}
