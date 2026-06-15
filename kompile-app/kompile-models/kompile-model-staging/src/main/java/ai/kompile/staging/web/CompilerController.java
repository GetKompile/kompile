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

import ai.kompile.staging.compiler.CompilerService;
import ai.kompile.staging.web.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for the DSP Compiler.
 * Provides endpoints for SameDiff graph optimization, graph inspection,
 * model comparison, Triton GPU compilation, cache management,
 * and async compilation jobs with SSE log streaming.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/compiler")
@CrossOrigin(origins = "*")
public class CompilerController {

    private static final Logger log = LoggerFactory.getLogger(CompilerController.class);

    private final CompilerService compilerService;

    public CompilerController(CompilerService compilerService) {
        this.compilerService = compilerService;
    }

    // ==================== Optimization Passes ====================

    /**
     * List all available optimization passes.
     */
    @GetMapping("/passes")
    public ResponseEntity<List<OptimizationPassInfo>> getAvailablePasses() {
        try {
            return ResponseEntity.ok(compilerService.getAvailablePasses());
        } catch (Exception e) {
            log.error("Failed to get available passes", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Optimization Profiles ====================

    /**
     * List all optimization profiles (predefined sets of passes).
     */
    @GetMapping("/profiles")
    public ResponseEntity<List<OptimizationProfileInfo>> getProfiles() {
        try {
            return ResponseEntity.ok(compilerService.getProfiles());
        } catch (Exception e) {
            log.error("Failed to get profiles", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Compiled Graph Management ====================

    /**
     * Save a compiled/optimized graph as a separate model.
     */
    @PostMapping("/save")
    public ResponseEntity<SaveCompiledGraphResponse> saveCompiledGraph(@RequestBody SaveCompiledGraphRequest request) {
        try {
            log.info("Save compiled graph requested: source={}, output={}", request.getSourceModelId(), request.getOutputModelId());
            SaveCompiledGraphResponse response = compilerService.saveCompiledGraph(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to save compiled graph", e);
            return ResponseEntity.internalServerError().body(
                    SaveCompiledGraphResponse.builder()
                            .success(false)
                            .error("Failed to save compiled graph: " + e.getMessage())
                            .build()
            );
        }
    }

    /**
     * List all compiled models in the models directory.
     */
    @GetMapping("/models")
    public ResponseEntity<java.util.List<CompiledModelInfo>> listCompiledModels() {
        try {
            return ResponseEntity.ok(compilerService.listCompiledModels());
        } catch (Exception e) {
            log.error("Failed to list compiled models", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Optimization ====================

    /**
     * Run optimization on a model with selected passes and settings.
     */
    @PostMapping("/optimize")
    public ResponseEntity<CompilerOptimizeResponse> optimize(@RequestBody CompilerOptimizeRequest request) {
        try {
            log.info("Optimization requested for model: {} with profile: {} and {} selected passes",
                    request.getModelId(),
                    request.getProfile(),
                    request.getSelectedPasses() != null ? request.getSelectedPasses().size() : 0);
            CompilerOptimizeResponse response = compilerService.optimizeGraph(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Optimization failed for model: {}", request.getModelId(), e);
            return ResponseEntity.internalServerError().body(
                    CompilerOptimizeResponse.builder()
                            .success(false)
                            .modelId(request.getModelId())
                            .error("Optimization failed: " + e.getMessage())
                            .build()
            );
        }
    }

    // ==================== Graph Inspection ====================

    /**
     * Get graph information for a model.
     */
    @GetMapping("/graph/{modelId}")
    public ResponseEntity<GraphInfoResponse> getGraphInfo(@PathVariable String modelId) {
        try {
            GraphInfoResponse response = compilerService.getGraphInfo(modelId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to get graph info for model: {}", modelId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Model Comparison ====================

    /**
     * Compare two model graphs.
     */
    @PostMapping("/compare")
    public ResponseEntity<CompilerCompareResponse> compare(@RequestBody Map<String, String> request) {
        try {
            String model1Id = request.get("model1Id");
            String model2Id = request.get("model2Id");

            if (model1Id == null || model2Id == null) {
                return ResponseEntity.badRequest().body(
                        CompilerCompareResponse.builder()
                                .success(false)
                                .error("Both model1Id and model2Id are required")
                                .build()
                );
            }

            log.info("Comparing models: {} and {}", model1Id, model2Id);
            CompilerCompareResponse response = compilerService.compareGraphs(model1Id, model2Id);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Model comparison failed", e);
            return ResponseEntity.internalServerError().body(
                    CompilerCompareResponse.builder()
                            .success(false)
                            .error("Comparison failed: " + e.getMessage())
                            .build()
            );
        }
    }

    // ==================== Triton Compiler ====================

    /**
     * Get current Triton compiler configuration.
     */
    @GetMapping("/triton/config")
    public ResponseEntity<TritonConfigResponse> getTritonConfig() {
        try {
            return ResponseEntity.ok(compilerService.getTritonConfig());
        } catch (Exception e) {
            log.error("Failed to get Triton config", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Compile a model with Triton GPU settings.
     */
    @PostMapping("/triton/compile")
    public ResponseEntity<CompilerOptimizeResponse> compileWithTriton(@RequestBody TritonCompileRequest request) {
        try {
            log.info("Triton compilation requested for model: {}", request.getModelId());
            CompilerOptimizeResponse response = compilerService.compileWithTriton(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Triton compilation failed for model: {}", request.getModelId(), e);
            return ResponseEntity.internalServerError().body(
                    CompilerOptimizeResponse.builder()
                            .success(false)
                            .modelId(request.getModelId())
                            .error("Triton compilation failed: " + e.getMessage())
                            .build()
            );
        }
    }

    // ==================== Per-Device & Native Cache ====================

    /**
     * Get per-device information and native TAD/Shape cache statistics.
     */
    @GetMapping("/devices/cache")
    public ResponseEntity<DeviceCacheStatusResponse> getDeviceCacheStatus() {
        try {
            return ResponseEntity.ok(compilerService.getDeviceCacheStatus());
        } catch (Exception e) {
            log.error("Failed to get device cache status", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Clear native caches. Query param 'type' can be: tad, shape, cleanup, or all.
     */
    @PostMapping("/devices/cache/clear")
    public ResponseEntity<Map<String, Object>> clearNativeCache(
            @RequestParam(defaultValue = "all") String type) {
        try {
            log.info("Clearing native cache: {}", type);
            return ResponseEntity.ok(compilerService.clearNativeCache(type));
        } catch (Exception e) {
            log.error("Failed to clear native cache", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Cache Management ====================

    /**
     * Get current cache status for ExecutionPlanCache and DAGCache.
     */
    @GetMapping("/cache/status")
    public ResponseEntity<CacheStatusResponse> getCacheStatus() {
        try {
            return ResponseEntity.ok(compilerService.getCacheStatus());
        } catch (Exception e) {
            log.error("Failed to get cache status", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Clear cache. Query param 'type' can be: executionPlan, dag, or all.
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String, Object>> clearCache(
            @RequestParam(defaultValue = "all") String type) {
        try {
            log.info("Clearing cache: {}", type);
            return ResponseEntity.ok(compilerService.clearCache(type));
        } catch (Exception e) {
            log.error("Failed to clear cache", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Enable or disable cache. Query params: type (executionPlan, dag, all), enabled (true/false).
     */
    @PutMapping("/cache/enabled")
    public ResponseEntity<Map<String, Object>> setCacheEnabled(
            @RequestParam(defaultValue = "all") String type,
            @RequestParam boolean enabled) {
        try {
            log.info("Setting cache enabled: type={}, enabled={}", type, enabled);
            return ResponseEntity.ok(compilerService.setCacheEnabled(type, enabled));
        } catch (Exception e) {
            log.error("Failed to set cache enabled", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Compilation Jobs ====================

    /**
     * Start an async compilation job.
     */
    @PostMapping("/jobs/start")
    public ResponseEntity<CompilationJobStatus> startCompilationJob(
            @RequestBody CompilationRequest request) {
        try {
            log.info("Starting compilation job for model: {}", request.getModelId());
            CompilationJobStatus status = compilerService.startCompilationJob(request);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to start compilation job", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * List all compilation jobs.
     */
    @GetMapping("/jobs")
    public ResponseEntity<List<CompilationJobStatus>> getJobs() {
        return ResponseEntity.ok(compilerService.getActiveJobs());
    }

    /**
     * Get status of a specific compilation job.
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<CompilationJobStatus> getJobStatus(@PathVariable String jobId) {
        CompilationJobStatus status = compilerService.getJobStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * Get logs for a specific compilation job.
     */
    @GetMapping("/jobs/{jobId}/logs")
    public ResponseEntity<List<CompilationLogEntry>> getJobLogs(@PathVariable String jobId) {
        return ResponseEntity.ok(compilerService.getJobLogs(jobId));
    }

    /**
     * Subscribe to real-time log stream for a compilation job (SSE).
     */
    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJobLogs(@PathVariable String jobId) {
        log.info("SSE subscription for job logs: {}", jobId);
        return compilerService.subscribeToJobLogs(jobId);
    }

    /**
     * Cancel a compilation job.
     */
    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable String jobId) {
        try {
            log.info("Cancelling job: {}", jobId);
            return ResponseEntity.ok(compilerService.cancelJob(jobId));
        } catch (Exception e) {
            log.error("Failed to cancel job: {}", jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
