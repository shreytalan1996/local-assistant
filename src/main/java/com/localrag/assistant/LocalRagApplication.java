package com.localrag.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point. Starts a console assistant that answers questions about a PDF.
 *
 * <p>Run with {@code --fillVectorStore} the first time to index the document.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LocalRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalRagApplication.class, args);
    }
}
