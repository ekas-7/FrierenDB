# FrierenDB Documentation

These documents explain FrierenDB well enough that you could rebuild it by hand, without an AI assistant, in the same order it was built. Every file covers one aspect and is meant to be read in a few minutes. Every claim was checked against the code in `frierendb/`; where something is *not* built, the text says so.

## Reading paths

| If you want to... | Read |
|---|---|
| Understand what this is and how the parts fit | [`hld/`](hld/) in order: 01 → 06 |
| Rebuild it from an empty directory | [`build-guide/`](build-guide/) in order: 00 → 19 |
| Look up a class, a byte layout, the grammar or an error | [`lld/`](lld/) |
| Test it, extend it, debug the build | [`guides/`](guides/) |

## High-level design (HLD)

1. [`hld/01-problem-and-scope.md`](hld/01-problem-and-scope.md): the problem, goals, non-goals
2. [`hld/02-system-architecture.md`](hld/02-system-architecture.md): modules, layers, component diagram
3. [`hld/03-runtime-flows.md`](hld/03-runtime-flows.md): what happens on `SELECT`, `INSERT`, `ask`
4. [`hld/04-design-decisions.md`](hld/04-design-decisions.md): the important choices and their trade-offs
5. [`hld/05-safety-model.md`](hld/05-safety-model.md): threat model for the AI agent
6. [`hld/06-limitations-and-roadmap.md`](hld/06-limitations-and-roadmap.md): what is missing and how it would be added

## Build guide (step by step)

| Step | File | You build |
|---|---|---|
| 0 | [`00-how-to-follow-this-guide.md`](build-guide/00-how-to-follow-this-guide.md) | Working rules, checkpoints |
| 1 | [`01-environment-and-maven.md`](build-guide/01-environment-and-maven.md) | JDK 21, multi-module Maven, strict compiler |
| 2 | [`02-types-and-values.md`](build-guide/02-types-and-values.md) | `DataType`, sealed `Value`, ordering |
| 3 | [`03-schema-and-row-codec.md`](build-guide/03-schema-and-row-codec.md) | `Schema`, `Row`, binary row format |
| 4 | [`04-page-file.md`](build-guide/04-page-file.md) | Fixed-size page I/O |
| 5 | [`05-buffer-pool.md`](build-guide/05-buffer-pool.md) | LRU cache with pinning |
| 6 | [`06-slotted-page.md`](build-guide/06-slotted-page.md) | Variable-length records in a page |
| 7 | [`07-heap-file.md`](build-guide/07-heap-file.md) | Unordered record collection |
| 8 | [`08-catalog-and-table.md`](build-guide/08-catalog-and-table.md) | Persistent table registry |
| 9 | [`09-lexer.md`](build-guide/09-lexer.md) | SQL tokenizer |
| 10 | [`10-ast-and-parser.md`](build-guide/10-ast-and-parser.md) | Sealed AST, recursive-descent parser |
| 11 | [`11-expression-semantics.md`](build-guide/11-expression-semantics.md) | NULL logic, arithmetic, `LIKE` |
| 12 | [`12-planner.md`](build-guide/12-planner.md) | AST to operator tree |
| 13 | [`13-operators.md`](build-guide/13-operators.md) | Scan, filter, join, aggregate, sort, … |
| 14 | [`14-executor-and-database.md`](build-guide/14-executor-and-database.md) | Statements, writes, `Database` facade |
| 15 | [`15-query-limits-and-read-only.md`](build-guide/15-query-limits-and-read-only.md) | Time/row/memory caps, read-only mode |
| 16 | [`16-agent-model-layer.md`](build-guide/16-agent-model-layer.md) | `ChatModel`, OpenAI-compatible client |
| 17 | [`17-agent-tools.md`](build-guide/17-agent-tools.md) | The four read-only tools |
| 18 | [`18-agent-loop.md`](build-guide/18-agent-loop.md) | Bounded plan-act-observe loop |
| 19 | [`19-cli-demo-and-eval.md`](build-guide/19-cli-demo-and-eval.md) | REPL, demo data, evaluation harness |

## Low-level design (LLD) and reference

- [`lld/01-engine-class-reference.md`](lld/01-engine-class-reference.md): every engine class and its job
- [`lld/02-agent-class-reference.md`](lld/02-agent-class-reference.md): every agent class and its job
- [`lld/03-on-disk-formats.md`](lld/03-on-disk-formats.md): directory layout, page, record and catalog bytes
- [`lld/04-sql-grammar.md`](lld/04-sql-grammar.md): the grammar and operator precedence
- [`lld/05-error-model.md`](lld/05-error-model.md): exception hierarchy, who throws what
- [`lld/06-explain-examples.md`](lld/06-explain-examples.md): real `EXPLAIN` output, explained
- [`lld/07-agent-protocol.md`](lld/07-agent-protocol.md): conversation shape and wire format

## Guides

- [`guides/testing-strategy.md`](guides/testing-strategy.md)
- [`guides/extending.md`](guides/extending.md): recipes for new functions, tools, providers
- [`guides/troubleshooting.md`](guides/troubleshooting.md)
- [`guides/glossary.md`](guides/glossary.md)

## Other files

- [`implementation-progress.md`](implementation-progress.md): milestone log and known limitations
- `assets/`: images used by the README

## Conventions used in these docs

- Paths are relative to the repository root. Java packages live under `frierendb/<module>/src/main/java/dev/frierendb/<module>/`.
- "Engine" means the `engine` Maven module; "agent" means the `agent` module.
- Code blocks are excerpts of the real code unless labelled *sketch*. Source files carry almost no comments on purpose; the explanation lives here.
- Numbers (page size, limits, defaults) are quoted from the source. If the source changes, update the doc in the same commit.
