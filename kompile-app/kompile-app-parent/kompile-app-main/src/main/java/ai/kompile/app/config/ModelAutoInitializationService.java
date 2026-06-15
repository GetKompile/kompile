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

package ai.kompile.app.config;

import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatically initializes embedding models at startup with periodic polling.
 *
 * <p>On startup (with a delay), checks if the embedding model needs initialization
 * and attempts to load it. Polls periodically until the model is loaded, then stops.
 *
 * <p>VLM models are NOT loaded here — they run in a subprocess to avoid
 * competing for GPU memory with the main app.
 */
@Service
@ConditionalOnProperty(name = "kompile.models.auto-init.enabled", havingValue = "true", matchIfMissing = true)
public class ModelAutoInitializationService {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected ModelAutoInitializationService() {}


    private static final Logger log = LoggerFactory.getLogger(ModelAutoInitializationService.class);

    private AnseriniEmbeddingModelImpl embeddingModel;

    private final AtomicBoolean embeddingInitialized = new AtomicBoolean(false);
    private final AtomicBoolean initInProgress = new AtomicBoolean(false);

    @Value("${kompile.models.auto-init.embedding.enabled:true}")
    private boolean embeddingAutoInitEnabled;

    @Autowired
    public ModelAutoInitializationService(
            @Lazy @Autowired(required = false) AnseriniEmbeddingModelImpl embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Periodic check that runs after startup delay and every 60 seconds.
     * Stops checking once the embedding model is initialized.
     */
    @Scheduled(initialDelayString = "${kompile.models.auto-init.initial-delay-ms:10000}",
               fixedDelayString = "${kompile.models.auto-init.poll-interval-ms:60000}")
    public void checkAndInitializeModels() {
        if (embeddingInitialized.get()) {
            return;
        }
        if (!initInProgress.compareAndSet(false, true)) {
            return; // Another thread is already initializing
        }

        try {
            if (embeddingAutoInitEnabled && !embeddingInitialized.get()) {
                tryInitializeEmbedding();
            }

            if (!embeddingAutoInitEnabled || embeddingInitialized.get() || embeddingModel == null) {
                log.info("Model auto-initialization complete. Embedding: {}",
                        embeddingInitialized.get() ? "loaded" : "skipped/unavailable");
            }
        } catch (Exception e) {
            log.warn("Model auto-initialization check encountered an error: {}", e.getMessage(), e);
        } finally {
            initInProgress.set(false);
        }
    }

    private void tryInitializeEmbedding() {
        if (embeddingModel == null) {
            log.debug("Embedding model bean not available, skipping auto-init");
            embeddingInitialized.set(true);
            return;
        }

        try {
            if (embeddingModel.isInitialized()) {
                embeddingInitialized.set(true);
                log.info("Embedding model already initialized: {}", embeddingModel.getActiveModelId());
                return;
            }

            log.info("Attempting to auto-initialize embedding model...");
            boolean success = embeddingModel.reloadModel();
            if (success) {
                embeddingInitialized.set(true);
                log.info("Embedding model auto-initialized successfully: {} ({}D)",
                        embeddingModel.getActiveModelId(), embeddingModel.dimensions());
            } else {
                log.debug("Embedding model auto-initialization not yet successful, will retry");
            }
        } catch (Exception e) {
            log.warn("Embedding auto-init attempt failed: {}", e.getMessage(), e);
        }
    }

    public boolean isEmbeddingInitialized() {
        return embeddingInitialized.get();
    }
}
