package com.localrag.assistant.fast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns text into search terms, aware of how code is written:
 * {@code OrderService} becomes {@code order}, {@code service} and {@code orderservice},
 * {@code MAX_RETRY_COUNT} becomes {@code max}, {@code retry}, {@code count} and {@code maxretrycount}.
 * Question words and very common code words are dropped, and plurals are reduced
 * ({@code controllers} becomes {@code controller}), the same way for the index and for the query.
 */
final class Tokenizer {

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            // question and filler words
            "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could", "did", "do", "does",
            "for", "from", "had", "has", "have", "how", "i", "if", "in", "into", "is", "it", "its", "me", "my",
            "of", "on", "or", "our", "so", "than", "that", "the", "their", "them", "then", "there", "these",
            "this", "those", "to", "was", "we", "were", "what", "when", "where", "which", "who", "why", "will",
            "with", "would", "you", "your", "about", "also", "any", "all", "explain", "find", "give", "list",
            "please", "show", "tell", "use", "used", "using", "work", "works", "implemented", "implement",
            "get", "set",
            // words that appear in almost every source file
            "public", "private", "protected", "static", "final", "void", "return", "import", "package", "new",
            "null", "true", "false", "int", "long", "boolean", "string", "extends", "implements", "throws",
            "throw", "try", "catch", "else", "while", "super", "var", "val", "def", "fun", "let", "const",
            "function", "self", "end", "not", "elif"));

    private static final int MIN_LENGTH = 2;
    private static final int MAX_RUN_LENGTH = 40;   // longer runs are hashes, base64 and similar noise

    private Tokenizer() {
    }

    /** Terms of a piece of text, in order, repeats included. */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        forEachToken(text, out::add);
        return out;
    }

    /** Distinct terms of a piece of text, in order of first appearance. */
    static List<String> distinctTokens(String text) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(tokens(text)));
    }

    /**
     * Term counts for a chunk of a file. Terms from the file path count {@code pathWeight} times, so a file named
     * {@code OrderService.java} ranks high for "order service".
     */
    static Counted count(String text, String path, int pathWeight) {
        Map<String, Integer> counts = new HashMap<>();
        int[] length = {0};
        forEachToken(text, t -> {
            counts.merge(t, 1, Integer::sum);
            length[0]++;
        });
        forEachToken(path, t -> {
            counts.merge(t, pathWeight, Integer::sum);
            length[0] += pathWeight;
        });
        return new Counted(counts, Math.max(1, length[0]));
    }

    record Counted(Map<String, Integer> counts, int length) {
    }

    static String stem(String token) {
        int n = token.length();
        if (n > 4 && token.endsWith("ies")) {
            return token.substring(0, n - 3) + "y";
        }
        if (n > 3 && token.endsWith("s")
                && !token.endsWith("ss") && !token.endsWith("us") && !token.endsWith("is")) {
            return token.substring(0, n - 1);
        }
        return token;
    }

    // ------------------------------------------------------------------ internals

    private static void forEachToken(CharSequence text, Consumer<String> sink) {
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (isWordChar(text.charAt(i))) {
                int j = i + 1;
                while (j < n && isWordChar(text.charAt(j))) {
                    j++;
                }
                emitRun(text.subSequence(i, j).toString(), sink);
                i = j;
            } else {
                i++;
            }
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static void emitRun(String run, Consumer<String> sink) {
        if (run.length() < MIN_LENGTH || run.length() > MAX_RUN_LENGTH) {
            return;
        }
        List<String> parts = splitParts(run);
        if (parts.size() == 1) {
            emit(parts.get(0), sink);
            return;
        }
        StringBuilder whole = new StringBuilder();
        for (String part : parts) {
            whole.append(part);
            emit(part, sink);
        }
        if (whole.length() >= 4) {
            emit(whole.toString(), sink);
        }
    }

    /** Splits an identifier at underscores and camelCase boundaries, lower-cased. */
    private static List<String> splitParts(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '_') {
                flush(cur, parts);
                continue;
            }
            if (cur.length() > 0 && Character.isUpperCase(c)) {
                char prev = s.charAt(k - 1);
                boolean lowerToUpper = Character.isLowerCase(prev) || Character.isDigit(prev);
                boolean acronymEnd = Character.isUpperCase(prev)
                        && k + 1 < s.length() && Character.isLowerCase(s.charAt(k + 1));
                if (lowerToUpper || acronymEnd) {
                    flush(cur, parts);
                }
            }
            cur.append(Character.toLowerCase(c));
        }
        flush(cur, parts);
        return parts;
    }

    private static void flush(StringBuilder cur, List<String> parts) {
        if (cur.length() > 0) {
            parts.add(cur.toString());
            cur.setLength(0);
        }
    }

    private static void emit(String token, Consumer<String> sink) {
        if (token.length() < MIN_LENGTH || STOPWORDS.contains(token) || isAllDigits(token)) {
            return;
        }
        String stemmed = stem(token);
        if (!STOPWORDS.contains(stemmed)) {
            sink.accept(stemmed);
        }
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
