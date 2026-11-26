package com.localrag.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexWorkspaceTest {

    @TempDir
    Path tempDir;

    @Test
    void projectIndexLivesInsideTheProjectAndIgnoresItselfForGit() throws IOException {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        IndexWorkspace workspace = new IndexWorkspace(tempDir.resolve("home"), true);

        Path dir = workspace.forProject(repo);
        workspace.use(dir);

        assertThat(dir).isEqualTo(repo.toAbsolutePath().normalize().resolve(".local-assistant"));
        assertThat(dir.resolve(".gitignore")).content().isEqualTo("*\n");
        assertThat(workspace.vectorFile()).isEqualTo(dir.resolve("vector-store.json"));
        assertThat(workspace.overviewFile()).isEqualTo(dir.resolve("overview.md"));
    }

    @Test
    void canStoreOutsideTheProject() {
        IndexWorkspace workspace = new IndexWorkspace(tempDir.resolve("home"), false);

        Path dir = workspace.forProject(tempDir.resolve("repo"));

        assertThat(dir).startsWith(tempDir.resolve("home").toAbsolutePath());
        assertThat(dir.getFileName().toString()).startsWith("repo-");
    }

    @Test
    void bookFoldersAreNamedAfterTheBook() {
        IndexWorkspace workspace = new IndexWorkspace(tempDir.resolve("home"), false);

        assertThat(workspace.forBook("file:./documents/My Book.txt").getFileName().toString()).startsWith("book-My-Book-");
    }

    @Test
    void remembersTheLastSourceAcrossStarts() throws IOException {
        IndexWorkspace first = new IndexWorkspace(tempDir.resolve("home"), true);
        assertThat(first.useLast()).isEmpty();
        assertThat(first.vectorFile()).isNull();

        Path dir = first.forProject(Files.createDirectories(tempDir.resolve("repo")));
        first.use(dir);

        IndexWorkspace nextStart = new IndexWorkspace(tempDir.resolve("home"), true);
        assertThat(nextStart.useLast()).contains(dir);
        assertThat(nextStart.hasCurrent()).isTrue();
    }

    @Test
    void everyBookGetsItsOwnFolder() {
        IndexWorkspace workspace = new IndexWorkspace(tempDir.resolve("home"), true);

        assertThat(workspace.forBook("file:./documents/a.txt")).isNotEqualTo(workspace.forBook("file:./documents/b.txt"));
        assertThat(workspace.forBook("file:./documents/a.txt")).isEqualTo(workspace.forBook("file:./documents/a.txt"));
    }
}
