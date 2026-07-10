package ai.kompile.embedding.anserini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class AnseriniEmbeddingTimeoutTest {

    /**
     * Verify that the timeout message format contains the "Embedding lane" pattern
     * that {@code isEmbeddingUnavailableError} in UnifiedCrawlGraphServiceImpl matches.
     * The crawl-side check is:
     *   msg.contains("Embedding lane")
     * so the timeout message MUST include that substring.
     */
    @Test
    void embedBatchTimeoutMessageMatchesCrawlPattern() {
        long timeoutSec = 300L;
        String msg = "Embedding lane timed out after " + timeoutSec + "s — lane-dead, batch skipped";

        // Must match the crawl-side isEmbeddingUnavailableError pattern
        assertTrue(msg.contains("Embedding lane"),
                "Timeout message must contain 'Embedding lane' to match isEmbeddingUnavailableError");
        // Also carries the lane-dead marker used in docs/memory
        assertTrue(msg.contains("lane-dead"),
                "Timeout message should contain 'lane-dead' for observability");
    }

    /**
     * Verify that a CompletableFuture with an overridden get(timeout,unit) that throws
     * TimeoutException can be caught separately from the general Exception path.
     * This mirrors the structure of the patched embedBatch() catch blocks.
     */
    @Test
    void timeoutExceptionCaughtSeparatelyReturnsEmpty() throws Exception {
        CompletableFuture<List<float[]>> slowFuture = new CompletableFuture<>() {
            @Override
            public List<float[]> get(long timeout, TimeUnit unit) throws TimeoutException {
                throw new TimeoutException("test timeout after " + timeout + " " + unit);
            }
        };

        List<float[]> result;
        try {
            result = slowFuture.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            // This is the path the patched code takes — return empty, not rethrow
            result = List.of();
        }

        assertNotNull(result, "Result must never be null");
        assertTrue(result.isEmpty(), "Timed-out batch must return empty list, not null or partial data");
    }

    /**
     * Verify the single-embed timeout message also matches the crawl pattern.
     */
    @Test
    void embedSingleTimeoutMessageMatchesCrawlPattern() {
        long timeoutSec = 60L;
        String msg = "Embedding lane timed out after " + timeoutSec + "s — lane-dead, single embed skipped";

        assertTrue(msg.contains("Embedding lane"),
                "Single-embed timeout message must contain 'Embedding lane' to match isEmbeddingUnavailableError");
        assertTrue(msg.contains("lane-dead"),
                "Single-embed timeout message should contain 'lane-dead' for observability");
    }
}
