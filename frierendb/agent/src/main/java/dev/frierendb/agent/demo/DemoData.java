package dev.frierendb.agent.demo;

import dev.frierendb.engine.Database;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class DemoData {

    private static final String RESOURCE = "/demo/university.sql";

    private DemoData() {}

    /** Loads the sample university dataset. Returns false and changes nothing if tables already exist. */
    public static boolean seedIfEmpty(Database db) {
        if (!db.tableNames().isEmpty()) {
            return false;
        }
        db.executeScript(script());
        return true;
    }

    static String script() {
        try (InputStream in = DemoData.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
