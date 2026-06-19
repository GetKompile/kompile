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

import ai.kompile.core.crawl.graph.UnifiedCrawlJob.RetryEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reusable retry policy for pipeline batch operations with:
 * <ul>
 *   <li><b>Exponential backoff</b> between retry attempts</li>
 *   <li><b>Batch size reduction</b> on memory-related failures (OOM, GPU OOM)</li>
 *   <li><b>Backend fallback</b> when the current backend is exhausted</li>
 *   <li><b>Dead-letter queue</b> for items that fail all retries</li>
 * </ul>
 *
 * <p>Inspired by the existing {@code SubprocessRestartManager} pattern (which handles
 * subprocess-level retries with backoff) but generalized for in-process pipeline
 * batch operations.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * BatchRetryPolicy policy = BatchRetryPolicy.builder()
 *     .maxRetries(3)
 *     .initialBackoffMs(1000)
 *     .backoffMultiplier(2.0)
 *     .batchShrinkFactor(0.5)
 *     .stage("GRAPH_EXTRACTION")
 *     .build();
 *
 * BatchRetryPolicy.RetryDecision decision = policy.evaluateFailure(
 *     batchIndex, batchItems, batchSize, "OutOfMemoryError", backendId, fallbackSelector, job);
 *
 * switch (decision.getAction()) {
 *     case RETRY_SAME_BACKEND:
 *         Thread.sleep(decision.getBackoffMs());
 *         processWithSize(decision.getReducedBatchSize());
 *         break;
 *     case RETRY_FALLBACK_BACKEND:
 *         processOnBackend(decision.getFallbackBackendId(), decision.getReducedBatchSize());
 *         break;
 *     case DEAD_LETTER:
 *         deadLetterQueue.addAll(items);
 *         break;
 *     case ABORT:
 *         throw new PipelineAbortException(decision.getReason());
 * }
 * }</pre>
 *
 * @param <T> the batch item type
 */
public class BatchRetryPolicy<T> {

    public enum RetryAction {
        /** Retry on the same backend with reduced batch size and backoff */
        RETRY_SAME_BACKEND,
        /** Retry on a different backend */
        RETRY_FALLBACK_BACKEND,
        /** Send items to dead-letter queue (all retries exhausted) */
        DEAD_LETTER,
        /** Abort the entire stage (fatal/non-retryable error) */
        ABORT
    }

    public enum FailureCategory {
        /** Java heap or native OOM — retryable with smaller batch */
        OUT_OF_MEMORY,
        /** GPU memory exhaustion — retryable with smaller batch */
        GPU_OUT_OF_MEMORY,
        /** Backend rate limit or quota exhaustion — retryable on fallback */
        RATE_LIMITED,
        /** Network timeout — retryable with backoff */
        TIMEOUT,
        /** Backend returned invalid response — retryable on fallback */
        BAD_RESPONSE,
        /** Permanent failure (auth, bad input) — not retryable */
        FATAL,
        /** Unknown error — retryable with backoff up to max retries */
        UNKNOWN
    }

    /**
     * The decision returned by {@link #evaluateFailure}.
     */
    public static class RetryDecision<T> {
        private final RetryAction action;
        private final int reducedBatchSize;
        private final long backoffMs;
        private final String fallbackBackendId;
        private final String reason;
        private final int attempt;
        private final List<T> items;

        private RetryDecision(RetryAction action, int reducedBatchSize, long backoffMs,
                              String fallbackBackendId, String reason, int attempt, List<T> items) {
            this.action = action;
            this.reducedBatchSize = reducedBatchSize;
            this.backoffMs = backoffMs;
            this.fallbackBackendId = fallbackBackendId;
            this.reason = reason;
            this.attempt = attempt;
            this.items = items;
        }

        public RetryAction getAction() { return action; }
        public int getReducedBatchSize() { return reducedBatchSize; }
        public long getBackoffMs() { return backoffMs; }
        public String getFallbackBackendId() { return fallbackBackendId; }
        public String getReason() { return reason; }
        public int getAttempt() { return attempt; }
        public List<T> getItems() { return items; }
    }

    private final String stage;
    private final int maxRetries;
    private final long initialBackoffMs;
    private final double backoffMultiplier;
    private final long maxBackoffMs;
    private final double batchShrinkFactor;
    private final int minBatchSize;
    private final double rateLimitBackoffMultiplier;

    // Per-batch attempt tracking, keyed by a caller-supplied batch identity so attempts for one
    // batch never bleed into another. The prior single shared counter accumulated failures across
    // every batch in the phase and dead-lettered late batches once the cumulative count exceeded
    // maxRetries (silent data loss under sporadic failures).
    private final ConcurrentHashMap<Object, AtomicInteger> attemptsByBatch = new ConcurrentHashMap<>();

    // Dead-letter queue
    private final List<T> deadLetterQueue = new CopyOnWriteArrayList<>();

    private BatchRetryPolicy(Builder<T> builder) {
        this.stage = builder.stage;
        this.maxRetries = builder.maxRetries;
        this.initialBackoffMs = builder.initialBackoffMs;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.maxBackoffMs = builder.maxBackoffMs;
        this.batchShrinkFactor = builder.batchShrinkFactor;
        this.minBatchSize = builder.minBatchSize;
        this.rateLimitBackoffMultiplier = builder.rateLimitBackoffMultiplier;
    }

    /**
     * Evaluate a batch failure and return a retry decision.
     *
     * @param batchKey        stable identity of the batch (e.g. its index); attempts are counted
     *                        per key so one batch's retries never dead-letter another
     * @param items          the failed batch items
     * @param currentBatchSize the batch size that failed
     * @param errorMessage   the error message/exception text
     * @param currentBackend the backend that failed (may be null for local)
     * @param fallbackSelector selector for an alternate backend on rate-limit (may be null)
     * @param job            the crawl job for recording retry events
     * @return the retry decision
     */
    public RetryDecision<T> evaluateFailure(Object batchKey, List<T> items, int currentBatchSize,
                                            String errorMessage, String currentBackend,
                                            FallbackBackendSelector fallbackSelector, UnifiedCrawlJob job) {
        int attempt = attemptsByBatch.computeIfAbsent(batchKey, k -> new AtomicInteger(0)).incrementAndGet();
        FailureCategory category = categorize(errorMessage);

        // Fatal errors — never retry
        if (category == FailureCategory.FATAL) {
            attemptsByBatch.remove(batchKey);
            recordEvent(job, attempt, items.size(), currentBatchSize, currentBatchSize,
                    errorMessage, currentBackend, null, 0, false, false);
            return new RetryDecision<>(RetryAction.ABORT, currentBatchSize, 0,
                    null, "fatal: " + errorMessage, attempt, items);
        }

        // Check if we've exhausted retries for THIS batch
        if (attempt > maxRetries) {
            attemptsByBatch.remove(batchKey);
            deadLetterQueue.addAll(items);
            if (job != null) {
                job.getDeadLetterCount().addAndGet(items.size());
            }
            recordEvent(job, attempt, items.size(), currentBatchSize, currentBatchSize,
                    errorMessage, currentBackend, null, 0, false, true);
            return new RetryDecision<>(RetryAction.DEAD_LETTER, currentBatchSize, 0,
                    null, "max_retries_exhausted", attempt, items);
        }

        long backoff = calculateBackoff(attempt);

        // Memory errors — shrink batch and retry same backend
        if (category == FailureCategory.OUT_OF_MEMORY || category == FailureCategory.GPU_OUT_OF_MEMORY) {
            int reducedSize = Math.max(minBatchSize,
                    (int)(currentBatchSize * batchShrinkFactor));
            recordEvent(job, attempt, items.size(), currentBatchSize, reducedSize,
                    errorMessage, currentBackend, null, backoff, false, false);
            return new RetryDecision<>(RetryAction.RETRY_SAME_BACKEND, reducedSize, backoff,
                    null, category.name().toLowerCase(), attempt, items);
        }

        // Rate limiting / quota — back off harder, and reroute to a fallback backend if available.
        if (category == FailureCategory.RATE_LIMITED) {
            long rlBackoff = Math.min(maxBackoffMs, (long)(backoff * rateLimitBackoffMultiplier));
            if (fallbackSelector != null) {
                Optional<String> fallback = fallbackSelector.selectFallback(stage.toLowerCase(), currentBackend);
                if (fallback.isPresent() && !fallback.get().equals(currentBackend)) {
                    recordEvent(job, attempt, items.size(), currentBatchSize, currentBatchSize,
                            errorMessage, currentBackend, fallback.get(), rlBackoff, false, false);
                    if (job != null) {
                        job.recordRerouteEvent(currentBackend, fallback.get(),
                                stage, "rate_limited_fallback", items.size());
                    }
                    return new RetryDecision<>(RetryAction.RETRY_FALLBACK_BACKEND, currentBatchSize,
                            rlBackoff, fallback.get(), "rate_limited_fallback", attempt, items);
                }
            }
            // No fallback — retry the same backend after a longer (rate-limit-aware) backoff.
            recordEvent(job, attempt, items.size(), currentBatchSize, currentBatchSize,
                    errorMessage, currentBackend, null, rlBackoff, false, false);
            return new RetryDecision<>(RetryAction.RETRY_SAME_BACKEND, currentBatchSize, rlBackoff,
                    null, "rate_limited", attempt, items);
        }

        // Timeout / bad response / unknown — retry same backend with backoff
        recordEvent(job, attempt, items.size(), currentBatchSize, currentBatchSize,
                errorMessage, currentBackend, null, backoff, false, false);
        return new RetryDecision<>(RetryAction.RETRY_SAME_BACKEND, currentBatchSize, backoff,
                null, category.name().toLowerCase(), attempt, items);
    }

    /**
     * Reset the attempt counter for a specific batch. Optional — each batch is already isolated by
     * key — but callers may use it to free the entry once a batch finally succeeds.
     */
    public void resetAttempts(Object batchKey) {
        attemptsByBatch.remove(batchKey);
    }

    /**
     * Reset all per-batch attempt counters (e.g. when reusing a policy for a fresh phase).
     */
    public void resetAttempts() {
        attemptsByBatch.clear();
    }

    /**
     * Get the dead-letter queue contents.
     */
    public List<T> getDeadLetterQueue() {
        return new ArrayList<>(deadLetterQueue);
    }

    /**
     * Get count of items in the dead-letter queue.
     */
    public int getDeadLetterCount() {
        return deadLetterQueue.size();
    }

    /**
     * Clear the dead-letter queue (e.g., after manual drain).
     */
    public void clearDeadLetterQueue() {
        deadLetterQueue.clear();
    }

    // ---- Internal ----

    private long calculateBackoff(int attempt) {
        long backoff = (long)(initialBackoffMs * Math.pow(backoffMultiplier, attempt - 1));
        return Math.min(backoff, maxBackoffMs);
    }

    static FailureCategory categorize(String error) {
        if (error == null) return FailureCategory.UNKNOWN;
        String lower = error.toLowerCase();

        // Check GPU OOM before generic OOM — "CUDA out of memory" contains "out of memory"
        if ((lower.contains("cuda") && (lower.contains("out of memory") || lower.contains("oom")))
                || lower.contains("gpu memory") || lower.contains("cublas")) {
            return FailureCategory.GPU_OUT_OF_MEMORY;
        }
        if (lower.contains("outofmemoryerror") || lower.contains("out of memory")
                || lower.contains("oom") || lower.contains("gc overhead")) {
            return FailureCategory.OUT_OF_MEMORY;
        }
        if (lower.contains("rate limit") || lower.contains("429")
                || lower.contains("quota") || lower.contains("capacity")
                || lower.contains("too many requests") || lower.contains("throttl")) {
            return FailureCategory.RATE_LIMITED;
        }
        if (lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("deadline exceeded")) {
            return FailureCategory.TIMEOUT;
        }
        if (lower.contains("401") || lower.contains("403") || lower.contains("unauthorized")
                || lower.contains("forbidden") || lower.contains("invalid api key")
                || lower.contains("authentication")) {
            return FailureCategory.FATAL;
        }
        if (lower.contains("parse") || lower.contains("json") || lower.contains("invalid response")
                || lower.contains("empty response") || lower.contains("malformed")) {
            return FailureCategory.BAD_RESPONSE;
        }
        return FailureCategory.UNKNOWN;
    }

    private void recordEvent(UnifiedCrawlJob job, int attempt, int itemCount,
                             int originalBatch, int reducedBatch, String failureReason,
                             String backendId, String fallbackId, long backoffMs,
                             boolean succeeded, boolean sentToDeadLetter) {
        if (job == null) return;
        job.recordRetryEvent(RetryEvent.builder()
                .timestamp(Instant.now())
                .stage(stage)
                .attempt(attempt)
                .maxAttempts(maxRetries)
                .itemCount(itemCount)
                .originalBatchSize(originalBatch)
                .reducedBatchSize(reducedBatch)
                .failureReason(failureReason)
                .backendId(backendId)
                .fallbackBackendId(fallbackId)
                .backoffMs(backoffMs)
                .succeeded(succeeded)
                .sentToDeadLetter(sentToDeadLetter)
                .build());
    }

    // ---- Builder ----

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private String stage = "DEFAULT";
        private int maxRetries = 3;
        private long initialBackoffMs = 1000;
        private double backoffMultiplier = 2.0;
        private long maxBackoffMs = 60_000;
        private double batchShrinkFactor = 0.5;
        private int minBatchSize = 1;
        private double rateLimitBackoffMultiplier = 3.0;

        public Builder<T> stage(String stage) { this.stage = stage; return this; }
        public Builder<T> maxRetries(int max) { this.maxRetries = max; return this; }
        public Builder<T> initialBackoffMs(long ms) { this.initialBackoffMs = ms; return this; }
        public Builder<T> backoffMultiplier(double mult) { this.backoffMultiplier = mult; return this; }
        public Builder<T> maxBackoffMs(long ms) { this.maxBackoffMs = ms; return this; }
        public Builder<T> batchShrinkFactor(double factor) { this.batchShrinkFactor = factor; return this; }
        public Builder<T> minBatchSize(int min) { this.minBatchSize = min; return this; }
        public Builder<T> rateLimitBackoffMultiplier(double mult) { this.rateLimitBackoffMultiplier = mult; return this; }

        public BatchRetryPolicy<T> build() {
            return new BatchRetryPolicy<>(this);
        }
    }

    /**
     * Predefined profiles for each pipeline stage.
     */
    public static <T> BatchRetryPolicy<T> forGraphExtraction() {
        return BatchRetryPolicy.<T>builder()
                .stage("GRAPH_EXTRACTION")
                .maxRetries(3)
                .initialBackoffMs(2000)
                .backoffMultiplier(2.0)
                .maxBackoffMs(30_000)
                .batchShrinkFactor(0.6)
                .minBatchSize(1)
                .build();
    }

    public static <T> BatchRetryPolicy<T> forVectorIndexing() {
        return BatchRetryPolicy.<T>builder()
                .stage("VECTOR_INDEXING")
                .maxRetries(3)
                .initialBackoffMs(1000)
                .backoffMultiplier(2.0)
                .maxBackoffMs(30_000)
                .batchShrinkFactor(0.5)
                .minBatchSize(1)
                .build();
    }

    public static <T> BatchRetryPolicy<T> forEmbedding() {
        return BatchRetryPolicy.<T>builder()
                .stage("EMBEDDING")
                .maxRetries(2)
                .initialBackoffMs(500)
                .backoffMultiplier(2.0)
                .maxBackoffMs(10_000)
                .batchShrinkFactor(0.5)
                .minBatchSize(1)
                .build();
    }

    public static <T> BatchRetryPolicy<T> forLlmPrompt() {
        return BatchRetryPolicy.<T>builder()
                .stage("LLM_PROMPT")
                .maxRetries(3)
                .initialBackoffMs(3000)
                .backoffMultiplier(2.5)
                .maxBackoffMs(60_000)
                .batchShrinkFactor(1.0)  // no batch shrink for single prompts
                .minBatchSize(1)
                .build();
    }
}
