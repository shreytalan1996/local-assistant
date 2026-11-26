package com.localrag.assistant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A stand-in for the Ollama embedding model, used only in tests so they need no Ollama.
 * Texts that share words get similar vectors, which is enough to test search. It counts how many
 * texts it embedded, so a test can prove that a saved index was loaded instead of embedded again.
 */
class FakeEmbeddingModel implements EmbeddingModel {

    static final int DIMENSIONS = 16;

    final AtomicInteger embeddedTexts = new AtomicInteger();

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> result = new ArrayList<>();
        int index = 0;
        for (String text : request.getInstructions()) {
            embeddedTexts.incrementAndGet();
            result.add(new Embedding(vector(text), index++));
        }
        return new EmbeddingResponse(result);
    }

    @Override
    public float[] embed(Document document) {
        embeddedTexts.incrementAndGet();
        return vector(document.getText());
    }

    private static float[] vector(String text) {
        float[] v = new float[DIMENSIONS];
        for (String word : text.toLowerCase().split("\\W+")) {
            if (!word.isEmpty()) {
                v[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
            }
        }
        return v;
    }
}
