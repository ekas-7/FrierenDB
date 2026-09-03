package dev.frierendb.agent.model;

import java.util.List;

public sealed interface ModelResponse {

    /** The model's final text; no further tools are wanted. */
    record Answer(String text) implements ModelResponse {}

    /** The model wants these tools run before it continues. {@code text} may be null. */
    record ToolRequest(String text, List<ToolCall> calls) implements ModelResponse {
        public ToolRequest {
            calls = List.copyOf(calls);
        }
    }
}
