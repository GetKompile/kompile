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

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.services.EmbeddingStatusBroadcaster;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.subprocess.model.ModelInitSubprocessLauncher;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.config.AnseriniEmbeddingStartupInitializer;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.VariableType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * REST controller for debugging and inspecting loaded models.
 * Provides endpoints to view:
 * - Loaded SameDiff models and their summaries
 * - Embedding models and their configurations
 * - Language models and their metadata
 * - Model variables, operations, and graph structure
 * - Model initialization status (including subprocess mode)
 */
@RestController
@RequestMapping("/api/models")
public class ModelDebugController {

    private static final Logger logger = LoggerFactory.getLogger(ModelDebugController.class);

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private List<EmbeddingModel> embeddingModels;

    @Autowired(required = false)
    private List<LanguageModel> languageModels;

    @Autowired(required = false)
    private Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;

    @Autowired(required = false)
    private AnseriniEmbeddingStartupInitializer startupInitializer;

    @Autowired(required = false)
    private ModelInitSubprocessLauncher subprocessLauncher;

    @Autowired(required = false)
    private EmbeddingStatusBroadcaster embeddingStatusBroadcaster;

    /**
     * Quick status check for embedding model availability and configuration.
     * Use this to diagnose pipeline bottlenecks related to embedding.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getModelStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        // Embedding model status
        Map<String, Object> embeddingStatus = new LinkedHashMap<>();
        if (embeddingModels == null || embeddingModels.isEmpty()) {
            embeddingStatus.put("available", false);
            embeddingStatus.put("reason", "No embedding models configured");
        } else {
            embeddingStatus.put("available", true);
            embeddingStatus.put("count", embeddingModels.size());

            List<Map<String, Object>> models = new ArrayList<>();
            for (EmbeddingModel model : embeddingModels) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("class", model.getClass().getSimpleName());
                info.put("dimensions", model.dimensions());
                info.put("optimalBatchSize", model.getOptimalBatchSize());
                info.put("maxBatchSize", model.getMaxBatchSize());

                // Check if it's a no-op implementation
                boolean isNoOp = model.getClass().getSimpleName().contains("NoOp");
                info.put("isNoOp", isNoOp);

                models.add(info);
            }
            embeddingStatus.put("models", models);

            // Primary model info
            EmbeddingModel primary = embeddingModels.get(0);
            embeddingStatus.put("primaryModel", primary.getClass().getSimpleName());
            embeddingStatus.put("primaryIsNoOp", primary.getClass().getSimpleName().contains("NoOp"));
        }
        status.put("embedding", embeddingStatus);

        // Language model status
        Map<String, Object> llmStatus = new LinkedHashMap<>();
        if (languageModels == null || languageModels.isEmpty()) {
            llmStatus.put("available", false);
        } else {
            llmStatus.put("available", true);
            llmStatus.put("count", languageModels.size());
            llmStatus.put("primaryModel", languageModels.get(0).getClass().getSimpleName());
        }
        status.put("languageModel", llmStatus);

        // Memory status
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("maxMB", runtime.maxMemory() / (1024 * 1024));
        memory.put("totalMB", runtime.totalMemory() / (1024 * 1024));
        memory.put("freeMB", runtime.freeMemory() / (1024 * 1024));
        memory.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        memory.put("usagePercent", ((runtime.totalMemory() - runtime.freeMemory()) * 100.0) / runtime.maxMemory());
        status.put("memory", memory);

        // Model initialization status (subprocess mode)
        Map<String, Object> initStatus = new LinkedHashMap<>();
        if (startupInitializer != null) {
            initStatus.put("subprocessModeEnabled", startupInitializer.isSubprocessInitEnabled());
            initStatus.put("subprocessStatus", startupInitializer.getSubprocessStatus().name());
            initStatus.put("subprocessProgress", startupInitializer.getSubprocessProgressPercent());
            initStatus.put("subprocessMessage", startupInitializer.getSubprocessStatusMessage());
            initStatus.put("pollingActive", startupInitializer.isPollingActive());
        }
        if (subprocessLauncher != null) {
            var launcherStatus = subprocessLauncher.getCurrentStatus();
            initStatus.put("launcherStatus", launcherStatus.status().name());
            initStatus.put("launcherTaskId", launcherStatus.taskId());
            initStatus.put("launcherModelId", launcherStatus.modelId());
            initStatus.put("launcherPhase", launcherStatus.phase() != null ? launcherStatus.phase().name() : null);
            initStatus.put("launcherProgress", launcherStatus.progressPercent());
            initStatus.put("launcherMessage", launcherStatus.message());
            initStatus.put("launcherIsRunning", subprocessLauncher.isInitializationRunning());
        }
        status.put("initialization", initStatus);

        return ResponseEntity.ok(status);
    }

    /**
     * Get detailed model initialization status for UI polling.
     * This endpoint provides comprehensive information about model initialization
     * including subprocess status when subprocess mode is enabled.
     */
    @GetMapping("/init-status")
    public ResponseEntity<Map<String, Object>> getInitializationStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        // Get primary embedding model status
        AnseriniEmbeddingModelImpl anseriniModel = null;
        if (embeddingModels != null) {
            for (EmbeddingModel model : embeddingModels) {
                if (model instanceof AnseriniEmbeddingModelImpl) {
                    anseriniModel = (AnseriniEmbeddingModelImpl) model;
                    break;
                }
            }
        }

        if (anseriniModel != null) {
            status.put("modelId", anseriniModel.getModelIdentifier());
            status.put("initialized", anseriniModel.isInitialized());
            status.put("loading", anseriniModel.isLoading());
            status.put("loadingPhase", anseriniModel.getLoadingPhase());
            status.put("loadingMessage", anseriniModel.getLoadingMessage());
            status.put("modelSource", anseriniModel.getModelSource().name());
            status.put("encoderType", anseriniModel.getEncoderType() != null ? anseriniModel.getEncoderType() : null);
            status.put("dimensions", anseriniModel.dimensions());
            status.put("initializationError", anseriniModel.getInitializationError());
        }

        // Subprocess mode status
        Map<String, Object> subprocess = new LinkedHashMap<>();
        if (startupInitializer != null) {
            subprocess.put("enabled", startupInitializer.isSubprocessInitEnabled());
            subprocess.put("status", startupInitializer.getSubprocessStatus().name());
            subprocess.put("progress", startupInitializer.getSubprocessProgressPercent());
            subprocess.put("message", startupInitializer.getSubprocessStatusMessage());

            // Map phase to user-friendly description
            String phaseDescription = switch (startupInitializer.getSubprocessStatus()) {
                case IDLE -> "Waiting to start";
                case STARTING -> "Starting subprocess JVM";
                case INITIALIZING_ND4J -> "Initializing ND4J backend";
                case CONFIGURING_SOURCE -> "Configuring model source";
                case LOOKING_UP_REGISTRY -> "Looking up model in registry";
                case LOADING_MODEL -> "Loading model files";
                case CREATING_ENCODER -> "Creating encoder (building neural network graph)";
                case VALIDATING -> "Validating model output";
                case COMPLETED -> "Model initialization complete";
                case FAILED -> "Initialization failed";
            };
            subprocess.put("phaseDescription", phaseDescription);
        }

        // Launcher status (if using subprocess)
        if (subprocessLauncher != null) {
            var launcherStatus = subprocessLauncher.getCurrentStatus();
            subprocess.put("launcherStatus", launcherStatus.status().name());
            subprocess.put("launcherTaskId", launcherStatus.taskId());
            subprocess.put("launcherPhase", launcherStatus.phase() != null ? launcherStatus.phase().name() : null);
            subprocess.put("launcherProgress", launcherStatus.progressPercent());
            subprocess.put("launcherMessage", launcherStatus.message());
            subprocess.put("launcherRunning", subprocessLauncher.isInitializationRunning());

            if (launcherStatus.embeddingDimensions() != null) {
                subprocess.put("resultDimensions", launcherStatus.embeddingDimensions());
            }
            if (launcherStatus.encoderType() != null) {
                subprocess.put("resultEncoderType", launcherStatus.encoderType());
            }
            if (launcherStatus.errorMessage() != null) {
                subprocess.put("error", launcherStatus.errorMessage());
                subprocess.put("errorRetriable", launcherStatus.errorRetriable());
            }
        }
        status.put("subprocess", subprocess);

        // Memory status
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        memory.put("maxMB", runtime.maxMemory() / (1024 * 1024));
        memory.put("usagePercent", ((runtime.totalMemory() - runtime.freeMemory()) * 100.0) / runtime.maxMemory());
        status.put("memory", memory);

        return ResponseEntity.ok(status);
    }

    /**
     * Get a comprehensive list of ALL loaded models in the application context
     * Scans all beans for SameDiff, ComputationGraph, and MultiLayerNetwork models
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listModels() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            // 1. List embedding models
            List<Map<String, Object>> embeddingInfo = new ArrayList<>();
            if (embeddingModels != null) {
                for (int i = 0; i < embeddingModels.size(); i++) {
                    EmbeddingModel model = embeddingModels.get(i);
                    Map<String, Object> info = createModelInfo(model, "embedding", i);
                    embeddingInfo.add(info);
                }
            }
            response.put("embeddingModels", embeddingInfo);
            response.put("embeddingModelCount", embeddingInfo.size());

            // 2. List language models
            List<Map<String, Object>> languageInfo = new ArrayList<>();
            if (languageModels != null) {
                for (int i = 0; i < languageModels.size(); i++) {
                    LanguageModel model = languageModels.get(i);
                    Map<String, Object> info = createModelInfo(model, "language", i);
                    languageInfo.add(info);
                }
            }
            response.put("languageModels", languageInfo);
            response.put("languageModelCount", languageInfo.size());

            // 3. Scan ALL beans for DL4J/SameDiff models
            List<Map<String, Object>> allModels = new ArrayList<>();
            Map<String, Object> allBeans = applicationContext.getBeansOfType(Object.class);

            for (Map.Entry<String, Object> entry : allBeans.entrySet()) {
                String beanName = entry.getKey();
                Object bean = entry.getValue();

                // Skip Spring internal beans and our own controller
                if (beanName.startsWith("org.springframework") ||
                    beanName.startsWith("spring") ||
                    bean instanceof ModelDebugController) {
                    continue;
                }

                Map<String, Object> beanModelInfo = scanBeanForModels(beanName, bean);
                if (!beanModelInfo.isEmpty()) {
                    allModels.add(beanModelInfo);
                }
            }
            response.put("allBeanModels", allModels);
            response.put("allBeanModelCount", allModels.size());

            // 4. Summary counts
            int totalSameDiff = countModelsOfType(allModels, "sameDiff");
            int totalComputationGraph = countModelsOfType(allModels, "computationGraph");
            int totalMultiLayerNetwork = countModelsOfType(allModels, "multiLayerNetwork");

            Map<String, Integer> summary = new LinkedHashMap<>();
            summary.put("totalSameDiffModels", totalSameDiff);
            summary.put("totalComputationGraphModels", totalComputationGraph);
            summary.put("totalMultiLayerNetworkModels", totalMultiLayerNetwork);
            summary.put("totalEmbeddingModels", embeddingInfo.size());
            summary.put("totalLanguageModels", languageInfo.size());
            response.put("summary", summary);

            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error listing models", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Create detailed model info for a given object
     */
    private Map<String, Object> createModelInfo(Object model, String category, int index) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("index", index);
        info.put("category", category);
        info.put("className", model.getClass().getSimpleName());
        info.put("fullClassName", model.getClass().getName());

        if (model instanceof EmbeddingModel) {
            info.put("dimensions", ((EmbeddingModel) model).dimensions());
            info.put("type", determineEmbeddingType((EmbeddingModel) model));
        }

        // Check for embedded models
        List<SameDiff> sameDiffs = extractSameDiffModels(model);
        List<Object> compGraphs = extractModelsOfType(model, "org.deeplearning4j.nn.graph.ComputationGraph");
        List<Object> mlns = extractModelsOfType(model, "org.deeplearning4j.nn.multilayer.MultiLayerNetwork");

        if (!sameDiffs.isEmpty()) {
            Map<String, Object> sdInfo = summarizeSameDiff(sameDiffs.get(0));
            info.put("sameDiffModel", sdInfo);
        }

        if (!compGraphs.isEmpty()) {
            Map<String, Object> cgInfo = summarizeDL4JModel(compGraphs.get(0), "computationGraph");
            info.put("computationGraphModel", cgInfo);
        }

        if (!mlns.isEmpty()) {
            Map<String, Object> mlnInfo = summarizeDL4JModel(mlns.get(0), "multiLayerNetwork");
            info.put("multiLayerNetworkModel", mlnInfo);
        }

        return info;
    }

    /**
     * Scan a single bean for all model types
     */
    private Map<String, Object> scanBeanForModels(String beanName, Object bean) {
        Map<String, Object> info = new LinkedHashMap<>();

        List<SameDiff> sameDiffs = extractSameDiffModels(bean);
        List<Object> compGraphs = extractModelsOfType(bean, "org.deeplearning4j.nn.graph.ComputationGraph");
        List<Object> mlns = extractModelsOfType(bean, "org.deeplearning4j.nn.multilayer.MultiLayerNetwork");

        if (sameDiffs.isEmpty() && compGraphs.isEmpty() && mlns.isEmpty()) {
            return info; // Empty map means no models found
        }

        info.put("beanName", beanName);
        info.put("beanClass", bean.getClass().getSimpleName());
        info.put("beanFullClass", bean.getClass().getName());

        if (!sameDiffs.isEmpty()) {
            List<Map<String, Object>> sdList = new ArrayList<>();
            for (SameDiff sd : sameDiffs) {
                sdList.add(summarizeSameDiff(sd));
            }
            info.put("sameDiff", sdList);
        }

        if (!compGraphs.isEmpty()) {
            List<Map<String, Object>> cgList = new ArrayList<>();
            for (Object cg : compGraphs) {
                cgList.add(summarizeDL4JModel(cg, "computationGraph"));
            }
            info.put("computationGraph", cgList);
        }

        if (!mlns.isEmpty()) {
            List<Map<String, Object>> mlnList = new ArrayList<>();
            for (Object mln : mlns) {
                mlnList.add(summarizeDL4JModel(mln, "multiLayerNetwork"));
            }
            info.put("multiLayerNetwork", mlnList);
        }

        return info;
    }

    /**
     * Generic method to extract models of a specific type using reflection
     * @param obj The object to inspect
     * @param className Fully qualified class name of the model type to find
     * @return List of found model instances
     */
    private List<Object> extractModelsOfType(Object obj, String className) {
        List<Object> models = new ArrayList<>();

        if (obj == null) {
            return models;
        }

        try {
            // Try to load the class (may not be available if dependency not included)
            Class<?> targetClass;
            try {
                targetClass = Class.forName(className);
            } catch (ClassNotFoundException e) {
                logger.debug("Class not found (dependency may be missing): {}", className);
                return models;
            }

            // Direct instance check
            if (targetClass.isInstance(obj)) {
                models.add(obj);
                return models;
            }

            // Check fields via reflection
            Class<?> clazz = obj.getClass();
            for (Field field : clazz.getDeclaredFields()) {
                if (targetClass.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object model = field.get(obj);
                    if (model != null) {
                        models.add(model);
                    }
                }
            }

            // Check getter methods
            for (Method method : clazz.getMethods()) {
                if (method.getName().startsWith("get") &&
                    method.getParameterCount() == 0 &&
                    targetClass.isAssignableFrom(method.getReturnType())) {
                    try {
                        Object model = method.invoke(obj);
                        if (model != null && !models.contains(model)) {
                            models.add(model);
                        }
                    } catch (Exception e) {
                        // Ignore invocation errors
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error extracting models of type {} from object", className, e);
        }

        return models;
    }

    /**
     * Summarize a SameDiff model
     */
    private Map<String, Object> summarizeSameDiff(SameDiff sd) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", "sameDiff");
        summary.put("numVariables", sd.variableMap().size());
        summary.put("numOperations", sd.ops().length);
        summary.put("hasLoss", sd.getLossVariables() != null && !sd.getLossVariables().isEmpty());
        summary.put("trainingConfig", sd.getTrainingConfig() != null);
        summary.put("inputs", sd.inputs());
        summary.put("outputs", sd.outputs());
        return summary;
    }

    /**
     * Generic method to summarize a DL4J model using reflection
     * @param model The model instance (ComputationGraph or MultiLayerNetwork)
     * @param modelType The type identifier ("computationGraph" or "multiLayerNetwork")
     * @return Summary map with model information
     */
    private Map<String, Object> summarizeDL4JModel(Object model, String modelType) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", modelType);

        if (model == null) {
            summary.put("error", "Model is null");
            return summary;
        }

        try {
            Class<?> modelClass = model.getClass();

            // Try to get numLayers (works for both ComputationGraph and MultiLayerNetwork)
            try {
                Method getNumLayers = modelClass.getMethod("getNumLayers");
                int numLayers = (int) getNumLayers.invoke(model);
                summary.put("numLayers", numLayers);
            } catch (NoSuchMethodException e) {
                // Try getnLayers for MultiLayerNetwork
                try {
                    Method getnLayers = modelClass.getMethod("getnLayers");
                    int numLayers = (int) getnLayers.invoke(model);
                    summary.put("numLayers", numLayers);
                } catch (Exception e2) {
                    logger.debug("Could not get layer count from model", e2);
                }
            }

            // Try to get numParams
            try {
                Method numParams = modelClass.getMethod("numParams");
                long paramCount = (long) numParams.invoke(model);
                summary.put("numParameters", paramCount);
            } catch (Exception e) {
                logger.debug("Could not get param count from model", e);
            }

            // For ComputationGraph, try to get input/output names
            if ("computationGraph".equals(modelType)) {
                try {
                    Method getConfiguration = modelClass.getMethod("getConfiguration");
                    Object config = getConfiguration.invoke(model);

                    if (config != null) {
                        Class<?> configClass = config.getClass();

                        // Get network inputs
                        try {
                            Method getNetworkInputs = configClass.getMethod("getNetworkInputs");
                            Object inputs = getNetworkInputs.invoke(config);
                            if (inputs != null) {
                                summary.put("inputNames", inputs.toString());
                            }
                        } catch (Exception e) {
                            logger.debug("Could not get network inputs", e);
                        }

                        // Get network outputs
                        try {
                            Method getNetworkOutputs = configClass.getMethod("getNetworkOutputs");
                            Object outputs = getNetworkOutputs.invoke(config);
                            if (outputs != null) {
                                summary.put("outputNames", outputs.toString());
                            }
                        } catch (Exception e) {
                            logger.debug("Could not get network outputs", e);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not get configuration from ComputationGraph", e);
                }
            }

            // For MultiLayerNetwork, try to get layer names
            if ("multiLayerNetwork".equals(modelType)) {
                try {
                    Method getLayerNames = modelClass.getMethod("getLayerNames");
                    Object layerNames = getLayerNames.invoke(model);
                    if (layerNames != null) {
                        summary.put("layerNames", layerNames);
                    }
                } catch (Exception e) {
                    logger.debug("Could not get layer names from MultiLayerNetwork", e);
                }
            }

        } catch (Exception e) {
            logger.error("Error summarizing DL4J model of type: " + modelType, e);
            summary.put("error", e.getMessage());
        }

        return summary;
    }

    /**
     * Count models of a specific type in the scanned beans
     */
    private int countModelsOfType(List<Map<String, Object>> allModels, String modelType) {
        int count = 0;
        for (Map<String, Object> beanInfo : allModels) {
            if (beanInfo.containsKey(modelType)) {
                Object models = beanInfo.get(modelType);
                if (models instanceof List) {
                    count += ((List<?>) models).size();
                }
            }
        }
        return count;
    }

    /**
     * Get a list of all SameDiff-based embedding models
     */
    @GetMapping("/samediff-embeddings/list")
    public ResponseEntity<Map<String, Object>> listSameDiffEmbeddings() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            List<Map<String, Object>> sameDiffEmbeddings = new ArrayList<>();

            if (embeddingModels != null) {
                for (EmbeddingModel model : embeddingModels) {
                    // Only include models that have SameDiff instances
                    List<SameDiff> sameDiffs = extractSameDiffModels(model);
                    if (!sameDiffs.isEmpty()) {
                        SameDiff sd = sameDiffs.get(0);

                        Map<String, Object> modelInfo = new LinkedHashMap<>();
                        modelInfo.put("className", model.getClass().getSimpleName());
                        modelInfo.put("fullClassName", model.getClass().getName());
                        modelInfo.put("dimensions", model.dimensions());
                        modelInfo.put("numVariables", sd.variableMap().size());
                        modelInfo.put("numOperations", sd.ops().length);
                        modelInfo.put("hasLoss", sd.getLossVariables() != null && !sd.getLossVariables().isEmpty());
                        modelInfo.put("trainingConfig", sd.getTrainingConfig() != null);

                        // Add variable type breakdown
                        Map<String, Long> typeBreakdown = new LinkedHashMap<>();
                        for (VariableType type : VariableType.values()) {
                            long count = sd.variableMap().values().stream()
                                    .filter(v -> v.getVariableType() == type)
                                    .count();
                            if (count > 0) {
                                typeBreakdown.put(type.toString(), count);
                            }
                        }
                        modelInfo.put("variableTypeBreakdown", typeBreakdown);

                        sameDiffEmbeddings.add(modelInfo);
                    }
                }
            }

            response.put("sameDiffEmbeddings", sameDiffEmbeddings);
            response.put("count", sameDiffEmbeddings.size());
            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error listing SameDiff embedding models", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Determine the type of embedding model based on its class name
     */
    private String determineEmbeddingType(EmbeddingModel model) {
        String className = model.getClass().getSimpleName();
        if (className.contains("SameDiff")) {
            return "samediff";
        } else if (className.contains("SentenceTransformer")) {
            return "sentence-transformer";
        } else if (className.contains("OpenAi")) {
            return "openai";
        } else if (className.contains("PostgresMl")) {
            return "postgresml";
        } else if (className.contains("Anserini")) {
            return "anserini";
        } else {
            return "other";
        }
    }

    /**
     * Get the raw SameDiff summary() output as plain text from an embedding model
     * This is the complete model summary without truncation, suitable for large models
     */
    @GetMapping(value = "/samediff-embeddings/{modelIndex}/summary/text", produces = "text/plain")
    public ResponseEntity<String> getSameDiffEmbeddingSummaryText(@PathVariable(name = "modelIndex") int modelIndex) {
        try {
            if (embeddingModels == null || modelIndex < 0 || modelIndex >= embeddingModels.size()) {
                return ResponseEntity.notFound().build();
            }

            EmbeddingModel model = embeddingModels.get(modelIndex);
            List<SameDiff> sameDiffs = extractSameDiffModels(model);

            if (sameDiffs.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body("Model at index " + modelIndex + " does not have a SameDiff instance");
            }

            SameDiff sd = sameDiffs.get(0);
            String summary = sd.summary();

            return ResponseEntity.ok()
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .body(summary);

        } catch (Exception e) {
            logger.error("Error getting SameDiff summary text for embedding model: " + modelIndex, e);
            return ResponseEntity.internalServerError()
                    .body("Error: " + e.getMessage());
        }
    }

    /**
     * Get the raw SameDiff summary() output as plain text from a bean name (legacy endpoint)
     * This is the complete model summary without truncation, suitable for large models
     */
    @GetMapping(value = "/samediff/{beanName}/summary/text", produces = "text/plain")
    public ResponseEntity<String> getSameDiffSummaryText(@PathVariable(name = "beanName") String beanName) {
        try {
            Object bean = applicationContext.getBean(beanName);
            List<SameDiff> models = extractSameDiffModels(bean);

            if (models.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            SameDiff sd = models.get(0);

            // Get the full summary string from SameDiff
            String summary = sd.summary();

            return ResponseEntity.ok()
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .body(summary);

        } catch (Exception e) {
            logger.error("Error getting SameDiff summary text for bean: " + beanName, e);
            return ResponseEntity.internalServerError()
                    .body("Error: " + e.getMessage());
        }
    }

    /**
     * Get detailed summary of a SameDiff embedding model as JSON with pagination support
     * @param modelIndex The index of the embedding model
     * @param includeVariables Whether to include variable details (default: true)
     * @param includeOperations Whether to include operation details (default: true)
     * @param maxVariables Maximum number of variables to include (default: 1000, 0 = all)
     * @param maxOperations Maximum number of operations to include (default: 1000, 0 = all)
     */
    @GetMapping("/samediff-embeddings/{modelIndex}/summary")
    public ResponseEntity<Map<String, Object>> getSameDiffEmbeddingSummary(
            @PathVariable(name = "modelIndex") int modelIndex,
            @RequestParam(name = "includeVariables", defaultValue = "true") boolean includeVariables,
            @RequestParam(name = "includeOperations", defaultValue = "true") boolean includeOperations,
            @RequestParam(name = "maxVariables", defaultValue = "1000") int maxVariables,
            @RequestParam(name = "maxOperations", defaultValue = "1000") int maxOperations) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (embeddingModels == null || modelIndex < 0 || modelIndex >= embeddingModels.size()) {
                response.put("status", "error");
                response.put("message", "Invalid model index: " + modelIndex);
                return ResponseEntity.notFound().build();
            }

            EmbeddingModel model = embeddingModels.get(modelIndex);
            List<SameDiff> sameDiffs = extractSameDiffModels(model);

            if (sameDiffs.isEmpty()) {
                response.put("status", "error");
                response.put("message", "Model at index " + modelIndex + " does not have a SameDiff instance");
                return ResponseEntity.badRequest().body(response);
            }

            SameDiff sd = sameDiffs.get(0);

            // Basic info
            response.put("modelIndex", modelIndex);
            response.put("modelType", determineEmbeddingType(model));
            response.put("className", model.getClass().getSimpleName());
            response.put("fullClassName", model.getClass().getName());
            response.put("dimensions", model.dimensions());

            return buildSameDiffSummary(sd, includeVariables, includeOperations, maxVariables, maxOperations, response);

        } catch (Exception e) {
            logger.error("Error getting SameDiff embedding summary for model: " + modelIndex, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get detailed summary of a specific SameDiff model as JSON with pagination support (legacy endpoint)
     * @param beanName The name of the bean containing the SameDiff model
     * @param includeVariables Whether to include variable details (default: true)
     * @param includeOperations Whether to include operation details (default: true)
     * @param maxVariables Maximum number of variables to include (default: 1000, 0 = all)
     * @param maxOperations Maximum number of operations to include (default: 1000, 0 = all)
     */
    @GetMapping("/samediff/{beanName}/summary")
    public ResponseEntity<Map<String, Object>> getSameDiffSummary(
            @PathVariable(name = "beanName") String beanName,
            @RequestParam(name = "includeVariables", defaultValue = "true") boolean includeVariables,
            @RequestParam(name = "includeOperations", defaultValue = "true") boolean includeOperations,
            @RequestParam(name = "maxVariables", defaultValue = "1000") int maxVariables,
            @RequestParam(name = "maxOperations", defaultValue = "1000") int maxOperations) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            Object bean = applicationContext.getBean(beanName);
            List<SameDiff> models = extractSameDiffModels(bean);

            if (models.isEmpty()) {
                response.put("status", "error");
                response.put("message", "No SameDiff models found in bean: " + beanName);
                return ResponseEntity.notFound().build();
            }

            SameDiff sd = models.get(0);

            // Basic info
            response.put("modelName", "SameDiff-" + beanName);
            response.put("beanName", beanName);

            return buildSameDiffSummary(sd, includeVariables, includeOperations, maxVariables, maxOperations, response);

        } catch (Exception e) {
            logger.error("Error getting SameDiff summary for bean: " + beanName, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Helper method to build detailed SameDiff summary
     */
    private ResponseEntity<Map<String, Object>> buildSameDiffSummary(
            SameDiff sd,
            boolean includeVariables,
            boolean includeOperations,
            int maxVariables,
            int maxOperations,
            Map<String, Object> response) {

        try {
            // Total counts (always include these)
            response.put("totalVariables", sd.variableMap().size());
            response.put("totalOperations", sd.ops().length);

            // Variables (with pagination)
            if (includeVariables) {
                List<Map<String, Object>> variables = new ArrayList<>();
                int varCount = 0;
                int limit = maxVariables <= 0 ? Integer.MAX_VALUE : maxVariables;

                for (Map.Entry<String, org.nd4j.autodiff.samediff.SDVariable> varEntry : sd.variableMap().entrySet()) {
                    if (varCount >= limit) {
                        break;
                    }
                    varCount++;

                    Map<String, Object> varInfo = new LinkedHashMap<>();
                    org.nd4j.autodiff.samediff.SDVariable var = varEntry.getValue();
                    varInfo.put("name", var.name());
                    varInfo.put("type", var.getVariableType().toString());
                    varInfo.put("dataType", var.dataType().toString());

                    if (var.getShape() != null) {
                        varInfo.put("shape", Arrays.toString(var.getShape()));
                    }

                    // Check if variable has an array
                    if (sd.hasVariable(var.name())) {
                        INDArray arr = sd.getVariable(var.name()).getArr();
                        if (arr != null) {
                            varInfo.put("hasData", true);
                            varInfo.put("actualShape", Arrays.toString(arr.shape()));
                            varInfo.put("length", arr.length());
                        } else {
                            varInfo.put("hasData", false);
                        }
                    }

                    variables.add(varInfo);
                }
                response.put("variables", variables);
                response.put("variableCount", variables.size());
                response.put("variablesTruncated", sd.variableMap().size() > variables.size());
            }

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
            response.put("variableTypeBreakdown", typeBreakdown);

            // Operations (with pagination) - enhanced with graph edges and execution order
            if (includeOperations) {
                List<Map<String, Object>> operations = new ArrayList<>();
                List<Map<String, Object>> graphEdges = new ArrayList<>();
                Map<String, Integer> variableExecutionOrder = new LinkedHashMap<>();
                org.nd4j.autodiff.functions.DifferentialFunction[] ops = sd.ops();
                int limit = maxOperations <= 0 ? Integer.MAX_VALUE : maxOperations;
                int opCount = 0;
                int executionStep = 0;

                for (org.nd4j.autodiff.functions.DifferentialFunction op : ops) {
                    if (opCount >= limit) {
                        break;
                    }

                    Map<String, Object> opInfo = new LinkedHashMap<>();
                    String opName = op.getOwnName() != null ? op.getOwnName() : "op_" + opCount;
                    opInfo.put("name", opName);
                    opInfo.put("opType", op.opName());
                    opInfo.put("opClass", op.getClass().getSimpleName());
                    opInfo.put("executionOrder", executionStep);
                    opCount++;
                    executionStep++;

                    // Get input variable names for graph edges
                    List<String> inputVarNames = new ArrayList<>();
                    try {
                        org.nd4j.autodiff.samediff.SDVariable[] args = op.args();
                        if (args != null) {
                            for (org.nd4j.autodiff.samediff.SDVariable arg : args) {
                                if (arg != null && arg.name() != null) {
                                    inputVarNames.add(arg.name());
                                    // Create edge: variable -> operation
                                    Map<String, Object> edge = new LinkedHashMap<>();
                                    edge.put("source", arg.name());
                                    edge.put("target", opName);
                                    edge.put("type", "input");
                                    edge.put("targetExecutionOrder", executionStep - 1);
                                    graphEdges.add(edge);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Could not get input args for op: " + opName, e);
                    }
                    opInfo.put("inputs", inputVarNames);

                    // Get output variable names for graph edges
                    List<String> outputVarNames = new ArrayList<>();
                    try {
                        org.nd4j.autodiff.samediff.SDVariable[] outputs = op.outputVariables();
                        if (outputs != null) {
                            for (org.nd4j.autodiff.samediff.SDVariable out : outputs) {
                                if (out != null && out.name() != null) {
                                    outputVarNames.add(out.name());
                                    // Track when this variable is produced
                                    variableExecutionOrder.put(out.name(), executionStep - 1);
                                    // Create edge: operation -> variable
                                    Map<String, Object> edge = new LinkedHashMap<>();
                                    edge.put("source", opName);
                                    edge.put("target", out.name());
                                    edge.put("type", "output");
                                    edge.put("sourceExecutionOrder", executionStep - 1);
                                    graphEdges.add(edge);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Could not get output vars for op: " + opName, e);
                    }
                    opInfo.put("outputs", outputVarNames);

                    operations.add(opInfo);
                }
                response.put("operations", operations);
                response.put("operationCount", operations.size());
                response.put("operationsTruncated", ops.length > operations.size());
                response.put("graphEdges", graphEdges);
                response.put("variableExecutionOrder", variableExecutionOrder);
                response.put("totalExecutionSteps", executionStep);
            }

            // Training config
            if (sd.getTrainingConfig() != null) {
                Map<String, Object> trainingInfo = new LinkedHashMap<>();
                trainingInfo.put("configured", true);
                trainingInfo.put("updater", sd.getTrainingConfig().getUpdater() != null ?
                        sd.getTrainingConfig().getUpdater().getClass().getSimpleName() : null);
                response.put("trainingConfig", trainingInfo);
            } else {
                response.put("trainingConfig", Collections.singletonMap("configured", false));
            }

            // Loss variables
            if (sd.getLossVariables() != null && !sd.getLossVariables().isEmpty()) {
                response.put("lossVariables", new ArrayList<>(sd.getLossVariables()));
            }

            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error building SameDiff summary", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get detailed information about embedding models
     */
    @GetMapping("/embeddings/info")
    public ResponseEntity<Map<String, Object>> getEmbeddingModelsInfo() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            List<Map<String, Object>> modelsInfo = new ArrayList<>();

            if (embeddingModels != null) {
                for (EmbeddingModel model : embeddingModels) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("className", model.getClass().getSimpleName());
                    info.put("fullClassName", model.getClass().getName());
                    info.put("dimensions", model.dimensions());

                    // Try to extract SameDiff model if present
                    List<SameDiff> sameDiffs = extractSameDiffModels(model);
                    if (!sameDiffs.isEmpty()) {
                        SameDiff sd = sameDiffs.get(0);
                        Map<String, Object> sdInfo = new LinkedHashMap<>();
                        sdInfo.put("modelName", "SameDiff-EmbeddingModel");
                        sdInfo.put("numVariables", sd.variableMap().size());
                        sdInfo.put("numOperations", sd.ops().length);
                        info.put("sameDiffModel", sdInfo);
                    }

                    modelsInfo.add(info);
                }
            }

            response.put("models", modelsInfo);
            response.put("count", modelsInfo.size());
            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting embedding models info", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Test an embedding model with sample text
     */
    @PostMapping("/embeddings/test")
    public ResponseEntity<Map<String, Object>> testEmbeddingModel(
            @RequestParam(name = "text", defaultValue = "Hello world") String text,
            @RequestParam(name = "modelIndex", defaultValue = "0") int modelIndex) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (embeddingModels == null || embeddingModels.isEmpty()) {
                response.put("status", "error");
                response.put("message", "No embedding models available");
                return ResponseEntity.badRequest().body(response);
            }

            if (modelIndex < 0 || modelIndex >= embeddingModels.size()) {
                response.put("status", "error");
                response.put("message", "Invalid model index. Available models: " + embeddingModels.size());
                return ResponseEntity.badRequest().body(response);
            }

            EmbeddingModel model = embeddingModels.get(modelIndex);

            long startTime = System.currentTimeMillis();
            INDArray embedding = model.embed(text);
            long duration = System.currentTimeMillis() - startTime;

            response.put("modelClass", model.getClass().getSimpleName());
            response.put("inputText", text);
            response.put("embeddingShape", Arrays.toString(embedding.shape()));
            response.put("embeddingLength", embedding.length());
            response.put("embeddingPreview", Arrays.toString(
                    Arrays.copyOf(embedding.toFloatVector(), Math.min(10, (int)embedding.length()))
            ));
            response.put("inferenceTimeMs", duration);
            response.put("status", "success");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error testing embedding model", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Helper method to extract SameDiff models from an object using reflection
     */
    private List<SameDiff> extractSameDiffModels(Object obj) {
        List<SameDiff> models = new ArrayList<>();

        if (obj == null) {
            return models;
        }

        // Direct instance check
        if (obj instanceof SameDiff) {
            models.add((SameDiff) obj);
            return models;
        }

        // Check fields via reflection - including all parent class fields
        try {
            Class<?> clazz = obj.getClass();

            // Traverse the entire class hierarchy
            while (clazz != null && clazz != Object.class) {
                for (Field field : clazz.getDeclaredFields()) {
                    // Check if field is a SameDiff instance
                    if (SameDiff.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        SameDiff sd = (SameDiff) field.get(obj);
                        if (sd != null && !models.contains(sd)) {
                            models.add(sd);
                        }
                    }
                    // Check if field is a SameDiffEncoder - special handling for Anserini encoders
                    else if (isSameDiffEncoder(field.getType())) {
                        field.setAccessible(true);
                        Object encoder = field.get(obj);
                        if (encoder != null) {
                            // Try to extract SameDiff from the encoder
                            SameDiff sd = extractSameDiffFromEncoder(encoder);
                            if (sd != null && !models.contains(sd)) {
                                models.add(sd);
                                logger.debug("Extracted SameDiff model from encoder field: {} in class: {}",
                                        field.getName(), clazz.getSimpleName());
                            }
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }

            // Check getter methods
            clazz = obj.getClass();
            for (Method method : clazz.getMethods()) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    Class<?> returnType = method.getReturnType();

                    // Direct SameDiff getter
                    if (SameDiff.class.isAssignableFrom(returnType)) {
                        try {
                            SameDiff sd = (SameDiff) method.invoke(obj);
                            if (sd != null && !models.contains(sd)) {
                                models.add(sd);
                            }
                        } catch (Exception e) {
                            logger.debug("Error invoking getter: {}", method.getName(), e);
                        }
                    }
                    // SameDiffEncoder getter
                    else if (isSameDiffEncoder(returnType)) {
                        try {
                            Object encoder = method.invoke(obj);
                            if (encoder != null) {
                                SameDiff sd = extractSameDiffFromEncoder(encoder);
                                if (sd != null && !models.contains(sd)) {
                                    models.add(sd);
                                    logger.debug("Extracted SameDiff model from encoder via method: {} in class: {}",
                                            method.getName(), obj.getClass().getSimpleName());
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("Error invoking encoder getter: {}", method.getName(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error extracting SameDiff models from object", e);
        }

        return models;
    }

    /**
     * Check if a class is a SameDiffEncoder or subclass thereof
     */
    private boolean isSameDiffEncoder(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }

        // Check by class name since we may not have the encoder on classpath
        String className = clazz.getName();
        if (className.contains("SameDiffEncoder")) {
            return true;
        }

        // Check if superclass or interfaces contain SameDiffEncoder
        Class<?> superClass = clazz.getSuperclass();
        while (superClass != null && superClass != Object.class) {
            if (superClass.getName().contains("SameDiffEncoder")) {
                return true;
            }
            superClass = superClass.getSuperclass();
        }

        return false;
    }

    /**
     * Extract SameDiff model from a SameDiffEncoder instance
     */
    private SameDiff extractSameDiffFromEncoder(Object encoder) {
        if (encoder == null) {
            return null;
        }

        try {
            // Try the standard getter method
            Method getSameDiffModel = encoder.getClass().getMethod("getSameDiffModel");
            if (getSameDiffModel != null) {
                Object result = getSameDiffModel.invoke(encoder);
                if (result instanceof SameDiff) {
                    logger.debug("Successfully extracted SameDiff model from encoder using getSameDiffModel() method");
                    return (SameDiff) result;
                }
            }
        } catch (NoSuchMethodException e) {
            logger.debug("Encoder does not have getSameDiffModel() method, trying field access");
        } catch (Exception e) {
            logger.debug("Error invoking getSameDiffModel() on encoder", e);
        }

        // Fallback: try to access the field directly
        try {
            Class<?> clazz = encoder.getClass();
            while (clazz != null && clazz != Object.class) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (SameDiff.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        SameDiff sd = (SameDiff) field.get(encoder);
                        if (sd != null) {
                            logger.debug("Successfully extracted SameDiff model from encoder field: {}", field.getName());
                            return sd;
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            logger.debug("Error accessing SameDiff field in encoder", e);
        }

        return null;
    }

    // ========================================================================
    // ND4J ENVIRONMENT TOGGLE ENDPOINTS
    // ========================================================================

    /**
     * Get all ND4J environment toggle states.
     * Returns the current state of all boolean toggles and integer configurations,
     * organized by category for the UI.
     */
    @GetMapping("/nd4j/environment")
    public ResponseEntity<Map<String, Object>> getNd4jEnvironmentConfig() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();

            // ========== GROUPED BOOLEAN TOGGLES ==========

            // All boolean toggles in a flat map (for backward compatibility)
            Map<String, Boolean> booleanToggles = new LinkedHashMap<>();

            // Core Debugging toggles
            Map<String, Boolean> coreDebugging = new LinkedHashMap<>();
            coreDebugging.put("verbose", env.isVerbose());
            coreDebugging.put("debug", env.isDebug());
            coreDebugging.put("profiling", env.isProfiling());
            booleanToggles.putAll(coreDebugging);

            // Leak Detection toggles
            Map<String, Boolean> leakDetection = new LinkedHashMap<>();
            leakDetection.put("detectingLeaks", env.isDetectingLeaks());
            leakDetection.put("lifecycleTracking", env.isLifecycleTracking());
            leakDetection.put("trackViews", env.isTrackViews());
            leakDetection.put("trackDeletions", env.isTrackDeletions());
            leakDetection.put("trackOperations", env.isTrackOperations());
            booleanToggles.putAll(leakDetection);

            // Component-specific Lifecycle Tracking
            Map<String, Boolean> componentTracking = new LinkedHashMap<>();
            componentTracking.put("ndArrayTracking", env.isNDArrayTracking());
            componentTracking.put("dataBufferTracking", env.isDataBufferTracking());
            componentTracking.put("tadCacheTracking", env.isTADCacheTracking());
            componentTracking.put("shapeCacheTracking", env.isShapeCacheTracking());
            componentTracking.put("opContextTracking", env.isOpContextTracking());
            booleanToggles.putAll(componentTracking);

            // Logging toggles
            Map<String, Boolean> logging = new LinkedHashMap<>();
            logging.put("logNDArrayEvents", env.isLogNDArrayEvents());
            logging.put("logNativeNDArrayCreation", env.isLogNativeNDArrayCreation());
            logging.put("truncateLogStrings", env.isTruncateNDArrayLogStrings());
            booleanToggles.putAll(logging);

            // Function Trace toggles
            Map<String, Boolean> funcTrace = new LinkedHashMap<>();
            funcTrace.put("funcTraceAllocate", env.isFuncTracePrintAllocate());
            funcTrace.put("funcTraceDeallocate", env.isFuncTracePrintDeallocate());
            funcTrace.put("funcTracePrintJavaOnly", env.isFuncTracePrintJavaOnly());
            booleanToggles.putAll(funcTrace);

            // BLAS and Helpers toggles
            Map<String, Boolean> blasHelpers = new LinkedHashMap<>();
            blasHelpers.put("enableBlas", env.isEnableBlas());
            blasHelpers.put("helpersAllowed", env.helpersAllowed());
            booleanToggles.putAll(blasHelpers);

            // Memory Management toggles
            Map<String, Boolean> memoryManagement = new LinkedHashMap<>();
            memoryManagement.put("deletePrimary", env.isDeletePrimary());
            memoryManagement.put("deleteSpecial", env.isDeleteSpecial());
            memoryManagement.put("deleteShapeInfo", env.isDeleteShapeInfo());
            booleanToggles.putAll(memoryManagement);

            // Workspace toggles
            Map<String, Boolean> workspace = new LinkedHashMap<>();
            workspace.put("trackWorkspaceOpenClose", env.isTrackWorkspaceOpenClose());
            workspace.put("snapshotFiles", env.isSnapshotFiles());
            booleanToggles.putAll(workspace);

            // Input/Output Checking toggles
            Map<String, Boolean> inputOutputCheck = new LinkedHashMap<>();
            inputOutputCheck.put("variableTracingEnabled", env.isVariableTracingEnabled());
            inputOutputCheck.put("checkInputChange", env.isCheckInputChange());
            inputOutputCheck.put("checkOutputChange", env.isCheckOutputChange());
            booleanToggles.putAll(inputOutputCheck);

            // Put flat map for backward compatibility
            response.put("booleanToggles", booleanToggles);

            // Put grouped toggles for better UI organization
            Map<String, Map<String, Boolean>> groupedToggles = new LinkedHashMap<>();
            groupedToggles.put("coreDebugging", coreDebugging);
            groupedToggles.put("leakDetection", leakDetection);
            groupedToggles.put("componentTracking", componentTracking);
            groupedToggles.put("logging", logging);
            groupedToggles.put("funcTrace", funcTrace);
            groupedToggles.put("blasHelpers", blasHelpers);
            groupedToggles.put("memoryManagement", memoryManagement);
            groupedToggles.put("workspace", workspace);
            groupedToggles.put("inputOutputCheck", inputOutputCheck);
            response.put("groupedToggles", groupedToggles);

            // ========== INTEGER CONFIGURATIONS ==========

            Map<String, Object> integerConfigs = new LinkedHashMap<>();

            // Tracking configs
            Map<String, Object> trackingConfigs = new LinkedHashMap<>();
            trackingConfigs.put("stackDepth", env.getStackDepth());
            trackingConfigs.put("reportInterval", env.getReportInterval());
            trackingConfigs.put("maxDeletionHistory", env.getMaxDeletionHistory());
            trackingConfigs.put("numWorkspaceEventsToKeep", env.numWorkspaceEventsToKeep());
            integerConfigs.putAll(trackingConfigs);

            // Performance thresholds
            Map<String, Object> performanceConfigs = new LinkedHashMap<>();
            performanceConfigs.put("tadThreshold", env.tadThreshold());
            performanceConfigs.put("elementwiseThreshold", env.elementwiseThreshold());
            integerConfigs.putAll(performanceConfigs);

            // Thread configs
            Map<String, Object> threadConfigs = new LinkedHashMap<>();
            threadConfigs.put("maxThreads", env.maxThreads());
            threadConfigs.put("maxMasterThreads", env.maxMasterThreads());
            integerConfigs.putAll(threadConfigs);

            response.put("integerConfigs", integerConfigs);

            // Put grouped integer configs for better UI organization
            Map<String, Map<String, Object>> groupedIntConfigs = new LinkedHashMap<>();
            groupedIntConfigs.put("tracking", trackingConfigs);
            groupedIntConfigs.put("performance", performanceConfigs);
            groupedIntConfigs.put("threads", threadConfigs);
            response.put("groupedIntConfigs", groupedIntConfigs);

            // ========== READ-ONLY INFO ==========

            Map<String, Object> readOnlyInfo = new LinkedHashMap<>();
            readOnlyInfo.put("isCPU", env.isCPU());
            readOnlyInfo.put("debugAndVerbose", env.isDebugAndVerbose());
            readOnlyInfo.put("blasMajorVersion", env.blasMajorVersion());
            readOnlyInfo.put("blasMinorVersion", env.blasMinorVersion());
            readOnlyInfo.put("blasPatchVersion", env.blasPatchVersion());
            response.put("readOnlyInfo", readOnlyInfo);

            // ========== CUDA SETTINGS (conditional) ==========

            if (!env.isCPU()) {
                Map<String, Object> cudaSettings = new LinkedHashMap<>();

                // CUDA device info
                cudaSettings.put("cudaDeviceCount", env.cudaDeviceCount());
                cudaSettings.put("cudaCurrentDevice", env.cudaCurrentDevice());

                // CUDA memory settings
                Map<String, Object> cudaMemory = new LinkedHashMap<>();
                cudaMemory.put("memoryPinned", env.cudaMemoryPinned());
                cudaMemory.put("useManagedMemory", env.cudaUseManagedMemory());
                cudaMemory.put("useUnifiedMemory", env.cudaUseUnifiedMemory());
                cudaMemory.put("memoryPoolSize", env.cudaMemoryPoolSize());
                cudaMemory.put("cachingAllocatorLimit", env.cudaCachingAllocatorLimit());
                cudaMemory.put("prefetchSize", env.cudaPrefetchSize());
                cudaSettings.put("memory", cudaMemory);

                // CUDA execution settings
                Map<String, Object> cudaExecution = new LinkedHashMap<>();
                cudaExecution.put("forceP2P", env.cudaForceP2P());
                cudaExecution.put("allocatorEnabled", env.cudaAllocatorEnabled());
                cudaExecution.put("asyncExecution", env.cudaAsyncExecution());
                cudaExecution.put("graphOptimization", env.cudaGraphOptimization());
                cudaExecution.put("tensorCoreEnabled", env.cudaTensorCoreEnabled());
                cudaSettings.put("execution", cudaExecution);

                // CUDA thread/block settings
                Map<String, Object> cudaThreads = new LinkedHashMap<>();
                cudaThreads.put("maxBlocks", env.cudaMaxBlocks());
                cudaThreads.put("maxThreadsPerBlock", env.cudaMaxThreadsPerBlock());
                cudaThreads.put("streamLimit", env.cudaStreamLimit());
                cudaThreads.put("eventLimit", env.cudaEventLimit());
                cudaSettings.put("threads", cudaThreads);

                // CUDA advanced settings
                Map<String, Object> cudaAdvanced = new LinkedHashMap<>();
                cudaAdvanced.put("useDeviceHost", env.cudaUseDeviceHost());
                cudaAdvanced.put("blockingSync", env.cudaBlockingSync());
                cudaAdvanced.put("deviceSchedule", env.cudaDeviceSchedule());
                cudaAdvanced.put("stackSize", env.cudaStackSize());
                cudaAdvanced.put("mallocHeapSize", env.cudaMallocHeapSize());
                cudaAdvanced.put("printfFifoSize", env.cudaPrintfFifoSize());
                cudaAdvanced.put("devRuntimeSyncDepth", env.cudaDevRuntimeSyncDepth());
                cudaAdvanced.put("devRuntimePendingLaunchCount", env.cudaDevRuntimePendingLaunchCount());
                cudaAdvanced.put("maxL2FetchGranularity", env.cudaMaxL2FetchGranularity());
                cudaAdvanced.put("persistingL2CacheSize", env.cudaPersistingL2CacheSize());
                cudaSettings.put("advanced", cudaAdvanced);

                response.put("cudaSettings", cudaSettings);
            }

            // Backend info
            Map<String, Object> backendInfo = new LinkedHashMap<>();
            backendInfo.put("backend", Nd4j.getBackend().getClass().getSimpleName());
            backendInfo.put("isCPU", env.isCPU());
            backendInfo.put("blasVersion", env.blasMajorVersion() + "." + env.blasMinorVersion() + "." + env.blasPatchVersion());
            response.put("backendInfo", backendInfo);

            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting ND4J environment config", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Enable all ND4J environment toggles (sets all boolean toggles to true).
     * Useful for debugging and comprehensive tracking.
     * WARNING: This enables high-overhead tracking features.
     */
    @PostMapping("/nd4j/environment/enable-all")
    public ResponseEntity<Map<String, Object>> enableAllNd4jToggles() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            logger.warn("Enabling ALL ND4J environment toggles - this may cause significant overhead");

            org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();

            // Core Debugging
            env.setVerbose(true);
            env.setDebug(true);
            env.setProfiling(true);

            // Leak Detection
            env.setLeaksDetector(true);
            env.setLifecycleTracking(true);
            env.setTrackViews(true);
            env.setTrackDeletions(true);
            env.setTrackOperations(true);

            // Component Tracking
            env.setNDArrayTracking(true);
            env.setDataBufferTracking(true);
            env.setTADCacheTracking(true);
            env.setShapeCacheTracking(true);
            env.setOpContextTracking(true);

            // Logging
            env.setLogNDArrayEvents(true);
            env.setLogNativeNDArrayCreation(true);
            env.setTruncateLogStrings(true);

            // Function Trace
            env.setFuncTraceForAllocate(true);
            env.setFuncTraceForDeallocate(true);
            env.setFuncTracePrintJavaOnly(true);

            // Workspace
            env.setTrackWorkspaceOpenClose(true);
            env.setSnapshotFiles(true);

            // Input/Output Checking
            env.setVariableTracingEnabled(true);
            env.setCheckInputChange(true);
            env.setCheckOutputChange(true);

            // Note: NOT enabling BLAS/memory toggles since disabling those could cause issues

            // Persist all toggles to disk
            persistEnableAllToggles();

            response.put("status", "success");
            response.put("message", "All ND4J environment toggles enabled");
            response.put("warning", "High overhead tracking is now enabled. Performance will be significantly impacted.");
            response.put("persisted", nd4jEnvironmentConfigService != null);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error enabling all ND4J environment toggles", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Persist enable-all toggles to the config file.
     */
    private void persistEnableAllToggles() {
        if (nd4jEnvironmentConfigService == null) {
            logger.warn("Nd4jEnvironmentConfigService not available - enable-all will not persist");
            return;
        }

        try {
            Nd4jEnvironmentConfig config = Nd4jEnvironmentConfig.builder()
                    .verbose(true)
                    .debug(true)
                    .profiling(true)
                    .leaksDetector(true)
                    .lifecycleTracking(true)
                    .trackViews(true)
                    .trackDeletions(true)
                    .trackOperations(true)
                    .ndArrayTracking(true)
                    .dataBufferTracking(true)
                    .tadCacheTracking(true)
                    .shapeCacheTracking(true)
                    .opContextTracking(true)
                    .logNDArrayEvents(true)
                    .logNativeNDArrayCreation(true)
                    .funcTracePrintAllocate(true)
                    .funcTracePrintDeallocate(true)
                    .funcTracePrintJavaOnly(true)
                    .trackWorkspaceOpenClose(true)
                    .snapshotFiles(true)
                    .variableTracingEnabled(true)
                    .checkInputChange(true)
                    .checkOutputChange(true)
                    .build();

            nd4jEnvironmentConfigService.updateConfiguration(config);
            logger.info("Persisted enable-all toggles to config file");
        } catch (Exception e) {
            logger.error("Failed to persist enable-all toggles: {}", e.getMessage());
        }
    }

    /**
     * Disable all ND4J environment toggles (sets all boolean toggles to false).
     * Useful for production to minimize overhead.
     */
    @PostMapping("/nd4j/environment/disable-all")
    public ResponseEntity<Map<String, Object>> disableAllNd4jToggles() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            logger.info("Disabling ALL ND4J environment toggles");

            org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();

            // Core Debugging
            env.setVerbose(false);
            env.setDebug(false);
            env.setProfiling(false);

            // Leak Detection
            env.setLeaksDetector(false);
            env.setLifecycleTracking(false);
            env.setTrackViews(false);
            env.setTrackDeletions(false);
            env.setTrackOperations(false);

            // Component Tracking
            env.setNDArrayTracking(false);
            env.setDataBufferTracking(false);
            env.setTADCacheTracking(false);
            env.setShapeCacheTracking(false);
            env.setOpContextTracking(false);

            // Logging
            env.setLogNDArrayEvents(false);
            env.setLogNativeNDArrayCreation(false);
            env.setTruncateLogStrings(false);

            // Function Trace
            env.setFuncTraceForAllocate(false);
            env.setFuncTraceForDeallocate(false);
            env.setFuncTracePrintJavaOnly(false);

            // Workspace
            env.setTrackWorkspaceOpenClose(false);
            env.setSnapshotFiles(false);

            // Input/Output Checking
            env.setVariableTracingEnabled(false);
            env.setCheckInputChange(false);
            env.setCheckOutputChange(false);

            // Note: NOT touching BLAS/memory toggles since those are typically production defaults

            // Persist all toggles to disk
            persistDisableAllToggles();

            response.put("status", "success");
            response.put("message", "All ND4J environment toggles disabled");
            response.put("persisted", nd4jEnvironmentConfigService != null);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error disabling all ND4J environment toggles", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Persist disable-all toggles to the config file.
     */
    private void persistDisableAllToggles() {
        if (nd4jEnvironmentConfigService == null) {
            logger.warn("Nd4jEnvironmentConfigService not available - disable-all will not persist");
            return;
        }

        try {
            Nd4jEnvironmentConfig config = Nd4jEnvironmentConfig.builder()
                    .verbose(false)
                    .debug(false)
                    .profiling(false)
                    .leaksDetector(false)
                    .lifecycleTracking(false)
                    .trackViews(false)
                    .trackDeletions(false)
                    .trackOperations(false)
                    .ndArrayTracking(false)
                    .dataBufferTracking(false)
                    .tadCacheTracking(false)
                    .shapeCacheTracking(false)
                    .opContextTracking(false)
                    .logNDArrayEvents(false)
                    .logNativeNDArrayCreation(false)
                    .funcTracePrintAllocate(false)
                    .funcTracePrintDeallocate(false)
                    .funcTracePrintJavaOnly(false)
                    .trackWorkspaceOpenClose(false)
                    .snapshotFiles(false)
                    .variableTracingEnabled(false)
                    .checkInputChange(false)
                    .checkOutputChange(false)
                    .build();

            nd4jEnvironmentConfigService.updateConfiguration(config);
            logger.info("Persisted disable-all toggles to config file");
        } catch (Exception e) {
            logger.error("Failed to persist disable-all toggles: {}", e.getMessage());
        }
    }

    /**
     * Set a specific ND4J environment boolean toggle.
     * Supports all boolean settings from the Environment interface.
     * @param toggle The toggle name
     * @param enabled Whether to enable or disable the toggle
     */
    @PostMapping("/nd4j/environment/toggle/{toggle}")
    public ResponseEntity<Map<String, Object>> setNd4jToggle(
            @PathVariable(name = "toggle") String toggle,
            @RequestParam(name = "enabled") boolean enabled) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();

            switch (toggle.toLowerCase()) {
                // Core Debugging
                case "verbose":
                    env.setVerbose(enabled);
                    break;
                case "debug":
                    env.setDebug(enabled);
                    break;
                case "profiling":
                    env.setProfiling(enabled);
                    break;

                // Leak Detection
                case "detectingleaks":
                    env.setLeaksDetector(enabled);
                    break;
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
                case "trackoperations":
                    env.setTrackOperations(enabled);
                    break;

                // Component-specific Tracking
                case "ndarraytracking":
                    env.setNDArrayTracking(enabled);
                    break;
                case "databuffertracking":
                    env.setDataBufferTracking(enabled);
                    break;
                case "tadcachetracking":
                    env.setTADCacheTracking(enabled);
                    break;
                case "shapecachetracking":
                    env.setShapeCacheTracking(enabled);
                    break;
                case "opcontexttracking":
                    env.setOpContextTracking(enabled);
                    break;

                // Logging
                case "logndarrayevents":
                    env.setLogNDArrayEvents(enabled);
                    break;
                case "lognativendrraycreation":
                    env.setLogNativeNDArrayCreation(enabled);
                    break;
                case "truncatelogstrings":
                    env.setTruncateLogStrings(enabled);
                    break;

                // Function Trace
                case "functraceallocate":
                    env.setFuncTraceForAllocate(enabled);
                    if (enabled) {
                        logger.warn("funcTraceAllocate enabled - this has VERY HIGH overhead");
                    }
                    break;
                case "functracedeallocate":
                    env.setFuncTraceForDeallocate(enabled);
                    if (enabled) {
                        logger.warn("funcTraceDeallocate enabled - this has VERY HIGH overhead");
                    }
                    break;
                case "functraceprintjavaonly":
                    env.setFuncTracePrintJavaOnly(enabled);
                    break;

                // BLAS and Helpers
                case "enableblas":
                    env.setEnableBlas(enabled);
                    if (!enabled) {
                        logger.warn("BLAS disabled - performance will be significantly impacted");
                    }
                    break;
                case "helpersallowed":
                    env.allowHelpers(enabled);
                    break;

                // Memory Management
                case "deleteprimary":
                    env.setDeletePrimary(enabled);
                    if (!enabled) {
                        logger.warn("deletePrimary disabled - may cause memory leaks (debugging only)");
                    }
                    break;
                case "deletespecial":
                    env.setDeleteSpecial(enabled);
                    if (!enabled) {
                        logger.warn("deleteSpecial disabled - may cause memory leaks (debugging only)");
                    }
                    break;
                case "deleteshapeinfo":
                    env.setDeleteShapeInfo(enabled);
                    break;

                // Workspace
                case "trackworkspaceopenclose":
                    env.setTrackWorkspaceOpenClose(enabled);
                    break;
                case "snapshotfiles":
                    env.setSnapshotFiles(enabled);
                    break;

                // Input/Output Checking
                case "variabletracingenabled":
                    env.setVariableTracingEnabled(enabled);
                    break;
                case "checkinputchange":
                    env.setCheckInputChange(enabled);
                    if (enabled) {
                        logger.warn("checkInputChange enabled - this has HIGH overhead");
                    }
                    break;
                case "checkoutputchange":
                    env.setCheckOutputChange(enabled);
                    if (enabled) {
                        logger.warn("checkOutputChange enabled - this has HIGH overhead");
                    }
                    break;

                default:
                    response.put("status", "error");
                    response.put("message", "Unknown toggle: " + toggle);
                    response.put("validToggles", getValidToggleNames());
                    return ResponseEntity.badRequest().body(response);
            }

            logger.info("ND4J toggle '{}' set to {}", toggle, enabled);

            // Persist the change to disk via the config service
            persistToggleChange(toggle, enabled);

            response.put("status", "success");
            response.put("toggle", toggle);
            response.put("enabled", enabled);
            response.put("persisted", nd4jEnvironmentConfigService != null);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error setting ND4J toggle: " + toggle, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Persist a toggle change to the ND4J environment config file.
     * Maps toggle names to Nd4jEnvironmentConfig fields.
     */
    private void persistToggleChange(String toggle, boolean enabled) {
        if (nd4jEnvironmentConfigService == null) {
            logger.warn("Nd4jEnvironmentConfigService not available - toggle change will not persist across restarts");
            return;
        }

        try {
            Nd4jEnvironmentConfig.Builder builder = Nd4jEnvironmentConfig.builder();

            switch (toggle.toLowerCase()) {
                case "verbose" -> builder.verbose(enabled);
                case "debug" -> builder.debug(enabled);
                case "profiling" -> builder.profiling(enabled);
                case "detectingleaks" -> builder.leaksDetector(enabled);
                case "lifecycletracking" -> builder.lifecycleTracking(enabled);
                case "trackviews" -> builder.trackViews(enabled);
                case "trackdeletions" -> builder.trackDeletions(enabled);
                case "trackoperations" -> builder.trackOperations(enabled);
                case "ndarraytracking" -> builder.ndArrayTracking(enabled);
                case "databuffertracking" -> builder.dataBufferTracking(enabled);
                case "tadcachetracking" -> builder.tadCacheTracking(enabled);
                case "shapecachetracking" -> builder.shapeCacheTracking(enabled);
                case "opcontexttracking" -> builder.opContextTracking(enabled);
                case "logndarrayevents" -> builder.logNDArrayEvents(enabled);
                case "lognativendrraycreation" -> builder.logNativeNDArrayCreation(enabled);
                case "functraceallocate" -> builder.funcTracePrintAllocate(enabled);
                case "functracedeallocate" -> builder.funcTracePrintDeallocate(enabled);
                case "functraceprintjavaonly" -> builder.funcTracePrintJavaOnly(enabled);
                case "enableblas" -> builder.enableBlas(enabled);
                case "helpersallowed" -> builder.helpersAllowed(enabled);
                case "deleteprimary" -> builder.deletePrimary(enabled);
                case "deletespecial" -> builder.deleteSpecial(enabled);
                case "deleteshapeinfo" -> builder.deleteShapeInfo(enabled);
                case "trackworkspaceopenclose" -> builder.trackWorkspaceOpenClose(enabled);
                case "snapshotfiles" -> builder.snapshotFiles(enabled);
                case "variabletracingenabled" -> builder.variableTracingEnabled(enabled);
                case "checkinputchange" -> builder.checkInputChange(enabled);
                case "checkoutputchange" -> builder.checkOutputChange(enabled);
                default -> {
                    logger.debug("Toggle '{}' not mapped for persistence", toggle);
                    return;
                }
            }

            nd4jEnvironmentConfigService.updateConfiguration(builder.build());
            logger.info("Persisted ND4J toggle '{}' = {} to config file", toggle, enabled);

        } catch (Exception e) {
            logger.error("Failed to persist toggle change '{}': {}", toggle, e.getMessage());
        }
    }

    /**
     * Get list of all valid toggle names for error messages.
     */
    private List<String> getValidToggleNames() {
        return Arrays.asList(
            // Core Debugging
            "verbose", "debug", "profiling",
            // Leak Detection
            "detectingLeaks", "lifecycleTracking", "trackViews", "trackDeletions", "trackOperations",
            // Component Tracking
            "ndArrayTracking", "dataBufferTracking", "tadCacheTracking", "shapeCacheTracking", "opContextTracking",
            // Logging
            "logNDArrayEvents", "logNativeNDArrayCreation", "truncateLogStrings",
            // Function Trace
            "funcTraceAllocate", "funcTraceDeallocate", "funcTracePrintJavaOnly",
            // BLAS and Helpers
            "enableBlas", "helpersAllowed",
            // Memory Management
            "deletePrimary", "deleteSpecial", "deleteShapeInfo",
            // Workspace
            "trackWorkspaceOpenClose", "snapshotFiles",
            // Input/Output Checking
            "variableTracingEnabled", "checkInputChange", "checkOutputChange"
        );
    }

    /**
     * Set a specific ND4J environment integer configuration.
     * Supports all integer settings from the Environment interface.
     * @param config The config name
     * @param value The value to set
     */
    @PostMapping("/nd4j/environment/config/{config}")
    public ResponseEntity<Map<String, Object>> setNd4jConfig(
            @PathVariable(name = "config") String config,
            @RequestParam(name = "value") long value) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();

            switch (config.toLowerCase()) {
                // Tracking configs
                case "stackdepth":
                    if (value < 1 || value > 256) {
                        response.put("status", "error");
                        response.put("message", "stackDepth must be between 1 and 256");
                        return ResponseEntity.badRequest().body(response);
                    }
                    env.setStackDepth((int) value);
                    break;
                case "reportinterval":
                    if (value < 1 || value > 3600) {
                        response.put("status", "error");
                        response.put("message", "reportInterval must be between 1 and 3600 seconds");
                        return ResponseEntity.badRequest().body(response);
                    }
                    env.setReportInterval((int) value);
                    break;
                case "maxdeletionhistory":
                    if (value < 1 || value > 1000000) {
                        response.put("status", "error");
                        response.put("message", "maxDeletionHistory must be between 1 and 1000000");
                        return ResponseEntity.badRequest().body(response);
                    }
                    env.setMaxDeletionHistory(value);
                    break;

                // Performance thresholds
                case "tadthreshold":
                    if (value < 1) {
                        response.put("status", "error");
                        response.put("message", "tadThreshold must be >= 1");
                        return ResponseEntity.badRequest().body(response);
                    }
                    env.setTadThreshold((int) value);
                    break;
                case "elementwisethreshold":
                    if (value < 1) {
                        response.put("status", "error");
                        response.put("message", "elementwiseThreshold must be >= 1");
                        return ResponseEntity.badRequest().body(response);
                    }
                    env.setElementwiseThreshold((int) value);
                    break;

                // Thread configs
                case "maxthreads":
                    if (value < 1 || value > 1024) {
                        response.put("status", "error");
                        response.put("message", "maxThreads must be between 1 and 1024");
                        return ResponseEntity.badRequest().body(response);
                    }
                    env.setMaxThreads((int) value);
                    break;
                case "maxmasterthreads":
                    if (value < 1 || value > 1024) {
                        response.put("status", "error");
                        response.put("message", "maxMasterThreads must be between 1 and 1024");
                        return ResponseEntity.badRequest().body(response);
                    }
                    env.setMaxMasterThreads((int) value);
                    break;

                // Memory limits (long values)
                case "maxprimarymemory":
                    env.setMaxPrimaryMemory(value);
                    break;
                case "maxspecialmemory":
                    env.setMaxSpecialMemory(value);
                    break;
                case "maxdevicememory":
                    env.setMaxDeviceMemory(value);
                    break;

                default:
                    response.put("status", "error");
                    response.put("message", "Unknown config: " + config);
                    response.put("validConfigs", getValidConfigNames());
                    return ResponseEntity.badRequest().body(response);
            }

            logger.info("ND4J config '{}' set to {}", config, value);

            // Persist the change to disk
            persistConfigChange(config, value);

            response.put("status", "success");
            response.put("config", config);
            response.put("value", value);
            response.put("persisted", nd4jEnvironmentConfigService != null);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error setting ND4J config: " + config, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Persist a config (int/long) change to the ND4J environment config file.
     */
    private void persistConfigChange(String config, long value) {
        if (nd4jEnvironmentConfigService == null) {
            logger.warn("Nd4jEnvironmentConfigService not available - config change will not persist");
            return;
        }

        try {
            Nd4jEnvironmentConfig.Builder builder = Nd4jEnvironmentConfig.builder();

            switch (config.toLowerCase()) {
                case "stackdepth" -> builder.stackDepth((int) value);
                case "reportinterval" -> builder.reportInterval((int) value);
                case "maxdeletionhistory" -> builder.maxDeletionHistory((int) value);
                case "tadthreshold" -> builder.tadThreshold((int) value);
                case "elementwisethreshold" -> builder.elementwiseThreshold((int) value);
                case "maxthreads" -> builder.maxThreads((int) value);
                case "maxmasterthreads" -> builder.maxMasterThreads((int) value);
                case "maxprimarymemory" -> builder.maxPrimaryMemory(value);
                case "maxspecialmemory" -> builder.maxSpecialMemory(value);
                case "maxdevicememory" -> builder.maxDeviceMemory(value);
                default -> {
                    logger.debug("Config '{}' not mapped for persistence", config);
                    return;
                }
            }

            nd4jEnvironmentConfigService.updateConfiguration(builder.build());
            logger.info("Persisted ND4J config '{}' = {} to config file", config, value);

        } catch (Exception e) {
            logger.error("Failed to persist config change '{}': {}", config, e.getMessage());
        }
    }

    /**
     * Persist bulk update changes to the config file.
     */
    private void persistBulkUpdate(Map<String, Object> applied) {
        if (nd4jEnvironmentConfigService == null) {
            logger.warn("Nd4jEnvironmentConfigService not available - bulk update will not persist");
            return;
        }

        if (applied == null || applied.isEmpty()) {
            return;
        }

        try {
            Nd4jEnvironmentConfig.Builder builder = Nd4jEnvironmentConfig.builder();

            for (Map.Entry<String, Object> entry : applied.entrySet()) {
                String key = entry.getKey().toLowerCase();
                Object value = entry.getValue();

                switch (key) {
                    // Boolean toggles
                    case "verbose" -> builder.verbose((Boolean) value);
                    case "debug" -> builder.debug((Boolean) value);
                    case "profiling" -> builder.profiling((Boolean) value);
                    case "leaksdetector", "detectingleaks" -> builder.leaksDetector((Boolean) value);
                    case "lifecycletracking" -> builder.lifecycleTracking((Boolean) value);
                    case "trackviews" -> builder.trackViews((Boolean) value);
                    case "trackdeletions" -> builder.trackDeletions((Boolean) value);
                    case "trackoperations" -> builder.trackOperations((Boolean) value);
                    case "ndarraytracking" -> builder.ndArrayTracking((Boolean) value);
                    case "databuffertracking" -> builder.dataBufferTracking((Boolean) value);
                    case "tadcachetracking" -> builder.tadCacheTracking((Boolean) value);
                    case "shapecachetracking" -> builder.shapeCacheTracking((Boolean) value);
                    case "opcontexttracking" -> builder.opContextTracking((Boolean) value);
                    case "logndarrayevents" -> builder.logNDArrayEvents((Boolean) value);
                    case "lognativendrraycreation" -> builder.logNativeNDArrayCreation((Boolean) value);
                    case "functraceallocate" -> builder.funcTracePrintAllocate((Boolean) value);
                    case "functracedeallocate" -> builder.funcTracePrintDeallocate((Boolean) value);
                    case "functraceprintjavaonly" -> builder.funcTracePrintJavaOnly((Boolean) value);
                    case "enableblas" -> builder.enableBlas((Boolean) value);
                    case "helpersallowed" -> builder.helpersAllowed((Boolean) value);
                    case "deleteprimary" -> builder.deletePrimary((Boolean) value);
                    case "deletespecial" -> builder.deleteSpecial((Boolean) value);
                    case "deleteshapeinfo" -> builder.deleteShapeInfo((Boolean) value);
                    case "trackworkspaceopenclose" -> builder.trackWorkspaceOpenClose((Boolean) value);
                    case "snapshotfiles" -> builder.snapshotFiles((Boolean) value);
                    case "variabletracingenabled" -> builder.variableTracingEnabled((Boolean) value);
                    case "checkinputchange" -> builder.checkInputChange((Boolean) value);
                    case "checkoutputchange" -> builder.checkOutputChange((Boolean) value);
                    // Integer/Long configs
                    case "stackdepth" -> builder.stackDepth(((Number) value).intValue());
                    case "reportinterval" -> builder.reportInterval(((Number) value).intValue());
                    case "maxdeletionhistory" -> builder.maxDeletionHistory(((Number) value).intValue());
                    case "tadthreshold" -> builder.tadThreshold(((Number) value).intValue());
                    case "elementwisethreshold" -> builder.elementwiseThreshold(((Number) value).intValue());
                    case "maxthreads" -> builder.maxThreads(((Number) value).intValue());
                    case "maxmasterthreads" -> builder.maxMasterThreads(((Number) value).intValue());
                    case "maxprimarymemory" -> builder.maxPrimaryMemory(((Number) value).longValue());
                    case "maxspecialmemory" -> builder.maxSpecialMemory(((Number) value).longValue());
                    case "maxdevicememory" -> builder.maxDeviceMemory(((Number) value).longValue());
                }
            }

            nd4jEnvironmentConfigService.updateConfiguration(builder.build());
            logger.info("Persisted {} bulk update settings to config file", applied.size());

        } catch (Exception e) {
            logger.error("Failed to persist bulk update: {}", e.getMessage());
        }
    }

    /**
     * Get list of all valid config names for error messages.
     */
    private List<String> getValidConfigNames() {
        return Arrays.asList(
            // Tracking
            "stackDepth", "reportInterval", "maxDeletionHistory",
            // Performance
            "tadThreshold", "elementwiseThreshold",
            // Threads
            "maxThreads", "maxMasterThreads",
            // Memory limits
            "maxPrimaryMemory", "maxSpecialMemory", "maxDeviceMemory"
        );
    }

    /**
     * Bulk update ND4J environment configuration.
     * Accepts a JSON body with toggle and config values to update.
     * Supports ALL ND4J Environment settings dynamically.
     * Example: {"lifecycleTracking": true, "stackDepth": 32, "verbose": true, "debug": false}
     */
    @PostMapping("/nd4j/environment/bulk-update")
    public ResponseEntity<Map<String, Object>> bulkUpdateNd4jEnvironment(
            @RequestBody Map<String, Object> updates) {

        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> applied = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        try {
            org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();

            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                try {
                    switch (key.toLowerCase()) {
                        // ========== Boolean toggles ==========
                        // Core lifecycle tracking
                        case "lifecycletracking":
                            env.setLifecycleTracking((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set lifecycleTracking = {}", value);
                            break;
                        case "trackviews":
                            env.setTrackViews((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set trackViews = {}", value);
                            break;
                        case "trackdeletions":
                            env.setTrackDeletions((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set trackDeletions = {}", value);
                            break;
                        case "snapshotfiles":
                            env.setSnapshotFiles((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set snapshotFiles = {}", value);
                            break;
                        case "trackoperations":
                            env.setTrackOperations((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set trackOperations = {}", value);
                            break;

                        // Debug and logging
                        case "verbose":
                            env.setVerbose((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set verbose = {}", value);
                            break;
                        case "debug":
                            env.setDebug((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set debug = {}", value);
                            break;
                        case "profiling":
                            env.setProfiling((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set profiling = {}", value);
                            break;
                        case "leaksdetector":
                            env.setLeaksDetector((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set leaksDetector = {}", value);
                            break;
                        case "logndarrayevents":
                            env.setLogNDArrayEvents((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set logNDArrayEvents = {}", value);
                            break;
                        case "lognativenrdarraycreation":
                        case "lognativendrraycreation":
                            env.setLogNativeNDArrayCreation((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set logNativeNDArrayCreation = {}", value);
                            break;
                        case "truncatelogstrings":
                            env.setTruncateLogStrings((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set truncateLogStrings = {}", value);
                            break;

                        // BLAS and helpers
                        case "enableblas":
                            env.setEnableBlas((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set enableBlas = {}", value);
                            break;
                        case "helpersallowed":
                            env.allowHelpers((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set helpersAllowed = {}", value);
                            break;

                        // Func trace settings
                        case "functraceallocate":
                            env.setFuncTraceForAllocate((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set funcTraceAllocate = {}", value);
                            break;
                        case "functracedeallocate":
                            env.setFuncTraceForDeallocate((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set funcTraceDeallocate = {}", value);
                            break;
                        case "functraceprintjavaonly":
                            env.setFuncTracePrintJavaOnly((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set funcTracePrintJavaOnly = {}", value);
                            break;

                        // Variable tracing
                        case "variabletracingenabled":
                            env.setVariableTracingEnabled((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set variableTracingEnabled = {}", value);
                            break;
                        case "checkinputchange":
                            env.setCheckInputChange((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set checkInputChange = {}", value);
                            break;
                        case "checkoutputchange":
                            env.setCheckOutputChange((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set checkOutputChange = {}", value);
                            break;

                        // Workspace
                        case "trackworkspaceopenclose":
                            env.setTrackWorkspaceOpenClose((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set trackWorkspaceOpenClose = {}", value);
                            break;

                        // Delete tracking
                        case "deleteprimary":
                            env.setDeletePrimary((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set deletePrimary = {}", value);
                            break;
                        case "deletespecial":
                            env.setDeleteSpecial((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set deleteSpecial = {}", value);
                            break;
                        case "deleteshapeinfo":
                            env.setDeleteShapeInfo((Boolean) value);
                            applied.put(key, value);
                            logger.info("Set deleteShapeInfo = {}", value);
                            break;

                        // ========== Integer configs ==========
                        case "stackdepth":
                            int stackDepth = ((Number) value).intValue();
                            if (stackDepth >= 1 && stackDepth <= 256) {
                                env.setStackDepth(stackDepth);
                                applied.put(key, stackDepth);
                                logger.info("Set stackDepth = {}", stackDepth);
                            } else {
                                errors.add(key + ": must be between 1 and 256");
                            }
                            break;
                        case "reportinterval":
                            int reportInterval = ((Number) value).intValue();
                            if (reportInterval >= 1 && reportInterval <= 3600) {
                                env.setReportInterval(reportInterval);
                                applied.put(key, reportInterval);
                                logger.info("Set reportInterval = {}", reportInterval);
                            } else {
                                errors.add(key + ": must be between 1 and 3600");
                            }
                            break;
                        case "maxdeletionhistory":
                            int maxHistory = ((Number) value).intValue();
                            if (maxHistory >= 1 && maxHistory <= 100000) {
                                env.setMaxDeletionHistory(maxHistory);
                                applied.put(key, maxHistory);
                                logger.info("Set maxDeletionHistory = {}", maxHistory);
                            } else {
                                errors.add(key + ": must be between 1 and 100000");
                            }
                            break;
                        case "tadthreshold":
                            int tadThreshold = ((Number) value).intValue();
                            env.setTadThreshold(tadThreshold);
                            applied.put(key, tadThreshold);
                            logger.info("Set tadThreshold = {}", tadThreshold);
                            break;
                        case "elementwisethreshold":
                            int elementwiseThreshold = ((Number) value).intValue();
                            env.setElementwiseThreshold(elementwiseThreshold);
                            applied.put(key, elementwiseThreshold);
                            logger.info("Set elementwiseThreshold = {}", elementwiseThreshold);
                            break;
                        case "maxthreads":
                            int maxThreads = ((Number) value).intValue();
                            env.setMaxThreads(maxThreads);
                            applied.put(key, maxThreads);
                            logger.info("Set maxThreads = {}", maxThreads);
                            break;
                        case "maxmasterthreads":
                            int maxMasterThreads = ((Number) value).intValue();
                            env.setMaxMasterThreads(maxMasterThreads);
                            applied.put(key, maxMasterThreads);
                            logger.info("Set maxMasterThreads = {}", maxMasterThreads);
                            break;

                        default:
                            // Log unknown keys but don't fail
                            logger.warn("Unknown ND4J environment key: {} (value: {})", key, value);
                            errors.add("Unknown key: " + key);
                    }
                } catch (ClassCastException e) {
                    errors.add(key + ": invalid value type");
                    logger.warn("Invalid type for key {}: expected {}", key, e.getMessage());
                } catch (Exception e) {
                    errors.add(key + ": " + e.getMessage());
                    logger.warn("Error setting {}: {}", key, e.getMessage());
                }
            }

            logger.info("========================================");
            logger.info("ND4J ENVIRONMENT BULK UPDATE COMPLETE");
            logger.info("Applied {} settings, {} errors", applied.size(), errors.size());
            logger.info("========================================");

            // Persist all applied changes to disk
            persistBulkUpdate(applied);

            response.put("status", errors.isEmpty() ? "success" : "partial");
            response.put("applied", applied);
            response.put("appliedCount", applied.size());
            response.put("persisted", nd4jEnvironmentConfigService != null);
            if (!errors.isEmpty()) {
                response.put("errors", errors);
                response.put("errorCount", errors.size());
            }

            // Include current state
            response.put("currentState", getCurrentEnvironmentState());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error bulk updating ND4J environment", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            response.put("applied", applied);
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get the current ND4J environment state as a map.
     */
    private Map<String, Object> getCurrentEnvironmentState() {
        org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();
        Map<String, Object> state = new LinkedHashMap<>();

        // Boolean toggles
        state.put("lifecycleTracking", env.isLifecycleTracking());
        state.put("trackViews", env.isTrackViews());
        state.put("trackDeletions", env.isTrackDeletions());
        state.put("snapshotFiles", env.isSnapshotFiles());
        state.put("trackOperations", env.isTrackOperations());
        state.put("verbose", env.isVerbose());
        state.put("debug", env.isDebug());
        state.put("profiling", env.isProfiling());
        state.put("leaksDetector", env.isDetectingLeaks());
        state.put("logNDArrayEvents", env.isLogNDArrayEvents());
        state.put("logNativeNDArrayCreation", env.isLogNativeNDArrayCreation());
        state.put("truncateLogStrings", env.isTruncateNDArrayLogStrings());
        state.put("enableBlas", env.isEnableBlas());
        state.put("helpersAllowed", env.helpersAllowed());
        state.put("funcTraceAllocate", env.isFuncTracePrintAllocate());
        state.put("funcTraceDeallocate", env.isFuncTracePrintDeallocate());
        state.put("funcTracePrintJavaOnly", env.isFuncTracePrintJavaOnly());
        state.put("variableTracingEnabled", env.isVariableTracingEnabled());
        state.put("checkInputChange", env.isCheckInputChange());
        state.put("checkOutputChange", env.isCheckOutputChange());
        state.put("trackWorkspaceOpenClose", env.isTrackWorkspaceOpenClose());
        state.put("deletePrimary", env.isDeletePrimary());
        state.put("deleteSpecial", env.isDeleteSpecial());
        state.put("deleteShapeInfo", env.isDeleteShapeInfo());

        // Integer configs
        state.put("stackDepth", env.getStackDepth());
        state.put("reportInterval", env.getReportInterval());
        state.put("maxDeletionHistory", env.getMaxDeletionHistory());
        state.put("tadThreshold", env.tadThreshold());
        state.put("elementwiseThreshold", env.elementwiseThreshold());
        state.put("maxThreads", env.maxThreads());
        state.put("maxMasterThreads", env.maxMasterThreads());

        return state;
    }

    // ========================================================================
    // COMPREHENSIVE PROFILING METRICS ENDPOINT
    // ========================================================================

    /**
     * Get comprehensive ND4J profiling metrics including memory, threads, BLAS info,
     * and all environment configuration. This is the main endpoint for UI profiling display.
     */
    @GetMapping("/nd4j/profiling-metrics")
    public ResponseEntity<Map<String, Object>> getProfilingMetrics() {
        Map<String, Object> response = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();

            // ========== BACKEND INFO ==========
            Map<String, Object> backendInfo = new LinkedHashMap<>();
            backendInfo.put("backend", Nd4j.getBackend().getClass().getSimpleName());
            backendInfo.put("isCPU", env.isCPU());
            backendInfo.put("blasMajorVersion", env.blasMajorVersion());
            backendInfo.put("blasMinorVersion", env.blasMinorVersion());
            backendInfo.put("blasPatchVersion", env.blasPatchVersion());
            backendInfo.put("blasVersion", env.blasMajorVersion() + "." + env.blasMinorVersion() + "." + env.blasPatchVersion());
            response.put("backend", backendInfo);

            // ========== THREAD CONFIGURATION ==========
            Map<String, Object> threadConfig = new LinkedHashMap<>();
            threadConfig.put("maxThreads", env.maxThreads());
            threadConfig.put("maxMasterThreads", env.maxMasterThreads());
            threadConfig.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            threadConfig.put("activeThreadCount", Thread.activeCount());
            response.put("threads", threadConfig);

            // ========== MEMORY METRICS ==========
            Map<String, Object> memoryMetrics = new LinkedHashMap<>();
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            memoryMetrics.put("jvmMaxMemoryMB", maxMemory / (1024 * 1024));
            memoryMetrics.put("jvmTotalMemoryMB", totalMemory / (1024 * 1024));
            memoryMetrics.put("jvmUsedMemoryMB", usedMemory / (1024 * 1024));
            memoryMetrics.put("jvmFreeMemoryMB", freeMemory / (1024 * 1024));
            memoryMetrics.put("jvmUsagePercent", String.format("%.1f", (usedMemory * 100.0) / maxMemory));

            // Device memory counters (may return 0 on CPU)
            try {
                memoryMetrics.put("device0AllocatedBytes", env.getDeviceCounter(0));
                memoryMetrics.put("device0LimitBytes", env.getDeviceLimit(0));
                memoryMetrics.put("group0LimitBytes", env.getGroupLimit(0));
            } catch (Exception e) {
                memoryMetrics.put("deviceMemoryError", e.getMessage());
            }
            response.put("memory", memoryMetrics);

            // ========== PROFILING MODE STATUS ==========
            Map<String, Object> profilingStatus = new LinkedHashMap<>();
            profilingStatus.put("verbose", env.isVerbose());
            profilingStatus.put("debug", env.isDebug());
            profilingStatus.put("profiling", env.isProfiling());
            profilingStatus.put("debugAndVerbose", env.isDebugAndVerbose());
            profilingStatus.put("detectingLeaks", env.isDetectingLeaks());
            profilingStatus.put("lifecycleTracking", env.isLifecycleTracking());
            profilingStatus.put("trackOperations", env.isTrackOperations());
            profilingStatus.put("trackViews", env.isTrackViews());
            profilingStatus.put("trackDeletions", env.isTrackDeletions());
            profilingStatus.put("logNDArrayEvents", env.isLogNDArrayEvents());
            profilingStatus.put("logNativeNDArrayCreation", env.isLogNativeNDArrayCreation());
            profilingStatus.put("funcTraceAllocate", env.isFuncTracePrintAllocate());
            profilingStatus.put("funcTraceDeallocate", env.isFuncTracePrintDeallocate());
            response.put("profilingStatus", profilingStatus);

            // ========== PERFORMANCE THRESHOLDS ==========
            Map<String, Object> thresholds = new LinkedHashMap<>();
            thresholds.put("tadThreshold", env.tadThreshold());
            thresholds.put("elementwiseThreshold", env.elementwiseThreshold());
            response.put("thresholds", thresholds);

            // ========== BLAS/HELPER STATUS ==========
            Map<String, Object> blasStatus = new LinkedHashMap<>();
            blasStatus.put("blasEnabled", env.isEnableBlas());
            blasStatus.put("helpersAllowed", env.helpersAllowed());
            response.put("blas", blasStatus);

            // ========== TRACKING CONFIGURATION ==========
            Map<String, Object> trackingConfig = new LinkedHashMap<>();
            trackingConfig.put("stackDepth", env.getStackDepth());
            trackingConfig.put("reportInterval", env.getReportInterval());
            trackingConfig.put("maxDeletionHistory", env.getMaxDeletionHistory());
            trackingConfig.put("snapshotFiles", env.isSnapshotFiles());
            trackingConfig.put("trackWorkspaceOpenClose", env.isTrackWorkspaceOpenClose());
            trackingConfig.put("numWorkspaceEventsToKeep", env.numWorkspaceEventsToKeep());
            response.put("tracking", trackingConfig);

            // ========== DEBUG SETTINGS ==========
            Map<String, Object> debugSettings = new LinkedHashMap<>();
            debugSettings.put("checkInputChange", env.isCheckInputChange());
            debugSettings.put("checkOutputChange", env.isCheckOutputChange());
            debugSettings.put("deletePrimary", env.isDeletePrimary());
            debugSettings.put("deleteSpecial", env.isDeleteSpecial());
            debugSettings.put("deleteShapeInfo", env.isDeleteShapeInfo());
            debugSettings.put("variableTracingEnabled", env.isVariableTracingEnabled());
            response.put("debug", debugSettings);

            // ========== PROFILING MODE PRESETS ==========
            // Show available presets for quick configuration
            Map<String, Object> presets = new LinkedHashMap<>();
            presets.put("production", "Minimal overhead: all profiling disabled");
            presets.put("monitoring", "Light monitoring: lifecycle + operations tracking");
            presets.put("debugging", "Full debugging: all tracking + verbose + debug modes");
            presets.put("memoryAnalysis", "Memory focus: lifecycle + deletions + snapshots");
            response.put("availablePresets", presets);

            // ========== INFERENCE METRICS PLACEHOLDER ==========
            // This will be populated by actual inference operations
            Map<String, Object> inferenceMetrics = new LinkedHashMap<>();
            inferenceMetrics.put("note", "Real-time inference metrics available via [INFERENCE-TIMING] logs");
            inferenceMetrics.put("heartbeatInterval", "5 seconds during active inference");
            response.put("inference", inferenceMetrics);

            response.put("status", "success");
            response.put("timestamp", System.currentTimeMillis());
            response.put("collectionTimeMs", System.currentTimeMillis() - startTime);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error collecting profiling metrics", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Apply a profiling preset configuration.
     * Presets: production, monitoring, debugging, memoryAnalysis
     */
    @PostMapping("/nd4j/profiling-preset/{preset}")
    public ResponseEntity<Map<String, Object>> applyProfilingPreset(
            @PathVariable(name = "preset") String preset) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();

            switch (preset.toLowerCase()) {
                case "production":
                    // Minimal overhead - disable all profiling
                    env.setVerbose(false);
                    env.setDebug(false);
                    env.setProfiling(false);
                    env.setLeaksDetector(false);
                    env.setLifecycleTracking(false);
                    env.setTrackViews(false);
                    env.setTrackDeletions(false);
                    env.setTrackOperations(false);
                    env.setLogNDArrayEvents(false);
                    env.setLogNativeNDArrayCreation(false);
                    env.setFuncTraceForAllocate(false);
                    env.setFuncTraceForDeallocate(false);
                    env.setSnapshotFiles(false);
                    env.setCheckInputChange(false);
                    env.setCheckOutputChange(false);
                    logger.info("Applied PRODUCTION preset - all profiling disabled");
                    response.put("message", "Production preset applied - minimal overhead");
                    break;

                case "monitoring":
                    // Light monitoring for production issues
                    env.setVerbose(false);
                    env.setDebug(false);
                    env.setProfiling(true);
                    env.setLeaksDetector(false);
                    env.setLifecycleTracking(true);
                    env.setTrackViews(false);
                    env.setTrackDeletions(false);
                    env.setTrackOperations(true);
                    env.setLogNDArrayEvents(false);
                    env.setLogNativeNDArrayCreation(false);
                    env.setFuncTraceForAllocate(false);
                    env.setFuncTraceForDeallocate(false);
                    env.setSnapshotFiles(false);
                    env.setStackDepth(16);
                    env.setReportInterval(60);
                    logger.info("Applied MONITORING preset - light profiling enabled");
                    response.put("message", "Monitoring preset applied - light overhead");
                    break;

                case "debugging":
                    // Full debugging for development
                    env.setVerbose(true);
                    env.setDebug(true);
                    env.setProfiling(true);
                    env.setLeaksDetector(true);
                    env.setLifecycleTracking(true);
                    env.setTrackViews(true);
                    env.setTrackDeletions(true);
                    env.setTrackOperations(true);
                    env.setLogNDArrayEvents(true);
                    env.setLogNativeNDArrayCreation(true);
                    env.setFuncTraceForAllocate(false); // Still high overhead
                    env.setFuncTraceForDeallocate(false);
                    env.setSnapshotFiles(true);
                    env.setStackDepth(32);
                    env.setReportInterval(30);
                    logger.warn("Applied DEBUGGING preset - HIGH overhead, use only for debugging");
                    response.put("message", "Debugging preset applied - HIGH overhead");
                    response.put("warning", "This will significantly impact performance");
                    break;

                case "memoryanalysis":
                    // Focus on memory leak detection
                    env.setVerbose(false);
                    env.setDebug(false);
                    env.setProfiling(true);
                    env.setLeaksDetector(true);
                    env.setLifecycleTracking(true);
                    env.setTrackViews(true);
                    env.setTrackDeletions(true);
                    env.setTrackOperations(true);
                    env.setLogNDArrayEvents(false);
                    env.setLogNativeNDArrayCreation(false);
                    env.setFuncTraceForAllocate(false);
                    env.setFuncTraceForDeallocate(false);
                    env.setSnapshotFiles(true);
                    env.setStackDepth(48);
                    env.setReportInterval(30);
                    env.setMaxDeletionHistory(50000);
                    logger.info("Applied MEMORY ANALYSIS preset - focused on leak detection");
                    response.put("message", "Memory analysis preset applied - moderate overhead");
                    break;

                default:
                    response.put("status", "error");
                    response.put("message", "Unknown preset: " + preset);
                    response.put("validPresets", Arrays.asList("production", "monitoring", "debugging", "memoryAnalysis"));
                    return ResponseEntity.badRequest().body(response);
            }

            // Persist the preset configuration to disk
            persistProfilingPreset(preset);

            response.put("status", "success");
            response.put("preset", preset);
            response.put("currentState", getCurrentEnvironmentState());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error applying profiling preset: " + preset, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Persist profiling preset configuration to disk.
     */
    private void persistProfilingPreset(String preset) {
        if (nd4jEnvironmentConfigService == null) {
            logger.warn("Nd4jEnvironmentConfigService not available - preset not persisted to disk");
            return;
        }

        try {
            Nd4jEnvironmentConfig.Builder builder = Nd4jEnvironmentConfig.builder();

            switch (preset.toLowerCase()) {
                case "production":
                    builder.verbose(false)
                            .debug(false)
                            .profiling(false)
                            .leaksDetector(false)
                            .lifecycleTracking(false)
                            .trackViews(false)
                            .trackDeletions(false)
                            .trackOperations(false)
                            .logNDArrayEvents(false)
                            .logNativeNDArrayCreation(false)
                            .funcTracePrintAllocate(false)
                            .funcTracePrintDeallocate(false)
                            .snapshotFiles(false)
                            .checkInputChange(false)
                            .checkOutputChange(false);
                    break;

                case "monitoring":
                    builder.verbose(false)
                            .debug(false)
                            .profiling(true)
                            .leaksDetector(false)
                            .lifecycleTracking(true)
                            .trackViews(false)
                            .trackDeletions(false)
                            .trackOperations(true)
                            .logNDArrayEvents(false)
                            .logNativeNDArrayCreation(false)
                            .funcTracePrintAllocate(false)
                            .funcTracePrintDeallocate(false)
                            .snapshotFiles(false)
                            .stackDepth(16)
                            .reportInterval(60);
                    break;

                case "debugging":
                    builder.verbose(true)
                            .debug(true)
                            .profiling(true)
                            .leaksDetector(true)
                            .lifecycleTracking(true)
                            .trackViews(true)
                            .trackDeletions(true)
                            .trackOperations(true)
                            .logNDArrayEvents(true)
                            .logNativeNDArrayCreation(true)
                            .funcTracePrintAllocate(false)
                            .funcTracePrintDeallocate(false)
                            .snapshotFiles(true)
                            .stackDepth(32)
                            .reportInterval(30);
                    break;

                case "memoryanalysis":
                    builder.verbose(false)
                            .debug(false)
                            .profiling(true)
                            .leaksDetector(true)
                            .lifecycleTracking(true)
                            .trackViews(true)
                            .trackDeletions(true)
                            .trackOperations(true)
                            .logNDArrayEvents(false)
                            .logNativeNDArrayCreation(false)
                            .funcTracePrintAllocate(false)
                            .funcTracePrintDeallocate(false)
                            .snapshotFiles(true)
                            .stackDepth(48)
                            .reportInterval(30)
                            .maxDeletionHistory(50000);
                    break;

                default:
                    return; // Unknown preset, don't persist
            }

            nd4jEnvironmentConfigService.updateConfiguration(builder.build());
            logger.debug("Persisted profiling preset '{}' to config file", preset);
        } catch (Exception e) {
            logger.warn("Failed to persist profiling preset '{}': {}", preset, e.getMessage());
        }
    }

    /**
     * Get real-time thread dump for ND4J/embedding workers.
     * Useful for diagnosing stuck threads in native code.
     */
    @GetMapping("/nd4j/thread-dump")
    public ResponseEntity<Map<String, Object>> getThreadDump(
            @RequestParam(name = "filter", required = false, defaultValue = "") String filter) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();
            List<Map<String, Object>> threadList = new ArrayList<>();

            for (Map.Entry<Thread, StackTraceElement[]> entry : allThreads.entrySet()) {
                Thread thread = entry.getKey();
                StackTraceElement[] stack = entry.getValue();

                // Apply filter if provided
                String threadName = thread.getName().toLowerCase();
                if (!filter.isEmpty() && !threadName.contains(filter.toLowerCase())) {
                    continue;
                }

                Map<String, Object> threadInfo = new LinkedHashMap<>();
                threadInfo.put("name", thread.getName());
                threadInfo.put("id", thread.getId());
                threadInfo.put("state", thread.getState().toString());
                threadInfo.put("priority", thread.getPriority());
                threadInfo.put("isDaemon", thread.isDaemon());
                threadInfo.put("isAlive", thread.isAlive());
                threadInfo.put("isInterrupted", thread.isInterrupted());

                // Include stack trace (limit depth for readability)
                List<String> stackTrace = new ArrayList<>();
                int maxDepth = Math.min(20, stack.length);
                for (int i = 0; i < maxDepth; i++) {
                    stackTrace.add(stack[i].toString());
                }
                if (stack.length > maxDepth) {
                    stackTrace.add("... " + (stack.length - maxDepth) + " more frames");
                }
                threadInfo.put("stackTrace", stackTrace);
                threadInfo.put("stackDepth", stack.length);

                // Flag potentially stuck threads
                boolean mightBeStuck = false;
                for (StackTraceElement elem : stack) {
                    String className = elem.getClassName();
                    if (className.contains("Nd4jCpu") || className.contains("execCustomOp") ||
                        className.contains("NativeOp") || className.contains("openblas")) {
                        mightBeStuck = true;
                        threadInfo.put("inNativeCode", true);
                        threadInfo.put("nativeMethod", elem.toString());
                        break;
                    }
                }
                threadInfo.put("mightBeStuck", mightBeStuck);

                threadList.add(threadInfo);
            }

            // Sort by state (RUNNABLE first, then by name)
            threadList.sort((a, b) -> {
                String stateA = (String) a.get("state");
                String stateB = (String) b.get("state");
                if (stateA.equals("RUNNABLE") && !stateB.equals("RUNNABLE")) return -1;
                if (!stateA.equals("RUNNABLE") && stateB.equals("RUNNABLE")) return 1;
                return ((String) a.get("name")).compareTo((String) b.get("name"));
            });

            response.put("status", "success");
            response.put("totalThreads", allThreads.size());
            response.put("filteredThreads", threadList.size());
            response.put("filter", filter.isEmpty() ? "none" : filter);
            response.put("threads", threadList);

            // Summary
            Map<String, Long> stateCounts = threadList.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            t -> (String) t.get("state"),
                            java.util.stream.Collectors.counting()));
            response.put("stateCounts", stateCounts);

            long inNativeCount = threadList.stream()
                    .filter(t -> Boolean.TRUE.equals(t.get("inNativeCode")))
                    .count();
            response.put("threadsInNativeCode", inNativeCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting thread dump", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== Subprocess Logs Endpoint ==========

    /**
     * Get recent subprocess logs for the embedding model initialization.
     * This provides historical log data for clients that connect after events were broadcast.
     *
     * @param limit Maximum number of log entries to return (default 100)
     * @return List of subprocess log entries with event type, model ID, timestamp, and data
     */
    @GetMapping("/subprocess/logs")
    public ResponseEntity<Map<String, Object>> getSubprocessLogs(
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (embeddingStatusBroadcaster == null) {
                response.put("status", "error");
                response.put("message", "EmbeddingStatusBroadcaster not available");
                response.put("logs", Collections.emptyList());
                return ResponseEntity.ok(response);
            }

            List<Map<String, Object>> logs = embeddingStatusBroadcaster.getRecentSubprocessLogs(limit);

            response.put("status", "success");
            response.put("count", logs.size());
            response.put("limit", limit);
            response.put("logs", logs);

            // Add summary of event types
            Map<String, Long> eventTypeCounts = logs.stream()
                    .filter(log -> log.get("eventType") != null)
                    .collect(java.util.stream.Collectors.groupingBy(
                            log -> (String) log.get("eventType"),
                            java.util.stream.Collectors.counting()));
            response.put("eventTypeCounts", eventTypeCounts);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting subprocess logs", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            response.put("logs", Collections.emptyList());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Clear subprocess logs.
     * Useful for resetting log history after debugging.
     */
    @DeleteMapping("/subprocess/logs")
    public ResponseEntity<Map<String, Object>> clearSubprocessLogs() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (embeddingStatusBroadcaster == null) {
                response.put("status", "error");
                response.put("message", "EmbeddingStatusBroadcaster not available");
                return ResponseEntity.ok(response);
            }

            embeddingStatusBroadcaster.clearSubprocessLogs();
            response.put("status", "success");
            response.put("message", "Subprocess logs cleared");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error clearing subprocess logs", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
