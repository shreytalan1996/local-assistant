package com.localrag.assistant.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A short, human-readable description of one Java file: its package, the types it declares,
 * its Spring role (Service, RestController...), whether it has a main method, and its public methods.
 *
 * <p>It is built with simple regular expressions, so it is approximate on purpose. It is indexed next to
 * the file content so questions like "which class handles X?" match a compact description instead of
 * having to find the right slice of code.
 */
public record JavaSourceSummary(String path,
                                String packageName,
                                List<String> types,
                                List<String> roles,
                                List<String> methods,
                                boolean hasMain) {

    private static final int MAX_TYPES = 6;
    private static final int MAX_METHODS = 12;

    private static final List<String> KNOWN_ROLES = List.of(
            "SpringBootApplication", "RestController", "Controller", "Service", "Repository",
            "Component", "Configuration", "ConfigurationProperties", "Entity");

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\\s+)*"
                    + "(class|interface|record|enum)\\s+(\\w+)");
    private static final Pattern ROLE = Pattern.compile("@(" + String.join("|", KNOWN_ROLES) + ")\\b");
    private static final Pattern METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:public|protected)\\s+(?:(?:static|final|synchronized|abstract|default)\\s+)*"
                    + "[\\w<>\\[\\],.? ]+?\\s+(\\w+)\\s*\\(");
    private static final Pattern MAIN = Pattern.compile("\\bstatic\\s+void\\s+main\\s*\\(");

    /** Analyses Java source text. {@code path} is only used for labelling. */
    public static JavaSourceSummary of(String path, String source) {
        String code = stripComments(source);

        Matcher pkg = PACKAGE.matcher(code);
        String packageName = pkg.find() ? pkg.group(1) : "";

        List<String> types = new ArrayList<>();
        Matcher type = TYPE.matcher(code);
        while (type.find() && types.size() < MAX_TYPES) {
            types.add(type.group(1) + " " + type.group(2));
        }

        Set<String> foundRoles = new LinkedHashSet<>();
        Matcher role = ROLE.matcher(code);
        while (role.find()) {
            foundRoles.add(role.group(1));
        }
        List<String> roles = KNOWN_ROLES.stream().filter(foundRoles::contains).toList();

        Set<String> methodNames = new LinkedHashSet<>();
        Matcher method = METHOD.matcher(code);
        while (method.find() && methodNames.size() < MAX_METHODS) {
            methodNames.add(method.group(1));
        }

        return new JavaSourceSummary(path, packageName, List.copyOf(types), roles,
                List.copyOf(methodNames), MAIN.matcher(code).find());
    }

    /** True for files under a {@code src/test} folder. They are indexed but left out of the project overview. */
    public boolean isTest() {
        return path.startsWith("src/test/") || path.contains("/src/test/");
    }

    /** Name of the first declared type, or the file name when none was found. */
    public String primaryName() {
        if (!types.isEmpty()) {
            String first = types.get(0);
            return first.substring(first.indexOf(' ') + 1);
        }
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
    }

    /** The text that is indexed. It starts with the file name, like every other chunk. */
    public String toText() {
        StringBuilder sb = new StringBuilder("File: ").append(path).append("\nSummary:");
        if (!packageName.isEmpty()) {
            sb.append(" package ").append(packageName).append(';');
        }
        if (!types.isEmpty()) {
            sb.append(" declares ").append(String.join(", ", types)).append(';');
        }
        if (!roles.isEmpty()) {
            sb.append(" Spring roles: ").append(String.join(", ", roles)).append(';');
        }
        if (hasMain) {
            sb.append(" has a main method (entry point);");
        }
        if (!methods.isEmpty()) {
            sb.append(" public methods: ").append(String.join(", ", methods)).append('.');
        }
        return sb.toString();
    }

    private static String stripComments(String source) {
        // text blocks first (code samples inside them are not real declarations), then comments
        return source.replaceAll("(?s)\"\"\".*?\"\"\"", "\"\"")
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }
}
