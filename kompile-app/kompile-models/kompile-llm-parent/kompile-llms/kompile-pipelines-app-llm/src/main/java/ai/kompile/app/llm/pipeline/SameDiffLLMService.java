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

package ai.kompile.app.llm.pipeline;

import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.ModelConstants.SameDiffLLMModelDescriptor;
import ai.kompile.modelmanager.llm.*;
import ai.kompile.modelmanager.llm.dynamic.LlmPipelineDefinition;
import ai.kompile.modelmanager.llm.registry.LlmPipelineRegistry;
import ai.kompile.pipelines.steps.samediff.llm.SameDiffLLMModelComponent;
import ai.kompile.pipelines.steps.samediff.llm.SameDiffLLMModelSet;
import ai.kompile.pipelines.steps.samediff.llm.SameDiffLLMPipelineBuilder;
import ai.kompile.pipelines.framework.runtime.pipeline.graph.GraphPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Service for managing SameDiff LLM pipelines and model sets.
 *
 * <p>Uses the model manager layer ({@link LlmModels}, {@link LlmModelResolver},
 * {@link LlmModelSetDownloader}) for model management and the
 * {@link LlmPipelineRegistry} for pipeline configuration management.</p>
 */
@Slf4j
@Service
public class SameDiffLLMService {

    private final LlmModelSetDownloader downloader = LlmModelSetDownloader.getInstance();
    private final LlmModelResolver resolver = LlmModelResolver.getInstance();
    private final LlmPipelineRegistry registry = LlmPipelineRegistry.getInstance();
    private final ConcurrentMap<String, GraphPipeline> registeredPipelines = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> downloadingModels = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DownloadProgress> downloadProgressMap = new ConcurrentHashMap<>();

    public SameDiffLLMService() {
        log.info("SameDiffLLMService initialized with cache directory: {}",
                downloader.getCacheDirectory());
    }

    // ==================== Model Set Operations ====================

    /**
     * Get all available SameDiff LLM model sets using the model manager layer.
     */
    public List<Map<String, Object>> getModelSets() {
        List<Map<String, Object>> results = new ArrayList<>();

        for (LlmModelSet modelSet : LlmModelSet.getAllModelSets().values()) {
            results.add(modelSetToMap(modelSet));
        }

        return results;
    }

    /**
     * Get a specific model set by ID.
     */
    public Optional<Map<String, Object>> getModelSet(String setId) {
        LlmModelSet modelSet = LlmModelSet.getModelSet(setId);
        if (modelSet == null) {
            return Optional.empty();
        }
        return Optional.of(modelSetToMap(modelSet));
    }

    /**
     * Get cache status for all model sets using the model manager layer.
     */
    public Map<String, Object> getModelSetsStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("cacheDirectory", downloader.getCacheDirectory().toString());
        status.put("modelSets", downloader.getCacheStatus());
        status.put("readyModels", LlmModels.getReadyModels());
        return status;
    }

    /**
     * Check if a model set is cached.
     */
    public boolean isModelSetCached(String setId) {
        return LlmModels.isCached(setId);
    }

    /**
     * Download a model set using the model manager layer.
     */
    public void downloadModelSet(String setId) {
        if (downloadingModels.putIfAbsent(setId, true) != null) {
            throw new IllegalStateException("Model set " + setId + " is already being downloaded");
        }

        LlmModelSet modelSet = LlmModelSet.getModelSet(setId);
        if (modelSet == null) {
            downloadingModels.remove(setId);
            throw new IllegalArgumentException("Unknown model set: " + setId);
        }
        DownloadProgress progress = new DownloadProgress(setId, modelSet.getComponents().size());
        downloadProgressMap.put(setId, progress);

        new Thread(() -> {
            try {
                downloader.downloadModelSet(modelSet, (component, componentProgress) -> {
                    progress.setCurrentComponent(component.getFileName());
                    if (componentProgress >= 1.0) {
                        progress.componentCompleted();
                    }
                    progress.setComponentProgress(componentProgress);
                });
                progress.setComplete(true);
            } catch (IOException e) {
                log.error("Download failed for model set: {}", setId, e);
                progress.setFailed("Download failed: " + e.getMessage());
            } finally {
                downloadingModels.remove(setId);
            }
        }, "llm-download-" + setId).start();
    }

    /**
     * Get download progress.
     */
    public DownloadProgress getDownloadProgress(String setId) {
        return downloadProgressMap.get(setId);
    }

    /**
     * Delete a cached model set using the model manager layer.
     */
    public boolean deleteModelSet(String setId) {
        return LlmModels.delete(setId);
    }

    // ==================== Pipeline Management ====================

    /**
     * Create a new pipeline from configuration.
     */
    public GraphPipeline createPipeline(String pipelineId, String modelSetId, Map<String, Object> config) {
        LlmModelResolver.ResolvedLlmModel resolved = resolver.resolve(modelSetId);
        if (resolved == null) {
            throw new IllegalArgumentException("Unknown model set: " + modelSetId);
        }

        if (!resolved.isFullyCached()) {
            throw new IllegalStateException("Model set " + modelSetId + " is not downloaded");
        }

        LlmModelSet modelSet = resolved.getModelSet();
        SameDiffLLMPipelineBuilder builder = SameDiffLLMPipelineBuilder.create()
                .modelId(modelSetId)
                .embedTokens(resolved.getEmbedTokensPath().toString())
                .decoder(resolved.getDecoderPath().toString())
                .tokenizer(resolved.getTokenizerPath().toString())
                .eosTokenId(modelSet.getEosTokenId())
                .maxNewTokens((Integer) config.getOrDefault("maxNewTokens", 512))
                .temperature(((Number) config.getOrDefault("temperature", 0.7)).floatValue())
                .topK((Integer) config.getOrDefault("topK", 40))
                .enableToolCalling((Boolean) config.getOrDefault("enableToolCalling", false))
                .pipelineId(pipelineId);

        GraphPipeline pipeline = builder.build();
        registeredPipelines.put(pipelineId, pipeline);

        log.info("Created pipeline {} with model set {}", pipelineId, modelSetId);
        return pipeline;
    }

    /**
     * Get a registered pipeline.
     */
    public Optional<GraphPipeline> getPipeline(String pipelineId) {
        return Optional.ofNullable(registeredPipelines.get(pipelineId));
    }

    /**
     * List all registered pipelines.
     */
    public List<String> listPipelines() {
        return new ArrayList<>(registeredPipelines.keySet());
    }

    /**
     * Delete a pipeline.
     */
    public boolean deletePipeline(String pipelineId) {
        return registeredPipelines.remove(pipelineId) != null;
    }

    // ==================== Registry Operations ====================

    /**
     * Get all pipeline definitions from the registry.
     */
    public List<LlmPipelineDefinition> getPipelineDefinitions() {
        return registry.getAllPipelines();
    }

    /**
     * Get pipeline stages from registry.
     */
    public List<Map<String, Object>> getPipelineStages() {
        List<Map<String, Object>> stages = new ArrayList<>();
        for (var stage : registry.getAllStages()) {
            Map<String, Object> stageMap = new LinkedHashMap<>();
            stageMap.put("stageId", stage.getStageId());
            stageMap.put("displayName", stage.getDisplayName());
            stageMap.put("inputDescription", stage.getInputDescription());
            stageMap.put("outputDescription", stage.getOutputDescription());
            stageMap.put("modelComponentKey", stage.getModelComponentKey());
            stageMap.put("requiresModel", stage.isRequiresModel());
            stageMap.put("isBuiltin", stage.isBuiltin());
            stages.add(stageMap);
        }
        return stages;
    }

    /**
     * Get registry statistics.
     */
    public Map<String, Object> getRegistryStats() {
        return registry.getStats();
    }

    /**
     * Get service status.
     */
    public Map<String, Object> getServiceStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("cacheDirectory", downloader.getCacheDirectory().toString());
        status.put("cachedModels", LlmModels.getReadyModels());
        status.put("activePipelines", registeredPipelines.keySet());
        status.put("activeDownloads", new ArrayList<>(downloadingModels.keySet()));
        status.put("registryStats", registry.getStats());
        status.put("availableModelSets", LlmModelSet.getAllModelSets().size());
        return status;
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> modelSetToMap(LlmModelSet modelSet) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("setId", modelSet.getSetId());
        map.put("displayName", modelSet.getDisplayName());
        map.put("description", modelSet.getDescription());
        map.put("architecture", modelSet.getArchitecture());
        map.put("vocabSize", modelSet.getVocabSize());
        map.put("hiddenSize", modelSet.getHiddenSize());
        map.put("numLayers", modelSet.getNumLayers());
        map.put("numHeads", modelSet.getNumHeads());
        map.put("numKvHeads", modelSet.getNumKvHeads());
        map.put("headDim", modelSet.getHeadDim());
        map.put("contextLength", modelSet.getMaxPositionEmbeddings());
        map.put("eosTokenId", modelSet.getEosTokenId());
        map.put("cached", isModelSetCached(modelSet.getSetId()));

        List<Map<String, Object>> componentsList = new ArrayList<>();
        for (LlmModelComponent component : modelSet.getComponents()) {
            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("componentKey", component.getComponentKey());
            comp.put("fileName", component.getFileName());
            comp.put("description", component.getDescription());
            comp.put("pipelineStage", component.getPipelineStage() != null ? component.getPipelineStage().getId() : null);
            componentsList.add(comp);
        }
        map.put("components", componentsList);
        map.put("huggingFaceRepo", modelSet.getHuggingFaceRepo());
        return map;
    }

    /**
     * Download progress tracking.
     */
    public static class DownloadProgress {
        private final String setId;
        private boolean downloading;
        private boolean complete;
        private boolean success;
        private String currentComponent;
        private int componentsCompleted;
        private int totalComponents;
        private double componentProgress;
        private String message;

        public DownloadProgress(String setId, int totalComponents) {
            this.setId = setId;
            this.totalComponents = totalComponents;
            this.downloading = true;
            this.complete = false;
            this.success = false;
            this.componentsCompleted = 0;
        }

        public String getSetId() { return setId; }
        public boolean isDownloading() { return downloading; }
        public boolean isComplete() { return complete; }
        public boolean isSuccess() { return success; }
        public String getCurrentComponent() { return currentComponent; }
        public int getComponentsCompleted() { return componentsCompleted; }
        public int getTotalComponents() { return totalComponents; }
        public double getComponentProgress() { return componentProgress; }
        public String getMessage() { return message; }

        public void setCurrentComponent(String component) { this.currentComponent = component; }
        public void setComponentProgress(double progress) { this.componentProgress = progress; }

        public void componentCompleted() {
            this.componentsCompleted++;
        }

        public void setComplete(boolean success) {
            this.complete = true;
            this.success = success;
            this.downloading = false;
            this.message = success ? "Download complete" : "Download failed";
        }

        public void setFailed(String error) {
            this.complete = true;
            this.success = false;
            this.downloading = false;
            this.message = error;
        }
    }
}
