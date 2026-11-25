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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>Indexing is the slow part (every chunk goes through the local embedding model). To keep start-up fast,
 * the service remembers a fingerprint of what it indexed and skips the work when nothing has changed.
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
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RagProperties properties;
    private final OverviewStore overviewStore;
    private final IndexState indexState;
    private final String tableName;

    public DocumentIngestionService(VectorStore vectorStore,
                                    JdbcTemplate jdbcTemplate,
                                    RagProperties properties,
                                    OverviewStore overviewStore,
                                    IndexState indexState,
                                    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
        if (!SAFE_TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid vector table name: " + tableName);
        }
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.overviewStore = overviewStore;
        this.indexState = indexState;
        this.tableName = tableName;
    }

    // ------------------------------------------------------------------ book mode

    /**
     * Replaces the index with the configured book, unless that book is already indexed and unchanged.
     *
     * @param force index again even if nothing changed
     */
    public IngestionResult ingestBook(boolean force) {
        String path = properties.documentPath();
        String source = "book:" + path;
        String fingerprint = bookFingerprint(path);

        if (!force && canSkip(source, fingerprint, false)) {
            log.info("Book already indexed and unchanged, skipping. Use --reindex to force.");
            return IngestionResult.upToDate();
        }

        log.info("Reading book: {}", path);
        reset();

        List<Document> documents = path.toLowerCase().endsWith(".txt") ? readText(path) : readPdf(path);
        List<Document> chunks = splitter().split(documents);
        addInBatches(chunks);

        if (fingerprint != null) {
            indexState.save(source, fingerprint);
        }
        log.info("Stored {} chunks (from {} sections)", chunks.size(), documents.size());
        return IngestionResult.indexed(chunks.size());
    }

    // ------------------------------------------------------------------ project mode

    /**
     * Replaces the index with a software project, unless that project is already indexed and unchanged.
     * For every Java file it stores a short summary plus the content chunks, and it saves a project
     * overview that is used for broad questions.
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
        if (!force && canSkip(source, fingerprint, true)) {
            log.info("Project already indexed and unchanged ({} files), skipping. Use --reindex to force.", files.size());
            return IngestionResult.upToDate();
        }

        reset();

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

            if (relative.endsWith(".java")) {
                JavaSourceSummary summary = JavaSourceSummary.of(relative, text);
                javaSummaries.add(summary);
                chunks.add(new Document(summary.toText(), Map.of("file", relative, "kind", "summary")));
            }

            for (Document chunk : splitter.split(List.of(new Document(text, Map.of("file", relative))))) {
                // The model only sees chunk text, not metadata, so the file name is written into the text
                chunks.add(new Document("File: " + relative + "\n" + chunk.getText(), chunk.getMetadata()));
            }
        }

        addInBatches(chunks);

        String projectName = base.getFileName() == null ? "project" : base.getFileName().toString();
        overviewStore.save(ProjectOverviewBuilder.build(projectName, paths, javaSummaries, pomXml, readme));
        indexState.save(source, fingerprint);

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

    /** Deletes everything from the vector store (use before indexing something different). */
    public void reset() {
        jdbcTemplate.execute("TRUNCATE TABLE " + tableName);
        overviewStore.clear();
        indexState.clear();
        log.info("Vector store cleared");
    }

    // ------------------------------------------------------------------ skipping unchanged sources

    /** Settings that change what gets indexed. A different value means the old index is not valid anymore. */
    private String settingsKey() {
        return "chunk=" + properties.chunkSize() + ";tests=" + properties.indexTests();
    }

    private boolean canSkip(String source, String fingerprint, boolean needsOverview) {
        if (fingerprint == null || !indexState.isCurrent(source, fingerprint)) {
            return false;
        }
        Long stored = jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableName, Long.class);
        boolean hasData = stored != null && stored > 0;
        return hasData && (!needsOverview || !overviewStore.load().isBlank());
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
