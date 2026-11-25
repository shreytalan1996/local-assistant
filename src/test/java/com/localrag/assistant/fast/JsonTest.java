package com.localrag.assistant.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void quotesAndEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", Json.quote("a\"b\\c\nd"));
        assertEquals("\"\\u0001\"", Json.quote("\u0001"));
    }

    @Test
    void readsAStringFieldWithEscapes() {
        String line = "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"Hi \\\"there\\\"\\nline2 \\u00e9\"},\"done\":false}";
        assertEquals("Hi \"there\"\nline2 \u00e9", Json.stringField(line, "content"));
        assertFalse(Json.isDone(line));
        assertTrue(Json.isDone("{\"done\":true}"));
    }

    @Test
    void ignoresKeysThatAppearAsValues() {
        assertEquals("real", Json.stringField("{\"x\":\"content\",\"content\":\"real\"}", "content"));
        assertNull(Json.stringField("{\"a\":1}", "content"));
    }

    @Test
    void readsEveryNameInAModelList() {
        String json = "{\"models\":[{\"name\":\"llama3.2:3b\",\"size\":1},{\"name\":\"nomic-embed-text:latest\"}]}";
        assertEquals(List.of("llama3.2:3b", "nomic-embed-text:latest"), Json.stringFields(json, "name"));
    }

    @Test
    void roundTripsAwkwardText() {
        String text = "tab\there \"quoted\" back\\slash \u2603 emoji \uD83D\uDE00";
        assertEquals(text, Json.stringField("{\"content\":" + Json.quote(text) + "}", "content"));
    }
}
