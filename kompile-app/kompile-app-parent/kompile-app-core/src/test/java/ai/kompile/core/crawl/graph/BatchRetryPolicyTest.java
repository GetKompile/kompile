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

import ai.kompile.core.crawl.graph.BatchRetryPolicy.FailureCategory;
import ai.kompile.core.crawl.graph.BatchRetryPolicy.RetryAction;
import ai.kompile.core.crawl.graph.BatchRetryPolicy.RetryDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BatchRetryPolicyTest {

    @Test
    void testCategorizeOom() {
        assertEquals(FailureCategory.OUT_OF_MEMORY,
                BatchRetryPolicy.categorize("java.lang.OutOfMemoryError: Java heap space"));
        assertEquals(FailureCategory.OUT_OF_MEMORY,
                BatchRetryPolicy.categorize("GC overhead limit exceeded"));
    }

    @Test
    void testCategorizeGpuOom() {
        assertEquals(FailureCategory.GPU_OUT_OF_MEMORY,
                BatchRetryPolicy.categorize("CUDA out of memory"));
        assertEquals(FailureCategory.GPU_OUT_OF_MEMORY,
                BatchRetryPolicy.categorize("cublas runtime error"));
    }

    @Test
    void testCategorizeRateLimited() {
        assertEquals(FailureCategory.RATE_LIMITED,
                BatchRetryPolicy.categorize("429 Too Many Requests"));
        assertEquals(FailureCategory.RATE_LIMITED,
                BatchRetryPolicy.categorize("Rate limit exceeded"));
        assertEquals(FailureCategory.RATE_LIMITED,
                BatchRetryPolicy.categorize("Throttled by API"));
    }

    @Test
    void testCategorizeFatal() {
        assertEquals(FailureCategory.FATAL,
                BatchRetryPolicy.categorize("401 Unauthorized"));
        assertEquals(FailureCategory.FATAL,
                BatchRetryPolicy.categorize("403 Forbidden"));
        assertEquals(FailureCategory.FATAL,
                BatchRetryPolicy.categorize("Invalid API key"));
    }

    @Test
    void testCategorizeTimeout() {
        assertEquals(FailureCategory.TIMEOUT,
                BatchRetryPolicy.categorize("Connection timed out"));
        assertEquals(FailureCategory.TIMEOUT,
                BatchRetryPolicy.categorize("Deadline exceeded"));
    }

    @Test
    void testCategorizeBadResponse() {
        assertEquals(FailureCategory.BAD_RESPONSE,
                BatchRetryPolicy.categorize("Invalid response from server"));
        assertEquals(FailureCategory.BAD_RESPONSE,
                BatchRetryPolicy.categorize("JSON parse error"));
    }

    @Test
    void testCategorizeUnknown() {
        assertEquals(FailureCategory.UNKNOWN,
                BatchRetryPolicy.categorize("Something unexpected happened"));
        assertEquals(FailureCategory.UNKNOWN,
                BatchRetryPolicy.categorize(null));
    }

    @Test
    void testFatalAborts() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("TEST")
                .maxRetries(3)
                .build();

        List<String> items = List.of("a", "b");
        RetryDecision<String> decision = policy.evaluateFailure(
                "batch-0", items, 10, "401 Unauthorized", "backend-1", null, null);

        assertEquals(RetryAction.ABORT, decision.getAction());
        assertEquals(1, decision.getAttempt());
    }

    @Test
    void testOomShrinksBatch() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("TEST")
                .maxRetries(3)
                .batchShrinkFactor(0.5)
                .minBatchSize(1)
                .build();

        List<String> items = List.of("a", "b", "c");
        RetryDecision<String> decision = policy.evaluateFailure(
                "batch-0", items, 10, "OutOfMemoryError", "backend-1", null, null);

        assertEquals(RetryAction.RETRY_SAME_BACKEND, decision.getAction());
        assertEquals(5, decision.getReducedBatchSize()); // 10 * 0.5
        assertTrue(decision.getBackoffMs() > 0);
    }

    @Test
    void testOomRespectsMinBatchSize() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("TEST")
                .maxRetries(3)
                .batchShrinkFactor(0.5)
                .minBatchSize(4)
                .build();

        List<String> items = List.of("a", "b");
        RetryDecision<String> decision = policy.evaluateFailure(
                "batch-0", items, 6, "Out of memory", "backend-1", null, null);

        assertEquals(RetryAction.RETRY_SAME_BACKEND, decision.getAction());
        assertEquals(4, decision.getReducedBatchSize()); // max(4, 6*0.5=3) = 4
    }

    @Test
    void testTimeoutRetriesSameBackend() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("TEST")
                .maxRetries(3)
                .build();

        List<String> items = List.of("a");
        RetryDecision<String> decision = policy.evaluateFailure(
                "batch-0", items, 10, "Connection timed out", "backend-1", null, null);

        assertEquals(RetryAction.RETRY_SAME_BACKEND, decision.getAction());
        assertEquals(10, decision.getReducedBatchSize()); // unchanged for timeout
    }

    @Test
    void testExhaustedRetriesToDeadLetter() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("TEST")
                .maxRetries(2)
                .build();

        List<String> items = List.of("a", "b");
        // Same batch key across attempts → counter accumulates for this batch
        policy.evaluateFailure("batch-0", items, 10, "timed out", "b1", null, null); // attempt 1
        policy.evaluateFailure("batch-0", items, 10, "timed out", "b1", null, null); // attempt 2
        RetryDecision<String> decision = policy.evaluateFailure(
                "batch-0", items, 10, "timed out", "b1", null, null); // attempt 3 > maxRetries=2

        assertEquals(RetryAction.DEAD_LETTER, decision.getAction());
        assertEquals(2, policy.getDeadLetterCount());
        assertEquals(2, policy.getDeadLetterQueue().size());
    }

    @Test
    void testResetAttempts() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("TEST")
                .maxRetries(1)
                .build();

        List<String> items = List.of("a");
        policy.evaluateFailure("batch-0", items, 10, "timed out", "b1", null, null);
        // Now at attempt 1 — next would be dead letter
        policy.resetAttempts();
        // After reset, should start fresh
        RetryDecision<String> decision = policy.evaluateFailure(
                "batch-0", items, 10, "timed out", "b1", null, null);
        assertEquals(RetryAction.RETRY_SAME_BACKEND, decision.getAction());
        assertEquals(1, decision.getAttempt());
    }

    /**
     * Regression: attempts must be scoped per batch. Previously a single shared counter accumulated
     * failures across every batch, so a healthy batch could be dead-lettered on its first failure
     * once earlier batches had used up the retry budget. This is silent data loss.
     */
    @Test
    void testAttemptsAreScopedPerBatch() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("TEST")
                .maxRetries(2)
                .build();

        List<String> batchA = List.of("a");
        List<String> batchB = List.of("b");

        // Batch A exhausts its own budget (3 failures > maxRetries=2) and dead-letters.
        policy.evaluateFailure("batch-A", batchA, 10, "timed out", "b1", null, null);
        policy.evaluateFailure("batch-A", batchA, 10, "timed out", "b1", null, null);
        RetryDecision<String> aDecision =
                policy.evaluateFailure("batch-A", batchA, 10, "timed out", "b1", null, null);
        assertEquals(RetryAction.DEAD_LETTER, aDecision.getAction());

        // Batch B's FIRST failure must still be a retry — NOT dead-lettered by A's history.
        RetryDecision<String> bDecision =
                policy.evaluateFailure("batch-B", batchB, 10, "timed out", "b1", null, null);
        assertEquals(RetryAction.RETRY_SAME_BACKEND, bDecision.getAction());
        assertEquals(1, bDecision.getAttempt());
    }

    @Test
    void testRateLimitedReroutesViaSelector() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("GRAPH_EXTRACTION")
                .maxRetries(3)
                .build();

        FallbackBackendSelector selector = (stage, exclude) -> Optional.of("fallback-2");
        RetryDecision<String> decision = policy.evaluateFailure(
                "batch-0", List.of("x"), 10, "429 Too Many Requests", "primary-1", selector, null);

        assertEquals(RetryAction.RETRY_FALLBACK_BACKEND, decision.getAction());
        assertEquals("fallback-2", decision.getFallbackBackendId());
    }

    @Test
    void testRateLimitedHeavierBackoffWithoutSelector() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("TEST")
                .maxRetries(3)
                .initialBackoffMs(1000)
                .backoffMultiplier(2.0)
                .maxBackoffMs(60_000)
                .rateLimitBackoffMultiplier(3.0)
                .build();

        // No selector → same backend, but with a heavier rate-limit backoff (1000 * 3.0).
        RetryDecision<String> decision = policy.evaluateFailure(
                "batch-0", List.of("x"), 10, "rate limit exceeded", "b1", null, null);

        assertEquals(RetryAction.RETRY_SAME_BACKEND, decision.getAction());
        assertEquals(3000, decision.getBackoffMs());
    }

    @Test
    void testRateLimitedSelectorReturningSameBackendFallsBackToRetry() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("TEST")
                .maxRetries(3)
                .build();

        // Selector offers the SAME backend → not a real reroute → retry same backend.
        FallbackBackendSelector selector = (stage, exclude) -> Optional.of("b1");
        RetryDecision<String> decision = policy.evaluateFailure(
                "batch-0", List.of("x"), 10, "quota exceeded", "b1", selector, null);

        assertEquals(RetryAction.RETRY_SAME_BACKEND, decision.getAction());
    }

    @Test
    void testExponentialBackoff() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("TEST")
                .maxRetries(5)
                .initialBackoffMs(1000)
                .backoffMultiplier(2.0)
                .maxBackoffMs(10000)
                .build();

        List<String> items = List.of("a");

        RetryDecision<String> d1 = policy.evaluateFailure("batch-0", items, 10, "timed out", "b1", null, null);
        assertEquals(1000, d1.getBackoffMs()); // 1000 * 2^0

        RetryDecision<String> d2 = policy.evaluateFailure("batch-0", items, 10, "timed out", "b1", null, null);
        assertEquals(2000, d2.getBackoffMs()); // 1000 * 2^1

        RetryDecision<String> d3 = policy.evaluateFailure("batch-0", items, 10, "timed out", "b1", null, null);
        assertEquals(4000, d3.getBackoffMs()); // 1000 * 2^2

        RetryDecision<String> d4 = policy.evaluateFailure("batch-0", items, 10, "timed out", "b1", null, null);
        assertEquals(8000, d4.getBackoffMs()); // 1000 * 2^3

        RetryDecision<String> d5 = policy.evaluateFailure("batch-0", items, 10, "timed out", "b1", null, null);
        assertEquals(10000, d5.getBackoffMs()); // capped at maxBackoff
    }

    @Test
    void testFactoryMethods() {
        BatchRetryPolicy<String> graphPolicy = BatchRetryPolicy.forGraphExtraction();
        assertNotNull(graphPolicy);

        BatchRetryPolicy<String> vectorPolicy = BatchRetryPolicy.forVectorIndexing();
        assertNotNull(vectorPolicy);

        BatchRetryPolicy<String> embeddingPolicy = BatchRetryPolicy.forEmbedding();
        assertNotNull(embeddingPolicy);

        BatchRetryPolicy<String> llmPolicy = BatchRetryPolicy.forLlmPrompt();
        assertNotNull(llmPolicy);
    }

    @Test
    void testRecordsRetryEventOnJob() {
        BatchRetryPolicy<String> policy = BatchRetryPolicy.<String>builder()
                .stage("GRAPH_EXTRACTION")
                .maxRetries(3)
                .build();

        UnifiedCrawlJob job = UnifiedCrawlJob.builder().jobId("test-job").build();
        List<String> items = List.of("chunk-1", "chunk-2");

        policy.evaluateFailure("batch-0", items, 10, "timed out", "backend-1", null, job);

        assertEquals(1, job.getRetriedBatches().get());
        assertEquals(2, job.getRetriedItems().get());
        assertFalse(job.getRecentRetryEvents().isEmpty());

        var event = job.getRecentRetryEvents().get(0);
        assertEquals("GRAPH_EXTRACTION", event.getStage());
        assertEquals(1, event.getAttempt());
        assertEquals(2, event.getItemCount());
        assertEquals("backend-1", event.getBackendId());
    }
}
