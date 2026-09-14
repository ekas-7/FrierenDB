# LLD 02 - Agent Class Reference

Root: `frierendb/agent/src/main/java/dev/frierendb/agent/`.

## `model`: provider boundary

| Class | Role | Notes |
|---|---|---|
| `ChatModel` (interface) | The only seam to an LLM | `ModelResponse chat(List<Message>, List<ToolSpec>)` |
| `Message` (sealed) | Conversation entries | `SystemMessage`, `UserMessage`, `AssistantMessage(content nullable, toolCalls)`, `ToolResultMessage(toolCallId, content)` |
| `ModelResponse` (sealed) | What the model returned | `Answer(text)`, `ToolRequest(text, calls)` |
| `ToolCall` (record) | `(id, name, JsonNode arguments)` | |
| `ToolSpec` (record) | `(name, description, JsonNode parametersSchema)` | JSON Schema |
| `ModelException` | Failure with `retryable()` | extends `FrierenException` |
| `OpenAiCompatibleModel` | HTTP client for `/chat/completions` with tools | `Config(baseUrl, apiKey, model, requestTimeout)`; key masked in `toString` |

## `tools`: capabilities

| Class | Role | Notes |
|---|---|---|
| `Tool` (interface) | `spec()` + `execute(JsonNode)` | |
| `ToolException` | Fixable problem shown to the model | |
| `ToolArguments` | Argument validation and schema builders | `requireString`, `singleStringSchema`, `emptySchema` |
| `ResultFormatter` | `QueryResult` → compact text | 200-char cells; footer with truncation notice |
| `DatabaseTools` | Factory and the four tools | `create(Database, QueryOptions)` → `Map<String, Tool>`; constants `MAX_SQL_CHARS = 4000`, `DATA_HEADER` |

Tools: `list_tables`, `describe_table`, `run_query`, `explain_query` (nested classes `ListTables`, `DescribeTable`, `RunQuery`, `ExplainQuery`).

## `core`: orchestration

| Class | Role | Notes |
|---|---|---|
| `AnalystAgent` | The loop | `new AnalystAgent(ChatModel, Database, AgentConfig)`, `AgentRun ask(String)` |
| `AgentConfig` (record) | All limits | `defaults()` = 8 steps, 4 calls/step, 120 s, 2 retries, 500 ms backoff, bounded(50 rows, 10 s), 6000 output chars, 2000 question chars |
| `AgentRun` (record) | Result of one question | `Status {ANSWERED, STEP_LIMIT, TIMEOUT, MODEL_FAILED}`; `successfulQueries()` |
| `TraceEvent` (record) | One audit entry | `(step, Kind {MODEL, TOOL}, name, detail, ok, millis)` |
| `SystemPrompt` (package-private) | Builds the system message with the schema | up to 40 tables listed |

## `cli`

| Class | Role | Notes |
|---|---|---|
| `Main` | Entry point, argument parsing, env-based model config | `--db`, `--demo`, `--eval`, `--help`; `modelFromEnv(Map)` |
| `Repl` | Command loop | constructor takes streams so it is testable; `run(BufferedReader, boolean interactive)` |
| `ResultPrinter` (package-private) | Aligned table output | 60-char cells |

## `demo` and `eval`

| Class | Role |
|---|---|
| `DemoData` | `seedIfEmpty(Database)` loads `/demo/university.sql` when no tables exist |
| `EvalCase` (record) | `(question, goldSql)` |
| `EvalRunner` | `loadCases(InputStream)`, `run(List<EvalCase>)` → `EvalReport` |
| `EvalReport` | `results()`, `passed()`, `total()`, `summary()` |

## Resources

- `src/main/resources/demo/university.sql`: the sample dataset.
- `src/main/resources/eval/university-questions.json`: 12 `{question, goldSql}` entries.

## Test support (in `src/test`)

| Class | Role |
|---|---|
| `support/ScriptedChatModel` | Replays scripted responses/exceptions; records requests (`requests`, `offeredTools`); helpers `of`, `repeating`, `call`, `runQuery`, `answer`, `toolResultsInLastRequest` |
| engine test-jar `TestDatabases` | Builds a small university database; `rows(db, sql)` helper |

## Dependency rules

- `model` depends on `engine` only for `FrierenException`.
- `tools` → `model` (for `ToolSpec`) and `engine` (`Database`, `QueryOptions`, `QueryResult`, `Schema`, `Column`).
- `core` → `model`, `tools`, `engine`.
- `cli` → `core`, `model`, `demo`, `eval`, `engine`.
- Nothing in the engine depends on the agent.
