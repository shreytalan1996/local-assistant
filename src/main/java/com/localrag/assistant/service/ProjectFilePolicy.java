package com.localrag.assistant.service;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which files of a software project are worth indexing.
 * Pure Java on purpose, so it is simple to unit test.
 */
public final class ProjectFilePolicy {

    /** Files larger than this are skipped (generated or data files). */
    public static final long MAX_FILE_BYTES = 200_000;

    private static final Set<String> EXTENSIONS = Set.of(
            "java", "kt", "md", "yml", "yaml", "xml", "properties", "st", "gradle", "sql");

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            "target", "build", "node_modules", ".git", ".idea", ".vscode", ".mvn", "documents",
            ".local-assistant", ".repo-assistant");   // the app's own state, written inside the folder when it indexes itself

    private ProjectFilePolicy() {
    }

    /** Same as {@link #isIndexable(Path, long, boolean)} with test sources left out. */
    public static boolean isIndexable(Path relativePath, long sizeInBytes) {
        return isIndexable(relativePath, sizeInBytes, false);
    }

    /**
     * @param relativePath path relative to the project root, e.g. {@code src/main/java/App.java}
     * @param sizeInBytes  file size
     * @param includeTests whether files under {@code src/test} should be indexed too
     */
    public static boolean isIndexable(Path relativePath, long sizeInBytes, boolean includeTests) {
        if (sizeInBytes <= 0 || sizeInBytes > MAX_FILE_BYTES) {
            return false;
        }
        int directories = relativePath.getNameCount() - 1;   // every part except the file name itself
        for (int i = 0; i < directories; i++) {
            String name = relativePath.getName(i).toString();
            if (SKIPPED_DIRECTORIES.contains(name)) {
                return false;
            }
            if (!includeTests && name.equals("src") && i + 1 < directories
                    && relativePath.getName(i + 1).toString().equals("test")) {
                return false;
            }
        }
        String fileName = relativePath.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(fileName.substring(dot + 1));
    }
}
