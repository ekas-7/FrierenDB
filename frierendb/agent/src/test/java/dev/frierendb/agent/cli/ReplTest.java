package dev.frierendb.agent.cli;

import static dev.frierendb.agent.support.ScriptedChatModel.answer;
import static dev.frierendb.agent.support.ScriptedChatModel.runQuery;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.agent.core.AgentConfig;
import dev.frierendb.agent.core.AnalystAgent;
import dev.frierendb.agent.demo.DemoData;
import dev.frierendb.agent.support.ScriptedChatModel;
import dev.frierendb.engine.Database;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplTest {

    @TempDir
    Path dir;

    private Database db;
    private ByteArrayOutputStream bytes;

    @BeforeEach
    void setUp() {
        db = Database.open(dir);
        DemoData.seedIfEmpty(db);
        bytes = new ByteArrayOutputStream();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private String session(Repl repl, String input) throws IOException {
        repl.run(new BufferedReader(new StringReader(input)), false);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private Repl repl(ScriptedChatModel model) {
        AnalystAgent agent = model == null ? null : new AnalystAgent(model, db, AgentConfig.defaults());
        return new Repl(db, agent, "NOT CONFIGURED", new PrintStream(bytes, true, StandardCharsets.UTF_8));
    }

    @Test
    void humanSqlIsPrintedAsAlignedTable() throws IOException {
        String out = session(repl(null), "SELECT sid, name FROM students WHERE sid <= 2 ORDER BY sid\n");

        assertTrue(out.contains("sid | name"), out);
        assertTrue(out.contains("1   | Ada Chen"), out);
        assertTrue(out.contains("(2 rows)"), out);
    }

    @Test
    void humanSqlMayWriteButReportsAffectedRows() throws IOException {
        String out = session(repl(null), "DELETE FROM enrollments WHERE sid = 1\nSELECT COUNT(*) FROM enrollments WHERE sid = 1\n");

        assertTrue(out.contains("3 row(s) affected"), out);
        assertTrue(out.contains("0"), out);
    }

    @Test
    void errorsAreReportedAndTheSessionContinues() throws IOException {
        String out = session(repl(null), "SELEKT 1\nSELECT COUNT(*) FROM students\n");

        assertTrue(out.contains("Error:"), out);
        assertTrue(out.contains("20"), out);
    }

    @Test
    void tablesCommandListsSchemas() throws IOException {
        String out = session(repl(null), "\\tables\n");

        assertTrue(out.contains("students(sid INT, name TEXT, major TEXT, gpa DOUBLE)"), out);
    }

    @Test
    void askWithoutAModelExplainsHowToConfigureOne() throws IOException {
        String out = session(repl(null), "ask how many students?\n");

        assertTrue(out.contains("NOT CONFIGURED"), out);
    }

    @Test
    void askShowsTheTraceAndTheAnswer() throws IOException {
        ScriptedChatModel model = ScriptedChatModel.of(
                runQuery("c1", "SELECT COUNT(*) FROM students"), answer("There are 20 students."));
        String out = session(repl(model), "ask How many students are there?\n");

        assertTrue(out.contains("[tool] run_query ok"), out);
        assertTrue(out.contains("There are 20 students."), out);
    }

    @Test
    void quitStopsReadingInput() throws IOException {
        String out = session(repl(null), "\\q\nSELECT COUNT(*) FROM students\n");

        assertFalse(out.contains("20"), out);
        assertEquals("", out);
    }
}
