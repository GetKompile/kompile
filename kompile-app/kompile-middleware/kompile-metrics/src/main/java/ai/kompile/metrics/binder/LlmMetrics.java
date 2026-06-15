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

package ai.kompile.metrics.binder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.TimeUnit;

/**
 * Metrics for LLM (language model) operations.
 *
 * Counters:
 * <ul>
 *   <li>{@code kompile.llm.request.count} – total LLM requests, tagged by provider</li>
 *   <li>{@code kompile.llm.request.errors} – failed LLM requests</li>
 *   <li>{@code kompile.llm.tokens.input} – total input tokens sent</li>
 *   <li>{@code kompile.llm.tokens.output} – total output tokens received</li>
 * </ul>
 *
 * Timers:
 * <ul>
 *   <li>{@code kompile.llm.request.time} – LLM request latency</li>
 * </ul>
 */
public class LlmMetrics {

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void bindMetrics() {
        // Pre-register base meters so they appear in /actuator/metrics even before first call
        Counter.builder("kompile.llm.request.count")
                .description("Total LLM generation requests").register(registry);
        Counter.builder("kompile.llm.request.errors")
                .description("Failed LLM generation requests").register(registry);
        Counter.builder("kompile.llm.tokens.input")
                .description("Total input tokens sent to LLM").register(registry);
        Counter.builder("kompile.llm.tokens.output")
                .description("Total output tokens received from LLM").register(registry);
        Timer.builder("kompile.llm.request.time")
                .description("LLM request latency").register(registry);
    }

    /**
     * Record a successful LLM request.
     *
     * @param provider    LLM provider name (e.g., "openai", "anthropic", "samediff")
     * @param model       model identifier (e.g., "gpt-4", "claude-3-sonnet")
     * @param durationMs  request duration in milliseconds
     * @param inputTokens number of input tokens (0 if unknown)
     * @param outputTokens number of output tokens (0 if unknown)
     */
    public void recordRequest(String provider, String model, long durationMs,
                              long inputTokens, long outputTokens) {
        registry.counter("kompile.llm.request.count",
                "provider", provider, "model", model).increment();
        registry.timer("kompile.llm.request.time",
                "provider", provider, "model", model).record(durationMs, TimeUnit.MILLISECONDS);
        if (inputTokens > 0) {
            registry.counter("kompile.llm.tokens.input",
                    "provider", provider, "model", model).increment(inputTokens);
        }
        if (outputTokens > 0) {
            registry.counter("kompile.llm.tokens.output",
                    "provider", provider, "model", model).increment(outputTokens);
        }
    }

    /**
     * Record a failed LLM request.
     *
     * @param provider LLM provider name
     * @param model    model identifier
     * @param error    error class name
     */
    public void recordError(String provider, String model, String error) {
        registry.counter("kompile.llm.request.errors",
                "provider", provider, "model", model, "error", error).increment();
    }
}
