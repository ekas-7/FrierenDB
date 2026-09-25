# Step 5 - Buffer Pool

Class: `dev.frierendb.engine.storage.BufferPool`.

## Goal

Keep recently used pages in memory, write changed pages back only when needed, and guarantee a page that someone is using is never evicted.

## Concepts

- **Cache hit / miss**: the page is already in memory / must be read from disk.
- **LRU eviction**: when full, drop the *least recently used* page.
- **Dirty page**: modified in memory, not yet written to disk. Evicting it requires a write-back.
- **Pinning**: while code holds a page's bytes, the pool must not evict or reuse that buffer. A pin count tracks the holders.

## Design

- Capacity is a number of pages (default in `Database` is 256, i.e. 1 MiB).
- The pool is shared by all files: a page is identified by `PageKey(PageFile file, int pageId)`.
- `fetch(file, pageId)` returns a `Frame` that is **pinned**. `Frame` implements `AutoCloseable`; closing unpins. Always use try-with-resources.
- A `Frame` exposes `data()` (the page's `byte[]`), `pageId()`, and `markDirty()`.
- LRU order uses `LinkedHashMap` with `accessOrder = true`: iteration order is least-recently-used first, so eviction scans from the front for the first frame with `pins == 0`.
- If **every** frame is pinned and a new page is needed, throw `StorageException("Buffer pool exhausted ...")`; never evict a pinned page.
- Statistics: `hits()`, `misses()`, `cachedPages()`.

API:

| Method | Behaviour |
|---|---|
| `fetch(file, pageId)` | Hit: bump pins. Miss: make room, read from disk, insert, pin |
| `allocate(file)` | Make room, append a new page to the file, return it pinned and zeroed |
| `flush(file)` | Write every dirty frame of that file, then `file.sync()` (frames stay cached) |
| `evictAll(file)` | `flush`, then remove the file's frames; fails if any is still pinned |

> **Later change (step 22):** with a `Wal` attached the pool also provides one transaction at a time (`begin`, `commit`, `rollback`, `checkpoint`), keeps before-images, and logs a page before it is written. The statement below that eviction "does not `sync()`" still holds for the plain cache; `checkpoint()` is what syncs the files written since the last sync. A pool without a log behaves exactly as described in this step.

## Build it

1. A private `record PageKey(PageFile file, int pageId)`. `PageFile` uses identity equality, which is what we want (one object per file).
2. `Frame` as an inner (non-static) class so `markDirty()` and `close()` can synchronize on the pool.
3. `makeRoom()`:

```java
for (Map.Entry<PageKey, Frame> entry : frames.entrySet()) {
    Frame candidate = entry.getValue();
    if (candidate.pins == 0) {
        writeIfDirty(candidate);
        frames.remove(entry.getKey());
        return;
    }
}
throw new StorageException("Buffer pool exhausted: all " + capacity + " pages are pinned");
```

   Removing inside the loop is safe because we `return` immediately.
4. `unpin`: throw if `pins <= 0` ("closing twice" is a bug worth catching).
5. All methods `synchronized`.

## Pitfalls

- **Double close**: closing a frame twice would drive `pins` negative and later allow eviction of a page in use. Reject it.
- A `LinkedHashMap` with access order **reorders on `get`**; do not iterate and `get` at the same time (concurrent modification).
- `flush` must not drop pages (flush ≠ evict); `evictAll` is for closing/dropping a table.
- The pool synchronizes its own state but **not the bytes of a page**: two threads editing the same page must be serialised by the caller. In this engine the `HeapFile` is synchronized, which provides that.
- Evicting a dirty page writes it directly with `PageFile.write`; it does **not** `sync()`. Durability comes from `flush`.

## Tests to write (`BufferPoolTest`)

- A repeated fetch is a hit (counter increases).
- The least-recently-used unpinned page is evicted first (touch A, B, A, then fetch C: B is evicted).
- A dirty page is written back when evicted; a clean page is not written.
- Fetching when everything is pinned fails.
- `flush` persists dirty pages without evicting them.
- `evictAll` refuses while a page is pinned.
- `allocate` returns a pinned, zeroed frame.
- Closing a frame twice fails.

## Checkpoint

With a pool of 2 pages and a file of 5, reading all pages repeatedly works and `cachedPages()` never exceeds 2.
