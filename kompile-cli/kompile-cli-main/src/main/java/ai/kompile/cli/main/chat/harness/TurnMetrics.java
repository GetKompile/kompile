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

package ai.kompile.cli.main.chat.harness;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of all per-turn execution metrics, built at
 * the execution site (AgenticChatLoop / DirectSubagentRunner) and
 * passed into {@link PerformanceHarness#evaluateTurnAsync(TurnMetrics)}.
 *
 * Carries Layer 1 (event counts), thinking content, and the full agent
 * output text needed by Layers 2-4 (escape detection, judge, thinking analysis).
 */
public class TurnMetrics {

    // ── Identity ──────────────────────────────────────────────────────
    private final String sessionId;
    private final String agentName;
    private final String model;
    private final String provider;

    // ── Layer 1: Event counts (zero-cost, collected at execution) ─────
    private final int agenticSteps;
    private final int subagentsSpawned;
    private final int toolCallsTotal;
    private final int toolCallErrors;
    private final Map<String, Integer> toolCallBreakdown;
    private final int compactionCount;
    private final long latencyMs;
    private final long inputTokens;
    private final long outputTokens;
    private final boolean hitMaxSteps;

    // ── Thinking content (Layer 4 input) ──────────────────────────────
    private final String thinkingText;
    private final long thinkingTokens;

    // ── Agent output (needed by escape detector + judge) ──────────────
    private final String agentOutput;

    // ── Task prompt (what the user asked — needed for outcome tracking) ─
    private final String taskPrompt;

    // ── Tool call invocations (for same-args loop detection) ─────────
    /** List of (toolName, argsFingerprint) pairs in call order. */
    private final List<ToolInvocation> toolInvocations;

    /**
     * A single tool call with its name and a fingerprint of its arguments.
     * The fingerprint can be an args hash, truncated JSON, or any string
     * that identifies "same call with same inputs."
     */
    public record ToolInvocation(String toolName, String argsFingerprint) {}

    private TurnMetrics(Builder b) {
        this.sessionId = b.sessionId;
        this.agentName = b.agentName;
        this.model = b.model;
        this.provider = b.provider;
        this.agenticSteps = b.agenticSteps;
        this.subagentsSpawned = b.subagentsSpawned;
        this.toolCallsTotal = b.toolCallsTotal;
        this.toolCallErrors = b.toolCallErrors;
        this.toolCallBreakdown = b.toolCallBreakdown != null
                ? Collections.unmodifiableMap(b.toolCallBreakdown)
                : Collections.emptyMap();
        this.compactionCount = b.compactionCount;
        this.latencyMs = b.latencyMs;
        this.inputTokens = b.inputTokens;
        this.outputTokens = b.outputTokens;
        this.hitMaxSteps = b.hitMaxSteps;
        this.thinkingText = b.thinkingText;
        this.thinkingTokens = b.thinkingTokens;
        this.agentOutput = b.agentOutput;
        this.taskPrompt = b.taskPrompt;
        this.toolInvocations = b.toolInvocations != null
                ? Collections.unmodifiableList(b.toolInvocations)
                : Collections.emptyList();
    }

    public static Builder builder() { return new Builder(); }

    // ── Getters ───────────────────────────────────────────────────────
    public String getSessionId() { return sessionId; }
    public String getAgentName() { return agentName; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public int getAgenticSteps() { return agenticSteps; }
    public int getSubagentsSpawned() { return subagentsSpawned; }
    public int getToolCallsTotal() { return toolCallsTotal; }
    public int getToolCallErrors() { return toolCallErrors; }
    public Map<String, Integer> getToolCallBreakdown() { return toolCallBreakdown; }
    public int getCompactionCount() { return compactionCount; }
    public long getLatencyMs() { return latencyMs; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public boolean isHitMaxSteps() { return hitMaxSteps; }
    public String getThinkingText() { return thinkingText; }
    public long getThinkingTokens() { return thinkingTokens; }
    public String getAgentOutput() { return agentOutput; }
    public String getTaskPrompt() { return taskPrompt; }
    public List<ToolInvocation> getToolInvocations() { return toolInvocations; }

    public boolean hasThinking() {
        return thinkingText != null && !thinkingText.isBlank();
    }

    public double getToolErrorRate() {
        return toolCallsTotal > 0 ? (double) toolCallErrors / toolCallsTotal : 0.0;
    }

    // ── Builder ───────────────────────────────────────────────────────
    public static class Builder {
        private String sessionId;
        private String agentName;
        private String model;
        private String provider;
        private int agenticSteps;
        private int subagentsSpawned;
        private int toolCallsTotal;
        private int toolCallErrors;
        private Map<String, Integer> toolCallBreakdown;
        private int compactionCount;
        private long latencyMs;
        private long inputTokens;
        private long outputTokens;
        private boolean hitMaxSteps;
        private String thinkingText;
        private long thinkingTokens;
        private String agentOutput;
        private String taskPrompt;
        private List<ToolInvocation> toolInvocations;

        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder agenticSteps(int v) { this.agenticSteps = v; return this; }
        public Builder subagentsSpawned(int v) { this.subagentsSpawned = v; return this; }
        public Builder toolCallsTotal(int v) { this.toolCallsTotal = v; return this; }
        public Builder toolCallErrors(int v) { this.toolCallErrors = v; return this; }
        public Builder toolCallBreakdown(Map<String, Integer> v) { this.toolCallBreakdown = v; return this; }
        public Builder compactionCount(int v) { this.compactionCount = v; return this; }
        public Builder latencyMs(long v) { this.latencyMs = v; return this; }
        public Builder inputTokens(long v) { this.inputTokens = v; return this; }
        public Builder outputTokens(long v) { this.outputTokens = v; return this; }
        public Builder hitMaxSteps(boolean v) { this.hitMaxSteps = v; return this; }
        public Builder thinkingText(String v) { this.thinkingText = v; return this; }
        public Builder thinkingTokens(long v) { this.thinkingTokens = v; return this; }
        public Builder agentOutput(String v) { this.agentOutput = v; return this; }
        public Builder taskPrompt(String v) { this.taskPrompt = v; return this; }
        public Builder toolInvocations(List<ToolInvocation> v) { this.toolInvocations = v; return this; }

        public TurnMetrics build() { return new TurnMetrics(this); }
    }
}
