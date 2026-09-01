package dev.frierendb.engine;

import dev.frierendb.engine.exec.QueryOptions;
import dev.frierendb.engine.exec.QueryResult;
import java.nio.file.Path;
import java.util.List;

public final class TestDatabases {

    private TestDatabases() {}

    public static final String UNIVERSITY_SCRIPT = """
            CREATE TABLE students (sid INT NOT NULL, name TEXT NOT NULL, major TEXT, gpa DOUBLE);
            CREATE TABLE courses (cid INT NOT NULL, title TEXT NOT NULL, dept TEXT NOT NULL);
            CREATE TABLE enrollments (sid INT NOT NULL, cid INT NOT NULL, grade DOUBLE);
            INSERT INTO students VALUES
              (1, 'Ada', 'CS', 3.75), (2, 'Bob', 'CS', 3.25), (3, 'Cy', 'Math', 3.5),
              (4, 'Di', 'Math', NULL), (5, 'Eve', 'Art', 2.5), (6, 'Fay', NULL, 3.0);
            INSERT INTO courses VALUES (10, 'Databases', 'CS'), (11, 'Algebra', 'Math'), (12, 'Painting', 'Art');
            INSERT INTO enrollments VALUES
              (1, 10, 4.0), (1, 11, 3.5), (2, 10, 3.0), (3, 11, 3.25), (3, 10, NULL), (5, 12, 3.5);
            """;

    public static Database university(Path dir) {
        Database db = Database.open(dir, 8);
        db.executeScript(UNIVERSITY_SCRIPT);
        return db;
    }

    /** Runs a read-only query and renders every value with toString, NULL included. */
    public static List<List<String>> rows(Database db, String sql) {
        return render(db.query(sql, QueryOptions.unlimited()));
    }

    public static List<List<String>> render(QueryResult result) {
        return result.rows().stream()
                .map(row -> row.values().stream().map(Object::toString).toList())
                .toList();
    }
}
