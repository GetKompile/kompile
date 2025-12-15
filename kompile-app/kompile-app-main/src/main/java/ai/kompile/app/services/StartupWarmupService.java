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

package ai.kompile.app.services;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.core.embeddings.EmbeddingModel;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service that preloads and warms up expensive resources at application startup.
 * This eliminates the "cold start" penalty on the first document upload.
 *
 * Resources preloaded:
 * - Embedding model (SameDiff/Anserini encoder) - saves 3-7 seconds
 * - OpenNLP sentence detection models - saves 0.5-2 seconds
 * - PDF parsing libraries (optional) - saves ~200ms
 *
 * Warmup is performed asynchronously to not block application startup.
 */
@Service
public class StartupWarmupService {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmupService.class);

    private final EmbeddingModel embeddingModel;
    private final TextChunker textChunker;
    private final SubprocessConfigService subprocessConfigService;

    @Value("${kompile.warmup.enabled:true}")
    private boolean warmupEnabled;

    @Value("${kompile.warmup.async:true}")
    private boolean warmupAsync;

    @Value("${kompile.warmup.embedding:true}")
    private boolean warmupEmbedding;

    @Value("${kompile.warmup.chunker:true}")
    private boolean warmupChunker;

    private final AtomicBoolean warmupComplete = new AtomicBoolean(false);
    private final AtomicBoolean embeddingReady = new AtomicBoolean(false);
    private final AtomicBoolean chunkerReady = new AtomicBoolean(false);

    // Track current warmup phase for better UI feedback
    private final AtomicReference<String> currentPhase = new AtomicReference<>("idle");
    private final AtomicBoolean modelDownloadRequired = new AtomicBoolean(false);

    private long warmupStartTime;
    private long embeddingWarmupTime;
    private long chunkerWarmupTime;

    @Autowired
    public StartupWarmupService(
            @Autowired(required = false) @org.springframework.context.annotation.Lazy EmbeddingModel embeddingModel,
            @Autowired(required = false) List<TextChunker> availableChunkers,
            @Autowired(required = false) SubprocessConfigService subprocessConfigService) {
        this.embeddingModel = embeddingModel;
        this.subprocessConfigService = subprocessConfigService;
        // Select the first non-NoOp chunker from available chunkers (don't hardcode OpenNLP)
        this.textChunker = availableChunkers != null ? availableChunkers.stream()
                .filter(c -> c != null && !c.getClass().getSimpleName().toLowerCase().contains("noop"))
                .findFirst()
                .orElse(null) : null;

        if (this.textChunker != null) {
            log.info("Warmup will use chunker: {}", this.textChunker.getName());
        }
    }

    /**
     * Triggered when the application is fully started and ready.
     * Initiates warmup of expensive resources.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!warmupEnabled) {
            log.info("Startup warmup is disabled (kompile.warmup.enabled=false)");
            warmupComplete.set(true);
            return;
        }

        log.info("=== Starting Model Warmup ===");
        warmupStartTime = System.currentTimeMillis();

        if (warmupAsync) {
            // Run warmup asynchronously so the server can start accepting requests immediately
            CompletableFuture.runAsync(this::performWarmup);
        } else {
            // Run warmup synchronously (blocks until complete)
            performWarmup();
        }
    }

    /**
     * Performs the actual warmup of all configured resources.
     *
     * NOTE: Embedding and chunker warmup run SEQUENTIALLY, not in parallel.
     * Running them in parallel via CompletableFuture.runAsync() causes severe
     * performance degradation in ND4J/OpenBLAS due to thread pool interference
     * with OpenMP's threading model. The ForkJoinPool threads conflict with
     * OpenBLAS's internal thread management, causing inference to slow down
     * from ~200ms to ~55 seconds per batch.
     */
    private void performWarmup() {
        try {
            log.info("Warming up resources in {} mode...", warmupAsync ? "async" : "sync");

            // Warm up embedding model first (takes longest)
            // IMPORTANT: Must run on the same thread, not via CompletableFuture.runAsync()
            // to avoid interference with ND4J/OpenBLAS threading
            if (warmupEmbedding) {
                warmupEmbeddingModel();
            } else {
                embeddingReady.set(true);
            }

            // Warm up chunker (sentence detection models)
            if (warmupChunker) {
                warmupChunker();
            } else {
                chunkerReady.set(true);
            }

            warmupComplete.set(true);
            long totalTime = System.currentTimeMillis() - warmupStartTime;

            log.info("=== Warmup Complete ===");
            log.info("Total warmup time: {}ms", totalTime);
            log.info("  - Embedding model: {}ms", embeddingWarmupTime);
            log.info("  - Chunker models: {}ms", chunkerWarmupTime);
            log.info("System is now ready for fast document processing!");

        } catch (Exception e) {
            log.error("Error during startup warmup (non-fatal, will load on-demand)", e);
            warmupComplete.set(true); // Mark as complete even on error
        }
    }

    /**
     * Warms up the embedding model by performing a test encoding.
     * This triggers model loading, tokenizer initialization, and JIT compilation.
     *
     * Note: If model files are not cached, this will also trigger model download
     * which can take several minutes depending on network speed.
     *
     * CRITICAL: When subprocess mode is enabled, embedding operations run in an isolated
     * subprocess to protect the parent process from native crashes. In that case, we
     * must NOT warm up embeddings here as it would load ND4J native code in the parent.
     */
    private void warmupEmbeddingModel() {
        // CRITICAL: Skip embedding warmup when subprocess mode is enabled
        // Loading ND4J/SameDiff in the parent defeats the purpose of subprocess isolation
        boolean subprocessEnabled = subprocessConfigService != null && subprocessConfigService.isEnabled();
        if (subprocessEnabled) {
            log.info("Subprocess mode enabled - skipping embedding warmup in parent process");
            log.info("Embedding will be warmed up in subprocess on first document ingest");
            embeddingReady.set(true);
            embeddingWarmupTime = 0;
            return;
        }

        if (embeddingModel == null) {
            log.warn("No embedding model available for warmup");
            embeddingReady.set(true);
            return;
        }

        long startTime = System.currentTimeMillis();
        currentPhase.set("loading_embedding");
        log.info("Warming up embedding model...");

        try {
            // Single test embedding to trigger model load and JIT compilation
            // Keep it minimal to reduce warmup time
            String testText = "Warmup text for embedding model initialization.";

            // Just call dimensions() first - this triggers lazy initialization without full inference
            int dims = embeddingModel.dimensions();
            log.info("Embedding model dimensions: {}", dims);

            currentPhase.set("warming_embedding");

            // Single embedding to warm up the inference path
            INDArray singleEmbed = embeddingModel.embed(testText);
            if (singleEmbed != null && !singleEmbed.isEmpty()) {
                log.debug("Warmup embedding shape: {}", singleEmbed.shape());
            }

            embeddingReady.set(true);
            embeddingWarmupTime = System.currentTimeMillis() - startTime;
            log.info("Embedding model warmup complete in {}ms (dimensions: {})",
                    embeddingWarmupTime, dims);

        } catch (Exception e) {
            embeddingWarmupTime = System.currentTimeMillis() - startTime;
            log.warn("Embedding model warmup failed after {}ms: {}", embeddingWarmupTime, e.getMessage());
            embeddingReady.set(true); // Mark ready anyway, will fail later if actually broken
        }
    }

    /**
     * Warms up the text chunker by performing a test chunking operation.
     * For OpenNLP, this triggers sentence model loading.
     */
    private void warmupChunker() {
        if (textChunker == null) {
            log.warn("No text chunker available for warmup");
            chunkerReady.set(true);
            return;
        }

        long startTime = System.currentTimeMillis();
        log.info("Warming up text chunker ({})...", textChunker.getName());

        try {
            // Create a test document with multiple sentences
            String testText = "This is the first sentence for warmup. " +
                    "Here is another sentence to ensure the model is fully loaded. " +
                    "The third sentence helps with JIT optimization. " +
                    "Finally, this fourth sentence completes the warmup process.";

            ai.kompile.core.retrievers.RetrievedDoc testDoc =
                    new ai.kompile.core.retrievers.RetrievedDoc(
                            "warmup-test",
                            testText,
                            Map.of("source", "warmup"));

            // Perform chunking (triggers OpenNLP model load for default language)
            var chunks = textChunker.chunk(testDoc, Map.of("language", "en"));

            chunkerReady.set(true);
            chunkerWarmupTime = System.currentTimeMillis() - startTime;
            log.info("Chunker warmup complete in {}ms (produced {} chunks)",
                    chunkerWarmupTime, chunks.size());

            // Also preload supported languages info
            var languages = textChunker.getSupportedLanguages();
            log.debug("Chunker supports {} languages: {}", languages.size(), languages);

        } catch (Exception e) {
            chunkerWarmupTime = System.currentTimeMillis() - startTime;
            log.warn("Chunker warmup failed after {}ms: {}", chunkerWarmupTime, e.getMessage());
            chunkerReady.set(true); // Mark ready anyway
        }
    }

    /**
     * Check if warmup is complete.
     * @return true if all warmup tasks have finished (or were disabled)
     */
    public boolean isWarmupComplete() {
        return warmupComplete.get();
    }

    /**
     * Check if the embedding model is ready.
     * @return true if the embedding model has been warmed up
     */
    public boolean isEmbeddingReady() {
        return embeddingReady.get();
    }

    /**
     * Check if the chunker is ready.
     * @return true if the chunker has been warmed up
     */
    public boolean isChunkerReady() {
        return chunkerReady.get();
    }

    /**
     * Get warmup status for monitoring.
     * @return Map with warmup status information
     */
    public Map<String, Object> getWarmupStatus() {
        // 'ready' = warmup complete OR warmup disabled (system is usable either way)
        boolean ready = warmupComplete.get() || !warmupEnabled;

        return Map.ofEntries(
                Map.entry("enabled", warmupEnabled),
                Map.entry("complete", warmupComplete.get()),
                Map.entry("ready", ready),  // Frontend expects this field
                Map.entry("embeddingReady", embeddingReady.get()),
                Map.entry("chunkerReady", chunkerReady.get()),
                Map.entry("embeddingWarmupTimeMs", embeddingWarmupTime),
                Map.entry("chunkerWarmupTimeMs", chunkerWarmupTime),
                Map.entry("totalWarmupTimeMs", embeddingWarmupTime + chunkerWarmupTime),
                Map.entry("chunkerName", textChunker != null ? textChunker.getName() : "none"),
                Map.entry("embeddingModel", embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "none"),
                // New fields for better UI feedback
                Map.entry("currentPhase", currentPhase.get()),
                Map.entry("modelDownloadRequired", modelDownloadRequired.get()),
                Map.entry("elapsedTimeMs", warmupStartTime > 0 ? System.currentTimeMillis() - warmupStartTime : 0)
        );
    }

    /**
     * Manually trigger warmup (useful for testing or after config changes).
     * @return CompletableFuture that completes when warmup is done
     */
    @Async
    public CompletableFuture<Void> triggerWarmup() {
        log.info("Manual warmup triggered");
        warmupComplete.set(false);
        embeddingReady.set(false);
        chunkerReady.set(false);
        warmupStartTime = System.currentTimeMillis();
        performWarmup();
        return CompletableFuture.completedFuture(null);
    }
}
