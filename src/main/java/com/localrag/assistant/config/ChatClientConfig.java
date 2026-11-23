package com.localrag.assistant.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Wires the {@link ChatClient} with its two "advisors":
 * <ul>
 *   <li>memory  - remembers the last few messages of the conversation</li>
 *   <li>retrieval - searches the vector store and adds matching chunks to the prompt (the "R" in RAG)</li>
 * </ul>
 */
@Configuration
public class ChatClientConfig {

    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          VectorStore vectorStore,
                          ChatMemory chatMemory,
                          RagProperties properties,
                          @Value("classpath:prompts/rag-prompt.st") Resource ragPromptResource) {

        PromptTemplate ragPrompt = PromptTemplate.builder()
                .template(read(ragPromptResource))
                .build();

        SearchRequest searchRequest = SearchRequest.builder()
                .topK(properties.topK())
                .similarityThreshold(properties.similarityThreshold())
                .build();

        return builder
                .defaultSystem(properties.systemPrompt())
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(searchRequest)
                                .promptTemplate(ragPrompt)
                                .build())
                .build();
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read prompt template " + resource, e);
        }
    }
}
