package dev.frierendb.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.frierendb.engine.Database;
import dev.frierendb.engine.TestDatabases;
import dev.frierendb.engine.exec.QueryOptions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private Database db;
    private Map<String, Tool> tools;

    @BeforeEach
    void setUp() {
        db = TestDatabases.university(dir);
        tools = DatabaseTools.create(db, QueryOptions.bounded(3, Duration.ofSeconds(5)));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static JsonNode args(String key, String value) {
        return MAPPER.createObjectNode().put(key, value);
    }

    @Test
    void exposesExactlyTheFourReadOnlyTools() {
        assertEquals(java.util.Set.of("list_tables", "describe_table", "run_query", "explain_query"), tools.keySet());
        tools.values().forEach(t -> {
            assertNotNull(t.spec().description());
            assertEquals("object", t.spec().parametersSchema().get("type").asText());
        });
    }

    @Test
    void listTablesShowsCountsFromTheDatabase() {
        String out = tools.get("list_tables").execute(MAPPER.createObjectNode());
        assertTrue(out.contains("students: 4 columns, 6 rows"), out);
        assertTrue(out.contains("courses: 3 columns, 3 rows"), out);
    }

    @Test
    void describeTableShowsColumnsAndSampleValues() {
        String out = tools.get("describe_table").execute(args("table", "STUDENTS"));
        assertTrue(out.contains("sid INT (not null)"), out);
        assertTrue(out.contains("major TEXT (nullable)"), out);
        assertTrue(out.contains("1 | Ada | CS | 3.75"), out);
        assertTrue(out.contains("(3 rows)"), out);
    }

    @Test
    void describeTableRejectsUnknownAndInjectedNames() {
        ToolException unknown = assertThrows(ToolException.class,
                () -> tools.get("describe_table").execute(args("table", "nope")));
        assertTrue(unknown.getMessage().contains("students"), "the error should list the real tables");
        assertThrows(ToolException.class,
                () -> tools.get("describe_table").execute(args("table", "students\"; DROP TABLE students; --")));
        assertEquals(6, db.query("select * from students", QueryOptions.unlimited()).rows().size());
    }

    @Test
    void runQueryReturnsLabelledData() {
        String out = tools.get("run_query").execute(args("sql", "select name from students where sid = 1"));
        assertTrue(out.startsWith(DatabaseTools.DATA_HEADER), out);
        assertTrue(out.contains("Ada"), out);
        assertTrue(out.endsWith("(1 row)"), out);
    }

    @Test
    void runQueryReportsTruncationInsteadOfSilentlyDroppingRows() {
        String out = tools.get("run_query").execute(args("sql", "select name from students"));
        assertTrue(out.contains("(3 rows; more rows exist"), out);
    }

    @Test
    void sqlErrorsBecomeToolErrorsTheModelCanReadAndFix() {
        ToolException e = assertThrows(ToolException.class,
                () -> tools.get("run_query").execute(args("sql", "select nope from students")));
        assertEquals("Unknown column: nope", e.getMessage());
        assertThrows(ToolException.class, () -> tools.get("run_query").execute(args("sql", "select from")));
        assertThrows(ToolException.class, () -> tools.get("run_query").execute(args("sql", "select * from missing")));
    }

    @Test
    void writesAreRefusedAndNothingChanges() {
        for (String sql : new String[] {
                "delete from students", "drop table students", "update students set name = 'x'",
                "insert into students values (9, 'x', 'y', 1.0)", "create table t (a INT)",
                "select * from students; drop table students"}) {
            assertThrows(ToolException.class, () -> tools.get("run_query").execute(args("sql", sql)), sql);
        }
        assertEquals(6, db.query("select * from students", QueryOptions.unlimited()).rows().size());
        assertEquals(3, db.tableNames().size());
    }

    @Test
    void argumentsAreValidated() {
        Tool run = tools.get("run_query");
        assertThrows(ToolException.class, () -> run.execute(MAPPER.createObjectNode()));
        assertThrows(ToolException.class, () -> run.execute(args("sql", "  ")));
        assertThrows(ToolException.class, () -> run.execute(MAPPER.createObjectNode().put("sql", 5)));
        assertThrows(ToolException.class, () -> run.execute(args("sql", "select 1 from students where name = '" + "x".repeat(5000) + "'")));
        assertThrows(ToolException.class, () -> run.execute(null));
    }

    @Test
    void explainShowsThePlanWithoutExecuting() {
        String out = tools.get("explain_query").execute(args("sql", "select * from students s join enrollments e on s.sid = e.sid"));
        assertTrue(out.contains("HashJoin"), out);
        assertFalse(out.contains(DatabaseTools.DATA_HEADER));
        assertThrows(ToolException.class, () -> tools.get("explain_query").execute(args("sql", "delete from students")));
    }

    @Test
    void longCellsAreCutOff() {
        db.execute("insert into students values (60, '" + "z".repeat(1000) + "', 'CS', 1.0)");
        String out = tools.get("run_query").execute(args("sql", "select name from students where sid = 60"));
        assertTrue(out.length() < 600, "cell should be truncated, got " + out.length() + " chars");
        assertTrue(out.contains("..."));
    }
}
