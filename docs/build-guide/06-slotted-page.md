# Step 6 - Slotted Page

Class: `dev.frierendb.engine.storage.SlottedPage`.

## Goal

Store variable-length records inside one 4096-byte page, with insert, read, delete, in-place update and space reuse.

## Concepts

- **Slot directory**: an array at the front of the page; slot `i` stores where record `i` lives. A record is addressed as `(page, slot)`, which stays stable even when the record's bytes move inside the page.
- **Record area**: grows from the end of the page backwards.
- **Fragmentation**: deleting records leaves holes; **compaction** slides records together to recover contiguous space.

## Layout

```
byte 0..1   slot count         (unsigned 16 bit)
byte 2..3   record-area start  (unsigned 16 bit; offset of the lowest record byte)
byte 4..    slot directory     4 bytes per slot: offset (2 bytes), length (2 bytes)
   ...      free space
   ...      record bytes, growing downward from byte 4096
```

- A slot with **offset 0 is free** (a real record can never start at 0 because the header lives there).
- Constants: `HEADER_SIZE = 4`, `SLOT_SIZE = 4`, `MAX_RECORD_SIZE = 4096 - 4 - 4 = 4088`.
- 4096 fits in an unsigned 16-bit field, so the empty page's record start of 4096 is representable (read with `& 0xFFFF`).

## Design

| Method | Contract |
|---|---|
| `static format(byte[])` | Slot count 0, record start 4096 |
| `insert(byte[])` | Returns the slot used, or `-1` if it does not fit. Reuses the first free slot, else appends a slot. Empty or > 4088-byte records throw |
| `get(slot)` | Copy of the record; throws for out-of-range or empty slots |
| `delete(slot)` | Marks the slot free (offset 0, length 0); bytes are reclaimed lazily |
| `update(slot, bytes)` | In place if it fits within the old length; else relocates inside the page (keeping the slot); returns `false` if the page cannot hold it |
| `freeBytes()` | Page size − header − slots − live record bytes (includes space recoverable by compaction) |
| `isLive(slot)`, `slotCount()` | Accessors |

Space accounting: a new slot costs 4 bytes, so inserting into a page with no free slot needs `record.length + 4` bytes; reusing a free slot needs only `record.length`.

## Build it

1. Wrap the `byte[]` in a `ByteBuffer` for `getShort`/`putShort` at absolute offsets. Always mask with `& 0xFFFF` when reading.
2. Helpers: `offset(slot)`, `length(slot)`, `setSlot(slot, offset, length)`, `recordStart()`, `setRecordStart()`, `slotCount()`, `setSlotCount()`.
3. `freeBytes()` sums live lengths: `PAGE_SIZE - HEADER_SIZE - slotCount*SLOT_SIZE - live`.
4. `place(slot, record)`: if the **contiguous** gap (`recordStart - (HEADER_SIZE + slotCount*SLOT_SIZE)`) is smaller than the record, call `compact()`; then write at `recordStart - length`, set the slot, update `recordStart`.
5. `compact()`: copy every live record out (`get`), then rewrite them from the end of the page downward, updating each slot's offset, and set the new `recordStart`.
6. `insert`:

```java
int slot = firstFreeSlot();
int slotCost = slot < 0 ? SLOT_SIZE : 0;
if (freeBytes() < record.length + slotCost) {
    return -1;
}
if (slot < 0) {
    slot = slotCount();
    setSlotCount(slot + 1);
    setSlot(slot, 0, 0);
}
place(slot, record);
return slot;
```

7. `update`: if the new record is not longer than the old, overwrite in place and shrink the recorded length. Otherwise if `freeBytes() + oldLength < newLength` return `false`; else free the slot and `place` again (which may compact).

## Pitfalls

- **Compaction while placing**: when compacting inside `place`, the slot being placed must already be marked free (offset 0) so its old bytes are not copied. `insert` sets a new slot to `(0,0)`; `update` calls `setSlot(slot, 0, 0)` first.
- **Do not reuse the slot count as a "full" signal**: `freeBytes()` is the truth; contiguous space may be smaller than free space until compaction.
- Shrinking in place leaves a hole; that is fine, compaction reclaims it later.
- A record of length 0 would be indistinguishable from a freed slot in some designs; we reject empty records.
- The largest record (4088 bytes) fits exactly once in an empty page (header 4 + slot 4 + 4088 = 4096).

## Tests to write (`SlottedPageTest`)

Inserts and reads back; a fresh page has maximal free space; the largest record fits exactly once; oversized or empty records rejected; a deleted slot is reused; reading a deleted slot fails; compaction reclaims fragmented space (fill, delete alternating records, insert one larger record that only fits after compaction); update shrinks in place; update grows keeping the slot and its neighbours intact; update reports failure when the page is too full; a page of the wrong size is rejected.

## Checkpoint

Fill a page with 50-byte records until `insert` returns `-1`, delete every other one, insert a 100-byte record: it succeeds (after compaction) and all surviving records still read back correctly.
