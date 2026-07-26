/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.serving.openai;

import ai.kompile.serving.openai.dto.ChatCompletionRequest;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelBridgeTest {

    private final ModelBridge bridge = new ModelBridge(null, null, "test-model");

    @Test
    void requestOverridesAreActuallyRepresentedWithoutLosingTokenizerDefaults() {
        SamplingConfig defaults = SamplingConfig.builder()
                .temperature(0.7)
                .doSample(true)
                .topK(40)
                .topP(0.9)
                .maxNewTokens(256)
                .repetitionPenalty(1.15)
                .eosTokenId(151645)
                .padTokenId(151643)
                .build();
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .temperature(0.0)
                .topP(0.8)
                .maxTokens(1024)
                .frequencyPenalty(0.25)
                .presencePenalty(0.35)
                .seed(123L)
                .build();

        SamplingConfig actual = bridge.buildSamplingConfig(request, defaults);

        assertEquals(0.0, actual.getTemperature());
        assertFalse(actual.isDoSample());
        assertEquals(0.8, actual.getTopP());
        assertEquals(40, actual.getTopK());
        assertEquals(1024, actual.getMaxNewTokens());
        assertEquals(0.25, actual.getFrequencyPenalty());
        assertEquals(0.35, actual.getPresencePenalty());
        assertEquals(1.15, actual.getRepetitionPenalty());
        assertEquals(123L, actual.getSeed());
        assertEquals(151645, actual.getEosTokenId());
        assertEquals(151643, actual.getPadTokenId());
    }

    @Test
    void omittedRequestFieldsPreservePipelineDefaults() {
        SamplingConfig defaults = SamplingConfig.builder()
                .temperature(0.55)
                .doSample(true)
                .topP(0.87)
                .topK(17)
                .maxNewTokens(333)
                .frequencyPenalty(0.1)
                .presencePenalty(0.2)
                .seed(9L)
                .eosTokenId(2)
                .padTokenId(0)
                .build();

        SamplingConfig actual = bridge.buildSamplingConfig(
                ChatCompletionRequest.builder().build(), defaults);

        assertEquals(0.55, actual.getTemperature());
        assertTrue(actual.isDoSample());
        assertEquals(0.87, actual.getTopP());
        assertEquals(17, actual.getTopK());
        assertEquals(333, actual.getMaxNewTokens());
        assertEquals(0.1, actual.getFrequencyPenalty());
        assertEquals(0.2, actual.getPresencePenalty());
        assertEquals(9L, actual.getSeed());
        assertEquals(2, actual.getEosTokenId());
        assertEquals(0, actual.getPadTokenId());
    }
}
