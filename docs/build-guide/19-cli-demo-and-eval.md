# Step 19 - CLI, Demo Data and Evaluation

Packages: `dev.frierendb.agent.cli` (`Main`, `Repl`, `ResultPrinter`), `demo` (`DemoData`), `eval` (`EvalCase`, `EvalRunner`, `EvalReport`). Resources: `demo/university.sql`, `eval/university-questions.json`.

## Goal

A usable command-line tool, a sample dataset, and a repeatable way to measure whether an agent answers questions correctly.

## Part A: `Repl`

```java
public Repl(Database db, AnalystAgent agent, String agentUnavailableReason, PrintStream out)
public void run(BufferedReader in, boolean interactive)
```

- Reads lines; blank lines are skipped; `quit`, `exit`, `\q` end the session; end of input also ends it.
- Commands: `\help`, `\tables` (prints `name(col TYPE, ...)` per table), `ask <question>`, anything else is **SQL typed by the human** and runs through `db.execute` (full privileges).
- Output of SQL goes through `ResultPrinter.format`: column-aligned table, `NULL` shown for nulls, cells cut at 60 characters, footer `(N rows)` (+ `, truncated`). Writes print `N row(s) affected`.
- `ask` prints the trace lines (`[tool] run_query ok (3 ms) {"sql":"..."}`), a `Run stopped: <STATUS>` line if not answered, then the answer. With no agent configured it prints the configuration help instead.
- Errors: `FrierenException` → `Error: <message>`; any other `RuntimeException` → `Internal error: <class>: <message>`; the loop continues either way.
- `Repl` takes its streams as parameters so it is fully testable (no `System.in` in the class).

## Part B: `Main`

```
Usage: frierendb [--db <dir>] [--demo] [--eval]
```

- `--db` default `./frieren-data`; `--demo` loads the sample data if the database has no tables; `--eval` runs the evaluation (implies `--demo`).
- The model is configured from the environment:

| Variable | Meaning |
|---|---|
| `FRIEREN_LLM_MODEL` | required to enable `ask` |
| `FRIEREN_LLM_BASE_URL` | default `https://api.openai.com/v1`; for Ollama use `http://localhost:11434/v1` |
| `FRIEREN_LLM_API_KEY` | optional (omit for local servers) |

- Without a model, SQL still works and `ask` explains how to configure one.
- Argument errors print a message to stderr and exit with status 2.
- Interactive prompt (`frieren> `) appears only when `System.console() != null`, so piping input works cleanly.

## Part C: Demo data

`DemoData.seedIfEmpty(db)` runs `/demo/university.sql` via `executeScript` **only if the database has no tables** and returns whether it did. The script (hand-written for this project) creates and fills:

| Table | Columns | Rows |
|---|---|---|
| `students` | `sid INT NOT NULL, name TEXT NOT NULL, major TEXT, gpa DOUBLE` | 20 (2 NULL GPAs, 1 NULL major) |
| `courses` | `cid INT NOT NULL, title TEXT NOT NULL, dept TEXT NOT NULL, credits INT NOT NULL` | 8 |
| `enrollments` | `sid INT NOT NULL, cid INT NOT NULL, term TEXT NOT NULL, grade DOUBLE` | 36 (2 NULL grades; 2 students never enrolled) |

The data was designed so every evaluation question has an unambiguous answer (for example, exactly one course has the most enrollments).

## Part D: Evaluation

### Question file

`eval/university-questions.json`: a list of `{ "question": "...", "goldSql": "..." }` (12 entries). The gold SQL is the definition of "correct".

### `EvalRunner(AnalystAgent, Database).run(List<EvalCase>)`

For each case:

1. Run `goldSql` with `QueryOptions.unlimited()`. If it fails, the case fails with "reference query is broken".
2. `agent.ask(question)`. If the status is not `ANSWERED`, fail with the status and text.
3. Take the **last** entry of `run.successfulQueries()`; none means "answered without running a successful query" (fail).
4. Run that SQL unlimited and normalise both results:
   - every value → text; `INT` and `DOUBLE` both become a number with 4 decimals (so `10` equals `10.0`);
   - NULL → `NULL`;
   - rows are **sorted** unless the gold SQL contains `ORDER BY` (then row order must match).
5. Pass if the normalised results are equal; otherwise report expected vs actual and the agent's SQL.

`EvalReport.summary()` prints `PASS`/`FAIL` per question and a final `N/12 correct` line.

### What this does and does not prove

- It measures whether the agent's **query** returns the right values, not the quality of the final prose.
- The tests validate the *harness* with scripted models: a model that replays the gold SQL scores 12/12; a model that always runs a wrong query or never queries scores 0. **They say nothing about a real LLM.** To measure one, run `--eval` with a configured model and record the model name, date and score.

## Build it

1. `ResultPrinter` first (pure function over `QueryResult`).
2. `Repl` with unit tests using `ByteArrayOutputStream` and a `StringReader`.
3. `DemoData` and the SQL file (verify with a test that seeding is idempotent).
4. `EvalRunner` etc., then the JSON file; add a test asserting every gold query runs and returns rows.
5. `Main` last.

## Pitfalls

- **Seed data should not depend on the CLI**: tests call `DemoData.seedIfEmpty` directly.
- **Ambiguous questions make evals meaningless**: ties in "top N" questions or NULL-handling ambiguity ("each major" - include the NULL group?) must be resolved in the question text (we wrote "Ignore students without a major").
- **Do not let `--eval` run on a database with other data**; the questions refer to the demo tables.
- **Never print the API key**; `Main` only passes it to `OpenAiCompatibleModel.Config`.
- A Markdown/README claim of accuracy must come from an actual `--eval` run, not from the scripted-model tests.

## Tests to write

`ReplTest` (7): aligned table output; human SQL may write and reports affected rows; errors are reported and the session continues; `\tables`; `ask` without a model explains configuration; `ask` shows the trace and answer; `\q` stops reading. `DemoDataTest` (1): seeds once and survives reopen. `EvalRunnerTest` (8): gold queries run and return rows; known facts about the data (20 students, 2 without GPA, most-enrolled course, top GPA); perfect model scores 100%; a wrong guesser scores 0; answering without querying fails; row order matters only when the gold query orders; numeric types compare by value; a broken gold query is reported, not thrown.

## Checkpoint

Run the CLI with `--demo`, type `SELECT COUNT(*) FROM students` (20), `\tables`, and `ask hello` (prints the configuration help if no model is set).

Commit: `feat: add interactive CLI, demo dataset and execution-accuracy evaluation harness`.
