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
 * Metrics for MCP (Model Context Protocol) tool invocations.
 *
 * Counters:
 * <ul>
 *   <li>{@code kompile.mcp.tool.calls.total} – total tool calls</li>
 *   <li>{@code kompile.mcp.tool.calls.success} – successful tool calls</li>
 *   <li>{@code kompile.mcp.tool.calls.failed} – failed tool calls</li>
 * </ul>
 *
 * Timers:
 * <ul>
 *   <li>{@code kompile.mcp.tool.time} – tool call duration distribution</li>
 * </ul>
 *
 * Per-tool counters are created dynamically with a {@code tool} tag:
 * <ul>
 *   <li>{@code kompile.mcp.tool.calls.by_tool} – calls per tool name</li>
 * </ul>
 */
public class McpToolMetrics {

    private final MeterRegistry registry;

    private Counter callsTotal;
    private Counter callsSuccess;
    private Counter callsFailed;
    private Timer callTimer;

    public McpToolMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void bindMetrics() {
        callsTotal = Counter.builder("kompile.mcp.tool.calls.total")
                .description("Total MCP tool calls").register(registry);
        callsSuccess = Counter.builder("kompile.mcp.tool.calls.success")
                .description("Successful MCP tool calls").register(registry);
        callsFailed = Counter.builder("kompile.mcp.tool.calls.failed")
                .description("Failed MCP tool calls").register(registry);

        callTimer = Timer.builder("kompile.mcp.tool.time")
                .description("MCP tool call duration").register(registry);
    }

    public void recordToolCall(String toolName, boolean success, long durationMs) {
        callsTotal.increment();
        if (success) {
            callsSuccess.increment();
        } else {
            callsFailed.increment();
        }
        if (durationMs > 0) callTimer.record(durationMs, TimeUnit.MILLISECONDS);

        registry.counter("kompile.mcp.tool.calls.by_tool",
                "tool", toolName,
                "result", success ? "success" : "failure").increment();
    }

    public void recordToolCall(String toolName, String actionType, boolean success, long durationMs) {
        recordToolCall(toolName, success, durationMs);
        registry.counter("kompile.mcp.tool.calls.by_action",
                "tool", toolName,
                "action", actionType).increment();
    }
}
