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

package ai.kompile.core.llm;

import java.util.Map;

/**
 * Captures token generation throughput metrics for LLM and VLM inference.
 * Used across local models (SameDiff, DL4J, VLM pipelines) and hosted APIs
 * (Anthropic, OpenAI) to provide consistent performance reporting.
 */
public record GenerationMetrics(
        int promptTokens,
        int generatedTokens,
        long firstTokenLatencyMs,
        long totalGenerationMs,
        double tokensPerSecond,
        String modelId
) {

    /**
     * Compute metrics from raw timing data.
     */
    public static GenerationMetrics compute(
            int promptTokens,
            int generatedTokens,
            long firstTokenLatencyMs,
            long totalGenerationMs,
            String modelId
    ) {
        double tps = totalGenerationMs > 0
                ? (generatedTokens * 1000.0) / totalGenerationMs
                : 0.0;
        return new GenerationMetrics(
                promptTokens,
                generatedTokens,
                firstTokenLatencyMs,
                totalGenerationMs,
                tps,
                modelId
        );
    }

    /**
     * Create metrics for hosted API responses where only output tokens and
     * total duration are known (no first-token latency).
     */
    public static GenerationMetrics fromApiResponse(
            int outputTokens,
            long totalMs,
            String modelId
    ) {
        return compute(0, outputTokens, -1, totalMs, modelId);
    }

    /**
     * Convert to a map for inclusion in ParsedDocument metadata or SSE events.
     */
    public Map<String, Object> toMap() {
        return Map.of(
                "promptTokens", promptTokens,
                "generatedTokens", generatedTokens,
                "firstTokenLatencyMs", firstTokenLatencyMs,
                "totalGenerationMs", totalGenerationMs,
                "tokensPerSecond", Math.round(tokensPerSecond * 100.0) / 100.0,
                "modelId", modelId != null ? modelId : "unknown"
        );
    }
}
