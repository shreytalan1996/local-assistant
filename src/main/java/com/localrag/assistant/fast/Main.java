package com.localrag.assistant.fast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Command line entry point: loads a repository, then answers questions about it. */
public final class Main {

    private static final String HELP = """
            repo-assistant: ask questions about any code repository, using a local model (Ollama).

            Usage:
              repo-assistant [folder] [options]

            Options:
              --ask "question"     answer one question and exit
              --search "words"     show matching code without using the model, and exit
              --model NAME         Ollama model (default: llama3.2:3b, or $RA_MODEL)
              --ollama URL         Ollama address (default: http://localhost:11434, or $OLLAMA_HOST)
              --chunks N           code excerpts sent to the model per question (default: 5)
              --ctx N              model context size in tokens (default: 4096)
              --embed-model NAME   embedding model for search by meaning (default: nomic-embed-text, or $RA_EMBED_MODEL)
              --no-vectors         keyword search only (no embedding model needed)
              --prepare            embed the whole repository now (saves it to the cache), then exit
              --no-tests           leave out test files
              --no-git             do not use git to list files
              --help               show this help

            In the chat:
              /search words        show matching code (no model needed)
              /sources             show which code the last answer was based on
              /status              show how much of the repository is ready for search by meaning
              /overview            show the repository overview
              /clear               forget the conversation
              exit                 quit
            """;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage() + "\n\nRun with --help for usage.");
            System.exit(2);
            return;
        }
        if (config.help) {
            System.out.print(HELP);
            return;
        }

        Path folder = Path.of(config.folder).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) {
            System.err.println("Not a folder: " + folder);
            System.exit(2);
            return;
        }

        OllamaClient client = new OllamaClient(config.ollama, config.model);
        boolean needsModel = config.search == null;
        if (needsModel) {
            Thread warm = new Thread(client::warmUp, "model-warm-up");
            warm.setDaemon(true);
            warm.start();   // loads the model while the repository is being indexed
        }

        RepoIndex repo = RepoIndex.build(folder, new RepoIndex.Options(config.useGit, config.includeTests, 30, 5));
        RepoIndex.Stats stats = repo.stats();
        System.out.printf("Loaded %s: %d files, %d searchable chunks in %.1f s%n",
                folder.getFileName(), stats.files(), stats.chunks(), stats.millis() / 1000.0);

        VectorIndex vectorIndex = setUpVectors(config, client, repo, folder);

        Assistant assistant = new Assistant(repo, client,
                new Assistant.Settings(config.chunks, Assistant.Settings.defaults().contextChars(), config.ctx));

        if (config.search != null) {
            printHits(assistant.search(config.search));
            return;
        }

        if (config.prepare) {
            prepare(repo, vectorIndex);
            return;
        }

        boolean modelReady = checkModel(client);
        if (config.ask != null) {
            answer(assistant, config.ask, modelReady);
            return;
        }

        System.out.println("Ask a question about the code. Type /help for commands, exit to quit.");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("\n> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) {
                return;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                return;
            }
            if (line.equals("/help")) {
                System.out.print(HELP);
            } else if (line.equals("/status")) {
                System.out.println(repo.vectorStatus());
            } else if (line.equals("/overview")) {
                System.out.println(repo.overview());
            } else if (line.equals("/sources")) {
                printHits(assistant.lastSources());
            } else if (line.equals("/clear")) {
                assistant.forget();
                System.out.println("Conversation cleared.");
            } else if (line.startsWith("/search")) {
                printHits(assistant.search(line.substring("/search".length()).trim()));
            } else {
                answer(assistant, line, modelReady);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Search by meaning is optional. Cached vectors are used straight away. When running interactively the
     * missing ones are made in the background; one-shot commands never wait for that.
     */
    private static VectorIndex setUpVectors(Config config, OllamaClient client, RepoIndex repo, Path folder) {
        if (!config.vectors) {
            return null;
        }
        List<String> models = client.installedModels();
        boolean available = models != null && models.stream().anyMatch(m -> m.equals(config.embedModel)
                || m.equals(config.embedModel + ":latest"));
        if (!available) {
            System.out.println(models == null
                    ? "Ollama is not reachable: keyword search only."
                    : "Search by meaning is off. For better answers run once: ollama pull " + config.embedModel);
            return null;
        }
        VectorIndex vectorIndex = repo.enableVectors(client, config.embedModel,
                new VectorCache(VectorCache.defaultFile(folder, config.embedModel)));
        boolean interactive = config.ask == null && config.search == null && !config.prepare;
        if (interactive && vectorIndex.ready() < vectorIndex.total()) {
            vectorIndex.startInBackground();
        }
        System.out.println(repo.vectorStatus() + (interactive && vectorIndex.ready() < vectorIndex.total()
                ? " - the rest is prepared in the background; answers get better as it fills (see /status)." : ""));
        return vectorIndex;
    }

    private static void prepare(RepoIndex repo, VectorIndex vectorIndex) {
        if (vectorIndex == null) {
            System.out.println("Nothing to prepare: search by meaning is not available.");
            return;
        }
        long start = System.currentTimeMillis();
        int total = vectorIndex.total();
        int[] last = {-1};
        vectorIndex.embedMissing(done -> {
            int percent = total == 0 ? 100 : done * 100 / total;
            if (percent / 5 != last[0] / 5) {
                last[0] = percent;
                System.out.printf("  %d%% (%d/%d chunks, %d s)%n", percent, done, total, (System.currentTimeMillis() - start) / 1000);
            }
        });
        System.out.println(repo.vectorStatus());
    }

    private static boolean checkModel(OllamaClient client) {
        List<String> models = client.installedModels();
        if (models == null) {
            System.out.println("Ollama is not reachable, so only /search works. Start it with: brew services start ollama");
            return false;
        }
        boolean installed = models.stream().anyMatch(m -> m.equals(client.model())
                || m.equals(client.model() + ":latest") || m.startsWith(client.model() + ":") && !client.model().contains(":"));
        if (!installed) {
            System.out.println("Model '" + client.model() + "' is not installed. Run: ollama pull " + client.model());
            return false;
        }
        return true;
    }

    private static void answer(Assistant assistant, String question, boolean modelReady) {
        if (!modelReady) {
            System.out.println("(No model available. Showing the best matching code instead.)");
            printHits(assistant.search(question));
            return;
        }
        try {
            Assistant.Answer answer = assistant.ask(question, System.out::print);
            System.out.println();
            if (!answer.sources().isEmpty()) {
                System.out.println("\nBased on: " + String.join(", ",
                        answer.sources().stream().map(h -> h.chunk().label()).toList()));
            }
        } catch (IOException e) {
            System.out.println("\nCould not get an answer: " + e.getMessage());
        }
    }

    private static void printHits(List<Bm25Index.Hit> hits) {
        if (hits.isEmpty()) {
            System.out.println("Nothing matched.");
            return;
        }
        for (Bm25Index.Hit hit : hits) {
            System.out.printf("%n%s  (score %.1f)%n", hit.chunk().label(), hit.score());
            String[] lines = hit.chunk().text().split("\n");
            for (int i = 0; i < Math.min(4, lines.length); i++) {
                System.out.println("    " + lines[i]);
            }
        }
    }

    // ------------------------------------------------------------------ arguments

    private static final class Config {
        String folder = ".";
        String ask;
        String search;
        String model = System.getenv().getOrDefault("RA_MODEL", "llama3.2:3b");
        String ollama = defaultOllama();
        int chunks = 5;
        int ctx = 4096;
        boolean includeTests = true;
        boolean useGit = true;
        boolean vectors = true;
        boolean prepare;
        String embedModel = System.getenv().getOrDefault("RA_EMBED_MODEL", "nomic-embed-text");
        boolean help;

        static Config parse(String[] args) {
            Config c = new Config();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> c.help = true;
                    case "--no-tests" -> c.includeTests = false;
                    case "--no-git" -> c.useGit = false;
                    case "--no-vectors" -> c.vectors = false;
                    case "--prepare" -> c.prepare = true;
                    case "--embed-model" -> c.embedModel = value(args, ++i, arg);
                    case "--ask" -> c.ask = value(args, ++i, arg);
                    case "--search" -> c.search = value(args, ++i, arg);
                    case "--model" -> c.model = value(args, ++i, arg);
                    case "--ollama" -> c.ollama = value(args, ++i, arg);
                    case "--chunks" -> c.chunks = number(value(args, ++i, arg), arg);
                    case "--ctx" -> c.ctx = number(value(args, ++i, arg), arg);
                    default -> {
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option: " + arg);
                        }
                        c.folder = arg;
                    }
                }
            }
            return c;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[index];
        }

        private static int number(String text, String option) {
            try {
                int n = Integer.parseInt(text);
                if (n < 1) {
                    throw new NumberFormatException();
                }
                return n;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " needs a positive number, got: " + text);
            }
        }

        private static String defaultOllama() {
            String host = System.getenv("OLLAMA_HOST");
            if (host == null || host.isBlank()) {
                return "http://localhost:11434";
            }
            return host.startsWith("http") ? host : "http://" + host;
        }
    }
}
