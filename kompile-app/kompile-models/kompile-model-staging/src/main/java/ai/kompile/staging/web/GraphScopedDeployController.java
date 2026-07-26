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

package ai.kompile.staging.web;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.staging.staging.GraphScopedDeployService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for per-graph model lifecycle: stage, activate, rollback.
 * All endpoints operate under {@code /api/staging/graph}.
 *
 * <pre>
 * POST /api/staging/graph/{projectId}/{graphId}/deploy
 *      Body: { "modelId": "rotate-e", "type": "kge", "artifactPath": "/abs/path/model.fb",
 *              "description": "optional", "embeddingDim": 128 }
 *      → stages + activates; returns the ModelEntry
 *
 * POST /api/staging/graph/{projectId}/{graphId}/stage
 *      Same body — stages but does NOT activate
 *
 * POST /api/staging/graph/models/{modelId}/activate
 *      → activates an already-staged graph-scoped model
 *
 * POST /api/staging/graph/models/{modelId}/rollback
 *      → re-activates a prior (STAGED) version
 *
 * GET  /api/staging/graph/{projectId}/{graphId}/active
 *      ?type=kge (optional)
 *      → returns the active ModelEntry for the scope
 *
 * GET  /api/staging/graph/{projectId}/{graphId}/versions
 *      → lists all versions (any status) for the scope
 * </pre>
 */
@RestController
@RequestMapping("/api/staging/graph")
public class GraphScopedDeployController {

    private static final Logger log = LoggerFactory.getLogger(GraphScopedDeployController.class);

    private final GraphScopedDeployService deployService;

    @Autowired
    public GraphScopedDeployController(GraphScopedDeployService deployService) {
        this.deployService = deployService;
    }

    // ==================== Request DTO ====================

    /**
     * Shared request body for deploy and stage operations.
     */
    public static class DeployRequest {
        private String modelId;
        private String type;
        private String artifactPath;
        private String description;
        private Integer embeddingDim;

        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getArtifactPath() { return artifactPath; }
        public void setArtifactPath(String artifactPath) { this.artifactPath = artifactPath; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Integer getEmbeddingDim() { return embeddingDim; }
        public void setEmbeddingDim(Integer embeddingDim) { this.embeddingDim = embeddingDim; }
    }

    // ==================== Endpoints ====================

    /**
     * Stage a model artifact AND immediately activate it for a (project, graph) scope.
     * The prior active model (if any) is demoted to STAGED for rollback.
     */
    @PostMapping("/{projectId}/{graphId}/deploy")
    public ResponseEntity<Map<String, Object>> deploy(
            @PathVariable String projectId,
            @PathVariable String graphId,
            @RequestBody DeployRequest request) {

        if (request.getArtifactPath() == null || request.getArtifactPath().isBlank()) {
            return badRequest("artifactPath is required");
        }
        if (request.getModelId() == null || request.getModelId().isBlank()) {
            return badRequest("modelId is required");
        }

        try {
            ModelType type = parseType(request.getType());
            Path artifactPath = Paths.get(request.getArtifactPath());
            ModelMetadata metadata = buildMetadata(request);

            ModelEntry entry = deployService.deploy(
                    request.getModelId(), projectId, graphId, type, artifactPath, metadata);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Model deployed and activated");
            resp.put("modelId", entry.getModelId());
            resp.put("projectId", projectId);
            resp.put("graphId", graphId);
            resp.put("status", entry.getStatus().getValue());
            resp.put("model", entry);
            return ResponseEntity.ok(resp);

        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IOException e) {
            log.error("Deploy failed for project={} graph={}", projectId, graphId, e);
            return serverError("Deploy failed: " + e.getMessage());
        }
    }

    /**
     * Stage a model artifact for a (project, graph) without activating it.
     * Useful for pre-staging before manual promotion/inspection.
     */
    @PostMapping("/{projectId}/{graphId}/stage")
    public ResponseEntity<Map<String, Object>> stageOnly(
            @PathVariable String projectId,
            @PathVariable String graphId,
            @RequestBody DeployRequest request) {

        if (request.getArtifactPath() == null || request.getArtifactPath().isBlank()) {
            return badRequest("artifactPath is required");
        }
        if (request.getModelId() == null || request.getModelId().isBlank()) {
            return badRequest("modelId is required");
        }

        try {
            ModelType type = parseType(request.getType());
            Path artifactPath = Paths.get(request.getArtifactPath());
            ModelMetadata metadata = buildMetadata(request);

            ModelEntry entry = deployService.stage(
                    request.getModelId(), projectId, graphId, type, artifactPath, metadata);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Model staged (not yet activated)");
            resp.put("modelId", entry.getModelId());
            resp.put("projectId", projectId);
            resp.put("graphId", graphId);
            resp.put("status", entry.getStatus().getValue());
            resp.put("model", entry);
            return ResponseEntity.accepted().body(resp);

        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IOException e) {
            log.error("Stage failed for project={} graph={}", projectId, graphId, e);
            return serverError("Stage failed: " + e.getMessage());
        }
    }

    /**
     * Activate an already-staged graph-scoped model entry.
     * The previously active model for the same (project, graph, type) is demoted.
     */
    @PostMapping("/models/{modelId}/activate")
    public ResponseEntity<Map<String, Object>> activateModel(@PathVariable String modelId) {
        try {
            ModelEntry entry = deployService.activate(modelId);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Model activated for graph scope");
            resp.put("modelId", entry.getModelId());
            resp.put("projectId", entry.getProjectId());
            resp.put("graphId", entry.getGraphId());
            resp.put("status", entry.getStatus().getValue());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return serverError(e.getMessage());
        }
    }

    /**
     * Roll back the active model for a (project, graph, type) to a prior STAGED version.
     * The targetModelId must already exist in the registry with STAGED status.
     */
    @PostMapping("/models/{modelId}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@PathVariable String modelId) {
        try {
            ModelEntry entry = deployService.rollback(modelId);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Rolled back to " + modelId);
            resp.put("modelId", entry.getModelId());
            resp.put("projectId", entry.getProjectId());
            resp.put("graphId", entry.getGraphId());
            resp.put("status", entry.getStatus().getValue());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return serverError(e.getMessage());
        }
    }

    /**
     * Get the currently ACTIVE model for a (project, graph) scope.
     *
     * @param type  optional model type filter (e.g. "kge", "encoder"); omit for any type
     */
    @GetMapping("/{projectId}/{graphId}/active")
    public ResponseEntity<Map<String, Object>> getActive(
            @PathVariable String projectId,
            @PathVariable String graphId,
            @RequestParam(required = false) String type) {

        ModelType modelType = (type != null && !type.isBlank()) ? parseType(type) : null;
        Optional<ModelEntry> active = deployService.findActive(projectId, graphId, modelType);
        if (active.isEmpty()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("found", false);
            resp.put("projectId", projectId);
            resp.put("graphId", graphId);
            resp.put("type", type);
            return ResponseEntity.ok(resp);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("found", true);
        resp.put("model", active.get());
        return ResponseEntity.ok(resp);
    }

    /**
     * List all model versions (any status) scoped to a (project, graph).
     */
    @GetMapping("/{projectId}/{graphId}/versions")
    public ResponseEntity<Map<String, Object>> listVersions(
            @PathVariable String projectId,
            @PathVariable String graphId) {

        List<ModelEntry> versions = deployService.listVersions(projectId, graphId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("projectId", projectId);
        resp.put("graphId", graphId);
        resp.put("count", versions.size());
        resp.put("versions", versions);
        return ResponseEntity.ok(resp);
    }

    // ==================== Helpers ====================

    private ModelType parseType(String type) {
        if (type == null || type.isBlank()) {
            return ModelType.KGE;
        }
        try {
            return ModelType.fromValue(type);
        } catch (Exception e) {
            log.warn("Unknown model type '{}', defaulting to KGE", type);
            return ModelType.KGE;
        }
    }

    private ModelMetadata buildMetadata(DeployRequest request) {
        ModelMetadata.ModelMetadataBuilder b = ModelMetadata.builder()
                .framework("kompile");
        if (request.getDescription() != null) {
            b.description(request.getDescription());
        }
        if (request.getEmbeddingDim() != null) {
            b.embeddingDim(request.getEmbeddingDim());
        }
        return b.build();
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", false);
        resp.put("error", message);
        return ResponseEntity.badRequest().body(resp);
    }

    private ResponseEntity<Map<String, Object>> serverError(String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", false);
        resp.put("error", message);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(resp);
    }
}
