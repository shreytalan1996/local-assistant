package com.localrag.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.localrag.assistant.service.ProjectFilePolicy;

class ProjectFilePolicyTest {

    @Test
    void acceptsSourceAndDocFiles() {
        assertThat(ProjectFilePolicy.isIndexable(Path.of("src/main/java/App.java"), 1_000)).isTrue();
        assertThat(ProjectFilePolicy.isIndexable(Path.of("README.md"), 500)).isTrue();
        assertThat(ProjectFilePolicy.isIndexable(Path.of("pom.xml"), 500)).isTrue();
    }

    @Test
    void ignoresBuildOutputAndVersionControl() {
        assertThat(ProjectFilePolicy.isIndexable(Path.of("target/classes/App.java"), 1_000)).isFalse();
        assertThat(ProjectFilePolicy.isIndexable(Path.of(".git/config.xml"), 1_000)).isFalse();
        assertThat(ProjectFilePolicy.isIndexable(Path.of("node_modules/x/index.md"), 1_000)).isFalse();
    }

    @Test
    void ignoresUnknownTypesAndSecrets() {
        assertThat(ProjectFilePolicy.isIndexable(Path.of("photo.png"), 1_000)).isFalse();
        assertThat(ProjectFilePolicy.isIndexable(Path.of(".env"), 100)).isFalse();
        assertThat(ProjectFilePolicy.isIndexable(Path.of("Makefile"), 100)).isFalse();
    }

    @Test
    void ignoresEmptyAndHugeFiles() {
        assertThat(ProjectFilePolicy.isIndexable(Path.of("Empty.java"), 0)).isFalse();
        assertThat(ProjectFilePolicy.isIndexable(Path.of("Big.java"), ProjectFilePolicy.MAX_FILE_BYTES + 1)).isFalse();
    }

    @Test
    void extensionCheckIsCaseInsensitive() {
        assertThat(ProjectFilePolicy.isIndexable(Path.of("Notes.MD"), 100)).isTrue();
    }
}
