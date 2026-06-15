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

package ai.kompile.core.crawl.graph;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks token consumption and estimated costs across LLM backends during
 * crawl pipeline execution. Supports per-backend and per-job budgets.
 *
 * <p>Cost estimation uses configurable per-model pricing. Prices are in
 * USD cents * 100 per 1M tokens for sub-cent precision without floating point.</p>
 *
 * <p>Budget enforcement is advisory — when a budget is exceeded, {@link #isOverBudget()}
 * returns true and callers can decide to stop dispatching. The tracker does not
 * block or reject requests itself.</p>
 */
public class TokenBudgetTracker {

    /**
     * Per-backend usage counters.
     */
    private static class BackendUsage {
        final String backendId;
        final AtomicLong inputTokens = new AtomicLong(0);
        final AtomicLong outputTokens = new AtomicLong(0);
        final AtomicLong requestCount = new AtomicLong(0);
        // Pricing: cents * 100 per 1M tokens
        final long inputPricePer1M;
        final long outputPricePer1M;

        BackendUsage(String backendId, long inputPricePer1M, long outputPricePer1M) {
            this.backendId = backendId;
            this.inputPricePer1M = inputPricePer1M;
            this.outputPricePer1M = outputPricePer1M;
        }

        long estimatedCostCentsX100() {
            return (inputTokens.get() * inputPricePer1M / 1_000_000)
                    + (outputTokens.get() * outputPricePer1M / 1_000_000);
        }
    }

    private final Map<String, BackendUsage> usageByBackend = new ConcurrentHashMap<>();
    private final AtomicLong globalInputTokens = new AtomicLong(0);
    private final AtomicLong globalOutputTokens = new AtomicLong(0);

    /** Budget limit in cents * 100 (0 = unlimited) */
    private final long budgetLimitCentsX100;

    /** Token limit across all backends (0 = unlimited) */
    private final long tokenLimit;

    /**
     * Create a token budget tracker.
     *
     * @param budgetLimitCentsX100 maximum spend in cents * 100 (0 = no limit)
     * @param tokenLimit           maximum total tokens (0 = no limit)
     */
    public TokenBudgetTracker(long budgetLimitCentsX100, long tokenLimit) {
        this.budgetLimitCentsX100 = budgetLimitCentsX100;
        this.tokenLimit = tokenLimit;
    }

    /** Create an unlimited tracker (no budget or token limits). */
    public TokenBudgetTracker() {
        this(0, 0);
    }

    /**
     * Register a backend with its token pricing.
     *
     * @param backendId        unique backend identifier
     * @param inputPricePer1M  input token price (cents * 100 per 1M tokens)
     * @param outputPricePer1M output token price (cents * 100 per 1M tokens)
     */
    public void registerBackend(String backendId, long inputPricePer1M, long outputPricePer1M) {
        usageByBackend.putIfAbsent(backendId,
                new BackendUsage(backendId, inputPricePer1M, outputPricePer1M));
    }

    /**
     * Register a backend with default pricing (zero cost, e.g. local model).
     */
    public void registerBackend(String backendId) {
        registerBackend(backendId, 0, 0);
    }

    /**
     * Record token consumption for a completed request.
     *
     * @param backendId    the backend that processed the request
     * @param inputTokens  input tokens consumed
     * @param outputTokens output tokens generated
     */
    public void recordUsage(String backendId, long inputTokens, long outputTokens) {
        BackendUsage usage = usageByBackend.get(backendId);
        if (usage != null) {
            usage.inputTokens.addAndGet(inputTokens);
            usage.outputTokens.addAndGet(outputTokens);
            usage.requestCount.incrementAndGet();
        }
        globalInputTokens.addAndGet(inputTokens);
        globalOutputTokens.addAndGet(outputTokens);
    }

    /**
     * Check if the budget has been exceeded.
     */
    public boolean isOverBudget() {
        if (budgetLimitCentsX100 > 0 && totalCostCentsX100() >= budgetLimitCentsX100) {
            return true;
        }
        if (tokenLimit > 0 && totalTokens() >= tokenLimit) {
            return true;
        }
        return false;
    }

    /**
     * Remaining budget in cents * 100, or Long.MAX_VALUE if unlimited.
     */
    public long remainingBudgetCentsX100() {
        if (budgetLimitCentsX100 <= 0) return Long.MAX_VALUE;
        return Math.max(0, budgetLimitCentsX100 - totalCostCentsX100());
    }

    /**
     * Remaining token allowance, or Long.MAX_VALUE if unlimited.
     */
    public long remainingTokens() {
        if (tokenLimit <= 0) return Long.MAX_VALUE;
        return Math.max(0, tokenLimit - totalTokens());
    }

    // ---- Aggregate accessors ----

    public long totalInputTokens() {
        return globalInputTokens.get();
    }

    public long totalOutputTokens() {
        return globalOutputTokens.get();
    }

    public long totalTokens() {
        return globalInputTokens.get() + globalOutputTokens.get();
    }

    public long totalCostCentsX100() {
        long total = 0;
        for (BackendUsage usage : usageByBackend.values()) {
            total += usage.estimatedCostCentsX100();
        }
        return total;
    }

    // ---- Per-backend accessors ----

    public long backendInputTokens(String backendId) {
        BackendUsage u = usageByBackend.get(backendId);
        return u != null ? u.inputTokens.get() : 0;
    }

    public long backendOutputTokens(String backendId) {
        BackendUsage u = usageByBackend.get(backendId);
        return u != null ? u.outputTokens.get() : 0;
    }

    public long backendCostCentsX100(String backendId) {
        BackendUsage u = usageByBackend.get(backendId);
        return u != null ? u.estimatedCostCentsX100() : 0;
    }

    public long backendRequestCount(String backendId) {
        BackendUsage u = usageByBackend.get(backendId);
        return u != null ? u.requestCount.get() : 0;
    }

    /**
     * Publish current token/cost stats to a {@link UnifiedCrawlJob} for UI visibility.
     */
    public void publishStats(UnifiedCrawlJob job) {
        job.getTotalInputTokens().set(globalInputTokens.get());
        job.getTotalOutputTokens().set(globalOutputTokens.get());
        job.getEstimatedCostCentsX100().set(totalCostCentsX100());
    }

    /**
     * Well-known model pricing constants (cents * 100 per 1M tokens).
     * Used by pipeline setup to auto-register backends.
     */
    public static final class Pricing {
        private Pricing() {}
        // Anthropic Claude 3.5 Sonnet
        public static final long CLAUDE_SONNET_INPUT = 300;   // $3.00/1M
        public static final long CLAUDE_SONNET_OUTPUT = 1500; // $15.00/1M
        // Anthropic Claude 3.5 Haiku
        public static final long CLAUDE_HAIKU_INPUT = 25;     // $0.25/1M
        public static final long CLAUDE_HAIKU_OUTPUT = 125;   // $1.25/1M
        // OpenAI GPT-4o
        public static final long GPT4O_INPUT = 250;           // $2.50/1M
        public static final long GPT4O_OUTPUT = 1000;         // $10.00/1M
        // OpenAI GPT-4o-mini
        public static final long GPT4O_MINI_INPUT = 15;       // $0.15/1M
        public static final long GPT4O_MINI_OUTPUT = 60;      // $0.60/1M
        // Local model (no cost)
        public static final long LOCAL_INPUT = 0;
        public static final long LOCAL_OUTPUT = 0;
    }
}
