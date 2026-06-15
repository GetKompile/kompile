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

package ai.kompile.modelmanager.llm;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for LLM text generation, analogous to {@link ai.kompile.modelmanager.vlm.VlmExtractionConfig}.
 *
 * <p>Provides presets for common generation scenarios and fine-grained control
 * over sampling parameters, tool calling, and context management.</p>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Use a preset
 * LlmGenerationConfig config = LlmGenerationConfig.forChat();
 *
 * // Customize
 * LlmGenerationConfig config = LlmGenerationConfig.builder()
 *     .maxNewTokens(512)
 *     .temperature(0.7)
 *     .topK(50)
 *     .topP(0.9)
 *     .enableToolCalling(true)
 *     .build();
 * }</pre>
 */
public class LlmGenerationConfig {

    private final int maxNewTokens;
    private final double temperature;
    private final int topK;
    private final double topP;
    private final double repetitionPenalty;
    private final String samplingStrategy;
    private final boolean enableToolCalling;
    private final String toolCallFormat;
    private final String systemPrompt;
    private final Map<String, Object> modelOverrides;

    private LlmGenerationConfig(Builder builder) {
        this.maxNewTokens = builder.maxNewTokens;
        this.temperature = builder.temperature;
        this.topK = builder.topK;
        this.topP = builder.topP;
        this.repetitionPenalty = builder.repetitionPenalty;
        this.samplingStrategy = builder.samplingStrategy;
        this.enableToolCalling = builder.enableToolCalling;
        this.toolCallFormat = builder.toolCallFormat;
        this.systemPrompt = builder.systemPrompt;
        this.modelOverrides = builder.modelOverrides != null
                ? Collections.unmodifiableMap(new HashMap<>(builder.modelOverrides))
                : Collections.emptyMap();
    }

    // --- Presets ---

    /** Greedy decoding - deterministic output, good for factual tasks. */
    public static LlmGenerationConfig forGreedy() {
        return builder()
                .maxNewTokens(256)
                .temperature(0.0)
                .topK(1)
                .samplingStrategy("greedy")
                .build();
    }

    /** Chat preset - balanced creativity and coherence. */
    public static LlmGenerationConfig forChat() {
        return builder()
                .maxNewTokens(512)
                .temperature(0.7)
                .topK(50)
                .topP(0.9)
                .samplingStrategy("top_p")
                .build();
    }

    /** Creative writing preset - high temperature for diverse outputs. */
    public static LlmGenerationConfig forCreativeWriting() {
        return builder()
                .maxNewTokens(1024)
                .temperature(1.0)
                .topK(100)
                .topP(0.95)
                .repetitionPenalty(1.2)
                .samplingStrategy("top_p")
                .build();
    }

    /** Code generation preset - lower temperature for precise output. */
    public static LlmGenerationConfig forCodeGeneration() {
        return builder()
                .maxNewTokens(1024)
                .temperature(0.2)
                .topK(10)
                .topP(0.95)
                .samplingStrategy("top_p")
                .build();
    }

    /** Tool calling preset - structured output with tool call support. */
    public static LlmGenerationConfig forToolCalling() {
        return builder()
                .maxNewTokens(512)
                .temperature(0.0)
                .topK(1)
                .samplingStrategy("greedy")
                .enableToolCalling(true)
                .toolCallFormat("json")
                .build();
    }

    /** Comprehensive preset - all features enabled, large context. */
    public static LlmGenerationConfig comprehensive() {
        return builder()
                .maxNewTokens(2048)
                .temperature(0.7)
                .topK(50)
                .topP(0.9)
                .repetitionPenalty(1.1)
                .samplingStrategy("top_p")
                .enableToolCalling(true)
                .toolCallFormat("json")
                .build();
    }

    // --- Getters ---

    public int getMaxNewTokens() { return maxNewTokens; }
    public double getTemperature() { return temperature; }
    public int getTopK() { return topK; }
    public double getTopP() { return topP; }
    public double getRepetitionPenalty() { return repetitionPenalty; }
    public String getSamplingStrategy() { return samplingStrategy; }
    public boolean isEnableToolCalling() { return enableToolCalling; }
    public String getToolCallFormat() { return toolCallFormat; }
    public String getSystemPrompt() { return systemPrompt; }
    public Map<String, Object> getModelOverrides() { return modelOverrides; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxNewTokens = 256;
        private double temperature = 0.7;
        private int topK = 50;
        private double topP = 0.9;
        private double repetitionPenalty = 1.0;
        private String samplingStrategy = "top_p";
        private boolean enableToolCalling = false;
        private String toolCallFormat = "json";
        private String systemPrompt;
        private Map<String, Object> modelOverrides;

        public Builder maxNewTokens(int maxNewTokens) { this.maxNewTokens = maxNewTokens; return this; }
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }
        public Builder topK(int topK) { this.topK = topK; return this; }
        public Builder topP(double topP) { this.topP = topP; return this; }
        public Builder repetitionPenalty(double repetitionPenalty) { this.repetitionPenalty = repetitionPenalty; return this; }
        public Builder samplingStrategy(String samplingStrategy) { this.samplingStrategy = samplingStrategy; return this; }
        public Builder enableToolCalling(boolean enableToolCalling) { this.enableToolCalling = enableToolCalling; return this; }
        public Builder toolCallFormat(String toolCallFormat) { this.toolCallFormat = toolCallFormat; return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder modelOverrides(Map<String, Object> modelOverrides) { this.modelOverrides = modelOverrides; return this; }

        public LlmGenerationConfig build() {
            return new LlmGenerationConfig(this);
        }
    }
}
