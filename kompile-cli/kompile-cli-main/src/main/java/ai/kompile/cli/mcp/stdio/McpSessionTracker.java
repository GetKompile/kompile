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

package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.harness.*;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Session-scoped tracker for MCP stdio sessions.
 * <p>
 * While the MCP JSON-RPC protocol is stateless per-message, the stdio server
 * process itself has a lifecycle (init → many calls → process exit).
 * This class tracks tool calls, escape evaluations, and subagent results
 * across that lifecycle and persists them on shutdown.
 * <p>
 * Also provides server-side multi-signal evaluation for the
 * {@code performance_harness} tool's {@code record} action, so external
 * agents get the same escape detection and composite scoring that the
 * built-in chat loop gets.
 */
public class McpSessionTracker {

    private final ChatSessionMetrics metrics;
    private final ModelPerformanceStore store;
    private final HarnessConfig config;
    private final EscapeDetector escapeDetector;
    private final CompositeScoreCalculator scorer;
    private final JudgeLlmEvaluator judge;

    public McpSessionTracker(ObjectMapper objectMapper) {
        this.metrics = new ChatSessionMetrics("mcp-stdio-" + System.currentTimeMillis());
        this.config = HarnessConfig.load(objectMapper);
        this.store = new ModelPerformanceStore(config.getMaxRecordAge(), config.getMaxRecords());
        this.store.loadFromFile();
        this.escapeDetector = new EscapeDetector();
        this.scorer = new CompositeScoreCalculator(config);

        // Build standalone judge from harness config (works when judgeProvider is set)
        JudgeLlmEvaluator candidate = new JudgeLlmEvaluator(objectMapper, config);
        this.judge = candidate.isAvailable() ? candidate : null;

        if (judge != null) {
            System.err.println("[MCP] Judge LLM available (provider: " + config.getJudgeProvider() + ")");
        }
    }

    /**
     * Record a tool call made through the MCP server.
     */
    public void recordToolCall(String toolName, boolean isError, long durationMs) {
        metrics.recordToolCall(toolName, isError, durationMs);
    }

    /**
     * Run server-side multi-signal evaluation on agent output reported via
     * the {@code performance_harness record} action.
     * <p>
     * Runs escape detection + efficiency scoring (Layers 1+2) locally.
     * The caller-provided quality_score (if any) is treated as the judge
     * signal. Returns the composite score and any escape detected.
     *
     * @return evaluation result with composite score and escape info
     */
    public EvaluationResult evaluate(TurnMetrics turnMetrics, String taskType,
                                      float callerProvidedScore) {
        if (!config.isEnabled()) {
            return new EvaluationResult(callerProvidedScore, null);
        }

        // Layer 2: Escape detection (sub-ms)
        EscapeDetector.EscapeResult escapeResult = null;
        if (config.isEscapeDetectionEnabled()) {
            escapeResult = escapeDetector.detect(turnMetrics, taskType);
            if (escapeResult.hasEscape()) {
                metrics.recordEscape(escapeResult.type().name());
            }
        }

        // Layer 3: Judge LLM (if available and enabled, and no hard escape)
        JudgeDimensions judgeDims = null;
        if (config.isJudgeEnabled() && judge != null
                && !(escapeResult != null && escapeResult.isHardEscape())) {
            judgeDims = judge.evaluate(turnMetrics, taskType,
                    turnMetrics.getAgentName());
            if (judgeDims != null && judgeDims.isValid()) {
                metrics.recordJudgeCall();
            }
        }

        // Fall back to caller-provided score as judge signal
        if (judgeDims == null && callerProvidedScore > 0) {
            judgeDims = JudgeDimensions.of(callerProvidedScore, callerProvidedScore, -1, -1,
                    "caller-provided score");
        }

        // Composite score via all available layers
        float composite = scorer.compute(turnMetrics, escapeResult, judgeDims, null, taskType);

        return new EvaluationResult(composite, escapeResult);
    }

    /**
     * Record a fully evaluated performance observation into the cross-session store.
     * Automatically flushes to disk via the store's auto-flush mechanism.
     */
    public void recordToStore(ModelPerformanceRecord record) {
        store.record(record);
        if (record.getQualityScore() > 0) {
            metrics.recordQualityScore(record.getModel(), record.getQualityScore());
        }
    }

    /**
     * Update an existing record's escape data and flush.
     */
    public void updateEscape(ModelPerformanceRecord record,
                              boolean hadEscape, String escapeType, String detail) {
        record.setEscape(hadEscape, escapeType, detail);
        if (hadEscape) {
            metrics.recordEscape(escapeType);
        }
        store.flush();
    }

    /**
     * Update an existing record's judge scores and flush.
     */
    public void updateJudgeScores(ModelPerformanceRecord record,
                                   float correctness, float completeness,
                                   float designQuality, float thinkingScore,
                                   String reasoning) {
        record.setJudgeScores(correctness, completeness, designQuality, thinkingScore, reasoning);
        metrics.recordJudgeCall();
        store.flush();
    }

    /**
     * Update an existing record's composite score and flush.
     */
    public void updateCompositeScore(ModelPerformanceRecord record, float score) {
        record.setQualityScore(score);
        if (score > 0) {
            metrics.recordQualityScore(record.getModel(), score);
        }
        store.flush();
    }

    /**
     * Record a subagent completion.
     */
    public void recordSubagent(String agentName, String output, long durationMs, int exitCode) {
        metrics.recordSubagentSpawned();

        if (!config.isEnabled() || output == null || output.isBlank()) return;

        TurnMetrics tm = TurnMetrics.builder()
                .sessionId(metrics.getSessionId())
                .agentName(agentName)
                .model("external-" + agentName)
                .latencyMs(durationMs)
                .hitMaxSteps(exitCode != 0)
                .agentOutput(output)
                .build();

        String taskType = JudgeLlmEvaluator.detectTaskType(agentName, output);
        EvaluationResult eval = evaluate(tm, taskType, 0);

        ModelPerformanceRecord rec = ModelPerformanceRecord.builder()
                .sessionId(metrics.getSessionId())
                .agentName(agentName)
                .taskType(taskType)
                .model("external-" + agentName)
                .latencyMs(durationMs)
                .qualityScore(eval.compositeScore())
                .hitMaxSteps(exitCode != 0)
                .hadEscape(eval.hasEscape())
                .escapeType(eval.escapeType())
                .timestamp(Instant.now())
                .build();

        store.record(rec);
        if (eval.compositeScore() > 0) {
            metrics.recordQualityScore("external-" + agentName, eval.compositeScore());
        }
    }

    // ========================================================================
    // Harness counter delegation — thin wrappers over ChatSessionMetrics
    // ========================================================================

    /**
     * Increment the escape counter and track by type.
     */
    public void recordEscape(String type) {
        metrics.recordEscape(type);
    }

    /** Increment the judge-call counter. */
    public void recordJudgeCall() {
        metrics.recordJudgeCall();
    }

    /** Increment the model-swap counter. */
    public void recordModelSwap() {
        metrics.recordModelSwap();
    }

    /**
     * Record a quality score for the given model.
     */
    public void recordQualityScore(String model, float score) {
        metrics.recordQualityScore(model, score);
    }

    /** Increment the subagents-spawned counter. */
    public void recordSubagentSpawned() {
        metrics.recordSubagentSpawned();
    }

    /**
     * Accumulate thinking tokens.
     */
    public void recordThinkingTokens(long tokens) {
        metrics.recordThinkingTokens(tokens);
    }

    public int getEscapeCount() { return metrics.getEscapeCount(); }

    public Map<String, java.util.concurrent.atomic.AtomicInteger> getEscapesByType() {
        return metrics.getEscapesByType();
    }

    public int getJudgeCallCount() { return metrics.getJudgeCallCount(); }

    public int getModelSwapCount() { return metrics.getModelSwapCount(); }

    public int getSubagentsSpawned() { return metrics.getSubagentsSpawned(); }

    public long getThinkingTokens() { return metrics.getThinkingTokens(); }

    public Map<String, List<Float>> getQualityScoresByModel() {
        // Convert the Double list from ChatSessionMetrics to Float for this API
        Map<String, List<Float>> result = new java.util.LinkedHashMap<>();
        metrics.getQualityScoresByModel().forEach((model, scores) -> {
            List<Float> floatScores = new java.util.ArrayList<>();
            for (double d : scores) floatScores.add((float) d);
            result.put(model, floatScores);
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    public Map<String, Double> getAvgScoreByModel() {
        return metrics.getAvgScoreByModel();
    }

    /**
     * Flush any remaining dirty records and persist. Called on shutdown.
     */
    public void shutdown() {
        if (config.isPersistCrossSession()) {
            store.flush();
        }
    }

    public ChatSessionMetrics getMetrics() { return metrics; }
    public ModelPerformanceStore getStore() { return store; }
    public HarnessConfig getConfig() { return config; }
    public EscapeDetector getEscapeDetector() { return escapeDetector; }

    /**
     * Result of a server-side multi-signal evaluation.
     */
    public record EvaluationResult(float compositeScore,
                                    EscapeDetector.EscapeResult escapeResult) {
        public boolean hasEscape() {
            return escapeResult != null && escapeResult.hasEscape();
        }

        public String escapeType() {
            return escapeResult != null && escapeResult.hasEscape()
                    ? escapeResult.type().name() : null;
        }

        public String escapeDetail() {
            return escapeResult != null && escapeResult.hasEscape()
                    ? escapeResult.detail() : null;
        }
    }
}
