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

import ai.kompile.staging.training.EvaluationService;
import ai.kompile.staging.web.dto.BenchmarkInfo;
import ai.kompile.staging.web.dto.EvaluationRequest;
import ai.kompile.staging.web.dto.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for model evaluation.
 * Provides endpoints for running evaluations synchronously or asynchronously,
 * retrieving results, and querying available evaluation metrics.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/evaluation")
@CrossOrigin(origins = "*")
public class EvaluationController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    // ==================== Synchronous Evaluation ====================

    /**
     * Run an evaluation synchronously and return the result.
     */
    @PostMapping("/run")
    public ResponseEntity<EvaluationResult> runEvaluation(@RequestBody EvaluationRequest request) {
        try {
            log.info("Running synchronous evaluation for model: {}", request.getModelId());
            EvaluationResult result = evaluationService.startEvaluation(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to run evaluation", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Asynchronous Evaluation ====================

    /**
     * Start an evaluation asynchronously and return the job status.
     */
    @PostMapping("/start")
    public ResponseEntity<EvaluationResult> startEvaluation(@RequestBody EvaluationRequest request) {
        try {
            log.info("Starting async evaluation for model: {}", request.getModelId());
            EvaluationResult result = evaluationService.startEvaluation(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to start async evaluation", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Results ====================

    /**
     * Get the result of a completed evaluation.
     */
    @GetMapping("/jobs/{evaluationId}")
    public ResponseEntity<EvaluationResult> getResult(@PathVariable String evaluationId) {
        try {
            EvaluationResult result = evaluationService.getResult(evaluationId);
            if (result == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get evaluation result: {}", evaluationId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Available Metrics ====================

    /**
     * Get available evaluation metrics.
     */
    @GetMapping("/metrics")
    public ResponseEntity<List<Map<String, String>>> getAvailableMetrics() {
        try {
            return ResponseEntity.ok(evaluationService.getAvailableMetrics());
        } catch (Exception e) {
            log.error("Failed to get available evaluation metrics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Benchmarks ====================

    /**
     * List available benchmarks from the samediff-llm eval framework.
     */
    @GetMapping("/benchmarks")
    public ResponseEntity<List<BenchmarkInfo>> getAvailableBenchmarks() {
        try {
            return ResponseEntity.ok(evaluationService.getAvailableBenchmarks());
        } catch (Exception e) {
            log.error("Failed to get available benchmarks", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Run a benchmark evaluation against a model.
     */
    @PostMapping("/run-benchmark")
    public ResponseEntity<EvaluationResult> runBenchmark(@RequestBody EvaluationRequest request) {
        try {
            if (request.getBenchmarkName() == null || request.getBenchmarkName().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            log.info("Running benchmark {} for model: {}", request.getBenchmarkName(), request.getModelId());
            EvaluationResult result = evaluationService.startEvaluation(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to run benchmark evaluation", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
