# 00 - How to Follow This Guide

The guide rebuilds FrierenDB layer by layer, bottom up. Each step ends in something you can run and test before moving on. Do not skip ahead: every layer depends on the ones below.

## The loop for every step

1. **Read** the step to learn the goal and the design.
2. **Write the tests first** (or at least the list of behaviours) from the "Tests to write" section. A failing test is the specification.
3. **Implement** until the tests pass.
4. **Run the whole build** (`mvn clean test` from `frierendb/`). A step is done only when everything is green, not just the new tests.
5. **Commit** with a message that says what and why (examples are given per step).
6. **Update** `docs/implementation-progress.md` with what you finished and what you learned.

## Rules we followed

- One layer per commit (or a few small commits); never mix a refactor with a feature.
- No test is ever disabled, and no lint is turned off to make a build pass. Fix the cause.
- Prefer small immutable types (records, sealed interfaces) and `final` classes.
- Validate at boundaries (file contents, SQL text, tool arguments, model output) and trust nothing that crosses them.
- Throw one clear, specific exception type per layer with a message a human can act on.
- Keep source comments to a minimum; write the reasoning in docs.

## Map of steps to commits (as built)

| Step | Layer | Suggested commit message |
|---|---|---|
| 1 | Maven skeleton | `build: initialize Java 21 Maven project` |
| 2-3 | Values, schema, row codec | `feat: add value types, schema and binary row codec` |
| 4-7 | Storage | `feat: add page file, buffer pool, slotted pages and heap file` |
| 8 | Catalog | `feat: add persistent catalog and table abstraction` |
| 9-10 | SQL front end | `feat: add SQL lexer, sealed AST and recursive-descent parser` |
| 11-15 | Execution | `feat: add SQL planner, executor and read-only query mode with resource limits` |
| 16-18 | Agent | `feat: add natural-language analyst agent with read-only tools` |
| 19 | CLI, demo, eval | `feat: add interactive CLI, demo dataset and evaluation harness` |
| 20-21 | B+ tree indexes, `CREATE INDEX`, index-aware planning | `feat(index): add a persistent B+ tree`, then catalog, SQL and planner commits |
| 22-23 | Write-ahead log, transactions, recovery | `feat(storage): add write-ahead-logged transactions to BufferPool`, `feat(storage): add crash recovery from the write-ahead log`, `feat: add transactions and crash recovery to Database` |

Steps 1-19 build the system in the order it was first written. Steps 20-23 were added afterwards and change files from steps 5, 7, 8, 10, 12 and 14 (each of those files has a note saying what changes). If you are rebuilding from scratch you may fold the changes into the earlier steps as you go, but keep the same tests.

## What you need

- A JDK 21 (see step 1), Maven 3.9+, a terminal, and an editor.
- Basic Java and some familiarity with files, byte buffers and SQL.

## How the steps are laid out

Each step has the same sections:

- **Goal** and what exists afterwards
- **Concepts** you need
- **Design** (decisions and invariants)
- **Build it** (ordered tasks and the important code)
- **Pitfalls** we hit or avoided
- **Tests to write**
- **Checkpoint** (how you know you are done)

## Verification habit

After each step, run from the `frierendb/` directory:

```bash
export JAVA_HOME=/path/to/jdk-21
mvn clean test
```

`clean` matters: stale class files can hide errors, and the agent module resolves the engine through the reactor, so a clean reactor build is the honest check.

## When you get stuck

- Read the matching class in [`../lld/01-engine-class-reference.md`](../lld/01-engine-class-reference.md).
- Check byte layouts in [`../lld/03-on-disk-formats.md`](../lld/03-on-disk-formats.md).
- See [`../guides/troubleshooting.md`](../guides/troubleshooting.md) for build issues.
