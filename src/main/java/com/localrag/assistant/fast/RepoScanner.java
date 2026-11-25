package com.localrag.assistant.fast;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Finds the files worth indexing. In a git repository it asks git (which already knows what is ignored, so
 * {@code node_modules} and build folders never show up). Otherwise it walks the folder and skips well-known
 * generated folders.
 */
final class RepoScanner {

    static final long MAX_FILE_BYTES = 300_000;
    static final long MAX_JSON_BYTES = 60_000;

    private static final Set<String> EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "groovy", "gradle", "py", "js", "jsx", "mjs", "cjs", "ts", "tsx",
            "go", "rs", "rb", "php", "cs", "c", "h", "cc", "cpp", "hpp", "swift", "m", "sh", "bash", "sql",
            "md", "mdx", "rst", "txt", "yml", "yaml", "xml", "properties", "toml", "ini", "cfg", "conf", "json",
            "html", "css", "scss", "vue", "svelte", "proto", "graphql", "tf");

    private static final Set<String> SPECIAL_NAMES = Set.of("dockerfile", "makefile", "jenkinsfile", "procfile");

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build", "dist", "out", ".idea", ".vscode", ".gradle", ".mvn",
            "venv", ".venv", "__pycache__", "vendor", ".next", ".nuxt", "coverage", "obj", ".local-assistant", ".repo-assistant");

    private static final Pattern TEST_FILE = Pattern.compile(
            ".*(Test|Tests|IT)\\.(java|kt)|.*_test\\.(go|py)|test_.*\\.py|.*\\.(test|spec)\\.(js|jsx|ts|tsx)");
    private static final Set<String> TEST_DIRECTORIES = Set.of("test", "tests", "__tests__");

    private RepoScanner() {
    }

    /** Relative paths (with '/') of the files to index, sorted. */
    static List<String> listFiles(Path root, boolean useGit, boolean includeTests) throws IOException {
        List<String> candidates = useGit ? gitFiles(root) : null;
        if (candidates == null) {
            candidates = walk(root);
        }
        List<String> result = new ArrayList<>();
        for (String relative : candidates) {
            if (!includeTests && isTestPath(relative)) {
                continue;
            }
            Path file = root.resolve(relative);
            try {
                if (Files.isRegularFile(file) && isIndexable(relative, Files.size(file))) {
                    result.add(relative);
                }
            } catch (IOException e) {
                // unreadable file: skip it
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    static boolean isIndexable(String relativePath, long sizeInBytes) {
        if (sizeInBytes <= 0 || sizeInBytes > MAX_FILE_BYTES) {
            return false;
        }
        String[] parts = relativePath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (SKIPPED_DIRECTORIES.contains(parts[i])) {
                return false;
            }
        }
        String name = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        if (SPECIAL_NAMES.contains(name)) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !EXTENSIONS.contains(name.substring(dot + 1))) {
            return false;
        }
        if (name.endsWith(".json")) {
            return sizeInBytes <= MAX_JSON_BYTES && !name.contains("lock");
        }
        return !name.endsWith(".min.js") && !name.endsWith(".min.css");
    }

    static boolean isTestPath(String relativePath) {
        String[] parts = relativePath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (TEST_DIRECTORIES.contains(parts[i])) {
                return true;
            }
        }
        return TEST_FILE.matcher(parts[parts.length - 1]).matches();
    }

    // ------------------------------------------------------------------ discovery

    private static List<String> gitFiles(Path root) {
        try {
            Process process = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z",
                    "--cached", "--others", "--exclude-standard")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return null;
            }
            List<String> files = new ArrayList<>();
            for (String entry : new String(output, java.nio.charset.StandardCharsets.UTF_8).split("\0")) {
                if (!entry.isEmpty()) {
                    files.add(entry);
                }
            }
            return files;
        } catch (IOException e) {
            return null;   // git is not installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static List<String> walk(Path root) throws IOException {
        List<String> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                if (!dir.equals(root) && name != null && SKIPPED_DIRECTORIES.contains(name.toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                files.add(root.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
}
