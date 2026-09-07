package dev.frierendb.agent.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.frierendb.agent.model.ChatModel;
import dev.frierendb.agent.model.Message;
import dev.frierendb.agent.model.ModelResponse;
import dev.frierendb.agent.model.ToolCall;
import dev.frierendb.agent.model.ToolSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Deterministic stand-in for an LLM: replays a script and records every request it receives. */
public final class ScriptedChatModel implements ChatModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Deque<Object> script = new ArrayDeque<>();
    private final boolean repeatLast;
    private Object last;
    public final List<List<Message>> requests = new ArrayList<>();
    public final List<List<ToolSpec>> offeredTools = new ArrayList<>();

    private ScriptedChatModel(List<Object> steps, boolean repeatLast) {
        script.addAll(steps);
        this.repeatLast = repeatLast;
    }

    /** Each element is a {@link ModelResponse} to return or a {@link RuntimeException} to throw. */
    public static ScriptedChatModel of(Object... steps) {
        return new ScriptedChatModel(List.of(steps), false);
    }

    public static ScriptedChatModel repeating(Object step) {
        return new ScriptedChatModel(List.of(step), true);
    }

    public static ToolCall call(String id, String tool, String argumentsJson) {
        try {
            return new ToolCall(id, tool, MAPPER.readTree(argumentsJson));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static ModelResponse runQuery(String id, String sqlText) {
        return new ModelResponse.ToolRequest(null, List.of(
                new ToolCall(id, "run_query", MAPPER.createObjectNode().put("sql", sqlText))));
    }

    public static ModelResponse answer(String text) {
        return new ModelResponse.Answer(text);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolSpec> tools) {
        requests.add(List.copyOf(messages));
        offeredTools.add(List.copyOf(tools));
        Object next = script.poll();
        if (next == null) {
            if (!repeatLast || last == null) {
                throw new AssertionError("The model was called more often than the script allows");
            }
            next = last;
        }
        last = next;
        if (next instanceof RuntimeException e) {
            throw e;
        }
        return (ModelResponse) next;
    }

    public int calls() {
        return requests.size();
    }

    /** Content of every tool result the model has been shown in its latest request. */
    public List<String> toolResultsInLastRequest() {
        return requests.get(requests.size() - 1).stream()
                .filter(m -> m instanceof Message.ToolResultMessage)
                .map(m -> ((Message.ToolResultMessage) m).content())
                .toList();
    }
}
