package com.localrag.assistant.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Keeps the project overview in a small markdown file ({@code overview.md}) so it survives restarts.
 * It is the overview of the active source, so it lives in that source's workspace.
 */
public class OverviewStore {

    private final Supplier<Path> location;

    public OverviewStore(Path file) {
        this(() -> file);
    }

    /** The file can change while the app runs (a new source was chosen); null means "no source yet". */
    public OverviewStore(Supplier<Path> location) {
        this.location = location;
    }

    public void save(String overview) {
        Path file = require();
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
        Path file = location.get();
        try {
            if (file == null || !Files.exists(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8).replace('{', '(').replace('}', ')');
        } catch (IOException e) {
            return "";
        }
    }

    public void clear() {
        Path file = location.get();
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + file, e);
        }
    }

    private Path require() {
        Path file = location.get();
        if (file == null) {
            throw new IllegalStateException("No source has been chosen yet");
        }
        return file;
    }
}
