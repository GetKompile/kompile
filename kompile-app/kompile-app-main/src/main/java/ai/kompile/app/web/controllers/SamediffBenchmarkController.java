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

import ai.kompile.app.config.SamediffBenchmarkConfig;
import ai.kompile.app.services.SamediffBenchmarkService;
import ai.kompile.app.web.dto.SamediffBenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for SameDiff benchmark configuration and execution.
 */
@RestController
@RequestMapping("/api/benchmark")
public class SamediffBenchmarkController {

    private static final Logger log = LoggerFactory.getLogger(SamediffBenchmarkController.class);

    private final SamediffBenchmarkService benchmarkService;

    public SamediffBenchmarkController(SamediffBenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    /**
     * List all benchmark configurations.
     */
    @GetMapping("/configs")
    public ResponseEntity<List<SamediffBenchmarkConfig>> listConfigs() {
        return ResponseEntity.ok(benchmarkService.listConfigs());
    }

    /**
     * Get a specific benchmark configuration by name.
     */
    @GetMapping("/configs/{name}")
    public ResponseEntity<SamediffBenchmarkConfig> getConfig(@PathVariable String name) {
        SamediffBenchmarkConfig config = benchmarkService.getConfig(name);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    /**
     * Save a new or updated benchmark configuration.
     */
    @PostMapping("/configs")
    public ResponseEntity<SamediffBenchmarkConfig> saveConfig(@RequestBody SamediffBenchmarkConfig config) {
        try {
            SamediffBenchmarkConfig saved = benchmarkService.saveConfig(config);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Update an existing benchmark configuration.
     */
    @PutMapping("/configs/{name}")
    public ResponseEntity<SamediffBenchmarkConfig> updateConfig(
            @PathVariable String name,
            @RequestBody SamediffBenchmarkConfig config) {
        if (benchmarkService.getConfig(name) == null) {
            return ResponseEntity.notFound().build();
        }

        // Ensure the name matches the path
        SamediffBenchmarkConfig toSave = new SamediffBenchmarkConfig(
                name, config.isActive(), config.createdAt(), config.lastUsedAt(),
                config.tritonBuildThreads(), config.tritonCacheEnabled(), config.tritonVerbose(),
                config.tritonAlwaysCompile(), config.tritonNumWarps(), config.tritonNumStages(),
                config.tritonNumCTAs(), config.tritonEnableFpFusion(), config.tritonCacheDir(),
                config.tritonDumpDir(), config.tritonOverrideArch(),
                config.cudaTensorCoreEnabled(), config.cudaGraphOptimization(),
                config.maxTokens(), config.captureMinExec(),
                config.minDiversityPct(), config.expectedSubstrings(), config.expectStructuralTags()
        );

        return ResponseEntity.ok(benchmarkService.saveConfig(toSave));
    }

    /**
     * Delete a benchmark configuration.
     */
    @DeleteMapping("/configs/{name}")
    public ResponseEntity<Map<String, Object>> deleteConfig(@PathVariable String name) {
        boolean deleted = benchmarkService.deleteConfig(name);
        Map<String, Object> response = new HashMap<>();
        if (deleted) {
            response.put("status", "success");
            response.put("message", "Config '" + name + "' deleted");
            return ResponseEntity.ok(response);
        } else {
            response.put("status", "error");
            response.put("message", "Config not found: " + name);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Activate a benchmark configuration — applies it to the ND4J environment.
     */
    @PostMapping("/configs/{name}/activate")
    public ResponseEntity<Map<String, Object>> activateConfig(@PathVariable String name) {
        try {
            SamediffBenchmarkConfig activated = benchmarkService.activateConfig(name);
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Config '" + name + "' activated and applied to ND4J environment");
            response.put("config", activated);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Get the currently active benchmark configuration.
     */
    @GetMapping("/active")
    public ResponseEntity<SamediffBenchmarkConfig> getActiveConfig() {
        SamediffBenchmarkConfig active = benchmarkService.getActiveConfig();
        if (active == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(active);
    }

    /**
     * Run a benchmark with the specified configuration.
     */
    @PostMapping("/run")
    public ResponseEntity<SamediffBenchmarkResult> runBenchmark(
            @RequestParam String configName) {
        log.info("Running benchmark with config: {}", configName);
        SamediffBenchmarkResult result = benchmarkService.runBenchmark(configName);
        return ResponseEntity.ok(result);
    }

    /**
     * Run a matrix of benchmarks varying Triton parameters.
     */
    @PostMapping("/run-matrix")
    public ResponseEntity<List<SamediffBenchmarkResult>> runMatrix(
            @RequestBody MatrixRequest request) {
        log.info("Running benchmark matrix: warps={}, stages={}, fpFusion={}",
                request.warpsRange, request.stagesRange, request.fpFusionRange);

        List<Integer> warps = request.warpsRange != null ? request.warpsRange : List.of(4, 8);
        List<Integer> stages = request.stagesRange != null ? request.stagesRange : List.of(2, 3);
        List<Boolean> fpFusion = request.fpFusionRange != null ? request.fpFusionRange : List.of(true, false);

        List<SamediffBenchmarkResult> results = benchmarkService.runMatrix(warps, stages, fpFusion);
        return ResponseEntity.ok(results);
    }

    /**
     * Search for the optimal profile by running a grid search.
     */
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> searchOptimalProfile(
            @RequestBody MatrixRequest request) {
        log.info("Searching for optimal profile");

        List<Integer> warps = request.warpsRange != null ? request.warpsRange : List.of(4, 8, 16);
        List<Integer> stages = request.stagesRange != null ? request.stagesRange : List.of(2, 3, 4);
        List<Boolean> fpFusion = request.fpFusionRange != null ? request.fpFusionRange : List.of(true, false);

        SamediffBenchmarkResult best = benchmarkService.searchOptimalProfile(warps, stages, fpFusion);

        Map<String, Object> response = new HashMap<>();
        if (best != null) {
            response.put("status", "success");
            response.put("bestConfig", best.configName());
            response.put("bestResult", best);
            response.put("message", "Optimal profile found and activated: " + best.configName());
        } else {
            response.put("status", "error");
            response.put("message", "No successful benchmark runs found");
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Get benchmark results history.
     */
    @GetMapping("/results")
    public ResponseEntity<List<SamediffBenchmarkResult>> getResults() {
        return ResponseEntity.ok(benchmarkService.getResults());
    }

    /**
     * Clear benchmark results history.
     */
    @DeleteMapping("/results")
    public ResponseEntity<Map<String, Object>> clearResults() {
        benchmarkService.clearResults();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Results history cleared");
        return ResponseEntity.ok(response);
    }

    /**
     * Apply optimal benchmark defaults.
     */
    @PostMapping("/apply-optimal")
    public ResponseEntity<Map<String, Object>> applyOptimalDefaults() {
        SamediffBenchmarkConfig optimal = benchmarkService.applyOptimalDefaults();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Optimal Triton/CUDA defaults applied");
        response.put("config", optimal);
        return ResponseEntity.ok(response);
    }

    /**
     * Request body for matrix and search operations.
     */
    public static class MatrixRequest {
        public List<Integer> warpsRange;
        public List<Integer> stagesRange;
        public List<Boolean> fpFusionRange;
    }
}
