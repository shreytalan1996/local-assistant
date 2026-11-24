package com.localrag.assistant.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps the project overview in a small text file so it survives restarts.
 * One overview at a time, matching the one index in the vector store.
 */
public class OverviewStore {

    private final Path file;

    public OverviewStore(Path file) {
        this.file = file;
    }

    public void save(String overview) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, overview, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save overview to " + file, e);
        }
    }

    /**
     * Returns the saved overview, or an empty string when there is none.
     * Curly braces are replaced because prompt templates treat them as placeholders.
     */
    public String load() {
        try {
            if (!Files.exists(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8).replace('{', '(').replace('}', ')');
        } catch (IOException e) {
            return "";
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + file, e);
        }
    }
}
