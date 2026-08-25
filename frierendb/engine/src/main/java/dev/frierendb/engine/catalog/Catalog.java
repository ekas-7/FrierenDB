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
 * Persistent table registry. Definitions live in {@code catalog.bin}; each table's rows live in
 * {@code <name>.tbl}. Table names are restricted to simple identifiers because they become file names.
 */
public final class Catalog implements AutoCloseable {

    private static final int MAGIC = 0x46524E43;
    private static final int VERSION = 1;
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
        table.close();
        try {
            Files.deleteIfExists(tablePath(key));
        } catch (IOException e) {
            throw new CatalogException("Cannot delete data file of table " + name, e);
        }
        save();
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
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new CatalogException(
                    "Invalid table name '" + name + "': use letters, digits and underscores, starting with a letter or underscore");
        }
        return name.toLowerCase(Locale.ROOT);
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
            if (version != VERSION) {
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
                tables.put(name, new Table(name, new Schema(columns), data, pool));
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new CatalogException("Cannot read catalog " + file, e);
        }
    }
}
