package dev.frierendb.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.frierendb.agent.core.AgentRun;
import dev.frierendb.agent.core.AnalystAgent;
import dev.frierendb.engine.Database;
import dev.frierendb.engine.FrierenException;
import dev.frierendb.engine.exec.QueryOptions;
import dev.frierendb.engine.exec.QueryResult;
import dev.frierendb.engine.types.Value;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Scores an agent by execution accuracy: the last successful query it ran must return the same
 * values as the reference query. Row order matters only when the reference query has ORDER BY.
 */
public final class EvalRunner {

    private final AnalystAgent agent;
    private final Database db;

    public EvalRunner(AnalystAgent agent, Database db) {
        this.agent = agent;
        this.db = db;
    }

    public static List<EvalCase> loadCases(InputStream in) {
        try {
            return new ObjectMapper().readValue(in, new TypeReference<List<EvalCase>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public EvalReport run(List<EvalCase> cases) {
        List<EvalReport.CaseResult> results = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            results.add(score(evalCase));
        }
        return new EvalReport(results);
    }

    private EvalReport.CaseResult score(EvalCase evalCase) {
        List<List<String>> expected;
        try {
            expected = normalize(db.query(evalCase.goldSql(), QueryOptions.unlimited()),
                    evalCase.goldSql().toLowerCase(Locale.ROOT).contains("order by"));
        } catch (FrierenException e) {
            return new EvalReport.CaseResult(evalCase, false, "reference query is broken: " + e.getMessage());
        }
        AgentRun run = agent.ask(evalCase.question());
        if (run.status() != AgentRun.Status.ANSWERED) {
            return new EvalReport.CaseResult(evalCase, false, "run ended as " + run.status() + ": " + run.answer());
        }
        List<String> queries = run.successfulQueries();
        if (queries.isEmpty()) {
            return new EvalReport.CaseResult(evalCase, false, "the agent answered without running a successful query");
        }
        String sql = queries.get(queries.size() - 1);
        List<List<String>> actual;
        try {
            actual = normalize(db.query(sql, QueryOptions.unlimited()),
                    evalCase.goldSql().toLowerCase(Locale.ROOT).contains("order by"));
        } catch (FrierenException e) {
            return new EvalReport.CaseResult(evalCase, false, "could not re-run the agent's query: " + e.getMessage());
        }
        if (actual.equals(expected)) {
            return new EvalReport.CaseResult(evalCase, true, "");
        }
        return new EvalReport.CaseResult(evalCase, false,
                "expected " + expected + " but the agent's query (" + sql + ") returned " + actual);
    }

    private static List<List<String>> normalize(QueryResult result, boolean ordered) {
        List<List<String>> rows = new ArrayList<>();
        for (var row : result.rows()) {
            rows.add(row.values().stream().map(EvalRunner::canonical).toList());
        }
        if (!ordered) {
            rows.sort((a, b) -> String.join("\u0000", a).compareTo(String.join("\u0000", b)));
        }
        return Collections.unmodifiableList(rows);
    }

    private static String canonical(Value value) {
        return switch (value) {
            case Value.NullValue n -> "NULL";
            case Value.IntValue i -> String.format(Locale.ROOT, "%.4f", (double) i.value());
            case Value.DoubleValue d -> String.format(Locale.ROOT, "%.4f", d.value());
            default -> value.toString();
        };
    }
}
