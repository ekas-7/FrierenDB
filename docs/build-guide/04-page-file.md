# Step 4 - Page File

Package: `dev.frierendb.engine.storage`. Class: `PageFile`.

## Goal

Read and write fixed-size 4096-byte pages of one file by page number. This is the only class that touches table files directly.

## Concepts

- **Page**: the unit of disk I/O. Databases read and write whole pages because disks and OS caches work in blocks.
- **`FileChannel`**: Java NIO file access that supports positional reads and writes (no shared seek pointer).
- **`force(false)`**: asks the OS to flush file data to the device (the "fsync" of Java).

## Design

- `PAGE_SIZE = 4096` (a public constant).
- Page `n` occupies bytes `[n * 4096, (n + 1) * 4096)`.
- The file length must always be a multiple of 4096; opening a file that is not is an error (`StorageException`), because it indicates corruption or the wrong file.
- Pages are only ever **appended** (`allocate`) or **overwritten**; never removed in the middle.
- All public methods are `synchronized`.

API:

| Method | Behaviour |
|---|---|
| `static PageFile open(Path)` | Creates the file if missing; computes `pageCount` from the size |
| `int allocate()` | Appends a zero-filled page; returns its id |
| `byte[] read(int pageId)` | Returns a fresh 4096-byte array; fails for ids outside `[0, pageCount)` |
| `void write(int pageId, byte[])` | Data must be exactly 4096 bytes; `pageId` may equal `pageCount` to extend by one page |
| `void sync()` | `channel.force(false)` |
| `void close()` | Closes the channel |
| `int pageCount()`, `Path path()` | Accessors |

Added in [step 22](22-write-ahead-log-and-recovery.md): `truncate(int pageCount)` (drops pages from a count onward, used to undo allocations) and `isOpen()`.

## Build it

1. `StorageException extends FrierenException` (with `serialVersionUID`).
2. Open the channel with `CREATE, READ, WRITE`.
3. In `open`, `size % PAGE_SIZE != 0` → close the channel then throw; otherwise `pageCount = size / PAGE_SIZE` using `Math.toIntExact` (fails loudly beyond 2^31 pages ≈ 8 TiB).
4. `read`: wrap a new `byte[4096]` in a `ByteBuffer` and **loop** until the buffer is full:

```java
while (buf.hasRemaining()) {
    int n = channel.read(buf, position + buf.position());
    if (n < 0) {
        throw new StorageException("Unexpected end of file reading page " + pageId);
    }
}
```

   A single `read` may return fewer bytes than requested, so the loop is required for correctness.
5. `write`: validate length and range, loop on `channel.write(buf, position + buf.position())`, and increment `pageCount` when `pageId == pageCount`.
6. Wrap every `IOException` in `StorageException` with the path and page id in the message.

## Pitfalls

- Using `channel.position(...)` plus a plain read is **not** safe if two threads share the channel; use the positional overloads.
- `allocate()` is `write(pageCount, new byte[PAGE_SIZE])`, then the new id is `pageCount - 1` (because `write` incremented it).
- Reading returns a **copy**. The buffer pool owns long-lived buffers; this class never caches.
- `sync()` is the durability point. Writes alone only reach the OS cache.

## Tests to write (`PageFileTest`)

- Allocated pages are zero-filled and numbered sequentially from 0.
- Written data survives closing and reopening the file.
- Reading a missing page fails; writing beyond the end (more than one past) or with a wrong-size array fails.
- A file whose length is not a multiple of 4096 is rejected on open.

## Checkpoint

Write a page of repeating bytes, close, reopen, read it back: identical.

Commit (with steps 5-7): `feat: add page file, buffer pool, slotted pages and heap file`.
