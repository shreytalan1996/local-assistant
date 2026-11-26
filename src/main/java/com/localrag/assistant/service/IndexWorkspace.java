package com.localrag.assistant.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Decides where the saved index of one source (a project or a book) lives on disk, and remembers which
 * source was used last, so a plain start can reload it.
 *
 * <p>A workspace is a small folder holding everything the app saved for that source:
 * <pre>
 *   vector-store.json          chunks and their embeddings (loaded on the next start instead of embedding again)
 *   overview.md                the project overview that is sent with every question
 *   files.md                   readable summary of every Java file (for people, not sent to the model)
 *   index-state.properties     the fingerprint that says whether the source changed
 * </pre>
 * Each source has its own workspace, so indexing a new source never deletes the others.
 * By default the workspaces are folders such as {@code indexes/my-project-1a2b3c4d} inside the Local Assistant
 * folder, where they are easy to find and look at.
 */
public class IndexWorkspace {

    static final String VECTOR_FILE = "vector-store.json";
    static final String OVERVIEW_FILE = "overview.md";
    static final String FILES_FILE = "files.md";
    static final String STATE_FILE = "index-state.properties";

    private final Path root;
    private final boolean storeInProject;
    private volatile Path current;

    /**
     * @param root           folder that holds the saved indexes, e.g. {@code indexes}
     * @param storeInProject true = a project's index is saved inside that project (in {@code .local-assistant});
     *                       false = everything is saved under {@code root}, so the project folder is never written to
     */
    public IndexWorkspace(Path root, boolean storeInProject) {
        this.root = root.toAbsolutePath().normalize();
        this.storeInProject = storeInProject;
    }

    public Path forProject(Path projectRoot) {
        Path base = projectRoot.toAbsolutePath().normalize();
        if (storeInProject) {
            return base.resolve(".local-assistant");
        }
        String name = base.getFileName() == null ? "project" : base.getFileName().toString();
        return root.resolve(readableName(name) + "-" + IndexState.hash(base.toString()).substring(0, 8));
    }

    public Path forBook(String bookLocation) {
        String file = bookLocation.substring(Math.max(bookLocation.lastIndexOf('/'), bookLocation.lastIndexOf('\\')) + 1);
        int dot = file.lastIndexOf('.');
        String name = dot > 0 ? file.substring(0, dot) : file;
        return root.resolve("book-" + readableName(name) + "-" + IndexState.hash(bookLocation).substring(0, 8));
    }

    /** A folder name people can read: letters, digits, dots, dashes and underscores only. */
    private static String readableName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        return cleaned.isEmpty() ? "source" : cleaned;
    }

    /** Makes this folder the active workspace and remembers it for the next start. */
    public void use(Path dir) {
        Path workspace = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(workspace);
            // The folder ignores itself, so generated files are never committed to a project's repository
            Path ignore = workspace.resolve(".gitignore");
            if (!Files.exists(ignore)) {
                Files.writeString(ignore, "*\n", StandardCharsets.UTF_8);
            }
            Files.createDirectories(root);
            Files.writeString(root.resolve("last-workspace.txt"), workspace.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare " + workspace, e);
        }
        this.current = workspace;
    }

    /** Switches to the workspace used last time. Returns it, or empty when nothing was indexed yet. */
    public Optional<Path> useLast() {
        Path pointer = root.resolve("last-workspace.txt");
        try {
            if (!Files.exists(pointer)) {
                return Optional.empty();
            }
            Path dir = Path.of(Files.readString(pointer, StandardCharsets.UTF_8).strip());
            if (!Files.isDirectory(dir)) {
                return Optional.empty();
            }
            this.current = dir;
            return Optional.of(dir);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public boolean hasCurrent() {
        return current != null;
    }

    /** Null when no source has been chosen yet. */
    public Path vectorFile() {
        return file(VECTOR_FILE);
    }

    public Path overviewFile() {
        return file(OVERVIEW_FILE);
    }

    public Path filesFile() {
        return file(FILES_FILE);
    }

    public Path stateFile() {
        return file(STATE_FILE);
    }

    private Path file(String name) {
        Path dir = current;
        return dir == null ? null : dir.resolve(name);
    }
}
