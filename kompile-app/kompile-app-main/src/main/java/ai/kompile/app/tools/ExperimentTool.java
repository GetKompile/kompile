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

package ai.kompile.app.tools;

import ai.kompile.app.eval.service.ExperimentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class ExperimentTool {

    private static final Logger logger = LoggerFactory.getLogger(ExperimentTool.class);

    private final ExperimentService experimentService;

    @Autowired
    public ExperimentTool(@Autowired(required = false) ExperimentService experimentService) {
        this.experimentService = experimentService;
        logger.info("ExperimentTool initialized");
    }

    public record CreateExperimentInput(String name, String description, String suiteId, String datasetId, List<String> tags) {}
    public record ListExperimentsInput() {}
    public record GetExperimentInput(String id) {}
    public record DeleteExperimentInput(String id) {}
    public record ImportDatasetCsvInput(String name, String description, String csvContent) {}
    public record ImportDatasetJsonlInput(String name, String description, String jsonlContent) {}

    @Tool(name = "create_experiment",
            description = "Creates a new experiment for A/B testing RAG configurations. Provide name, description, optional suiteId, datasetId, and tags.")
    public Map<String, Object> createExperiment(CreateExperimentInput input) {
        try {
            if (experimentService == null) return Map.of("status", "error", "error", "ExperimentService not available");
            if (input.name() == null || input.name().isBlank()) return Map.of("status", "error", "error", "name is required");
            var experiment = experimentService.createExperiment(input.name(), input.description(),
                    input.suiteId(), input.datasetId(), input.tags());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", experiment.getId());
            result.put("name", experiment.getName());
            result.put("message", "Experiment created");
            return result;
        } catch (Exception e) {
            logger.error("Error creating experiment: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_experiments",
            description = "Lists all experiments with their IDs, names, and status.")
    public Map<String, Object> listExperiments(ListExperimentsInput input) {
        try {
            if (experimentService == null) return Map.of("status", "error", "error", "ExperimentService not available");
            var experiments = experimentService.listExperiments();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", experiments.size());
            result.put("experiments", experiments.stream().map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("name", e.getName());
                m.put("description", e.getDescription());
                return m;
            }).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing experiments: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_experiment",
            description = "Gets a specific experiment by its ID with full details.")
    public Map<String, Object> getExperiment(GetExperimentInput input) {
        try {
            if (experimentService == null) return Map.of("status", "error", "error", "ExperimentService not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            var experiment = experimentService.getExperiment(input.id());
            if (experiment.isEmpty()) return Map.of("status", "error", "error", "Experiment not found: " + input.id());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("experiment", experiment.get());
            return result;
        } catch (Exception e) {
            logger.error("Error getting experiment: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_experiment",
            description = "Deletes an experiment by its ID.")
    public Map<String, Object> deleteExperiment(DeleteExperimentInput input) {
        try {
            if (experimentService == null) return Map.of("status", "error", "error", "ExperimentService not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            boolean deleted = experimentService.deleteExperiment(input.id());
            if (!deleted) return Map.of("status", "error", "error", "Experiment not found: " + input.id());
            return Map.of("status", "success", "message", "Experiment deleted", "id", input.id());
        } catch (Exception e) {
            logger.error("Error deleting experiment: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "import_eval_dataset_csv",
            description = "Imports an evaluation dataset from CSV content. Provide name, description, and the CSV content as a string.")
    public Map<String, Object> importDatasetCsv(ImportDatasetCsvInput input) {
        try {
            if (experimentService == null) return Map.of("status", "error", "error", "ExperimentService not available");
            if (input.name() == null || input.csvContent() == null) return Map.of("status", "error", "error", "name and csvContent are required");
            var dataset = experimentService.importDatasetCsv(input.name(), input.description(), input.csvContent());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("dataset", dataset);
            result.put("message", "Dataset imported from CSV");
            return result;
        } catch (Exception e) {
            logger.error("Error importing CSV dataset: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "import_eval_dataset_jsonl",
            description = "Imports an evaluation dataset from JSONL content. Provide name, description, and the JSONL content as a string.")
    public Map<String, Object> importDatasetJsonl(ImportDatasetJsonlInput input) {
        try {
            if (experimentService == null) return Map.of("status", "error", "error", "ExperimentService not available");
            if (input.name() == null || input.jsonlContent() == null) return Map.of("status", "error", "error", "name and jsonlContent are required");
            var dataset = experimentService.importDatasetJsonl(input.name(), input.description(), input.jsonlContent());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("dataset", dataset);
            result.put("message", "Dataset imported from JSONL");
            return result;
        } catch (Exception e) {
            logger.error("Error importing JSONL dataset: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
