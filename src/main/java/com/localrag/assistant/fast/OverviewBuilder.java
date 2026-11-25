package com.localrag.assistant.fast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds a short description of a repository: languages, build tool, layout, entry points, Spring components
 * and the start of the README. It is given to the model with every question, so broad questions ("what is this
 * project?") can be answered even though search only finds details.
 */
final class OverviewBuilder {

    static final int MAX_CHARS = 3500;
    private static final int README_CHARS = 700;

    private static final Pattern PARENT = Pattern.compile("(?s)<parent>.*?</parent>");
    private static final Pattern DEP_MANAGEMENT = Pattern.compile("(?s)<dependencyManagement>.*?</dependencyManagement>");
    private static final Pattern DEPENDENCIES = Pattern.compile("(?s)<dependencies>(.*?)</dependencies>");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern JAVA_VERSION = Pattern.compile("<java.version>\\s*([^<\\s]+)\\s*</java.version>");
    private static final Pattern JSON_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private static final List<String> LAYER_ORDER = List.of(
            "RestController", "Controller", "Service", "Repository", "Component", "Configuration", "Entity");

    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("kts", "Kotlin"),
            Map.entry("scala", "Scala"), Map.entry("groovy", "Groovy"), Map.entry("py", "Python"),
            Map.entry("js", "JavaScript"), Map.entry("jsx", "JavaScript"), Map.entry("mjs", "JavaScript"),
            Map.entry("cjs", "JavaScript"), Map.entry("ts", "TypeScript"), Map.entry("tsx", "TypeScript"),
            Map.entry("go", "Go"), Map.entry("rs", "Rust"), Map.entry("rb", "Ruby"), Map.entry("php", "PHP"),
            Map.entry("cs", "C#"), Map.entry("c", "C"), Map.entry("h", "C"), Map.entry("cc", "C++"),
            Map.entry("cpp", "C++"), Map.entry("hpp", "C++"), Map.entry("swift", "Swift"),
            Map.entry("sh", "Shell"), Map.entry("bash", "Shell"), Map.entry("sql", "SQL"),
            Map.entry("md", "Markdown"), Map.entry("yml", "YAML"), Map.entry("yaml", "YAML"),
            Map.entry("xml", "XML"), Map.entry("json", "JSON"), Map.entry("html", "HTML"),
            Map.entry("css", "CSS"), Map.entry("scss", "CSS"), Map.entry("vue", "Vue"),
            Map.entry("tf", "Terraform"), Map.entry("properties", "Properties"));

    private OverviewBuilder() {
    }

    /**
     * @param projectName name of the repository folder
     * @param paths       relative paths of all indexed files
     * @param java        summaries of the Java files
     * @param rootFiles   content of root files by name (pom.xml, package.json, README.md), when present
     */
    static String build(String projectName, List<String> paths, List<JavaSourceSummary> java,
                        Map<String, String> rootFiles) {
        List<JavaSourceSummary> mainJava = java.stream().filter(j -> !j.isTest()).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("Repository: ").append(projectName).append('\n');
        sb.append("Files: ").append(paths.size()).append(languageLine(paths)).append('\n');
        appendBuild(sb, paths, rootFiles);
        appendLayout(sb, paths);
        appendEntryPoints(sb, mainJava);
        appendLayers(sb, mainJava);
        appendReadme(sb, rootFiles.get("README.md"));

        String text = sb.toString().strip();
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "\n...(overview truncated)" : text;
    }

    // ------------------------------------------------------------------ sections

    private static String languageLine(List<String> paths) {
        Map<String, Long> counts = paths.stream()
                .map(OverviewBuilder::language)
                .filter(l -> l != null)
                .collect(Collectors.groupingBy(l -> l, TreeMap::new, Collectors.counting()));
        String top = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(6)
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
        return top.isEmpty() ? "" : " (" + top + ")";
    }

    private static String language(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? null : LANGUAGES.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static void appendBuild(StringBuilder sb, List<String> paths, Map<String, String> rootFiles) {
        List<String> tools = new ArrayList<>();
        String pom = rootFiles.get("pom.xml");
        if (pom != null) {
            StringBuilder maven = new StringBuilder("Maven");
            String clean = DEP_MANAGEMENT.matcher(PARENT.matcher(pom).replaceAll(" ")).replaceAll(" ");
            Matcher id = ARTIFACT_ID.matcher(clean);
            if (id.find()) {
                maven.append(" (").append(id.group(1)).append(')');
            }
            Matcher java = JAVA_VERSION.matcher(pom);
            if (java.find()) {
                maven.append(", Java ").append(java.group(1));
            }
            tools.add(maven.toString());
            List<String> deps = pomDependencies(clean);
            if (!deps.isEmpty()) {
                sb.append("Key dependencies: ").append(String.join(", ", deps)).append('\n');
            }
        }
        if (has(paths, "build.gradle") || has(paths, "build.gradle.kts")) {
            tools.add("Gradle");
        }
        String packageJson = rootFiles.get("package.json");
        if (packageJson != null) {
            Matcher name = JSON_NAME.matcher(packageJson);
            tools.add("npm" + (name.find() ? " (" + name.group(1) + ")" : ""));
        }
        if (has(paths, "pyproject.toml") || has(paths, "requirements.txt") || has(paths, "setup.py")) {
            tools.add("Python packaging");
        }
        if (has(paths, "go.mod")) {
            tools.add("Go modules");
        }
        if (has(paths, "Cargo.toml")) {
            tools.add("Cargo");
        }
        if (has(paths, "Dockerfile")) {
            tools.add("Docker");
        }
        if (!tools.isEmpty()) {
            sb.append("Build and tooling: ").append(String.join(", ", tools)).append('\n');
        }
        List<String> modules = paths.stream()
                .filter(p -> p.contains("/") && (p.endsWith("/pom.xml") || p.endsWith("/build.gradle")
                        || p.endsWith("/package.json") || p.endsWith("/go.mod") || p.endsWith("/Cargo.toml")))
                .map(p -> p.substring(0, p.lastIndexOf('/')))
                .limit(10)
                .toList();
        if (!modules.isEmpty()) {
            sb.append("Modules or sub-projects: ").append(String.join(", ", modules)).append('\n');
        }
    }

    static List<String> pomDependencies(String cleanedPom) {
        List<String> result = new ArrayList<>();
        Matcher block = DEPENDENCIES.matcher(cleanedPom);
        if (block.find()) {
            Matcher id = ARTIFACT_ID.matcher(block.group(1));
            while (id.find() && result.size() < 12) {
                if (!id.group(1).endsWith("-test")) {
                    result.add(id.group(1));
                }
            }
        }
        return result;
    }

    private static boolean has(List<String> paths, String name) {
        return paths.contains(name);
    }

    private static void appendLayout(StringBuilder sb, List<String> paths) {
        Map<String, Long> byFolder = paths.stream()
                .collect(Collectors.groupingBy(p -> p.contains("/") ? p.substring(0, p.indexOf('/')) + "/" : "(root)",
                        TreeMap::new, Collectors.counting()));
        if (byFolder.size() <= 1 && byFolder.containsKey("(root)")) {
            return;
        }
        String layout = byFolder.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(10)
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
        sb.append("Top-level layout (files): ").append(layout).append('\n');
    }

    private static void appendEntryPoints(StringBuilder sb, List<JavaSourceSummary> java) {
        List<String> entries = java.stream()
                .filter(s -> s.hasMain() || s.roles().contains("SpringBootApplication"))
                .map(s -> s.primaryName() + " (" + s.path() + ")")
                .limit(8)
                .toList();
        if (!entries.isEmpty()) {
            sb.append("Java entry points: ").append(String.join(", ", entries)).append('\n');
        }
    }

    private static void appendLayers(StringBuilder sb, List<JavaSourceSummary> java) {
        Map<String, List<String>> layers = new LinkedHashMap<>();
        for (String role : LAYER_ORDER) {
            List<String> names = java.stream()
                    .filter(s -> s.roles().contains(role))
                    .map(JavaSourceSummary::primaryName)
                    .limit(8)
                    .toList();
            if (!names.isEmpty()) {
                layers.put(role, names);
            }
        }
        if (!layers.isEmpty()) {
            sb.append("Spring components by role:\n");
            layers.forEach((role, names) -> sb.append("- ").append(role).append(": ")
                    .append(String.join(", ", names)).append('\n'));
        }
    }

    private static void appendReadme(StringBuilder sb, String readme) {
        if (readme == null || readme.isBlank()) {
            return;
        }
        String compact = readme.strip().replaceAll("\\n{3,}", "\n\n");
        if (compact.length() > README_CHARS) {
            compact = compact.substring(0, README_CHARS) + "...";
        }
        sb.append("README (start):\n").append(compact).append('\n');
    }
}
