package dev.frierendb.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;

/** The outcome of one question. {@code answer} is the model's text, or the reason the run stopped. */
public record AgentRun(String question, Status status, String answer, List<TraceEvent> trace, Duration elapsed) {

    public enum Status { ANSWERED, STEP_LIMIT, TIMEOUT, MODEL_FAILED }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public AgentRun {
        trace = List.copyOf(trace);
    }

    /** SQL of every run_query call that succeeded, in order. */
    public List<String> successfulQueries() {
        return trace.stream()
                .filter(e -> e.kind() == TraceEvent.Kind.TOOL && e.ok() && e.name().equals("run_query"))
                .map(e -> sqlOf(e.detail()))
                .toList();
    }

    private static String sqlOf(String argumentsJson) {
        try {
            return MAPPER.readTree(argumentsJson).path("sql").asText("");
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
