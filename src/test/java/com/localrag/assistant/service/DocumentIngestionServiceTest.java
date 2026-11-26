package com.localrag.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.SearchRequest;

import com.localrag.assistant.config.RagProperties;

/**
 * The promise of the project: index once, then start quickly. Each "start" below builds new objects and a new
 * embedding model, like a fresh run of the app, and only the files on disk connect them.
 */
class DocumentIngestionServiceTest {

    @TempDir
    Path tempDir;

    Path repo;

    @BeforeEach
    void createRepo() throws IOException {
        repo = Files.createDirectories(tempDir.resolve("my-repo"));
        write("src/main/java/com/demo/StreamService.java",
                "package com.demo;\npublic class StreamService { public void streamAnswer() { } }\n", 1_000_000);
        write("README.md", "# Demo\nA small demo about streaming answers.\n", 1_000_000);
    }

    private Path write(String relative, String text, long modified) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }

    /** One run of the app: everything is new except the files on disk. */
    private static class App {
        final FakeEmbeddingModel embedding = new FakeEmbeddingModel();
        final WorkspaceVectorStore store = new WorkspaceVectorStore(embedding);
        final IndexWorkspace workspace;
        final DocumentIngestionService service;

        App(Path home, boolean storeInProject) {
            workspace = new IndexWorkspace(home, storeInProject);
            RagProperties properties = new RagProperties("file:./none.txt", "system", 400, 5, 0.0,
                    home.toString(), storeInProject, false, 1, false);
            service = new DocumentIngestionService(store, workspace, properties,
                    new OverviewStore(workspace::overviewFile), new IndexState(workspace::stateFile));
        }
    }

    @Test
    void firstRunIndexesAndSavesEverythingInTheRepoFolder() throws IOException {
        App app = new App(tempDir.resolve("home"), true);

        var result = app.service.ingestProject(repo, false);

        assertThat(result.skipped()).isFalse();
        assertThat(result.chunks()).isPositive();
        assertThat(app.embedding.embeddedTexts.get()).isPositive();

        Path saved = repo.resolve(".local-assistant");
        assertThat(saved.resolve("vector-store.json")).exists();
        assertThat(saved.resolve("overview.md")).exists();
        assertThat(saved.resolve("files.md")).content().contains("StreamService");
        assertThat(saved.resolve("index-state.properties")).exists();
        assertThat(saved.resolve(".gitignore")).content().isEqualTo("*\n");
    }

    @Test
    void secondRunLoadsTheSavedIndexAndEmbedsNothing() throws IOException {
        new App(tempDir.resolve("home"), true).service.ingestProject(repo, false);

        App second = new App(tempDir.resolve("home"), true);
        var result = second.service.ingestProject(repo, false);

        assertThat(result.skipped()).isTrue();
        assertThat(second.embedding.embeddedTexts.get()).as("no chunk was embedded again").isZero();
        assertThat(second.store.hasData()).isTrue();
        assertThat(second.store.similaritySearch(SearchRequest.builder().query("streamAnswer").topK(1).build())).hasSize(1);
    }

    @Test
    void startingWithoutFlagsReloadsTheLastSource() throws IOException {
        new App(tempDir.resolve("home"), true).service.ingestProject(repo, false);

        App later = new App(tempDir.resolve("home"), true);
        assertThat(later.service.openLast()).contains(repo.resolve(".local-assistant").toAbsolutePath().normalize());
        assertThat(later.store.hasData()).isTrue();
        assertThat(later.embedding.embeddedTexts.get()).isZero();
    }

    @Test
    void changingAFileIndexesAgain() throws IOException {
        new App(tempDir.resolve("home"), true).service.ingestProject(repo, false);
        write("README.md", "# Demo\nNow it says something different.\n", 2_000_000);

        App second = new App(tempDir.resolve("home"), true);
        var result = second.service.ingestProject(repo, false);

        assertThat(result.skipped()).isFalse();
        assertThat(second.embedding.embeddedTexts.get()).isPositive();
    }

    @Test
    void reindexFlagForcesIndexingEvenWhenNothingChanged() throws IOException {
        new App(tempDir.resolve("home"), true).service.ingestProject(repo, false);

        App second = new App(tempDir.resolve("home"), true);
        assertThat(second.service.ingestProject(repo, true).skipped()).isFalse();
    }

    @Test
    void aBrokenSavedFileIsRebuiltInsteadOfFailing() throws IOException {
        new App(tempDir.resolve("home"), true).service.ingestProject(repo, false);
        Files.writeString(repo.resolve(".local-assistant/vector-store.json"), "not json");

        App second = new App(tempDir.resolve("home"), true);
        var result = second.service.ingestProject(repo, false);

        assertThat(result.skipped()).isFalse();
        assertThat(second.store.hasData()).isTrue();
    }

    @Test
    void indexingAnotherProjectKeepsTheFirstOne() throws IOException {
        App app = new App(tempDir.resolve("home"), true);
        app.service.ingestProject(repo, false);

        Path other = Files.createDirectories(tempDir.resolve("other-repo"));
        Files.writeString(other.resolve("README.md"), "# Other\nSomething else.\n");
        app.service.ingestProject(other, false);

        assertThat(repo.resolve(".local-assistant/vector-store.json")).exists();
        assertThat(other.resolve(".local-assistant/vector-store.json")).exists();

        // Going back to the first project is still instant
        App back = new App(tempDir.resolve("home"), true);
        assertThat(back.service.ingestProject(repo, false).skipped()).isTrue();
        assertThat(back.embedding.embeddedTexts.get()).isZero();
    }

    @Test
    void canKeepTheRepoUntouchedWhenStoringInTheHomeFolder() throws IOException {
        App app = new App(tempDir.resolve("home"), false);

        app.service.ingestProject(repo, false);

        assertThat(repo.resolve(".local-assistant")).doesNotExist();
        try (var folders = Files.list(tempDir.resolve("home"))) {
            assertThat(folders.map(f -> f.getFileName().toString()))
                    .anyMatch(name -> name.startsWith("my-repo-"));
        }
        assertThat(tempDir.resolve("home/last-workspace.txt")).exists();
    }

    @Test
    void resetDeletesTheSavedIndex() throws IOException {
        App app = new App(tempDir.resolve("home"), true);
        app.service.ingestProject(repo, false);

        app.service.reset();

        assertThat(repo.resolve(".local-assistant/vector-store.json")).doesNotExist();
        assertThat(repo.resolve(".local-assistant/overview.md")).doesNotExist();
        assertThat(app.store.hasData()).isFalse();
    }
}
