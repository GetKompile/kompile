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
package ai.kompile.knowledgegraph.matrix.config;

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.matrix.store.VectorStoreMatrixGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for matrix-based graph storage.
 * <p>
 * This configuration is always active when a VectorStore bean is available.
 * The matrix-based graph storage is the default Graph RAG implementation
 * that works without any external dependencies.
 * </p>
 *
 * <h2>Optional Configuration Properties</h2>
 * <pre>
 * # Optional: Initial graph capacity (default: 1024)
 * kompile.graph.matrix.initial-capacity=2048
 * </pre>
 */
@Configuration
@Slf4j
public class MatrixGraphConfig {

    /**
     * Creates a MatrixGraphStore backed by the configured VectorStore.
     *
     * @param vectorStore  The vector store for persistence
     * @param objectMapper JSON serializer
     * @return MatrixGraphStore instance
     */
    @Bean
    @ConditionalOnBean(VectorStore.class)
    public MatrixGraphStore matrixGraphStore(VectorStore vectorStore, ObjectMapper objectMapper) {
        log.info("Initializing Matrix-based graph store with VectorStore persistence");
        return new VectorStoreMatrixGraphStore(vectorStore, objectMapper);
    }
}
