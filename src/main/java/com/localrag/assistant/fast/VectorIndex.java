package com.localrag.assistant.fast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntConsumer;

/**
 * "Search by meaning". Every chunk is turned into a vector by an embedding model and a question is compared
 * with all of them. Embedding is slow, so it never blocks anything: vectors come from the disk cache when they
 * exist, the rest are made in the background, and a search simply uses whatever is ready.
 */
final class VectorIndex {

    private static final int BATCH = 16;
    private static final int SAVE_EVERY_BATCHES = 25;
    private static final int MAX_TEXT_CHARS = 2000;

    private final List<Chunk> chunks;
    private final String[] keys;
    private final AtomicReferenceArray<float[]> vectors;
    private final AtomicInteger ready = new AtomicInteger();
    private final OllamaClient client;
    private final String model;
    private final VectorCache cache;
    private volatile boolean paused;
    private volatile boolean stopped;
    private volatile String error;

    VectorIndex(List<Chunk> chunks, OllamaClient client, String model, VectorCache cache) {
        this.chunks = chunks;
        this.client = client;
        this.model = model;
        this.cache = cache;
        this.keys = new String[chunks.size()];
        this.vectors = new AtomicReferenceArray<>(chunks.size());
        for (int i = 0; i < keys.length; i++) {
            keys[i] = VectorCache.key(documentText(chunks.get(i)));
        }
    }

    /** The text that is embedded for a chunk (the prefix is what the nomic-embed-text model expects). */
    static String documentText(Chunk chunk) {
        String text = chunk.path() + "\n" + chunk.text();
        return "search_document: " + (text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text);
    }

    int ready() {
        return ready.get();
    }

    int total() {
        return chunks.size();
    }

    String error() {
        return error;
    }

    void pause(boolean value) {
        paused = value;
    }

    void stop() {
        stopped = true;
    }

    /** Fills in every vector that is already in the disk cache. Fast. Returns how many were found. */
    int loadCached() {
        Map<String, float[]> stored = cache.load();
        for (int i = 0; i < keys.length; i++) {
            float[] vector = stored.get(keys[i]);
            if (vector != null && vectors.compareAndSet(i, null, vector)) {
                ready.incrementAndGet();
            }
        }
        return ready.get();
    }

    /** Starts embedding the missing chunks on a background thread. */
    Thread startInBackground() {
        Thread thread = new Thread(() -> embedMissing(n -> { }), "embedding");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Embeds every chunk that has no vector yet, then saves the cache. Blocks until finished or failed. */
    void embedMissing(IntConsumer progress) {
        List<Integer> todo = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (vectors.get(i) == null && chunks.get(i).summary()) {
                todo.add(i);   // summaries first: small, and they answer broad questions
            }
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (vectors.get(i) == null && !chunks.get(i).summary()) {
                todo.add(i);
            }
        }
        try {
            int batches = 0;
            for (int from = 0; from < todo.size() && !stopped; from += BATCH) {
                while (paused && !stopped) {
                    Thread.sleep(100);
                }
                List<Integer> ids = todo.subList(from, Math.min(from + BATCH, todo.size()));
                List<String> texts = new ArrayList<>(ids.size());
                for (int id : ids) {
                    texts.add(documentText(chunks.get(id)));
                }
                List<float[]> embedded = client.embed(model, texts);
                for (int j = 0; j < ids.size(); j++) {
                    vectors.set(ids.get(j), normalize(embedded.get(j)));
                    ready.incrementAndGet();
                }
                progress.accept(ready.get());
                if (++batches % SAVE_EVERY_BATCHES == 0) {
                    saveCache();
                }
            }
            saveCache();
        } catch (IOException e) {
            error = e.getMessage();
            saveQuietly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void saveCache() throws IOException {
        Map<String, float[]> all = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            float[] v = vectors.get(i);
            if (v != null) {
                all.put(keys[i], v);
            }
        }
        cache.save(all);
    }

    private void saveQuietly() {
        try {
            saveCache();
        } catch (IOException e) {
            // the cache is only an optimisation
        }
    }

    /**
     * Chunk numbers ordered from the closest meaning to the furthest, using only chunks that are ready.
     * Empty when nothing is ready or the question cannot be embedded.
     */
    List<Integer> search(String query, int limit) {
        if (ready.get() == 0 || query.isBlank()) {
            return List.of();
        }
        float[] q;
        try {
            q = normalize(client.embed(model, List.of("search_query: " + query)).get(0));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        record Scored(int id, float score) {
        }
        PriorityQueue<Scored> best = new PriorityQueue<>(Comparator.comparingDouble(Scored::score));
        for (int i = 0; i < chunks.size(); i++) {
            float[] v = vectors.get(i);
            if (v == null || v.length != q.length) {
                continue;
            }
            float dot = 0;
            for (int j = 0; j < q.length; j++) {
                dot += q[j] * v[j];
            }
            if (best.size() < limit) {
                best.add(new Scored(i, dot));
            } else if (dot > best.peek().score()) {
                best.poll();
                best.add(new Scored(i, dot));
            }
        }
        List<Scored> sorted = new ArrayList<>(best);
        sorted.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Integer> ids = new ArrayList<>(sorted.size());
        for (Scored s : sorted) {
            ids.add(s.id());
        }
        return ids;
    }

    static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }
}
