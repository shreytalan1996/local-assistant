package com.localrag.assistant.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class OllamaClientTest {

    private static HttpServer server(AtomicReference<String> lastBody, int chatStatus, String chatReply) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[{\"name\":\"llama3.2:3b\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/chat", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = chatReply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(chatStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void streamsTokensAndSendsTheConversation() throws IOException {
        AtomicReference<String> sent = new AtomicReference<>();
        String reply = "{\"message\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"done\":false}\n"
                + "{\"message\":{\"role\":\"assistant\",\"content\":\"lo \\\"you\\\"\"},\"done\":false}\n"
                + "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}\n";
        HttpServer server = server(sent, 200, reply);
        try {
            OllamaClient client = new OllamaClient(url(server), "llama3.2:3b");
            List<String> pieces = new ArrayList<>();

            String full = client.chat(List.of(
                    new OllamaClient.Message("system", "be brief"),
                    new OllamaClient.Message("user", "say \"hi\"\nplease")), 4096, pieces::add);

            assertEquals("Hello \"you\"", full);
            assertEquals(List.of("Hel", "lo \"you\""), pieces);
            assertTrue(sent.get().contains("\"model\":\"llama3.2:3b\""));
            assertTrue(sent.get().contains("\"stream\":true"));
            assertTrue(sent.get().contains("\"num_ctx\":4096"));
            assertTrue(sent.get().contains("\"keep_alive\":\"30m\""));
            assertTrue(sent.get().contains("say \\\"hi\\\"\\nplease"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void turnsOllamaErrorsIntoReadableMessages() throws IOException {
        HttpServer server = server(new AtomicReference<>(), 404, "{\"error\":\"model \\\"nope\\\" not found, try pulling it first\"}");
        try {
            OllamaClient client = new OllamaClient(url(server), "nope");
            IOException e = assertThrows(IOException.class, () -> client.chat(List.of(new OllamaClient.Message("user", "x")), 2048, t -> { }));
            assertTrue(e.getMessage().contains("not found"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listsInstalledModelsAndReportsAnUnreachableServer() throws IOException {
        HttpServer server = server(new AtomicReference<>(), 200, "");
        OllamaClient client = new OllamaClient(url(server), "llama3.2:3b");
        assertEquals(List.of("llama3.2:3b"), client.installedModels());
        server.stop(0);

        assertNull(new OllamaClient("http://127.0.0.1:1", "x").installedModels());
    }
}
