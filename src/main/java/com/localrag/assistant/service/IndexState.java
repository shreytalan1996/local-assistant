package com.localrag.assistant.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Remembers what is currently in the vector store (which source, and a fingerprint of it), so that
 * starting the app again on an unchanged project can skip the slow indexing step.
 */
public class IndexState {

    private final Supplier<Path> location;

    public IndexState(Path file) {
        this(() -> file);
    }

    /** The file can change while the app runs (a new source was chosen); null means "no source yet". */
    public IndexState(Supplier<Path> location) {
        this.location = location;
    }

    /** True when the saved state was written for this exact source and fingerprint. */
    public boolean isCurrent(String source, String fingerprint) {
        Properties saved = read();
        return source.equals(saved.getProperty("source")) && fingerprint.equals(saved.getProperty("fingerprint"));
    }

    public void save(String source, String fingerprint) {
        Path file = location.get();
        if (file == null) {
            throw new IllegalStateException("No source has been chosen yet");
        }
        Properties props = new Properties();
        props.setProperty("source", source);
        props.setProperty("fingerprint", fingerprint);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Local Assistant index state");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save index state to " + file, e);
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

    private Properties read() {
        Properties props = new Properties();
        Path file = location.get();
        if (file != null && Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                return new Properties();
            }
        }
        return props;
    }

    /**
     * Fingerprint of a project: path, size and last-modified time of every indexed file, plus the
     * settings that change how files are indexed. Any edit, new file or deleted file changes it.
     */
    public static String fingerprint(Path base, List<Path> files, String settings) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Path file : files) {
            String relative = base.relativize(file).toString().replace('\\', '/');
            lines.add(relative + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis());
        }
        Collections.sort(lines);
        return hash(settings + "\n" + String.join("\n", lines));
    }

    public static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 exists in every JDK
        }
    }
}
