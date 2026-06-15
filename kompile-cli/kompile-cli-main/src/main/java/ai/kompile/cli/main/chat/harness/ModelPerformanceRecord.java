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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A single performance observation after one agent turn completes.
 * Recorded by the harness and persisted to the cross-session store.
 * Carries all four signal layers for post-hoc analysis.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelPerformanceRecord {

    // ── Identity ──────────────────────────────────────────────────────
    @JsonProperty private String sessionId;
    @JsonProperty private String agentName;
    @JsonProperty private String taskType;
    @JsonProperty private String provider;
    @JsonProperty private String model;

    // ── Layer 1: Event metrics ────────────────────────────────────────
    @JsonProperty private long latencyMs;
    @JsonProperty private long inputTokens;
    @JsonProperty private long outputTokens;
    @JsonProperty private int agenticSteps;
    @JsonProperty private int subagentsSpawned;
    @JsonProperty private int toolCallErrors;
    @JsonProperty private boolean hitMaxSteps;
    @JsonProperty private long thinkingTokens;

    // ── Layer 2: Escape detection ─────────────────────────────────────
    @JsonProperty private boolean hadEscape;
    @JsonProperty private String escapeType;
    @JsonProperty private String escapeDetail;

    // ── Layer 3: Multi-dimensional judge ──────────────────────────────
    @JsonProperty private float qualityScore;   // composite score (back-compat)
    @JsonProperty private String judgeReasoning;
    @JsonProperty private boolean judgeWasCalled;
    @JsonProperty private float judgeCorrectness;
    @JsonProperty private float judgeCompleteness;
    @JsonProperty private float judgeDesignQuality;   // -1 = N/A
    @JsonProperty private float judgeThinkingScore;   // -1 = N/A

    // ── Layer 4: Thinking analysis ────────────────────────────────────
    @JsonProperty private float thinkingCoherenceScore;
    @JsonProperty private boolean thinkingHadBacktracking;

    // ── Layer 5: Outcome tracking ───────────────────────────────────
    @JsonProperty private String taskOutcome;       // TaskOutcome enum name
    @JsonProperty private String taskPrompt;        // the user's original request
    @JsonProperty private String outcomeReason;     // why this outcome was determined
    @JsonProperty private int assertionsPassed;     // number of assertions that passed
    @JsonProperty private int assertionsTotal;      // total assertions evaluated

    // ── Layer 1 extended: fields previously lost from TurnMetrics ─────
    @JsonProperty private int toolCallsTotal;
    @JsonProperty private int compactionCount;
    @JsonProperty private String toolCallBreakdownJson; // JSON map of tool->count

    // ── Operational flags ─────────────────────────────────────────────
    @JsonProperty private boolean hadRateLimit;
    @JsonProperty private boolean hadApiError;
    @JsonProperty private Instant timestamp;

    // Jackson deserialization
    public ModelPerformanceRecord() {}

    private ModelPerformanceRecord(Builder builder) {
        this.sessionId = builder.sessionId;
        this.agentName = builder.agentName;
        this.taskType = builder.taskType;
        this.provider = builder.provider;
        this.model = builder.model;
        this.latencyMs = builder.latencyMs;
        this.inputTokens = builder.inputTokens;
        this.outputTokens = builder.outputTokens;
        this.agenticSteps = builder.agenticSteps;
        this.subagentsSpawned = builder.subagentsSpawned;
        this.toolCallErrors = builder.toolCallErrors;
        this.hitMaxSteps = builder.hitMaxSteps;
        this.thinkingTokens = builder.thinkingTokens;
        this.hadEscape = builder.hadEscape;
        this.escapeType = builder.escapeType;
        this.escapeDetail = builder.escapeDetail;
        this.qualityScore = builder.qualityScore;
        this.judgeReasoning = builder.judgeReasoning;
        this.judgeWasCalled = builder.judgeWasCalled;
        this.judgeCorrectness = builder.judgeCorrectness;
        this.judgeCompleteness = builder.judgeCompleteness;
        this.judgeDesignQuality = builder.judgeDesignQuality;
        this.judgeThinkingScore = builder.judgeThinkingScore;
        this.thinkingCoherenceScore = builder.thinkingCoherenceScore;
        this.thinkingHadBacktracking = builder.thinkingHadBacktracking;
        this.taskOutcome = builder.taskOutcome;
        this.taskPrompt = builder.taskPrompt;
        this.outcomeReason = builder.outcomeReason;
        this.assertionsPassed = builder.assertionsPassed;
        this.assertionsTotal = builder.assertionsTotal;
        this.toolCallsTotal = builder.toolCallsTotal;
        this.compactionCount = builder.compactionCount;
        this.toolCallBreakdownJson = builder.toolCallBreakdownJson;
        this.hadRateLimit = builder.hadRateLimit;
        this.hadApiError = builder.hadApiError;
        this.timestamp = builder.timestamp;
    }

    // ── Getters ───────────────────────────────────────────────────────
    public String getSessionId() { return sessionId; }
    public String getAgentName() { return agentName; }
    public String getTaskType() { return taskType; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public long getLatencyMs() { return latencyMs; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public int getAgenticSteps() { return agenticSteps; }
    public int getSubagentsSpawned() { return subagentsSpawned; }
    public int getToolCallErrors() { return toolCallErrors; }
    public boolean isHitMaxSteps() { return hitMaxSteps; }
    public long getThinkingTokens() { return thinkingTokens; }
    public boolean isHadEscape() { return hadEscape; }
    public String getEscapeType() { return escapeType; }
    public String getEscapeDetail() { return escapeDetail; }
    public float getQualityScore() { return qualityScore; }
    public String getJudgeReasoning() { return judgeReasoning; }
    public boolean isJudgeWasCalled() { return judgeWasCalled; }
    public float getJudgeCorrectness() { return judgeCorrectness; }
    public float getJudgeCompleteness() { return judgeCompleteness; }
    public float getJudgeDesignQuality() { return judgeDesignQuality; }
    public float getJudgeThinkingScore() { return judgeThinkingScore; }
    public float getThinkingCoherenceScore() { return thinkingCoherenceScore; }
    public boolean isThinkingHadBacktracking() { return thinkingHadBacktracking; }
    public String getTaskOutcome() { return taskOutcome; }
    public String getTaskPrompt() { return taskPrompt; }
    public String getOutcomeReason() { return outcomeReason; }
    public int getAssertionsPassed() { return assertionsPassed; }
    public int getAssertionsTotal() { return assertionsTotal; }
    public int getToolCallsTotal() { return toolCallsTotal; }
    public int getCompactionCount() { return compactionCount; }
    public String getToolCallBreakdownJson() { return toolCallBreakdownJson; }
    public boolean isHadRateLimit() { return hadRateLimit; }
    public boolean isHadApiError() { return hadApiError; }
    public Instant getTimestamp() { return timestamp; }

    // ── Setters for incremental updates ──────────────────────────────
    // Allow individual signal layers to update a record after creation.

    public void setEscape(boolean hadEscape, String escapeType, String escapeDetail) {
        this.hadEscape = hadEscape;
        this.escapeType = escapeType;
        this.escapeDetail = escapeDetail;
    }

    public void setJudgeScores(float correctness, float completeness,
                                float designQuality, float thinkingScore, String reasoning) {
        this.judgeCorrectness = correctness;
        this.judgeCompleteness = completeness;
        this.judgeDesignQuality = designQuality;
        this.judgeThinkingScore = thinkingScore;
        this.judgeReasoning = reasoning;
        this.judgeWasCalled = true;
    }

    public void setThinkingAnalysis(float coherenceScore, boolean hadBacktracking) {
        this.thinkingCoherenceScore = coherenceScore;
        this.thinkingHadBacktracking = hadBacktracking;
    }

    public void setQualityScore(float qualityScore) {
        this.qualityScore = qualityScore;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public void setHadRateLimit(boolean hadRateLimit) {
        this.hadRateLimit = hadRateLimit;
    }

    public void setHadApiError(boolean hadApiError) {
        this.hadApiError = hadApiError;
    }

    public void setOutcome(TaskOutcome outcome, String reason,
                            int assertionsPassed, int assertionsTotal) {
        this.taskOutcome = outcome != null ? outcome.name() : null;
        this.outcomeReason = reason;
        this.assertionsPassed = assertionsPassed;
        this.assertionsTotal = assertionsTotal;
    }

    public void setTaskPrompt(String taskPrompt) {
        this.taskPrompt = taskPrompt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private String agentName;
        private String taskType = "general";
        private String provider;
        private String model;
        private long latencyMs;
        private long inputTokens;
        private long outputTokens;
        private int agenticSteps;
        private int subagentsSpawned;
        private int toolCallErrors;
        private boolean hitMaxSteps;
        private long thinkingTokens;
        private boolean hadEscape;
        private String escapeType;
        private String escapeDetail;
        private float qualityScore;
        private String judgeReasoning;
        private boolean judgeWasCalled;
        private float judgeCorrectness;
        private float judgeCompleteness;
        private float judgeDesignQuality = -1;
        private float judgeThinkingScore = -1;
        private float thinkingCoherenceScore;
        private boolean thinkingHadBacktracking;
        private String taskOutcome;
        private String taskPrompt;
        private String outcomeReason;
        private int assertionsPassed;
        private int assertionsTotal;
        private int toolCallsTotal;
        private int compactionCount;
        private String toolCallBreakdownJson;
        private boolean hadRateLimit;
        private boolean hadApiError;
        private Instant timestamp = Instant.now();

        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder taskType(String v) { this.taskType = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder latencyMs(long v) { this.latencyMs = v; return this; }
        public Builder inputTokens(long v) { this.inputTokens = v; return this; }
        public Builder outputTokens(long v) { this.outputTokens = v; return this; }
        public Builder agenticSteps(int v) { this.agenticSteps = v; return this; }
        public Builder subagentsSpawned(int v) { this.subagentsSpawned = v; return this; }
        public Builder toolCallErrors(int v) { this.toolCallErrors = v; return this; }
        public Builder hitMaxSteps(boolean v) { this.hitMaxSteps = v; return this; }
        public Builder thinkingTokens(long v) { this.thinkingTokens = v; return this; }
        public Builder hadEscape(boolean v) { this.hadEscape = v; return this; }
        public Builder escapeType(String v) { this.escapeType = v; return this; }
        public Builder escapeDetail(String v) { this.escapeDetail = v; return this; }
        public Builder qualityScore(float v) { this.qualityScore = v; return this; }
        public Builder judgeReasoning(String v) { this.judgeReasoning = v; return this; }
        public Builder judgeWasCalled(boolean v) { this.judgeWasCalled = v; return this; }
        public Builder judgeCorrectness(float v) { this.judgeCorrectness = v; return this; }
        public Builder judgeCompleteness(float v) { this.judgeCompleteness = v; return this; }
        public Builder judgeDesignQuality(float v) { this.judgeDesignQuality = v; return this; }
        public Builder judgeThinkingScore(float v) { this.judgeThinkingScore = v; return this; }
        public Builder thinkingCoherenceScore(float v) { this.thinkingCoherenceScore = v; return this; }
        public Builder thinkingHadBacktracking(boolean v) { this.thinkingHadBacktracking = v; return this; }
        public Builder taskOutcome(String v) { this.taskOutcome = v; return this; }
        public Builder taskPrompt(String v) { this.taskPrompt = v; return this; }
        public Builder outcomeReason(String v) { this.outcomeReason = v; return this; }
        public Builder assertionsPassed(int v) { this.assertionsPassed = v; return this; }
        public Builder assertionsTotal(int v) { this.assertionsTotal = v; return this; }
        public Builder toolCallsTotal(int v) { this.toolCallsTotal = v; return this; }
        public Builder compactionCount(int v) { this.compactionCount = v; return this; }
        public Builder toolCallBreakdownJson(String v) { this.toolCallBreakdownJson = v; return this; }
        public Builder hadRateLimit(boolean v) { this.hadRateLimit = v; return this; }
        public Builder hadApiError(boolean v) { this.hadApiError = v; return this; }
        public Builder timestamp(Instant v) { this.timestamp = v; return this; }

        public ModelPerformanceRecord build() {
            return new ModelPerformanceRecord(this);
        }
    }
}
