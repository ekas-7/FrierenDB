package dev.frierendb.engine.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * An append-only write-ahead log of whole-page changes.
 *
 * <p>File layout: an 8-byte header (magic, version) followed by frames of
 * {@code [payload length: u32][CRC-32 of payload: u32][payload]}. A payload is a type byte, a
 * transaction id, and for UPDATE records the page file's name, the page id, an optional
 * before-image and the after-image. A frame that is cut short or fails its checksum, and everything
 * after it, is treated as never written; this is how a crash in the middle of an append is detected.
 *
 * <p>The log never decides what is durable by itself: {@link #force()} must be called, and
 * {@link BufferPool} calls it at commit and before an uncommitted dirty page may be written.
 */
public final class Wal implements AutoCloseable {

    public static final String FILE_NAME = "wal.log";

    private static final int MAGIC = 0x4657414C; // "FWAL"
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 8;
    private static final int FRAME_HEADER_BYTES = 8;
    private static final int MAX_NAME_BYTES = 255;
    private static final int MAX_PAYLOAD =
            1 + 8 + 2 + MAX_NAME_BYTES + 4 + 1 + 2 * PageFile.PAGE_SIZE;

    public enum Type {
        UPDATE((byte) 1), COMMIT((byte) 2), ABORT((byte) 3);

        private final byte code;

        Type(byte code) {
            this.code = code;
        }

        static Type of(byte code) {
            for (Type type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * One log record. {@code before} is null when the page did not exist before the transaction
     * (it was allocated by it). Only UPDATE records carry a file, page and images.
     */
    public record LogRecord(Type type, long txId, String file, int pageId, byte[] before, byte[] after) {

        public static LogRecord update(long txId, String file, int pageId, byte[] before, byte[] after) {
            return new LogRecord(Type.UPDATE, txId, file, pageId, before, after);
        }

        public static LogRecord commit(long txId) {
            return new LogRecord(Type.COMMIT, txId, null, 0, null, null);
        }

        public static LogRecord abort(long txId) {
            return new LogRecord(Type.ABORT, txId, null, 0, null, null);
        }
    }

    /** The valid prefix of a log. {@code validBytes} is where the next record would be appended. */
    public record Contents(List<LogRecord> records, long validBytes, boolean tornTail) {}

    private final Path path;
    private final FileChannel channel;
    private long position;
    private long nextTxId = 1;

    private Wal(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    /**
     * Opens the log, creating it if needed. A torn tail left by a crash is cut off so that new
     * records follow the last valid one.
     */
    public static Wal open(Path path) {
        try {
            FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            Wal wal = new Wal(path, channel);
            if (channel.size() < HEADER_BYTES) {
                wal.writeHeader();
            } else {
                Contents contents = read(path);
                for (LogRecord record : contents.records()) {
                    wal.nextTxId = Math.max(wal.nextTxId, record.txId() + 1);
                }
                channel.truncate(contents.validBytes());
                wal.position = contents.validBytes();
            }
            return wal;
        } catch (IOException e) {
            throw new StorageException("Cannot open write-ahead log " + path, e);
        }
    }

    public Path path() {
        return path;
    }

    public synchronized long nextTxId() {
        return nextTxId++;
    }

    /** Bytes in the log, header included. A log with no records is {@value #HEADER_BYTES} bytes. */
    public synchronized long size() {
        return position;
    }

    public synchronized void append(LogRecord record) {
        byte[] payload = encode(record);
        ByteBuffer frame = ByteBuffer.allocate(FRAME_HEADER_BYTES + payload.length);
        CRC32 crc = new CRC32();
        crc.update(payload);
        frame.putInt(payload.length);
        frame.putInt((int) crc.getValue());
        frame.put(payload);
        frame.flip();
        try {
            long at = position;
            while (frame.hasRemaining()) {
                at += channel.write(frame, at);
            }
            position = at;
        } catch (IOException e) {
            throw new StorageException("Cannot append to write-ahead log " + path, e);
        }
    }

    /** Makes every appended record durable. */
    public synchronized void force() {
        try {
            channel.force(false);
        } catch (IOException e) {
            throw new StorageException("Cannot sync write-ahead log " + path, e);
        }
    }

    /** Valid records appended so far, including those not yet forced. */
    public synchronized Contents contents() {
        return read(path);
    }

    /** Discards every record. Only safe once all changes they describe are on disk. */
    public synchronized void reset() {
        try {
            channel.truncate(HEADER_BYTES);
            position = HEADER_BYTES;
            channel.force(false);
        } catch (IOException e) {
            throw new StorageException("Cannot reset write-ahead log " + path, e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new StorageException("Cannot close write-ahead log " + path, e);
        }
    }

    /** Reads the valid prefix of the log at {@code path}; a missing file is an empty log. */
    public static Contents read(Path path) {
        if (!Files.exists(path)) {
            return new Contents(List.of(), HEADER_BYTES, false);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new StorageException("Cannot read write-ahead log " + path, e);
        }
        if (bytes.length < HEADER_BYTES) {
            return new Contents(List.of(), HEADER_BYTES, bytes.length > 0);
        }
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (in.getInt() != MAGIC) {
            throw new StorageException(path + " is not a FrierenDB write-ahead log");
        }
        int version = in.getInt();
        if (version != VERSION) {
            throw new StorageException("Unsupported write-ahead log version " + version + " in " + path);
        }
        List<LogRecord> records = new ArrayList<>();
        long valid = HEADER_BYTES;
        while (in.remaining() > 0) {
            if (in.remaining() < FRAME_HEADER_BYTES) {
                return new Contents(records, valid, true);
            }
            int length = in.getInt();
            int expectedCrc = in.getInt();
            if (length <= 0 || length > MAX_PAYLOAD || length > in.remaining()) {
                return new Contents(records, valid, true);
            }
            byte[] payload = new byte[length];
            in.get(payload);
            CRC32 crc = new CRC32();
            crc.update(payload);
            LogRecord record = (int) crc.getValue() == expectedCrc ? decode(payload) : null;
            if (record == null) {
                return new Contents(records, valid, true);
            }
            records.add(record);
            valid += FRAME_HEADER_BYTES + length;
        }
        return new Contents(records, valid, false);
    }

    private void writeHeader() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(MAGIC);
        header.putInt(VERSION);
        header.flip();
        channel.truncate(0);
        long at = 0;
        while (header.hasRemaining()) {
            at += channel.write(header, at);
        }
        channel.force(false);
        position = HEADER_BYTES;
    }

    private static byte[] encode(LogRecord record) {
        if (record.type() != Type.UPDATE) {
            ByteBuffer out = ByteBuffer.allocate(1 + 8);
            out.put(record.type().code);
            out.putLong(record.txId());
            return out.array();
        }
        byte[] name = record.file().getBytes(StandardCharsets.UTF_8);
        if (name.length == 0 || name.length > MAX_NAME_BYTES) {
            throw new StorageException("Unsupported page file name length: " + name.length);
        }
        if (record.after().length != PageFile.PAGE_SIZE
                || record.before() != null && record.before().length != PageFile.PAGE_SIZE) {
            throw new StorageException("Page images must be exactly " + PageFile.PAGE_SIZE + " bytes");
        }
        boolean hasBefore = record.before() != null;
        ByteBuffer out = ByteBuffer.allocate(
                1 + 8 + 2 + name.length + 4 + 1 + (hasBefore ? PageFile.PAGE_SIZE : 0) + PageFile.PAGE_SIZE);
        out.put(record.type().code);
        out.putLong(record.txId());
        out.putShort((short) name.length);
        out.put(name);
        out.putInt(record.pageId());
        out.put((byte) (hasBefore ? 1 : 0));
        if (hasBefore) {
            out.put(record.before());
        }
        out.put(record.after());
        return out.array();
    }

    /** Returns null if the payload is not a well-formed record. */
    private static LogRecord decode(byte[] payload) {
        ByteBuffer in = ByteBuffer.wrap(payload);
        Type type = Type.of(in.get());
        if (type == null || in.remaining() < 8) {
            return null;
        }
        long txId = in.getLong();
        if (type != Type.UPDATE) {
            return in.remaining() == 0 ? new LogRecord(type, txId, null, 0, null, null) : null;
        }
        if (in.remaining() < 2) {
            return null;
        }
        int nameLength = in.getShort() & 0xFFFF;
        if (nameLength == 0 || nameLength > MAX_NAME_BYTES || in.remaining() < nameLength + 4 + 1) {
            return null;
        }
        byte[] name = new byte[nameLength];
        in.get(name);
        int pageId = in.getInt();
        int hasBefore = in.get();
        int expected = (hasBefore == 1 ? PageFile.PAGE_SIZE : 0) + PageFile.PAGE_SIZE;
        if (hasBefore != 0 && hasBefore != 1 || in.remaining() != expected) {
            return null;
        }
        byte[] before = null;
        if (hasBefore == 1) {
            before = new byte[PageFile.PAGE_SIZE];
            in.get(before);
        }
        byte[] after = new byte[PageFile.PAGE_SIZE];
        in.get(after);
        return new LogRecord(Type.UPDATE, txId, new String(name, StandardCharsets.UTF_8), pageId, before, after);
    }
}
