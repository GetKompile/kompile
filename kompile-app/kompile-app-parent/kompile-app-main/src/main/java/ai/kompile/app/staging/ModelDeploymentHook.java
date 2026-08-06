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
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import ai.kompile.modelmanager.registry.ModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

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
     * <p>On receipt it resolves Model Staging from the managed service endpoint configuration and
     * POSTs {@code /api/staging/graph/{projectId}/{graphId}/deploy} to that service
 * which stages + activates the artifact in the model registry, scoped to the current
 * project and trained fact-sheet.
 *
 * <h3>No model-staging on the classpath</h3>
 * The staging server is a SEPARATE process (default :8090). app-main reaches it over HTTP —
 * exactly like {@link ai.kompile.app.services.agent.KompileLocalModelService} and the staging
 * lifecycle services do — so {@code kompile-model-staging} is NOT (and must never be) a
 * dependency of {@code kompile-app-main}. Bundling it would drag its {@code @SpringBootApplication},
 * controllers, and Angular UI into the app.
 *
 * <h3>Failure isolation</h3>
 * The POST is wrapped in try/catch. If the staging server is down or unreachable, the failure is
 * logged and swallowed — the trained artifact remains on disk and the cascade/training job is
 * unaffected.
 */
@Component
public class ModelDeploymentHook {

    private static final Logger log = LoggerFactory.getLogger(ModelDeploymentHook.class);

    /** Explicit test override; normal operation resolves the managed endpoint per event. */
    private String stagingUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Optional: project backend service for resolving the current projectId.
     * Null-safe — if unresolvable, {@code "default"} is used as the project ID.
     */
    @Nullable
    private final ProjectBackendService projectBackendService;

    public ModelDeploymentHook(@Nullable ProjectBackendService projectBackendService) {
        this.projectBackendService = projectBackendService;
    }

    /**
     * Handle a {@link ModelTrainedEvent} by POSTing a deploy request to the staging server.
     *
     * <p>Runs {@link Async asynchronously} so the cascade thread is never blocked on network I/O.
     * If the model type is unknown, the artifact is missing, or the staging server is unreachable,
     * the error is logged and swallowed.</p>
     *
     * @param event the training completion event
     */
    @Async
    @EventListener
    public void onModelTrained(ModelTrainedEvent event) {
        String modelType   = event.getModelType();
        long   factSheetId = event.getFactSheetId();
        Path   artifactPath = event.getArtifactPath();
        String baseModelId  = event.getBaseModelId();

        // Validate the model-type tag early (PSL / MEBN / KGE) — staging re-validates server-side.
        try {
            resolveModelType(modelType);
        } catch (IllegalArgumentException e) {
            log.warn("ModelDeploymentHook: unknown model type '{}' in event for factSheet={} — skipping",
                    modelType, factSheetId);
            return;
        }

        // Guard: artifact must exist before we ask staging to deploy it
        if (artifactPath == null || !Files.exists(artifactPath)) {
            log.warn("ModelDeploymentHook: artifact path {} does not exist for {} factSheet={} — skipping",
                    artifactPath, modelType, factSheetId);
            return;
        }

        String projectId = resolveProjectId();
        String graphId   = String.valueOf(factSheetId);
        String stagingBase = stagingBase();

        // POST to the staging SERVER over HTTP — app-main never bundles kompile-model-staging.
        String url = stagingBase + "/api/staging/graph/" + projectId + "/" + graphId + "/deploy";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", baseModelId);
        body.put("type", modelType);
        body.put("artifactPath", artifactPath.toAbsolutePath().toString());
        body.put("description", "cascade-auto-deploy");

        try {
            restTemplate.postForObject(url, body, Map.class);
            log.info("ModelDeploymentHook: staged+activated {} model '{}' for project={} graph={} via staging {} (artifact={})",
                    modelType, baseModelId, projectId, graphId, stagingBase, artifactPath.getFileName());
        } catch (Exception e) {
            log.warn("ModelDeploymentHook: deploy POST to staging {} failed for {} factSheet={} — {} (training succeeded; artifact remains on disk at {})",
                    stagingBase, modelType, factSheetId, e.getMessage(), artifactPath);
            // Intentionally swallowed — training/cascade must not fail because staging is down or not running.
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String stagingBase() {
        String configured = stagingUrl;
        if (configured == null || configured.isBlank()) {
            configured = ServiceEndpointsConfigManager.shared().current().effectiveStagingUrl();
        }
        return configured.trim().replaceAll("/+$", "");
    }

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
