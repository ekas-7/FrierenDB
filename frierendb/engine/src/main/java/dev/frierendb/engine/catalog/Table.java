package dev.frierendb.engine.catalog;

import dev.frierendb.engine.index.BPlusTree;
import dev.frierendb.engine.schema.Row;
import dev.frierendb.engine.schema.RowCodec;
import dev.frierendb.engine.schema.Schema;
import dev.frierendb.engine.storage.BufferPool;
import dev.frierendb.engine.storage.HeapFile;
import dev.frierendb.engine.storage.PageFile;
import dev.frierendb.engine.storage.RecordId;
import dev.frierendb.engine.types.DataType;
import dev.frierendb.engine.types.Value;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

/** Rows of one table in a heap file, plus the secondary indexes that are kept in step with them. */
public final class Table {

    public record RowEntry(RecordId id, Row row) {}

    private final String name;
    private final Schema schema;
    private final PageFile file;
    private final BufferPool pool;
    private final HeapFile heap;
    private final Map<String, TableIndex> indexes = new TreeMap<>();

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

    /** Definitions of the indexes on this table, ordered by name. */
    public List<IndexInfo> indexes() {
        return indexes.values().stream().map(TableIndex::info).toList();
    }

    /** Throws if any index could not store this row's key. Call before modifying anything. */
    public void checkIndexable(Row row) {
        for (TableIndex index : indexes.values()) {
            index.check(row);
        }
    }

    public RecordId insert(Row row) {
        checkIndexable(row);
        RecordId id = heap.insert(RowCodec.encode(schema, row));
        for (TableIndex index : indexes.values()) {
            index.add(row, id);
        }
        return id;
    }

    public Row get(RecordId id) {
        return RowCodec.decode(schema, heap.get(id));
    }

    public void delete(RecordId id) {
        if (!indexes.isEmpty()) {
            Row old = get(id);
            heap.delete(id);
            for (TableIndex index : indexes.values()) {
                index.remove(old, id);
            }
        } else {
            heap.delete(id);
        }
    }

    public RecordId update(RecordId id, Row row) {
        if (indexes.isEmpty()) {
            return heap.update(id, RowCodec.encode(schema, row));
        }
        checkIndexable(row);
        Row old = get(id);
        RecordId newId = heap.update(id, RowCodec.encode(schema, row));
        for (TableIndex index : indexes.values()) {
            index.replace(old, id, row, newId);
        }
        return newId;
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

    /**
     * Rows whose indexed column lies between the bounds, in ascending key order. A null bound is
     * unbounded. Bounds must have the column's type.
     */
    public Iterator<RowEntry> indexScan(String indexName, Value lo, boolean loInclusive, Value hi,
            boolean hiInclusive) {
        TableIndex index = indexes.get(indexName.toLowerCase(Locale.ROOT));
        if (index == null) {
            throw new CatalogException("No such index on table " + name + ": " + indexName);
        }
        Iterator<RecordId> ids = index.tree().range(lo, loInclusive, hi, hiInclusive);
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return ids.hasNext();
            }

            @Override
            public RowEntry next() {
                if (!ids.hasNext()) {
                    throw new NoSuchElementException();
                }
                RecordId id = ids.next();
                return new RowEntry(id, get(id));
            }
        };
    }

    /** Row count according to the named index; used by tests to compare an index with the heap. */
    public long indexSize(String indexName) {
        TableIndex index = indexes.get(indexName.toLowerCase(Locale.ROOT));
        if (index == null) {
            throw new CatalogException("No such index on table " + name + ": " + indexName);
        }
        return index.tree().size();
    }

    /** Builds an index over the rows already in the table. The file must not be registered yet. */
    TableIndex buildIndex(IndexInfo info, int ordinal, Path path) {
        PageFile indexFile = PageFile.open(path);
        try {
            DataType type = schema.column(ordinal).type();
            BPlusTree tree = BPlusTree.open(indexFile, pool, type);
            TableIndex index = new TableIndex(info, ordinal, indexFile, tree);
            for (Iterator<RowEntry> it = scan(); it.hasNext(); ) {
                RowEntry entry = it.next();
                index.check(entry.row());
                index.add(entry.row(), entry.id());
            }
            pool.flush(indexFile);
            return index;
        } catch (RuntimeException e) {
            try {
                pool.evictAll(indexFile);
            } catch (RuntimeException ignored) {
                // the original failure is the one worth reporting
            }
            indexFile.close();
            throw e;
        }
    }

    /** Opens an index that already exists on disk. */
    TableIndex openIndex(IndexInfo info, int ordinal, Path path) {
        PageFile indexFile = PageFile.open(path);
        try {
            BPlusTree tree = BPlusTree.open(indexFile, pool, schema.column(ordinal).type());
            return new TableIndex(info, ordinal, indexFile, tree);
        } catch (RuntimeException e) {
            indexFile.close();
            throw e;
        }
    }

    void attach(TableIndex index) {
        indexes.put(index.info().name(), index);
    }

    TableIndex detach(String indexName) {
        return indexes.remove(indexName);
    }

    boolean hasIndex(String indexName) {
        return indexes.containsKey(indexName);
    }

    List<TableIndex> indexFiles() {
        return new ArrayList<>(indexes.values());
    }

    void flush() {
        heap.flush();
        indexes.values().forEach(index -> index.flush(pool));
    }

    void close() {
        indexes.values().forEach(index -> index.close(pool));
        pool.evictAll(file);
        file.close();
    }
}
