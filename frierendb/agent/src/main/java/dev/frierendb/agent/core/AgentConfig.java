package dev.frierendb.agent.core;

import dev.frierendb.engine.exec.QueryOptions;
import java.time.Duration;

/** Limits that bound every agent run, so cost, time and database load are finite by construction. */
public record AgentConfig(
        int maxSteps,
        int maxToolCallsPerStep,
        Duration maxRunTime,
        int modelRetries,
        Duration retryBackoff,
        QueryOptions queryOptions,
        int maxToolOutputChars,
        int maxQuestionChars) {

    public AgentConfig {
        if (maxSteps < 1 || maxToolCallsPerStep < 1 || modelRetries < 0
                || maxToolOutputChars < 100 || maxQuestionChars < 1) {
            throw new IllegalArgumentException("Invalid agent limits");
        }
    }

    public static AgentConfig defaults() {
        return new AgentConfig(8, 4, Duration.ofSeconds(120), 2, Duration.ofMillis(500),
                QueryOptions.bounded(50, Duration.ofSeconds(10)), 6000, 2000);
    }
}
