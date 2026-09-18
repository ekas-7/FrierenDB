package dev.frierendb.engine.catalog;

import dev.frierendb.engine.index.BPlusTree;
import dev.frierendb.engine.schema.Row;
import dev.frierendb.engine.storage.BufferPool;
import dev.frierendb.engine.storage.PageFile;
import dev.frierendb.engine.storage.RecordId;
import dev.frierendb.engine.types.Value;

/** A B+ tree on one column of a table, plus the file it lives in. NULL values are not indexed. */
final class TableIndex {

    private final IndexInfo info;
    private final int ordinal;
    private final PageFile file;
    private final BPlusTree tree;

    TableIndex(IndexInfo info, int ordinal, PageFile file, BPlusTree tree) {
        this.info = info;
        this.ordinal = ordinal;
        this.file = file;
        this.tree = tree;
    }

    IndexInfo info() {
        return info;
    }

    int ordinal() {
        return ordinal;
    }

    BPlusTree tree() {
        return tree;
    }

    PageFile file() {
        return file;
    }

    /** Fails before anything is modified if this row's key cannot be stored. */
    void check(Row row) {
        tree.checkKey(row.get(ordinal));
    }

    void add(Row row, RecordId id) {
        Value key = row.get(ordinal);
        if (!key.isNull()) {
            tree.insert(key, id);
        }
    }

    void remove(Row row, RecordId id) {
        Value key = row.get(ordinal);
        if (!key.isNull()) {
            tree.delete(key, id);
        }
    }

    /** Applies a row change; does nothing when neither the key nor the record id moved. */
    void replace(Row before, RecordId oldId, Row after, RecordId newId) {
        if (before.get(ordinal).equals(after.get(ordinal)) && oldId.equals(newId)) {
            return;
        }
        remove(before, oldId);
        add(after, newId);
    }

    void flush(BufferPool pool) {
        pool.flush(file);
    }

    void close(BufferPool pool) {
        pool.evictAll(file);
        file.close();
    }
}
