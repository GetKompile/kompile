/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.staging.execution;

import ai.kompile.staging.web.dto.LlmGenerateRequest;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmExecutionServiceTest {

    @Test
    void requestSamplingHonorsGreedyExtractionAndPreservesTokenizerDefaults() {
        SamplingConfig defaults = SamplingConfig.builder()
                .temperature(0.8)
                .doSample(true)
                .eosTokenId(151645)
                .padTokenId(151643)
                .seed(77L)
                .build();
        LlmGenerateRequest request = LlmGenerateRequest.builder()
                .maxTokens(1024)
                .temperature(0.0)
                .doSample(true)
                .topK(12)
                .topP(0.75)
                .repetitionPenalty(1.1)
                .frequencyPenalty(0.2)
                .presencePenalty(0.3)
                .minTokens(8)
                .seed(42L)
                .build();

        SamplingConfig actual =
                LlmExecutionService.buildSamplingConfig(request, defaults);

        assertEquals(0.0, actual.getTemperature());
        assertFalse(actual.isDoSample(), "temperature zero must force deterministic decoding");
        assertEquals(1024, actual.getMaxNewTokens());
        assertEquals(8, actual.getMinNewTokens());
        assertEquals(12, actual.getTopK());
        assertEquals(0.75, actual.getTopP());
        assertEquals(1.1, actual.getRepetitionPenalty());
        assertEquals(0.2, actual.getFrequencyPenalty());
        assertEquals(0.3, actual.getPresencePenalty());
        assertEquals(42L, actual.getSeed());
        assertEquals(151645, actual.getEosTokenId());
        assertEquals(151643, actual.getPadTokenId());
    }

    @Test
    void greedyPresetRetainsPipelineTokenIds() {
        SamplingConfig defaults = SamplingConfig.builder()
                .eosTokenId(2)
                .padTokenId(0)
                .build();
        LlmGenerateRequest request = LlmGenerateRequest.builder()
                .presetName("greedy")
                .maxTokens(64)
                .build();

        SamplingConfig actual =
                LlmExecutionService.buildSamplingConfig(request, defaults);

        assertFalse(actual.isDoSample());
        assertEquals(0.0, actual.getTemperature());
        assertEquals(2, actual.getEosTokenId());
        assertEquals(0, actual.getPadTokenId());
    }

    @Test
    void executableContextNeverExceedsAnyActiveCeiling() {
        assertEquals(4096,
                LlmExecutionService.effectiveExecutionContext(0, 131072, 4096));
        assertEquals(2048,
                LlmExecutionService.effectiveExecutionContext(2048, 131072, 4096));
        assertEquals(1024,
                LlmExecutionService.effectiveExecutionContext(8192, 1024, 4096));
        assertEquals(0,
                LlmExecutionService.effectiveExecutionContext(0, 0, 0));
    }

    @Test
    void memoryClampUsesFloorBucket() {
        int[] buckets = {256, 512, 1024, 2048, 4096};

        assertEquals(2048, LlmExecutionService.floorBucketFor(3000, buckets));
        assertEquals(128, LlmExecutionService.floorBucketFor(128, buckets));
        assertThrows(IllegalArgumentException.class,
                () -> LlmExecutionService.floorBucketFor(0, buckets));
    }

    @Test
    void continuationChunkIsBoundedByTotalRequestCapacity() {
        assertEquals(384,
                LlmExecutionService.validateContinuationChunkTokens(384, 768));
        assertEquals(128,
                LlmExecutionService.validateContinuationChunkTokens(384, 128));
        assertThrows(IllegalArgumentException.class,
                () -> LlmExecutionService.validateContinuationChunkTokens(0, 128));
        assertThrows(IllegalArgumentException.class,
                () -> LlmExecutionService.validateContinuationChunkTokens(64, 0));
    }
}
