package com.localrag.assistant.config;

import java.nio.file.Path;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.localrag.assistant.service.IndexState;
import com.localrag.assistant.service.IndexWorkspace;
import com.localrag.assistant.service.OverviewStore;
import com.localrag.assistant.service.WorkspaceVectorStore;

/**
 * The saved index of the active source: where its files live and the objects that read and write them.
 * There is no database. Everything is kept in plain files inside the source's workspace folder.
 */
@Configuration
public class OverviewConfig {

    @Bean
    IndexWorkspace indexWorkspace(RagProperties properties) {
        return new IndexWorkspace(Path.of(properties.workspaceRoot()), properties.storeInProject());
    }

    /** The vector store used for retrieval: in memory, saved to and loaded from vector-store.json. */
    @Bean
    WorkspaceVectorStore vectorStore(EmbeddingModel embeddingModel) {
        return new WorkspaceVectorStore(embeddingModel);
    }

    @Bean
    OverviewStore overviewStore(IndexWorkspace workspace) {
        return new OverviewStore(workspace::overviewFile);
    }

    @Bean
    IndexState indexState(IndexWorkspace workspace) {
        return new IndexState(workspace::stateFile);
    }
}
