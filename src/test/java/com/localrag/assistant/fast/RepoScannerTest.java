package com.localrag.assistant.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class RepoScannerTest {

    @Test
    void acceptsSourceDocsAndConfig() {
        assertTrue(RepoScanner.isIndexable("src/main/java/App.java", 1_000));
        assertTrue(RepoScanner.isIndexable("web/app.tsx", 1_000));
        assertTrue(RepoScanner.isIndexable("README.md", 500));
        assertTrue(RepoScanner.isIndexable("Dockerfile", 200));
    }

    @Test
    void rejectsGeneratedBinaryHugeAndEmptyFiles() {
        assertFalse(RepoScanner.isIndexable("node_modules/x/index.js", 1_000));
        assertFalse(RepoScanner.isIndexable("target/classes/App.java", 1_000));
        assertFalse(RepoScanner.isIndexable("logo.png", 1_000));
        assertFalse(RepoScanner.isIndexable("app.min.js", 1_000));
        assertFalse(RepoScanner.isIndexable("package-lock.json", 1_000));
        assertFalse(RepoScanner.isIndexable("data.json", RepoScanner.MAX_JSON_BYTES + 1));
        assertFalse(RepoScanner.isIndexable("Big.java", RepoScanner.MAX_FILE_BYTES + 1));
        assertFalse(RepoScanner.isIndexable("Empty.java", 0));
    }

    @Test
    void recognisesTestFiles() {
        assertTrue(RepoScanner.isTestPath("src/test/java/AppTest.java"));
        assertTrue(RepoScanner.isTestPath("web/__tests__/a.js"));
        assertTrue(RepoScanner.isTestPath("pkg/util_test.go"));
        assertTrue(RepoScanner.isTestPath("tests/test_api.py"));
        assertTrue(RepoScanner.isTestPath("ui/button.spec.ts"));
        assertFalse(RepoScanner.isTestPath("src/main/java/Latest.java"));
    }

    @Test
    void walksAFolderWithoutGitAndSkipsGeneratedFolders() throws IOException {
        Path root = Files.createTempDirectory("scan");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("node_modules/lib"));
        Files.writeString(root.resolve("src/App.java"), "class App {}");
        Files.writeString(root.resolve("src/AppTest.java"), "class AppTest {}");
        Files.writeString(root.resolve("node_modules/lib/x.js"), "var x");
        Files.writeString(root.resolve("notes.png"), "not text");

        assertEquals(List.of("src/App.java", "src/AppTest.java"), RepoScanner.listFiles(root, false, true));
        assertEquals(List.of("src/App.java"), RepoScanner.listFiles(root, false, false));
    }
}
