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
 * Metrics for guardrail input/output validation.
 *
 * Counters:
 * <ul>
 *   <li>{@code kompile.guardrail.input.total} – total input validations</li>
 *   <li>{@code kompile.guardrail.input.passed} – input validations that passed</li>
 *   <li>{@code kompile.guardrail.input.blocked} – input validations that were blocked</li>
 *   <li>{@code kompile.guardrail.input.warned} – input validations that produced warnings</li>
 *   <li>{@code kompile.guardrail.output.total} – total output validations</li>
 *   <li>{@code kompile.guardrail.output.passed} – output validations that passed</li>
 *   <li>{@code kompile.guardrail.output.blocked} – output validations that were blocked</li>
 *   <li>{@code kompile.guardrail.output.warned} – output validations that produced warnings</li>
 * </ul>
 *
 * Timers:
 * <ul>
 *   <li>{@code kompile.guardrail.input.time} – input validation duration distribution</li>
 *   <li>{@code kompile.guardrail.output.time} – output validation duration distribution</li>
 * </ul>
 */
public class GuardrailMetrics {

    private final MeterRegistry registry;

    private Counter inputTotal;
    private Counter inputPassed;
    private Counter inputBlocked;
    private Counter inputWarned;
    private Counter outputTotal;
    private Counter outputPassed;
    private Counter outputBlocked;
    private Counter outputWarned;
    private Timer inputTimer;
    private Timer outputTimer;

    public GuardrailMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void bindMetrics() {
        inputTotal = Counter.builder("kompile.guardrail.input.total")
                .description("Total input validations").register(registry);
        inputPassed = Counter.builder("kompile.guardrail.input.passed")
                .description("Input validations that passed").register(registry);
        inputBlocked = Counter.builder("kompile.guardrail.input.blocked")
                .description("Input validations that were blocked").register(registry);
        inputWarned = Counter.builder("kompile.guardrail.input.warned")
                .description("Input validations that produced warnings").register(registry);

        outputTotal = Counter.builder("kompile.guardrail.output.total")
                .description("Total output validations").register(registry);
        outputPassed = Counter.builder("kompile.guardrail.output.passed")
                .description("Output validations that passed").register(registry);
        outputBlocked = Counter.builder("kompile.guardrail.output.blocked")
                .description("Output validations that were blocked").register(registry);
        outputWarned = Counter.builder("kompile.guardrail.output.warned")
                .description("Output validations that produced warnings").register(registry);

        inputTimer = Timer.builder("kompile.guardrail.input.time")
                .description("Input validation duration").register(registry);
        outputTimer = Timer.builder("kompile.guardrail.output.time")
                .description("Output validation duration").register(registry);
    }

    public void recordInputValidation(String result, long durationMs) {
        inputTotal.increment();
        switch (result) {
            case "passed" -> inputPassed.increment();
            case "blocked" -> inputBlocked.increment();
            case "warned" -> inputWarned.increment();
        }
        if (durationMs > 0) inputTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordOutputValidation(String result, long durationMs) {
        outputTotal.increment();
        switch (result) {
            case "passed" -> outputPassed.increment();
            case "blocked" -> outputBlocked.increment();
            case "warned" -> outputWarned.increment();
        }
        if (durationMs > 0) outputTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordInputValidation(String guardrailType, String result, long durationMs) {
        recordInputValidation(result, durationMs);
        registry.counter("kompile.guardrail.input.by_type", "type", guardrailType, "result", result).increment();
    }

    public void recordOutputValidation(String guardrailType, String result, long durationMs) {
        recordOutputValidation(result, durationMs);
        registry.counter("kompile.guardrail.output.by_type", "type", guardrailType, "result", result).increment();
    }
}
