package com.localrag.assistant.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds a compact overview of a project: build tool, key dependencies, entry points,
 * Spring layers, main packages and the start of the README.
 *
 * <p>The overview is given to the model with every question. Retrieval finds details, but broad
 * questions ("what does this project do?", "how is it structured?") need this big picture.
 */
public final class ProjectOverviewBuilder {

    static final int MAX_CHARS = 6000;
    private static final int MAX_DEPENDENCIES = 15;
    private static final int MAX_PER_LAYER = 8;
    private static final int MAX_PACKAGES = 12;
    private static final int README_CHARS = 1200;

    private static final List<String> LAYER_ORDER = List.of(
            "RestController", "Controller", "Service", "Repository", "Component",
            "Configuration", "ConfigurationProperties", "Entity");

    private static final Pattern PARENT = Pattern.compile("(?s)<parent>.*?</parent>");
    private static final Pattern DEP_MANAGEMENT = Pattern.compile("(?s)<dependencyManagement>.*?</dependencyManagement>");
    private static final Pattern DEPENDENCIES = Pattern.compile("(?s)<dependencies>(.*?)</dependencies>");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern JAVA_VERSION = Pattern.compile("<java.version>\\s*([^<\\s]+)\\s*</java.version>");

    private ProjectOverviewBuilder() {
    }

    /**
     * @param projectName   folder name of the project
     * @param paths         relative paths of all indexed files
     * @param javaSummaries one summary per Java file
     * @param pomXml        content of the root pom.xml, or null
     * @param readme        content of the root README.md, or null
     */
    public static String build(String projectName,
                               List<String> paths,
                               List<JavaSourceSummary> javaSummaries,
                               String pomXml,
                               String readme) {
        // test classes are indexed for search, but they would distort the picture of the application
        List<JavaSourceSummary> mainJava = javaSummaries.stream().filter(j -> !j.isTest()).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(projectName).append('\n');
        int testFiles = javaSummaries.size() - mainJava.size();
        sb.append("Indexed files: ").append(paths.size())
                .append(" (").append(javaSummaries.size()).append(" Java")
                .append(testFiles > 0 ? ", " + testFiles + " of them tests" : "").append(")\n");

        appendBuild(sb, paths, pomXml);
        appendEntryPoints(sb, mainJava);
        appendLayers(sb, mainJava);
        appendPackages(sb, mainJava);
        appendReadme(sb, readme);

        String text = sb.toString().strip();
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "\n...(overview truncated)" : text;
    }

    // ------------------------------------------------------------------ sections

    private static void appendBuild(StringBuilder sb, List<String> paths, String pomXml) {
        if (pomXml != null) {
            sb.append("Build: Maven");
            String clean = DEP_MANAGEMENT.matcher(PARENT.matcher(pomXml).replaceAll(" ")).replaceAll(" ");
            Matcher id = ARTIFACT_ID.matcher(clean);
            if (id.find()) {
                sb.append(", artifactId ").append(id.group(1));
            }
            Matcher java = JAVA_VERSION.matcher(pomXml);
            if (java.find()) {
                sb.append(", Java ").append(java.group(1));
            }
            sb.append('\n');
            List<String> deps = pomDependencies(clean);
            if (!deps.isEmpty()) {
                sb.append("Key dependencies: ").append(String.join(", ", deps)).append('\n');
            }
        } else if (paths.stream().anyMatch(p -> p.equals("build.gradle") || p.equals("build.gradle.kts"))) {
            sb.append("Build: Gradle\n");
        }
    }

    static List<String> pomDependencies(String cleanedPom) {
        List<String> result = new ArrayList<>();
        Matcher block = DEPENDENCIES.matcher(cleanedPom);
        if (block.find()) {
            Matcher id = ARTIFACT_ID.matcher(block.group(1));
            while (id.find() && result.size() < MAX_DEPENDENCIES) {
                if (!id.group(1).endsWith("-test")) {
                    result.add(id.group(1));
                }
            }
        }
        return result;
    }

    private static void appendEntryPoints(StringBuilder sb, List<JavaSourceSummary> java) {
        List<String> entries = java.stream()
                .filter(s -> s.hasMain() || s.roles().contains("SpringBootApplication"))
                .map(s -> s.primaryName() + " (" + s.path() + ")")
                .toList();
        if (!entries.isEmpty()) {
            sb.append("Entry points: ").append(String.join(", ", entries)).append('\n');
        }
    }

    private static void appendLayers(StringBuilder sb, List<JavaSourceSummary> java) {
        Map<String, List<String>> layers = new LinkedHashMap<>();
        for (String role : LAYER_ORDER) {
            List<String> names = java.stream()
                    .filter(s -> s.roles().contains(role))
                    .map(JavaSourceSummary::primaryName)
                    .limit(MAX_PER_LAYER)
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

    private static void appendPackages(StringBuilder sb, List<JavaSourceSummary> java) {
        Map<String, Long> counts = java.stream()
                .filter(s -> !s.packageName().isEmpty())
                .collect(Collectors.groupingBy(JavaSourceSummary::packageName, TreeMap::new, Collectors.counting()));
        if (counts.isEmpty()) {
            return;
        }
        sb.append("Main packages (Java files):\n");
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_PACKAGES)
                .forEach(e -> sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n'));
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
