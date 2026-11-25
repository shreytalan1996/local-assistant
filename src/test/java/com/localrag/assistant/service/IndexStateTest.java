package com.localrag.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexStateTest {

    @TempDir
    Path tempDir;

    @Test
    void remembersWhatWasIndexed() {
        IndexState state = new IndexState(tempDir.resolve("state/index.properties"));

        assertThat(state.isCurrent("project:/a", "abc")).isFalse();

        state.save("project:/a", "abc");
        assertThat(state.isCurrent("project:/a", "abc")).isTrue();
        assertThat(state.isCurrent("project:/a", "different")).isFalse();
        assertThat(state.isCurrent("project:/other", "abc")).isFalse();

        state.clear();
        assertThat(state.isCurrent("project:/a", "abc")).isFalse();
    }

    @Test
    void fingerprintChangesWhenAFileChangesOrSettingsChange() throws IOException {
        Path file = Files.writeString(tempDir.resolve("A.java"), "class A {}");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000));

        String first = IndexState.fingerprint(tempDir, List.of(file), "chunk=400");
        String same = IndexState.fingerprint(tempDir, List.of(file), "chunk=400");
        assertThat(same).isEqualTo(first);

        assertThat(IndexState.fingerprint(tempDir, List.of(file), "chunk=800")).isNotEqualTo(first);

        Files.writeString(file, "class A { int x; }");
        Files.setLastModifiedTime(file, FileTime.fromMillis(2_000_000));
        assertThat(IndexState.fingerprint(tempDir, List.of(file), "chunk=400")).isNotEqualTo(first);
    }

    @Test
    void fingerprintDoesNotDependOnFileOrder() throws IOException {
        Path a = Files.writeString(tempDir.resolve("A.java"), "class A {}");
        Path b = Files.writeString(tempDir.resolve("B.java"), "class B {}");

        assertThat(IndexState.fingerprint(tempDir, List.of(a, b), "s"))
                .isEqualTo(IndexState.fingerprint(tempDir, List.of(b, a), "s"));
    }
}
