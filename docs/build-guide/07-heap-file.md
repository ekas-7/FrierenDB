# Step 7 - Heap File

Classes: `HeapFile`, `RecordId` (package `dev.frierendb.engine.storage`).

## Goal

An unordered collection of records spanning many pages, addressed by `RecordId(pageId, slot)`, with insert, get, delete, update and a full scan.

## Concepts

- **Heap file**: records are placed wherever there is room; there is no ordering.
- **Record id (RID)**: the permanent address of a record. If an update must move a record to another page, its RID changes, and the caller receives the new one.
- **Free-space map**: an in-memory list of how many bytes are free in each page so an insert does not read every page.

## Design

- `record RecordId(int pageId, int slot)`; `toString()` is `page:slot`.
- `HeapFile(PageFile, BufferPool)`: on construction, scan every existing page once to fill `freeBytes` (one `Integer` per page).
- All methods `synchronized`.
- `insert(byte[])`:
  1. Reject records over `SlottedPage.MAX_RECORD_SIZE`.
  2. First-fit: find a page whose cached free space is `>= length + SLOT_SIZE` (conservative: assumes a new slot is needed).
  3. Fetch it, insert; if it unexpectedly does not fit, refresh the cached free space and continue.
  4. If no page fits, `pool.allocate(file)`, format it, insert there.
- `update(id, bytes)`: try `SlottedPage.update`; if the page cannot hold it, delete the old record and insert elsewhere; return the (possibly new) RID.
- `scan()`: returns an `Iterator<Entry(RecordId, byte[])>` over the pages that **existed when the scan began**, pinning one page at a time (never leaving pages pinned between `next()` calls). Records inserted during the scan to new pages are not visited.
- `flush()` delegates to `BufferPool.flush(file)` (write + fsync).

## Build it

1. `RecordId` record.
2. Constructor: loop `pageId in [0, pageCount)`, fetch, `new SlottedPage(frame.data()).freeBytes()`, add to the list, close the frame.
3. Always use try-with-resources around `pool.fetch`.
4. After every change to a page, call `frame.markDirty()` and update `freeBytes.set(pageId, page.freeBytes())`.
5. For `scan`, snapshot `pagesAtStart` and write an iterator with fields `pageId`, `slot`, and a precomputed `next`:

```java
private Entry advance() {
    while (pageId < pagesAtStart) {
        synchronized (HeapFile.this) {
            try (BufferPool.Frame frame = pool.fetch(file, pageId)) {
                SlottedPage page = new SlottedPage(frame.data());
                while (slot < page.slotCount()) {
                    int current = slot++;
                    if (page.isLive(current)) {
                        return new Entry(new RecordId(pageId, current), page.get(current));
                    }
                }
            }
        }
        pageId++;
        slot = 0;
    }
    return null;
}
```

   The iterator copies the record bytes (`page.get`), so releasing the pin is safe.
6. `fetch(RecordId)` helper: validate the page id against `pageCount` and throw `StorageException("Record ... does not exist")`.

## Pitfalls

- **Pin leaks**: forgetting to close a frame slowly pins the whole pool, then everything fails with "Buffer pool exhausted". `HeapFileTest.scanDoesNotLeavePagesPinned` guards this.
- **Free space vs slot cost**: a page with exactly `length` free bytes but no free slot cannot take the record; the `+ SLOT_SIZE` check avoids a pointless fetch.
- **Mutating while scanning**: because the scan snapshot's `pagesAtStart` ignores new pages, an `UPDATE` that relocates rows to the end cannot loop forever. Even so, the executor avoids the problem entirely by collecting matches first (step 14).
- **The record id is only valid until the record moves**: never keep RIDs across updates without using the id returned by `update`.

## Tests to write (`HeapFileTest`)

Insert, get and delete; many records span pages and a scan returns all of them; deleted space is reused (file does not grow); update relocates when the page is full; update keeps the id when the record still fits; data survives `flush` and reopen; **unflushed data is lost on reopen because there is no write-ahead log** (documents the limitation honestly); oversized record rejected; scan leaves no pages pinned.

## Checkpoint

Insert 10,000 small records with a buffer pool of only a few pages; scan returns exactly 10,000; delete half; insert 5,000 more; the file did not grow by more than a few pages.

Commit: `feat: add page file, buffer pool, slotted pages and heap file`.
