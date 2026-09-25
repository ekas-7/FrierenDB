# LLD 08 - Index Internals

Package `dev.frierendb.engine.index` (`BPlusTree`, `KeyCodec`), plus `catalog` (`IndexInfo`, `TableIndex`, `Table`, `Catalog`) and `exec` (`IndexSelection`, `PlanNodes.IndexScan`). Byte layouts are in [`03-on-disk-formats.md`](03-on-disk-formats.md).

## Entries and ordering

An index entry is `(key: Value, rid: RecordId)`. Entries are ordered by key (`Value.compareTo`), then by `RecordId` (page, then slot). Because the record id is part of the order:

- duplicate keys are separate, distinct entries;
- deleting one row's entry never touches another row with the same key (`delete(key, rid)` finds exactly one entry);
- the tree can be searched for "the first entry with this key" by descending with the lowest possible record id (`LOWEST = (MIN_VALUE, MIN_VALUE)`).

`NULL` is not indexed. `Table` simply does not call the tree for a `NULL` key.

## Public API of `BPlusTree`

| Method | Behaviour |
|---|---|
| `open(file, pool, keyType)` | Empty file: allocates page 0 (meta) and page 1 (empty root leaf). Otherwise verifies magic, version and key type |
| `insert(key, rid)` | Returns whether an entry was added (re-inserting the same `(key, rid)` is a no-op) |
| `delete(key, rid)` | Returns whether an entry was removed |
| `find(key)` | All record ids with that key, in record-id order |
| `range(lo, loInclusive, hi, hiInclusive)` | Iterator over entries in order; a `null` bound is unbounded; reads one leaf at a time |
| `size()`, `height()` | Entry count (kept in the meta page) and number of levels |
| `checkKey(key)` | Throws if `key` could not be stored (type mismatch, TEXT over 255 bytes); `NULL` passes |
| `checkInvariants()` | Test hook: order, separators, equal leaf depth, leaf chain, entry count |

The tree keeps no state besides page 0 and the pages themselves. That is why a rollback needs no index-side cache reload: restoring the pages restores the tree.

## Insert

1. Descend from the root. In an internal node, pick the child `i` such that the new entry is `>=` separator `i-1` and `<` separator `i`.
2. In the leaf, binary-search for the position and insert.
3. If the page image no longer fits in 4096 bytes, **split by bytes**: move entries to a new right sibling until about half of the bytes are on each side. The right sibling takes over the old leaf's `next` link; the old leaf points to it.
4. The split returns `Split(separator, rightPage)`. The separator is the first entry of the new right node (a full `(key, rid)` entry, not only a key).
5. The parent inserts the separator and child pointer. If it overflows it splits likewise, and the middle separator moves up instead of being copied.
6. If the root splits, a new internal root is allocated and the meta page is updated with the new root id.

Nodes are decoded to an in-memory list, modified, and re-encoded into the page whole. This costs CPU per operation but keeps every structural rule in one place and makes splits by byte size simple.

## Delete

Find the leaf, remove the entry, rewrite the page, decrement `size`. **No merging or redistribution.** A tree that lost most of its entries keeps its pages and height. Empty leaves stay in the chain and are skipped by scans. This is a deliberate simplification; the cost is wasted space, not wrong answers.

## Range scan

`range` positions the iterator in the first leaf that can hold `lo` (descending with `LOWEST` for an inclusive bound), then walks the `next` links. Each step fetches one leaf, decodes it and unpins it, so a scan never pins more than one page. It stops at the first entry beyond `hi`. Entries equal to an exclusive bound are skipped.

## Key limits

`KeyCodec.MAX_TEXT_BYTES = 255`. With keys that small a leaf always holds many entries, so a split always produces two valid pages. Longer keys are rejected with `StorageException("Index key is N bytes; TEXT index keys are limited to 255 bytes")`.

## How tables keep indexes in step

`Table` owns a list of `TableIndex` (index definition + tree + file):

| Table operation | Index work |
|---|---|
| `insert(row)` | for each index: `tree.insert(row[col], newRid)` unless the key is `NULL` |
| `delete(rid)` | read the old row (only if the table has indexes), delete it from the heap, then `tree.delete(oldKey, rid)` for each index |
| `update(rid, row)` | `checkIndexable`, then the heap update (which may relocate the row), then for each index: if neither the key nor the record id changed do nothing, otherwise remove the old entry and add the new one |
| `checkIndexable(row)` | throws if any index would reject the row's key; called by `Executor` for **all** prepared rows before the first write |
| `indexScan(name, lo, loInc, hi, hiInc)` | iterates the tree, fetches each row from the heap |

## Catalog changes

- `catalog.bin` version 2 stores per table: index count, then `(index name, column name)` pairs. Version 1 files still load (zero indexes).
- Index file name: `<table>.<index>.idx`. Names are normalised (lower case, validated like table names) and are unique **across tables**.
- `createIndex`: build the complete file in the table's pool first (scan the heap, insert every non-`NULL` key), delete any stale file with that name before starting, register the index, save the catalog. On any failure the new file is removed and the catalog is unchanged.
- `dropIndex`: detach, close, save the catalog, then delete the file.
- `dropTable`: delete the table's index files too; the catalog is saved **before** files are deleted.
- `createTable` deletes a stale file of the same name left by an earlier crash.

## Planner: `IndexSelection`

Input: the `Table`, the alias the query uses for it, and the compiled `WHERE` expression. Output: `Optional<Choice(index, lo, loInclusive, hi, hiInclusive, condition)>`.

1. Split the `WHERE` on top-level `AND` into conjuncts.
2. A conjunct is *usable* when it is `column op constant` or `constant op column` (the operator is flipped), with `op` one of `= < <= > >=`, the column belongs to the table (qualified by its alias or unqualified) and has an index. A *constant* is an expression with no column references that evaluates against an **empty** `RowSchema`; if evaluation throws, or the value is `NULL`, the conjunct is skipped.
3. The constant must have the column's type. The only conversion is `INT` constant to `DOUBLE` column, allowed only for `|n| <= 2^53`. Anything else (including a `DOUBLE` constant against an `INT` column) is skipped and left to the filter.
4. Conjuncts for the same index are merged into one range: the largest lower bound and smallest upper bound win; an exclusive bound beats an inclusive one at the same value; the first equality wins.
5. Score: equality = 3, otherwise one point per bound. The highest score wins; on a tie the column whose conjunct appears first in the `WHERE` wins. If one column has several indexes, the first index defined for it is the one considered.

`Planner` builds all scans first, compiles the `WHERE` against the combined schema (so errors such as unknown columns are reported before any decision), then applies `IndexSelection` to the `FROM` table only, and finally builds joins. The `Filter` above the scan keeps the **entire** `WHERE`.

`EXPLAIN` prints `IndexScan <table> [AS <alias>] using <index> (<condition>)`. `UPDATE` and `DELETE` call `IndexSelection.choose` through `Executor.matching`, so they use the index to find rows too.

## Tests that guard this

| Test class | What it proves |
|---|---|
| `BPlusTreeTest` | Ordered iteration, splits, duplicates, delete, persistence across reopen, all key types, invariants, random operations against a `TreeMap` oracle, key-size limit |
| `CatalogIndexTest` | Create/drop, building from existing rows, persistence, version-1 catalogs, name rules, failure cleanup |
| `SqlIndexDdlTest` | `CREATE`/`DROP INDEX` statements, errors, effect on `DROP TABLE`, `NULL` keys, oversized keys fail without writing |
| `SqlIndexPlanTest` | Plan text; fallbacks; `LEFT JOIN`; random predicates compared with a no-index oracle; a 120-step write workload with checks after every step |

A deliberate blind spot: widening an index range is invisible to result-equality tests (the `Filter` hides it), so only the plan-text assertions catch an over-wide range. Narrowing it, which would lose rows, is caught by the oracle tests.
