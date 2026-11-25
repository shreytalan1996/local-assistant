package com.localrag.assistant.fast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Keeps embeddings on disk so each piece of text is embedded only once. Entries are found by a hash of the
 * text, so a changed file simply gets new entries and unchanged files are never embedded again.
 * Stored in the user's home folder, not inside the repository.
 */
final class VectorCache {

    private static final int FORMAT = 1;

    private final Path file;

    VectorCache(Path file) {
        this.file = file;
    }

    /** Where the cache for this repository and embedding model lives. */
    static Path defaultFile(Path repoRoot, String embeddingModel) {
        String dir = System.getProperty("ra.cache.dir");
        Path base = dir != null ? Path.of(dir) : Path.of(System.getProperty("user.home"), ".repo-assistant", "cache");
        return base.resolve(key(repoRoot.toString() + "|" + embeddingModel) + ".vec");
    }

    /** A short, stable identifier for a text. */
    static String key(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the cache. A missing or damaged file just means an empty cache. */
    Map<String, float[]> load() {
        Map<String, float[]> vectors = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return vectors;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != FORMAT) {
                return new HashMap<>();
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                float[] vector = new float[in.readInt()];
                for (int j = 0; j < vector.length; j++) {
                    vector[j] = in.readFloat();
                }
                vectors.put(key, vector);
            }
            return vectors;
        } catch (IOException | RuntimeException e) {
            return new HashMap<>();
        }
    }

    /** Writes the cache (to a temporary file first, so a crash cannot leave a half-written cache). */
    void save(Map<String, float[]> vectors) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(FORMAT);
            out.writeInt(vectors.size());
            for (Map.Entry<String, float[]> e : vectors.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeInt(e.getValue().length);
                for (float v : e.getValue()) {
                    out.writeFloat(v);
                }
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
