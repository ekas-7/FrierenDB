package dev.frierendb.agent.cli;

import dev.frierendb.agent.core.AgentConfig;
import dev.frierendb.agent.core.AnalystAgent;
import dev.frierendb.agent.demo.DemoData;
import dev.frierendb.agent.eval.EvalRunner;
import dev.frierendb.agent.model.ChatModel;
import dev.frierendb.agent.model.OpenAiCompatibleModel;
import dev.frierendb.engine.Database;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public final class Main {

    private static final String USAGE = """
            Usage: frierendb [--db <dir>] [--demo] [--eval]

              --db <dir>   database directory (default: ./frieren-data)
              --demo       load the sample university dataset if the database is empty
              --eval       run the bundled evaluation questions against the configured model (implies --demo)

            The AI analyst is configured through environment variables:
              FRIEREN_LLM_MODEL     model name (required for `ask`)
              FRIEREN_LLM_BASE_URL  OpenAI-compatible endpoint (default: https://api.openai.com/v1)
              FRIEREN_LLM_API_KEY   API key (omit for local servers such as Ollama)
            """;

    private Main() {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of("frieren-data");
        boolean demo = false;
        boolean eval = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--db" -> {
                    if (i + 1 >= args.length) {
                        fail("--db needs a directory");
                    }
                    dir = Path.of(args[++i]);
                }
                case "--demo" -> demo = true;
                case "--eval" -> {
                    eval = true;
                    demo = true;
                }
                case "--help", "-h" -> {
                    System.out.print(USAGE);
                    return;
                }
                default -> fail("Unknown argument: " + args[i]);
            }
        }

        try (Database db = Database.open(dir)) {
            if (demo && DemoData.seedIfEmpty(db)) {
                System.out.println("Loaded the demo dataset.");
            }
            Map<String, String> env = System.getenv();
            ChatModel model = modelFromEnv(env);
            AnalystAgent agent = model == null ? null : new AnalystAgent(model, db, AgentConfig.defaults());
            String unavailable = "The AI analyst is not configured. Set FRIEREN_LLM_MODEL (and FRIEREN_LLM_API_KEY"
                    + " or FRIEREN_LLM_BASE_URL). Run with --help for details.";

            if (eval) {
                if (agent == null) {
                    fail(unavailable);
                }
                try (InputStream in = Main.class.getResourceAsStream("/eval/university-questions.json")) {
                    var report = new EvalRunner(agent, db).run(EvalRunner.loadCases(in));
                    System.out.println(report.summary());
                }
                return;
            }

            System.out.println("FrierenDB. Type \\help for commands.");
            boolean interactive = System.console() != null;
            var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            new Repl(db, agent, unavailable, System.out).run(in, interactive);
        }
    }

    static ChatModel modelFromEnv(Map<String, String> env) {
        String model = env.get("FRIEREN_LLM_MODEL");
        if (model == null || model.isBlank()) {
            return null;
        }
        String base = env.getOrDefault("FRIEREN_LLM_BASE_URL", "https://api.openai.com/v1");
        String key = env.get("FRIEREN_LLM_API_KEY");
        return new OpenAiCompatibleModel(new OpenAiCompatibleModel.Config(
                URI.create(base), key == null || key.isBlank() ? null : key, model, Duration.ofSeconds(60)));
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
