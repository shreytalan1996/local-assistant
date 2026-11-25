package com.localrag.assistant.fast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything known about one repository: a keyword index over its files and a short overview.
 * Reading and tokenizing the files runs on all CPU cores, so building it takes seconds.
 */
final class RepoIndex {

    record Options(boolean useGit, boolean includeTests, int window, int overlap) {
        static Options defaults() {
            return new Options(true, true, 30, 5);
        }
    }

    record Stats(int files, int chunks, long millis) {
    }

    private static final int PATH_WEIGHT = 3;
    private static final int MAX_PER_FILE = 2;
    private static final Set<String> ROOT_FILES = Set.of("pom.xml", "package.json", "README.md", "readme.md");

    private final Path root;
    private final Bm25Index index;
    private final String overview;
    private final Stats stats;
    private final List<Chunk> chunkList;
    private final Map<Chunk, Integer> chunkIds = new IdentityHashMap<>();
    private volatile VectorIndex vectors;

    private RepoIndex(Path root, Bm25Index index, List<Chunk> chunkList, String overview, Stats stats) {
        this.root = root;
        this.index = index;
        this.chunkList = chunkList;
        this.overview = overview;
        this.stats = stats;
        for (int i = 0; i < chunkList.size(); i++) {
            chunkIds.put(chunkList.get(i), i);
        }
    }

    Path root() {
        return root;
    }

    String overview() {
        return overview;
    }

    Stats stats() {
        return stats;
    }

    // ------------------------------------------------------------------ building

    private record LoadedFile(String path, List<Chunk> chunks, List<Tokenizer.Counted> counts,
                              JavaSourceSummary summary, String rootFileContent) {
    }

    static RepoIndex build(Path root, Options options) throws IOException {
        long start = System.currentTimeMillis();
        Path base = root.toAbsolutePath().normalize();
        List<String> paths = RepoScanner.listFiles(base, options.useGit(), options.includeTests());

        List<LoadedFile> loaded = paths.parallelStream()
                .map(path -> load(base, path, options))
                .filter(Objects::nonNull)
                .toList();

        Bm25Index index = new Bm25Index();
        List<Chunk> chunkList = new ArrayList<>();
        List<String> indexedPaths = new ArrayList<>();
        List<JavaSourceSummary> javaSummaries = new ArrayList<>();
        Map<String, String> rootFiles = new HashMap<>();
        for (LoadedFile file : loaded) {
            indexedPaths.add(file.path());
            if (file.summary() != null) {
                javaSummaries.add(file.summary());
            }
            if (file.rootFileContent() != null) {
                rootFiles.put(file.path().equalsIgnoreCase("readme.md") ? "README.md" : file.path(), file.rootFileContent());
            }
            for (int i = 0; i < file.chunks().size(); i++) {
                index.add(file.chunks().get(i), file.counts().get(i));
                chunkList.add(file.chunks().get(i));
            }
        }

        String name = base.getFileName() == null ? "repository" : base.getFileName().toString();
        String overview = OverviewBuilder.build(name, indexedPaths, javaSummaries, rootFiles);
        Stats stats = new Stats(indexedPaths.size(), index.size(), System.currentTimeMillis() - start);
        return new RepoIndex(base, index, chunkList, overview, stats);
    }

    /** Reads one file and turns it into chunks with term counts. Runs on a worker thread. */
    private static LoadedFile load(Path base, String relative, Options options) {
        try {
            byte[] bytes = Files.readAllBytes(base.resolve(relative));
            if (looksBinary(bytes)) {
                return null;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (looksMinified(text)) {
                return null;
            }

            List<Chunk> chunks = new ArrayList<>();
            JavaSourceSummary summary = null;
            if (relative.endsWith(".java")) {
                summary = JavaSourceSummary.of(relative, text);
                chunks.add(new Chunk(relative, 0, 0, summary.toText(), true));
            }
            chunks.addAll(Chunker.chunk(relative, text, options.window(), options.overlap()));

            List<Tokenizer.Counted> counts = new ArrayList<>(chunks.size());
            for (Chunk chunk : chunks) {
                counts.add(Tokenizer.count(chunk.text(), relative, PATH_WEIGHT));
            }
            String rootContent = ROOT_FILES.contains(relative) ? text : null;
            return new LoadedFile(relative, chunks, counts, summary, rootContent);
        } catch (IOException | RuntimeException e) {
            return null;   // unreadable file: leave it out
        }
    }

    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 4096);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksMinified(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return text.length() / lines > 300;
    }

    // ------------------------------------------------------------------ searching

    /**
     * Finds the chunks that best match a question.
     *
     * @param question      the user's question
     * @param followUpOf    the previous question, used when the new one is too short to search on its own
     * @param maxChunks     how many chunks to return at most
     * @param maxChars      total size limit of the returned chunks
     */
    List<Bm25Index.Hit> search(String question, String followUpOf, int maxChunks, int maxChars) {
        List<String> terms = Tokenizer.tokens(question);
        if (distinctCount(terms) < 2 && followUpOf != null) {
            terms = new ArrayList<>(terms);
            terms.addAll(Tokenizer.tokens(followUpOf));
        }
        Map<String, Double> weighted = QueryExpander.weightedTerms(terms);

        int pool = Math.max(maxChunks * 8, 40);
        List<Bm25Index.Hit> candidates = fuse(index.search(weighted, pool), searchText(question, followUpOf), pool);
        List<Bm25Index.Hit> chosen = new ArrayList<>();
        Map<String, Integer> perFile = new HashMap<>();
        int chars = 0;
        for (Bm25Index.Hit hit : candidates) {
            if (chosen.size() >= maxChunks) {
                break;
            }
            int used = perFile.getOrDefault(hit.chunk().path(), 0);
            int size = hit.chunk().text().length();
            if (used >= MAX_PER_FILE || (chars + size > maxChars && !chosen.isEmpty())) {
                continue;
            }
            perFile.put(hit.chunk().path(), used + 1);
            chosen.add(hit);
            chars += size;
        }
        return chosen;
    }

    // ------------------------------------------------------------------ search by meaning

    private static final int RRF_K = 60;

    /**
     * Starts "search by meaning" next to the keyword search. Vectors already in the disk cache are used at once;
     * the others can be made in the background. Searching works before, during and after that.
     */
    VectorIndex enableVectors(OllamaClient client, String embeddingModel, VectorCache cache) {
        VectorIndex v = new VectorIndex(chunkList, client, embeddingModel, cache);
        v.loadCached();
        vectors = v;
        return v;
    }

    /** One line describing the state of search by meaning. */
    String vectorStatus() {
        VectorIndex v = vectors;
        if (v == null) {
            return "Search by meaning: off (keyword search only)";
        }
        String text = "Search by meaning: " + v.ready() + "/" + v.total() + " chunks ready ("
                + (v.total() == 0 ? 100 : v.ready() * 100 / v.total()) + "%)";
        if (v.error() != null) {
            text += ", stopped: " + v.error();
        }
        return text;
    }

    /** Lets background embedding wait while the chat model is answering, so the answer is not slowed down. */
    void pauseEmbedding(boolean paused) {
        VectorIndex v = vectors;
        if (v != null) {
            v.pause(paused);
        }
    }

    private static String searchText(String question, String followUpOf) {
        return followUpOf != null && distinctCount(Tokenizer.tokens(question)) < 2 ? question + " " + followUpOf : question;
    }

    /**
     * Combines the keyword ranking with the meaning ranking (reciprocal rank fusion: a chunk that is high in
     * either list, or in both, comes first). Scores are shown as 0-100, where 100 means first in both lists.
     */
    private List<Bm25Index.Hit> fuse(List<Bm25Index.Hit> keywordHits, String query, int pool) {
        VectorIndex v = vectors;
        if (v == null) {
            return keywordHits;
        }
        List<Integer> byMeaning = v.search(query, pool);
        if (byMeaning.isEmpty()) {
            return keywordHits;
        }
        Map<Integer, Double> score = new HashMap<>();
        for (int rank = 0; rank < keywordHits.size(); rank++) {
            Integer id = chunkIds.get(keywordHits.get(rank).chunk());
            if (id != null) {
                score.merge(id, 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        for (int rank = 0; rank < byMeaning.size(); rank++) {
            score.merge(byMeaning.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        double best = 2.0 / (RRF_K + 1);
        List<Bm25Index.Hit> fused = new ArrayList<>();
        score.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(pool)
                .forEach(e -> fused.add(new Bm25Index.Hit(chunkList.get(e.getKey()), e.getValue() / best * 100)));
        return fused;
    }

    private static int distinctCount(List<String> terms) {
        return (int) terms.stream().distinct().count();
    }
}
