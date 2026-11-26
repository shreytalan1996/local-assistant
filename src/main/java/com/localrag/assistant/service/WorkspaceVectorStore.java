package com.localrag.assistant.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * The vector store, with no database: Spring AI's {@link SimpleVectorStore} keeps the chunks and their
 * embeddings in memory, and this class saves them to a JSON file and loads them back.
 *
 * <p>Saving is what makes the second start quick. Embedding is the slow step, so after indexing the
 * result is written to disk; next time the file is read instead of embedding every chunk again.
 *
 * <p>The chat client holds this one object for the whole run. Switching to another source swaps the store
 * inside it, so nothing else needs to change.
 */
public class WorkspaceVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceVectorStore.class);

    private final EmbeddingModel embeddingModel;
    private volatile SimpleVectorStore delegate;
    private final AtomicInteger chunks = new AtomicInteger();

    public WorkspaceVectorStore(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.delegate = fresh();
    }

    /**
     * Replaces the content with what was saved in this file.
     *
     * @return true when saved chunks were loaded; false when there is no usable file (the store is then empty)
     */
    public boolean open(Path file) {
        SimpleVectorStore loaded = fresh();
        chunks.set(0);
        this.delegate = loaded;
        try {
            if (file == null || !Files.isRegularFile(file) || Files.size(file) < 3) {
                return false;
            }
            long start = System.currentTimeMillis();
            loaded.load(file.toFile());
            chunks.set(1);   // the exact count is not exposed; "not empty" is all that is needed
            log.info("Loaded saved index {} ({} KB) in {} ms, no embedding needed", file, Files.size(file) / 1024,
                    System.currentTimeMillis() - start);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Saved index {} could not be read ({}), it will be built again", file, e.getMessage());
            this.delegate = fresh();
            chunks.set(0);
            return false;
        }
    }

    /** Forgets everything in memory. */
    public void clear() {
        this.delegate = fresh();
        chunks.set(0);
    }

    /** Writes the store to disk. The file appears all at once, so a crash never leaves a half-written index. */
    public void save(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            delegate.save(temp.toFile());
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save the index to " + file, e);
        }
    }

    public boolean hasData() {
        return chunks.get() > 0;
    }

    private SimpleVectorStore fresh() {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    // ------------------------------------------------------------------ VectorStore

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
        chunks.addAndGet(documents.size());
    }

    @Override
    public void delete(List<String> idList) {
        delegate.delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return delegate.similaritySearch(request);
    }

    @Override
    public String getName() {
        return "workspace-simple-vector-store";
    }
}
