package dev.frierendb.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat-completions client for any server speaking the OpenAI tool-calling protocol (OpenAI, Ollama,
 * vLLM and others). The API key is never logged and never appears in error messages.
 */
public final class OpenAiCompatibleModel implements ChatModel {

    public record Config(URI baseUrl, String apiKey, String model, Duration requestTimeout) {

        public Config {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model must not be blank");
            }
        }

        @Override
        public String toString() {
            return "Config[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null ? "none" : "***")
                    + ", model=" + model + ", requestTimeout=" + requestTimeout + "]";
        }
    }

    private static final int MAX_ERROR_BODY_CHARS = 300;

    private final Config config;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleModel(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolSpec> tools) {
        HttpRequest request = buildRequest(messages, tools);
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ModelException("Could not reach the model server: " + e.getClass().getSimpleName(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("Interrupted while waiting for the model", false, e);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new ModelException("The model server rejected the credentials (HTTP " + status + ")", false);
        }
        if (status == 429 || status >= 500) {
            throw new ModelException("The model server is unavailable or rate limiting (HTTP " + status + ")", true);
        }
        if (status != 200) {
            throw new ModelException("The model server returned HTTP " + status + ": " + abbreviate(response.body()), false);
        }
        return parse(response.body());
    }

    private HttpRequest buildRequest(List<Message> messages, List<ToolSpec> tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());
        body.put("temperature", 0);
        body.set("messages", messagesJson(messages));
        if (!tools.isEmpty()) {
            body.set("tools", toolsJson(tools));
            body.put("tool_choice", "auto");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl().toString().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }
        return builder.build();
    }

    private ArrayNode messagesJson(List<Message> messages) {
        ArrayNode array = mapper.createArrayNode();
        for (Message message : messages) {
            ObjectNode node = array.addObject();
            switch (message) {
                case Message.SystemMessage m -> {
                    node.put("role", "system");
                    node.put("content", m.content());
                }
                case Message.UserMessage m -> {
                    node.put("role", "user");
                    node.put("content", m.content());
                }
                case Message.AssistantMessage m -> {
                    node.put("role", "assistant");
                    if (m.content() == null) {
                        node.putNull("content");
                    } else {
                        node.put("content", m.content());
                    }
                    if (!m.toolCalls().isEmpty()) {
                        ArrayNode calls = node.putArray("tool_calls");
                        for (ToolCall call : m.toolCalls()) {
                            ObjectNode c = calls.addObject();
                            c.put("id", call.id());
                            c.put("type", "function");
                            ObjectNode function = c.putObject("function");
                            function.put("name", call.name());
                            function.put("arguments", call.arguments().toString());
                        }
                    }
                }
                case Message.ToolResultMessage m -> {
                    node.put("role", "tool");
                    node.put("tool_call_id", m.toolCallId());
                    node.put("content", m.content());
                }
            }
        }
        return array;
    }

    private ArrayNode toolsJson(List<ToolSpec> tools) {
        ArrayNode array = mapper.createArrayNode();
        for (ToolSpec tool : tools) {
            ObjectNode node = array.addObject();
            node.put("type", "function");
            ObjectNode function = node.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.parametersSchema());
        }
        return array;
    }

    private ModelResponse parse(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ModelException("The model server returned invalid JSON", false, e);
        }
        JsonNode message = root.path("choices").path(0).path("message");
        if (message.isMissingNode() || !message.isObject()) {
            throw new ModelException("The model server response has no choices[0].message", false);
        }
        String text = message.path("content").isTextual() ? message.get("content").asText() : null;
        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            return new ModelResponse.Answer(text == null ? "" : text);
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : toolCalls) {
            String name = call.path("function").path("name").asText("");
            String id = call.path("id").asText("");
            if (name.isEmpty() || id.isEmpty()) {
                throw new ModelException("The model sent a tool call without an id or name", true);
            }
            calls.add(new ToolCall(id, name, parseArguments(call.path("function").path("arguments"))));
        }
        return new ModelResponse.ToolRequest(text, calls);
    }

    private JsonNode parseArguments(JsonNode arguments) {
        if (arguments.isObject()) {
            return arguments;
        }
        if (!arguments.isTextual() || arguments.asText().isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(arguments.asText());
            if (!parsed.isObject()) {
                throw new ModelException("The model sent tool arguments that are not a JSON object", true);
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new ModelException("The model sent malformed tool arguments", true, e);
        }
    }

    private static String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_ERROR_BODY_CHARS ? flat : flat.substring(0, MAX_ERROR_BODY_CHARS) + "...";
    }
}
