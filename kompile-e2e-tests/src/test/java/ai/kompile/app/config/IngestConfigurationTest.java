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

package ai.kompile.app.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IngestConfiguration} — defaults, setter clamping,
 * job management (tryStartJob/completeJob), memory checks, batch sizing,
 * and the MemoryInfo record.
 */
class IngestConfigurationTest {

    private IngestConfiguration config;

    @BeforeEach
    void setUp() {
        config = new IngestConfiguration();
    }

    // ─── Defaults ───────────────────────────────────────────────────

    @Test
    void defaults_concurrentJobs() {
        assertEquals(4, config.getMaxConcurrentJobs());
    }

    @Test
    void defaults_batchSizes() {
        assertEquals(100, config.getIndexBatchSize());
        assertEquals(25, config.getMinBatchSize());
        assertEquals(500, config.getMaxBatchSize());
    }

    @Test
    void defaults_embeddingSettings() {
        assertEquals(64, config.getEmbeddingTargetBatchSize());
        assertEquals(500, config.getEmbeddingMaxWaitMs());
        assertEquals(25, config.getEmbeddingMinWaitMs());
    }

    @Test
    void defaults_memoryThresholds() {
        assertEquals(80, config.getMemoryThresholdPercent());
        assertEquals(90, config.getMemoryCriticalPercent());
        assertEquals(95, config.getMemoryKillThresholdPercent());
    }

    @Test
    void defaults_adaptiveBatchSizeEnabled() {
        assertTrue(config.isAdaptiveBatchSize());
    }

    @Test
    void defaults_chunkSettings() {
        assertEquals(1000, config.getDefaultChunkSize());
        assertEquals(100, config.getDefaultChunkOverlap());
        assertEquals("table-aware", config.getDefaultChunker());
    }

    @Test
    void defaults_pipelineThreading() {
        assertEquals(1, config.getEmbeddingThreads());
        assertEquals(4, config.getIndexingThreads());
        assertTrue(config.getChunkingThreads() > 0);
    }

    @Test
    void defaults_parallelIndexing() {
        assertTrue(config.isParallelIndexingEnabled());
    }

    // ─── Setter Clamping ────────────────────────────────────────────

    @Test
    void setMaxConcurrentJobs_clampsToRange() {
        config.setMaxConcurrentJobs(0);
        assertEquals(1, config.getMaxConcurrentJobs());

        config.setMaxConcurrentJobs(100);
        assertEquals(32, config.getMaxConcurrentJobs());
    }

    @Test
    void setIndexBatchSize_clampsToMinMax() {
        config.setIndexBatchSize(1);
        assertEquals(25, config.getIndexBatchSize()); // clamped to minBatchSize

        config.setIndexBatchSize(99999);
        assertEquals(500, config.getIndexBatchSize()); // clamped to maxBatchSize
    }

    @Test
    void setEmbeddingTargetBatchSize_clampsToRange() {
        config.setEmbeddingTargetBatchSize(1);
        assertEquals(8, config.getEmbeddingTargetBatchSize());

        config.setEmbeddingTargetBatchSize(99999);
        assertEquals(500, config.getEmbeddingTargetBatchSize()); // clamped to maxBatchSize
    }

    @Test
    void setEmbeddingMaxWaitMs_clampsToRange() {
        config.setEmbeddingMaxWaitMs(10);
        assertEquals(100, config.getEmbeddingMaxWaitMs());

        config.setEmbeddingMaxWaitMs(99999);
        assertEquals(5000, config.getEmbeddingMaxWaitMs());
    }

    @Test
    void setEmbeddingMinWaitMs_clampsToRange() {
        config.setEmbeddingMinWaitMs(1);
        assertEquals(10, config.getEmbeddingMinWaitMs());

        // min wait must be <= maxWait/2
        config.setEmbeddingMaxWaitMs(200);
        config.setEmbeddingMinWaitMs(200);
        assertTrue(config.getEmbeddingMinWaitMs() <= config.getEmbeddingMaxWaitMs() / 2);
    }

    @Test
    void setMemoryThresholdPercent_clampsTo0_100() {
        config.setMemoryThresholdPercent(-10);
        assertEquals(0, config.getMemoryThresholdPercent());

        config.setMemoryThresholdPercent(200);
        assertEquals(100, config.getMemoryThresholdPercent());
    }

    @Test
    void setMemoryCriticalPercent_clampedAboveThreshold() {
        config.setMemoryThresholdPercent(80);
        config.setMemoryCriticalPercent(70);
        // critical must be >= threshold
        assertEquals(80, config.getMemoryCriticalPercent());
    }

    @Test
    void setMemoryKillThresholdPercent_adjustedIfBelowCritical() {
        config.setMemoryCriticalPercent(90);
        config.setMemoryKillThresholdPercent(85);
        // kill must be >= critical
        assertEquals(90, config.getMemoryKillThresholdPercent());
    }

    @Test
    void setMemoryKillThresholdPercent_zeroDisablesKill() {
        config.setMemoryKillThresholdPercent(0);
        assertEquals(0, config.getMemoryKillThresholdPercent());
    }

    @Test
    void setDefaultChunkSize_clampsToRange() {
        config.setDefaultChunkSize(10);
        assertEquals(100, config.getDefaultChunkSize());

        config.setDefaultChunkSize(99999);
        assertEquals(10000, config.getDefaultChunkSize());
    }

    @Test
    void setDefaultChunkOverlap_clampsToHalfOfChunkSize() {
        config.setDefaultChunkSize(1000);
        config.setDefaultChunkOverlap(999);
        assertEquals(500, config.getDefaultChunkOverlap()); // max = chunkSize/2

        config.setDefaultChunkOverlap(-10);
        assertEquals(0, config.getDefaultChunkOverlap());
    }

    @Test
    void setDefaultChunker_nullFallsToDefault() {
        config.setDefaultChunker(null);
        assertEquals("recursive-character", config.getDefaultChunker());
    }

    @Test
    void setLargeDocumentSizeThreshold_clampsToRange() {
        config.setLargeDocumentSizeThreshold(100);
        assertEquals(1024 * 1024, config.getLargeDocumentSizeThreshold()); // min 1MB

        config.setLargeDocumentSizeThreshold(Long.MAX_VALUE);
        assertEquals(1024L * 1024 * 1024, config.getLargeDocumentSizeThreshold()); // max 1GB
    }

    @Test
    void setLargeDocumentPageThreshold_clampsToRange() {
        config.setLargeDocumentPageThreshold(1);
        assertEquals(10, config.getLargeDocumentPageThreshold());

        config.setLargeDocumentPageThreshold(99999);
        assertEquals(1000, config.getLargeDocumentPageThreshold());
    }

    @Test
    void setChunkingThreads_clampsToRange() {
        config.setChunkingThreads(0);
        assertEquals(1, config.getChunkingThreads());

        config.setChunkingThreads(1000);
        assertEquals(64, config.getChunkingThreads());
    }

    @Test
    void setEmbeddingThreads_clampsToRange() {
        config.setEmbeddingThreads(0);
        assertEquals(1, config.getEmbeddingThreads());

        config.setEmbeddingThreads(100);
        assertEquals(16, config.getEmbeddingThreads());
    }

    @Test
    void setIndexingThreads_clampsToRange() {
        config.setIndexingThreads(0);
        assertEquals(1, config.getIndexingThreads());

        config.setIndexingThreads(100);
        assertEquals(32, config.getIndexingThreads());
    }

    @Test
    void setPipelineQueueCapacity_clampsToRange() {
        config.setPipelineQueueCapacity(10);
        assertEquals(100, config.getPipelineQueueCapacity());

        config.setPipelineQueueCapacity(99999);
        assertEquals(10000, config.getPipelineQueueCapacity());
    }

    @Test
    void setMaxPoolSize_clampsAboveCorePoolSize() {
        config.setCorePoolSize(4);
        config.setMaxPoolSize(2);
        assertEquals(4, config.getMaxPoolSize());
    }

    // ─── Job Management ─────────────────────────────────────────────

    @Test
    void tryStartJob_success_incrementsCount() {
        assertTrue(config.tryStartJob());
        assertEquals(1, config.getActiveJobCount());
    }

    @Test
    void tryStartJob_atLimit_returnsFalse() {
        config.setMaxConcurrentJobs(2);
        assertTrue(config.tryStartJob());
        assertTrue(config.tryStartJob());
        assertFalse(config.tryStartJob());
    }

    @Test
    void completeJob_decrementsCount() {
        config.tryStartJob();
        config.tryStartJob();
        assertEquals(2, config.getActiveJobCount());

        config.completeJob();
        assertEquals(1, config.getActiveJobCount());
    }

    @Test
    void canAcceptNewJob_underLimit_returnsTrue() {
        // Memory usage in tests is typically well below threshold
        assertTrue(config.canAcceptNewJob());
    }

    @Test
    void canAcceptNewJob_atJobLimit_returnsFalse() {
        config.setMaxConcurrentJobs(1);
        config.tryStartJob();
        assertFalse(config.canAcceptNewJob());
    }

    // ─── Memory Info ────────────────────────────────────────────────

    @Test
    void getMemoryUsagePercent_returnsPositive() {
        double usage = config.getMemoryUsagePercent();
        assertTrue(usage > 0);
        assertTrue(usage < 100);
    }

    @Test
    void getMemoryInfo_returnsValidRecord() {
        IngestConfiguration.MemoryInfo info = config.getMemoryInfo();
        assertTrue(info.maxBytes() > 0);
        assertTrue(info.usedBytes() > 0);
        assertTrue(info.usagePercent() > 0);
        assertTrue(info.maxMB() > 0);
        assertTrue(info.usedMB() >= 0);
        assertTrue(info.freeMB() >= 0);
    }

    // ─── Optimal Batch Size ─────────────────────────────────────────

    @Test
    void calculateOptimalBatchSize_adaptiveDisabled_returnsDefault() {
        config.setAdaptiveBatchSize(false);
        int size = config.calculateOptimalBatchSize(1000);
        assertEquals(config.getIndexBatchSize(), size);
    }

    @Test
    void calculateOptimalBatchSize_adaptiveEnabled_returnsPositive() {
        config.setAdaptiveBatchSize(true);
        int size = config.calculateOptimalBatchSize(1000);
        assertTrue(size >= config.getMinBatchSize());
        assertTrue(size <= config.getMaxBatchSize());
    }

    @Test
    void calculateOptimalBatchSize_fewChunks_respectsMinBatches() {
        config.setAdaptiveBatchSize(true);
        int size = config.calculateOptimalBatchSize(10);
        assertTrue(size >= config.getMinBatchSize());
    }

    // ─── Chunking Options ───────────────────────────────────────────

    @Test
    void getChunkingOptions_containsExpectedKeys() {
        Map<String, Object> options = config.getChunkingOptions();
        assertEquals(1000, options.get("chunkSize"));
        assertEquals(100, options.get("overlap"));
        assertEquals(true, options.get("preserveParagraphs"));
    }

    // ─── Other Settings ─────────────────────────────────────────────

    @Test
    void enableResumeSupport_toggles() {
        assertTrue(config.isEnableResumeSupport());
        config.setEnableResumeSupport(false);
        assertFalse(config.isEnableResumeSupport());
    }

    @Test
    void setStateDirectory_nullFallsToDefault() {
        config.setStateDirectory(null);
        assertTrue(config.getStateDirectory().contains(".kompile/state"));
    }

    @Test
    void setQueueCapacity_clampsMin() {
        config.setQueueCapacity(0);
        assertEquals(1, config.getQueueCapacity());
    }

    @Test
    void setMinBatchSize_clampsMin() {
        config.setMinBatchSize(-5);
        assertEquals(1, config.getMinBatchSize());
    }

    @Test
    void setMaxBatchSize_clampsAboveMin() {
        config.setMinBatchSize(50);
        config.setMaxBatchSize(10);
        assertEquals(50, config.getMaxBatchSize());
    }

    @Test
    void setCorePoolSize_clampsMin() {
        config.setCorePoolSize(0);
        assertEquals(1, config.getCorePoolSize());
    }

    @Test
    void setIndexingBatchAccumulationSize_clampsToRange() {
        config.setIndexingBatchAccumulationSize(0);
        assertEquals(1, config.getIndexingBatchAccumulationSize());

        config.setIndexingBatchAccumulationSize(200);
        assertEquals(64, config.getIndexingBatchAccumulationSize());
    }

    @Test
    void setMaxBatchWaitMs_clampsToRange() {
        config.setMaxBatchWaitMs(10);
        assertEquals(100, config.getMaxBatchWaitMs());

        config.setMaxBatchWaitMs(99999);
        assertEquals(5000, config.getMaxBatchWaitMs());
    }

    @Test
    void setMinBatchWaitMs_clampsToRange() {
        config.setMinBatchWaitMs(1);
        assertEquals(10, config.getMinBatchWaitMs());
    }

    @Test
    void parallelIndexing_toggles() {
        config.setParallelIndexingEnabled(false);
        assertFalse(config.isParallelIndexingEnabled());
    }

    @Test
    void setTaskExecutor_noError() {
        assertDoesNotThrow(() -> config.setTaskExecutor(null));
    }
}
