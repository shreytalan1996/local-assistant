package com.localrag.assistant.fast;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyword search cannot know that "authentication" and "JWT" are related. This adds a few well-known related
 * words for common topics (security, database, configuration...), with a lower weight than the user's own words.
 */
final class QueryExpander {

    static final double ORIGINAL_WEIGHT = 1.0;
    static final double RELATED_WEIGHT = 0.4;

    private static final List<List<String>> GROUPS = List.of(
            List.of("auth", "authentication", "authorization", "login", "logout", "security", "jwt", "oauth",
                    "token", "password", "credential", "session"),
            List.of("database", "db", "sql", "jpa", "repository", "entity", "datasource", "persistence",
                    "hibernate", "jdbc", "table", "migration"),
            List.of("config", "configuration", "setting", "property", "properties", "yaml", "yml", "env",
                    "environment", "bean"),
            List.of("api", "endpoint", "rest", "controller", "route", "http", "request", "mapping", "handler",
                    "resource"),
            List.of("error", "exception", "fail", "failure", "handler", "advice", "throw"),
            List.of("test", "testing", "junit", "mock", "assert", "spec"),
            List.of("build", "maven", "gradle", "pom", "dependency", "plugin", "package"),
            List.of("deploy", "docker", "dockerfile", "kubernetes", "container", "compose", "helm", "pipeline"),
            List.of("log", "logger", "logging", "slf4j", "logback"),
            List.of("cache", "caching", "redis", "ttl", "evict"),
            List.of("queue", "message", "messaging", "kafka", "rabbitmq", "event", "listener", "publish",
                    "consumer", "producer"),
            List.of("start", "startup", "main", "entry", "run", "boot", "bootstrap", "application"));

    /** Stemmed word to the indexes of the groups it belongs to. */
    private static final Map<String, List<Integer>> GROUPS_BY_WORD = new HashMap<>();

    static {
        for (int g = 0; g < GROUPS.size(); g++) {
            for (String word : GROUPS.get(g)) {
                GROUPS_BY_WORD.computeIfAbsent(Tokenizer.stem(word), k -> new java.util.ArrayList<>()).add(g);
            }
        }
    }

    private QueryExpander() {
    }

    /** The user's terms at full weight, plus related terms at a lower weight. */
    static Map<String, Double> weightedTerms(List<String> queryTerms) {
        Map<String, Double> terms = new LinkedHashMap<>();
        for (String term : queryTerms) {
            terms.put(term, ORIGINAL_WEIGHT);
        }
        for (String term : queryTerms) {
            for (int group : GROUPS_BY_WORD.getOrDefault(term, List.of())) {
                for (String related : GROUPS.get(group)) {
                    terms.putIfAbsent(Tokenizer.stem(related), RELATED_WEIGHT);
                }
            }
        }
        return terms;
    }
}
