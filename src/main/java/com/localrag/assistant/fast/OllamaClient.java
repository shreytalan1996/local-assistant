package com.localrag.assistant.fast;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A small client for a local Ollama server, using only what the JDK provides. */
final class OllamaClient {

    record Message(String role, String content) {
    }

    private static final String KEEP_ALIVE = "30m";   // keeps the model in memory between questions

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String model;

    OllamaClient(String baseUrl, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
    }

    String model() {
        return model;
    }

    /** Names of the installed models, or null when Ollama cannot be reached. */
    List<String> installedModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200 ? Json.stringFields(response.body(), "name") : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Loads the model into memory without generating anything, so the first real question is quicker. */
    void warmUp() {
        try {
            String body = "{\"model\":" + Json.quote(model) + ",\"keep_alive\":\"" + KEEP_ALIVE + "\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            // warming up is only an optimisation
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Turns texts into vectors (lists of numbers that capture meaning) with an embedding model.
     * Returns one vector per text, in the same order.
     */
    List<float[]> embed(String embeddingModel, List<String> texts) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":").append(Json.quote(embeddingModel))
                .append(",\"keep_alive\":\"").append(KEEP_ALIVE).append("\",\"input\":[");
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(Json.quote(texts.get(i)));
        }
        body.append("]}");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/embed"))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                String error = Json.stringField(response.body(), "error");
                throw new IOException(error != null ? error : "Ollama answered with HTTP " + response.statusCode());
            }
            List<float[]> vectors = Json.numberRows(response.body(), "embeddings");
            if (vectors.size() != texts.size()) {
                throw new IOException("Ollama returned " + vectors.size() + " vectors for " + texts.size() + " texts");
            }
            return vectors;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Ollama", e);
        }
    }

    /**
     * Sends a conversation and streams the reply. {@code onToken} is called with each piece of text as it
     * arrives. Returns the complete reply.
     */
    String chat(List<Message> messages, int contextTokens, Consumer<String> onToken) throws IOException {
        String body = chatRequest(messages, contextTokens);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();

        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Ollama", e);
        }

        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                String text = lines.collect(Collectors.joining("\n"));
                String error = Json.stringField(text, "error");
                throw new IOException(error != null ? error : "Ollama answered with HTTP " + response.statusCode());
            }
            StringBuilder reply = new StringBuilder();
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line.isBlank()) {
                    continue;
                }
                String error = Json.stringField(line, "error");
                if (error != null) {
                    throw new IOException(error);
                }
                String piece = Json.stringField(line, "content");
                if (piece != null && !piece.isEmpty()) {
                    reply.append(piece);
                    onToken.accept(piece);
                }
                if (Json.isDone(line)) {
                    break;
                }
            }
            return reply.toString();
        }
    }

    String chatRequest(List<Message> messages, int contextTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":").append(Json.quote(model))
                .append(",\"stream\":true,\"keep_alive\":\"").append(KEEP_ALIVE).append('"')
                .append(",\"options\":{\"num_ctx\":").append(contextTokens).append(",\"temperature\":0.1}")
                .append(",\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Message m = messages.get(i);
            sb.append("{\"role\":").append(Json.quote(m.role())).append(",\"content\":").append(Json.quote(m.content())).append('}');
        }
        return sb.append("]}").toString();
    }
}
