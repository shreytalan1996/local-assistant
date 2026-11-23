package com.localrag.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GutenbergStripTest {

    @Test
    void keepsOnlyTheBookBetweenMarkers() {
        String raw = """
                Licence text before
                *** START OF THE PROJECT GUTENBERG EBOOK SAMPLE ***
                Chapter 1
                The real content.
                *** END OF THE PROJECT GUTENBERG EBOOK SAMPLE ***
                Licence text after
                """;

        String result = DocumentIngestionService.stripGutenbergLicence(raw);

        assertThat(result).isEqualTo("Chapter 1\nThe real content.");
    }

    @Test
    void leavesTextWithoutMarkersUntouchedExceptWhitespace() {
        assertThat(DocumentIngestionService.stripGutenbergLicence("  plain text \n")).isEqualTo("plain text");
    }
}
