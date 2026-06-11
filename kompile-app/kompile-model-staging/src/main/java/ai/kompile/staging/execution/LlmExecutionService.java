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

package ai.kompile.staging.execution;

import ai.kompile.staging.conversion.ggml.GgmlImporter;
import ai.kompile.staging.conversion.ggml.GgmlModelInfo;
import ai.kompile.staging.web.dto.*;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipelineConfig;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.llm.generation.KvCacheStrategy;
import org.eclipse.deeplearning4j.llm.generation.SamplingConfig;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ai.kompile.staging.web.dto.ChatRequest;
import ai.kompile.staging.web.dto.ChatResponse;
import ai.kompile.staging.web.dto.BatchGenerateRequest;
import ai.kompile.staging.web.dto.BatchGenerateResponse;
import ai.kompile.staging.web.dto.ChatMessage;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Service managing LLM model loading, unloading, and text generation
 * using the samediff-llm GenerationPipeline API.
 *
 * <p>Thread-safe model state management. Only one model can be loaded at a time.</p>
 */
@Service
public class LlmExecutionService {

    private static final Logger log = LoggerFactory.getLogger(LlmExecutionService.class);

    // Current model state
    private final AtomicReference<String> currentModelId = new AtomicReference<>(null);
    private final AtomicReference<GenerationPipeline> currentPipeline = new AtomicReference<>(null);
    private final AtomicReference<SamplingConfig> currentSamplingConfig = new AtomicReference<>(null);
    private final AtomicBoolean modelLoading = new AtomicBoolean(false);
    private final AtomicBoolean generating = new AtomicBoolean(false);

    // Speculative decoding config
    private volatile SpeculativeDecodingConfig speculativeConfig = SpeculativeDecodingConfig.builder().build();

    // Decoder config
    private volatile DecoderConfigRequest decoderConfig = DecoderConfigRequest.builder().build();

    // KV cache type for the loaded model
    private volatile String kvCacheType = "STATIC";

    // Path used to load the current model
    private volatile String currentDecoderPath = null;

    // ==================== Model Management ====================

    /**
     * Load a model for inference.
     *
     * @param modelId     the model identifier from the registry
     * @param modelPath   the file path to the model
     * @param kvCacheType the KV cache type to use (STATIC, PAGED, QUANTIZED)
     * @return status response
     */
    public LlmModelStatusResponse loadModel(String modelId, String modelPath, String kvCacheType) {
        if (modelLoading.get()) {
            return LlmModelStatusResponse.builder()
                    .modelId(modelId)
                    .loaded(false)
                    .message("Another model is currently being loaded")
                    .build();
        }

        modelLoading.set(true);
        try {
            // Unload current model if any
            unloadModelInternal();

            log.info("Loading LLM model: {} from {}", modelId, modelPath);

            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                return LlmModelStatusResponse.builder()
                        .modelId(modelId)
                        .loaded(false)
                        .message("Model file not found: " + modelPath)
                        .build();
            }

            // Resolve KV cache strategy
            KvCacheStrategy cacheStrategy = resolveKvCacheStrategy(kvCacheType);

            // Build GenerationPipeline from model path
            SamplingConfig defaultConfig = SamplingConfig.defaultConfig();
            GenerationPipelineConfig config = GenerationPipelineConfig.builder()
                    .decoderPath(modelPath)
                    .samplingConfig(defaultConfig)
                    .kvCacheStrategy(cacheStrategy)
                    .build();

            GenerationPipeline pipeline = GenerationPipeline.create(config);

            currentPipeline.set(pipeline);
            currentModelId.set(modelId);
            currentSamplingConfig.set(defaultConfig);
            this.kvCacheType = kvCacheType != null ? kvCacheType : "STATIC";
            this.currentDecoderPath = modelPath;

            long memoryUsage = estimateMemoryUsageMb(null);

            log.info("LLM model loaded successfully: {} (memory ~{}MB)", modelId, memoryUsage);

            return LlmModelStatusResponse.builder()
                    .modelId(modelId)
                    .loaded(true)
                    .memoryUsageMb(memoryUsage)
                    .kvCacheType(this.kvCacheType)
                    .decoderPath(modelPath)
                    .maxContextLength(decoderConfig.getMaxContextLength())
                    .message("Model loaded successfully")
                    .build();

        } catch (Exception e) {
            log.error("Failed to load LLM model: {}", modelId, e);
            return LlmModelStatusResponse.builder()
                    .modelId(modelId)
                    .loaded(false)
                    .message("Failed to load model: " + e.getMessage())
                    .build();
        } finally {
            modelLoading.set(false);
        }
    }

    /**
     * Unload the currently loaded model and free resources.
     */
    public LlmModelStatusResponse unloadModel() {
        String modelId = currentModelId.get();
        unloadModelInternal();
        return LlmModelStatusResponse.builder()
                .modelId(modelId)
                .loaded(false)
                .memoryUsageMb(0)
                .message(modelId != null ? "Model unloaded: " + modelId : "No model was loaded")
                .build();
    }

    private void unloadModelInternal() {
        GenerationPipeline pipeline = currentPipeline.getAndSet(null);
        String modelId = currentModelId.getAndSet(null);
        currentSamplingConfig.set(null);
        currentDecoderPath = null;
        if (pipeline != null) {
            pipeline.close();
            log.info("Unloaded LLM model: {}", modelId);
        }
    }

    /**
     * Get the current model status.
     */
    public LlmModelStatusResponse getStatus() {
        String modelId = currentModelId.get();
        GenerationPipeline pipeline = currentPipeline.get();
        boolean loaded = pipeline != null && modelId != null;

        return LlmModelStatusResponse.builder()
                .modelId(modelId)
                .loaded(loaded)
                .memoryUsageMb(loaded ? estimateMemoryUsageMb(null) : 0)
                .kvCacheType(loaded ? this.kvCacheType : null)
                .decoderPath(loaded ? this.currentDecoderPath : null)
                .maxContextLength(loaded ? decoderConfig.getMaxContextLength() : 0)
                .message(loaded ? "Model ready" : "No model loaded")
                .build();
    }

    // ==================== Text Generation ====================

    /**
     * Generate text synchronously.
     *
     * @param request generation parameters
     * @return generation response with metrics
     */
    public LlmGenerateResponse generate(LlmGenerateRequest request) {
        GenerationPipeline pipeline = currentPipeline.get();
        if (pipeline == null) {
            return LlmGenerateResponse.builder()
                    .generatedText("")
                    .finishReason("error")
                    .build();
        }

        if (generating.getAndSet(true)) {
            return LlmGenerateResponse.builder()
                    .generatedText("")
                    .finishReason("error")
                    .build();
        }

        try {
            int maxTokens = request.getMaxTokens();
            long startTime = System.currentTimeMillis();

            GenerationResult result = pipeline.generate(request.getPrompt(), maxTokens);

            long totalTime = System.currentTimeMillis() - startTime;
            String generatedText = result.getText();

            // Apply stop sequences if configured
            List<String> stopSequences = request.getStopSequences();
            if (stopSequences == null || stopSequences.isEmpty()) {
                stopSequences = decoderConfig.getStopSequences();
            }
            if (stopSequences != null && !stopSequences.isEmpty() && generatedText != null) {
                generatedText = applyStopSequences(generatedText, stopSequences);
            }

            return LlmGenerateResponse.builder()
                    .generatedText(generatedText)
                    .tokensPerSecond(result.getTokensPerSecond())
                    .firstTokenLatencyMs(result.getFirstTokenLatencyMs())
                    .totalTokens(result.getTotalTokenCount())
                    .finishReason(result.getFinishReason() != null ? result.getFinishReason().name() : "completed")
                    .totalTimeMs(totalTime)
                    .build();

        } catch (Exception e) {
            log.error("Text generation failed", e);
            return LlmGenerateResponse.builder()
                    .generatedText("")
                    .finishReason("error: " + e.getMessage())
                    .build();
        } finally {
            generating.set(false);
        }
    }

    /**
     * Generate text with streaming via callback. Each token is sent to the callback
     * as it is generated.
     *
     * @param request      generation parameters
     * @param tokenCallback callback invoked for each generated token
     * @return final generation response with metrics
     */
    public LlmGenerateResponse generateStreaming(LlmGenerateRequest request, Consumer<String> tokenCallback) {
        GenerationPipeline pipeline = currentPipeline.get();
        if (pipeline == null) {
            return LlmGenerateResponse.builder()
                    .generatedText("")
                    .finishReason("error: no model loaded")
                    .build();
        }

        if (generating.getAndSet(true)) {
            return LlmGenerateResponse.builder()
                    .generatedText("")
                    .finishReason("error: generation already in progress")
                    .build();
        }

        try {
            int maxTokens = request.getMaxTokens();
            long startTime = System.currentTimeMillis();
            StringBuilder fullText = new StringBuilder();

            pipeline.generateStream(
                    request.getPrompt(),
                    maxTokens,
                    token -> {
                        fullText.append(token);
                        tokenCallback.accept(token);
                    }
            );

            long totalTime = System.currentTimeMillis() - startTime;

            return LlmGenerateResponse.builder()
                    .generatedText(fullText.toString())
                    .totalTimeMs(totalTime)
                    .finishReason("completed")
                    .build();

        } catch (Exception e) {
            log.error("Streaming text generation failed", e);
            return LlmGenerateResponse.builder()
                    .generatedText("")
                    .finishReason("error: " + e.getMessage())
                    .build();
        } finally {
            generating.set(false);
        }
    }

    /**
     * Chat: format messages using a chat template, then generate a response.
     */
    public ChatResponse chat(ChatRequest request, ChatTemplateService chatTemplateService) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return ChatResponse.builder()
                    .assistantMessage("")
                    .finishReason("error: no messages provided")
                    .build();
        }

        String formattedPrompt = chatTemplateService.format(request.getMessages(), request.getChatTemplate());

        LlmGenerateRequest genRequest = LlmGenerateRequest.builder()
                .prompt(formattedPrompt)
                .maxTokens(request.getMaxTokens())
                .temperature(request.getTemperature())
                .topK(request.getTopK())
                .topP(request.getTopP())
                .repetitionPenalty(request.getRepetitionPenalty())
                .doSample(request.isDoSample())
                .presetName(request.getPresetName())
                .stopSequences(request.getStopSequences())
                .build();

        LlmGenerateResponse response = generate(genRequest);

        return ChatResponse.builder()
                .assistantMessage(response.getGeneratedText())
                .formattedPrompt(formattedPrompt)
                .tokensPerSecond(response.getTokensPerSecond())
                .totalTokens(response.getTotalTokens())
                .finishReason(response.getFinishReason())
                .totalTimeMs(response.getTotalTimeMs())
                .build();
    }

    /**
     * Chat with streaming: format messages using a chat template, then stream tokens.
     */
    public ChatResponse chatStreaming(ChatRequest request, ChatTemplateService chatTemplateService, Consumer<String> tokenCallback) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return ChatResponse.builder()
                    .assistantMessage("")
                    .finishReason("error: no messages provided")
                    .build();
        }

        String formattedPrompt = chatTemplateService.format(request.getMessages(), request.getChatTemplate());

        LlmGenerateRequest genRequest = LlmGenerateRequest.builder()
                .prompt(formattedPrompt)
                .maxTokens(request.getMaxTokens())
                .temperature(request.getTemperature())
                .topK(request.getTopK())
                .topP(request.getTopP())
                .repetitionPenalty(request.getRepetitionPenalty())
                .doSample(request.isDoSample())
                .presetName(request.getPresetName())
                .stopSequences(request.getStopSequences())
                .build();

        LlmGenerateResponse response = generateStreaming(genRequest, tokenCallback);

        return ChatResponse.builder()
                .assistantMessage(response.getGeneratedText())
                .formattedPrompt(formattedPrompt)
                .tokensPerSecond(response.getTokensPerSecond())
                .totalTokens(response.getTotalTokens())
                .finishReason(response.getFinishReason())
                .totalTimeMs(response.getTotalTimeMs())
                .build();
    }

    /**
     * Generate text for multiple prompts sequentially.
     */
    public BatchGenerateResponse generateBatch(BatchGenerateRequest request) {
        long startTime = System.currentTimeMillis();
        List<LlmGenerateResponse> results = new ArrayList<>();
        int successCount = 0;
        int errorCount = 0;

        for (String prompt : request.getPrompts()) {
            LlmGenerateRequest genRequest = LlmGenerateRequest.builder()
                    .prompt(prompt)
                    .maxTokens(request.getMaxTokens())
                    .temperature(request.getTemperature())
                    .topK(request.getTopK())
                    .topP(request.getTopP())
                    .repetitionPenalty(request.getRepetitionPenalty())
                    .doSample(request.isDoSample())
                    .presetName(request.getPresetName())
                    .build();

            LlmGenerateResponse response = generate(genRequest);
            results.add(response);

            if (response.getFinishReason() != null && response.getFinishReason().startsWith("error")) {
                errorCount++;
            } else {
                successCount++;
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;

        return BatchGenerateResponse.builder()
                .results(results)
                .totalTimeMs(totalTime)
                .successCount(successCount)
                .errorCount(errorCount)
                .build();
    }

    /**
     * Cancel any ongoing generation.
     */
    public void cancelGeneration() {
        generating.set(false);
    }

    /**
     * Check if generation is currently in progress.
     */
    public boolean isGenerating() {
        return generating.get();
    }

    // ==================== Decoder Configuration ====================

    /**
     * Update decoder configuration.
     */
    public DecoderConfigRequest updateDecoderConfig(DecoderConfigRequest config) {
        this.decoderConfig = config;
        log.info("Updated decoder config: eosTokenId={}, maxContextLength={}, seed={}, " +
                        "frequencyPenalty={}, presencePenalty={}, minNewTokens={}, stopSequences={}",
                config.getEosTokenId(), config.getMaxContextLength(), config.getSeed(),
                config.getFrequencyPenalty(), config.getPresencePenalty(),
                config.getMinNewTokens(),
                config.getStopSequences() != null ? config.getStopSequences().size() : 0);
        return this.decoderConfig;
    }

    /**
     * Get the current decoder configuration.
     */
    public DecoderConfigRequest getDecoderConfig() {
        return this.decoderConfig;
    }

    // ==================== Pipeline Info ====================

    /**
     * Get detailed pipeline information for the currently loaded model.
     */
    public PipelineInfoResponse getPipelineInfo() {
        String modelId = currentModelId.get();
        GenerationPipeline pipeline = currentPipeline.get();
        boolean loaded = pipeline != null && modelId != null;

        PipelineInfoResponse.PipelineInfoResponseBuilder builder = PipelineInfoResponse.builder()
                .loaded(loaded)
                .modelId(modelId)
                .decoderPath(currentDecoderPath)
                .kvCacheStrategy(this.kvCacheType)
                .memoryUsageMb(loaded ? estimateMemoryUsageMb(null) : 0)
                .decoderConfig(this.decoderConfig)
                .speculativeConfig(this.speculativeConfig);

        if (loaded) {
            // Build current sampling config snapshot
            SamplingConfig sc = currentSamplingConfig.get();
            if (sc != null) {
                Map<String, Object> samplingSnapshot = new LinkedHashMap<>();
                samplingSnapshot.put("temperature", sc.getTemperature());
                samplingSnapshot.put("topK", sc.getTopK());
                samplingSnapshot.put("topP", sc.getTopP());
                samplingSnapshot.put("repetitionPenalty", sc.getRepetitionPenalty());
                samplingSnapshot.put("doSample", sc.isDoSample());
                samplingSnapshot.put("maxNewTokens", sc.getMaxNewTokens());
                builder.currentSamplingConfig(samplingSnapshot);
            }

            builder.message("Pipeline active");
        } else {
            builder.message("No pipeline loaded");
        }

        return builder.build();
    }

    // ==================== Speculative Decoding ====================

    /**
     * Update speculative decoding configuration.
     */
    public SpeculativeDecodingConfig updateSpeculativeConfig(SpeculativeDecodingConfig config) {
        this.speculativeConfig = config;

        // Apply speculative decoding system properties
        if (config.isEnabled()) {
            System.setProperty("samediff.llm.speculative.enabled", "true");
            System.setProperty("samediff.llm.speculative.ngram.size", String.valueOf(config.getNgramSize()));
            System.setProperty("samediff.llm.speculative.max.tokens", String.valueOf(config.getMaxSpeculativeTokens()));
        } else {
            System.setProperty("samediff.llm.speculative.enabled", "false");
        }

        log.info("Updated speculative decoding config: enabled={}, ngramSize={}, maxTokens={}",
                config.isEnabled(), config.getNgramSize(), config.getMaxSpeculativeTokens());

        return this.speculativeConfig;
    }

    /**
     * Get current speculative decoding configuration.
     */
    public SpeculativeDecodingConfig getSpeculativeConfig() {
        return this.speculativeConfig;
    }

    // ==================== Sampling Presets ====================

    /**
     * Get available sampling presets.
     */
    public List<Map<String, Object>> getSamplingPresets() {
        List<Map<String, Object>> presets = new ArrayList<>();

        Map<String, Object> greedy = new LinkedHashMap<>();
        greedy.put("name", "greedy");
        greedy.put("displayName", "Greedy");
        greedy.put("description", "Deterministic, always picks most likely token. Best for factual/structured output.");
        greedy.put("temperature", 0.0);
        greedy.put("topK", 0);
        greedy.put("topP", 1.0);
        greedy.put("repetitionPenalty", 1.0);
        greedy.put("doSample", false);
        presets.add(greedy);

        Map<String, Object> defaultPreset = new LinkedHashMap<>();
        defaultPreset.put("name", "default");
        defaultPreset.put("displayName", "Default");
        defaultPreset.put("description", "Balanced sampling with moderate randomness.");
        defaultPreset.put("temperature", 0.7);
        defaultPreset.put("topK", 50);
        defaultPreset.put("topP", 0.9);
        defaultPreset.put("repetitionPenalty", 1.1);
        defaultPreset.put("doSample", true);
        presets.add(defaultPreset);

        Map<String, Object> creative = new LinkedHashMap<>();
        creative.put("name", "creative");
        creative.put("displayName", "Creative");
        creative.put("description", "High randomness for creative/imaginative text generation.");
        creative.put("temperature", 1.2);
        creative.put("topK", 0);
        creative.put("topP", 0.95);
        creative.put("repetitionPenalty", 1.2);
        creative.put("doSample", true);
        presets.add(creative);

        Map<String, Object> precise = new LinkedHashMap<>();
        precise.put("name", "precise");
        precise.put("displayName", "Precise");
        precise.put("description", "Low randomness for accurate, focused output.");
        precise.put("temperature", 0.3);
        precise.put("topK", 10);
        precise.put("topP", 0.8);
        precise.put("repetitionPenalty", 1.0);
        precise.put("doSample", true);
        presets.add(precise);

        return presets;
    }

    // ==================== Internal Helpers ====================

    /**
     * Build SamplingConfig from a generate request, respecting presets.
     */
    private SamplingConfig buildSamplingConfig(LlmGenerateRequest request) {
        // Check for named presets first
        if (request.getPresetName() != null) {
            switch (request.getPresetName().toLowerCase()) {
                case "greedy": {
                    SamplingConfig sc = SamplingConfig.greedy();
                    sc.setMaxNewTokens(request.getMaxTokens());
                    return sc;
                }
                case "default": {
                    SamplingConfig sc = SamplingConfig.defaultConfig();
                    sc.setMaxNewTokens(request.getMaxTokens());
                    return sc;
                }
                case "creative": {
                    SamplingConfig sc = SamplingConfig.creative();
                    sc.setMaxNewTokens(request.getMaxTokens());
                    return sc;
                }
                case "precise": {
                    SamplingConfig sc = SamplingConfig.precise();
                    sc.setMaxNewTokens(request.getMaxTokens());
                    return sc;
                }
                default:
                    log.warn("Unknown sampling preset '{}', using explicit parameters", request.getPresetName());
                    break;
            }
        }

        // Build from individual parameters
        return SamplingConfig.builder()
                .temperature(request.getTemperature())
                .topK(request.getTopK())
                .topP(request.getTopP())
                .repetitionPenalty(request.getRepetitionPenalty())
                .doSample(request.isDoSample())
                .maxNewTokens(request.getMaxTokens())
                .build();
    }

    /**
     * Resolve KvCacheStrategy from string name.
     */
    private KvCacheStrategy resolveKvCacheStrategy(String kvCacheType) {
        if (kvCacheType == null) return KvCacheStrategy.STATIC;
        switch (kvCacheType.toUpperCase()) {
            case "PAGED": return KvCacheStrategy.PAGED;
            case "QUANTIZED": return KvCacheStrategy.QUANTIZED;
            case "STATIC":
            default: return KvCacheStrategy.STATIC;
        }
    }

    /**
     * Apply stop sequences to generated text, truncating at the first match.
     */
    private String applyStopSequences(String text, List<String> stopSequences) {
        if (text == null || stopSequences == null) return text;
        int earliestStop = text.length();
        for (String seq : stopSequences) {
            if (seq != null && !seq.isEmpty()) {
                int idx = text.indexOf(seq);
                if (idx >= 0 && idx < earliestStop) {
                    earliestStop = idx;
                }
            }
        }
        return earliestStop < text.length() ? text.substring(0, earliestStop) : text;
    }

    /**
     * Estimate memory usage in MB for the loaded model.
     */
    private long estimateMemoryUsageMb(SameDiff sameDiff) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            return usedMemory / (1024 * 1024);
        } catch (Exception e) {
            return -1;
        }
    }
}
