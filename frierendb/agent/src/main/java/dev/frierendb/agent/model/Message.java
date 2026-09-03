package dev.frierendb.agent.model;

import java.util.List;

public sealed interface Message {

    record SystemMessage(String content) implements Message {}

    record UserMessage(String content) implements Message {}

    /** {@code content} may be null when the model only requested tools. */
    record AssistantMessage(String content, List<ToolCall> toolCalls) implements Message {
        public AssistantMessage {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    record ToolResultMessage(String toolCallId, String content) implements Message {}
}
