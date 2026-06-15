/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.eval.domain.EvalCaseEntity;
import ai.kompile.app.eval.domain.EvalDatasetEntity;
import ai.kompile.app.eval.domain.ExperimentEntity;
import ai.kompile.app.eval.domain.ExperimentRunEntity;
import ai.kompile.app.eval.service.ExperimentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for experiment tracking and model evaluation comparison.
 */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private static final Logger logger = LoggerFactory.getLogger(ExperimentController.class);

    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EXPERIMENT CRUD
    // ═══════════════════════════════════════════════════════════════════════════════

    @PostMapping
    public ResponseEntity<ExperimentDto> createExperiment(@RequestBody CreateExperimentRequest request) {
        try {
            ExperimentEntity entity = experimentService.createExperiment(
                    request.name(), request.description(), request.suiteId(),
                    request.datasetId(), request.tags());
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(entity));
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid experiment request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Failed to create experiment", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping
    public ResponseEntity<List<ExperimentDto>> listExperiments() {
        List<ExperimentDto> dtos = experimentService.listExperiments().stream()
                .map(this::toDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExperimentWithRunsDto> getExperiment(@PathVariable("id") String id) {
        return experimentService.getExperiment(id)
                .map(this::toDetailDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExperiment(@PathVariable("id") String id) {
        return experimentService.deleteExperiment(id)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/{id}/compare")
    public ResponseEntity<Map<String, Object>> compareRuns(@PathVariable("id") String id) {
        Map<String, Object> comparison = experimentService.compareRuns(id);
        return ResponseEntity.ok(comparison);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EXPERIMENT RUNS
    // ═══════════════════════════════════════════════════════════════════════════════

    @PostMapping("/{id}/runs")
    public ResponseEntity<ExperimentRunDto> addRun(
            @PathVariable("id") String experimentId,
            @RequestBody AddRunRequest request) {
        try {
            ExperimentRunEntity run = experimentService.addRun(
                    experimentId, request.modelId(), request.modelVariant(), request.modelType());
            return ResponseEntity.status(HttpStatus.CREATED).body(toRunDto(run));
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid run request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Failed to add run", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{id}/runs/{runId}/execute")
    public ResponseEntity<ExperimentRunDto> executeRun(
            @PathVariable("id") String experimentId,
            @PathVariable("runId") String runId) {
        try {
            ExperimentRunEntity run = experimentService.executeRun(experimentId, runId);
            return ResponseEntity.ok(toRunDto(run));
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid execute request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Failed to execute run", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<List<ExperimentRunDto>> listRuns(@PathVariable("id") String experimentId) {
        List<ExperimentRunDto> dtos = experimentService.getRunsForExperiment(experimentId).stream()
                .map(this::toRunDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}/runs/{runId}")
    public ResponseEntity<ExperimentRunDto> getRun(
            @PathVariable("id") String experimentId,
            @PathVariable("runId") String runId) {
        return experimentService.getRun(runId)
                .map(this::toRunDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // MODEL HISTORY
    // ═══════════════════════════════════════════════════════════════════════════════

    @GetMapping("/models/{modelId}/runs")
    public ResponseEntity<List<ExperimentRunDto>> getModelHistory(@PathVariable("modelId") String modelId) {
        List<ExperimentRunDto> dtos = experimentService.getModelHistory(modelId).stream()
                .map(this::toRunDto).toList();
        return ResponseEntity.ok(dtos);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EVAL DATASETS
    // ═══════════════════════════════════════════════════════════════════════════════

    @PostMapping("/eval-datasets")
    public ResponseEntity<EvalDatasetDto> uploadDataset(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description) {
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            String filename = file.getOriginalFilename();
            EvalDatasetEntity dataset;

            if (filename != null && filename.endsWith(".jsonl")) {
                dataset = experimentService.importDatasetJsonl(name, description, content);
            } else {
                dataset = experimentService.importDatasetCsv(name, description, content);
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(toDatasetDto(dataset));
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid dataset: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Failed to upload dataset", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/eval-datasets")
    public ResponseEntity<List<EvalDatasetDto>> listDatasets() {
        List<EvalDatasetDto> dtos = experimentService.listDatasets().stream()
                .map(this::toDatasetDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/eval-datasets/{id}")
    public ResponseEntity<EvalDatasetDto> getDataset(@PathVariable("id") String id) {
        return experimentService.getDataset(id)
                .map(this::toDatasetDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/eval-datasets/{id}")
    public ResponseEntity<Void> deleteDataset(@PathVariable("id") String id) {
        return experimentService.deleteDataset(id)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/eval-datasets/{id}/preview")
    public ResponseEntity<List<DatasetRowDto>> previewDataset(
            @PathVariable("id") String id,
            @RequestParam(defaultValue = "20") int maxRows) {
        List<EvalCaseEntity> cases = experimentService.previewDataset(id, maxRows);
        List<DatasetRowDto> dtos = cases.stream()
                .map(c -> new DatasetRowDto(c.getId(), c.getName(), c.getQuery(), c.getExpectedAnswer()))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DTO CLASSES
    // ═══════════════════════════════════════════════════════════════════════════════

    public record CreateExperimentRequest(String name, String description, String suiteId,
                                          String datasetId, List<String> tags) {}
    public record AddRunRequest(String modelId, String modelVariant, String modelType) {}

    public record ExperimentDto(String id, String name, String description, String suiteId,
                                String datasetId, String status, String createdAt, String updatedAt) {}

    public record ExperimentWithRunsDto(String id, String name, String description, String suiteId,
                                        String datasetId, String status, String createdAt, String updatedAt,
                                        List<ExperimentRunDto> runs) {}

    public record ExperimentRunDto(String id, String experimentId, String modelId, String modelVariant,
                                   String modelType, String suiteResultId, String status,
                                   Double passRate, Double averageScore, Integer passedCount,
                                   Integer failedCount, Integer totalCount, String startedAt,
                                   String completedAt, Long executionTimeMs, String errorMessage) {}

    public record EvalDatasetDto(String id, String name, String description, String suiteId,
                                 String format, Integer sampleCount, String version,
                                 String createdAt, String updatedAt) {}

    public record DatasetRowDto(String id, String name, String query, String expectedAnswer) {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // CONVERSION METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private ExperimentDto toDto(ExperimentEntity e) {
        return new ExperimentDto(
                e.getId(), e.getName(), e.getDescription(), e.getSuiteId(),
                e.getDatasetId(), e.getStatus(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null,
                e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
    }

    private ExperimentWithRunsDto toDetailDto(ExperimentEntity e) {
        List<ExperimentRunDto> runDtos = e.getRuns() != null
                ? e.getRuns().stream().map(this::toRunDto).toList()
                : List.of();
        return new ExperimentWithRunsDto(
                e.getId(), e.getName(), e.getDescription(), e.getSuiteId(),
                e.getDatasetId(), e.getStatus(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null,
                e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null,
                runDtos);
    }

    private ExperimentRunDto toRunDto(ExperimentRunEntity r) {
        return new ExperimentRunDto(
                r.getId(),
                r.getExperiment() != null ? r.getExperiment().getId() : null,
                r.getModelId(), r.getModelVariant(), r.getModelType(),
                r.getSuiteResultId(), r.getStatus(),
                r.getPassRate(), r.getAverageScore(), r.getPassedCount(),
                r.getFailedCount(), r.getTotalCount(),
                r.getStartedAt() != null ? r.getStartedAt().toString() : null,
                r.getCompletedAt() != null ? r.getCompletedAt().toString() : null,
                r.getExecutionTimeMs(), r.getErrorMessage());
    }

    private EvalDatasetDto toDatasetDto(EvalDatasetEntity d) {
        return new EvalDatasetDto(
                d.getId(), d.getName(), d.getDescription(), d.getSuiteId(),
                d.getFormat(), d.getSampleCount(), d.getVersion(),
                d.getCreatedAt() != null ? d.getCreatedAt().toString() : null,
                d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null);
    }
}
