# LLD 03 - On-Disk Formats

All multi-byte integers are **big-endian** (the Java `ByteBuffer`/`DataOutputStream` default).

## Directory

```
<db-dir>/
  catalog.bin        table and index definitions
  catalog.bin.tmp    only during a catalog save (atomically moved over catalog.bin)
  <table>.tbl        one heap file per table; <table> is lower-case [a-z0-9_]
  <table>.<index>.idx  one B+ tree file per index (see below)
  wal.log            write-ahead log (see below); 8 bytes when empty
```

## `<table>.tbl`: a sequence of pages

- File length is always a multiple of 4096. Page `n` starts at byte `n * 4096`.
- No file header, no page header beyond the slotted-page header below, no checksums.
- An all-zero page is a valid empty page layout only after `SlottedPage.format` has been applied (zero slot count and record start 0 would be misread). The heap file always formats a page when it allocates one.

### Slotted page

```
offset  size  field
0       2     slot count (unsigned)
2       2     record-area start (unsigned; 4096 when empty)
4       4*n   slot directory: for slot i at 4 + 4*i:  offset (2), length (2)
...           free space
start   ...   records, packed toward the end of the page
4096          end
```

- Slot offset `0` means **free slot** (length also 0).
- Record bytes are placed at `recordStart - length`; the record start moves down.
- Deleting only zeroes the slot. Space is reclaimed by compaction on demand.
- Limits: `MAX_RECORD_SIZE = 4096 - 4 - 4 = 4088`.

#### Worked example (real output from the code)

Format an empty page, insert a 5-byte record `01 02 03 04 05`, then a 3-byte record `09 09 09`:

```
header + first 2 slots:  00 02 | 0f f8 | 0f fb 00 05 | 0f f8 00 03
                         slots=2  start=4088  slot0=(4091,5)  slot1=(4088,3)
last 8 bytes of page:    09 09 09 01 02 03 04 05      (bytes 4088..4095)
```

Delete slot 0:

```
00 02 0f f8 | 00 00 00 00 | 0f f8 00 03        slot0 now free (0,0); freeBytes() = 4081
```

(4081 = 4096 − 4 header − 2×4 slots − 3 live bytes.)

### Record (row) encoding

```
[ null bitmap: ceil(columns/8) bytes ][ non-null values in column order ]
```

| Type | Bytes |
|---|---|
| `INT` | 8, signed |
| `DOUBLE` | 8, IEEE-754 |
| `BOOLEAN` | 1 (0 or 1) |
| `TEXT` | 4-byte length `L`, then `L` bytes of UTF-8 |

Bitmap bit for column `i` is bit `i % 8` of byte `i / 8` (least significant bit first); 1 means NULL and no payload bytes follow for that column.

#### Worked example

Schema `(id INT NOT NULL, name TEXT, gpa DOUBLE)` and row `(1, 'Ada', NULL)`:

```
04                         bitmap: bit 2 set (gpa is NULL)
00 00 00 00 00 00 00 01    id = 1
00 00 00 03                name length = 3
41 64 61                   "Ada"
```

16 bytes total; there are no bytes for `gpa`.

Decoding rejects: a text length that is negative or larger than the remaining bytes, running out of bytes, and bytes left over after the last column.

## `catalog.bin`

```
int      magic        0x46524E43 ("FRNC")
int      version      2            (version 1 files, which have no index section, still load)
int      table count
per table:
  UTF    table name           (DataOutputStream.writeUTF: 2-byte length + modified UTF-8)
  int    column count
  per column:
    UTF      column name
    UTF      type name        ("INT" | "DOUBLE" | "TEXT" | "BOOLEAN")
    boolean  nullable         (1 byte)
  int    index count          (version 2 only)
  per index:
    UTF      index name
    UTF      column name
```

- Written to `catalog.bin.tmp` then moved with `ATOMIC_MOVE | REPLACE_EXISTING`, so a crash leaves either the old or the new file.
- Loading fails with `CatalogException` on a wrong magic, a version above 2, a missing `<table>.tbl` or index file, or any unreadable content.
- The catalog stores **definitions only**. Row counts and statistics are not stored.

## `<table>.<index>.idx`: a B+ tree

Pages are 4096 bytes. Page 0 is the meta page; page 1 is the first root (a leaf). Every other page is a leaf or an internal node.

```
Meta page (page 0)
offset  size  field
0       4     magic 0x46425054 ("FBPT")
4       2     version (1)
6       1     key type ordinal (0 INT, 1 DOUBLE, 2 TEXT, 3 BOOLEAN: the `DataType` enum order)
7       4     root page id
11      8     entry count

Node page
0       1     kind: 1 = leaf, 2 = internal
1       2     entry count n (unsigned)
3       4     leaf: next leaf page id (-1 = none)   internal: first child page id
7       ...   n entries, packed, rest of the page zero

Leaf entry:      key | page id (4) | slot (4)
Internal entry:  key | page id (4) | slot (4) | child page id (4)    (child holds entries >= this entry)
Key bytes:       INT 8 (signed long) | DOUBLE 8 | BOOLEAN 1 | TEXT 2-byte length + UTF-8 (at most 255 bytes)
```

- Entries are ordered by key, then page id, then slot. An internal node with `n` entries has `n + 1` children.
- `NULL` keys are never stored.
- Loading checks the magic, the version and that the key type matches the column; otherwise `StorageException`.

## `wal.log`: the write-ahead log

```
int   magic    0x4657414C ("FWAL")
int   version  1
frames, until the end of the file:
  int   payload length  (1 .. MAX_PAYLOAD)
  int   CRC-32 of the payload
  bytes payload

COMMIT / ABORT payload:   type (1: COMMIT = 2, ABORT = 3) | transaction id (8)
UPDATE payload (type 1):  type (1) | transaction id (8)
                          | file name length (2) | file name (UTF-8, 1..255 bytes, no path)
                          | page id (4) | has-before (1: 0 or 1)
                          | before image (4096, only if has-before = 1) | after image (4096)
```

- `has-before = 0` means the transaction allocated that page (the file grew); recovery undoes it by truncating the file to that page id.
- `MAX_PAYLOAD = 1 + 8 + 2 + 255 + 4 + 1 + 2 * 4096`.
- The valid log is the longest prefix of whole frames whose length, CRC and contents are valid. Reading stops at the first bad frame; the bytes after it are ignored and trimmed on open.
- A short file (under 8 bytes) is an empty log. A full header with the wrong magic or version is an **error**.
- The log is emptied (truncated to the 8-byte header) by a checkpoint, by recovery, and by a clean `close`.
- Transaction ids increase; after a restart the next id is one more than the largest id found in the log, or 1 if the log is empty.

## Compatibility

There is no migration tooling. A format change must bump the version of that file (`catalog.bin` is at 2 and still reads 1; `.idx` and `wal.log` are at 1 and reject anything else) and be rejected or converted explicitly. Heap pages have no version field; adding one (for example in a reserved header byte) would be the first step of any heap-format change.

## What is *not* on disk

- No free-space map (rebuilt on open by scanning heap pages), no statistics, no checksums on data or index pages (only the log has CRCs).
