package com.localrag.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverviewStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesLoadsAndClears() {
        OverviewStore store = new OverviewStore(tempDir.resolve("nested/overview.txt"));

        assertThat(store.load()).isEmpty();

        store.save("Project: demo");
        assertThat(store.load()).isEqualTo("Project: demo");

        store.clear();
        assertThat(store.load()).isEmpty();
    }

    @Test
    void replacesCurlyBracesSoPromptTemplatesDoNotBreak() {
        OverviewStore store = new OverviewStore(tempDir.resolve("overview.txt"));
        store.save("class A { int x; }");

        assertThat(store.load()).isEqualTo("class A ( int x; )");
    }
}
