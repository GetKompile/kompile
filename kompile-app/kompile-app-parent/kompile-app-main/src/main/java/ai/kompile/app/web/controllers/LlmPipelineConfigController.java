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

package ai.kompile.app.web.controllers;

import ai.kompile.modelmanager.llm.LlmModelSet;
import ai.kompile.modelmanager.llm.dynamic.*;
import ai.kompile.modelmanager.llm.registry.LlmPipelineRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API controller for LLM pipeline configuration management.
 *
 * <p>Provides CRUD operations for dynamic LLM pipelines, stages, and model sets.
 * Works with {@link LlmPipelineRegistry} for persistence and validation.</p>
 *
 * <p>Analogous to {@link VlmPipelineConfigController}.</p>
 */
@RestController
@RequestMapping("/api/llm/config")
@CrossOrigin
public class LlmPipelineConfigController {

    private static final Logger log = LoggerFactory.getLogger(LlmPipelineConfigController.class);
    private final LlmPipelineRegistry registry = LlmPipelineRegistry.getInstance();

    // ==================== Pipeline CRUD ====================

    @GetMapping("/pipelines")
    public ResponseEntity<Map<String, Object>> listPipelines(
            @RequestParam(required = false) String type) {
        try {
            List<LlmPipelineDefinition> pipelines;
            if ("builtin".equals(type)) {
                pipelines = registry.getBuiltinPipelines();
            } else if ("custom".equals(type)) {
                pipelines = registry.getCustomPipelines();
            } else {
                pipelines = registry.getAllPipelines();
            }

            List<Map<String, Object>> items = pipelines.stream()
                    .map(this::pipelineToSummary)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "pipelines", items,
                    "total", items.size()
            ));
        } catch (Exception e) {
            log.error("Error listing pipelines", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/pipelines/{pipelineId}")
    public ResponseEntity<Object> getPipeline(@PathVariable String pipelineId) {
        LlmPipelineDefinition pipeline = registry.getPipeline(pipelineId);
        if (pipeline == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(pipeline);
    }

    @PostMapping("/pipelines")
    public ResponseEntity<Map<String, Object>> createPipeline(@RequestBody LlmPipelineDefinition pipeline) {
        try {
            if (registry.getPipeline(pipeline.getPipelineId()) != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Pipeline already exists: " + pipeline.getPipelineId()));
            }

            List<String> errors = registry.validatePipeline(pipeline);
            if (!errors.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Validation failed", "details", errors));
            }

            pipeline.setCreatedAt(Instant.now().toString());
            pipeline.setUpdatedAt(pipeline.getCreatedAt());
            registry.registerPipeline(pipeline);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message", "Pipeline created", "pipelineId", pipeline.getPipelineId()));
        } catch (Exception e) {
            log.error("Error creating pipeline", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/pipelines/{pipelineId}")
    public ResponseEntity<Map<String, Object>> updatePipeline(
            @PathVariable String pipelineId, @RequestBody LlmPipelineDefinition pipeline) {
        try {
            LlmPipelineDefinition existing = registry.getPipeline(pipelineId);
            if (existing == null) {
                return ResponseEntity.notFound().build();
            }
            if (existing.isBuiltin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Cannot modify builtin pipeline"));
            }

            pipeline.setPipelineId(pipelineId);
            List<String> errors = registry.validatePipeline(pipeline);
            if (!errors.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Validation failed", "details", errors));
            }

            pipeline.setCreatedAt(existing.getCreatedAt());
            pipeline.setUpdatedAt(Instant.now().toString());
            registry.registerPipeline(pipeline);

            return ResponseEntity.ok(Map.of("message", "Pipeline updated", "pipelineId", pipelineId));
        } catch (Exception e) {
            log.error("Error updating pipeline", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/pipelines/{pipelineId}")
    public ResponseEntity<Map<String, Object>> deletePipeline(@PathVariable String pipelineId) {
        try {
            boolean removed = registry.removePipeline(pipelineId);
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("message", "Pipeline deleted", "pipelineId", pipelineId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/pipelines/validate")
    public ResponseEntity<Map<String, Object>> validatePipeline(@RequestBody LlmPipelineDefinition pipeline) {
        List<String> errors = registry.validatePipeline(pipeline);
        return ResponseEntity.ok(Map.of(
                "valid", errors.isEmpty(),
                "errors", errors
        ));
    }

    // ==================== Stage Management ====================

    @GetMapping("/stages")
    public ResponseEntity<Map<String, Object>> listStages() {
        List<LlmStageDefinition> stages = registry.getAllStages();
        return ResponseEntity.ok(Map.of(
                "stages", stages,
                "total", stages.size()
        ));
    }

    @GetMapping("/stages/{stageId}")
    public ResponseEntity<Object> getStage(@PathVariable String stageId) {
        LlmStageDefinition stage = registry.getStage(stageId);
        if (stage == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(stage);
    }

    @PostMapping("/stages")
    public ResponseEntity<Map<String, Object>> createStage(@RequestBody LlmStageDefinition stage) {
        try {
            if (registry.getStage(stage.getStageId()) != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Stage already exists: " + stage.getStageId()));
            }
            stage.setBuiltin(false);
            registry.registerStage(stage);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message", "Stage created", "stageId", stage.getStageId()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/stages/{stageId}")
    public ResponseEntity<Map<String, Object>> deleteStage(@PathVariable String stageId) {
        try {
            boolean removed = registry.removeStage(stageId);
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("message", "Stage deleted", "stageId", stageId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== Model Set Management ====================

    @GetMapping("/model-sets")
    public ResponseEntity<Map<String, Object>> listModelSets() {
        Map<String, LlmModelSet> builtinSets = LlmModelSet.getAllModelSets();
        List<LlmCustomModelSet> customSets = registry.getAllCustomModelSets();

        List<Map<String, Object>> items = new ArrayList<>();

        for (LlmModelSet modelSet : builtinSets.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("setId", modelSet.getSetId());
            item.put("displayName", modelSet.getDisplayName());
            item.put("description", modelSet.getDescription());
            item.put("architecture", modelSet.getArchitecture());
            item.put("components", modelSet.getComponents().size());
            item.put("isBuiltin", true);
            items.add(item);
        }

        for (LlmCustomModelSet customSet : customSets) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("setId", customSet.getSetId());
            item.put("displayName", customSet.getDisplayName());
            item.put("description", customSet.getDescription());
            item.put("source", customSet.getSource());
            item.put("components", customSet.getComponents().size());
            item.put("isBuiltin", false);
            items.add(item);
        }

        return ResponseEntity.ok(Map.of(
                "modelSets", items,
                "total", items.size()
        ));
    }

    @PostMapping("/model-sets")
    public ResponseEntity<Map<String, Object>> createCustomModelSet(@RequestBody LlmCustomModelSet modelSet) {
        try {
            if (LlmModelSet.isModelSetSupported(modelSet.getSetId()) ||
                    registry.getCustomModelSet(modelSet.getSetId()) != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Model set already exists: " + modelSet.getSetId()));
            }
            registry.registerCustomModelSet(modelSet);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message", "Custom model set created", "setId", modelSet.getSetId()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/model-sets/{setId}")
    public ResponseEntity<Map<String, Object>> deleteCustomModelSet(@PathVariable String setId) {
        boolean removed = registry.removeCustomModelSet(setId);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "Custom model set deleted", "setId", setId));
    }

    // ==================== Registry Stats ====================

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(registry.getStats());
    }

    // ==================== Helpers ====================

    private Map<String, Object> pipelineToSummary(LlmPipelineDefinition pipeline) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("pipelineId", pipeline.getPipelineId());
        summary.put("displayName", pipeline.getDisplayName());
        summary.put("description", pipeline.getDescription());
        summary.put("pipelineType", pipeline.getPipelineType());
        summary.put("modelSetId", pipeline.getModelSetId());
        summary.put("stageCount", pipeline.getStageCount());
        summary.put("isBuiltin", pipeline.isBuiltin());
        summary.put("enabled", pipeline.isEnabled());
        summary.put("createdAt", pipeline.getCreatedAt());
        summary.put("updatedAt", pipeline.getUpdatedAt());
        return summary;
    }
}
