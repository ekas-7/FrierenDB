package dev.frierendb.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.frierendb.agent.model.ChatModel;
import dev.frierendb.agent.model.Message;
import dev.frierendb.agent.model.ModelException;
import dev.frierendb.agent.model.ModelResponse;
import dev.frierendb.agent.model.ToolCall;
import dev.frierendb.agent.model.ToolSpec;
import dev.frierendb.agent.tools.DatabaseTools;
import dev.frierendb.agent.tools.Tool;
import dev.frierendb.agent.tools.ToolException;
import dev.frierendb.engine.Database;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan-act-observe loop that answers a question by calling read-only database tools. It stops on a
 * final answer, a step limit, a run deadline, or an unrecoverable model failure, and records a trace.
 */
public final class AnalystAgent {

    private final ChatModel model;
    private final Database db;
    private final AgentConfig config;
    private final Map<String, Tool> tools;
    private final List<ToolSpec> toolSpecs;

    public AnalystAgent(ChatModel model, Database db, AgentConfig config) {
        this.model = model;
        this.db = db;
        this.config = config;
        this.tools = DatabaseTools.create(db, config.queryOptions());
        this.toolSpecs = tools.values().stream().map(Tool::spec)
                .sorted(java.util.Comparator.comparing(ToolSpec::name)).toList();
    }

    public AgentRun ask(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("The question must not be blank");
        }
        if (question.length() > config.maxQuestionChars()) {
            throw new IllegalArgumentException("The question is too long (limit " + config.maxQuestionChars() + " characters)");
        }
        long start = System.nanoTime();
        List<TraceEvent> trace = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.SystemMessage(SystemPrompt.build(db)));
        messages.add(new Message.UserMessage(question));

        for (int step = 1; step <= config.maxSteps(); step++) {
            if (elapsedSince(start).compareTo(config.maxRunTime()) > 0) {
                return finish(question, AgentRun.Status.TIMEOUT,
                        "Stopped: the run exceeded its time limit of " + config.maxRunTime().toSeconds() + " seconds.",
                        trace, start);
            }
            ModelResponse response;
            try {
                response = callModel(messages, step, trace);
            } catch (ModelException e) {
                return finish(question, AgentRun.Status.MODEL_FAILED, "The model could not be reached: " + e.getMessage(), trace, start);
            }
            switch (response) {
                case ModelResponse.Answer answer -> {
                    return finish(question, AgentRun.Status.ANSWERED, answer.text(), trace, start);
                }
                case ModelResponse.ToolRequest request -> {
                    messages.add(new Message.AssistantMessage(request.text(), request.calls()));
                    runTools(request.calls(), step, messages, trace);
                }
            }
        }
        return finish(question, AgentRun.Status.STEP_LIMIT,
                "Stopped: no final answer after " + config.maxSteps() + " steps.", trace, start);
    }

    private ModelResponse callModel(List<Message> messages, int step, List<TraceEvent> trace) {
        for (int attempt = 0; ; attempt++) {
            long begin = System.nanoTime();
            try {
                ModelResponse response = model.chat(List.copyOf(messages), toolSpecs);
                if (response instanceof ModelResponse.Answer a && a.text().isBlank()) {
                    throw new ModelException("The model returned an empty answer", true);
                }
                String outcome = response instanceof ModelResponse.ToolRequest r
                        ? r.calls().size() + " tool call(s)"
                        : "final answer";
                trace.add(new TraceEvent(step, TraceEvent.Kind.MODEL, "chat", outcome, true, millisSince(begin)));
                return response;
            } catch (ModelException e) {
                trace.add(new TraceEvent(step, TraceEvent.Kind.MODEL, "chat", e.getMessage(), false, millisSince(begin)));
                if (!e.retryable() || attempt >= config.modelRetries()) {
                    throw e;
                }
                pause(config.retryBackoff().multipliedBy(1L << attempt));
            }
        }
    }

    private void runTools(List<ToolCall> calls, int step, List<Message> messages, List<TraceEvent> trace) {
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            long begin = System.nanoTime();
            String output;
            boolean ok = false;
            Tool tool = tools.get(call.name());
            if (i >= config.maxToolCallsPerStep()) {
                output = "ERROR: too many tool calls in one step; only the first "
                        + config.maxToolCallsPerStep() + " are executed. Call fewer tools at a time.";
            } else if (tool == null) {
                output = "ERROR: unknown tool '" + call.name() + "'. Available tools: " + String.join(", ", tools.keySet().stream().sorted().toList());
            } else {
                try {
                    output = tool.execute(call.arguments());
                    ok = true;
                } catch (ToolException e) {
                    output = "ERROR: " + e.getMessage();
                } catch (RuntimeException e) {
                    output = "ERROR: the tool failed unexpectedly";
                    trace.add(new TraceEvent(step, TraceEvent.Kind.TOOL, call.name(),
                            "internal failure " + e.getClass().getSimpleName() + ": " + e.getMessage(), false, millisSince(begin)));
                }
            }
            trace.add(new TraceEvent(step, TraceEvent.Kind.TOOL, call.name(), argumentsText(call.arguments()), ok, millisSince(begin)));
            messages.add(new Message.ToolResultMessage(call.id(), truncate(output)));
        }
    }

    private String truncate(String output) {
        int limit = config.maxToolOutputChars();
        return output.length() <= limit ? output : output.substring(0, limit) + "\n[output truncated]";
    }

    private static String argumentsText(JsonNode arguments) {
        return arguments == null ? "{}" : arguments.toString();
    }

    private void pause(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("Interrupted while waiting to retry", false, e);
        }
    }

    private static AgentRun finish(String question, AgentRun.Status status, String answer,
            List<TraceEvent> trace, long start) {
        return new AgentRun(question, status, answer, trace, elapsedSince(start));
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static long millisSince(long startNanos) {
        return elapsedSince(startNanos).toMillis();
    }
}
