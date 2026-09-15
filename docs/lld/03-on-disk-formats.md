# LLD 03 - On-Disk Formats

All multi-byte integers are **big-endian** (the Java `ByteBuffer`/`DataOutputStream` default).

## Directory

```
<db-dir>/
  catalog.bin        table definitions
  catalog.bin.tmp    only during a catalog save (atomically moved over catalog.bin)
  <table>.tbl        one heap file per table; <table> is lower-case [a-z0-9_]
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
int      version      1
int      table count
per table:
  UTF    table name           (DataOutputStream.writeUTF: 2-byte length + modified UTF-8)
  int    column count
  per column:
    UTF      column name
    UTF      type name        ("INT" | "DOUBLE" | "TEXT" | "BOOLEAN")
    boolean  nullable         (1 byte)
```

- Written to `catalog.bin.tmp` then moved with `ATOMIC_MOVE | REPLACE_EXISTING`, so a crash leaves either the old or the new file.
- Loading fails with `CatalogException` on a wrong magic or version, a missing `<table>.tbl`, or any unreadable content.
- The catalog stores **definitions only**. Row counts and statistics are not stored.

## Compatibility

There is no migration tooling. A format change must bump `VERSION` (catalog) and be rejected or converted explicitly. Page format has no version field; adding one (for example in a reserved header byte) would be the first step of any storage-format change.

## What is *not* on disk

- No write-ahead log, no free-space map (rebuilt on open by scanning pages), no indexes, no statistics.
