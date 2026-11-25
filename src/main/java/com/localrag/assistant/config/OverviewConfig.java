package com.localrag.assistant.config;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.localrag.assistant.service.IndexState;
import com.localrag.assistant.service.OverviewStore;

@Configuration
public class OverviewConfig {

    @Bean
    OverviewStore overviewStore(RagProperties properties) {
        return new OverviewStore(Path.of(properties.overviewPath()));
    }

    /** Lives next to the overview file, e.g. .local-assistant/index-state.properties */
    @Bean
    IndexState indexState(RagProperties properties) {
        return new IndexState(Path.of(properties.overviewPath()).resolveSibling("index-state.properties"));
    }
}
