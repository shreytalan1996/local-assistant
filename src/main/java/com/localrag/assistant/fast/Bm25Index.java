package com.localrag.assistant.fast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * An in-memory keyword index using the BM25 ranking formula (the same family of scoring that search engines use).
 * Building it only needs counting words, so it takes seconds even for large repositories, with no database and
 * no embedding model.
 */
final class Bm25Index {

    record Hit(Chunk chunk, double score) {
    }

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private static final class Postings {
        int[] docs = new int[2];
        int[] counts = new int[2];
        int size;

        void add(int doc, int count) {
            if (size == docs.length) {
                docs = java.util.Arrays.copyOf(docs, size * 2);
                counts = java.util.Arrays.copyOf(counts, size * 2);
            }
            docs[size] = doc;
            counts[size] = count;
            size++;
        }
    }

    private final List<Chunk> chunks = new ArrayList<>();
    private int[] lengths = new int[1024];
    private long totalLength;
    private final Map<String, Postings> postings = new HashMap<>();

    /** Adds one chunk with its term counts (see {@link Tokenizer#count}). */
    void add(Chunk chunk, Tokenizer.Counted counted) {
        int doc = chunks.size();
        chunks.add(chunk);
        if (doc == lengths.length) {
            lengths = java.util.Arrays.copyOf(lengths, doc * 2);
        }
        lengths[doc] = counted.length();
        totalLength += counted.length();
        for (Map.Entry<String, Integer> e : counted.counts().entrySet()) {
            postings.computeIfAbsent(e.getKey(), k -> new Postings()).add(doc, e.getValue());
        }
    }

    int size() {
        return chunks.size();
    }

    /**
     * @param terms term to weight (1.0 for words the user typed, less for expansions)
     * @param limit maximum number of hits
     */
    List<Hit> search(Map<String, Double> terms, int limit) {
        int n = chunks.size();
        if (n == 0 || terms.isEmpty() || limit <= 0) {
            return List.of();
        }
        double averageLength = (double) totalLength / n;
        double[] scores = new double[n];

        for (Map.Entry<String, Double> term : terms.entrySet()) {
            Postings p = postings.get(term.getKey());
            if (p == null) {
                continue;
            }
            double idf = Math.log(1 + (n - p.size + 0.5) / (p.size + 0.5));
            double weight = term.getValue();
            for (int i = 0; i < p.size; i++) {
                int doc = p.docs[i];
                double tf = p.counts[i];
                double norm = K1 * (1 - B + B * lengths[doc] / averageLength);
                scores[doc] += weight * idf * (tf * (K1 + 1)) / (tf + norm);
            }
        }

        PriorityQueue<Hit> best = new PriorityQueue<>(Comparator.comparingDouble(Hit::score));
        for (int doc = 0; doc < n; doc++) {
            if (scores[doc] <= 0) {
                continue;
            }
            if (best.size() < limit) {
                best.add(new Hit(chunks.get(doc), scores[doc]));
            } else if (scores[doc] > best.peek().score()) {
                best.poll();
                best.add(new Hit(chunks.get(doc), scores[doc]));
            }
        }
        List<Hit> result = new ArrayList<>(best);
        result.sort(Comparator.comparingDouble(Hit::score).reversed());
        return result;
    }
}
