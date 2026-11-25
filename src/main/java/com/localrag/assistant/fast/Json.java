package com.localrag.assistant.fast;

import java.util.ArrayList;
import java.util.List;

/**
 * Just enough JSON for talking to Ollama, so the tool needs no libraries:
 * writing a string literal, and reading string fields out of a response line.
 */
final class Json {

    private Json() {
    }

    /** A JSON string literal, including the surrounding quotes. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Value of the first string field with this name, or null. */
    static String stringField(String json, String key) {
        int start = find(json, key, 0);
        return start < 0 ? null : read(json, start)[0];
    }

    /** Values of every string field with this name, in order. */
    static List<String> stringFields(String json, String key) {
        List<String> values = new ArrayList<>();
        int from = 0;
        int start;
        while ((start = find(json, key, from)) >= 0) {
            String[] parsed = read(json, start);
            values.add(parsed[0]);
            from = Integer.parseInt(parsed[1]);
        }
        return values;
    }

    /** The rows of a JSON array of number arrays, e.g. {@code "embeddings":[[0.1,0.2],[0.3,0.4]]}. */
    static List<float[]> numberRows(String json, String key) {
        List<float[]> rows = new ArrayList<>();
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) {
            return rows;
        }
        int i = json.indexOf('[', at);
        if (i < 0) {
            return rows;
        }
        int depth = 0;
        float[] row = new float[256];
        int size = 0;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
                if (depth == 2) {
                    size = 0;
                }
            } else if (c == ']') {
                if (depth == 2) {
                    rows.add(java.util.Arrays.copyOf(row, size));
                }
                depth--;
                if (depth == 0) {
                    break;
                }
            } else if (depth == 2 && (c == '-' || c == '+' || c == '.' || Character.isDigit(c))) {
                int end = i;
                while (end < json.length() && "+-.eE0123456789".indexOf(json.charAt(end)) >= 0) {
                    end++;
                }
                if (size == row.length) {
                    row = java.util.Arrays.copyOf(row, size * 2);
                }
                row[size++] = Float.parseFloat(json.substring(i, end));
                i = end - 1;
            }
        }
        return rows;
    }

    /** True when an Ollama stream line is the final one. */
    static boolean isDone(String line) {
        return line.contains("\"done\":true") || line.contains("\"done\": true");
    }

    // ------------------------------------------------------------------ internals

    /** Index of the first character of the string value of {@code "key": "..."}, or -1. */
    private static int find(String json, String key, int from) {
        String needle = "\"" + key + "\"";
        int i = from;
        while ((i = json.indexOf(needle, i)) >= 0) {
            int j = i + needle.length();
            j = skipSpaces(json, j);
            if (j < json.length() && json.charAt(j) == ':') {
                j = skipSpaces(json, j + 1);
                if (j < json.length() && json.charAt(j) == '"') {
                    return j + 1;
                }
            }
            i += needle.length();
        }
        return -1;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Reads a JSON string body starting after the opening quote. Returns {value, indexAfterClosingQuote}. */
    private static String[] read(String json, int start) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                return new String[] {sb.toString(), String.valueOf(i + 1)};
            }
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 5 < json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(next);   // \" \\ \/
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return new String[] {sb.toString(), String.valueOf(json.length())};
    }
}
