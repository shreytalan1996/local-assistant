package com.localrag.assistant.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class ChunkerTest {

    private static String lines(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> "line " + i).collect(Collectors.joining("\n")) + "\n";
    }

    @Test
    void smallFilesBecomeOneChunk() {
        List<Chunk> chunks = Chunker.chunk("A.java", lines(10), 30, 5);
        assertEquals(1, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(10, chunks.get(0).endLine());
    }

    @Test
    void longFilesOverlapAndKeepLineNumbers() {
        List<Chunk> chunks = Chunker.chunk("A.java", lines(70), 30, 5);

        assertEquals(3, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(30, chunks.get(0).endLine());
        assertEquals(26, chunks.get(1).startLine());   // 5 lines overlap
        assertEquals(51, chunks.get(2).startLine());
        assertEquals(70, chunks.get(2).endLine());
        assertEquals("A.java:26-55", chunks.get(1).label());
    }

    @Test
    void blankFilesGiveNoChunksAndHugeLinesAreClipped() {
        assertTrue(Chunker.chunk("blank.txt", "\n\n\n", 30, 5).isEmpty());
        String huge = "x".repeat(5000) + "\n";
        assertTrue(Chunker.chunk("big.txt", huge, 30, 5).get(0).text().length() < 400);
    }

    @Test
    void windowMustBeLargerThanOverlap() {
        assertThrows(IllegalArgumentException.class, () -> Chunker.chunk("a", "x", 5, 5));
    }
}
