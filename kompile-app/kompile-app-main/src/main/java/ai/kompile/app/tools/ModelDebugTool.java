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
import org.nd4j.linalg.api.device.DeviceDescriptor;
import org.nd4j.linalg.api.device.DeviceMemoryManager;
import org.nd4j.linalg.api.device.DeviceRoutingConfiguration;
import org.nd4j.linalg.api.device.DeviceType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.DeviceAwareOpExecutioner;
import org.nd4j.linalg.api.ops.executioner.TransferMetrics;
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
 * MCP Tool for model debugging and device monitoring operations.
 *
 * <p>Exposes functionality to:
 * <ul>
 *   <li>Inspect SameDiff models, variables, and operations</li>
 *   <li>Query and configure ND4J environment settings</li>
 *   <li>Monitor hybrid/multi-device execution (CPU, GPU, etc.)</li>
 *   <li>Track cross-device data transfers and bandwidth</li>
 *   <li>Analyze multi-backend execution (CPU vs GPU op distribution)</li>
 *   <li>Configure device routing policies and memory caps</li>
 * </ul>
 *
 * <p>New in this version: Comprehensive hybrid and multi-device monitoring tools:
 * <ul>
 *   <li>{@code get_device_info} - List all compute devices with capabilities</li>
 *   <li>{@code get_device_memory_stats} - Per-device memory allocation tracking</li>
 *   <li>{@code get_transfer_metrics} - Cross-device transfer bandwidth and overhead</li>
 *   <li>{@code get_multi_backend_summary} - CPU vs GPU execution breakdown</li>
 *   <li>{@code get_routing_configuration} - Current routing policy settings</li>
 *   <li>{@code set_routing_configuration} - Apply preset routing configs</li>
 *   <li>{@code get_multi_backend_status} - Multi-backend execution status</li>
 *   <li>{@code enable_multi_backend} - Enable automatic CPU fallback</li>
 *   <li>{@code reset_transfer_metrics} - Reset transfer measurements</li>
 *   <li>{@code reset_device_memory_peaks} - Reset peak memory tracking</li>
 * </ul>
 *
 * @see org.nd4j.linalg.api.device.DeviceMemoryManager
 * @see org.nd4j.linalg.api.device.DeviceRoutingConfiguration
 * @see org.nd4j.linalg.api.ops.executioner.TransferMetrics
 * @see org.nd4j.linalg.api.ops.executioner.DeviceAwareOpExecutioner
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

    // Input records for hybrid/multi-device monitoring
    public record GetDeviceInfoInput(Boolean includeCapabilities) {}
    public record GetDeviceMemoryStatsInput(String deviceId) {}
    public record GetTransferMetricsInput(Boolean includeRoutes) {}
    public record GetMultiBackendSummaryInput() {}
    public record GetRoutingConfigurationInput() {}
    public record SetRoutingConfigurationInput(String preset) {}
    public record EnableMultiBackendInput() {}
    public record GetMultiBackendStatusInput() {}
    public record ResetTransferMetricsInput() {}
    public record ResetDeviceMemoryPeaksInput() {}

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
    // Hybrid and Multi-Device/Multi-Backend Monitoring
    // ========================================================================

    /**
     * Gets information about all registered compute devices.
     */
    @Tool(name = "get_device_info",
            description = "Gets information about all registered compute devices including CPU and GPU. Returns device types, names, memory, compute capability, and optionally hardware capabilities (FP16, tensor cores, AVX512, etc.).")
    public Map<String, Object> getDeviceInfo(GetDeviceInfoInput input) {
        boolean includeCapabilities = input.includeCapabilities() == null || input.includeCapabilities();

        logger.info("Getting device information");

        try {
            DeviceMemoryManager mgr = DeviceMemoryManager.getInstance();
            Collection<DeviceDescriptor> devices = mgr.getRegisteredDevices();

            List<Map<String, Object>> deviceList = new ArrayList<>();

            for (DeviceDescriptor device : devices) {
                Map<String, Object> deviceInfo = new LinkedHashMap<>();
                deviceInfo.put("deviceId", device.getDeviceId());
                deviceInfo.put("deviceType", device.getDeviceType().toString());
                deviceInfo.put("deviceIndex", device.getDeviceIndex());
                deviceInfo.put("deviceName", device.getDeviceName());
                deviceInfo.put("backendId", device.getBackendId());
                deviceInfo.put("isDefault", device.isDefault());
                deviceInfo.put("isAvailable", device.isAvailable());

                // Memory info
                Map<String, Object> memoryInfo = new LinkedHashMap<>();
                memoryInfo.put("totalMemoryMB", device.getTotalMemory() / (1024 * 1024));
                memoryInfo.put("availableMemoryMB", device.getAvailableMemory() / (1024 * 1024));
                memoryInfo.put("memoryBandwidthGBps", device.getMemoryBandwidth() / (1024.0 * 1024 * 1024));
                deviceInfo.put("memory", memoryInfo);

                // Compute info
                Map<String, Object> computeInfo = new LinkedHashMap<>();
                computeInfo.put("computeUnits", device.getComputeUnits());
                computeInfo.put("computeCapability", device.getComputeCapability());
                computeInfo.put("estimatedTFLOPS", device.getEstimatedFlops() / 1e12);
                deviceInfo.put("compute", computeInfo);

                // Capabilities
                if (includeCapabilities) {
                    Set<String> capabilities = device.getCapabilities();
                    deviceInfo.put("capabilities", new ArrayList<>(capabilities));

                    // Highlight key capabilities
                    Map<String, Boolean> keyCapabilities = new LinkedHashMap<>();
                    keyCapabilities.put("fp16", device.hasCapability(DeviceDescriptor.CAPABILITY_FP16));
                    keyCapabilities.put("fp64", device.hasCapability(DeviceDescriptor.CAPABILITY_FP64));
                    keyCapabilities.put("int8", device.hasCapability(DeviceDescriptor.CAPABILITY_INT8));
                    keyCapabilities.put("tensorCores", device.hasCapability(DeviceDescriptor.CAPABILITY_TENSOR_CORES));
                    keyCapabilities.put("unifiedMemory", device.hasCapability(DeviceDescriptor.CAPABILITY_UNIFIED_MEMORY));
                    keyCapabilities.put("avx2", device.hasCapability(DeviceDescriptor.CAPABILITY_AVX2));
                    keyCapabilities.put("avx512", device.hasCapability(DeviceDescriptor.CAPABILITY_AVX512));
                    deviceInfo.put("keyCapabilities", keyCapabilities);
                }

                // Device properties
                Map<String, Object> properties = device.getProperties();
                if (properties != null && !properties.isEmpty()) {
                    deviceInfo.put("properties", properties);
                }

                deviceList.add(deviceInfo);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("deviceCount", deviceList.size());
            result.put("defaultDevice", mgr.getDefaultDevice() != null ? mgr.getDefaultDevice().getDeviceId() : "none");
            result.put("fallbackDevice", mgr.getFallbackDevice() != null ? mgr.getFallbackDevice().getDeviceId() : "none");
            result.put("autoFallbackEnabled", mgr.isAutoFallbackEnabled());
            result.put("devices", deviceList);

            return result;

        } catch (Exception e) {
            logger.error("Error getting device info: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get device info: " + e.getMessage());
        }
    }

    /**
     * Gets memory statistics for all devices or a specific device.
     */
    @Tool(name = "get_device_memory_stats",
            description = "Gets detailed memory statistics for compute devices. Shows allocated, available, peak memory, memory caps, and utilization percentage. Optionally filter by deviceId (e.g., 'cuda:gpu:0' or 'native:cpu:0').")
    public Map<String, Object> getDeviceMemoryStats(GetDeviceMemoryStatsInput input) {
        String deviceId = input.deviceId();

        logger.info("Getting device memory stats{}", deviceId != null ? " for device: " + deviceId : "");

        try {
            DeviceMemoryManager mgr = DeviceMemoryManager.getInstance();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");

            if (deviceId != null && !deviceId.isEmpty()) {
                // Get stats for specific device
                DeviceDescriptor device = DeviceDescriptor.fromId(deviceId);
                if (!mgr.isDeviceRegistered(device)) {
                    return Map.of("status", "error", "error", "Device not registered: " + deviceId,
                            "registeredDevices", mgr.getRegisteredDevices().stream()
                                    .map(DeviceDescriptor::getDeviceId).toList());
                }

                DeviceMemoryManager.DeviceMemoryStats stats = mgr.getMemoryStats(device);
                result.put("deviceId", stats.deviceId);
                result.put("stats", formatMemoryStats(stats));

            } else {
                // Get stats for all devices
                Map<String, DeviceMemoryManager.DeviceMemoryStats> allStats = mgr.getMemoryStats();
                List<Map<String, Object>> statsList = new ArrayList<>();

                for (Map.Entry<String, DeviceMemoryManager.DeviceMemoryStats> entry : allStats.entrySet()) {
                    Map<String, Object> deviceStats = new LinkedHashMap<>();
                    deviceStats.put("deviceId", entry.getKey());
                    deviceStats.putAll(formatMemoryStats(entry.getValue()));
                    statsList.add(deviceStats);
                }

                result.put("deviceCount", statsList.size());
                result.put("memoryPressureThreshold", mgr.getMemoryPressureThreshold());
                result.put("devices", statsList);
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting device memory stats: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get memory stats: " + e.getMessage());
        }
    }

    private Map<String, Object> formatMemoryStats(DeviceMemoryManager.DeviceMemoryStats stats) {
        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("totalMemoryMB", stats.total / (1024 * 1024));
        formatted.put("allocatedMemoryMB", stats.allocated / (1024 * 1024));
        formatted.put("availableMemoryMB", stats.available / (1024 * 1024));
        formatted.put("peakMemoryMB", stats.peak / (1024 * 1024));
        formatted.put("memoryCapMB", stats.cap > 0 ? stats.cap / (1024 * 1024) : "unlimited");
        formatted.put("utilizationPercent", String.format("%.1f", stats.utilization * 100));
        return formatted;
    }

    /**
     * Gets transfer metrics for cross-device data movement.
     */
    @Tool(name = "get_transfer_metrics",
            description = "Gets metrics for data transfers between devices. Shows total transfers, bytes moved, bandwidth, transfer overhead percentage, and optionally per-route breakdown (e.g., CPU->GPU, GPU->GPU).")
    public Map<String, Object> getTransferMetrics(GetTransferMetricsInput input) {
        boolean includeRoutes = input.includeRoutes() == null || input.includeRoutes();

        logger.info("Getting transfer metrics");

        try {
            TransferMetrics metrics = TransferMetrics.getInstance();
            Map<String, Object> stats = metrics.getStatistics();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");

            // Summary statistics
            result.put("totalTransfers", stats.get("totalTransfers"));
            result.put("totalBytesMB", ((Long) stats.get("totalBytes")) / (1024.0 * 1024));
            result.put("totalTimeMs", stats.get("totalTimeMs"));
            result.put("overheadPercent", String.format("%.1f", (Double) stats.get("overheadPercent")));
            result.put("averageBandwidthGBps", String.format("%.2f", (Double) stats.get("averageBandwidthGBps")));

            // Warning threshold info
            result.put("warningThresholdPercent", metrics.getWarningThresholdPercent());
            result.put("loggingIndividualTransfers", metrics.isLogIndividualTransfers());
            result.put("minBytesForLoggingMB", metrics.getMinBytesForLogging() / (1024 * 1024));

            // CPU fallback stats
            result.put("cpuFallbackCount", metrics.getCpuFallbackCount());
            result.put("cpuFallbackTimeMs", metrics.getCpuFallbackTimeNanos() / 1_000_000.0);

            // Per-route breakdown
            if (includeRoutes) {
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> routes = (Map<String, Map<String, Object>>) stats.get("routes");
                if (routes != null && !routes.isEmpty()) {
                    List<Map<String, Object>> routeList = new ArrayList<>();
                    for (Map.Entry<String, Map<String, Object>> entry : routes.entrySet()) {
                        Map<String, Object> routeInfo = new LinkedHashMap<>();
                        routeInfo.put("route", entry.getKey());
                        routeInfo.put("transfers", entry.getValue().get("transfers"));
                        routeInfo.put("bytesMB", ((Long) entry.getValue().get("bytes")) / (1024.0 * 1024));
                        routeInfo.put("avgBandwidthGBps", String.format("%.2f", entry.getValue().get("avgBandwidthGBps")));
                        routeList.add(routeInfo);
                    }
                    result.put("routes", routeList);
                }
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting transfer metrics: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get transfer metrics: " + e.getMessage());
        }
    }

    /**
     * Gets multi-backend execution summary.
     */
    @Tool(name = "get_multi_backend_summary",
            description = "Gets a summary of multi-backend (CPU vs GPU) execution. Shows operation counts, execution times, CPU fallback statistics, and data transfer overhead. Useful for analyzing hybrid execution performance.")
    public Map<String, Object> getMultiBackendSummary(GetMultiBackendSummaryInput input) {
        logger.info("Getting multi-backend execution summary");

        try {
            TransferMetrics metrics = TransferMetrics.getInstance();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");

            // Get backend execution stats
            Map<String, Object> cpuStats = metrics.getBackendExecutionStats(DeviceType.CPU);
            Map<String, Object> gpuStats = metrics.getBackendExecutionStats(DeviceType.CUDA_GPU);

            long cpuOps = (Long) cpuStats.get("opCount");
            long gpuOps = (Long) gpuStats.get("opCount");
            long totalOps = cpuOps + gpuOps;

            // CPU stats
            Map<String, Object> cpuInfo = new LinkedHashMap<>();
            cpuInfo.put("opCount", cpuOps);
            cpuInfo.put("opPercent", totalOps > 0 ? String.format("%.1f", 100.0 * cpuOps / totalOps) : "0.0");
            cpuInfo.put("totalTimeMs", cpuStats.get("totalTimeMs"));
            cpuInfo.put("avgTimeMs", cpuOps > 0 ? ((Long) cpuStats.get("totalTimeNanos")) / (cpuOps * 1_000_000.0) : 0);
            result.put("cpu", cpuInfo);

            // GPU stats
            Map<String, Object> gpuInfo = new LinkedHashMap<>();
            gpuInfo.put("opCount", gpuOps);
            gpuInfo.put("opPercent", totalOps > 0 ? String.format("%.1f", 100.0 * gpuOps / totalOps) : "0.0");
            gpuInfo.put("totalTimeMs", gpuStats.get("totalTimeMs"));
            gpuInfo.put("avgTimeMs", gpuOps > 0 ? ((Long) gpuStats.get("totalTimeNanos")) / (gpuOps * 1_000_000.0) : 0);
            result.put("gpu", gpuInfo);

            // CPU fallback stats
            Map<String, Object> fallbackInfo = new LinkedHashMap<>();
            fallbackInfo.put("count", metrics.getCpuFallbackCount());
            fallbackInfo.put("totalTimeMs", metrics.getCpuFallbackTimeNanos() / 1_000_000.0);
            result.put("cpuFallback", fallbackInfo);

            // Transfer overhead
            Map<String, Object> transferInfo = new LinkedHashMap<>();
            transferInfo.put("overheadPercent", String.format("%.1f", metrics.getOverheadPercent()));
            transferInfo.put("averageBandwidthGBps", String.format("%.2f", metrics.getAverageBandwidthGBps()));
            result.put("transferOverhead", transferInfo);

            // Overall summary
            result.put("totalOperations", totalOps);
            result.put("summaryText", metrics.getMultiBackendSummary());

            return result;

        } catch (Exception e) {
            logger.error("Error getting multi-backend summary: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get multi-backend summary: " + e.getMessage());
        }
    }

    /**
     * Gets current device routing configuration.
     */
    @Tool(name = "get_routing_configuration",
            description = "Gets the current device routing configuration including routing policy, auto-transfer settings, memory caps, and logging options.")
    public Map<String, Object> getRoutingConfiguration(GetRoutingConfigurationInput input) {
        logger.info("Getting routing configuration");

        try {
            DeviceRoutingConfiguration config = DeviceRoutingConfiguration.current();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");

            // Device selection
            Map<String, Object> selection = new LinkedHashMap<>();
            selection.put("defaultPolicy", config.getDefaultPolicy().toString());
            selection.put("autoFallbackEnabled", config.isAutoFallbackEnabled());
            selection.put("preferredDeviceTypes", config.getPreferredDeviceTypes().stream()
                    .map(DeviceType::toString).toList());
            result.put("deviceSelection", selection);

            // Auto transfer settings
            Map<String, Object> transfer = new LinkedHashMap<>();
            transfer.put("autoTransferEnabled", config.isAutoTransferEnabled());
            transfer.put("asyncTransferEnabled", config.isAsyncTransferEnabled());
            transfer.put("asyncTransferThresholdKB", config.getAsyncTransferThresholdBytes() / 1024);
            transfer.put("cacheTransferredData", config.isCacheTransferredData());
            transfer.put("maxCachePerDeviceMB", config.getMaxCachePerDevice() / (1024 * 1024));
            transfer.put("prefetchEnabled", config.isPrefetchEnabled());
            transfer.put("prefetchDepth", config.getPrefetchDepth());
            result.put("autoTransfer", transfer);

            // Memory management
            Map<String, Object> memory = new LinkedHashMap<>();
            memory.put("gpuMemoryCapPercent", (int) (config.getGpuMemoryCapFraction() * 100));
            memory.put("cpuMemoryCapPercent", (int) (config.getCpuMemoryCapFraction() * 100));
            memory.put("memoryPressureThresholdPercent", (int) (config.getMemoryPressureThreshold() * 100));
            memory.put("evictOnPressure", config.isEvictOnPressure());
            result.put("memoryManagement", memory);

            // Op execution
            Map<String, Object> opExec = new LinkedHashMap<>();
            opExec.put("routeOpsByInputLocation", config.isRouteOpsByInputLocation());
            opExec.put("minSizeForDeviceRoutingBytes", config.getMinSizeForDeviceRouting());
            opExec.put("cpuOnlyOpsCount", config.getCpuOnlyOps().size());
            opExec.put("gpuPreferredOpsCount", config.getGpuPreferredOps().size());
            result.put("opExecution", opExec);

            // Synchronization
            Map<String, Object> sync = new LinkedHashMap<>();
            sync.put("syncAfterOp", config.isSyncAfterOp());
            sync.put("syncBeforeHostRead", config.isSyncBeforeHostRead());
            sync.put("useEvents", config.isUseEvents());
            result.put("synchronization", sync);

            // Logging and debugging
            Map<String, Object> logging = new LinkedHashMap<>();
            logging.put("logRoutingDecisions", config.isLogRoutingDecisions());
            logging.put("logDataTransfers", config.isLogDataTransfers());
            logging.put("collectStatistics", config.isCollectStatistics());
            logging.put("transferOverheadWarningThresholdPercent", (int) config.getTransferOverheadWarningThreshold());
            result.put("logging", logging);

            return result;

        } catch (Exception e) {
            logger.error("Error getting routing configuration: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get routing configuration: " + e.getMessage());
        }
    }

    /**
     * Sets device routing configuration using a preset.
     */
    @Tool(name = "set_routing_configuration",
            description = "Applies a preset device routing configuration. Available presets: 'default', 'gpu_optimized', 'cpu_only', 'multi_gpu_balanced', 'memory_constrained', 'debug'. Each preset optimizes for different workload patterns.")
    public Map<String, Object> setRoutingConfiguration(SetRoutingConfigurationInput input) {
        if (input.preset() == null || input.preset().isEmpty()) {
            return Map.of("status", "error", "error", "Preset name is required",
                    "availablePresets", Arrays.asList("default", "gpu_optimized", "cpu_only",
                            "multi_gpu_balanced", "memory_constrained", "debug"));
        }

        String preset = input.preset().toLowerCase().replace("-", "_");
        logger.info("Setting routing configuration preset: {}", preset);

        try {
            DeviceRoutingConfiguration config;

            switch (preset) {
                case "default":
                    config = DeviceRoutingConfiguration.defaultConfig();
                    break;
                case "gpu_optimized":
                    config = DeviceRoutingConfiguration.gpuOptimized();
                    break;
                case "cpu_only":
                    config = DeviceRoutingConfiguration.cpuOnly();
                    break;
                case "multi_gpu_balanced":
                    config = DeviceRoutingConfiguration.multiGpuBalanced();
                    break;
                case "memory_constrained":
                    config = DeviceRoutingConfiguration.memoryConstrained();
                    break;
                case "debug":
                    config = DeviceRoutingConfiguration.debug();
                    break;
                default:
                    return Map.of("status", "error", "error", "Unknown preset: " + preset,
                            "availablePresets", Arrays.asList("default", "gpu_optimized", "cpu_only",
                                    "multi_gpu_balanced", "memory_constrained", "debug"));
            }

            config.apply();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("preset", preset);
            result.put("message", "Routing configuration preset '" + preset + "' applied");

            // Return key settings of applied config
            Map<String, Object> applied = new LinkedHashMap<>();
            applied.put("policy", config.getDefaultPolicy().toString());
            applied.put("autoTransfer", config.isAutoTransferEnabled());
            applied.put("gpuMemoryCapPercent", (int) (config.getGpuMemoryCapFraction() * 100));
            applied.put("logTransfers", config.isLogDataTransfers());
            result.put("appliedSettings", applied);

            return result;

        } catch (Exception e) {
            logger.error("Error setting routing configuration: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to set configuration: " + e.getMessage());
        }
    }

    /**
     * Gets multi-backend execution status.
     */
    @Tool(name = "get_multi_backend_status",
            description = "Gets the current status of multi-backend execution. Shows whether DeviceAwareOpExecutioner is installed, which backends are registered, and if multi-backend mode is active.")
    public Map<String, Object> getMultiBackendStatus(GetMultiBackendStatusInput input) {
        logger.info("Getting multi-backend status");

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");

            boolean installed = DeviceAwareOpExecutioner.isInstalled();
            result.put("deviceAwareExecutionerInstalled", installed);

            if (installed) {
                DeviceAwareOpExecutioner executor = DeviceAwareOpExecutioner.getInstance();
                result.put("multiBackendEnabled", executor.isMultiBackendEnabled());
                result.put("enabled", executor.isEnabled());
                result.put("backendInfo", executor.getBackendInfo());
            } else {
                result.put("multiBackendEnabled", false);
                result.put("message", "DeviceAwareOpExecutioner not installed. Use enable_multi_backend to install.");
            }

            // Auto-enable setting
            result.put("autoMultiBackendEnabled", DeviceAwareOpExecutioner.isAutoMultiBackendEnabled());
            result.put("autoEnableProperty", "nd4j.multibackend.enabled");

            return result;

        } catch (Exception e) {
            logger.error("Error getting multi-backend status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get status: " + e.getMessage());
        }
    }

    /**
     * Enables multi-backend execution.
     */
    @Tool(name = "enable_multi_backend",
            description = "Enables multi-backend execution by installing the DeviceAwareOpExecutioner. This allows automatic CPU fallback when GPU memory is constrained and enables transparent cross-device data transfers.")
    public Map<String, Object> enableMultiBackend(EnableMultiBackendInput input) {
        logger.info("Enabling multi-backend execution");

        try {
            if (DeviceAwareOpExecutioner.isInstalled()) {
                DeviceAwareOpExecutioner executor = DeviceAwareOpExecutioner.getInstance();
                return Map.of("status", "success",
                        "message", "DeviceAwareOpExecutioner already installed",
                        "multiBackendEnabled", executor.isMultiBackendEnabled(),
                        "backendInfo", executor.getBackendInfo());
            }

            boolean multiBackendSuccess = DeviceAwareOpExecutioner.installWithMultiBackend();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("installed", true);
            result.put("multiBackendEnabled", multiBackendSuccess);

            if (multiBackendSuccess) {
                result.put("message", "Multi-backend execution enabled with CPU fallback support");
            } else {
                result.put("message", "Single-backend mode installed (CPU fallback not available)");
            }

            if (DeviceAwareOpExecutioner.isInstalled()) {
                result.put("backendInfo", DeviceAwareOpExecutioner.getInstance().getBackendInfo());
            }

            return result;

        } catch (Exception e) {
            logger.error("Error enabling multi-backend: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to enable multi-backend: " + e.getMessage());
        }
    }

    /**
     * Resets transfer metrics.
     */
    @Tool(name = "reset_transfer_metrics",
            description = "Resets all transfer metrics (transfer counts, bytes, timing, routes). Use this to start fresh measurements for a specific workload or benchmark.")
    public Map<String, Object> resetTransferMetrics(ResetTransferMetricsInput input) {
        logger.info("Resetting transfer metrics");

        try {
            TransferMetrics metrics = TransferMetrics.getInstance();

            // Get current stats before reset
            long transfersBefore = (Long) metrics.getStatistics().get("totalTransfers");
            long bytesBefore = (Long) metrics.getStatistics().get("totalBytes");

            metrics.reset();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "Transfer metrics reset");
            result.put("clearedTransfers", transfersBefore);
            result.put("clearedBytesMB", bytesBefore / (1024.0 * 1024));

            return result;

        } catch (Exception e) {
            logger.error("Error resetting transfer metrics: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to reset metrics: " + e.getMessage());
        }
    }

    /**
     * Resets device memory peak tracking.
     */
    @Tool(name = "reset_device_memory_peaks",
            description = "Resets peak memory tracking for all devices. Useful for measuring peak memory usage of a specific workload or operation sequence.")
    public Map<String, Object> resetDeviceMemoryPeaks(ResetDeviceMemoryPeaksInput input) {
        logger.info("Resetting device memory peak tracking");

        try {
            DeviceMemoryManager mgr = DeviceMemoryManager.getInstance();

            // Get current peaks before reset
            Map<String, Long> peaksBefore = new LinkedHashMap<>();
            for (DeviceDescriptor device : mgr.getRegisteredDevices()) {
                peaksBefore.put(device.getDeviceId(), mgr.getPeakMemory(device) / (1024 * 1024));
            }

            mgr.resetPeakMemory();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "Peak memory tracking reset for all devices");
            result.put("previousPeaksMB", peaksBefore);

            return result;

        } catch (Exception e) {
            logger.error("Error resetting memory peaks: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to reset peaks: " + e.getMessage());
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
