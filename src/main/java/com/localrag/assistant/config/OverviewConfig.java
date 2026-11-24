package com.localrag.assistant.config;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.localrag.assistant.service.OverviewStore;

@Configuration
public class OverviewConfig {

    @Bean
    OverviewStore overviewStore(RagProperties properties) {
        return new OverviewStore(Path.of(properties.overviewPath()));
    }
}
