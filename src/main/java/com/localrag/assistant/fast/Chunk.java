package com.localrag.assistant.fast;

/**
 * A searchable piece of the repository: a window of lines from one file, or a generated summary of a file.
 *
 * @param path      path relative to the repository root
 * @param startLine first line (1-based), 0 for a summary
 * @param endLine   last line, 0 for a summary
 * @param text      the text shown to the model
 * @param summary   true when the text is a generated summary rather than file content
 */
record Chunk(String path, int startLine, int endLine, String text, boolean summary) {

    /** Short label used in the answer context and in the source list, e.g. {@code src/App.java:10-40}. */
    String label() {
        return summary ? path + " (summary)" : path + ":" + startLine + "-" + endLine;
    }
}
