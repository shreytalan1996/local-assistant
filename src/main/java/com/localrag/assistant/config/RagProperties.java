package com.localrag.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed settings bound from the {@code rag.*} section of application.yml.
 *
 * @param documentPath        location of the book (.pdf or .txt), e.g. {@code file:./documents/book.txt}
 * @param systemPrompt        instructions that define the assistant's role
 * @param chunkSize           approximate chunk size (in tokens) used when splitting text
 * @param topK                how many matching chunks are sent to the model with each question
 * @param similarityThreshold minimum similarity (0.0 - 1.0) for a chunk to be used; 0 accepts everything
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        @DefaultValue("file:./documents/book.txt") String documentPath,
        @DefaultValue("You answer questions about the provided material using only that material.") String systemPrompt,
        @DefaultValue("400") int chunkSize,
        @DefaultValue("5") int topK,
        @DefaultValue("0.0") double similarityThreshold) {
}
