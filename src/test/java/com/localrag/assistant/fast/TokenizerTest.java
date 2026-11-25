package com.localrag.assistant.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TokenizerTest {

    @Test
    void splitsCamelCaseAndKeepsTheWholeIdentifier() {
        List<String> tokens = Tokenizer.tokens("OrderService");
        assertTrue(tokens.contains("order"));
        assertTrue(tokens.contains("service"));
        assertTrue(tokens.contains("orderservice"));
    }

    @Test
    void splitsSnakeCaseAndAcronyms() {
        assertTrue(Tokenizer.tokens("MAX_RETRY_COUNT").containsAll(List.of("max", "retry", "count", "maxretrycount")));
        List<String> http = Tokenizer.tokens("HTTPServer");
        assertTrue(http.contains("http"));
        assertTrue(http.contains("server"));
    }

    @Test
    void dropsQuestionWordsCodeKeywordsAndNumbers() {
        List<String> tokens = Tokenizer.tokens("What does the public static void main do with 12345");
        assertEquals(List.of("main"), tokens);
    }

    @Test
    void reducesPluralsTheSameWayEverywhere() {
        assertEquals("controller", Tokenizer.stem("controllers"));
        assertEquals("property", Tokenizer.stem("properties"));
        assertEquals("class", Tokenizer.stem("class"));
        assertEquals("status", Tokenizer.stem("status"));
        assertEquals(Tokenizer.tokens("controllers"), Tokenizer.tokens("controller"));
    }

    @Test
    void pathWordsCountMoreThanBodyWords() {
        Tokenizer.Counted counted = Tokenizer.count("nothing here", "src/OrderService.java", 3);
        assertEquals(3, counted.counts().get("order"));
        assertTrue(counted.counts().containsKey("nothing"));
    }

    @Test
    void ignoresHashLikeNoise() {
        assertFalse(Tokenizer.tokens("a".repeat(60)).iterator().hasNext());
    }
}
