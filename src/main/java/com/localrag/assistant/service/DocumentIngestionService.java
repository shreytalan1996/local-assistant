package com.localrag.assistant.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.localrag.assistant.config.RagProperties;

/**
 * Phase 1 of RAG: read the source material, split it into chunks, embed and store them.
 *
 * <p>Two sources are supported:
 * <ul>
 *   <li>a book ({@code .pdf} or {@code .txt})</li>
 *   <li>a software project folder (source code, docs and config files)</li>
 * </ul>
 *
 * <p>Indexing is the slow part (every chunk goes through the local embedding model). So the result is saved
 * to disk, in a workspace folder for that source ({@link IndexWorkspace}). On the next start the saved index is
 * loaded from the file, and when the fingerprint says nothing changed, no chunking or embedding happens at all.
 */
@Service
public class DocumentIngestionService {

    /** Outcome of an indexing request. {@code skipped} means the existing index was still up to date. */
    public record IngestionResult(int chunks, boolean skipped) {
        static IngestionResult indexed(int chunks) {
            return new IngestionResult(chunks, false);
        }

        static IngestionResult upToDate() {
            return new IngestionResult(0, true);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    // Project Gutenberg wraps every book in licence text between these markers
    private static final String GUTENBERG_START = "*** START OF";
    private static final String GUTENBERG_END = "*** END OF";

    private static final int EMBED_BATCH_SIZE = 25;

    private final WorkspaceVectorStore vectorStore;
    private final IndexWorkspace workspace;
    private final RagProperties properties;
    private final OverviewStore overviewStore;
    private final IndexState indexState;

    public DocumentIngestionService(WorkspaceVectorStore vectorStore,
                                    IndexWorkspace workspace,
                                    RagProperties properties,
                                    OverviewStore overviewStore,
                                    IndexState indexState) {
        this.vectorStore = vectorStore;
        this.workspace = workspace;
        this.properties = properties;
        this.overviewStore = overviewStore;
        this.indexState = indexState;
    }

    // ------------------------------------------------------------------ reuse what was saved last time

    /** Loads the index saved for the source used last time (no embedding). Empty when there is none. */
    public Optional<Path> openLast() {
        Optional<Path> last = workspace.useLast();
        last.ifPresent(dir -> vectorStore.open(workspace.vectorFile()));
        return last;
    }

    // ------------------------------------------------------------------ book mode

    /**
     * Indexes the configured book, unless it was already indexed and saved, and has not changed.
     *
     * @param force index again even if nothing changed
     */
    public IngestionResult ingestBook(boolean force) {
        String path = properties.documentPath();
        String source = "book:" + path;
        String fingerprint = bookFingerprint(path);
        workspace.use(workspace.forBook(path));

        if (!force && canSkip(source, fingerprint, false)) {
            log.info("Book already indexed and unchanged, skipping. Use --reindex to force.");
            return IngestionResult.upToDate();
        }

        log.info("Reading book: {}", path);
        clearWorkspace();

        List<Document> documents = path.toLowerCase().endsWith(".txt") ? readText(path) : readPdf(path);
        List<Document> chunks = splitter().split(documents);
        addInBatches(chunks);

        vectorStore.save(workspace.vectorFile());
        if (fingerprint != null) {
            indexState.save(source, fingerprint);   // written last: it marks the saved index as complete
        }
        log.info("Stored {} chunks (from {} sections)", chunks.size(), documents.size());
        return IngestionResult.indexed(chunks.size());
    }

    // ------------------------------------------------------------------ project mode

    /**
     * Indexes a software project, unless it was already indexed and saved, and has not changed.
     * For every Java file it stores a short summary plus the content chunks, and it saves a project
     * overview (overview.md) that is used for broad questions, and a readable list of file summaries (files.md).
     *
     * @param force index again even if nothing changed
     */
    public IngestionResult ingestProject(Path root, boolean force) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("Not a directory: " + base);
        }
        log.info("Reading project: {}", base);

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> isIndexable(base, p))
                    .sorted()
                    .forEach(files::add);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("No indexable files found in " + base);
        }

        String source = "project:" + base;
        String fingerprint = IndexState.fingerprint(base, files, settingsKey());
        workspace.use(workspace.forProject(base));
        if (!force && canSkip(source, fingerprint, true)) {
            log.info("Project already indexed and unchanged ({} files), skipping. Use --reindex to force.", files.size());
            return IngestionResult.upToDate();
        }

        clearWorkspace();
        if (properties.quickIndex()) {
            log.info("Quick index: Java files are indexed as summaries, other files by their first part only.");
        }

        TokenTextSplitter splitter = splitter();
        List<Document> chunks = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<JavaSourceSummary> javaSummaries = new ArrayList<>();
        String pomXml = null;
        String readme = null;

        for (Path file : files) {
            String relative = base.relativize(file).toString().replace('\\', '/');
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException e) {
                log.warn("Skipping {} (not readable as UTF-8 text)", relative);
                continue;
            }
            paths.add(relative);

            if (relative.equals("pom.xml")) {
                pomXml = text;
            } else if (relative.equalsIgnoreCase("README.md")) {
                readme = text;
            }

            boolean isJava = relative.endsWith(".java");
            if (isJava) {
                JavaSourceSummary summary = JavaSourceSummary.of(relative, text);
                javaSummaries.add(summary);
                chunks.add(new Document(summary.toText(), Map.of("file", relative, "kind", "summary")));
            }

            List<Document> pieces = splitter.split(List.of(new Document(text, Map.of("file", relative))));
            if (properties.quickIndex()) {
                // Quick mode: a Java file is represented by its summary alone, any other file by its first part
                pieces = isJava ? List.of() : pieces.stream().limit(1).toList();
            }

            for (Document chunk : pieces) {
                // The model only sees chunk text, not metadata, so the file name is written into the text
                chunks.add(new Document("File: " + relative + "\n" + chunk.getText(), chunk.getMetadata()));
            }
        }

        addInBatches(chunks);

        String projectName = base.getFileName() == null ? "project" : base.getFileName().toString();
        vectorStore.save(workspace.vectorFile());
        overviewStore.save(ProjectOverviewBuilder.build(projectName, paths, javaSummaries, pomXml, readme));
        writeFileSummaries(projectName, javaSummaries);
        indexState.save(source, fingerprint);   // written last: it marks the saved index as complete

        log.info("Stored {} chunks from {} files ({} Java)", chunks.size(), paths.size(), javaSummaries.size());
        return IngestionResult.indexed(chunks.size());
    }

    private boolean isIndexable(Path base, Path file) {
        try {
            return ProjectFilePolicy.isIndexable(base.relativize(file), Files.size(file), properties.indexTests());
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ maintenance

    /** Deletes the saved index of the active source (the next start will index it again). */
    public void reset() {
        if (!workspace.hasCurrent()) {
            return;
        }
        clearWorkspace();
        log.info("Saved index cleared");
    }

    private void clearWorkspace() {
        vectorStore.clear();
        try {
            Files.deleteIfExists(workspace.vectorFile());
            Files.deleteIfExists(workspace.filesFile());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot clear " + workspace.vectorFile().getParent(), e);
        }
        overviewStore.clear();
        indexState.clear();
    }

    /** A readable list of what every Java file does. For people to read, it is not sent to the model. */
    private void writeFileSummaries(String projectName, List<JavaSourceSummary> summaries) {
        StringBuilder sb = new StringBuilder("# Java files in ").append(projectName).append("\n\n");
        sb.append("Generated by Local Assistant. One entry per Java file.\n");
        for (JavaSourceSummary summary : summaries) {
            sb.append("\n## ").append(summary.path()).append("\n\n").append(summary.toText()).append('\n');
        }
        try {
            Files.writeString(workspace.filesFile(), sb.toString());
        } catch (IOException e) {
            log.warn("Could not write {}: {}", workspace.filesFile(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ skipping unchanged sources

    /** Settings that change what gets indexed. A different value means the old index is not valid anymore. */
    private String settingsKey() {
        return "chunk=" + properties.chunkSize() + ";tests=" + properties.indexTests() + ";quick=" + properties.quickIndex();
    }

    private boolean canSkip(String source, String fingerprint, boolean needsOverview) {
        if (fingerprint == null || !indexState.isCurrent(source, fingerprint)) {
            return false;
        }
        if (needsOverview && overviewStore.load().isBlank()) {
            return false;
        }
        return vectorStore.open(workspace.vectorFile());   // loads the saved chunks and embeddings from the file
    }

    private String bookFingerprint(String path) {
        try {
            Resource resource = new DefaultResourceLoader().getResource(path);
            return IndexState.hash(settingsKey() + "|" + path + "|" + resource.contentLength() + "|" + resource.lastModified());
        } catch (IOException | RuntimeException e) {
            return null;   // cannot fingerprint, so always index
        }
    }

    // ------------------------------------------------------------------ embedding

    /**
     * Embeds and stores the chunks in small batches, several requests at a time, and logs progress.
     * Embedding is the slow part (every chunk goes through the local model).
     */
    private void addInBatches(List<Document> chunks) {
        int total = chunks.size();
        int threads = Math.max(1, properties.embedParallelism());
        log.info("Embedding {} chunks with the local model ({} requests at a time). This can take a few minutes.", total, threads);

        long start = System.currentTimeMillis();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int from = 0; from < total; from += EMBED_BATCH_SIZE) {
                List<Document> batch = chunks.subList(from, Math.min(from + EMBED_BATCH_SIZE, total));
                futures.add(pool.submit(() -> {
                    vectorStore.add(batch);
                    logProgress(done.addAndGet(batch.size()), batch.size(), total, start);
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Indexing was interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Embedding failed: " + e.getCause().getMessage(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Logs roughly every 100 chunks and at the end. */
    private void logProgress(int finished, int batchSize, int total, long startMillis) {
        boolean crossedHundred = (finished - batchSize) / 100 != finished / 100;
        if (crossedHundred || finished == total) {
            long elapsed = (System.currentTimeMillis() - startMillis) / 1000;
            long remaining = finished == 0 ? 0 : elapsed * (total - finished) / finished;
            log.info("Embedded {}/{} chunks ({}s elapsed, about {}s left)", finished, total, elapsed, remaining);
        }
    }

    // ------------------------------------------------------------------ readers

    private TokenTextSplitter splitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(properties.chunkSize())
                .build();
    }

    private List<Document> readText(String path) {
        return new TextReader(path).read().stream()
                .map(doc -> new Document(stripGutenbergLicence(doc.getText()), doc.getMetadata()))
                .toList();
    }

    /** Keeps only the book itself: the text between the Gutenberg START and END markers. */
    static String stripGutenbergLicence(String text) {
        int start = text.indexOf(GUTENBERG_START);
        if (start >= 0) {
            int firstLineEnd = text.indexOf('\n', start);
            text = text.substring(firstLineEnd < 0 ? start : firstLineEnd + 1);
        }
        int end = text.indexOf(GUTENBERG_END);
        if (end >= 0) {
            text = text.substring(0, end);
        }
        return text.strip();
    }

    /**
     * Paragraph reader keeps chapter structure but needs a PDF with a table of contents.
     * If the PDF has none, fall back to reading page by page.
     */
    private List<Document> readPdf(String path) {
        try {
            return new ParagraphPdfDocumentReader(path).read();
        } catch (RuntimeException e) {
            log.warn("No table of contents found ({}). Falling back to page-by-page reading.", e.getMessage());
            return new PagePdfDocumentReader(path).read();
        }
    }
}
