package dev.frierendb.engine.catalog;

import dev.frierendb.engine.schema.Column;
import dev.frierendb.engine.schema.Schema;
import dev.frierendb.engine.storage.BufferPool;
import dev.frierendb.engine.types.DataType;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Persistent registry of tables and their indexes. Definitions live in {@code catalog.bin}; each
 * table's rows live in {@code <table>.tbl} and each index in {@code <table>.<index>.idx}. Names are
 * restricted to simple identifiers because they become file names.
 */
public final class Catalog implements AutoCloseable {

    private static final int MAGIC = 0x46524E43;
    private static final int VERSION = 2;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private final Path dir;
    private final BufferPool pool;
    private final Map<String, Table> tables = new TreeMap<>();

    private Catalog(Path dir, BufferPool pool) {
        this.dir = dir;
        this.pool = pool;
    }

    public static Catalog open(Path dir, BufferPool pool) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new CatalogException("Cannot create database directory " + dir, e);
        }
        Catalog catalog = new Catalog(dir, pool);
        Path file = dir.resolve("catalog.bin");
        if (Files.exists(file)) {
            catalog.load(file);
        }
        return catalog;
    }

    public synchronized Table createTable(String name, Schema schema) {
        String key = normalize(name);
        if (tables.containsKey(key)) {
            throw new CatalogException("Table already exists: " + name);
        }
        deleteStale(tablePath(key));
        Table table = new Table(key, schema, tablePath(key), pool);
        tables.put(key, table);
        save();
        return table;
    }

    public synchronized void dropTable(String name) {
        String key = normalize(name);
        Table table = tables.remove(key);
        if (table == null) {
            throw new CatalogException("No such table: " + name);
        }
        List<Path> files = new ArrayList<>();
        for (IndexInfo index : table.indexes()) {
            files.add(indexPath(key, index.name()));
        }
        table.close();
        save();
        try {
            Files.deleteIfExists(tablePath(key));
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new CatalogException("Cannot delete data file of table " + name, e);
        }
    }

    /**
     * Creates an index on one column and fills it from the rows already in the table. The index
     * file is built first and only registered once it is complete and on disk.
     */
    public synchronized IndexInfo createIndex(String name, String tableName, String columnName) {
        String indexKey = normalize("index", name);
        if (indexExists(indexKey)) {
            throw new CatalogException("Index already exists: " + name);
        }
        Table table = table(tableName);
        int ordinal = table.schema().requireIndexOf(columnName);
        IndexInfo info = new IndexInfo(indexKey, table.name(), table.schema().column(ordinal).name());
        Path path = indexPath(table.name(), indexKey);
        deleteStale(path);
        TableIndex index;
        try {
            index = table.buildIndex(info, ordinal, path);
        } catch (RuntimeException e) {
            deleteStale(path);
            throw e;
        }
        table.attach(index);
        save();
        return info;
    }

    public synchronized void dropIndex(String name) {
        String indexKey = name.toLowerCase(Locale.ROOT);
        for (Table table : tables.values()) {
            if (table.hasIndex(indexKey)) {
                TableIndex index = table.detach(indexKey);
                save();
                index.close(pool);
                deleteStale(indexPath(table.name(), indexKey));
                return;
            }
        }
        throw new CatalogException("No such index: " + name);
    }

    public synchronized boolean indexExists(String name) {
        String indexKey = name.toLowerCase(Locale.ROOT);
        return tables.values().stream().anyMatch(t -> t.hasIndex(indexKey));
    }

    public synchronized Table table(String name) {
        Table table = tables.get(name.toLowerCase(Locale.ROOT));
        if (table == null) {
            throw new CatalogException("No such table: " + name);
        }
        return table;
    }

    public synchronized boolean exists(String name) {
        return tables.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public synchronized List<String> tableNames() {
        return List.copyOf(tables.keySet());
    }

    /** Rebuilds in-memory state derived from pages, after a rollback restored the pages themselves. */
    public synchronized void reload() {
        tables.values().forEach(Table::reload);
    }

    public synchronized void flush() {
        tables.values().forEach(Table::flush);
    }

    @Override
    public synchronized void close() {
        flush();
        tables.values().forEach(Table::close);
        tables.clear();
    }

    private String normalize(String name) {
        return normalize("table", name);
    }

    private String normalize(String kind, String name) {
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new CatalogException(
                    "Invalid " + kind + " name '" + name + "': use letters, digits and underscores, starting with a letter or underscore");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private Path indexPath(String table, String index) {
        return dir.resolve(table + "." + index + ".idx");
    }

    /** Removes a file that no catalog entry refers to, such as one left behind by a crash. */
    private void deleteStale(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new CatalogException("Cannot delete " + path, e);
        }
    }

    private Path tablePath(String key) {
        return dir.resolve(key + ".tbl");
    }

    private void save() {
        Path temp = dir.resolve("catalog.bin.tmp");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(tables.size());
            for (Table table : tables.values()) {
                out.writeUTF(table.name());
                out.writeInt(table.schema().size());
                for (Column column : table.schema().columns()) {
                    out.writeUTF(column.name());
                    out.writeUTF(column.type().name());
                    out.writeBoolean(column.nullable());
                }
                List<IndexInfo> indexes = table.indexes();
                out.writeInt(indexes.size());
                for (IndexInfo index : indexes) {
                    out.writeUTF(index.name());
                    out.writeUTF(index.column());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Files.move(temp, dir.resolve("catalog.bin"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new CatalogException("Cannot write catalog", e);
        }
    }

    private void load(Path file) {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != MAGIC) {
                throw new CatalogException(file + " is not a FrierenDB catalog");
            }
            int version = in.readInt();
            if (version < 1 || version > VERSION) {
                throw new CatalogException("Unsupported catalog version " + version);
            }
            int tableCount = in.readInt();
            for (int t = 0; t < tableCount; t++) {
                String name = in.readUTF();
                int columnCount = in.readInt();
                List<Column> columns = new ArrayList<>();
                for (int c = 0; c < columnCount; c++) {
                    columns.add(new Column(in.readUTF(), DataType.valueOf(in.readUTF()), in.readBoolean()));
                }
                Path data = tablePath(normalize(name));
                if (!Files.exists(data)) {
                    throw new CatalogException("Data file missing for table " + name + ": " + data);
                }
                Table table = new Table(name, new Schema(columns), data, pool);
                tables.put(name, table);
                int indexCount = version >= 2 ? in.readInt() : 0;
                for (int i = 0; i < indexCount; i++) {
                    String indexName = in.readUTF();
                    String columnName = in.readUTF();
                    Path indexFile = indexPath(table.name(), normalize("index", indexName));
                    if (!Files.exists(indexFile)) {
                        throw new CatalogException("Index file missing for index " + indexName + ": " + indexFile);
                    }
                    int ordinal = table.schema().requireIndexOf(columnName);
                    table.attach(table.openIndex(new IndexInfo(indexName, table.name(), columnName), ordinal, indexFile));
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new CatalogException("Cannot read catalog " + file, e);
        }
    }
}
