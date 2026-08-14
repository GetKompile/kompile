/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package org.eclipse.deeplearning4j.audio.synthesis;

import java.nio.file.Path;
import java.util.Map;
import org.nd4j.linalg.api.buffer.DataType;

/**
 * Fail-closed builder bridge for Sonatype snapshots that do not publish the
 * reusable SameDiff waveform generator.
 */
public final class SameDiffWaveformGenerator implements AudioFileGenerator {
    private static final String UNAVAILABLE =
            "Audio synthesis is unavailable because the configured DL4J Maven "
                    + "repository does not publish the synthesis API";

    public enum TokenizerType {
        HUGGING_FACE,
        UTF8_BYTES
    }

    private SameDiffWaveformGenerator() {
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public GeneratedAudioFile generate(
            AudioSynthesisRequest request,
            Path outputDirectory) {
        throw new UnsupportedOperationException(UNAVAILABLE);
    }

    public static final class Builder {
        public Builder modelFile(Path value) { return this; }
        public Builder tokenizerType(TokenizerType value) { return this; }
        public Builder tokenizerFile(Path value) { return this; }
        public Builder tokenIdsInput(String value) { return this; }
        public Builder attentionMaskInput(String value) { return this; }
        public Builder tokenLengthsInput(String value) { return this; }
        public Builder waveformOutput(String value) { return this; }
        public Builder confidenceOutput(String value) { return this; }
        public Builder tokenDataType(DataType value) { return this; }
        public Builder addSpecialTokens(boolean value) { return this; }
        public Builder sampleRateHz(int value) { return this; }
        public Builder maxInputTokens(int value) { return this; }
        public Builder maxOutputSamples(long value) { return this; }
        public Builder voice(String value) { return this; }
        public Builder language(String value) { return this; }
        public Builder modelId(String value) { return this; }
        public Builder modelVersion(String value) { return this; }
        public Builder configurationVersion(String value) { return this; }
        public Builder defaultConfidence(double value) { return this; }
        public Builder configurationEvidence(Map<String, Object> value) { return this; }

        public SameDiffWaveformGenerator build() {
            return new SameDiffWaveformGenerator();
        }
    }
}
