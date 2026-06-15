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

package ai.kompile.embedding.anserini.config;

import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.AnseriniEncoderFactory;
import ai.kompile.embedding.anserini.AnseriniImageEmbeddingModel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Startup initializer that eagerly initializes the Anserini embedding model
 * when the application starts.
 *
 * <p>The embedding model runs in an isolated subprocess to keep SameDiff/ND4J
 * out of the main JVM. This provides:
 * <ul>
 *   <li>Isolated ND4J native library loading (doesn't affect main JVM)</li>
 *   <li>OOM during model loading doesn't crash the main application</li>
 *   <li>Real-time progress reporting via JSON protocol</li>
 *   <li>Automatic recovery on subprocess crash</li>
 * </ul>
 *
 * <p>Model configuration is done via the UI, not Spring properties.
 */
@Component
@ConditionalOnClass(name = "ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl")
@ConditionalOnProperty(name = "kompile.embedding.anserini.enabled", havingValue = "true", matchIfMissing = true)
public class AnseriniEmbeddingStartupInitializer {

    private static final Logger log = LoggerFactory.getLogger(AnseriniEmbeddingStartupInitializer.class);

    // Default polling interval when staging service is configured but unavailable
    private static final int DEFAULT_STAGING_POLL_INTERVAL_SECONDS = 30;

    private final ObjectProvider<AnseriniEmbeddingModelImpl> embeddingModelProvider;
    private final ObjectProvider<AnseriniImageEmbeddingModel> imageEmbeddingModelProvider;
    private final AtomicBoolean initializationStarted = new AtomicBoolean(false);
    private final AtomicBoolean pollingActive = new AtomicBoolean(false);

    // Current subprocess initialization status
    private volatile SubprocessInitStatus subprocessStatus = SubprocessInitStatus.IDLE;
    private volatile String subprocessStatusMessage = null;
    private volatile int subprocessProgressPercent = 0;

    // Scheduler for background polling when staging service is configured
    private volatile ScheduledExecutorService pollingScheduler;
    private volatile ScheduledFuture<?> pollingTask;

    public AnseriniEmbeddingStartupInitializer(
            ObjectProvider<AnseriniEmbeddingModelImpl> embeddingModelProvider,
            ObjectProvider<AnseriniImageEmbeddingModel> imageEmbeddingModelProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.imageEmbeddingModelProvider = imageEmbeddingModelProvider;
    }

    /**
     * Status of subprocess-based model initialization.
     */
    public enum SubprocessInitStatus {
        IDLE,
        STARTING,
        INITIALIZING_ND4J,
        CONFIGURING_SOURCE,
        LOOKING_UP_REGISTRY,
        LOADING_MODEL,
        CREATING_ENCODER,
        VALIDATING,
        COMPLETED,
        FAILED
    }

    /**
     * Get the current subprocess initialization status.
     */
    public SubprocessInitStatus getSubprocessStatus() {
        return subprocessStatus;
    }

    /**
     * Get the current subprocess status message.
     */
    public String getSubprocessStatusMessage() {
        return subprocessStatusMessage;
    }

    /**
     * Get the current subprocess progress percentage (0-100).
     */
    public int getSubprocessProgressPercent() {
        return subprocessProgressPercent;
    }

    /**
     * Subprocess initialization is always enabled now.
     */
    public boolean isSubprocessInitEnabled() {
        return true;
    }

    /**
     * Gets the configured polling interval from the staging service configuration.
     * Falls back to default if not configured.
     */
    private int getStagingPollIntervalSeconds() {
        try {
            int interval = AnseriniEncoderFactory.getStagingRetryPollIntervalSeconds();
            return interval > 0 ? interval : DEFAULT_STAGING_POLL_INTERVAL_SECONDS;
        } catch (Exception e) {
            log.debug("Could not get staging poll interval from factory, using default: {}", e.getMessage());
            return DEFAULT_STAGING_POLL_INTERVAL_SECONDS;
        }
    }

    /**
     * Triggers embedding model initialization when the application is ready.
     *
     * <p>Runs asynchronously so application startup completes without delay.
     * The model is initialized in a subprocess for isolation.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(200)
    public void onApplicationReady() {
        // Prevent duplicate initialization
        if (!initializationStarted.compareAndSet(false, true)) {
            log.debug("Embedding model initialization already started, skipping");
            return;
        }

        log.info("");
        log.info("=======================================================================");
        log.info("  EMBEDDING MODEL STARTUP INITIALIZATION");
        log.info("=======================================================================");
        log.info("Mode: SUBPROCESS (model runs in isolated JVM)");
        log.info("  - SameDiff/ND4J loads in subprocess, not main JVM");
        log.info("  - Progress shown via [subprocess] prefixed log messages");
        log.info("  - Crash recovery: subprocess auto-restarts on failure");
        log.info("");
        log.info("Starting embedding model initialization...");
        log.info("=======================================================================");

        // Run initialization asynchronously so startup completes
        CompletableFuture.runAsync(this::initializeEmbeddingModel)
                .exceptionally(ex -> {
                    log.error("Embedding model initialization failed unexpectedly", ex);
                    subprocessStatus = SubprocessInitStatus.FAILED;
                    subprocessStatusMessage = ex.getMessage();
                    return null;
                });
    }

    /**
     * Performs the actual embedding model initialization via subprocess.
     *
     * <p>This triggers the {@link AnseriniEmbeddingModelImpl} which starts
     * a subprocess and loads the model there.
     */
    private void initializeEmbeddingModel() {
        long startTime = System.currentTimeMillis();
        subprocessStatus = SubprocessInitStatus.STARTING;
        subprocessStatusMessage = "Starting embedding subprocess...";
        subprocessProgressPercent = 0;

        try {
            // Get the embedding model bean
            AnseriniEmbeddingModelImpl embeddingModel = embeddingModelProvider.getIfAvailable();

            if (embeddingModel == null) {
                log.warn("Anserini embedding model bean not available - skipping startup initialization");
                subprocessStatus = SubprocessInitStatus.FAILED;
                subprocessStatusMessage = "Embedding model bean not available";
                return;
            }

            String modelId = embeddingModel.getModelIdentifier();
            log.info("Initializing embedding model: {}", modelId);
            subprocessStatusMessage = "Initializing model: " + modelId;
            subprocessProgressPercent = 10;

            // Trigger initialization by calling embedBatch with a test text
            // This will start the subprocess and load the model
            subprocessStatus = SubprocessInitStatus.LOADING_MODEL;
            subprocessStatusMessage = "Loading model in subprocess...";
            subprocessProgressPercent = 30;

            // This triggers ensureInitialized() in AnseriniEmbeddingModelImpl
            // which starts the subprocess and loads the model
            var testResult = embeddingModel.embedBatch(java.util.List.of("initialization test"));

            long elapsed = System.currentTimeMillis() - startTime;

            if (embeddingModel.isInitialized()) {
                subprocessStatus = SubprocessInitStatus.COMPLETED;
                subprocessProgressPercent = 100;
                subprocessStatusMessage = "Model ready";

                log.info("");
                log.info("=======================================================================");
                log.info("  EMBEDDING MODEL READY");
                log.info("=======================================================================");
                log.info("Model: {}", embeddingModel.getModelIdentifier());
                log.info("Source: {}", embeddingModel.getModelSource());
                log.info("Type: {}", embeddingModel.getEncoderType());
                log.info("Dimensions: {}", embeddingModel.dimensions());
                log.info("Initialization time: {}ms", elapsed);
                log.info("Mode: SUBPROCESS (SameDiff running in isolated JVM)");
                log.info("=======================================================================");
                // Model loaded successfully - no need for polling
                stopPolling();
            }

            // Also probe for VLM image embedding (independent of text embedding success)
            probeVlmImageEmbedding();

            if (!embeddingModel.isInitialized()) {
                handleInitializationFailure(embeddingModel, elapsed);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Embedding model startup initialization failed after {}ms", elapsed, e);

            subprocessStatus = SubprocessInitStatus.FAILED;
            subprocessStatusMessage = e.getMessage();

            // Check if we should start polling
            handleExceptionDuringInit(e);
        }
    }

    /**
     * Handle initialization failure - check if polling should start.
     */
    private void handleInitializationFailure(AnseriniEmbeddingModelImpl embeddingModel, long elapsed) {
        String stagingUrl = AnseriniEncoderFactory.getStagingUrl();
        boolean stagingConfigured = stagingUrl != null && !stagingUrl.isBlank();

        subprocessStatus = SubprocessInitStatus.FAILED;
        subprocessStatusMessage = embeddingModel.getInitializationError();

        log.warn("");
        log.warn("=======================================================================");
        log.warn("  EMBEDDING MODEL NOT AVAILABLE");
        log.warn("=======================================================================");
        log.warn("Model: {}", embeddingModel.getModelIdentifier());
        log.warn("Status: {}", embeddingModel.getModelSource());
        log.warn("Time: {}ms", elapsed);
        String error = embeddingModel.getInitializationError();
        if (error != null) {
            log.warn("Error: {}", error);
        }

        if (stagingConfigured) {
            // Only start polling if the error is retriable (transient)
            if (embeddingModel.isInitializationErrorRetriable()) {
                log.warn("");
                log.warn("Remote staging service is configured: {}", stagingUrl);
                log.warn("The error appears to be TRANSIENT - will poll every {} seconds until model becomes available.",
                        getStagingPollIntervalSeconds());
                log.warn("=======================================================================");
                startPolling(embeddingModel);
            } else {
                log.warn("");
                log.warn("Remote staging service is configured but the error is PERMANENT.");
                log.warn("Background polling will NOT be started because retrying won't help.");
                log.warn("Please check the model configuration, files, or import a valid model archive.");
                log.warn("=======================================================================");
            }
        } else {
            log.warn("The application will continue. Configure a model via the UI.");
            log.warn("=======================================================================");
        }
    }

    /**
     * Handle exception during initialization.
     */
    private void handleExceptionDuringInit(Exception e) {
        String stagingUrl = AnseriniEncoderFactory.getStagingUrl();
        if (stagingUrl != null && !stagingUrl.isBlank()) {
            AnseriniEmbeddingModelImpl embeddingModel = embeddingModelProvider.getIfAvailable();
            if (embeddingModel != null && !embeddingModel.isInitialized()) {
                // Only start polling if the error is retriable
                if (embeddingModel.shouldContinuePolling()) {
                    log.info("Starting background polling for staging service: {} (error is retriable)", stagingUrl);
                    startPolling(embeddingModel);
                } else {
                    log.warn("NOT starting background polling - error is permanent and retrying won't help");
                }
            }
        }
    }

    /**
     * Starts background polling for the staging service.
     * Polls at the configured interval until the model becomes available.
     */
    private void startPolling(AnseriniEmbeddingModelImpl embeddingModel) {
        if (!pollingActive.compareAndSet(false, true)) {
            log.debug("Polling already active, skipping start");
            return;
        }

        pollingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "staging-model-poller");
            t.setDaemon(true);
            return t;
        });

        int pollIntervalSeconds = getStagingPollIntervalSeconds();
        log.info("Starting background polling for embedding model (interval: {}s)", pollIntervalSeconds);

        pollingTask = pollingScheduler.scheduleWithFixedDelay(
                () -> pollForModel(embeddingModel),
                pollIntervalSeconds,  // Initial delay
                pollIntervalSeconds,  // Subsequent delay
                TimeUnit.SECONDS
        );
    }

    /**
     * Polls the staging service and attempts to reload the model.
     */
    private void pollForModel(AnseriniEmbeddingModelImpl embeddingModel) {
        try {
            String stagingUrl = AnseriniEncoderFactory.getStagingUrl();
            if (stagingUrl == null || stagingUrl.isBlank()) {
                log.info("Staging service no longer configured, stopping polling");
                stopPolling();
                return;
            }

            log.debug("Polling staging service for model: {} at {}", embeddingModel.getModelIdentifier(), stagingUrl);

            // Refresh the registry to pick up any changes
            AnseriniEncoderFactory.refreshRegistry();

            // Attempt to reload the model
            boolean success = embeddingModel.reloadModel();

            if (success && embeddingModel.isInitialized()) {
                subprocessStatus = SubprocessInitStatus.COMPLETED;
                subprocessProgressPercent = 100;
                subprocessStatusMessage = "Model ready (from polling)";

                log.info("");
                log.info("=======================================================================");
                log.info("  EMBEDDING MODEL NOW AVAILABLE (from polling)");
                log.info("=======================================================================");
                log.info("Model: {}", embeddingModel.getModelIdentifier());
                log.info("Source: {}", embeddingModel.getModelSource());
                log.info("Type: {}", embeddingModel.getEncoderType());
                log.info("Dimensions: {}", embeddingModel.dimensions());
                log.info("=======================================================================");
                stopPolling();
            } else {
                // Check if we should continue polling
                if (!embeddingModel.shouldContinuePolling()) {
                    log.warn("");
                    log.warn("=======================================================================");
                    log.warn("  STOPPING BACKGROUND POLLING - PERMANENT ERROR DETECTED");
                    log.warn("=======================================================================");
                    log.warn("Model: {}", embeddingModel.getModelIdentifier());
                    log.warn("Error: {}", embeddingModel.getInitializationError());
                    log.warn("The error is PERMANENT - retrying won't help.");
                    log.warn("Please check the model configuration, files, or import a valid model archive.");
                    log.warn("=======================================================================");
                    stopPolling();
                } else {
                    log.debug("Model still not available (retriable error), will retry in {}s", getStagingPollIntervalSeconds());
                }
            }
            // Also probe for VLM image embedding during polling
            probeVlmImageEmbedding();

        } catch (Exception e) {
            log.debug("Polling attempt failed: {}", e.getMessage());
        }
    }

    /**
     * Stops the background polling.
     */
    private void stopPolling() {
        pollingActive.set(false);

        if (pollingTask != null) {
            pollingTask.cancel(false);
            pollingTask = null;
        }

        if (pollingScheduler != null) {
            pollingScheduler.shutdown();
            try {
                if (!pollingScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    pollingScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                pollingScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            pollingScheduler = null;
        }

        if (log.isDebugEnabled()) {
            log.debug("Stopped staging service polling");
        }
    }

    /**
     * Probes the staging service for VLM image embedding availability.
     * This is called during initialization and during polling to discover
     * VLM models dynamically.
     */
    private void probeVlmImageEmbedding() {
        try {
            AnseriniImageEmbeddingModel imageModel = imageEmbeddingModelProvider.getIfAvailable();
            if (imageModel == null) {
                return;
            }

            if (imageModel.isInitialized()) {
                log.debug("VLM image embedding already initialized: {}", imageModel.getModelIdentifier());
                return;
            }

            boolean discovered = imageModel.discoverFromStaging();
            if (discovered) {
                log.info("");
                log.info("=======================================================================");
                log.info("  VLM IMAGE EMBEDDING AVAILABLE");
                log.info("=======================================================================");
                log.info("Model: {}", imageModel.getModelIdentifier());
                log.info("Dimensions: {}", imageModel.dimensions());
                log.info("Source: staging service");
                log.info("=======================================================================");
            }
        } catch (Exception e) {
            log.debug("VLM image embedding probe failed: {}", e.getMessage());
        }
    }

    /**
     * Cleanup on application shutdown.
     */
    @PreDestroy
    public void cleanup() {
        log.debug("Shutting down embedding startup initializer");
        stopPolling();
    }

    /**
     * Check if background polling is currently active.
     */
    public boolean isPollingActive() {
        return pollingActive.get();
    }
}
