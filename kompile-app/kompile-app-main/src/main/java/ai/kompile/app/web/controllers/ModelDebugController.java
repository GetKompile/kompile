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

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.llm.LanguageModel;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.VariableType;
import org.nd4j.linalg.api.ndarray.INDArray;
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
    public ResponseEntity<String> getSameDiffEmbeddingSummaryText(@PathVariable int modelIndex) {
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
    public ResponseEntity<String> getSameDiffSummaryText(@PathVariable String beanName) {
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
            @PathVariable int modelIndex,
            @RequestParam(defaultValue = "true") boolean includeVariables,
            @RequestParam(defaultValue = "true") boolean includeOperations,
            @RequestParam(defaultValue = "1000") int maxVariables,
            @RequestParam(defaultValue = "1000") int maxOperations) {

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
            @PathVariable String beanName,
            @RequestParam(defaultValue = "true") boolean includeVariables,
            @RequestParam(defaultValue = "true") boolean includeOperations,
            @RequestParam(defaultValue = "1000") int maxVariables,
            @RequestParam(defaultValue = "1000") int maxOperations) {

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

            // Operations (with pagination)
            if (includeOperations) {
                List<Map<String, String>> operations = new ArrayList<>();
                org.nd4j.autodiff.functions.DifferentialFunction[] ops = sd.ops();
                int limit = maxOperations <= 0 ? Integer.MAX_VALUE : maxOperations;
                int opCount = 0;

                for (org.nd4j.autodiff.functions.DifferentialFunction op : ops) {
                    if (opCount >= limit) {
                        break;
                    }
                    opCount++;

                    Map<String, String> opInfo = new LinkedHashMap<>();
                    opInfo.put("name", op.getOwnName() != null ? op.getOwnName() : "unnamed");
                    opInfo.put("opType", op.opName());
                    opInfo.put("opClass", op.getClass().getSimpleName());
                    operations.add(opInfo);
                }
                response.put("operations", operations);
                response.put("operationCount", operations.size());
                response.put("operationsTruncated", ops.length > operations.size());
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
            @RequestParam(defaultValue = "Hello world") String text,
            @RequestParam(defaultValue = "0") int modelIndex) {

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

        // Check fields via reflection
        try {
            Class<?> clazz = obj.getClass();
            for (Field field : clazz.getDeclaredFields()) {
                if (SameDiff.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    SameDiff sd = (SameDiff) field.get(obj);
                    if (sd != null) {
                        models.add(sd);
                    }
                }
            }

            // Check getter methods
            for (Method method : clazz.getMethods()) {
                if (method.getName().startsWith("get") &&
                    method.getParameterCount() == 0 &&
                    SameDiff.class.isAssignableFrom(method.getReturnType())) {
                    try {
                        SameDiff sd = (SameDiff) method.invoke(obj);
                        if (sd != null && !models.contains(sd)) {
                            models.add(sd);
                        }
                    } catch (Exception e) {
                        // Ignore invocation errors
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error extracting SameDiff models from object", e);
        }

        return models;
    }
}
