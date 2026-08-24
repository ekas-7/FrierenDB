package dev.frierendb.engine.catalog;

import dev.frierendb.engine.schema.Row;
import dev.frierendb.engine.schema.RowCodec;
import dev.frierendb.engine.schema.Schema;
import dev.frierendb.engine.storage.BufferPool;
import dev.frierendb.engine.storage.HeapFile;
import dev.frierendb.engine.storage.PageFile;
import dev.frierendb.engine.storage.RecordId;
import java.nio.file.Path;
import java.util.Iterator;

public final class Table {

    public record RowEntry(RecordId id, Row row) {}

    private final String name;
    private final Schema schema;
    private final PageFile file;
    private final BufferPool pool;
    private final HeapFile heap;

    Table(String name, Schema schema, Path path, BufferPool pool) {
        this.name = name;
        this.schema = schema;
        this.pool = pool;
        this.file = PageFile.open(path);
        this.heap = new HeapFile(file, pool);
    }

    public String name() {
        return name;
    }

    public Schema schema() {
        return schema;
    }

    public RecordId insert(Row row) {
        return heap.insert(RowCodec.encode(schema, row));
    }

    public Row get(RecordId id) {
        return RowCodec.decode(schema, heap.get(id));
    }

    public void delete(RecordId id) {
        heap.delete(id);
    }

    public RecordId update(RecordId id, Row row) {
        return heap.update(id, RowCodec.encode(schema, row));
    }

    public Iterator<RowEntry> scan() {
        Iterator<HeapFile.Entry> raw = heap.scan();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return raw.hasNext();
            }

            @Override
            public RowEntry next() {
                HeapFile.Entry entry = raw.next();
                return new RowEntry(entry.id(), RowCodec.decode(schema, entry.bytes()));
            }
        };
    }

    void flush() {
        heap.flush();
    }

    void close() {
        pool.evictAll(file);
        file.close();
    }
}
