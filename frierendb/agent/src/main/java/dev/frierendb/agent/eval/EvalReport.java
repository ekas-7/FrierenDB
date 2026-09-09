package dev.frierendb.agent.eval;

import java.util.List;

public record EvalReport(List<CaseResult> results) {

    public record CaseResult(EvalCase evalCase, boolean passed, String detail) {}

    public EvalReport {
        results = List.copyOf(results);
    }

    public int total() {
        return results.size();
    }

    public long passed() {
        return results.stream().filter(CaseResult::passed).count();
    }

    public String summary() {
        StringBuilder out = new StringBuilder();
        for (CaseResult r : results) {
            out.append(r.passed() ? "PASS  " : "FAIL  ").append(r.evalCase().question());
            if (!r.passed()) {
                out.append("\n      ").append(r.detail());
            }
            out.append('\n');
        }
        out.append(passed()).append('/').append(total()).append(" correct");
        return out.toString();
    }
}
