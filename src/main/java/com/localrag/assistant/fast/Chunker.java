package com.localrag.assistant.fast;

import java.util.ArrayList;
import java.util.List;

/** Cuts a file into overlapping windows of lines. Line numbers are kept so answers can point at them. */
final class Chunker {

    static final int MAX_LINE_CHARS = 240;
    static final int MAX_CHUNK_CHARS = 2500;

    private Chunker() {
    }

    static List<Chunk> chunk(String path, String content, int window, int overlap) {
        if (window <= overlap) {
            throw new IllegalArgumentException("window must be larger than overlap");
        }
        String[] lines = content.split("\n", -1);
        int total = lines.length;
        if (total > 0 && lines[total - 1].isEmpty()) {
            total--;   // text ended with a newline
        }

        List<Chunk> chunks = new ArrayList<>();
        int step = window - overlap;
        for (int start = 0; start < total; start += step) {
            int end = Math.min(start + window, total);
            StringBuilder text = new StringBuilder();
            for (int i = start; i < end; i++) {
                text.append(clip(lines[i])).append('\n');
            }
            String body = text.toString();
            if (!body.isBlank()) {
                if (body.length() > MAX_CHUNK_CHARS) {
                    body = body.substring(0, MAX_CHUNK_CHARS) + "...\n";
                }
                chunks.add(new Chunk(path, start + 1, end, body, false));
            }
            if (end == total) {
                break;
            }
        }
        return chunks;
    }

    private static String clip(String line) {
        String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        return trimmed.length() > MAX_LINE_CHARS ? trimmed.substring(0, MAX_LINE_CHARS) + "..." : trimmed;
    }
}
