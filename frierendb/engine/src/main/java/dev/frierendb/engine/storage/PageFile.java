package dev.frierendb.engine.storage;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class PageFile implements Closeable {

    public static final int PAGE_SIZE = 4096;

    private final Path path;
    private final FileChannel channel;
    private int pageCount;

    private PageFile(Path path, FileChannel channel, int pageCount) {
        this.path = path;
        this.channel = channel;
        this.pageCount = pageCount;
    }

    public static PageFile open(Path path) {
        try {
            FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            long size = channel.size();
            if (size % PAGE_SIZE != 0) {
                channel.close();
                throw new StorageException(
                        "File " + path + " is not a whole number of pages (" + size + " bytes)");
            }
            return new PageFile(path, channel, Math.toIntExact(size / PAGE_SIZE));
        } catch (IOException e) {
            throw new StorageException("Cannot open " + path, e);
        }
    }

    public Path path() {
        return path;
    }

    public synchronized int pageCount() {
        return pageCount;
    }

    /** Appends a zero-filled page and returns its id. */
    public synchronized int allocate() {
        write(pageCount, new byte[PAGE_SIZE]);
        return pageCount - 1;
    }

    public synchronized byte[] read(int pageId) {
        checkRange(pageId);
        byte[] data = new byte[PAGE_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(data);
        try {
            long position = (long) pageId * PAGE_SIZE;
            while (buf.hasRemaining()) {
                int n = channel.read(buf, position + buf.position());
                if (n < 0) {
                    throw new StorageException("Unexpected end of file reading page " + pageId);
                }
            }
        } catch (IOException e) {
            throw new StorageException("Cannot read page " + pageId + " of " + path, e);
        }
        return data;
    }

    /** Writes a page; writing at {@code pageCount()} extends the file by one page. */
    public synchronized void write(int pageId, byte[] data) {
        if (data.length != PAGE_SIZE) {
            throw new StorageException("Page data must be exactly " + PAGE_SIZE + " bytes");
        }
        if (pageId < 0 || pageId > pageCount) {
            throw new StorageException("Page " + pageId + " is beyond the end of " + path);
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        try {
            long position = (long) pageId * PAGE_SIZE;
            while (buf.hasRemaining()) {
                channel.write(buf, position + buf.position());
            }
        } catch (IOException e) {
            throw new StorageException("Cannot write page " + pageId + " of " + path, e);
        }
        if (pageId == pageCount) {
            pageCount++;
        }
    }

    public synchronized void sync() {
        try {
            channel.force(false);
        } catch (IOException e) {
            throw new StorageException("Cannot sync " + path, e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new StorageException("Cannot close " + path, e);
        }
    }

    private void checkRange(int pageId) {
        if (pageId < 0 || pageId >= pageCount) {
            throw new StorageException(
                    "Page " + pageId + " does not exist in " + path + " (" + pageCount + " pages)");
        }
    }
}
