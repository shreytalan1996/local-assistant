package com.localrag.assistant.fast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Answers questions about one repository: finds the relevant code, then asks the local model. */
final class Assistant {

    record Settings(int chunks, int contextChars, int contextTokens) {
        static Settings defaults() {
            return new Settings(5, 7000, 4096);
        }
    }

    record Answer(String text, List<Bm25Index.Hit> sources) {
    }

    private static final int HISTORY_PAIRS = 2;
    private static final int HISTORY_ANSWER_CHARS = 500;

    private static final String INSTRUCTIONS = """
            You are a code assistant for the repository described below.
            Answer using only the overview and the code excerpts that come with each question.
            Mention the file paths (and line numbers) your answer is based on.
            If the excerpts do not contain the answer, say so plainly and suggest which files or search words to try.
            Be concise and concrete. Do not invent files, classes or behaviour.
            """;

    private final RepoIndex repo;
    private final OllamaClient client;
    private final Settings settings;
    private final List<OllamaClient.Message> history = new ArrayList<>();
    private String lastQuestion;
    private List<Bm25Index.Hit> lastSources = List.of();

    Assistant(RepoIndex repo, OllamaClient client, Settings settings) {
        this.repo = repo;
        this.client = client;
        this.settings = settings;
    }

    List<Bm25Index.Hit> lastSources() {
        return lastSources;
    }

    void forget() {
        history.clear();
        lastQuestion = null;
    }

    /** Searches the repository without involving the model. */
    List<Bm25Index.Hit> search(String query) {
        return repo.search(query, null, settings.chunks() * 2, settings.contextChars() * 2);
    }

    Answer ask(String question, Consumer<String> onToken) throws IOException {
        List<Bm25Index.Hit> hits = repo.search(question, lastQuestion, settings.chunks(), settings.contextChars());
        lastSources = hits;

        List<OllamaClient.Message> messages = new ArrayList<>();
        messages.add(new OllamaClient.Message("system", INSTRUCTIONS + "\n" + repo.overview()));
        messages.addAll(history);
        messages.add(new OllamaClient.Message("user", withContext(question, hits)));

        String reply;
        repo.pauseEmbedding(true);   // do not compete with the answer for the CPU
        try {
            reply = client.chat(messages, settings.contextTokens(), onToken);
        } finally {
            repo.pauseEmbedding(false);
        }

        history.add(new OllamaClient.Message("user", question));
        history.add(new OllamaClient.Message("assistant", clip(reply, HISTORY_ANSWER_CHARS)));
        while (history.size() > HISTORY_PAIRS * 2) {
            history.remove(0);
            history.remove(0);
        }
        lastQuestion = question;
        return new Answer(reply, hits);
    }

    static String withContext(String question, List<Bm25Index.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        if (hits.isEmpty()) {
            sb.append("No code excerpts matched this question.\n\n");
        } else {
            sb.append("Code excerpts:\n\n");
            for (Bm25Index.Hit hit : hits) {
                sb.append("### ").append(hit.chunk().label()).append('\n')
                        .append(hit.chunk().text()).append('\n');
            }
        }
        return sb.append("Question: ").append(question).toString();
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
