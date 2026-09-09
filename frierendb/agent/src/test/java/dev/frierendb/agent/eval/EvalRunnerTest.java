package dev.frierendb.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.agent.core.AgentConfig;
import dev.frierendb.agent.core.AnalystAgent;
import dev.frierendb.agent.demo.DemoData;
import dev.frierendb.agent.model.ChatModel;
import dev.frierendb.agent.model.Message;
import dev.frierendb.agent.model.ModelResponse;
import dev.frierendb.agent.model.ToolSpec;
import dev.frierendb.agent.support.ScriptedChatModel;
import dev.frierendb.engine.Database;
import dev.frierendb.engine.exec.QueryOptions;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * These tests validate the harness and the bundled dataset with scripted models. They say nothing
 * about how accurate a real LLM is.
 */
class EvalRunnerTest {

    @TempDir
    Path dir;

    private Database db;
    private List<EvalCase> cases;

    @BeforeEach
    void setUp() {
        db = Database.open(dir);
        DemoData.seedIfEmpty(db);
        cases = EvalRunner.loadCases(getClass().getResourceAsStream("/eval/university-questions.json"));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private AnalystAgent agent(ChatModel model) {
        return new AnalystAgent(model, db, AgentConfig.defaults());
    }

    /** Looks up the reference SQL for the question and runs it, standing in for a perfect model. */
    private static ChatModel oracle(List<EvalCase> cases) {
        Map<String, String> goldByQuestion = cases.stream()
                .collect(Collectors.toMap(EvalCase::question, EvalCase::goldSql));
        return new ChatModel() {
            @Override
            public ModelResponse chat(List<Message> messages, List<ToolSpec> tools) {
                boolean alreadyQueried = messages.stream().anyMatch(m -> m instanceof Message.ToolResultMessage);
                if (alreadyQueried) {
                    return ScriptedChatModel.answer("done");
                }
                String question = messages.stream()
                        .filter(m -> m instanceof Message.UserMessage)
                        .map(m -> ((Message.UserMessage) m).content())
                        .reduce((a, b) -> b).orElseThrow();
                return ScriptedChatModel.runQuery("c1", goldByQuestion.get(question));
            }
        };
    }

    /** Runs one fixed query per question, then answers; every question gets the same query. */
    private static ChatModel fixedQuery(String sql) {
        return (messages, tools) -> messages.stream().anyMatch(m -> m instanceof Message.ToolResultMessage)
                ? ScriptedChatModel.answer("done")
                : ScriptedChatModel.runQuery("c1", sql);
    }

    @Test
    void everyReferenceQueryRunsAndReturnsRows() {
        assertTrue(cases.size() >= 10);
        for (EvalCase c : cases) {
            var result = db.query(c.goldSql(), QueryOptions.unlimited());
            assertFalse(result.rows().isEmpty(), c.question());
        }
    }

    @Test
    void referenceAnswersMatchKnownFacts() {
        assertEquals("20", scalar("SELECT COUNT(*) FROM students"));
        assertEquals("2", scalar("SELECT COUNT(*) FROM students WHERE gpa IS NULL"));
        assertEquals("Intro to Databases", scalar(cases.get(8).goldSql()));
        assertEquals("Nia Cole", scalar(cases.get(2).goldSql()));
    }

    private String scalar(String sql) {
        return db.query(sql, QueryOptions.unlimited()).rows().get(0).get(0).toString();
    }

    @Test
    void aModelThatRunsTheReferenceQueriesScoresPerfectly() {
        EvalReport report = new EvalRunner(agent(oracle(cases)), db).run(cases);

        assertEquals(cases.size(), report.passed(), report.summary());
    }

    @Test
    void aModelThatGuessesWrongScoresZero() {
        ChatModel guesser = fixedQuery("SELECT COUNT(*) FROM courses");
        EvalReport report = new EvalRunner(agent(guesser), db).run(cases.subList(0, 2));

        assertEquals(0, report.passed());
        assertTrue(report.summary().contains("0/2 correct"));
    }

    @Test
    void answeringWithoutQueryingFails() {
        ChatModel liar = ScriptedChatModel.repeating(ScriptedChatModel.answer("There are 20 students."));
        EvalReport report = new EvalRunner(agent(liar), db).run(cases.subList(0, 1));

        assertEquals(0, report.passed());
        assertTrue(report.results().get(0).detail().contains("without running a successful query"));
    }

    @Test
    void rowOrderIsIgnoredUnlessTheReferenceSortsRows() {
        EvalCase unordered = new EvalCase("Math courses?", "SELECT title FROM courses WHERE dept = 'Math' ORDER BY title");
        EvalCase unorderedGold = new EvalCase("Math courses?", "SELECT title FROM courses WHERE dept = 'Math'");
        ChatModel reversed = fixedQuery("SELECT title FROM courses WHERE dept = 'Math' ORDER BY title DESC");

        assertEquals(0, new EvalRunner(agent(reversed), db).run(List.of(unordered)).passed());
        assertEquals(1, new EvalRunner(agent(reversed), db).run(List.of(unorderedGold)).passed());
    }

    @Test
    void numericTypesCompareByValue() {
        EvalCase c = new EvalCase("Ten?", "SELECT 10.0 FROM courses LIMIT 1");
        ChatModel model = fixedQuery("SELECT 10 FROM courses LIMIT 1");

        assertEquals(1, new EvalRunner(agent(model), db).run(List.of(c)).passed());
    }

    @Test
    void aBrokenReferenceQueryIsReportedNotThrown() {
        EvalCase broken = new EvalCase("?", "SELECT nope FROM nowhere");
        EvalReport report = new EvalRunner(agent(ScriptedChatModel.of()), db).run(List.of(broken));

        assertEquals(0, report.passed());
        assertTrue(report.results().get(0).detail().contains("reference query is broken"));
    }
}
