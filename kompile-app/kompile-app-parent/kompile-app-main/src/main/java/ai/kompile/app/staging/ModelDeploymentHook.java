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
package ai.kompile.app.staging;

import ai.kompile.app.project.ProjectBackendService;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.staging.staging.GraphScopedDeployService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bridges the grounding-cascade / KGE training pipeline to the model-staging registry.
 *
 * <p>Listens for {@link ModelTrainedEvent}s published by:
 * <ul>
 *   <li>{@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator}
 *       — STEP 5b (PSL weights) and STEP 9 (MEBN weights)</li>
 *   <li>{@link ai.kompile.knowledgegraph.embedding.service.KGEmbeddingJobService}
 *       — after a successful KGE training run</li>
 * </ul>
 *
 * <p>On receipt, it calls
 * {@link GraphScopedDeployService#deploy(String, String, String, ModelType, Path, ModelMetadata)}
 * to stage and immediately activate the artifact in the model registry, scoped to the
 * current project ({@code projectId}) and the trained fact-sheet ({@code graphId}).</p>
 *
 * <h3>No circular dependency</h3>
 * {@code kompile-knowledge-graph} publishes {@link ModelTrainedEvent} but does NOT depend
 * on {@code kompile-model-staging}. This class lives in {@code kompile-app-main}, which
 * already depends on both modules, so there is no cycle.
 *
 * <h3>Failure isolation</h3>
 * All staging operations are wrapped in a try/catch. A failure to stage does NOT propagate
 * back to the cascade or training job — the trained weights remain on disk even if deployment
 * fails (e.g. because model-staging is not configured).
 */
@Component
public class ModelDeploymentHook {

    private static final Logger log = LoggerFactory.getLogger(ModelDeploymentHook.class);

    private final GraphScopedDeployService deployService;

    /**
     * Optional: project backend service for resolving the current projectId.
     * Null-safe — if unresolvable, {@code "default"} is used as the project ID.
     */
    @Nullable
    private final ProjectBackendService projectBackendService;

    public ModelDeploymentHook(GraphScopedDeployService deployService,
                               @Nullable ProjectBackendService projectBackendService) {
        this.deployService = deployService;
        this.projectBackendService = projectBackendService;
    }

    /**
     * Handle a {@link ModelTrainedEvent} by staging and activating the artifact.
     *
     * <p>Runs {@link Async asynchronously} to avoid blocking the cascade thread while
     * the registry I/O completes. If the artifact file does not exist or deployment fails,
     * the error is logged and swallowed.</p>
     *
     * @param event the training completion event
     */
    @Async
    @EventListener
    public void onModelTrained(ModelTrainedEvent event) {
        String modelType = event.getModelType();
        long   factSheetId = event.getFactSheetId();
        Path   artifactPath = event.getArtifactPath();
        String baseModelId  = event.getBaseModelId();

        // Resolve ModelType enum from the string tag
        ModelType type;
        try {
            type = resolveModelType(modelType);
        } catch (IllegalArgumentException e) {
            log.warn("ModelDeploymentHook: unknown model type '{}' in event for factSheet={} — skipping",
                    modelType, factSheetId);
            return;
        }

        // Guard: artifact must exist before we try to stage it
        if (artifactPath == null || !Files.exists(artifactPath)) {
            log.warn("ModelDeploymentHook: artifact path {} does not exist for {} factSheet={} — skipping",
                    artifactPath, modelType, factSheetId);
            return;
        }

        // Resolve project ID — fall back to "default" if no open project
        String projectId = resolveProjectId();
        String graphId   = String.valueOf(factSheetId);

        // Build minimal metadata
        ModelMetadata metadata = ModelMetadata.builder()
                .sourceOrigin("cascade-auto-deploy")
                .build();

        try {
            deployService.deploy(baseModelId, projectId, graphId, type, artifactPath, metadata);
            log.info("ModelDeploymentHook: staged+activated {} model '{}' for project={} graph={} (artifact={})",
                    modelType, baseModelId, projectId, graphId, artifactPath.getFileName());
        } catch (Exception e) {
            log.warn("ModelDeploymentHook: failed to deploy {} model for factSheet={} — {} (training succeeded; artifact remains on disk at {})",
                    modelType, factSheetId, e.getMessage(), artifactPath);
            // Intentionally swallowed — cascade must not fail due to staging errors
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveProjectId() {
        if (projectBackendService == null) {
            return "default";
        }
        try {
            var response = projectBackendService.current();
            if (response != null && response.manifest() != null) {
                String pid = response.manifest().getProjectId();
                if (pid != null && !pid.isBlank()) {
                    return pid;
                }
            }
        } catch (Exception e) {
            log.debug("ModelDeploymentHook: could not resolve projectId — {}", e.getMessage());
        }
        return "default";
    }

    private static ModelType resolveModelType(String modelType) {
        if (modelType == null) throw new IllegalArgumentException("null modelType");
        return switch (modelType.toLowerCase()) {
            case "psl"  -> ModelType.PSL;
            case "mebn" -> ModelType.MEBN;
            case "kge"  -> ModelType.KGE;
            default -> throw new IllegalArgumentException("Unknown model type: " + modelType);
        };
    }
}
