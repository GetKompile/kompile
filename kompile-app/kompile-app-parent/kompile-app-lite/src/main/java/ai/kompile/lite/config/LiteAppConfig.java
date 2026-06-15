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

package ai.kompile.lite.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PreDestroy;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Minimal Spring configuration for Kompile Lite.
 * Provides essential beans and shutdown handlers.
 */
@Configuration(proxyBeanMethods = false)
public class LiteAppConfig {

    private static final Logger logger = LoggerFactory.getLogger(LiteAppConfig.class);

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * Graceful shutdown for embedding models — runs FIRST during shutdown.
     */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class EmbeddingModelShutdownHandler {
        private static final Logger log = LoggerFactory.getLogger(EmbeddingModelShutdownHandler.class);

        private final ai.kompile.core.embeddings.EmbeddingModel embeddingModel;

        public EmbeddingModelShutdownHandler(
                @Autowired(required = false) ai.kompile.core.embeddings.EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        @PreDestroy
        public void shutdown() {
            if (embeddingModel != null) {
                log.info("Shutting down embedding model...");
                try {
                    embeddingModel.close();
                    log.info("Embedding model closed successfully");
                } catch (Exception e) {
                    log.warn("Error closing embedding model: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * ND4J cleanup — runs LAST during shutdown.
     */
    @Component
    @Order(Ordered.LOWEST_PRECEDENCE)
    public static class Nd4jCleanupHandler {
        private static final Logger log = LoggerFactory.getLogger(Nd4jCleanupHandler.class);

        @PreDestroy
        public void cleanup() {
            log.info("Cleaning up ND4J resources...");
            try {
                Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
                log.info("ND4J workspaces destroyed");
            } catch (Exception e) {
                log.warn("Error during ND4J cleanup: {}", e.getMessage());
            }

            // Terminate OpenBLAS threads
            try {
                Class<?> blasClass = Class.forName("org.bytedeco.openblas.global.openblas");
                java.lang.reflect.Method setNumThreads = blasClass.getMethod("openblas_set_num_threads", int.class);
                setNumThreads.invoke(null, 0);
                log.info("OpenBLAS threads terminated");
            } catch (Exception e) {
                log.debug("OpenBLAS cleanup skipped: {}", e.getMessage());
            }
        }
    }
}
