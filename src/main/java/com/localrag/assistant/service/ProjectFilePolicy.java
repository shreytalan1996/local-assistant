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
            "target", "build", "node_modules", ".git", ".idea", ".vscode", ".mvn", "documents");

    private ProjectFilePolicy() {
    }

    /**
     * @param relativePath path relative to the project root, e.g. {@code src/main/java/App.java}
     * @param sizeInBytes  file size
     */
    public static boolean isIndexable(Path relativePath, long sizeInBytes) {
        if (sizeInBytes <= 0 || sizeInBytes > MAX_FILE_BYTES) {
            return false;
        }
        // skip anything inside an ignored directory (all parts except the file name itself)
        for (int i = 0; i < relativePath.getNameCount() - 1; i++) {
            if (SKIPPED_DIRECTORIES.contains(relativePath.getName(i).toString())) {
                return false;
            }
        }
        String fileName = relativePath.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(fileName.substring(dot + 1));
    }
}
