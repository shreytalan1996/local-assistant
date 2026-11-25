package com.localrag.assistant.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class Bm25IndexTest {

    private static void add(Bm25Index index, String path, String text) {
        index.add(new Chunk(path, 1, 5, text, false), Tokenizer.count(text, path, 3));
    }

    @Test
    void ranksTheChunkThatMentionsTheTermsFirst() {
        Bm25Index index = new Bm25Index();
        add(index, "a/Payment.java", "class Payment { void charge(Card card) { gateway.send(card); } }");
        add(index, "b/Mailer.java", "class Mailer { void send(Message m) { smtp.deliver(m); } }");
        add(index, "c/Readme.md", "This project processes orders and sends emails");

        List<Bm25Index.Hit> hits = index.search(Map.of("charge", 1.0, "card", 1.0), 3);

        assertEquals("a/Payment.java", hits.get(0).chunk().path());
    }

    @Test
    void rareTermsMatterMoreThanCommonOnes() {
        Bm25Index index = new Bm25Index();
        for (int i = 0; i < 20; i++) {
            add(index, "common" + i + ".txt", "service service service handler");
        }
        add(index, "rare.txt", "service reconciliation");

        List<Bm25Index.Hit> hits = index.search(Map.of("service", 1.0, "reconciliation", 1.0), 1);

        assertEquals("rare.txt", hits.get(0).chunk().path());
    }

    @Test
    void relatedTermsCountLessThanTypedOnes() {
        Bm25Index index = new Bm25Index();
        add(index, "typed.txt", "database connection");
        add(index, "related.txt", "jdbc connection");

        List<Bm25Index.Hit> hits = index.search(Map.of("database", 1.0, "jdbc", 0.4), 2);

        assertEquals("typed.txt", hits.get(0).chunk().path());
        assertTrue(hits.get(0).score() > hits.get(1).score());
    }

    @Test
    void returnsNothingForUnknownTermsOrAnEmptyIndex() {
        Bm25Index index = new Bm25Index();
        assertTrue(index.search(Map.of("x", 1.0), 5).isEmpty());
        add(index, "a.txt", "hello world");
        assertTrue(index.search(Map.of("zzz", 1.0), 5).isEmpty());
    }
}
