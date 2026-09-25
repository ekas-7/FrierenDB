# Step 20 - The B+ Tree

Classes: `dev.frierendb.engine.index.BPlusTree` and `KeyCodec`.

Prerequisite: steps 4 and 5 (page file, buffer pool). Do this step after step 19 or any time after step 8; it does not touch SQL yet.

## Goal

A persistent, ordered map from one column's values to `RecordId`s that supports insert, delete, point lookup and range scan, using only `PageFile` and `BufferPool`.

## Concepts

- **B+ tree**: a balanced search tree stored in pages. *Internal* pages route a search; *leaf* pages hold the entries; leaves are linked left to right for range scans.
- **Non-unique index**: many rows can share a key. Making the **record id part of the sort order** turns every entry into a unique `(key, rid)` pair, so duplicates need no special code.
- **Split**: when a page overflows, cut it into two and push a separator up to the parent.
- **Separator**: here a full `(key, rid)` entry, so descending is a plain comparison.

## Design decisions

| Decision | Reason |
|---|---|
| Node pages are decoded to lists, changed, re-encoded whole | One place knows the layout; splits by byte size are simple |
| Split by bytes, not entry count | Entries have variable size (TEXT keys) |
| TEXT keys limited to 255 UTF-8 bytes | A leaf then always holds several entries, so a split always yields two valid pages |
| No merge or rebalance on delete | Much simpler; costs space only. Document it |
| `NULL` never stored | Comparisons with `NULL` are never true, so an index lookup could not return those rows anyway; the table skips them |
| The tree has no cache besides page 0 | A rollback that restores pages restores the tree with no extra work |

## Page layouts

Meta page (page 0): `magic 0x46425054 (4) | version 1 (2) | key type ordinal (1) | root page (4) | entry count (8)`.

Node page: `kind (1: 1 leaf, 2 internal) | count (2) | link (4) | entries...`
- leaf `link` = next leaf page (or -1); entry = `key | pageId (4) | slot (4)`
- internal `link` = first child; entry = `key | pageId (4) | slot (4) | child page (4)`; an internal node with `n` entries has `n + 1` children

Key bytes (`KeyCodec`): `INT` 8-byte long, `DOUBLE` 8-byte double, `BOOLEAN` 1 byte, `TEXT` 2-byte length + UTF-8.

## Build it

1. **`KeyCodec`**: `check(type, key)` (not NULL, right type, TEXT size), `size`, `write`, `read`.
2. **Entry**: a record `Entry(Value key, RecordId rid)` implementing `Comparable` by key, then page, then slot. Binary search on a `List<Entry>` gives insert positions.
3. **Open**: empty file → allocate page 0 and an empty root leaf (page 1), write meta. Non-empty → check magic, version and key type, else `StorageException`.
4. **Insert** (recursive): leaf → binary search; if the pair exists return false; insert; if it fits (`encodedSize <= 4096`) write it. Otherwise split at `splitPoint` (about half the bytes, never leaving a side with fewer than one entry), allocate the right node, link leaves, and return `Split(firstEntryOfRight, rightPage)`. Internal → pick the child with `childIndex` = the number of separators `<=` the entry, recurse, insert a returned split into this node, split this node too if it overflows (the middle entry moves *up*). A split of the root allocates a new root and rewrites the meta page.
5. **Delete**: descend with the exact entry, binary search the leaf, remove, write, decrement the count. No merging.
6. **Range**: `RangeIterator` starts at the leaf for `lo` (descend with the lowest possible record id for an inclusive bound), then reads one leaf at a time along `next`, skipping entries `< lo` (or `== lo` when exclusive) and stopping after `hi`.
7. **`checkInvariants()`** for tests: strict order inside nodes, every entry inside its separator range, all leaves at the same depth, leaf chain equals the in-order leaf list, entry count equals the meta count.

## Pitfalls

- Using the **key only** as the sort order makes deleting one of many equal keys ambiguous. Always include the record id.
- The separator pushed up from an *internal* split is **moved**, not copied; from a *leaf* split it is **copied** (the right leaf still contains it).
- A range iterator must not pin more than one page; a wide scan would otherwise exhaust a small pool.
- Re-encode with `Arrays.fill(page, 0)` first or a shrinking node leaves stale bytes.
- Do not write to the tree while a range iterator is open.

## Tests to write (`BPlusTreeTest`)

Empty tree; thousands of inserts in random order come back sorted and the height grows; duplicates and identical pairs; many duplicates of one key across leaves; inclusive and exclusive bounds; delete and `checkInvariants`; **random insert/delete/find/range against a `TreeMap` oracle**; long TEXT keys; every key type; rejects NULL, wrong type, oversize key; survives close and reopen; refuses a file built for another key type.

## Checkpoint

5,000 `INT` keys inserted ascending, descending and shuffled into a tree backed by a 16-page pool: `size()` is 5000, `height()` is at least 2, `checkInvariants()` passes, and `range(null, true, null, true)` returns all record ids in key order. Commit: `feat(index): add a persistent B+ tree`.
