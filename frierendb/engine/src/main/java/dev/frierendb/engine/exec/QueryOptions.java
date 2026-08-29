package dev.frierendb.engine.exec;

import java.time.Duration;

/** Resource limits for one statement. A zero timeout means no deadline. */
public record QueryOptions(long maxRows, Duration timeout, long maxMaterializedRows) {

    public QueryOptions {
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        if (maxMaterializedRows < 1) {
            throw new IllegalArgumentException("maxMaterializedRows must be positive");
        }
    }

    public static QueryOptions unlimited() {
        return new QueryOptions(Long.MAX_VALUE, Duration.ZERO, ExecutionContext.DEFAULT_MAX_MATERIALIZED_ROWS);
    }

    public static QueryOptions bounded(long maxRows, Duration timeout) {
        return new QueryOptions(maxRows, timeout, ExecutionContext.DEFAULT_MAX_MATERIALIZED_ROWS);
    }

    ExecutionContext newContext() {
        return new ExecutionContext(timeout, maxMaterializedRows);
    }
}
