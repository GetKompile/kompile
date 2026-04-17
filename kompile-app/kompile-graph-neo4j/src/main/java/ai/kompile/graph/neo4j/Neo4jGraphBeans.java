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
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean definitions for Neo4j graph components.
 * This class is only loaded after Neo4jGraphConfiguration confirms Neo4j is on the classpath.
 */
@Configuration
@ConditionalOnClass(name = "org.neo4j.driver.Driver")
public class Neo4jGraphBeans {

    private static final String URI_EXPR = "'${kompile.graph.neo4j.uri:${neo4j.uri:}}' != ''";

    @Bean
    @ConditionalOnMissingBean(Driver.class)
    @ConditionalOnExpression(URI_EXPR)
    public Driver neo4jDriver(
            @Value("${kompile.graph.neo4j.uri:${neo4j.uri:}}") String uri,
            @Value("${kompile.graph.neo4j.username:${neo4j.username:neo4j}}") String username,
            @Value("${kompile.graph.neo4j.password:${neo4j.password:}}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

    @Bean
    @ConditionalOnExpression(URI_EXPR)
    public Neo4jGraphStorage neo4jGraphStorage(Driver driver) {
        return new Neo4jGraphStorage(driver);
    }

    @Bean
    @ConditionalOnExpression(URI_EXPR)
    public Neo4jGraphRagService neo4jGraphRagService(
            Driver driver,
            EmbeddingModel embeddingModel,
            LLMChat llmChat,
            KompileChatMemory chatMemory) {
        return new Neo4jGraphRagService(driver, embeddingModel, llmChat, chatMemory);
    }

    @Bean
    @ConditionalOnExpression(URI_EXPR)
    public Neo4jGraphConstructor neo4jGraphConstructor(
            Driver driver,
            LLMChat llmChat,
            ObjectMapper objectMapper,
            TextChunker textChunker,
            EntityResolutionService entityResolutionService) {
        return new Neo4jGraphConstructor(driver, llmChat, objectMapper, textChunker, entityResolutionService);
    }
}
