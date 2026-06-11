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

import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Facade for the multi-signal Agent Performance Harness.
 *
 * Four evaluation layers run on each agent turn:
 * <ol>
 *   <li><b>Event metrics</b> (free) — step count, tool errors, compactions, tokens</li>
 *   <li><b>Escape detection</b> (sync, sub-ms) — refusals, empty output, loops</li>
 *   <li><b>Judge LLM</b> (async, expensive) — multi-dimensional correctness/completeness</li>
 *   <li><b>Thinking analysis</b> (async, free) — coherence from extended thinking content</li>
 * </ol>
 *
 * Layers 1-2 run synchronously at the call site. Layers 3-4 run on a background
 * thread. The composite score feeds into {@link ModelRouter} for swap decisions.
 */
public class PerformanceHarness {

    // Lazy-initialized components — allocated on first evaluateTurnAsync() call
    private volatile JudgeLlmEvaluator judge;
    private volatile EscapeDetector escapeDetector;
    private volatile ThinkingAnalyzer thinkingAnalyzer;
    private volatile CompositeScoreCalculator scorer;
    private volatile ModelRouter router;
    private volatile ModelPerformanceStore store;
    private volatile ExecutorService judgeExecutor;

    private final HarnessConfig config;
    private final TerminalRenderer renderer;
    private final ChatSessionMetrics sessionMetrics;
    private final ChatConfig chatConfig;
    private final BackgroundProcessManager processManager;

    // Retained for lazy judge construction
    private final DirectLlmClient llmClient;
    private final ObjectMapper objectMapper;

    private volatile SwapListener swapListener;
    private volatile boolean initialized;

    public PerformanceHarness(DirectLlmClient llmClient, ChatConfig chatConfig,
                               ObjectMapper objectMapper, TerminalRenderer renderer,
                               ChatSessionMetrics sessionMetrics) {
        this(llmClient, chatConfig, objectMapper, renderer, sessionMetrics, null);
    }

    public PerformanceHarness(DirectLlmClient llmClient, ChatConfig chatConfig,
                               ObjectMapper objectMapper, TerminalRenderer renderer,
                               ChatSessionMetrics sessionMetrics,
                               BackgroundProcessManager processManager) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.chatConfig = chatConfig;
        this.renderer = renderer;
        this.sessionMetrics = sessionMetrics;
        this.processManager = processManager;
        // Config is cheap (one small JSON file) — load eagerly so isEnabled() works immediately
        this.config = HarnessConfig.load(objectMapper);
    }

    /**
     * Lazily initialize all heavyweight components on first use.
     * Double-checked locking ensures at most one initialization.
     */
    private void ensureInitialized() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            this.store = new ModelPerformanceStore(config.getMaxRecordAge(), config.getMaxRecords());
            this.store.loadFromFile();
            this.escapeDetector = new EscapeDetector();
            this.thinkingAnalyzer = new ThinkingAnalyzer();
            this.scorer = new CompositeScoreCalculator(config);
            this.router = new ModelRouter(config, store, chatConfig);
            if (config.isJudgeEnabled()) {
                this.judge = new JudgeLlmEvaluator(llmClient, objectMapper, config, processManager);
            }
            this.judgeExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "harness-judge");
                t.setDaemon(true);
                return t;
            });
            initialized = true;
        }
    }

    // ── Decomposed evaluation API ──────────────────────────────────────
    // Each layer can be called independently. Call createBaseRecord first,
    // then any combination of evaluateEscape / evaluateJudge / evaluateThinking,
    // then recomputeComposite to roll up all signals.
    // evaluateTurnAsync() remains as the convenience wrapper that runs all layers.

    /**
     * Layer 1: Create a base performance record from turn event metrics.
     * Records it in the store immediately (with qualityScore=0 until
     * composite is computed). Returns the mutable record for further updates.
     */
    public ModelPerformanceRecord createBaseRecord(TurnMetrics metrics, String taskType) {
        ensureInitialized();

        // Serialize tool call breakdown to JSON for persistence
        String breakdownJson = null;
        if (metrics.getToolCallBreakdown() != null && !metrics.getToolCallBreakdown().isEmpty()) {
            try {
                breakdownJson = objectMapper.writeValueAsString(metrics.getToolCallBreakdown());
            } catch (Exception ignored) {}
        }

        ModelPerformanceRecord rec = ModelPerformanceRecord.builder()
                .sessionId(metrics.getSessionId())
                .agentName(metrics.getAgentName())
                .taskType(taskType)
                .provider(metrics.getProvider())
                .model(metrics.getModel())
                .latencyMs(metrics.getLatencyMs())
                .inputTokens(metrics.getInputTokens())
                .outputTokens(metrics.getOutputTokens())
                .agenticSteps(metrics.getAgenticSteps())
                .subagentsSpawned(metrics.getSubagentsSpawned())
                .toolCallErrors(metrics.getToolCallErrors())
                .hitMaxSteps(metrics.isHitMaxSteps())
                .thinkingTokens(metrics.getThinkingTokens())
                .toolCallsTotal(metrics.getToolCallsTotal())
                .compactionCount(metrics.getCompactionCount())
                .toolCallBreakdownJson(breakdownJson)
                .taskPrompt(metrics.getTaskPrompt())
                .timestamp(Instant.now())
                .build();
        store.record(rec);
        return rec;
    }

    /**
     * Layer 2: Run escape detection and update the record in-place.
     * Synchronous, sub-ms. Safe to call from any thread.
     *
     * @return the escape result (also written into the record)
     */
    public EscapeDetector.EscapeResult evaluateEscape(ModelPerformanceRecord record,
                                                       TurnMetrics metrics, String taskType) {
        ensureInitialized();
        if (!config.isEscapeDetectionEnabled()) return null;

        EscapeDetector.EscapeResult result = escapeDetector.detect(metrics, taskType);
        record.setEscape(result.hasEscape(), result.type().name(), result.detail());

        if (result.hasEscape() && sessionMetrics != null) {
            sessionMetrics.recordEscape(result.type().name());
        }
        if (result.hasEscape() && config.isVerboseLogging()) {
            System.out.println(renderer.dim("  [escape] " + metrics.getAgentName()
                    + " -> " + result.type() + ": " + result.detail()));
        }
        return result;
    }

    /**
     * Layer 3: Run judge LLM evaluation and update the record in-place.
     * Expensive (network call). Should be run on a background thread.
     *
     * @return the judge dimensions (also written into the record)
     */
    public JudgeDimensions evaluateJudge(ModelPerformanceRecord record,
                                          TurnMetrics metrics, String taskType) {
        ensureInitialized();
        if (!config.isJudgeEnabled()) return null;
        // Lazy-create judge on first judge call (skipped in ensureInitialized if disabled at startup)
        if (judge == null) {
            synchronized (this) {
                if (judge == null) {
                    judge = new JudgeLlmEvaluator(llmClient, objectMapper, config, processManager);
                }
            }
        }

        JudgeDimensions dims = judge.evaluate(metrics, taskType, metrics.getAgentName());
        if (dims != null && dims.isValid()) {
            record.setJudgeScores(dims.getCorrectness(), dims.getCompleteness(),
                    dims.getDesignQuality(), dims.getThinkingCoherence(), dims.getReasoning());
        }
        if (sessionMetrics != null) {
            sessionMetrics.recordJudgeCall();
        }
        return dims;
    }

    /**
     * Layer 4: Run thinking analysis and update the record in-place.
     * Free (pattern matching). Can run on any thread.
     *
     * @return the thinking analysis (also written into the record)
     */
    public ThinkingAnalyzer.ThinkingAnalysis evaluateThinking(ModelPerformanceRecord record,
                                                               TurnMetrics metrics) {
        ensureInitialized();
        if (!config.isThinkingAnalysisEnabled() || !metrics.hasThinking()) return null;

        ThinkingAnalyzer.ThinkingAnalysis analysis = thinkingAnalyzer.analyze(
                metrics.getThinkingText(), metrics.getThinkingTokens(),
                metrics.getOutputTokens());
        if (analysis.coherenceScore() > 0) {
            record.setThinkingAnalysis(analysis.coherenceScore(), analysis.hasBacktracking());
        }
        if (sessionMetrics != null) {
            sessionMetrics.recordThinkingTokens(metrics.getThinkingTokens());
        }
        return analysis;
    }

    /**
     * Recompute the composite score from all signals currently on the record,
     * update session metrics, and check for model swap.
     */
    public float recomputeComposite(ModelPerformanceRecord record, TurnMetrics metrics,
                                     EscapeDetector.EscapeResult escapeResult,
                                     JudgeDimensions judgeDims,
                                     ThinkingAnalyzer.ThinkingAnalysis thinkingAnalysis,
                                     String taskType) {
        ensureInitialized();
        float compositeScore = scorer.compute(
                metrics, escapeResult, judgeDims, thinkingAnalysis, taskType);
        record.setQualityScore(compositeScore);

        // Update session metrics
        if (sessionMetrics != null && compositeScore > 0) {
            sessionMetrics.recordQualityScore(metrics.getModel(), compositeScore);
        }

        // Check if model swap is needed
        if (compositeScore > 0) {
            boolean hadError = record.isHadApiError() || record.isHadRateLimit();
            String swappedTo = router.onTurnComplete(metrics.getAgentName(), metrics.getModel(),
                    compositeScore, metrics.getLatencyMs(), hadError);
            if (swappedTo != null) {
                if (sessionMetrics != null) sessionMetrics.recordModelSwap();
                notifySwap(metrics.getAgentName(), metrics.getModel(), swappedTo,
                        "composite score below threshold");
            }
        }

        // Flush store to persist the completed record
        store.flush();

        // Verbose logging
        if (config.isVerboseLogging()) {
            logEvaluation(metrics, compositeScore, escapeResult, judgeDims, thinkingAnalysis);
        }

        return compositeScore;
    }

    // ── Convenience wrappers (run all layers) ────────────────────────

    /**
     * Primary entry point: evaluate a completed agent turn using all four signal layers.
     * Escape detection runs synchronously (sub-ms). Judge + thinking run async.
     */
    public void evaluateTurnAsync(TurnMetrics metrics) {
        if (!config.isEnabled()) return;
        if (metrics.getAgentOutput() == null || metrics.getAgentOutput().isBlank()) return;

        String taskType = JudgeLlmEvaluator.detectTaskType(
                metrics.getAgentName(), metrics.getAgentOutput());

        // Layer 1: Create base record
        ModelPerformanceRecord record = createBaseRecord(metrics, taskType);

        // Layer 2: Escape detection (synchronous, sub-ms)
        EscapeDetector.EscapeResult escapeResult = evaluateEscape(record, metrics, taskType);

        // Capture for lambda
        final EscapeDetector.EscapeResult finalEscapeResult = escapeResult;
        final String finalTaskType = taskType;

        // Layers 3+4: Judge + thinking analysis (background thread)
        judgeExecutor.submit(() -> {
            try {
                // Layer 4: Thinking
                ThinkingAnalyzer.ThinkingAnalysis thinkingAnalysis =
                        evaluateThinking(record, metrics);

                // Layer 3: Judge
                JudgeDimensions judgeDims = evaluateJudge(record, metrics, finalTaskType);

                // Composite + swap check + flush
                recomputeComposite(record, metrics, finalEscapeResult,
                        judgeDims, thinkingAnalysis, finalTaskType);
            } catch (Exception e) {
                if (config.isVerboseLogging()) {
                    System.err.println("Harness evaluation error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Backward-compatible overload: builds a minimal TurnMetrics from legacy parameters.
     */
    public void evaluateTurnAsync(String agentName, String model, String agentOutput,
                                   String sessionId, long latencyMs) {
        evaluateTurnAsync(TurnMetrics.builder()
                .sessionId(sessionId)
                .agentName(agentName)
                .model(model)
                .provider(chatConfig != null ? chatConfig.getProvider() : null)
                .latencyMs(latencyMs)
                .agentOutput(agentOutput)
                .build());
    }

    private void logEvaluation(TurnMetrics metrics, float compositeScore,
                                EscapeDetector.EscapeResult escapeResult,
                                JudgeDimensions judgeDims,
                                ThinkingAnalyzer.ThinkingAnalysis thinkingAnalysis) {
        StringBuilder log = new StringBuilder();
        log.append("  [harness] ").append(metrics.getAgentName())
                .append(" -> composite=").append(String.format("%.1f/5", compositeScore));
        if (judgeDims != null && judgeDims.isValid()) {
            log.append(" judge=").append(String.format("%.1f", judgeDims.weightedAverage()))
                    .append(" (c=").append(String.format("%.0f", judgeDims.getCorrectness()))
                    .append(" comp=").append(String.format("%.0f", judgeDims.getCompleteness()));
            if (judgeDims.getDesignQuality() > 0)
                log.append(" dq=").append(String.format("%.0f", judgeDims.getDesignQuality()));
            if (judgeDims.getThinkingCoherence() > 0)
                log.append(" tc=").append(String.format("%.0f", judgeDims.getThinkingCoherence()));
            log.append(")");
        }
        if (escapeResult != null && escapeResult.hasEscape()) {
            log.append(" escape=").append(escapeResult.type());
        }
        if (thinkingAnalysis != null && thinkingAnalysis.coherenceScore() > 0) {
            log.append(" thinking=").append(String.format("%.1f", thinkingAnalysis.coherenceScore()));
        }
        log.append(" -- ").append(judgeDims != null && judgeDims.isValid()
                ? judgeDims.getReasoning() : "no judge");
        System.out.println(renderer.dim(log.toString()));
    }

    /**
     * Fast path for rate-limit errors detected in streamDirectTurn.
     */
    public String onRateLimitError(String currentModel) {
        if (!config.isEnabled() || !config.isRateLimitFallbackEnabled()) return null;
        ensureInitialized();
        String fallback = router.onRateLimitError(currentModel);
        if (fallback != null) {
            if (sessionMetrics != null) sessionMetrics.recordModelSwap();
            notifySwap(null, currentModel, fallback, "rate-limit (HTTP 429)");
        }
        return fallback;
    }

    public void onManualModelChange(String newModel) {
        if (initialized && router != null) {
            router.setManualOverride(newModel);
        }
    }

    public String getModelOverride(String agentName) {
        return (initialized && router != null) ? router.getCurrentModelFor(agentName) : null;
    }

    public void shutdown() {
        if (!initialized) return;
        if (judgeExecutor != null) {
            judgeExecutor.shutdown();
            try {
                judgeExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (judge != null) {
            judge.close();
        }
        if (config.isPersistCrossSession() && store != null) {
            store.flush();
        }
    }

    public HarnessConfig getConfig() { return config; }
    public ModelPerformanceStore getStore() { ensureInitialized(); return store; }
    public ModelRouter getRouter() { ensureInitialized(); return router; }

    public void setSwapListener(SwapListener listener) {
        this.swapListener = listener;
    }

    private void notifySwap(String agentName, String fromModel, String toModel, String reason) {
        SwapListener listener = this.swapListener;
        if (listener != null) {
            listener.onModelSwap(agentName, fromModel, toModel, reason);
        }
    }

    @FunctionalInterface
    public interface SwapListener {
        void onModelSwap(String agentName, String fromModel, String toModel, String reason);
    }
}
