package com.localrag.assistant;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Needs Ollama and PostgreSQL running, so it is disabled by default.
@Disabled("Requires Ollama + PGVector")
@SpringBootTest
class LocalAssistantApplicationTests {

    @Test
    void contextLoads() {
    }
}
