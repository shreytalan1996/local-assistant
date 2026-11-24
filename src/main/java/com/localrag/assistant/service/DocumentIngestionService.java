package com.localrag.assistant.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    // Project Gutenberg wraps every book in licence text between these markers
    private static final String GUTENBERG_START = "*** START OF";
    private static final String GUTENBERG_END = "*** END OF";

    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RagProperties properties;
    private final OverviewStore overviewStore;
    private final String tableName;

    public DocumentIngestionService(VectorStore vectorStore,
                                    JdbcTemplate jdbcTemplate,
                                    RagProperties properties,
                                    OverviewStore overviewStore,
                                    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
        if (!SAFE_TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid vector table name: " + tableName);
        }
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.overviewStore = overviewStore;
        this.tableName = tableName;
    }

    // ------------------------------------------------------------------ book mode

    /** Replaces the index with the configured book. Returns the number of chunks saved. */
    public int ingestBook() {
        String path = properties.documentPath();
        log.info("Reading book: {}", path);
        reset();

        List<Document> documents = path.toLowerCase().endsWith(".txt") ? readText(path) : readPdf(path);
        List<Document> chunks = splitter().split(documents);

        // add() calls the embedding model for every chunk, then saves the vectors in PGVector
        vectorStore.add(chunks);

        log.info("Stored {} chunks (from {} sections)", chunks.size(), documents.size());
        return chunks.size();
    }

    // ------------------------------------------------------------------ project mode

    /**
     * Replaces the index with a software project. For every Java file it stores a short summary plus the
     * content chunks, and it saves a project overview that is used for broad questions.
     * Returns the number of chunks saved.
     */
    public int ingestProject(Path root) throws IOException {
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

        vectorStore.add(chunks);

        String projectName = base.getFileName() == null ? "project" : base.getFileName().toString();
        overviewStore.save(ProjectOverviewBuilder.build(projectName, paths, javaSummaries, pomXml, readme));

        log.info("Stored {} chunks from {} files ({} Java)", chunks.size(), paths.size(), javaSummaries.size());
        return chunks.size();
    }

    private static boolean isIndexable(Path base, Path file) {
        try {
            return ProjectFilePolicy.isIndexable(base.relativize(file), Files.size(file));
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ maintenance

    /** Deletes everything from the vector store (use before indexing something different). */
    public void reset() {
        jdbcTemplate.execute("TRUNCATE TABLE " + tableName);
        overviewStore.clear();
        log.info("Vector store cleared");
    }

    // ------------------------------------------------------------------ helpers

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
