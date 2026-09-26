# Glossary

| Term | Meaning here |
|---|---|
| **Agent** | A program that lets an LLM decide which tools to call, runs them, and returns results to the model until it answers. Here: `AnalystAgent`. |
| **AST** | Abstract syntax tree: the parsed form of a SQL statement or expression (`Statement`, `Expr`). |
| **Accumulator** | Running state of an aggregate function (count, sum, ...) over one group. |
| **Aggregate** | A function over many rows: `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`. |
| **Atomic move** | Replacing a file in one indivisible step (used for `catalog.bin`). |
| **Bitmap (null bitmap)** | A byte array with one bit per column marking NULLs in an encoded row. |
| **Blocking operator** | An operator that must consume all input before producing output (sort, aggregate, join build side). |
| **Buffer pool** | In-memory cache of pages with LRU eviction and pinning. |
| **Catalog** | Metadata about tables (names and schemas). |
| **Compaction** | Sliding records together inside a page to merge scattered free space. |
| **Dirty page** | A cached page modified in memory but not yet written to disk. |
| **Deadline / tick** | The wall-clock limit of a query and the cheap counter that periodically checks it. |
| **Equi-join** | A join whose condition contains `a.x = b.y`. Eligible for hash join. |
| **Execution accuracy** | Evaluation metric: do two queries return the same values? |
| **Frame** | A buffer-pool slot holding one page, with pin count and dirty flag. |
| **Gold SQL** | The reference query defining the correct answer for an evaluation question. |
| **Hash join** | Join that builds a hash table on one input and probes it with the other. |
| **Heap file** | Unordered collection of records over many pages. |
| **Iterator model (Volcano)** | Operators expose `next()` and pull rows from children. |
| **LRU** | Least recently used; the eviction policy of the buffer pool. |
| **Materialize** | Hold rows in memory (counted against `maxMaterializedRows`). |
| **Nested-loop join** | Compare every left row with every right row. |
| **Pin** | A hold on a cached page that prevents eviction while code uses its bytes. |
| **Plan / PlanNode** | The operator tree built from a `SELECT`. |
| **Prompt injection** | Text (in user input or data) crafted to make a model ignore its instructions. |
| **Read-only mode** | `Database.query`: only `SELECT`/`EXPLAIN` allowed, checked on the parsed statement. |
| **RecordId (RID)** | `(pageId, slot)`, the address of a record in a heap file. |
| **Reactor** | Maven's multi-module build; modules resolve each other without installing. |
| **Retryable error** | A model failure that may succeed if repeated (rate limit, outage). |
| **Row cap** | `QueryOptions.maxRows`: maximum rows returned; excess is reported as `truncated`. |
| **Scripted model** | A test double for `ChatModel` that replays predefined responses. |
| **Sealed interface** | A Java type with a fixed set of implementations, enabling exhaustive `switch`. |
| **Slot** | An entry in a slotted page's directory pointing to one record. |
| **Slotted page** | Page layout with a slot directory at the front and records packed from the back. |
| **Three-valued logic** | Boolean logic with `TRUE`, `FALSE`, `UNKNOWN` (`NULL`). |
| **Tool** | A function the model may request (`run_query`, ...), executed by our code with validation and limits. |
| **Tool call** | The model's request to run a tool with arguments; must be answered with a tool-result message. |
| **Trace** | The list of `TraceEvent`s recording each model call and tool call of a run. |
| **Trust boundary** | A line across which data must be validated (model output, tool args, DB data, files). |
| **Untrusted data label** | The `RESULT (untrusted data ...)` prefix on database text sent to the model. |
| **WAL (write-ahead log)** | `wal.log`: before and after images of changed pages, forced to disk at commit and before an uncommitted page is written, so crashes can be recovered. |
| **Transaction** | A group of changes that are all kept (`COMMIT`) or all discarded (`ROLLBACK`). Every write statement is one automatically. |
| **Steal / no-force** | Steal: uncommitted pages may be written to data files. No-force: commit does not write data pages, only the log. |
| **Before-image / after-image** | A page's bytes before / after a transaction changed it; undo restores the first, redo the second. |
| **Redo / undo** | Recovery re-applies committed changes and reverts unfinished ones. |
| **Checkpoint** | Write every dirty page, sync, and empty the log. |
| **Recovery** | `Recovery.recover` on open: redo, undo, sync, empty log. |
| **Torn write / torn tail** | A write cut off by a crash; the log reader ignores a partial or corrupt last frame. |
| **B+ tree** | A balanced tree whose leaves hold all entries and are linked for range scans; the index structure used here. |
| **Index scan** | Reading only a key range of an index, then fetching those rows; `IndexScan` in `EXPLAIN`. |
| **Rule-based planner** | Chooses by fixed rules (use an index if one fits), not by estimated cost from statistics. |
| **Oracle test** | A test that compares a fast implementation with a slow, obviously correct one on random input. |
