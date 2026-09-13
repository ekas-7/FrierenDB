# Step 8 - Catalog and Table

Package: `dev.frierendb.engine.catalog`. Classes: `Catalog`, `Table`, `CatalogException`.

## Goal

Named tables that survive restarts: remember each table's schema, and give every table a typed row API on top of the heap file.

## Concepts

- **Catalog (system catalog)**: metadata about tables. Ours is a single binary file.
- **Atomic replace**: write a new file next to the old one, then rename it over the old one in one step, so readers never see a half-written file.
- **Path traversal**: a user-supplied name like `../x` becoming a file path. Prevent by validating names, not by escaping.

## Design

### `Table`

- Created only by `Catalog` (package-private constructor), which supplies the schema, the file path and the shared `BufferPool`.
- Holds a `PageFile` and a `HeapFile`.
- Row API: `insert(Row)`, `get(RecordId)`, `delete(RecordId)`, `update(RecordId, Row)` (returns the possibly new id), `scan()` returning `Iterator<RowEntry(RecordId id, Row row)>`.
- Encoding and decoding go through `RowCodec` with the table's schema; so `insert` validates rows (`Schema.conform` is called inside `encode`).
- `flush()` → `heap.flush()`; `close()` → `pool.evictAll(file)` then `file.close()`.

### `Catalog`

- Directory layout: `catalog.bin` plus `<table>.tbl`.
- In memory: `TreeMap<String, Table>` keyed by **lower-case** name (sorted, so `tableNames()` is stable).
- **Name validation**: `[A-Za-z_][A-Za-z0-9_]{0,63}`. Because names become file names, this is a security control, not just style.
- Operations: `createTable(name, schema)`, `dropTable(name)`, `table(name)`, `exists(name)`, `tableNames()`, `flush()`, `close()`. All synchronized.
- `catalog.bin` format (big-endian, written with `DataOutputStream`):

```
int   magic   0x46524E43  ("FRNC")
int   version 1
int   tableCount
repeat tableCount:
  UTF   table name
  int   column count
  repeat column count:
    UTF     column name
    UTF     type name (enum name, e.g. "INT")
    boolean nullable
```

- **Saving**: write to `catalog.bin.tmp`, then `Files.move(temp, catalog.bin, ATOMIC_MOVE, REPLACE_EXISTING)`. Called after every create/drop.
- **Loading** (on `open`): check magic and version; for each table, require that `<table>.tbl` exists, else `CatalogException("Data file missing ...")`; any I/O or enum problem becomes `CatalogException("Cannot read catalog ...")`.
- **Drop**: remove from the map, close the table, delete the data file, save the catalog.

## Build it

1. `CatalogException extends FrierenException` (with `serialVersionUID`).
2. `Table` as above. Its `scan()` wraps the heap's iterator and decodes lazily.
3. `Catalog.open(dir, pool)`: `Files.createDirectories(dir)`, then `load` if `catalog.bin` exists.
4. `normalize(name)`: validate with a compiled `Pattern`; return `name.toLowerCase(Locale.ROOT)`.
5. `createTable`: reject duplicates; construct `Table(key, schema, tablePath(key), pool)`; put; `save()`.
6. `save()` as described. Use `DataOutputStream.writeUTF` for strings.
7. `close()`: `flush()` all tables, `close()` all, clear the map.

## Pitfalls

- **Order of drop**: close the table (releases the file handle and evicts cached pages) *before* deleting the file, or deleting fails on some platforms and stale pages stay cached.
- **Atomic move can fail across file systems**; keep the temp file in the same directory (we do).
- **Case**: table names are stored lower-case, column names keep their case but are looked up case-insensitively. Be consistent: `table("Students")` must find `students`.
- `writeUTF` has a 64 KiB limit per string; irrelevant for 64-character names but keep in mind for column names (validated by `Schema` as non-blank only).
- The catalog does not validate column name syntax; the SQL layer controls what reaches it.

## Tests to write (`CatalogTest`)

A created table stores and scans rows; lookup is case-insensitive; schema and rows survive reopen; a duplicate table is rejected; an unknown table is rejected; dropping removes both data and definition; **table names cannot escape the directory** (`../x`, names with slashes, empty); a missing data file is reported on open; a corrupt catalog (bad magic) is reported on open; rows violating the schema are rejected; update and delete work through the table.

## Checkpoint

Create a table, insert rows, close the catalog, open a new catalog on the same directory: the rows are there.

Commit: `feat: add persistent catalog and table abstraction`.
