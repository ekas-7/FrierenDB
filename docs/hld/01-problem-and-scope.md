# 01 - Problem and Scope

## The problem

People who own data in a relational store often cannot write SQL. A natural-language interface sounds simple ("ask a question, get an answer"), but a careless one is dangerous: a language model that can run arbitrary SQL can delete data, leak it, or burn resources, and model output cannot be trusted.

FrierenDB attacks the problem from both ends:

1. **A small, understandable relational engine** written from scratch, so every behaviour (NULL handling, limits, read-only enforcement) is under our control and testable.
2. **An analyst agent** that answers questions by calling a handful of *read-only, bounded* tools against that engine.

Owning the engine is what makes the safety story credible: the "read-only" rule is enforced by the same parser that executes the statement, not by a wrapper around someone else's driver.

## Goals

| Goal | How it is met |
|---|---|
| Correct core SQL for analytics | `SELECT` with joins, grouping, aggregates, ordering; three-valued logic; overflow and divide-by-zero are errors |
| Durable embedded storage | Page file, buffer pool, slotted pages, heap file, persistent catalog |
| Safe AI access | Four tools, read-only parser check, row/time/memory caps, bounded loop |
| Testable without an LLM | `ChatModel` interface and a scripted fake; a local HTTP server for the real client |
| Honest evaluation | Execution-accuracy harness with reference SQL; no accuracy is claimed without a real run |
| Understandable | Small classes, sealed types, few dependencies, these docs |

## Non-goals (not built)

- Indexes (no B+ tree), so every scan is a full table scan.
- Transactions, locking, MVCC, or a write-ahead log. A crash during a write can corrupt data.
- Network server, wire protocol, authentication, multi-user access.
- Full SQL: no subqueries, CTEs, `UNION`, `CASE`, `OFFSET`, window functions, comma joins, constraints other than `NOT NULL`.
- Query optimisation beyond choosing a hash join for equi-joins.
- Multiple LLM provider SDKs. Only the OpenAI-style chat-completions protocol is implemented.

See [`06-limitations-and-roadmap.md`](06-limitations-and-roadmap.md) for how each would be approached.

## Users and use cases

| Actor | Does | Through |
|---|---|---|
| Developer | Embeds the engine in Java code | `Database.open(dir)`, `execute`, `query` |
| Analyst | Asks English questions about their data | CLI `ask ...` |
| Database owner | Seeds data, runs admin SQL | CLI raw SQL (full privileges) |
| Maintainer | Checks agent quality | CLI `--eval` against a real model |

Two privilege levels exist and they never mix: **raw SQL typed by a human** runs with full privileges; **everything the model triggers** goes through `Database.query`, which accepts only `SELECT` and `EXPLAIN`.

## Constraints that shaped the design

- **Java 21**, Maven, JUnit 5, no framework. Engine has zero runtime dependencies; the agent adds only Jackson.
- **One writer at a time**: all `Database` methods are `synchronized`.
- **Everything fits a teaching scale**: 4 KiB pages, a 256-page default cache (1 MiB), records up to 4088 bytes.
- **No secrets in the repo**: API keys come from environment variables and are masked in `toString`/errors.

## Success criteria

A reader can (a) build and test with one command, (b) type SQL into the CLI and get correct results, (c) point the CLI at any OpenAI-compatible endpoint and ask questions, (d) run `--eval` and read a pass/fail report, and (e) rebuild each layer from the build guide. What is *measured* is limited to tests passing (217 at the time of writing: 162 engine, 55 agent). Real-model accuracy is not measured.
