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

package ai.kompile.modelmanager.llm.registry;

import ai.kompile.modelmanager.llm.LlmModelSet;
import ai.kompile.modelmanager.llm.dynamic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Singleton registry for LLM pipelines, stages, and model sets.
 *
 * <p>Analogous to {@code VlmPipelineRegistry}. Provides CRUD operations
 * and validation for dynamic pipeline configurations.</p>
 *
 * <p>Thread-safe via ConcurrentHashMap.</p>
 */
public class LlmPipelineRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmPipelineRegistry.class);
    private static final LlmPipelineRegistry INSTANCE = new LlmPipelineRegistry();

    private final Map<String, LlmPipelineDefinition> pipelines = new ConcurrentHashMap<>();
    private final Map<String, LlmStageDefinition> stages = new ConcurrentHashMap<>();
    private final Map<String, LlmCustomModelSet> customModelSets = new ConcurrentHashMap<>();

    private LlmPipelineRegistry() {
        registerBuiltinStages();
        registerBuiltinPipelines();
    }

    public static LlmPipelineRegistry getInstance() {
        return INSTANCE;
    }

    // --- Builtin Registration ---

    private void registerBuiltinStages() {
        stages.put("TOKENIZATION", LlmStageDefinition.TOKENIZATION);
        stages.put("TOKEN_EMBEDDING", LlmStageDefinition.TOKEN_EMBEDDING);
        stages.put("AUTOREGRESSIVE_DECODING", LlmStageDefinition.AUTOREGRESSIVE_DECODING);
        stages.put("TOKEN_SAMPLING", LlmStageDefinition.TOKEN_SAMPLING);
        stages.put("TOKEN_DECODING", LlmStageDefinition.TOKEN_DECODING);
    }

    private void registerBuiltinPipelines() {
        // Default text generation pipeline
        pipelines.put("default-text-generation", LlmPipelineDefinition.builder()
                .pipelineId("default-text-generation")
                .displayName("Default Text Generation")
                .description("Standard autoregressive text generation pipeline with tokenization, embedding, decoding, and detokenization.")
                .pipelineType(LlmPipelineDefinition.PipelineType.SEQUENCE)
                .isBuiltin(true)
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKENIZATION").order(1).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKEN_EMBEDDING").order(2).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("AUTOREGRESSIVE_DECODING").order(3).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKEN_SAMPLING").order(4).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKEN_DECODING").order(5).build())
                .addParameter("maxNewTokens", 256)
                .addParameter("temperature", 0.7)
                .addParameter("topK", 50)
                .addParameter("samplingStrategy", "top_p")
                .build());

        // Chat pipeline with tool calling
        pipelines.put("chat-with-tools", LlmPipelineDefinition.builder()
                .pipelineId("chat-with-tools")
                .displayName("Chat with Tool Calling")
                .description("Text generation pipeline with tool calling support for agent workflows.")
                .pipelineType(LlmPipelineDefinition.PipelineType.SEQUENCE)
                .isBuiltin(true)
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKENIZATION").order(1).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKEN_EMBEDDING").order(2).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("AUTOREGRESSIVE_DECODING").order(3).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKEN_SAMPLING").order(4).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKEN_DECODING").order(5).build())
                .addParameter("maxNewTokens", 512)
                .addParameter("temperature", 0.0)
                .addParameter("topK", 1)
                .addParameter("samplingStrategy", "greedy")
                .addParameter("enableToolCalling", true)
                .addParameter("toolCallFormat", "json")
                .build());

        // Greedy generation pipeline
        pipelines.put("greedy-generation", LlmPipelineDefinition.builder()
                .pipelineId("greedy-generation")
                .displayName("Greedy Generation")
                .description("Deterministic generation with greedy decoding. Best for factual/structured output.")
                .pipelineType(LlmPipelineDefinition.PipelineType.SEQUENCE)
                .isBuiltin(true)
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKENIZATION").order(1).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKEN_EMBEDDING").order(2).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("AUTOREGRESSIVE_DECODING").order(3).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKEN_SAMPLING").order(4).build())
                .addStage(LlmPipelineStageConfig.builder().stageId("TOKEN_DECODING").order(5).build())
                .addParameter("maxNewTokens", 256)
                .addParameter("temperature", 0.0)
                .addParameter("topK", 1)
                .addParameter("samplingStrategy", "greedy")
                .build());
    }

    // --- Pipeline CRUD ---

    public LlmPipelineDefinition getPipeline(String pipelineId) {
        return pipelines.get(pipelineId);
    }

    public List<LlmPipelineDefinition> getAllPipelines() {
        return new ArrayList<>(pipelines.values());
    }

    public List<LlmPipelineDefinition> getBuiltinPipelines() {
        return pipelines.values().stream()
                .filter(LlmPipelineDefinition::isBuiltin)
                .collect(Collectors.toList());
    }

    public List<LlmPipelineDefinition> getCustomPipelines() {
        return pipelines.values().stream()
                .filter(p -> !p.isBuiltin())
                .collect(Collectors.toList());
    }

    public void registerPipeline(LlmPipelineDefinition pipeline) {
        if (pipeline.getPipelineId() == null || pipeline.getPipelineId().isEmpty()) {
            throw new IllegalArgumentException("Pipeline ID is required");
        }
        pipelines.put(pipeline.getPipelineId(), pipeline);
        log.info("Registered LLM pipeline: {}", pipeline.getPipelineId());
    }

    public boolean removePipeline(String pipelineId) {
        LlmPipelineDefinition existing = pipelines.get(pipelineId);
        if (existing != null && existing.isBuiltin()) {
            throw new IllegalArgumentException("Cannot remove builtin pipeline: " + pipelineId);
        }
        return pipelines.remove(pipelineId) != null;
    }

    // --- Stage CRUD ---

    public LlmStageDefinition getStage(String stageId) {
        return stages.get(stageId);
    }

    public List<LlmStageDefinition> getAllStages() {
        return new ArrayList<>(stages.values());
    }

    public List<LlmStageDefinition> getBuiltinStages() {
        return stages.values().stream()
                .filter(LlmStageDefinition::isBuiltin)
                .collect(Collectors.toList());
    }

    public void registerStage(LlmStageDefinition stage) {
        stages.put(stage.getStageId(), stage);
        log.info("Registered LLM stage: {}", stage.getStageId());
    }

    public boolean removeStage(String stageId) {
        LlmStageDefinition existing = stages.get(stageId);
        if (existing != null && existing.isBuiltin()) {
            throw new IllegalArgumentException("Cannot remove builtin stage: " + stageId);
        }
        return stages.remove(stageId) != null;
    }

    // --- Custom Model Set CRUD ---

    public LlmCustomModelSet getCustomModelSet(String setId) {
        return customModelSets.get(setId);
    }

    public List<LlmCustomModelSet> getAllCustomModelSets() {
        return new ArrayList<>(customModelSets.values());
    }

    public void registerCustomModelSet(LlmCustomModelSet modelSet) {
        customModelSets.put(modelSet.getSetId(), modelSet);
        log.info("Registered custom LLM model set: {}", modelSet.getSetId());
    }

    public boolean removeCustomModelSet(String setId) {
        return customModelSets.remove(setId) != null;
    }

    // --- Validation ---

    public List<String> validatePipeline(LlmPipelineDefinition pipeline) {
        List<String> errors = new ArrayList<>();

        if (pipeline.getPipelineId() == null || pipeline.getPipelineId().isEmpty()) {
            errors.add("Pipeline ID is required");
        }

        if (pipeline.getPipelineType() == LlmPipelineDefinition.PipelineType.SEQUENCE) {
            if (pipeline.getStages() == null || pipeline.getStages().isEmpty()) {
                errors.add("Sequential pipeline must have at least one stage");
            } else {
                for (LlmPipelineStageConfig stageConfig : pipeline.getStages()) {
                    if (!stages.containsKey(stageConfig.getStageId())) {
                        errors.add("Unknown stage: " + stageConfig.getStageId());
                    }
                }
            }
        } else if (pipeline.getPipelineType() == LlmPipelineDefinition.PipelineType.GRAPH) {
            if (pipeline.getGraphNodes() == null || pipeline.getGraphNodes().isEmpty()) {
                errors.add("Graph pipeline must have at least one node");
            } else {
                Set<String> nodeIds = new HashSet<>();
                for (LlmGraphNodeConfig node : pipeline.getGraphNodes()) {
                    if (!nodeIds.add(node.getNodeId())) {
                        errors.add("Duplicate node ID: " + node.getNodeId());
                    }
                    if (!stages.containsKey(node.getStageId())) {
                        errors.add("Unknown stage in node " + node.getNodeId() + ": " + node.getStageId());
                    }
                    if (node.getInputs() != null) {
                        for (String input : node.getInputs()) {
                            if (!nodeIds.contains(input) && !pipeline.getGraphNodes().stream()
                                    .anyMatch(n -> n.getNodeId().equals(input))) {
                                errors.add("Node " + node.getNodeId() + " references unknown input: " + input);
                            }
                        }
                    }
                }
            }
        }

        if (pipeline.getModelSetId() != null && !pipeline.getModelSetId().isEmpty()) {
            if (!LlmModelSet.isModelSetSupported(pipeline.getModelSetId()) &&
                    !customModelSets.containsKey(pipeline.getModelSetId())) {
                errors.add("Unknown model set: " + pipeline.getModelSetId());
            }
        }

        return errors;
    }

    // --- Statistics ---

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPipelines", pipelines.size());
        stats.put("builtinPipelines", getBuiltinPipelines().size());
        stats.put("customPipelines", getCustomPipelines().size());
        stats.put("totalStages", stages.size());
        stats.put("builtinStages", getBuiltinStages().size());
        stats.put("customModelSets", customModelSets.size());
        return stats;
    }
}
