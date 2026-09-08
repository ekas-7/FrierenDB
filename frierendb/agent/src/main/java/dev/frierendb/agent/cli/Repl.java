package dev.frierendb.agent.cli;

import dev.frierendb.agent.core.AgentRun;
import dev.frierendb.agent.core.AnalystAgent;
import dev.frierendb.agent.core.TraceEvent;
import dev.frierendb.engine.Database;
import dev.frierendb.engine.FrierenException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Interactive loop. Lines starting with {@code ask} go to the agent (read-only tools); anything
 * else is SQL typed by the human user and runs with full privileges.
 */
public final class Repl {

    private static final String HELP = """
            Commands:
              ask <question>   ask the AI analyst (read-only access to your data)
              <sql>            run a single SQL statement yourself
              \\tables          list tables and columns
              \\help            show this help
              \\q               quit""";

    private final Database db;
    private final AnalystAgent agent;
    private final String agentUnavailableReason;
    private final PrintStream out;

    /** {@code agent} may be null; then {@code agentUnavailableReason} is shown for {@code ask}. */
    public Repl(Database db, AnalystAgent agent, String agentUnavailableReason, PrintStream out) {
        this.db = db;
        this.agent = agent;
        this.agentUnavailableReason = agentUnavailableReason;
        this.out = out;
    }

    public void run(BufferedReader in, boolean interactive) throws IOException {
        while (true) {
            if (interactive) {
                out.print("frieren> ");
                out.flush();
            }
            String line = in.readLine();
            if (line == null) {
                return;
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!handle(line)) {
                return;
            }
        }
    }

    /** Returns false when the session should end. */
    boolean handle(String line) {
        try {
            if (line.equals("\\q") || line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                return false;
            }
            if (line.equals("\\help")) {
                out.println(HELP);
            } else if (line.equals("\\tables")) {
                printTables();
            } else if (line.regionMatches(true, 0, "ask ", 0, 4)) {
                ask(line.substring(4).strip());
            } else {
                out.println(ResultPrinter.format(db.execute(line)));
            }
        } catch (FrierenException e) {
            out.println("Error: " + e.getMessage());
        } catch (RuntimeException e) {
            out.println("Internal error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return true;
    }

    private void printTables() {
        var names = db.tableNames();
        if (names.isEmpty()) {
            out.println("(no tables)");
        }
        for (String name : names) {
            out.println(name + "(" + String.join(", ", db.tableSchema(name).columns().stream()
                    .map(c -> c.name() + " " + c.type()).toList()) + ")");
        }
    }

    private void ask(String question) {
        if (agent == null) {
            out.println(agentUnavailableReason);
            return;
        }
        AgentRun run = agent.ask(question);
        for (TraceEvent e : run.trace()) {
            out.println("  [" + e.kind().name().toLowerCase() + "] " + e.name() + " "
                    + (e.ok() ? "ok" : "failed") + " (" + e.millis() + " ms)"
                    + (e.kind() == TraceEvent.Kind.TOOL ? " " + e.detail() : ""));
        }
        if (run.status() != AgentRun.Status.ANSWERED) {
            out.println("Run stopped: " + run.status());
        }
        out.println(run.answer());
    }
}
