# Step 16 - Agent: Model Layer

Package: `dev.frierendb.agent.model`. Module: `agent` (new).

## Goal

A provider-neutral way to talk to an LLM with tool calling, plus one real implementation for OpenAI-compatible servers. Everything above this layer is testable without a network.

## Concepts

- **Chat completion with tools**: you send a list of messages and a list of tool descriptions (name, text, JSON Schema for arguments). The model answers with text **or** with tool calls.
- **Tool call round trip**: the assistant message carrying `tool_calls` must be followed by one `tool` message per call id before the next model request.
- **Retryable vs permanent errors**: rate limits and outages may succeed later; bad credentials never will.

## Set up the module first

1. Add `<module>agent</module>` to the parent POM.
2. `agent/pom.xml`: depends on `frierendb-engine`, `jackson-databind` 2.18.2, JUnit (test), and the engine's **test-jar** with `<type>test-jar</type><scope>test</scope>`.
3. In `engine/pom.xml` add the `maven-jar-plugin` `test-jar` goal so the engine publishes its test classes (`TestDatabases`) to the reactor.
4. Add `exec-maven-plugin` with `mainClass` set to the CLI class (used in step 19).

## Design

### Types

```java
public sealed interface Message {
    record SystemMessage(String content) implements Message {}
    record UserMessage(String content) implements Message {}
    record AssistantMessage(String content, List<ToolCall> toolCalls) implements Message {}   // content may be null
    record ToolResultMessage(String toolCallId, String content) implements Message {}
}
public record ToolCall(String id, String name, JsonNode arguments)
public record ToolSpec(String name, String description, JsonNode parametersSchema)
public sealed interface ModelResponse {
    record Answer(String text) implements ModelResponse {}
    record ToolRequest(String text, List<ToolCall> calls) implements ModelResponse {}
}
public interface ChatModel { ModelResponse chat(List<Message> messages, List<ToolSpec> tools); }
public class ModelException extends FrierenException { boolean retryable(); }
```

`ChatModel` is the **only** seam between the agent and any provider.

### `OpenAiCompatibleModel`

- `Config(URI baseUrl, String apiKey, String model, Duration requestTimeout)`; blank model rejected; `toString` prints `apiKey=***` (or `none`), never the key.
- Request: `POST <baseUrl>/chat/completions` (trailing slashes on the base URL trimmed) with JSON:

```json
{ "model": "...", "temperature": 0,
  "messages": [ ... ],
  "tools": [ {"type":"function","function":{"name":"...","description":"...","parameters":{...}}} ],
  "tool_choice": "auto" }
```

  `tools` and `tool_choice` are omitted when no tools are offered. The `Authorization: Bearer <key>` header is sent only when a key is configured (local servers need none).
- Message mapping: system/user → `{role, content}`; assistant → `{role:"assistant", content (or null), tool_calls:[{id,type:"function",function:{name, arguments:"<JSON text>"}}]}`; tool result → `{role:"tool", tool_call_id, content}`.
- Response parsing: read `choices[0].message`. No `tool_calls` → `Answer(content or "")`. Otherwise each call needs a non-empty `id` and `function.name`, and `arguments` may be a JSON **string** (OpenAI) or an object (some servers) or empty (becomes `{}`); anything else that is not a JSON object is an error.
- Status handling:

| HTTP | Result |
|---|---|
| 200 | parse |
| 401, 403 | `ModelException(retryable=false)`, message mentions status, never the key |
| 429, ≥ 500 | `retryable=true` |
| other | `retryable=false`, message includes the body flattened and cut to 300 characters |
| `IOException` | `retryable=true` ("Could not reach the model server: <exception class>") |
| interrupted | restores the interrupt flag, `retryable=false` |

- Invalid JSON, a missing `choices[0].message`, or a tool call without id/name: malformed JSON and missing message are permanent; malformed **tool arguments** or a missing id/name are **retryable** because the model may produce a correct call next time.
- `HttpClient` has a 10 s connect timeout; each request uses `Config.requestTimeout`.

## Build it

1. Message/response records and `ChatModel`.
2. `ModelException(message, retryable)` and `(message, retryable, cause)` constructors.
3. The HTTP client with `java.net.http.HttpClient`; build JSON with Jackson's `ObjectMapper` tree API (`ObjectNode`, `ArrayNode`) rather than string concatenation, to avoid injection and escaping bugs.
4. Parsing helpers: `parseArguments(JsonNode)` handles object / textual / blank cases.
5. Provide `abbreviate(body)` for error bodies (collapse whitespace, cap at 300 characters).

## Pitfalls

- **Never put the key in an exception, log line or `toString`**. Test this explicitly.
- Use `tool_call_id` exactly as the model sent it when replying with the tool result.
- An assistant message with `tool_calls` must keep `content` as JSON `null` (not omitted) if there is no text; some servers reject a missing field.
- Temperature 0 improves repeatability but does not guarantee identical outputs.
- Do not parse the response into your own types with Jackson data binding; you want tolerant tree access because servers differ in small ways.

## Tests to write (`OpenAiCompatibleModelTest`, 12, using a real local `com.sun.net.httpserver.HttpServer` on an ephemeral port)

Text answer parsed; tool calls with JSON-string arguments parsed; the request carries the conversation, tools and credentials in protocol format; no `Authorization` header without a key and no `tools` without tools; 429/5xx retryable; 401/403 permanent and the key never appears in the message; other 4xx permanent with a short body; malformed responses rejected; malformed tool arguments retryable; empty arguments become `{}`; unreachable server retryable; `Config.toString` never prints the key.

## Checkpoint

The test suite proves the client against a local server, and `ScriptedChatModel` (a test helper that replays a list of `ModelResponse`s or exceptions and records requests) is available for the next steps.
