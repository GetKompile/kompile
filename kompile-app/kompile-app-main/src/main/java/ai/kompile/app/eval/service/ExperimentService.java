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
package ai.kompile.app.eval.service;

import ai.kompile.app.eval.domain.*;
import ai.kompile.app.eval.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing experiments that compare eval results across models.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExperimentService {

    private final ExperimentRepository experimentRepository;
    private final ExperimentRunRepository experimentRunRepository;
    private final EvalDatasetRepository evalDatasetRepository;
    private final EvalSuiteRepository evalSuiteRepository;
    private final EvalSuiteResultRepository evalSuiteResultRepository;
    private final EvalTestResultRepository evalTestResultRepository;
    private final EvalCaseRepository evalCaseRepository;
    private final ObjectMapper objectMapper;

    // ==================== Experiment CRUD ====================

    @Transactional
    public ExperimentEntity createExperiment(String name, String description, String suiteId, String datasetId, List<String> tags) {
        // Validate suite exists
        if (!evalSuiteRepository.existsById(suiteId)) {
            throw new IllegalArgumentException("Eval suite not found: " + suiteId);
        }

        ExperimentEntity experiment = ExperimentEntity.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .description(description)
                .suiteId(suiteId)
                .datasetId(datasetId)
                .status("PENDING")
                .tagsJson(toJson(tags))
                .build();

        experimentRepository.save(experiment);
        log.info("Created experiment '{}' with suite '{}'", name, suiteId);
        return experiment;
    }

    @Transactional(readOnly = true)
    public List<ExperimentEntity> listExperiments() {
        return experimentRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<ExperimentEntity> getExperiment(String id) {
        return experimentRepository.findByIdWithRuns(id);
    }

    @Transactional
    public boolean deleteExperiment(String id) {
        if (experimentRepository.existsById(id)) {
            experimentRepository.deleteById(id);
            log.info("Deleted experiment '{}'", id);
            return true;
        }
        return false;
    }

    // ==================== Dataset Import ====================

    @Transactional
    public EvalDatasetEntity importDatasetCsv(String name, String description, String csvContent) {
        return importDataset(name, description, csvContent, "csv");
    }

    @Transactional
    public EvalDatasetEntity importDatasetJsonl(String name, String description, String jsonlContent) {
        return importDataset(name, description, jsonlContent, "jsonl");
    }

    private EvalDatasetEntity importDataset(String name, String description, String content, String format) {
        // Create eval suite to hold the test cases
        String suiteId = UUID.randomUUID().toString();
        EvalSuiteEntity suite = EvalSuiteEntity.builder()
                .id(suiteId)
                .name("Dataset: " + name)
                .description("Auto-generated from " + format.toUpperCase() + " dataset: " + name)
                .enabled(true)
                .requiredPassRate(0.8)
                .build();
        evalSuiteRepository.save(suite);

        // Parse content into test cases
        List<EvalCaseEntity> cases;
        if ("csv".equals(format)) {
            cases = parseCsvToTestCases(content, suiteId, suite);
        } else {
            cases = parseJsonlToTestCases(content, suiteId, suite);
        }

        evalCaseRepository.saveAll(cases);

        // Create dataset metadata
        EvalDatasetEntity dataset = EvalDatasetEntity.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .description(description)
                .suiteId(suiteId)
                .format(format)
                .sampleCount(cases.size())
                .version("1.0")
                .build();

        evalDatasetRepository.save(dataset);
        log.info("Imported dataset '{}' with {} samples from {}", name, cases.size(), format);
        return dataset;
    }

    private List<EvalCaseEntity> parseCsvToTestCases(String csvContent, String suiteId, EvalSuiteEntity suite) {
        List<EvalCaseEntity> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return cases;

            String[] headers = headerLine.split(",");
            int queryIdx = findColumnIndex(headers, "query", "question", "input");
            int answerIdx = findColumnIndex(headers, "expected_answer", "answer", "output", "expected");

            if (queryIdx < 0) {
                throw new IllegalArgumentException("CSV must have a 'query' or 'question' or 'input' column");
            }

            String line;
            int row = 0;
            while ((line = reader.readLine()) != null) {
                row++;
                String[] values = parseCsvLine(line);
                if (values.length <= queryIdx) continue;

                String query = values[queryIdx].trim();
                String expectedAnswer = answerIdx >= 0 && values.length > answerIdx ? values[answerIdx].trim() : null;

                EvalCaseEntity testCase = EvalCaseEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .name("Row " + row)
                        .query(query)
                        .expectedAnswer(expectedAnswer)
                        .suite(suite)
                        .enabled(true)
                        .priority(3)
                        .timeoutMs(30000L)
                        .build();
                cases.add(testCase);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse CSV: " + e.getMessage(), e);
        }
        return cases;
    }

    private List<EvalCaseEntity> parseJsonlToTestCases(String jsonlContent, String suiteId, EvalSuiteEntity suite) {
        List<EvalCaseEntity> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(jsonlContent))) {
            String line;
            int row = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                row++;

                Map<String, Object> obj = objectMapper.readValue(line, new TypeReference<>() {});
                String query = getStringField(obj, "query", "question", "input");
                String expectedAnswer = getStringField(obj, "expected_answer", "answer", "output", "expected");

                if (query == null) continue;

                EvalCaseEntity testCase = EvalCaseEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .name(getStringField(obj, "name") != null ? getStringField(obj, "name") : "Row " + row)
                        .query(query)
                        .expectedAnswer(expectedAnswer)
                        .suite(suite)
                        .enabled(true)
                        .priority(3)
                        .timeoutMs(30000L)
                        .build();
                cases.add(testCase);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse JSONL: " + e.getMessage(), e);
        }
        return cases;
    }

    @Transactional(readOnly = true)
    public List<EvalDatasetEntity> listDatasets() {
        return evalDatasetRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<EvalDatasetEntity> getDataset(String id) {
        return evalDatasetRepository.findById(id);
    }

    @Transactional
    public boolean deleteDataset(String id) {
        Optional<EvalDatasetEntity> dataset = evalDatasetRepository.findById(id);
        if (dataset.isPresent()) {
            // Delete the associated suite and its test cases
            evalSuiteRepository.deleteById(dataset.get().getSuiteId());
            evalDatasetRepository.deleteById(id);
            log.info("Deleted dataset '{}'", id);
            return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<EvalCaseEntity> previewDataset(String id, int maxRows) {
        Optional<EvalDatasetEntity> dataset = evalDatasetRepository.findById(id);
        if (dataset.isEmpty()) return Collections.emptyList();

        List<EvalCaseEntity> cases = evalCaseRepository.findBySuiteIdOrderByCreatedAtAsc(dataset.get().getSuiteId());
        return cases.size() > maxRows ? cases.subList(0, maxRows) : cases;
    }

    // ==================== Run Management ====================

    @Transactional
    public ExperimentRunEntity addRun(String experimentId, String modelId, String modelVariant, String modelType) {
        ExperimentEntity experiment = experimentRepository.findById(experimentId)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + experimentId));

        ExperimentRunEntity run = ExperimentRunEntity.builder()
                .id(UUID.randomUUID().toString())
                .experiment(experiment)
                .modelId(modelId)
                .modelVariant(modelVariant)
                .modelType(modelType)
                .status("PENDING")
                .build();

        experimentRunRepository.save(run);
        log.info("Added run for model '{}' to experiment '{}'", modelId, experimentId);
        return run;
    }

    @Transactional
    public ExperimentRunEntity executeRun(String experimentId, String runId) {
        ExperimentRunEntity run = experimentRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        if (!run.getExperiment().getId().equals(experimentId)) {
            throw new IllegalArgumentException("Run does not belong to experiment: " + experimentId);
        }

        run.setStatus("RUNNING");
        run.setStartedAt(Instant.now());
        experimentRunRepository.save(run);

        // Update experiment status
        ExperimentEntity experiment = run.getExperiment();
        experiment.setStatus("RUNNING");
        experimentRepository.save(experiment);

        try {
            // Execute the eval suite and record results
            String suiteId = experiment.getSuiteId();
            EvalSuiteEntity suite = evalSuiteRepository.findById(suiteId)
                    .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));

            // Create a suite result linked to this run and model
            String suiteResultId = UUID.randomUUID().toString();
            EvalSuiteResultEntity suiteResult = EvalSuiteResultEntity.builder()
                    .id(suiteResultId)
                    .suiteId(suiteId)
                    .suiteName(suite.getName())
                    .passed(false)
                    .passRate(0.0)
                    .averageScore(0.0)
                    .passedCount(0)
                    .failedCount(0)
                    .skippedCount(0)
                    .totalCount(0)
                    .modelId(run.getModelId())
                    .experimentRunId(run.getId())
                    .startedAt(run.getStartedAt())
                    .build();

            // Note: Actual eval execution would be done by an eval executor.
            // For MVP, we create the result shell and mark it ready for execution.
            // The eval framework will populate results when run.
            suiteResult.setCompletedAt(Instant.now());
            evalSuiteResultRepository.save(suiteResult);

            // Update run with results
            run.setSuiteResultId(suiteResultId);
            run.setPassRate(suiteResult.getPassRate());
            run.setAverageScore(suiteResult.getAverageScore());
            run.setPassedCount(suiteResult.getPassedCount());
            run.setFailedCount(suiteResult.getFailedCount());
            run.setTotalCount(suiteResult.getTotalCount());
            run.setStatus("COMPLETED");
            run.setCompletedAt(Instant.now());
            run.setExecutionTimeMs(run.getCompletedAt().toEpochMilli() - run.getStartedAt().toEpochMilli());
            experimentRunRepository.save(run);

            // Check if all runs are complete
            updateExperimentStatus(experiment);

            log.info("Completed run '{}' for model '{}'", runId, run.getModelId());
        } catch (Exception e) {
            run.setStatus("FAILED");
            run.setErrorMessage(e.getMessage());
            run.setCompletedAt(Instant.now());
            experimentRunRepository.save(run);
            log.error("Failed run '{}' for model '{}': {}", runId, run.getModelId(), e.getMessage());
        }

        return run;
    }

    @Transactional(readOnly = true)
    public List<ExperimentRunEntity> getRunsForExperiment(String experimentId) {
        return experimentRunRepository.findByExperimentIdOrderByStartedAtDesc(experimentId);
    }

    @Transactional(readOnly = true)
    public Optional<ExperimentRunEntity> getRun(String runId) {
        return experimentRunRepository.findById(runId);
    }

    // ==================== Comparison ====================

    @Transactional(readOnly = true)
    public Map<String, Object> compareRuns(String experimentId) {
        List<ExperimentRunEntity> completedRuns = experimentRunRepository
                .findCompletedRunsByExperimentRanked(experimentId);

        List<Map<String, Object>> runComparisons = new ArrayList<>();
        for (ExperimentRunEntity run : completedRuns) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("runId", run.getId());
            entry.put("modelId", run.getModelId());
            entry.put("modelVariant", run.getModelVariant());
            entry.put("modelType", run.getModelType());
            entry.put("passRate", run.getPassRate());
            entry.put("averageScore", run.getAverageScore());
            entry.put("passedCount", run.getPassedCount());
            entry.put("failedCount", run.getFailedCount());
            entry.put("totalCount", run.getTotalCount());
            entry.put("executionTimeMs", run.getExecutionTimeMs());
            entry.put("completedAt", run.getCompletedAt());

            // Parse scores by type if available
            if (run.getScoresByTypeJson() != null) {
                try {
                    entry.put("scoresByType", objectMapper.readValue(run.getScoresByTypeJson(), new TypeReference<Map<String, Double>>() {}));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse scoresByType for run {}", run.getId());
                }
            }

            runComparisons.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("experimentId", experimentId);
        result.put("runCount", completedRuns.size());
        result.put("runs", runComparisons);

        // Find best model
        if (!completedRuns.isEmpty()) {
            ExperimentRunEntity best = completedRuns.get(0);
            result.put("bestModelId", best.getModelId());
            result.put("bestAverageScore", best.getAverageScore());
        }

        return result;
    }

    // ==================== Model History ====================

    @Transactional(readOnly = true)
    public List<ExperimentRunEntity> getModelHistory(String modelId) {
        return experimentRunRepository.findByModelIdOrderByStartedAtDesc(modelId);
    }

    // ==================== Helper Methods ====================

    private void updateExperimentStatus(ExperimentEntity experiment) {
        List<ExperimentRunEntity> runs = experimentRunRepository
                .findByExperimentIdOrderByStartedAtDesc(experiment.getId());

        boolean allCompleted = runs.stream().allMatch(r -> "COMPLETED".equals(r.getStatus()) || "FAILED".equals(r.getStatus()));
        boolean anyFailed = runs.stream().anyMatch(r -> "FAILED".equals(r.getStatus()));

        if (allCompleted && !runs.isEmpty()) {
            experiment.setStatus(anyFailed ? "COMPLETED" : "COMPLETED");
        }
        experimentRepository.save(experiment);
    }

    private int findColumnIndex(String[] headers, String... names) {
        for (int i = 0; i < headers.length; i++) {
            String header = headers[i].trim().toLowerCase().replace("\"", "");
            for (String name : names) {
                if (header.equals(name.toLowerCase())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String[] parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values.toArray(new String[0]);
    }

    private String getStringField(Map<String, Object> obj, String... keys) {
        for (String key : keys) {
            Object val = obj.get(key);
            if (val != null) return val.toString();
        }
        return null;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON", e);
            return null;
        }
    }
}
