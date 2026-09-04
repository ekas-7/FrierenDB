package dev.frierendb.agent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "sk-test-secret-123";

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath());
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAiCompatibleModel model(String apiKey) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        return new OpenAiCompatibleModel(new OpenAiCompatibleModel.Config(base, apiKey, "test-model", Duration.ofSeconds(5)));
    }

    private static final List<Message> CONVERSATION = List.of(
            new Message.SystemMessage("be careful"),
            new Message.UserMessage("how many students?"));

    private static final List<ToolSpec> TOOLS = List.of(new ToolSpec("run_query", "runs sql",
            MAPPER.createObjectNode().put("type", "object")));

    @Test
    void parsesATextAnswer() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"six\"}}]}";
        ModelResponse response = model(SECRET).chat(CONVERSATION, TOOLS);
        assertEquals("six", assertInstanceOf(ModelResponse.Answer.class, response).text());
    }

    @Test
    void parsesToolCallsWithJsonStringArguments() {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"run_query","arguments":"{\\"sql\\":\\"select 1 from t\\"}"}}
                ]}}]}""";
        ModelResponse.ToolRequest request = assertInstanceOf(ModelResponse.ToolRequest.class,
                model(SECRET).chat(CONVERSATION, TOOLS));
        assertNull(request.text());
        assertEquals("call_1", request.calls().get(0).id());
        assertEquals("run_query", request.calls().get(0).name());
        assertEquals("select 1 from t", request.calls().get(0).arguments().get("sql").asText());
    }

    @Test
    void sendsTheConversationToolsAndCredentialsInTheProtocolFormat() throws IOException {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        List<Message> conversation = List.of(
                new Message.SystemMessage("sys"),
                new Message.UserMessage("question"),
                new Message.AssistantMessage(null, List.of(new ToolCall("c1", "run_query", MAPPER.createObjectNode().put("sql", "select 1 from t")))),
                new Message.ToolResultMessage("c1", "RESULT 1"));

        model(SECRET).chat(conversation, TOOLS);

        assertEquals("/v1/chat/completions", lastPath.get());
        assertEquals("Bearer " + SECRET, lastAuth.get());
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("test-model", body.get("model").asText());
        assertEquals(0, body.get("temperature").asInt());
        assertEquals("function", body.get("tools").get(0).get("type").asText());
        assertEquals("run_query", body.get("tools").get(0).get("function").get("name").asText());
        JsonNode messages = body.get("messages");
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("assistant", messages.get(2).get("role").asText());
        assertTrue(messages.get(2).get("content").isNull());
        JsonNode sentCall = messages.get(2).get("tool_calls").get(0);
        assertEquals("c1", sentCall.get("id").asText());
        assertEquals("{\"sql\":\"select 1 from t\"}", sentCall.get("function").get("arguments").asText());
        assertEquals("tool", messages.get(3).get("role").asText());
        assertEquals("c1", messages.get(3).get("tool_call_id").asText());
    }

    @Test
    void omitsAuthorizationWithoutAKeyAndToolsWhenThereAreNone() throws IOException {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        model(null).chat(CONVERSATION, List.of());
        assertNull(lastAuth.get());
        assertFalse(MAPPER.readTree(lastBody.get()).has("tools"));
    }

    @Test
    void rateLimitsAndServerErrorsAreRetryable() {
        for (int code : new int[] {429, 500, 503}) {
            status = code;
            ModelException e = assertThrows(ModelException.class, () -> model(SECRET).chat(CONVERSATION, TOOLS));
            assertTrue(e.retryable(), "HTTP " + code);
        }
    }

    @Test
    void authenticationFailuresArePermanentAndNeverLeakTheKey() {
        for (int code : new int[] {401, 403}) {
            status = code;
            responseBody = "{\"error\":\"bad key " + SECRET + "\"}";
            ModelException e = assertThrows(ModelException.class, () -> model(SECRET).chat(CONVERSATION, TOOLS));
            assertFalse(e.retryable());
            assertFalse(e.getMessage().contains(SECRET), e.getMessage());
        }
    }

    @Test
    void otherClientErrorsArePermanentAndCarryAShortBody() {
        status = 400;
        responseBody = "{\"error\":\"" + "x".repeat(2000) + "\"}";
        ModelException e = assertThrows(ModelException.class, () -> model(SECRET).chat(CONVERSATION, TOOLS));
        assertFalse(e.retryable());
        assertTrue(e.getMessage().length() < 450, "error bodies must be abbreviated");
    }

    @Test
    void malformedResponsesAreRejected() {
        responseBody = "not json";
        assertFalse(assertThrows(ModelException.class, () -> model(SECRET).chat(CONVERSATION, TOOLS)).retryable());
        responseBody = "{\"choices\":[]}";
        assertFalse(assertThrows(ModelException.class, () -> model(SECRET).chat(CONVERSATION, TOOLS)).retryable());
    }

    @Test
    void malformedToolArgumentsAreRetryableBecauseTheModelMayFixThem() {
        responseBody = """
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"c","type":"function","function":{"name":"run_query","arguments":"{not json"}}]}}]}""";
        assertTrue(assertThrows(ModelException.class, () -> model(SECRET).chat(CONVERSATION, TOOLS)).retryable());
        responseBody = """
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"c","type":"function","function":{"name":"run_query","arguments":"[1,2]"}}]}}]}""";
        assertTrue(assertThrows(ModelException.class, () -> model(SECRET).chat(CONVERSATION, TOOLS)).retryable());
    }

    @Test
    void emptyToolArgumentsBecomeAnEmptyObject() {
        responseBody = """
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"c","type":"function","function":{"name":"list_tables","arguments":""}}]}}]}""";
        ModelResponse.ToolRequest request = assertInstanceOf(ModelResponse.ToolRequest.class,
                model(SECRET).chat(CONVERSATION, TOOLS));
        assertTrue(request.calls().get(0).arguments().isEmpty());
    }

    @Test
    void anUnreachableServerIsRetryable() {
        int deadPort = server.getAddress().getPort();
        server.stop(0);
        OpenAiCompatibleModel dead = new OpenAiCompatibleModel(new OpenAiCompatibleModel.Config(
                URI.create("http://127.0.0.1:" + deadPort + "/v1"), SECRET, "m", Duration.ofSeconds(2)));
        ModelException e = assertThrows(ModelException.class, () -> dead.chat(CONVERSATION, TOOLS));
        assertTrue(e.retryable());
        assertFalse(e.getMessage().contains(SECRET));
    }

    @Test
    void configNeverPrintsTheKey() {
        var config = new OpenAiCompatibleModel.Config(URI.create("http://localhost/v1"), SECRET, "m", Duration.ofSeconds(1));
        assertFalse(config.toString().contains(SECRET));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleModel.Config(URI.create("http://localhost/v1"), SECRET, " ", Duration.ofSeconds(1)));
    }
}
