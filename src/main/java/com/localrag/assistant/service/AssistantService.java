package com.localrag.assistant.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.localrag.assistant.config.RagProperties;

import reactor.core.publisher.Flux;

/**
 * Phase 2 of RAG: answer a question. Retrieval and memory are handled by the advisors configured in
 * {@code ChatClientConfig}. When a project was indexed, its overview is added to the system prompt so broad
 * questions ("what does this project do?") can be answered, not only detail questions.
 *
 * <p>Kept separate from the console so it can later be reused by a REST controller.
 */
@Service
public class AssistantService {

    private final ChatClient chatClient;
    private final OverviewStore overviewStore;
    private final RagProperties properties;

    public AssistantService(ChatClient chatClient, OverviewStore overviewStore, RagProperties properties) {
        this.chatClient = chatClient;
        this.overviewStore = overviewStore;
        this.properties = properties;
    }

    /** Streams the answer token by token. */
    public Flux<String> askStreaming(String question) {
        return chatClient.prompt()
                .system(systemText())
                .user(question)
                .stream()
                .content();
    }

    /** Returns the full answer at once. */
    public String ask(String question) {
        return chatClient.prompt()
                .system(systemText())
                .user(question)
                .call()
                .content();
    }

    /** The overview of the indexed project, or an empty string for books. */
    public String overview() {
        return overviewStore.load();
    }

    private String systemText() {
        String overview = overviewStore.load();
        if (overview.isBlank()) {
            return properties.systemPrompt();
        }
        return properties.systemPrompt()
                + "\n\nOverview of the project you are answering about. Use it for broad questions"
                + " (what it does, how it is structured) and use the retrieved context for details:\n"
                + overview;
    }
}
