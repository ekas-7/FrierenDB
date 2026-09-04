package dev.frierendb.agent.model;

import java.util.List;

/** The only point where the agent touches an LLM provider. */
public interface ChatModel {

    /** Sends the conversation and available tools, and returns either an answer or tool requests. */
    ModelResponse chat(List<Message> messages, List<ToolSpec> tools);
}
