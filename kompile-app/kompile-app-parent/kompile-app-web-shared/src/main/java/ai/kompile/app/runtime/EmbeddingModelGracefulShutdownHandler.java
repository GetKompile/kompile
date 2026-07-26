/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.runtime;

import ai.kompile.core.embeddings.EmbeddingModel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Component to initiate graceful shutdown of embedding models EARLY in the
 * shutdown process.
 * This runs BEFORE the Nd4jCleanupAndExitHandler to ensure that:
 * 1. New encoding operations are rejected
 * 2. Active encoding operations are allowed to complete
 * 3. Native tokenizer resources are freed AFTER all operations complete
 *
 * Using HIGHEST_PRECEDENCE to run FIRST (before other @PreDestroy handlers).
 *
 * <p>Lives in {@code kompile-app-web-shared} so that every persona process — admin, chat and
 * crawl-manager — shuts its embedding model down the same way; it used to be a nested class of
 * {@code MainApplication} and so was admin-only.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmbeddingModelGracefulShutdownHandler {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelGracefulShutdownHandler.class);

    private final EmbeddingModel embeddingModel;

    public EmbeddingModelGracefulShutdownHandler(
            @Autowired(required = false) EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @PreDestroy
    public void initiateGracefulShutdown() {
        log.info("=== Initiating graceful shutdown of embedding models ===");

        if (embeddingModel == null) {
            log.info("No embedding model configured, skipping graceful shutdown");
            return;
        }

        // Check if this is an AnseriniEmbeddingModelImpl which supports graceful
        // shutdown
        if (embeddingModel instanceof ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl) {
            ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl anseriniModel = (ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl) embeddingModel;

            log.info("Initiating graceful shutdown for Anserini embedding model");
            try {
                anseriniModel.initiateShutdown();
                log.info("Graceful shutdown initiated - new operations will be rejected");
            } catch (Exception e) {
                log.warn("Error initiating graceful shutdown for embedding model", e);
            }
        } else {
            log.info("Embedding model does not support graceful shutdown: {}",
                    embeddingModel.getClass().getName());
        }

        log.info("=== Embedding model graceful shutdown initiated ===");
    }
}
