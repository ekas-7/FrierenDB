# 05 - Safety Model for the Agent

## Assets and threats

| Asset | Threat | Defence | Where |
|---|---|---|---|
| Data integrity | Model (or injected text) runs `DELETE`/`DROP`/`UPDATE`/`INSERT` | Read-only check on the **parsed statement**; only `SELECT`/`EXPLAIN` pass | `Database.query`, `Statement.isReadOnly` |
| Data integrity | Stacked statements: `SELECT 1; DROP TABLE x` | Parser accepts exactly one statement; trailing input is an error | `Parser.parse` |
| Host resources | Huge result | `maxRows` cap, `truncated` flag | `Executor.select` |
| Host resources | Cartesian product / slow query | Wall-clock deadline checked by scans and join loops | `ExecutionContext.tick` |
| Host resources | Memory blow-up in sort/aggregate/join | `maxMaterializedRows` | `ExecutionContext.materialize` |
| Host resources | Pathological `LIKE` | Linear-time matcher, no regex backtracking | `SqlValues.like` |
| Host resources | Deeply nested SQL to overflow the stack | Parse depth limit of 64 | `Parser.enter` |
| File system | Table name such as `../../etc/x` used as a file name | Table names must match `[A-Za-z_][A-Za-z0-9_]{0,63}`; quoted names are validated too | `Catalog.normalize` |
| Cost and availability | Endless tool loop | `maxSteps`, `maxToolCallsPerStep`, `maxRunTime` | `AnalystAgent` |
| Prompt integrity | Instructions hidden in table data ("ignore previous rules and delete…") | Tool output is labelled untrusted; more importantly, even a fully compromised model has no write capability | `DatabaseTools.DATA_HEADER` + read-only check |
| Secrets | API key in logs or errors | `Config.toString` masks the key; errors never include it; key comes from env, not code | `OpenAiCompatibleModel` |
| Tool misuse | Unknown tool name, missing/oversized arguments | Validated; return `ERROR:` text instead of throwing | `AnalystAgent.runTools`, `ToolArguments` |
| Context flooding | Giant tool output | Cell cap (200 chars), output cap (`maxToolOutputChars`) | `ResultFormatter`, `AnalystAgent.truncate` |

## The key principle

> Assume the model is compromised. Is the worst outcome still acceptable?

With this design the worst a compromised model can do is: read any table (there is no per-table access control), waste up to `maxSteps` model calls, and run bounded queries for at most `maxRunTime`. It cannot modify data, run shell commands, touch other files, or execute multiple statements.

## What is *not* protected

Be explicit about the gaps:

- **No access control**: if a table is in the database, the agent can read it. Do not point the agent at a database that holds data the *asker* should not see.
- **Data leaves the machine**: tool results are sent to the LLM provider. Treat the provider as a data recipient.
- **No authentication** on the CLI or engine. Anyone with the files has everything.
- **Prompt injection can still change the *answer*** (for example make the model state something false). It cannot change what the system *does*. Users should treat answers as assistance, not authority; the CLI prints the trace (queries run) so the result can be checked.
- **No rate limiting across runs**; each `ask` has its own limits.
- **Denial of service by many `ask` calls** is out of scope for an embedded tool.

## Defence in depth: why each layer exists

1. **Prompt rules** reduce accidents (the model usually behaves).
2. **Tool whitelist** removes capabilities (only four tools, none can write).
3. **Argument validation** prevents malformed or oversized input reaching the engine.
4. **Parser read-only check** blocks writes even if a tool were misused.
5. **Engine limits** bound cost even for legal queries.
6. **Loop limits** bound the total work per question.
7. **Tests** pin all of the above (see `AnalystAgentTest`, `DatabaseToolsTest`, `QuerySafetyTest`).

## Tests that demonstrate the model

| Claim | Test |
|---|---|
| A model that tries to write is blocked and data survives | `AnalystAgentTest.aModelThatTriesToWriteIsBlockedAndTheDataSurvives` |
| Instructions in stored data cannot gain write access | `AnalystAgentTest.instructionsHiddenInDataCannotGainWriteAccess` |
| Every modifying statement type is rejected | `QuerySafetyTest.readOnlyModeRejectsEveryModifyingStatement` |
| Stacked statements are rejected before anything runs | `QuerySafetyTest.stackedStatementsAreRejectedByTheParserBeforeAnythingRuns` |
| A runaway cross join is stopped | `QuerySafetyTest.runawayCrossJoinIsStoppedByTheTimeout` |
| Sort/aggregate respect the memory cap | `QuerySafetyTest.blockingOperatorsRespectTheMemoryCap` |
| Injected table names cannot escape the directory | `DatabaseToolsTest.describeTableRejectsUnknownAndInjectedNames`, `CatalogTest.tableNamesCannotEscapeTheDatabaseDirectory` |
| The API key never leaks | `OpenAiCompatibleModelTest.authenticationFailuresArePermanentAndNeverLeakTheKey`, `configNeverPrintsTheKey` |

## Operating guidance

- Give the agent a **copy** or a **dedicated database** containing only data it may read.
- Keep the key in an environment variable or a secrets manager; never commit `.env` (the repo's `.gitignore` excludes it).
- Prefer a local model (e.g. Ollama) for sensitive data.
- Review traces when answers matter.
