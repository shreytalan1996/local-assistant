package com.localrag.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

class WorkspaceVectorStoreTest {

    @TempDir
    Path tempDir;

    private static SearchRequest ask(String question) {
        return SearchRequest.builder().query(question).topK(1).build();
    }

    @Test
    void savedChunksAreLoadedBackWithoutEmbeddingAgain() {
        Path file = tempDir.resolve("ws/vector-store.json");

        FakeEmbeddingModel first = new FakeEmbeddingModel();
        WorkspaceVectorStore store = new WorkspaceVectorStore(first);
        store.add(List.of(new Document("pasta recipe with tomato sauce"), new Document("java streaming with flux")));
        store.save(file);
        assertThat(Files.exists(file)).isTrue();

        // A new start: new store object, new embedding model, same file
        FakeEmbeddingModel second = new FakeEmbeddingModel();
        WorkspaceVectorStore reloaded = new WorkspaceVectorStore(second);
        assertThat(reloaded.open(file)).isTrue();
        assertThat(reloaded.hasData()).isTrue();
        assertThat(second.embeddedTexts.get()).as("loading must not embed any chunk").isZero();

        List<Document> found = reloaded.similaritySearch(ask("flux streaming"));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getText()).contains("flux");
    }

    @Test
    void missingOrBrokenFileGivesAnEmptyStore() throws Exception {
        WorkspaceVectorStore store = new WorkspaceVectorStore(new FakeEmbeddingModel());

        assertThat(store.open(tempDir.resolve("nothing.json"))).isFalse();
        assertThat(store.hasData()).isFalse();

        Path broken = Files.writeString(tempDir.resolve("broken.json"), "{ this is not json");
        assertThat(store.open(broken)).isFalse();
        assertThat(store.hasData()).isFalse();
    }

    @Test
    void clearForgetsEverything() {
        WorkspaceVectorStore store = new WorkspaceVectorStore(new FakeEmbeddingModel());
        store.add(List.of(new Document("some text")));
        assertThat(store.hasData()).isTrue();

        store.clear();

        assertThat(store.hasData()).isFalse();
        assertThat(store.similaritySearch(ask("some text"))).isEmpty();
    }
}
