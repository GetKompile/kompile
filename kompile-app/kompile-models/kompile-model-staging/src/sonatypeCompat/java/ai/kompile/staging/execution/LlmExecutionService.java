/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.staging.execution;

import ai.kompile.staging.web.dto.BatchGenerateRequest;
import ai.kompile.staging.web.dto.BatchGenerateResponse;
import ai.kompile.staging.web.dto.ChatRequest;
import ai.kompile.staging.web.dto.ChatResponse;
import ai.kompile.staging.web.dto.DecoderConfigRequest;
import ai.kompile.staging.web.dto.LlmGenerateRequest;
import ai.kompile.staging.web.dto.LlmGenerateResponse;
import ai.kompile.staging.web.dto.LlmModelStatusResponse;
import ai.kompile.staging.web.dto.PipelineInfoResponse;
import ai.kompile.staging.web.dto.SpeculativeDecodingConfig;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * Fail-closed LLM execution adapter for Sonatype snapshots that predate the
 * session and mutable-sampling GenerationPipeline APIs.
 */
@Service
public class LlmExecutionService {
    private static final String UNAVAILABLE =
            "Local LLM execution is unavailable because the configured DL4J Maven "
                    + "repository predates the required GenerationPipeline API";

    private volatile DecoderConfigRequest decoderConfig = DecoderConfigRequest.builder().build();
    private volatile SpeculativeDecodingConfig speculativeConfig =
            SpeculativeDecodingConfig.builder().build();

    public synchronized LlmModelStatusResponse loadModel(
            String modelId,
            String modelPath,
            String kvCacheType) {
        return status(modelId, "Model was not loaded. " + UNAVAILABLE);
    }

    public synchronized LlmModelStatusResponse unloadModel() {
        return status(null, "No model was loaded. " + UNAVAILABLE);
    }

    public LlmModelStatusResponse getStatus() {
        return status(null, UNAVAILABLE);
    }

    public synchronized LlmGenerateResponse generate(LlmGenerateRequest request) {
        return generationUnavailable();
    }

    public synchronized LlmGenerateResponse generateStreaming(
            LlmGenerateRequest request,
            Consumer<String> tokenCallback) {
        return generationUnavailable();
    }

    public ChatResponse chat(
            ChatRequest request,
            ChatTemplateService chatTemplateService) {
        return chatUnavailable();
    }

    public ChatResponse chatStreaming(
            ChatRequest request,
            ChatTemplateService chatTemplateService,
            Consumer<String> tokenCallback) {
        return chatUnavailable();
    }

    public BatchGenerateResponse generateBatch(BatchGenerateRequest request) {
        return BatchGenerateResponse.builder()
                .results(List.of())
                .successCount(0)
                .errorCount(1)
                .totalTimeMs(0)
                .build();
    }

    public void cancelGeneration() {
    }

    public boolean isGenerating() {
        return false;
    }

    public DecoderConfigRequest updateDecoderConfig(DecoderConfigRequest config) {
        decoderConfig = config == null ? DecoderConfigRequest.builder().build() : config;
        return decoderConfig;
    }

    public DecoderConfigRequest getDecoderConfig() {
        return decoderConfig;
    }

    public PipelineInfoResponse getPipelineInfo() {
        return PipelineInfoResponse.builder()
                .loaded(false)
                .decoderConfig(decoderConfig)
                .speculativeConfig(speculativeConfig)
                .inputNames(List.of())
                .outputNames(List.of())
                .kvCacheKeyNames(List.of())
                .kvCacheValueNames(List.of())
                .currentSamplingConfig(Map.of())
                .message(UNAVAILABLE)
                .build();
    }

    public SpeculativeDecodingConfig updateSpeculativeConfig(
            SpeculativeDecodingConfig config) {
        speculativeConfig =
                config == null ? SpeculativeDecodingConfig.builder().build() : config;
        return speculativeConfig;
    }

    public SpeculativeDecodingConfig getSpeculativeConfig() {
        return speculativeConfig;
    }

    public List<Map<String, Object>> getSamplingPresets() {
        return List.of();
    }

    private static LlmModelStatusResponse status(String modelId, String message) {
        return LlmModelStatusResponse.builder()
                .modelId(modelId)
                .loaded(false)
                .memoryUsageMb(0)
                .kvCacheType("UNAVAILABLE")
                .message(message)
                .maxContextLength(0)
                .kvBucket(0)
                .build();
    }

    private static LlmGenerateResponse generationUnavailable() {
        return LlmGenerateResponse.builder()
                .generatedText("")
                .tokensPerSecond(0)
                .firstTokenLatencyMs(0)
                .totalTokens(0)
                .finishReason("unavailable")
                .totalTimeMs(0)
                .build();
    }

    private static ChatResponse chatUnavailable() {
        return ChatResponse.builder()
                .assistantMessage("")
                .formattedPrompt("")
                .tokensPerSecond(0)
                .totalTokens(0)
                .finishReason("unavailable")
                .totalTimeMs(0)
                .build();
    }
}
