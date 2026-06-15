/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.neo4j;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.llm.memory.KompileChatMemory;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService;
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Bean definitions for Neo4j graph components.
 * This class is only loaded after Neo4jGraphConfiguration confirms Neo4j is on the classpath.
 *
 * <p>Neo4j connection settings are read from {@link KGEmbeddingConfigService} (stored in
 * {@code ~/.kompile/config/kg-embedding-config.json}) rather than Spring {@code @Value} properties.
 * Enable Neo4j and configure the URI/credentials via the UI under Knowledge Graph &rarr; Neo4j settings.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.neo4j.driver.Driver")
@Conditional(Neo4jEnabledCondition.class)
public class Neo4jGraphBeans {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphBeans.class);

    @Autowired
    private KGEmbeddingConfigService kgEmbeddingConfigService;

    /**
     * Creates the Neo4j Driver using connection settings from {@link KGEmbeddingConfigService}.
     */
    @Bean
    @ConditionalOnMissingBean(Driver.class)
    public Driver neo4jDriver() {
        KGEmbeddingConfigService.Neo4jConfig neo4jConfig = kgEmbeddingConfigService.getNeo4jConfig();
        log.info("Creating Neo4j Driver for URI: {}", neo4jConfig.uri());
        return GraphDatabase.driver(neo4jConfig.uri(),
                AuthTokens.basic(neo4jConfig.username(), neo4jConfig.password()));
    }

    @Bean
    public Neo4jGraphStorage neo4jGraphStorage(Driver driver) {
        return new Neo4jGraphStorage(driver);
    }

    @Bean
    public Neo4jGraphRagService neo4jGraphRagService(
            Driver driver,
            EmbeddingModel embeddingModel,
            LLMChat llmChat,
            KompileChatMemory chatMemory) {
        return new Neo4jGraphRagService(driver, embeddingModel, llmChat, chatMemory);
    }

    @Bean
    public Neo4jGraphConstructor neo4jGraphConstructor(
            Driver driver,
            LLMChat llmChat,
            ObjectMapper objectMapper,
            TextChunker textChunker,
            EntityResolutionService entityResolutionService) {
        return new Neo4jGraphConstructor(driver, llmChat, objectMapper, textChunker, entityResolutionService);
    }
}
