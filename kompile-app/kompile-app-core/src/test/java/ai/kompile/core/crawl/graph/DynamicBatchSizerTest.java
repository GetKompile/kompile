package ai.kompile.core.crawl.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DynamicBatchSizerTest {

    @Test
    void testInitialBatchSize() {
        DynamicBatchSizer sizer = DynamicBatchSizer.builder()
                .initialBatchSize(32)
                .minBatchSize(1)
                .maxBatchSize(128)
                .build();
        assertEquals(32, sizer.currentBatchSize());
    }

    @Test
    void testAdditiveIncreaseOnSuccess() {
        DynamicBatchSizer sizer = DynamicBatchSizer.builder()
                .initialBatchSize(10)
                .minBatchSize(1)
                .maxBatchSize(100)
                .additiveIncrease(2)
                .successThresholdForIncrease(2)
                .build();

        // Two successes at low memory → should increase
        sizer.recordBatchResult(10, 100, true, 0.50);
        assertEquals(10, sizer.currentBatchSize()); // only 1 success, need 2

        sizer.recordBatchResult(10, 100, true, 0.50);
        assertEquals(12, sizer.currentBatchSize()); // now 2 successes → +2
        assertEquals(DynamicBatchSizer.AdjustDirection.UP, sizer.getLastDirection());
    }

    @Test
    void testMultiplicativeDecreaseOnFailure() {
        DynamicBatchSizer sizer = DynamicBatchSizer.builder()
                .initialBatchSize(20)
                .minBatchSize(1)
                .maxBatchSize(100)
                .multiplicativeDecrease(0.5)
                .build();

        sizer.recordBatchResult(20, 100, false, 0.50);
        assertEquals(10, sizer.currentBatchSize()); // 20 * 0.5 = 10
        assertEquals(DynamicBatchSizer.AdjustDirection.DOWN, sizer.getLastDirection());
        assertEquals("batch_failure", sizer.getLastReason());
    }

    @Test
    void testMemoryPressureShrink() {
        DynamicBatchSizer sizer = DynamicBatchSizer.builder()
                .initialBatchSize(20)
                .minBatchSize(1)
                .maxBatchSize(100)
                .memoryPressureThreshold(0.80)
                .memoryCriticalThreshold(0.90)
                .additiveIncrease(2)
                .multiplicativeDecrease(0.5)
                .build();

        // Success but at critical memory
        sizer.recordBatchResult(20, 100, true, 0.92);
        assertEquals(10, sizer.currentBatchSize()); // multiplicative decrease
        assertEquals("memory_critical", sizer.getLastReason());
    }

    @Test
    void testMemoryPressureMildShrink() {
        DynamicBatchSizer sizer = DynamicBatchSizer.builder()
                .initialBatchSize(20)
                .minBatchSize(1)
                .maxBatchSize(100)
                .memoryPressureThreshold(0.80)
                .memoryCriticalThreshold(0.90)
                .additiveIncrease(2)
                .build();

        sizer.recordBatchResult(20, 100, true, 0.85);
        assertEquals(18, sizer.currentBatchSize()); // 20 - 2 = 18
        assertEquals("memory_pressure", sizer.getLastReason());
    }

    @Test
    void testNeverGoBelowMin() {
        DynamicBatchSizer sizer = DynamicBatchSizer.builder()
                .initialBatchSize(2)
                .minBatchSize(1)
                .maxBatchSize(100)
                .multiplicativeDecrease(0.5)
                .build();

        sizer.recordBatchResult(2, 100, false, 0.50);
        assertEquals(1, sizer.currentBatchSize()); // 2 * 0.5 = 1 (min)

        sizer.recordBatchResult(1, 100, false, 0.50);
        assertEquals(1, sizer.currentBatchSize()); // can't go below 1
    }

    @Test
    void testNeverGoAboveMax() {
        DynamicBatchSizer sizer = DynamicBatchSizer.builder()
                .initialBatchSize(98)
                .minBatchSize(1)
                .maxBatchSize(100)
                .additiveIncrease(5)
                .successThresholdForIncrease(1)
                .build();

        sizer.recordBatchResult(98, 100, true, 0.50);
        assertEquals(100, sizer.currentBatchSize()); // clamped to max
    }

    @Test
    void testEmergencyShrink() {
        DynamicBatchSizer sizer = DynamicBatchSizer.builder()
                .initialBatchSize(64)
                .minBatchSize(1)
                .maxBatchSize(128)
                .multiplicativeDecrease(0.5)
                .build();

        sizer.emergencyShrink("oom_signal");
        // Double multiplicative decrease: 64 * 0.5 * 0.5 = 16
        assertEquals(16, sizer.currentBatchSize());
        assertEquals("oom_signal", sizer.getLastReason());
    }

    @Test
    void testEmaLatencyTracking() {
        DynamicBatchSizer sizer = DynamicBatchSizer.builder()
                .initialBatchSize(10)
                .minBatchSize(1)
                .maxBatchSize(100)
                .emaAlpha(1.0) // no smoothing for test
                .successThresholdForIncrease(999) // prevent growth
                .build();

        sizer.recordBatchResult(10, 1000, true, 0.50);
        // per-item latency = 1000ms / 10 items = 100ms → * 100 = 10000
        assertEquals(10000, sizer.getEmaLatencyMsX100());
    }

    @Test
    void testStageFactoryMethods() {
        DynamicBatchSizer embedding = DynamicBatchSizer.forEmbedding(64);
        assertEquals("EMBEDDING", embedding.getStageId());
        assertTrue(embedding.currentBatchSize() > 0);
        assertTrue(embedding.currentBatchSize() <= 64);

        DynamicBatchSizer graph = DynamicBatchSizer.forGraphExtraction(20);
        assertEquals("GRAPH_EXTRACTION", graph.getStageId());

        DynamicBatchSizer vector = DynamicBatchSizer.forVectorIndexing(48);
        assertEquals("VECTOR_INDEXING", vector.getStageId());

        DynamicBatchSizer chunking = DynamicBatchSizer.forChunking(100);
        assertEquals("CHUNKING", chunking.getStageId());
        assertEquals(100, chunking.currentBatchSize()); // starts at max
    }

    @Test
    void testPublishStats() {
        DynamicBatchSizer sizer = DynamicBatchSizer.builder()
                .initialBatchSize(16)
                .minBatchSize(1)
                .maxBatchSize(64)
                .successThresholdForIncrease(1)
                .additiveIncrease(4)
                .build();

        sizer.recordBatchResult(16, 200, true, 0.50);

        UnifiedCrawlJob job = UnifiedCrawlJob.builder().jobId("test").build();
        sizer.publishStats(job);

        assertEquals(20, job.getAdaptiveBatchSize().get()); // 16 + 4
        assertEquals(1, job.getBatchSizeAdjustments().get());
        assertEquals("UP", job.getLastBatchAdjustDirection().get());
    }
}
