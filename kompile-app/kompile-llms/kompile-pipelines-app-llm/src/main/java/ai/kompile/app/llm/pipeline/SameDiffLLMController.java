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

package ai.kompile.app.llm.pipeline;

import ai.kompile.modelmanager.llm.LlmGenerationConfig;
import ai.kompile.modelmanager.llm.dynamic.LlmPipelineDefinition;
import ai.kompile.pipelines.framework.runtime.pipeline.graph.GraphPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API controller for SameDiff LLM pipeline management.
 *
 * <p>Provides endpoints for model set management, pipeline creation/execution,
 * and registry operations with full parity to the VLM controller.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/samediff-llm")
@CrossOrigin(origins = "*")
public class SameDiffLLMController {

    @Autowired
    private SameDiffLLMService samediffLlmService;

    // ==================== Model Set Operations ====================

    @GetMapping("/model-sets")
    public ResponseEntity<List<Map<String, Object>>> getModelSets() {
        return ResponseEntity.ok(samediffLlmService.getModelSets());
    }

    @GetMapping("/model-sets/{setId}")
    public ResponseEntity<Map<String, Object>> getModelSet(@PathVariable String setId) {
        return samediffLlmService.getModelSet(setId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/model-sets/status")
    public ResponseEntity<Map<String, Object>> getModelSetsStatus() {
        return ResponseEntity.ok(samediffLlmService.getModelSetsStatus());
    }

    @PostMapping("/model-sets/{setId}/download")
    public ResponseEntity<Map<String, String>> downloadModelSet(@PathVariable String setId) {
        try {
            samediffLlmService.downloadModelSet(setId);
            return ResponseEntity.accepted().body(Map.of(
                    "status", "started",
                    "message", "Download started for model set: " + setId
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/model-sets/{setId}/download/status")
    public ResponseEntity<Object> getDownloadStatus(@PathVariable String setId) {
        SameDiffLLMService.DownloadProgress progress = samediffLlmService.getDownloadProgress(setId);
        if (progress == null) {
            if (samediffLlmService.isModelSetCached(setId)) {
                return ResponseEntity.ok(Map.of(
                        "setId", setId, "downloading", false,
                        "complete", true, "success", true));
            }
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(progress);
    }

    @DeleteMapping("/model-sets/{setId}")
    public ResponseEntity<Map<String, Object>> deleteModelSet(@PathVariable String setId) {
        boolean deleted = samediffLlmService.deleteModelSet(setId);
        if (deleted) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Model set deleted"));
        }
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Model set not found or not cached"));
    }

    // ==================== Pipeline Stages ====================

    @GetMapping("/pipeline-stages")
    public ResponseEntity<List<Map<String, Object>>> getPipelineStages() {
        return ResponseEntity.ok(samediffLlmService.getPipelineStages());
    }

    // ==================== Pipeline Definitions (Registry) ====================

    @GetMapping("/pipeline-definitions")
    public ResponseEntity<List<LlmPipelineDefinition>> getPipelineDefinitions() {
        return ResponseEntity.ok(samediffLlmService.getPipelineDefinitions());
    }

    // ==================== Generation Presets ====================

    @GetMapping("/presets")
    public ResponseEntity<List<Map<String, Object>>> getPresets() {
        List<Map<String, Object>> presets = new ArrayList<>();

        presets.add(presetToMap("greedy", "Greedy Decoding",
                "Deterministic output, best for factual tasks", LlmGenerationConfig.forGreedy()));
        presets.add(presetToMap("chat", "Chat",
                "Balanced creativity and coherence for conversation", LlmGenerationConfig.forChat()));
        presets.add(presetToMap("creative", "Creative Writing",
                "High temperature for diverse, creative outputs", LlmGenerationConfig.forCreativeWriting()));
        presets.add(presetToMap("code", "Code Generation",
                "Low temperature for precise, structured code output", LlmGenerationConfig.forCodeGeneration()));
        presets.add(presetToMap("tool-calling", "Tool Calling",
                "Structured output with tool call support for agents", LlmGenerationConfig.forToolCalling()));
        presets.add(presetToMap("comprehensive", "Comprehensive",
                "All features enabled with large context", LlmGenerationConfig.comprehensive()));

        return ResponseEntity.ok(presets);
    }

    // ==================== Pipeline Management ====================

    @PostMapping("/pipelines")
    public ResponseEntity<Map<String, Object>> createPipeline(@RequestBody Map<String, Object> config) {
        try {
            String pipelineId = (String) config.get("pipelineId");
            String modelSetId = (String) config.get("modelSetId");

            if (pipelineId == null || pipelineId.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "pipelineId is required"));
            }
            if (modelSetId == null || modelSetId.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "modelSetId is required"));
            }

            samediffLlmService.createPipeline(pipelineId, modelSetId, config);
            return ResponseEntity.ok(Map.of(
                    "success", true, "pipelineId", pipelineId,
                    "message", "Pipeline created successfully"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating pipeline", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to create pipeline"));
        }
    }

    @GetMapping("/pipelines")
    public ResponseEntity<List<String>> listPipelines() {
        return ResponseEntity.ok(samediffLlmService.listPipelines());
    }

    @GetMapping("/pipelines/{pipelineId}")
    public ResponseEntity<Map<String, Object>> getPipeline(@PathVariable String pipelineId) {
        Optional<GraphPipeline> pipeline = samediffLlmService.getPipeline(pipelineId);
        if (pipeline.isPresent()) {
            return ResponseEntity.ok(Map.of("pipelineId", pipelineId, "exists", true));
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/pipelines/{pipelineId}")
    public ResponseEntity<Map<String, Boolean>> deletePipeline(@PathVariable String pipelineId) {
        return ResponseEntity.ok(Map.of("deleted", samediffLlmService.deletePipeline(pipelineId)));
    }

    // ==================== Service Status ====================

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getServiceStatus() {
        return ResponseEntity.ok(samediffLlmService.getServiceStatus());
    }

    @GetMapping("/registry/stats")
    public ResponseEntity<Map<String, Object>> getRegistryStats() {
        return ResponseEntity.ok(samediffLlmService.getRegistryStats());
    }

    // ==================== Helpers ====================

    private Map<String, Object> presetToMap(String id, String name, String description, LlmGenerationConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("description", description);
        map.put("maxNewTokens", config.getMaxNewTokens());
        map.put("temperature", config.getTemperature());
        map.put("topK", config.getTopK());
        map.put("topP", config.getTopP());
        map.put("repetitionPenalty", config.getRepetitionPenalty());
        map.put("samplingStrategy", config.getSamplingStrategy());
        map.put("enableToolCalling", config.isEnableToolCalling());
        return map;
    }
}
