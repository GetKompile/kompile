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

import ai.kompile.modelmanager.llm.LlmModelSet;
import ai.kompile.staging.conversion.ggml.GgmlImporter;
import ai.kompile.staging.conversion.ggml.GgmlModelInfo;
import ai.kompile.staging.web.dto.*;
import ai.kompile.utils.inference.InferenceBatchPlanner;
import org.nd4j.common.config.ND4JSystemProperties;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipelineConfig;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.llm.generation.kvcache.KvCacheStrategy;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
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

    // KV-bucket / DSP-plan shape chosen at load time via InferenceBatchPlanner.
    // 0 means no model loaded or bucketing not computed yet.
    private volatile int currentKvBucket = 0;

    // Model context window (max_position_embeddings) for the loaded model.
    private volatile int currentModelContextWindow = 0;

    // Hidden size for the loaded model (used for memory-ceiling estimation).
    private volatile int currentHiddenSize = 0;

    // Default sequence buckets for KV-cache / position plan reuse.
    // Configurable via system property: kompile.llm.seqBuckets (comma-separated ints).
    private static final String SEQ_BUCKETS_PROP = "kompile.llm.seqBuckets";
    private static final String SEQ_BUCKETS_DEFAULT = "256,512,1024,2048,4096";

    // Decode chunk size for retained-KV continuation on in-graph-KV GGUF models.
    // maxTokens remains the total per-request output ceiling.
    static final String CONTINUATION_CHUNK_TOKENS_PROP =
            "kompile.llm.continuationChunkTokens";
    private static final int CONTINUATION_CHUNK_TOKENS_DEFAULT = 384;

    // Safety fraction for native-memory ceiling estimate.
    // Configurable via system property: kompile.llm.memorySafetyFraction
    private static final String SAFETY_FRACTION_PROP = "kompile.llm.memorySafetyFraction";
    private static final double SAFETY_FRACTION_DEFAULT = 0.5;

    // Activation factor for native-memory ceiling estimate.
    // Configurable via system property: kompile.llm.activationFactor
    private static final String ACTIVATION_FACTOR_PROP = "kompile.llm.activationFactor";
    private static final double ACTIVATION_FACTOR_DEFAULT = 16.0;

    // ==================== Model Management ====================

    /**
     * Load a model for inference.
     *
     * @param modelId     the model identifier from the registry
     * @param modelPath   the file path to the model
     * @param kvCacheType the KV cache type to use (STATIC, PAGED, QUANTIZED)
     * @return status response
     */
    public synchronized LlmModelStatusResponse loadModel(String modelId, String modelPath, String kvCacheType) {
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

            // Resolve model context window and hidden size from LlmModelSet catalogue
            // (keyed by modelId). Falls back to decoderConfig when set, otherwise uses
            // a safe conservative default so bucketing still works for unknown models.
            LlmModelSet modelSet = LlmModelSet.getModelSet(modelId);
            int modelContextWindow;
            int hiddenSize;
            if (modelSet != null) {
                modelContextWindow = modelSet.getMaxPositionEmbeddings();
                hiddenSize = modelSet.getHiddenSize();
            } else {
                // Caller-supplied decoderConfig.maxContextLength takes priority over defaults.
                modelContextWindow = decoderConfig.getMaxContextLength() > 0
                        ? decoderConfig.getMaxContextLength() : 2048;
                // Hidden size 0 → estimateMaxBatchTokens still runs but is conservative.
                hiddenSize = 0;
            }

            // KV-cache / DSP-plan position bucketing via InferenceBatchPlanner.
            // Rounds up the context window to the nearest configured bucket,
            // then clamps it by the native-memory ceiling so one request can't
            // exhaust native memory (org.bytedeco.javacpp.maxphysicalbytes).
            if (decoderConfig.getMaxContextLength() > 0) {
                modelContextWindow = Math.min(modelContextWindow, decoderConfig.getMaxContextLength());
            }
            int kvBucket = computeKvBucket(modelContextWindow, hiddenSize);

            // Build GenerationPipeline from model path with the bucketed maxKvCacheLength.
            SamplingConfig defaultConfig = SamplingConfig.defaultConfig();
            GenerationPipelineConfig config = GenerationPipelineConfig.builder()
                    .decoderPath(modelPath)
                    .samplingConfig(defaultConfig)
                    .kvCacheStrategy(cacheStrategy)
                    .maxKvCacheLength(kvBucket)
                    .prefillLastPositionLogitsEnabled(true)
                    .build();

            GenerationPipeline pipeline = GenerationPipeline.create(config);

            currentPipeline.set(pipeline);
            currentModelId.set(modelId);
            currentSamplingConfig.set(defaultConfig);
            this.kvCacheType = kvCacheType != null ? kvCacheType : "STATIC";
            this.currentDecoderPath = modelPath;
            this.currentKvBucket = kvBucket;
            this.currentModelContextWindow = modelContextWindow;
            this.currentHiddenSize = hiddenSize;

            long memoryUsage = estimateMemoryUsageMb(null);

            log.info("LLM model loaded successfully: {} (memory ~{}MB, kvBucket={}, contextWindow={})",
                    modelId, memoryUsage, kvBucket, modelContextWindow);

            return LlmModelStatusResponse.builder()
                    .modelId(modelId)
                    .loaded(true)
                    .memoryUsageMb(memoryUsage)
                    .kvCacheType(this.kvCacheType)
                    .decoderPath(modelPath)
                    .maxContextLength(effectiveExecutionContext(
                            decoderConfig.getMaxContextLength(), modelContextWindow, kvBucket))
                    .kvBucket(kvBucket)
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
    public synchronized LlmModelStatusResponse unloadModel() {
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
        currentKvBucket = 0;
        currentModelContextWindow = 0;
        currentHiddenSize = 0;
        if (pipeline != null) {
            pipeline.close();
            log.info("Unloaded LLM model: {}", modelId);
        }
    }

    /**
     * Get the current model status.
     */
    public synchronized LlmModelStatusResponse getStatus() {
        String modelId = currentModelId.get();
        GenerationPipeline pipeline = currentPipeline.get();
        boolean loaded = pipeline != null && modelId != null;
        int effectiveMaxContext = loaded
                ? effectiveExecutionContext(
                        decoderConfig.getMaxContextLength(),
                        currentModelContextWindow,
                        currentKvBucket)
                : 0;

        return LlmModelStatusResponse.builder()
                .modelId(modelId)
                .loaded(loaded)
                .memoryUsageMb(loaded ? estimateMemoryUsageMb(null) : 0)
                .kvCacheType(loaded ? this.kvCacheType : null)
                .decoderPath(loaded ? this.currentDecoderPath : null)
                .maxContextLength(effectiveMaxContext)
                .kvBucket(loaded ? this.currentKvBucket : 0)
                .message(loaded ? "Model ready" : "No model loaded")
                .build();
    }

    /**
     * Capture the effective, read-only execution configuration for one request. This uses
     * the same preset/default resolution and stop-sequence fallback as generation itself,
     * while the service monitor prevents a concurrent load or configuration update from
     * producing a torn snapshot.
     */
    public synchronized ExecutionConfigurationSnapshot snapshotExecutionConfiguration(
            LlmGenerateRequest request) {
        SamplingConfig sampling = buildSamplingConfig(request, currentSamplingConfig.get());

        Map<String, Object> resolvedSampling = new LinkedHashMap<>();
        resolvedSampling.put("temperature", sampling.getTemperature());
        resolvedSampling.put("topK", sampling.getTopK());
        resolvedSampling.put("topP", sampling.getTopP());
        resolvedSampling.put("repetitionPenalty", sampling.getRepetitionPenalty());
        resolvedSampling.put("doSample", sampling.isDoSample());
        resolvedSampling.put("maxNewTokens", sampling.getMaxNewTokens());
        resolvedSampling.put("minNewTokens", sampling.getMinNewTokens());
        resolvedSampling.put("frequencyPenalty", sampling.getFrequencyPenalty());
        resolvedSampling.put("presencePenalty", sampling.getPresencePenalty());
        resolvedSampling.put("seed", sampling.getSeed());
        resolvedSampling.put("eosTokenId", sampling.getEosTokenId());
        resolvedSampling.put("padTokenId", sampling.getPadTokenId());

        DecoderConfigRequest decoder = this.decoderConfig;
        Map<String, Object> decoderSnapshot = new LinkedHashMap<>();
        decoderSnapshot.put("eosTokenId", decoder.getEosTokenId());
        decoderSnapshot.put("maxContextLength", decoder.getMaxContextLength());
        decoderSnapshot.put("minNewTokens", decoder.getMinNewTokens());
        decoderSnapshot.put("stopSequences", immutableList(decoder.getStopSequences()));
        decoderSnapshot.put("seed", decoder.getSeed());
        decoderSnapshot.put("frequencyPenalty", decoder.getFrequencyPenalty());
        decoderSnapshot.put("presencePenalty", decoder.getPresencePenalty());
        decoderSnapshot.put("numHeads", decoder.getNumHeads());
        decoderSnapshot.put("headDim", decoder.getHeadDim());
        decoderSnapshot.put("numKvLayers", decoder.getNumKvLayers());

        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("maxTokens", request.getMaxTokens());
        requested.put("minTokens", request.getMinTokens());
        requested.put("frequencyPenalty", request.getFrequencyPenalty());
        requested.put("presencePenalty", request.getPresencePenalty());
        requested.put("temperature", request.getTemperature());
        requested.put("topK", request.getTopK());
        requested.put("topP", request.getTopP());
        requested.put("repetitionPenalty", request.getRepetitionPenalty());
        requested.put("doSample", request.isDoSample());
        requested.put("presetName", request.getPresetName());
        requested.put("seed", request.getSeed());
        requested.put("stopSequences", immutableList(request.getStopSequences()));

        SpeculativeDecodingConfig speculative = this.speculativeConfig;
        Map<String, Object> speculativeSnapshot = new LinkedHashMap<>();
        speculativeSnapshot.put("enabled", speculative.isEnabled());
        speculativeSnapshot.put("ngramSize", speculative.getNgramSize());
        speculativeSnapshot.put("maxSpeculativeTokens", speculative.getMaxSpeculativeTokens());
        speculativeSnapshot.put("useDraftModel", speculative.isUseDraftModel());
        speculativeSnapshot.put("draftModelId", speculative.getDraftModelId());

        return new ExecutionConfigurationSnapshot(
                currentModelId.get(), currentDecoderPath,
                resolvedSampling, effectiveStopSequences(request),
                kvCacheType, currentKvBucket,
                effectiveExecutionContext(decoder.getMaxContextLength(),
                        currentModelContextWindow, currentKvBucket),
                currentModelContextWindow, currentHiddenSize, decoderSnapshot,
                requested, speculativeSnapshot);
    }

    // ==================== Text Generation ====================

    /**
     * Generate text synchronously.
     *
     * @param request generation parameters
     * @return generation response with metrics
     */
    public synchronized LlmGenerateResponse generate(LlmGenerateRequest request) {
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
            SamplingConfig requestSampling = buildSamplingConfig(
                    request, currentSamplingConfig.get());

            GenerationResult result = generateWithOptionalContinuation(
                    pipeline, request.getPrompt(), maxTokens, requestSampling);

            long totalTime = System.currentTimeMillis() - startTime;
            String generatedText = result.getText();

            // Apply stop sequences if configured
            List<String> stopSequences = effectiveStopSequences(request);
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
    public synchronized LlmGenerateResponse generateStreaming(LlmGenerateRequest request, Consumer<String> tokenCallback) {
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
            SamplingConfig requestSampling = buildSamplingConfig(
                    request, currentSamplingConfig.get());
            SamplingConfig previousSampling = pipeline.getSamplingConfig();

            pipeline.setSamplingConfig(requestSampling);
            try {
                pipeline.generateStream(
                        request.getPrompt(),
                        maxTokens,
                        token -> {
                            fullText.append(token);
                            tokenCallback.accept(token);
                        }
                );
            } finally {
                pipeline.setSamplingConfig(previousSampling);
            }

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
    public synchronized DecoderConfigRequest updateDecoderConfig(DecoderConfigRequest config) {
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
    public synchronized DecoderConfigRequest getDecoderConfig() {
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
    public synchronized SpeculativeDecodingConfig updateSpeculativeConfig(SpeculativeDecodingConfig config) {
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
    public synchronized SpeculativeDecodingConfig getSpeculativeConfig() {
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
     * Generate through retained in-graph KV when the requested output is larger than one
     * bounded decode chunk. The session owns the total output ceiling; continuation calls
     * only divide that ceiling and never re-prefill or silently increase it.
     */
    private GenerationResult generateWithOptionalContinuation(
            GenerationPipeline pipeline,
            String prompt,
            int maxTokens,
            SamplingConfig requestSampling) {
        int chunkTokens = continuationChunkTokens(maxTokens);
        boolean supportsContinuation = currentDecoderPath != null
                && currentDecoderPath.toLowerCase(Locale.ROOT).endsWith(".gguf");
        if (!supportsContinuation || maxTokens <= chunkTokens) {
            return pipeline.generate(prompt, maxTokens, requestSampling);
        }

        SamplingConfig previousSampling = pipeline.getSamplingConfig();
        pipeline.setSamplingConfig(requestSampling);
        long startedAt = System.currentTimeMillis();
        try (GenerationPipeline.GenerationSession session =
                     pipeline.startSession(prompt, maxTokens)) {
            int firstBudget = Math.min(chunkTokens, session.getRemainingCapacity());
            GenerationResult first = session.generate(firstBudget);
            GenerationResult last = first;
            int chunks = 1;

            while (last.isTruncated()
                    && !session.isEosReached()
                    && session.getRemainingCapacity() > 0) {
                int nextBudget = Math.min(chunkTokens, session.getRemainingCapacity());
                last = session.continueGeneration(nextBudget);
                chunks++;
            }

            int[] allTokens = session.getAllTokens();
            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.info("Completed retained-KV generation in {} chunk(s): generated={}, finishReason={}, "
                            + "remainingCapacity={}",
                    chunks, allTokens.length, last.getFinishReason(), session.getRemainingCapacity());
            return GenerationResult.builder()
                    .text(session.getFullText())
                    .tokenIds(allTokens)
                    .generatedTokenCount(allTokens.length)
                    .promptTokenCount(first.getPromptTokenCount())
                    .totalTokenCount(first.getPromptTokenCount() + allTokens.length)
                    .finishReason(last.getFinishReason())
                    .firstTokenLatencyMs(first.getFirstTokenLatencyMs())
                    .generationTimeMs(elapsedMs)
                    .tokensPerSecond(elapsedMs > 0 ? allTokens.length * 1000.0 / elapsedMs : 0.0)
                    .decodeTokensPerSecond(last.getDecodeTokensPerSecond())
                    .steadyStateTokensPerSecond(last.getSteadyStateTokensPerSecond())
                    .lateSteadyStateTokensPerSecond(last.getLateSteadyStateTokensPerSecond())
                    .sessionId(session.getSessionId())
                    .build();
        } finally {
            pipeline.setSamplingConfig(previousSampling);
        }
    }

    static int continuationChunkTokens(int maxTokens) {
        String raw = System.getProperty(CONTINUATION_CHUNK_TOKENS_PROP);
        int configured = CONTINUATION_CHUNK_TOKENS_DEFAULT;
        if (raw != null && !raw.isBlank()) {
            try {
                configured = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        CONTINUATION_CHUNK_TOKENS_PROP + " must be a positive integer: " + raw, e);
            }
        }
        return validateContinuationChunkTokens(configured, maxTokens);
    }

    static int validateContinuationChunkTokens(int configured, int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive: " + maxTokens);
        }
        if (configured <= 0) {
            throw new IllegalArgumentException(
                    CONTINUATION_CHUNK_TOKENS_PROP + " must be positive: " + configured);
        }
        return Math.min(configured, maxTokens);
    }

    /**
     * Build SamplingConfig from a generate request, respecting presets while retaining
     * tokenizer-specific EOS/pad IDs and other pipeline defaults.
     */
    static SamplingConfig buildSamplingConfig(
            LlmGenerateRequest request,
            SamplingConfig defaults) {
        Objects.requireNonNull(request, "request");
        SamplingConfig base = defaults != null ? defaults : SamplingConfig.defaultConfig();
        int maxTokens = request.getMaxTokens();
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive: " + maxTokens);
        }

        Long requestSeed = request.getSeed() >= 0
                ? Long.valueOf(request.getSeed())
                : base.getSeed();
        SamplingConfig.SamplingConfigBuilder builder = base.toBuilder()
                .maxNewTokens(maxTokens)
                .minNewTokens(Math.min(Math.max(0, request.getMinTokens()), maxTokens))
                .frequencyPenalty(request.getFrequencyPenalty())
                .presencePenalty(request.getPresencePenalty())
                .repetitionPenalty(request.getRepetitionPenalty())
                .seed(requestSeed);

        if (request.getPresetName() != null) {
            switch (request.getPresetName().toLowerCase(Locale.ROOT)) {
                case "greedy":
                    return builder.doSample(false).temperature(0.0).build();
                case "default":
                    return builder.temperature(0.7).topP(0.9).doSample(true).build();
                case "creative":
                    return builder.temperature(0.9).topK(50).topP(0.95).doSample(true).build();
                case "precise":
                    return builder.temperature(0.3).topP(0.85).doSample(true).build();
                default:
                    log.warn("Unknown sampling preset '{}', using explicit parameters",
                            request.getPresetName());
                    break;
            }
        }

        return builder
                .temperature(request.getTemperature())
                .topK(request.getTopK())
                .topP(request.getTopP())
                .doSample(request.isDoSample() && request.getTemperature() > 0.0)
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

    private List<String> effectiveStopSequences(LlmGenerateRequest request) {
        List<String> stopSequences = request.getStopSequences();
        if (stopSequences == null || stopSequences.isEmpty()) {
            stopSequences = decoderConfig.getStopSequences();
        }
        return immutableList(stopSequences);
    }

    private static <T> List<T> immutableList(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Return the context window this loaded lane can actually execute. A declared model
     * window or caller override is never allowed to exceed the allocated KV ceiling.
     */
    static int effectiveExecutionContext(
            int configuredContext,
            int modelContext,
            int kvBucket) {
        int effective = Integer.MAX_VALUE;
        if (configuredContext > 0) {
            effective = Math.min(effective, configuredContext);
        }
        if (modelContext > 0) {
            effective = Math.min(effective, modelContext);
        }
        if (kvBucket > 0) {
            effective = Math.min(effective, kvBucket);
        }
        return effective == Integer.MAX_VALUE ? 0 : Math.max(1, effective);
    }

    /**
     * Largest configured bucket that does not exceed a hard resource cap.
     */
    static int floorBucketFor(int hardCap, int[] sortedBuckets) {
        if (hardCap <= 0) {
            throw new IllegalArgumentException("hardCap must be positive: " + hardCap);
        }
        int selected = 0;
        if (sortedBuckets != null) {
            for (int bucket : sortedBuckets) {
                if (bucket > 0 && bucket <= hardCap) {
                    selected = Math.max(selected, bucket);
                }
            }
        }
        return selected > 0 ? selected : hardCap;
    }

    /**
     * Parse the comma-separated sequence-bucket system property into a sorted int array.
     * Falls back to {@link #SEQ_BUCKETS_DEFAULT} when the property is absent or unparseable.
     */
    private static int[] parseSeqBuckets() {
        String raw = System.getProperty(SEQ_BUCKETS_PROP, SEQ_BUCKETS_DEFAULT);
        String[] parts = raw.split(",");
        List<Integer> list = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    int v = Integer.parseInt(trimmed);
                    if (v > 0) {
                        list.add(v);
                    }
                } catch (NumberFormatException ignored) {
                    log.warn("Ignoring invalid seq bucket value '{}' in system property {}", trimmed, SEQ_BUCKETS_PROP);
                }
            }
        }
        if (list.isEmpty()) {
            // Absolute fallback if the property was set to something unusable
            return new int[]{256, 512, 1024, 2048, 4096};
        }
        Collections.sort(list);
        int[] result = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    /**
     * Compute the KV-cache / DSP-plan position bucket for this model using
     * {@link InferenceBatchPlanner#bucketFor} and then clamp it by an upper bound
     * derived from the native-memory ceiling via
     * {@link InferenceBatchPlanner#estimateMaxBatchTokens}.
     *
     * <p>The bucket determines the {@code maxKvCacheLength} passed to
     * {@link GenerationPipelineConfig}, which (when STATIC KV cache is used)
     * pre-allocates the KV buffer at exactly that length. Using a small set of
     * buckets rather than an arbitrary per-request value means ND4J's
     * DynamicShapePlanExecutor reuses a single execution plan per bucket
     * instead of building a new one for every request length.</p>
     *
     * @param modelContextWindow the model's max_position_embeddings (hard cap)
     * @param hiddenSize         the model's hidden size; 0 if unknown (skips memory estimate)
     * @return the effective KV bucket to pass as maxKvCacheLength
     */
    private int computeKvBucket(int modelContextWindow, int hiddenSize) {
        int[] seqBuckets = parseSeqBuckets();
        double safetyFraction = parseDoubleProperty(SAFETY_FRACTION_PROP, SAFETY_FRACTION_DEFAULT);
        double activationFactor = parseDoubleProperty(ACTIVATION_FACTOR_PROP, ACTIVATION_FACTOR_DEFAULT);

        // The largest configured bucket is an explicit executable-context ceiling.
        // A model may declare 128k, but advertising or allocating that much is unsafe when this
        // lane was configured only for 4k. Operators can opt into larger contexts by extending
        // kompile.llm.seqBuckets.
        int configuredCeiling = seqBuckets[seqBuckets.length - 1];
        int executionHardCap = Math.max(1, Math.min(modelContextWindow, configuredCeiling));
        int bucketedContext = InferenceBatchPlanner.bucketFor(
                executionHardCap, seqBuckets, executionHardCap);

        // Clamp by native-memory ceiling if both hiddenSize > 0 and the
        // org.bytedeco.javacpp.maxphysicalbytes property is set. Use a floor bucket:
        // rounding upward would violate the memory cap it is meant to enforce.
        int memoryClamped = bucketedContext;
        if (hiddenSize > 0) {
            String maxPhysicalProp = System.getProperty(ND4JSystemProperties.JAVACPP_MEMORY_MAX_PHYSICAL_BYTES);
            long maxPhysicalBytes = InferenceBatchPlanner.parseByteSize(maxPhysicalProp);
            if (maxPhysicalBytes > 0) {
                long memTokenBudget = InferenceBatchPlanner.estimateMaxBatchTokens(
                        maxPhysicalBytes, hiddenSize, 4, safetyFraction, activationFactor, executionHardCap);
                int memCapContext = (int) Math.max(
                        1L, Math.min(memTokenBudget, executionHardCap));
                int memCappedBucket = floorBucketFor(memCapContext, seqBuckets);
                memoryClamped = Math.min(bucketedContext, memCappedBucket);
                if (memoryClamped < bucketedContext) {
                    log.info("LLM KV bucket memory-clamped: {} → {} (maxphysicalbytes={}, hiddenSize={}, safety={}, activationFactor={})",
                            bucketedContext, memoryClamped, maxPhysicalBytes, hiddenSize, safetyFraction, activationFactor);
                }
            } else if (maxPhysicalProp != null && !maxPhysicalProp.isBlank()) {
                log.warn("Could not parse org.bytedeco.javacpp.maxphysicalbytes='{}', skipping memory clamp", maxPhysicalProp);
            }
        }

        log.info("LLM KV bucket selected: {} (declaredContext={}, configuredCeiling={}, hiddenSize={}, seqBuckets={})",
                memoryClamped, modelContextWindow, configuredCeiling, hiddenSize, Arrays.toString(seqBuckets));
        return memoryClamped;
    }

    /**
     * Parse a double system property, returning the default value on parse failure or absence.
     */
    private static double parseDoubleProperty(String key, double defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Could not parse system property {}='{}', using default {}", key, raw, defaultValue);
            return defaultValue;
        }
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

    /** Immutable provenance view of the settings that can affect one generation. */
    public record ExecutionConfigurationSnapshot(
            String loadedModelId,
            String loadedDecoderPath,
            Map<String, Object> resolvedSamplingConfig,
            List<String> effectiveStopSequences,
            String kvCacheType,
            int kvBucket,
            int effectiveContextLength,
            int modelContextWindow,
            int hiddenSize,
            Map<String, Object> decoderConfiguration,
            Map<String, Object> requestedSettings,
            Map<String, Object> speculativeConfiguration) {

        public ExecutionConfigurationSnapshot {
            resolvedSamplingConfig = immutableMap(resolvedSamplingConfig);
            effectiveStopSequences = immutableList(effectiveStopSequences);
            decoderConfiguration = immutableMap(decoderConfiguration);
            requestedSettings = immutableMap(requestedSettings);
            speculativeConfiguration = immutableMap(speculativeConfiguration);
        }

        public Map<String, Object> asEvidence() {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("loadedModelId", loadedModelId);
            // Do not expose an absolute host path; durable generation verifies it separately
            // against the registry artifact before and after execution.
            evidence.put("resolvedSamplingConfig", resolvedSamplingConfig);
            evidence.put("effectiveStopSequences", effectiveStopSequences);
            evidence.put("kvCacheType", kvCacheType);
            evidence.put("kvBucket", kvBucket);
            evidence.put("effectiveContextLength", effectiveContextLength);
            evidence.put("modelContextWindow", modelContextWindow);
            evidence.put("hiddenSize", hiddenSize);
            evidence.put("decoderConfiguration", decoderConfiguration);
            evidence.put("requestedSettings", requestedSettings);
            evidence.put("speculativeConfiguration", speculativeConfiguration);
            return immutableMap(evidence);
        }
    }
}
