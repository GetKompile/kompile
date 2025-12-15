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

import ai.kompile.core.embeddings.EmbeddingModel;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.VariableType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * MCP Tool for model debugging operations.
 * Exposes functionality to inspect SameDiff models, variables, operations,
 * and ND4J environment configuration.
 */
@Component
public class ModelDebugTool {

    private static final Logger logger = LoggerFactory.getLogger(ModelDebugTool.class);

    private final ApplicationContext applicationContext;
    private final List<EmbeddingModel> embeddingModels;

    @Autowired
    public ModelDebugTool(
            ApplicationContext applicationContext,
            @Autowired(required = false) List<EmbeddingModel> embeddingModels) {
        this.applicationContext = applicationContext;
        this.embeddingModels = embeddingModels != null ? embeddingModels : Collections.emptyList();
        logger.info("ModelDebugTool initialized with {} embedding models", this.embeddingModels.size());
    }

    // Input records for tools
    public record ListSameDiffModelsInput() {}
    public record GetSameDiffSummaryInput(Integer modelIndex, Boolean includeVariables, Boolean includeOperations, Integer maxItems) {}
    public record GetSameDiffVariablesInput(Integer modelIndex, String variableType, Integer limit) {}
    public record GetSameDiffOperationsInput(Integer modelIndex, Integer limit) {}
    public record GetNd4jEnvironmentInput() {}
    public record SetNd4jToggleInput(String toggle, Boolean enabled) {}
    public record SetNd4jConfigInput(String config, Integer value) {}
    public record EnableAllNd4jTogglesInput() {}
    public record DisableAllNd4jTogglesInput() {}

    /**
     * Lists all SameDiff-based embedding models.
     */
    @Tool(name = "list_samediff_models",
            description = "Lists all SameDiff-based embedding models in the application. Returns model names, dimensions, variable counts, and operation counts.")
    public Map<String, Object> listSameDiffModels(ListSameDiffModelsInput input) {
        logger.info("Listing SameDiff models");

        try {
            List<Map<String, Object>> models = new ArrayList<>();

            for (int i = 0; i < embeddingModels.size(); i++) {
                EmbeddingModel model = embeddingModels.get(i);
                List<SameDiff> sameDiffs = extractSameDiffModels(model);

                if (!sameDiffs.isEmpty()) {
                    SameDiff sd = sameDiffs.get(0);

                    Map<String, Object> modelInfo = new LinkedHashMap<>();
                    modelInfo.put("index", i);
                    modelInfo.put("className", model.getClass().getSimpleName());
                    modelInfo.put("dimensions", model.getDimensions());
                    modelInfo.put("numVariables", sd.variableMap().size());
                    modelInfo.put("numOperations", sd.ops().length);
                    modelInfo.put("hasLoss", sd.getLossVariables() != null && !sd.getLossVariables().isEmpty());
                    modelInfo.put("hasTrainingConfig", sd.getTrainingConfig() != null);

                    // Variable type breakdown
                    Map<String, Long> typeBreakdown = new LinkedHashMap<>();
                    for (VariableType type : VariableType.values()) {
                        long count = sd.variableMap().values().stream()
                                .filter(v -> v.getVariableType() == type)
                                .count();
                        if (count > 0) {
                            typeBreakdown.put(type.toString(), count);
                        }
                    }
                    modelInfo.put("variableTypes", typeBreakdown);

                    models.add(modelInfo);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("sameDiffModelCount", models.size());
            result.put("models", models);

            return result;

        } catch (Exception e) {
            logger.error("Error listing SameDiff models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to list models: " + e.getMessage());
        }
    }

    /**
     * Gets a detailed summary of a SameDiff model.
     */
    @Tool(name = "get_samediff_summary",
            description = "Gets detailed summary of a SameDiff embedding model including variables, operations, and training config. Specify modelIndex (default 0), and optionally includeVariables/includeOperations (default true) and maxItems limit (default 100).")
    public Map<String, Object> getSameDiffSummary(GetSameDiffSummaryInput input) {
        int modelIndex = input.modelIndex() != null ? input.modelIndex() : 0;
        boolean includeVariables = input.includeVariables() == null || input.includeVariables();
        boolean includeOperations = input.includeOperations() == null || input.includeOperations();
        int maxItems = input.maxItems() != null ? input.maxItems() : 100;

        logger.info("Getting SameDiff summary for model index: {}", modelIndex);

        try {
            if (embeddingModels.isEmpty()) {
                return Map.of("status", "error", "error", "No embedding models available");
            }

            if (modelIndex < 0 || modelIndex >= embeddingModels.size()) {
                return Map.of("status", "error", "error", "Invalid model index: " + modelIndex,
                        "availableIndices", "0-" + (embeddingModels.size() - 1));
            }

            EmbeddingModel model = embeddingModels.get(modelIndex);
            List<SameDiff> sameDiffs = extractSameDiffModels(model);

            if (sameDiffs.isEmpty()) {
                return Map.of("status", "error", "error", "Model at index " + modelIndex + " does not contain a SameDiff instance");
            }

            SameDiff sd = sameDiffs.get(0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("modelIndex", modelIndex);
            result.put("modelClass", model.getClass().getSimpleName());
            result.put("dimensions", model.getDimensions());

            // Basic counts
            result.put("totalVariables", sd.variableMap().size());
            result.put("totalOperations", sd.ops().length);

            // Inputs and outputs
            result.put("inputs", sd.inputs());
            result.put("outputs", sd.outputs());

            // Variable type breakdown
            Map<String, Long> typeBreakdown = new LinkedHashMap<>();
            for (VariableType type : VariableType.values()) {
                long count = sd.variableMap().values().stream()
                        .filter(v -> v.getVariableType() == type)
                        .count();
                if (count > 0) {
                    typeBreakdown.put(type.toString(), count);
                }
            }
            result.put("variableTypeBreakdown", typeBreakdown);

            // Variables (limited)
            if (includeVariables) {
                List<Map<String, Object>> variables = new ArrayList<>();
                int count = 0;
                for (SDVariable var : sd.variableMap().values()) {
                    if (count >= maxItems) break;
                    count++;

                    Map<String, Object> varInfo = new LinkedHashMap<>();
                    varInfo.put("name", var.name());
                    varInfo.put("type", var.getVariableType().toString());
                    varInfo.put("dataType", var.dataType().toString());
                    if (var.getShape() != null) {
                        varInfo.put("shape", Arrays.toString(var.getShape()));
                    }
                    variables.add(varInfo);
                }
                result.put("variables", variables);
                result.put("variablesTruncated", sd.variableMap().size() > maxItems);
            }

            // Operations (limited)
            if (includeOperations) {
                List<Map<String, String>> operations = new ArrayList<>();
                int count = 0;
                for (var op : sd.ops()) {
                    if (count >= maxItems) break;
                    count++;

                    Map<String, String> opInfo = new LinkedHashMap<>();
                    opInfo.put("name", op.getOwnName() != null ? op.getOwnName() : "unnamed");
                    opInfo.put("opType", op.opName());
                    opInfo.put("opClass", op.getClass().getSimpleName());
                    operations.add(opInfo);
                }
                result.put("operations", operations);
                result.put("operationsTruncated", sd.ops().length > maxItems);
            }

            // Training config
            if (sd.getTrainingConfig() != null) {
                Map<String, Object> trainingInfo = new LinkedHashMap<>();
                trainingInfo.put("configured", true);
                if (sd.getTrainingConfig().getUpdater() != null) {
                    trainingInfo.put("updater", sd.getTrainingConfig().getUpdater().getClass().getSimpleName());
                }
                result.put("trainingConfig", trainingInfo);
            } else {
                result.put("trainingConfig", Map.of("configured", false));
            }

            // Loss variables
            if (sd.getLossVariables() != null && !sd.getLossVariables().isEmpty()) {
                result.put("lossVariables", new ArrayList<>(sd.getLossVariables()));
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting SameDiff summary: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get summary: " + e.getMessage());
        }
    }

    /**
     * Gets variables from a SameDiff model with optional filtering.
     */
    @Tool(name = "get_samediff_variables",
            description = "Gets variables from a SameDiff model. Filter by variableType: VARIABLE, CONSTANT, PLACEHOLDER, ARRAY. Limit results with limit parameter (default 50).")
    public Map<String, Object> getSameDiffVariables(GetSameDiffVariablesInput input) {
        int modelIndex = input.modelIndex() != null ? input.modelIndex() : 0;
        String variableType = input.variableType();
        int limit = input.limit() != null ? input.limit() : 50;

        logger.info("Getting SameDiff variables for model: {}, type: {}", modelIndex, variableType);

        try {
            if (embeddingModels.isEmpty()) {
                return Map.of("status", "error", "error", "No embedding models available");
            }

            if (modelIndex < 0 || modelIndex >= embeddingModels.size()) {
                return Map.of("status", "error", "error", "Invalid model index");
            }

            EmbeddingModel model = embeddingModels.get(modelIndex);
            List<SameDiff> sameDiffs = extractSameDiffModels(model);

            if (sameDiffs.isEmpty()) {
                return Map.of("status", "error", "error", "No SameDiff instance in model");
            }

            SameDiff sd = sameDiffs.get(0);

            // Parse variable type filter
            VariableType filterType = null;
            if (variableType != null && !variableType.isEmpty()) {
                try {
                    filterType = VariableType.valueOf(variableType.toUpperCase());
                } catch (IllegalArgumentException e) {
                    return Map.of("status", "error", "error", "Invalid variable type: " + variableType,
                            "validTypes", Arrays.toString(VariableType.values()));
                }
            }

            List<Map<String, Object>> variables = new ArrayList<>();
            int count = 0;
            final VariableType finalFilterType = filterType;

            for (SDVariable var : sd.variableMap().values()) {
                if (finalFilterType != null && var.getVariableType() != finalFilterType) {
                    continue;
                }

                if (count >= limit) break;
                count++;

                Map<String, Object> varInfo = new LinkedHashMap<>();
                varInfo.put("name", var.name());
                varInfo.put("type", var.getVariableType().toString());
                varInfo.put("dataType", var.dataType().toString());

                if (var.getShape() != null) {
                    varInfo.put("shape", Arrays.toString(var.getShape()));
                }

                // Check if has data
                INDArray arr = var.getArr();
                if (arr != null) {
                    varInfo.put("hasData", true);
                    varInfo.put("actualShape", Arrays.toString(arr.shape()));
                    varInfo.put("length", arr.length());
                } else {
                    varInfo.put("hasData", false);
                }

                variables.add(varInfo);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("modelIndex", modelIndex);
            result.put("filter", variableType != null ? variableType : "all");
            result.put("count", variables.size());
            result.put("totalVariables", sd.variableMap().size());
            result.put("variables", variables);

            return result;

        } catch (Exception e) {
            logger.error("Error getting SameDiff variables: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get variables: " + e.getMessage());
        }
    }

    /**
     * Gets operations from a SameDiff model.
     */
    @Tool(name = "get_samediff_operations",
            description = "Gets operations/layers from a SameDiff model. Limit results with limit parameter (default 50).")
    public Map<String, Object> getSameDiffOperations(GetSameDiffOperationsInput input) {
        int modelIndex = input.modelIndex() != null ? input.modelIndex() : 0;
        int limit = input.limit() != null ? input.limit() : 50;

        logger.info("Getting SameDiff operations for model: {}", modelIndex);

        try {
            if (embeddingModels.isEmpty()) {
                return Map.of("status", "error", "error", "No embedding models available");
            }

            if (modelIndex < 0 || modelIndex >= embeddingModels.size()) {
                return Map.of("status", "error", "error", "Invalid model index");
            }

            EmbeddingModel model = embeddingModels.get(modelIndex);
            List<SameDiff> sameDiffs = extractSameDiffModels(model);

            if (sameDiffs.isEmpty()) {
                return Map.of("status", "error", "error", "No SameDiff instance in model");
            }

            SameDiff sd = sameDiffs.get(0);
            var ops = sd.ops();

            List<Map<String, Object>> operations = new ArrayList<>();
            int count = 0;

            for (var op : ops) {
                if (count >= limit) break;
                count++;

                Map<String, Object> opInfo = new LinkedHashMap<>();
                opInfo.put("index", count - 1);
                opInfo.put("name", op.getOwnName() != null ? op.getOwnName() : "unnamed");
                opInfo.put("opType", op.opName());
                opInfo.put("opClass", op.getClass().getSimpleName());

                // Try to get input/output info
                try {
                    String[] inputNames = sd.getInputsForOp(op);
                    if (inputNames != null) {
                        opInfo.put("inputs", Arrays.asList(inputNames));
                    }
                } catch (Exception ignored) {}

                try {
                    String[] outputNames = sd.getOutputsForOp(op);
                    if (outputNames != null) {
                        opInfo.put("outputs", Arrays.asList(outputNames));
                    }
                } catch (Exception ignored) {}

                operations.add(opInfo);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("modelIndex", modelIndex);
            result.put("count", operations.size());
            result.put("totalOperations", ops.length);
            result.put("operations", operations);

            return result;

        } catch (Exception e) {
            logger.error("Error getting SameDiff operations: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get operations: " + e.getMessage());
        }
    }

    /**
     * Gets the current ND4J environment configuration.
     */
    @Tool(name = "get_nd4j_environment",
            description = "Gets the current ND4J environment configuration including all boolean toggles (lifecycle tracking, debugging, profiling) and integer configs (stack depth, thread counts).")
    public Map<String, Object> getNd4jEnvironment(GetNd4jEnvironmentInput input) {
        logger.info("Getting ND4J environment configuration");

        try {
            var env = Nd4j.getEnvironment();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");

            // Boolean toggles - Core lifecycle tracking
            Map<String, Boolean> lifecycleToggles = new LinkedHashMap<>();
            lifecycleToggles.put("lifecycleTracking", env.isLifecycleTracking());
            lifecycleToggles.put("trackViews", env.isTrackViews());
            lifecycleToggles.put("trackDeletions", env.isTrackDeletions());
            lifecycleToggles.put("snapshotFiles", env.isSnapshotFiles());
            lifecycleToggles.put("trackOperations", env.isTrackOperations());
            result.put("lifecycleToggles", lifecycleToggles);

            // Debug and logging toggles
            Map<String, Boolean> debugToggles = new LinkedHashMap<>();
            debugToggles.put("verbose", env.isVerbose());
            debugToggles.put("debug", env.isDebug());
            debugToggles.put("profiling", env.isProfiling());
            debugToggles.put("leaksDetector", env.isDetectingLeaks());
            debugToggles.put("logNDArrayEvents", env.isLogNDArrayEvents());
            debugToggles.put("logNativeNDArrayCreation", env.isLogNativeNDArrayCreation());
            result.put("debugToggles", debugToggles);

            // Integer configs
            Map<String, Number> integerConfigs = new LinkedHashMap<>();
            integerConfigs.put("stackDepth", env.getStackDepth());
            integerConfigs.put("reportInterval", env.getReportInterval());
            integerConfigs.put("maxDeletionHistory", env.getMaxDeletionHistory());
            integerConfigs.put("tadThreshold", env.tadThreshold());
            integerConfigs.put("elementwiseThreshold", env.elementwiseThreshold());
            integerConfigs.put("maxThreads", env.maxThreads());
            integerConfigs.put("maxMasterThreads", env.maxMasterThreads());
            result.put("integerConfigs", integerConfigs);

            // Backend info
            Map<String, Object> backendInfo = new LinkedHashMap<>();
            backendInfo.put("backend", Nd4j.getBackend().getClass().getSimpleName());
            backendInfo.put("helpersAllowed", env.helpersAllowed());
            backendInfo.put("blasEnabled", env.isEnableBlas());
            result.put("backendInfo", backendInfo);

            return result;

        } catch (Exception e) {
            logger.error("Error getting ND4J environment: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get environment: " + e.getMessage());
        }
    }

    /**
     * Sets a specific ND4J environment toggle.
     */
    @Tool(name = "set_nd4j_toggle",
            description = "Sets a specific ND4J environment boolean toggle. Valid toggles: lifecycleTracking, trackViews, trackDeletions, snapshotFiles, trackOperations, verbose, debug, profiling, leaksDetector.")
    public Map<String, Object> setNd4jToggle(SetNd4jToggleInput input) {
        if (input.toggle() == null || input.toggle().isEmpty()) {
            return Map.of("status", "error", "error", "Toggle name is required");
        }
        if (input.enabled() == null) {
            return Map.of("status", "error", "error", "Enabled value is required");
        }

        String toggle = input.toggle();
        boolean enabled = input.enabled();

        logger.info("Setting ND4J toggle: {} = {}", toggle, enabled);

        try {
            var env = Nd4j.getEnvironment();

            switch (toggle.toLowerCase()) {
                case "lifecycletracking":
                    env.setLifecycleTracking(enabled);
                    break;
                case "trackviews":
                    env.setTrackViews(enabled);
                    break;
                case "trackdeletions":
                    env.setTrackDeletions(enabled);
                    if (enabled) {
                        logger.warn("trackDeletions enabled - this has HIGH overhead");
                    }
                    break;
                case "snapshotfiles":
                    env.setSnapshotFiles(enabled);
                    break;
                case "trackoperations":
                    env.setTrackOperations(enabled);
                    break;
                case "verbose":
                    env.setVerbose(enabled);
                    break;
                case "debug":
                    env.setDebug(enabled);
                    break;
                case "profiling":
                    env.setProfiling(enabled);
                    break;
                case "leaksdetector":
                    env.setLeaksDetector(enabled);
                    break;
                default:
                    return Map.of("status", "error", "error", "Unknown toggle: " + toggle,
                            "validToggles", Arrays.asList("lifecycleTracking", "trackViews", "trackDeletions",
                                    "snapshotFiles", "trackOperations", "verbose", "debug", "profiling", "leaksDetector"));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("toggle", toggle);
            result.put("enabled", enabled);
            result.put("message", "Toggle " + toggle + " set to " + enabled);

            return result;

        } catch (Exception e) {
            logger.error("Error setting ND4J toggle: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to set toggle: " + e.getMessage());
        }
    }

    /**
     * Sets a specific ND4J environment integer configuration.
     */
    @Tool(name = "set_nd4j_config",
            description = "Sets a specific ND4J environment integer configuration. Valid configs: stackDepth (1-256), reportInterval (1-3600), maxDeletionHistory (1-100000), maxThreads, maxMasterThreads.")
    public Map<String, Object> setNd4jConfig(SetNd4jConfigInput input) {
        if (input.config() == null || input.config().isEmpty()) {
            return Map.of("status", "error", "error", "Config name is required");
        }
        if (input.value() == null) {
            return Map.of("status", "error", "error", "Value is required");
        }

        String config = input.config();
        int value = input.value();

        logger.info("Setting ND4J config: {} = {}", config, value);

        try {
            var env = Nd4j.getEnvironment();

            switch (config.toLowerCase()) {
                case "stackdepth":
                    if (value < 1 || value > 256) {
                        return Map.of("status", "error", "error", "stackDepth must be between 1 and 256");
                    }
                    env.setStackDepth(value);
                    break;
                case "reportinterval":
                    if (value < 1 || value > 3600) {
                        return Map.of("status", "error", "error", "reportInterval must be between 1 and 3600");
                    }
                    env.setReportInterval(value);
                    break;
                case "maxdeletionhistory":
                    if (value < 1 || value > 100000) {
                        return Map.of("status", "error", "error", "maxDeletionHistory must be between 1 and 100000");
                    }
                    env.setMaxDeletionHistory(value);
                    break;
                case "maxthreads":
                    env.setMaxThreads(value);
                    break;
                case "maxmasterthreads":
                    env.setMaxMasterThreads(value);
                    break;
                default:
                    return Map.of("status", "error", "error", "Unknown config: " + config,
                            "validConfigs", Arrays.asList("stackDepth", "reportInterval", "maxDeletionHistory",
                                    "maxThreads", "maxMasterThreads"));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("config", config);
            result.put("value", value);
            result.put("message", "Config " + config + " set to " + value);

            return result;

        } catch (Exception e) {
            logger.error("Error setting ND4J config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to set config: " + e.getMessage());
        }
    }

    /**
     * Enables all ND4J debugging toggles.
     */
    @Tool(name = "enable_all_nd4j_toggles",
            description = "Enables ALL ND4J environment debugging toggles. WARNING: This enables high-overhead tracking features that may significantly impact performance. Use for debugging only.")
    public Map<String, Object> enableAllNd4jToggles(EnableAllNd4jTogglesInput input) {
        logger.warn("Enabling ALL ND4J environment toggles - this may cause significant overhead");

        try {
            var env = Nd4j.getEnvironment();

            env.setLifecycleTracking(true);
            env.setTrackViews(true);
            env.setTrackDeletions(true);
            env.setSnapshotFiles(true);
            env.setTrackOperations(true);
            env.setVerbose(true);
            env.setDebug(true);
            env.setProfiling(true);
            env.setLeaksDetector(true);

            Map<String, Boolean> enabledToggles = new LinkedHashMap<>();
            enabledToggles.put("lifecycleTracking", env.isLifecycleTracking());
            enabledToggles.put("trackViews", env.isTrackViews());
            enabledToggles.put("trackDeletions", env.isTrackDeletions());
            enabledToggles.put("snapshotFiles", env.isSnapshotFiles());
            enabledToggles.put("trackOperations", env.isTrackOperations());
            enabledToggles.put("verbose", env.isVerbose());
            enabledToggles.put("debug", env.isDebug());
            enabledToggles.put("profiling", env.isProfiling());
            enabledToggles.put("leaksDetector", env.isDetectingLeaks());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "All ND4J environment toggles enabled");
            result.put("warning", "High overhead tracking is now enabled - performance may be significantly impacted");
            result.put("enabledToggles", enabledToggles);

            return result;

        } catch (Exception e) {
            logger.error("Error enabling ND4J toggles: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to enable toggles: " + e.getMessage());
        }
    }

    /**
     * Disables all ND4J debugging toggles.
     */
    @Tool(name = "disable_all_nd4j_toggles",
            description = "Disables ALL ND4J environment debugging toggles. Use this to restore production performance after debugging.")
    public Map<String, Object> disableAllNd4jToggles(DisableAllNd4jTogglesInput input) {
        logger.info("Disabling ALL ND4J environment toggles");

        try {
            var env = Nd4j.getEnvironment();

            env.setLifecycleTracking(false);
            env.setTrackViews(false);
            env.setTrackDeletions(false);
            env.setSnapshotFiles(false);
            env.setTrackOperations(false);
            env.setVerbose(false);
            env.setDebug(false);
            env.setProfiling(false);
            env.setLeaksDetector(false);

            Map<String, Boolean> disabledToggles = new LinkedHashMap<>();
            disabledToggles.put("lifecycleTracking", env.isLifecycleTracking());
            disabledToggles.put("trackViews", env.isTrackViews());
            disabledToggles.put("trackDeletions", env.isTrackDeletions());
            disabledToggles.put("snapshotFiles", env.isSnapshotFiles());
            disabledToggles.put("trackOperations", env.isTrackOperations());
            disabledToggles.put("verbose", env.isVerbose());
            disabledToggles.put("debug", env.isDebug());
            disabledToggles.put("profiling", env.isProfiling());
            disabledToggles.put("leaksDetector", env.isDetectingLeaks());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "All ND4J environment toggles disabled");
            result.put("disabledToggles", disabledToggles);

            return result;

        } catch (Exception e) {
            logger.error("Error disabling ND4J toggles: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to disable toggles: " + e.getMessage());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Extract SameDiff models from an object using reflection.
     */
    private List<SameDiff> extractSameDiffModels(Object obj) {
        List<SameDiff> models = new ArrayList<>();

        if (obj == null) return models;

        if (obj instanceof SameDiff) {
            models.add((SameDiff) obj);
            return models;
        }

        try {
            Class<?> clazz = obj.getClass();

            // Traverse class hierarchy
            while (clazz != null && clazz != Object.class) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (SameDiff.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        SameDiff sd = (SameDiff) field.get(obj);
                        if (sd != null && !models.contains(sd)) {
                            models.add(sd);
                        }
                    }
                    // Check for SameDiffEncoder
                    else if (isSameDiffEncoder(field.getType())) {
                        field.setAccessible(true);
                        Object encoder = field.get(obj);
                        if (encoder != null) {
                            SameDiff sd = extractSameDiffFromEncoder(encoder);
                            if (sd != null && !models.contains(sd)) {
                                models.add(sd);
                            }
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }

            // Check getter methods
            for (Method method : obj.getClass().getMethods()) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    Class<?> returnType = method.getReturnType();

                    if (SameDiff.class.isAssignableFrom(returnType)) {
                        try {
                            SameDiff sd = (SameDiff) method.invoke(obj);
                            if (sd != null && !models.contains(sd)) {
                                models.add(sd);
                            }
                        } catch (Exception ignored) {}
                    }
                    else if (isSameDiffEncoder(returnType)) {
                        try {
                            Object encoder = method.invoke(obj);
                            if (encoder != null) {
                                SameDiff sd = extractSameDiffFromEncoder(encoder);
                                if (sd != null && !models.contains(sd)) {
                                    models.add(sd);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error extracting SameDiff models: {}", e.getMessage());
        }

        return models;
    }

    private boolean isSameDiffEncoder(Class<?> clazz) {
        if (clazz == null) return false;

        String className = clazz.getName();
        if (className.contains("SameDiffEncoder")) return true;

        Class<?> superClass = clazz.getSuperclass();
        while (superClass != null && superClass != Object.class) {
            if (superClass.getName().contains("SameDiffEncoder")) return true;
            superClass = superClass.getSuperclass();
        }

        return false;
    }

    private SameDiff extractSameDiffFromEncoder(Object encoder) {
        if (encoder == null) return null;

        try {
            Method getSameDiffModel = encoder.getClass().getMethod("getSameDiffModel");
            Object result = getSameDiffModel.invoke(encoder);
            if (result instanceof SameDiff) {
                return (SameDiff) result;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            logger.debug("Error invoking getSameDiffModel: {}", e.getMessage());
        }

        // Fallback: direct field access
        try {
            Class<?> clazz = encoder.getClass();
            while (clazz != null && clazz != Object.class) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (SameDiff.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        return (SameDiff) field.get(encoder);
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            logger.debug("Error accessing SameDiff field: {}", e.getMessage());
        }

        return null;
    }
}
