package com.localrag.assistant.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class HybridSearchTest {

    /** A tiny stand-in for Ollama's /api/embed: texts about "token" or "trusted" get one direction, the rest another. */
    private static HttpServer embedServer(AtomicInteger calls, boolean fail) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.incrementAndGet();
            if (fail) {
                byte[] body = "{\"error\":\"model not found\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            String inputs = request.substring(request.indexOf("\"input\""));
            StringBuilder body = new StringBuilder("{\"model\":\"m\",\"embeddings\":[");
            String[] parts = inputs.split("\",\"");
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].toLowerCase();
                boolean auth = p.contains("token") || p.contains("trusted");
                boolean pay = p.contains("charge") || p.contains("paymentcontroller");
                body.append(i > 0 ? "," : "").append(auth ? "[0.0,1.0,0.0]" : pay ? "[1.0,0.0,0.0]" : "[0.0,0.0,1.0]");
            }
            body.append("]}");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static Path repo() throws IOException {
        Path root = Files.createTempDirectory("hybrid");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/PaymentController.java"), """
                package shop;
                public class PaymentController {
                    public Receipt pay(Order order) { return paymentService.charge(order); }
                }
                """);
        Files.writeString(root.resolve("src/JwtFilter.java"), """
                package shop;
                public class JwtFilter {
                    public boolean verifyToken(String token) { return signer.valid(token); }
                }
                """);
        return root;
    }

    private static VectorCache cache() throws IOException {
        return new VectorCache(Files.createTempDirectory("cache").resolve("v.vec"));
    }

    private static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    @Test
    void meaningSearchFindsWhatKeywordsMiss() throws IOException {
        HttpServer server = embedServer(new AtomicInteger(), false);
        try {
            RepoIndex repo = RepoIndex.build(repo(), new RepoIndex.Options(false, true, 30, 5));
            String question = "who checks that the caller is trusted";

            // keyword search alone has no word in common with the right file
            List<Bm25Index.Hit> before = repo.search(question, null, 3, 5000);
            assertTrue(before.isEmpty() || !before.get(0).chunk().path().endsWith("JwtFilter.java"));

            VectorIndex vectors = repo.enableVectors(new OllamaClient(url(server), "x"), "m", cache());
            vectors.embedMissing(n -> { });
            assertEquals(vectors.total(), vectors.ready());

            List<Bm25Index.Hit> after = repo.search(question, null, 3, 5000);
            assertEquals("src/JwtFilter.java", after.get(0).chunk().path());
            assertTrue(after.get(0).score() <= 100.0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cachedVectorsAreReusedWithoutCallingTheModel() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = embedServer(calls, false);
        try {
            Path root = repo();
            VectorCache cache = cache();
            RepoIndex first = RepoIndex.build(root, new RepoIndex.Options(false, true, 30, 5));
            VectorIndex firstVectors = first.enableVectors(new OllamaClient(url(server), "x"), "m", cache);
            firstVectors.embedMissing(n -> { });
            int callsAfterFirstRun = calls.get();
            assertTrue(callsAfterFirstRun > 0);

            RepoIndex second = RepoIndex.build(root, new RepoIndex.Options(false, true, 30, 5));
            VectorIndex secondVectors = second.enableVectors(new OllamaClient(url(server), "x"), "m", cache);
            assertEquals(secondVectors.total(), secondVectors.ready());   // everything came from disk
            secondVectors.embedMissing(n -> { });
            assertEquals(callsAfterFirstRun, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aChangedFileIsEmbeddedAgainAndOthersAreNot() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = embedServer(calls, false);
        try {
            Path root = repo();
            VectorCache cache = cache();
            RepoIndex first = RepoIndex.build(root, new RepoIndex.Options(false, true, 30, 5));
            first.enableVectors(new OllamaClient(url(server), "x"), "m", cache).embedMissing(n -> { });

            Files.writeString(root.resolve("src/JwtFilter.java"), "package shop;\npublic class JwtFilter { int other; }\n");
            RepoIndex second = RepoIndex.build(root, new RepoIndex.Options(false, true, 30, 5));
            VectorIndex vectors = second.enableVectors(new OllamaClient(url(server), "x"), "m", cache);
            assertTrue(vectors.ready() > 0);
            assertTrue(vectors.ready() < vectors.total());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ifTheEmbeddingModelFailsKeywordSearchStillWorks() throws IOException {
        HttpServer server = embedServer(new AtomicInteger(), true);
        try {
            RepoIndex repo = RepoIndex.build(repo(), new RepoIndex.Options(false, true, 30, 5));
            VectorIndex vectors = repo.enableVectors(new OllamaClient(url(server), "x"), "m", cache());
            vectors.embedMissing(n -> { });

            assertEquals("model not found", vectors.error());
            assertEquals(0, vectors.ready());
            assertTrue(repo.vectorStatus().contains("stopped"));
            List<Bm25Index.Hit> hits = repo.search("which class handles payments", null, 3, 5000);
            assertEquals("src/PaymentController.java", hits.get(0).chunk().path());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embedClientParsesVectorsAndSendsAllTexts() throws IOException {
        HttpServer server = embedServer(new AtomicInteger(), false);
        try {
            List<float[]> vectors = new OllamaClient(url(server), "x").embed("m", List.of("a token", "plain", "charge it"));
            assertEquals(3, vectors.size());
            assertEquals(1.0, vectors.get(0)[1]);
            assertEquals(1.0, vectors.get(1)[2]);
            assertEquals(1.0, vectors.get(2)[0]);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void numberRowsParsesNestedArrays() {
        List<float[]> rows = Json.numberRows("{\"embeddings\":[[0.5,-1.25e0,3],[2,4]],\"x\":[[9]]}", "embeddings");
        assertEquals(2, rows.size());
        assertEquals(3, rows.get(0).length);
        assertEquals(-1.25, rows.get(0)[1]);
        assertEquals(4.0, rows.get(1)[1]);
        assertTrue(Json.numberRows("{}", "embeddings").isEmpty());
    }

    @Test
    void cacheRoundTripsAndIgnoresDamagedFiles() throws IOException {
        Path dir = Files.createTempDirectory("c");
        VectorCache cache = new VectorCache(dir.resolve("a.vec"));
        cache.save(Map.of("k1", new float[] {1f, 2f}, "k2", new float[] {3f}));
        Map<String, float[]> loaded = cache.load();
        assertEquals(2, loaded.size());
        assertEquals(2.0, loaded.get("k1")[1]);

        Files.writeString(dir.resolve("bad.vec"), "garbage");
        assertTrue(new VectorCache(dir.resolve("bad.vec")).load().isEmpty());
        assertTrue(new VectorCache(dir.resolve("missing.vec")).load().isEmpty());
        assertFalse(VectorCache.key("a").equals(VectorCache.key("b")));
        assertNull(loaded.get("nope"));
    }
}
