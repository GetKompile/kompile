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

package ai.kompile.app.services.sdx;

import ai.kompile.core.util.FieldNames;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class SdxServingService {

    private static final Logger log = LoggerFactory.getLogger(SdxServingService.class);

    private final ConcurrentHashMap<String, LoadedModel> loadedModels = new ConcurrentHashMap<>();
    private final Path modelsBaseDir;

    private static final String SAMEDIFF_RUNNER = "ai.kompile.pipelines.steps.samediff.SameDiffRunner";
    private static final String DL4J_RUNNER = "ai.kompile.pipelines.steps.deeplearning4j.DL4JRunner";

    public SdxServingService() {
        String dataDir = System.getProperty("kompile.data.dir",
                System.getProperty("user.home") + "/.kompile");
        this.modelsBaseDir = Paths.get(dataDir, "models");
    }

    // ==================== Model Discovery ====================

    public List<Map<String, Object>> listAvailableModels() {
        List<Map<String, Object>> models = new ArrayList<>();

        // Scan known subdirectories for model files
        String[] subdirs = {"sdz-bundles", "encoders", "vlm-models", ""};
        Set<String> seen = new HashSet<>();

        for (String subdir : subdirs) {
            Path dir = subdir.isEmpty() ? modelsBaseDir : modelsBaseDir.resolve(subdir);
            if (!Files.isDirectory(dir)) continue;

            try (Stream<Path> paths = Files.walk(dir, 3)) {
                paths.filter(p -> {
                            String name = p.getFileName().toString();
                            return name.endsWith(".sdz") || name.endsWith(".fb") || name.endsWith(".zip");
                        })
                        .forEach(modelFile -> {
                            String modelId = deriveModelId(modelFile);
                            if (seen.add(modelId)) {
                                Map<String, Object> info = new LinkedHashMap<>();
                                info.put(FieldNames.MODEL_ID, modelId);
                                info.put("path", modelFile.toString());
                                info.put("format", getExtension(modelFile));
                                info.put("loaded", loadedModels.containsKey(modelId));
                                try {
                                    info.put("sizeBytes", Files.size(modelFile));
                                } catch (IOException e) {
                                    log.debug("Could not read size for model file {}: {}", modelFile, e.getMessage());
                                }
                                models.add(info);
                            }
                        });
            } catch (IOException e) {
                log.debug("Error scanning directory: {}", dir, e);
            }
        }

        return models;
    }

    // ==================== Model Loading ====================

    public Map<String, Object> loadModel(String modelId) {
        if (loadedModels.containsKey(modelId)) {
            return Map.of("status", "already_loaded", FieldNames.MODEL_ID, modelId);
        }

        // Find model file
        Path modelFile = findModelFile(modelId);
        if (modelFile == null) {
            throw new IllegalArgumentException("Model not found: " + modelId);
        }

        String ext = getExtension(modelFile);
        String runnerClass = ext.equals("zip") ? DL4J_RUNNER : SAMEDIFF_RUNNER;

        try {
            Data params = Data.empty();
            params.put("modelUri", modelFile.toAbsolutePath().toString());

            GenericStepConfig stepConfig = new GenericStepConfig(runnerClass, params);
            SequencePipeline pipeline = new SequencePipeline(modelId, List.of(stepConfig));
            PipelineExecutor executor = pipeline.createExecutor();

            // Get schema for input/output info
            StepSchema schema = findSchemaForRunner(runnerClass);

            LoadedModel loaded = new LoadedModel();
            loaded.modelId = modelId;
            loaded.modelPath = modelFile.toString();
            loaded.executor = executor;
            loaded.runnerClass = runnerClass;
            loaded.schema = schema;
            loaded.loadedAt = System.currentTimeMillis();

            loadedModels.put(modelId, loaded);
            log.info("Loaded model: {} from {}", modelId, modelFile);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "loaded");
            result.put(FieldNames.MODEL_ID, modelId);
            result.put("path", modelFile.toString());
            result.put("runnerClass", runnerClass);
            return result;
        } catch (Exception e) {
            log.error("Failed to load model: {}", modelId, e);
            throw new RuntimeException("Failed to load model: " + e.getMessage(), e);
        }
    }

    public boolean unloadModel(String modelId) {
        LoadedModel model = loadedModels.remove(modelId);
        if (model != null) {
            try {
                model.executor.close();
            } catch (Exception e) {
                log.warn("Error closing executor for model: {}", modelId, e);
            }
            log.info("Unloaded model: {}", modelId);
            return true;
        }
        return false;
    }

    // ==================== Schema ====================

    public Map<String, Object> getModelSchema(String modelId) {
        LoadedModel model = loadedModels.get(modelId);
        if (model == null) {
            throw new IllegalArgumentException("Model not loaded: " + modelId);
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(FieldNames.MODEL_ID, modelId);
        schema.put("runnerClass", model.runnerClass);
        schema.put("path", model.modelPath);

        if (model.schema != null) {
            schema.put("description", model.schema.getDescription());
            schema.put("parameters", toParamList(model.schema.getParameters()));
            schema.put("inputs", toParamList(model.schema.getInputs()));
            schema.put("outputs", toParamList(model.schema.getOutputs()));
        }

        return schema;
    }

    public Map<String, Object> getInputTemplate(String modelId) {
        LoadedModel model = loadedModels.get(modelId);
        if (model == null) {
            throw new IllegalArgumentException("Model not loaded: " + modelId);
        }

        Map<String, Object> template = new LinkedHashMap<>();
        template.put(FieldNames.MODEL_ID, modelId);

        if (model.schema != null && model.schema.getInputs() != null) {
            Map<String, Object> inputTemplate = new LinkedHashMap<>();
            for (ParameterSchema input : model.schema.getInputs()) {
                inputTemplate.put(input.getName(), getTemplateValue(input));
            }
            template.put("inputs", inputTemplate);
        } else {
            template.put("inputs", Map.of("input", "<provide input data>"));
        }

        return template;
    }

    // ==================== Inference ====================

    public Map<String, Object> infer(String modelId, Map<String, Object> input) {
        LoadedModel model = loadedModels.get(modelId);
        if (model == null) {
            throw new IllegalArgumentException("Model not loaded: " + modelId);
        }

        long start = System.currentTimeMillis();
        try {
            Data inputData = input != null && !input.isEmpty() ? Data.fromMap(input) : Data.empty();
            Data output = model.executor.exec(inputData);
            long duration = System.currentTimeMillis() - start;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put(FieldNames.MODEL_ID, modelId);
            result.put("outputData", output.toMap());
            result.put(FieldNames.DURATION_MS, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Inference failed for model: {}", modelId, e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "error");
            result.put(FieldNames.MODEL_ID, modelId);
            result.put("errorMessage", e.getMessage());
            result.put(FieldNames.DURATION_MS, duration);
            return result;
        }
    }

    // ==================== Status ====================

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("loadedModelCount", loadedModels.size());

        List<Map<String, Object>> models = new ArrayList<>();
        loadedModels.forEach((id, model) -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put(FieldNames.MODEL_ID, id);
            info.put("path", model.modelPath);
            info.put("runnerClass", model.runnerClass);
            info.put("loadedAt", model.loadedAt);
            models.add(info);
        });
        status.put("models", models);

        return status;
    }

    // ==================== Helpers ====================

    private Path findModelFile(String modelId) {
        // Check common locations
        String[] patterns = {
                "sdz-bundles/" + modelId + "/" + modelId + ".sdz",
                "sdz-bundles/" + modelId + ".sdz",
                "encoders/" + modelId + "/model.sdz",
                "vlm-models/" + modelId + "/" + modelId + ".sdz",
                modelId + ".sdz",
                modelId + ".fb",
                modelId + ".zip"
        };

        for (String pattern : patterns) {
            Path candidate = modelsBaseDir.resolve(pattern);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        // Try walking to find by name
        try (Stream<Path> paths = Files.walk(modelsBaseDir, 4)) {
            return paths
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(modelId) &&
                                (name.endsWith(".sdz") || name.endsWith(".fb") || name.endsWith(".zip"));
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.debug("Error searching for model: {}", modelId, e);
            return null;
        }
    }

    private String deriveModelId(Path modelFile) {
        String name = modelFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String getExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    private StepSchema findSchemaForRunner(String runnerClass) {
        try {
            ServiceLoader<PipelineStepRunnerFactory> factories = ServiceLoader.load(PipelineStepRunnerFactory.class);
            for (PipelineStepRunnerFactory factory : factories) {
                if (factory.getRunnerType().equals(runnerClass)) {
                    return factory.getSchema();
                }
            }
        } catch (Exception e) {
            log.debug("Could not find schema for runner: {}", runnerClass, e);
        }
        return null;
    }

    private List<Map<String, Object>> toParamList(List<ParameterSchema> params) {
        if (params == null) return Collections.emptyList();
        return params.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("type", p.getType() != null ? p.getType().name() : "STRING");
            m.put("description", p.getDescription());
            m.put("required", p.isRequired());
            if (p.getDefaultValue() != null) {
                m.put("defaultValue", p.getDefaultValue());
            }
            return m;
        }).collect(Collectors.toList());
    }

    private Object getTemplateValue(ParameterSchema param) {
        if (param.getDefaultValue() != null) return param.getDefaultValue();
        if (param.getType() == null) return "<value>";
        return switch (param.getType()) {
            case STRING -> "<string value>";
            case INT64 -> 0L;
            case DOUBLE -> 0.0;
            case BOOLEAN -> false;
            case NDARRAY -> Map.of("shape", List.of(1, 768), "data", List.of());
            case LIST -> List.of();
            case DATA -> Map.of();
            default -> "<value>";
        };
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up SDX serving service - unloading {} models", loadedModels.size());
        loadedModels.forEach((id, model) -> {
            try {
                model.executor.close();
            } catch (Exception e) {
                log.warn("Error closing executor for model: {}", id, e);
            }
        });
        loadedModels.clear();
    }

    private static class LoadedModel {
        String modelId;
        String modelPath;
        PipelineExecutor executor;
        String runnerClass;
        StepSchema schema;
        long loadedAt;
    }
}
