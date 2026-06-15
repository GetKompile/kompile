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

import ai.kompile.app.config.ModelAutoInitializationService;
import ai.kompile.app.staging.domain.StagingServiceConfig;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.NoOpEmbeddingModelImpl;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service that computes the overall setup/initialization status of the application.
 * Aggregates state from model staging, embedding model, index services, and fact sheets
 * into a single unified status suitable for a setup wizard UI.
 */
@Service
public class SetupStatusService {

    private static final Logger log = LoggerFactory.getLogger(SetupStatusService.class);

    private final StagingServerLifecycleService stagingServerLifecycleService;
    private final StagingServiceConfigService stagingConfigService;
    private final ModelAutoInitializationService autoInitService;
    private final IndexStatusService indexStatusService;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    // Track whether the user has dismissed the wizard this session
    private volatile boolean wizardDismissed = false;

    @Autowired
    public SetupStatusService(
            @Autowired(required = false) StagingServerLifecycleService stagingServerLifecycleService,
            @Autowired(required = false) StagingServiceConfigService stagingConfigService,
            @Autowired(required = false) ModelAutoInitializationService autoInitService,
            @Autowired(required = false) IndexStatusService indexStatusService,
            @Autowired(required = false) List<EmbeddingModel> embeddingModels,
            @Autowired(required = false) List<VectorStore> vectorStores) {
        this.stagingServerLifecycleService = stagingServerLifecycleService;
        this.stagingConfigService = stagingConfigService;
        this.autoInitService = autoInitService;
        this.indexStatusService = indexStatusService;
        this.embeddingModel = selectNonNoOp(embeddingModels, NoOpEmbeddingModelImpl.class);
        this.vectorStore = selectNonNoOp(vectorStores, NoOpVectorStoreImpl.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T selectNonNoOp(List<T> items, Class<?> noOpClass) {
        if (items == null || items.isEmpty()) return null;
        for (T item : items) {
            if (!noOpClass.isInstance(item)) return item;
        }
        return items.get(0);
    }

    /**
     * Compute the full setup status.
     */
    public SetupStatus getStatus() {
        SetupStatus.SetupStatusBuilder builder = SetupStatus.builder();

        // Step 1: Staging server running
        StepStatus stagingServer = computeStagingServerStep();
        builder.stagingServer(stagingServer);

        // Step 2: Model source configuration
        StepStatus modelSource = computeModelSourceStep();
        builder.modelSource(modelSource);

        // Step 3: Embedding model initialization
        StepStatus embeddingStatus = computeEmbeddingModelStep();
        builder.embeddingModel(embeddingStatus);

        // Step 4: Document indexing
        StepStatus indexing = computeIndexingStep();
        builder.indexing(indexing);

        // Step 5: Search readiness (all pieces working together)
        StepStatus searchReady = computeSearchReadinessStep(modelSource, embeddingStatus, indexing);
        builder.searchReady(searchReady);

        // Overall
        boolean allComplete = stagingServer.isComplete() && modelSource.isComplete()
                && embeddingStatus.isComplete() && indexing.isComplete() && searchReady.isComplete();
        builder.setupComplete(allComplete);
        builder.wizardDismissed(wizardDismissed);

        // Determine which step needs attention
        int currentStep = 1;
        if (stagingServer.isComplete()) currentStep = 2;
        if (stagingServer.isComplete() && modelSource.isComplete()) currentStep = 3;
        if (stagingServer.isComplete() && modelSource.isComplete() && embeddingStatus.isComplete()) currentStep = 4;
        if (stagingServer.isComplete() && modelSource.isComplete() && embeddingStatus.isComplete() && indexing.isComplete()) currentStep = 5;
        builder.currentStep(currentStep);
        builder.totalSteps(5);

        return builder.build();
    }

    private StepStatus computeStagingServerStep() {
        StepStatus.StepStatusBuilder step = StepStatus.builder()
                .stepNumber(1)
                .name("Staging Server")
                .description("Start the model staging service to provide embedding models");

        if (stagingServerLifecycleService == null) {
            return step.status(StepState.WARNING)
                    .complete(true) // Don't block if the service isn't available
                    .message("Staging server lifecycle management not available")
                    .action("Configure a model source manually in Developer > Model Staging.")
                    .build();
        }

        StagingServerLifecycleService.StagingServerStatus serverStatus = stagingServerLifecycleService.getStatus();

        switch (serverStatus.getStatus()) {
            case "running":
                return step.status(StepState.COMPLETE)
                        .complete(true)
                        .message("Staging server running")
                        .detail("Port " + serverStatus.getPort() + " — " + serverStatus.getUrl())
                        .build();
            case "starting":
                return step.status(StepState.IN_PROGRESS)
                        .complete(false)
                        .message("Staging server is starting...")
                        .detail("PID " + serverStatus.getPid())
                        .build();
            case "not_installed":
                return step.status(StepState.NOT_STARTED)
                        .complete(false)
                        .message("Staging server not installed")
                        .action("Install with: kompile install kompile-model-staging")
                        .build();
            default: // stopped
                return step.status(StepState.NOT_STARTED)
                        .complete(false)
                        .message("Staging server is not running")
                        .detail(serverStatus.isInstalled() ? "Installed but stopped" : null)
                        .action("Click 'Start Staging Server' or run: kompile setup staging-server start")
                        .build();
        }
    }

    private StepStatus computeModelSourceStep() {
        StepStatus.StepStatusBuilder step = StepStatus.builder()
                .stepNumber(2)
                .name("Model Source")
                .description("Configure where embedding models are loaded from");

        if (stagingConfigService == null) {
            return step.status(StepState.NOT_STARTED)
                    .complete(false)
                    .message("Staging service not available. Models must be present locally.")
                    .action("Place model files in ~/.kompile/models/ or configure a staging service.")
                    .build();
        }

        Optional<StagingServiceConfig> activeConfig = stagingConfigService.getActiveConfig();
        if (activeConfig.isPresent()) {
            StagingServiceConfig config = activeConfig.get();
            if (config.isVerified()) {
                return step.status(StepState.COMPLETE)
                        .complete(true)
                        .message("Connected to staging service: " + config.getName())
                        .detail(config.getEndpointUrl())
                        .build();
            } else {
                return step.status(StepState.WARNING)
                        .complete(true) // Configured but not verified is still "done" for the step
                        .message("Staging service configured but not verified: " + config.getName())
                        .detail(config.getLastError())
                        .action("Verify the staging service connection in Developer > Model Staging.")
                        .build();
            }
        }

        // Check if models are available locally
        if (embeddingModel instanceof AnseriniEmbeddingModelImpl) {
            AnseriniEmbeddingModelImpl anserini = (AnseriniEmbeddingModelImpl) embeddingModel;
            if (anserini.isInitialized()) {
                return step.status(StepState.COMPLETE)
                        .complete(true)
                        .message("Models loaded from local cache")
                        .detail(anserini.getActiveModelId())
                        .build();
            }
        }

        return step.status(StepState.NOT_STARTED)
                .complete(false)
                .message("No model source configured")
                .action("Go to Developer > Model Staging to connect a staging service, or import a .karch archive.")
                .build();
    }

    private StepStatus computeEmbeddingModelStep() {
        StepStatus.StepStatusBuilder step = StepStatus.builder()
                .stepNumber(3)
                .name("Embedding Model")
                .description("Load an embedding model for document vectorization");

        if (embeddingModel == null || embeddingModel instanceof NoOpEmbeddingModelImpl) {
            return step.status(StepState.NOT_STARTED)
                    .complete(false)
                    .message("No embedding model available")
                    .action("Configure a model source first, then the model will auto-load.")
                    .build();
        }

        if (embeddingModel instanceof AnseriniEmbeddingModelImpl) {
            AnseriniEmbeddingModelImpl anserini = (AnseriniEmbeddingModelImpl) embeddingModel;
            if (anserini.isInitialized()) {
                return step.status(StepState.COMPLETE)
                        .complete(true)
                        .message("Model loaded: " + anserini.getActiveModelId())
                        .detail(anserini.dimensions() + " dimensions")
                        .build();
            }

            // Check if auto-init is in progress
            if (autoInitService != null && !autoInitService.isEmbeddingInitialized()) {
                return step.status(StepState.IN_PROGRESS)
                        .complete(false)
                        .message("Embedding model is loading...")
                        .action("The model will load automatically. This may take a moment on first run.")
                        .build();
            }
        }

        return step.status(StepState.NOT_STARTED)
                .complete(false)
                .message("Embedding model not yet loaded")
                .action("Ensure a model source is configured. The model will auto-load within ~60 seconds.")
                .build();
    }

    private StepStatus computeIndexingStep() {
        StepStatus.StepStatusBuilder step = StepStatus.builder()
                .stepNumber(4)
                .name("Document Index")
                .description("Index documents to enable search");

        if (indexStatusService == null) {
            return step.status(StepState.NOT_STARTED)
                    .complete(false)
                    .message("Index service not available")
                    .build();
        }

        IndexStatusService.IndexStatus indexStatus = indexStatusService.getStatus();

        if (indexStatus.isAnyIndexLoaded()) {
            long totalDocs = indexStatus.getVectorDocumentCount() + indexStatus.getKeywordDocumentCount();
            String detail = String.format("Vector: %d docs, Keyword: %d docs",
                    indexStatus.getVectorDocumentCount(), indexStatus.getKeywordDocumentCount());
            return step.status(StepState.COMPLETE)
                    .complete(true)
                    .message(totalDocs + " documents indexed")
                    .detail(detail)
                    .build();
        }

        // Check if there are karch files or existing indices that could be loaded
        boolean hasKarch = !indexStatus.getAvailableKarchFiles().isEmpty();
        boolean hasExistingIndices = !indexStatus.getAvailableVectorIndices().isEmpty();

        String action;
        if (hasKarch) {
            action = "Import an available .karch archive from Fact Sheets, or upload new documents.";
        } else if (hasExistingIndices) {
            action = "Existing indices found. Switch to one via the index configuration, or upload new documents.";
        } else {
            action = "Upload documents in the Fact Sheets tab to build a search index.";
        }

        return step.status(StepState.NOT_STARTED)
                .complete(false)
                .message("No documents indexed yet")
                .detail(hasKarch ? indexStatus.getAvailableKarchFiles().size() + " .karch archive(s) available" : null)
                .action(action)
                .build();
    }

    private StepStatus computeSearchReadinessStep(StepStatus modelSource, StepStatus embedding, StepStatus indexing) {
        StepStatus.StepStatusBuilder step = StepStatus.builder()
                .stepNumber(5)
                .name("Search Ready")
                .description("All components working together for RAG queries");

        if (modelSource.isComplete() && embedding.isComplete() && indexing.isComplete()) {
            return step.status(StepState.COMPLETE)
                    .complete(true)
                    .message("System is ready for RAG queries")
                    .action("Go to the Chat tab to start querying your documents.")
                    .build();
        }

        if (embedding.getStatus() == StepState.IN_PROGRESS) {
            return step.status(StepState.IN_PROGRESS)
                    .complete(false)
                    .message("Waiting for embedding model to finish loading...")
                    .build();
        }

        // Find the first incomplete step
        String blocker;
        if (!modelSource.isComplete()) {
            blocker = "Configure a model source (Step 1)";
        } else if (!embedding.isComplete()) {
            blocker = "Wait for embedding model to load (Step 2)";
        } else {
            blocker = "Index some documents (Step 3)";
        }

        return step.status(StepState.NOT_STARTED)
                .complete(false)
                .message("Not ready yet")
                .action("Complete the previous steps: " + blocker)
                .build();
    }

    /**
     * Mark the wizard as dismissed for this application session.
     */
    public void dismissWizard() {
        this.wizardDismissed = true;
    }

    /**
     * Reset the wizard dismissed state (e.g., if user wants to see it again).
     */
    public void resetWizardDismissed() {
        this.wizardDismissed = false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    public enum StepState {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETE,
        WARNING
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SetupStatus {
        private StepStatus stagingServer;
        private StepStatus modelSource;
        private StepStatus embeddingModel;
        private StepStatus indexing;
        private StepStatus searchReady;
        private boolean setupComplete;
        private boolean wizardDismissed;
        private int currentStep;
        private int totalSteps;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StepStatus {
        private int stepNumber;
        private String name;
        private String description;
        private StepState status;
        private boolean complete;
        private String message;
        private String detail;
        private String action;
    }
}
