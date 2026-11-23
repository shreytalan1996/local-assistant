package com.localrag.assistant.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/**
 * Phase 2 of RAG: answer a question. Retrieval and memory are handled by the
 * advisors configured in {@code ChatClientConfig}.
 *
 * <p>Kept separate from the console so it can later be reused by a REST controller.
 */
@Service
public class AssistantService {

    private final ChatClient chatClient;

    public AssistantService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** Streams the answer token by token. */
    public Flux<String> askStreaming(String question) {
        return chatClient.prompt()
                .user(question)
                .stream()
                .content();
    }

    /** Returns the full answer at once. */
    public String ask(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
