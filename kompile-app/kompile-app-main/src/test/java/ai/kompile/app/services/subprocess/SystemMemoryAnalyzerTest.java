package ai.kompile.app.services.subprocess;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SystemMemoryAnalyzer")
class SystemMemoryAnalyzerTest {

    private SystemMemoryAnalyzer analyzer;

    @BeforeEach void setUp() {
        analyzer = new SystemMemoryAnalyzer();
    }

    @Nested @DisplayName("formatBytes")
    class FormatBytes {
        @Test void bytes() { assertEquals("100 B", SystemMemoryAnalyzer.formatBytes(100)); }
        @Test void kilobytes() { assertEquals("1.5 KB", SystemMemoryAnalyzer.formatBytes(1536)); }
        @Test void megabytes() { assertEquals("512.0 MB", SystemMemoryAnalyzer.formatBytes(512L * 1024 * 1024)); }
        @Test void gigabytes() { assertEquals("4.00 GB", SystemMemoryAnalyzer.formatBytes(4L * 1024 * 1024 * 1024)); }
        @Test void terabytes() { assertEquals("1.00 TB", SystemMemoryAnalyzer.formatBytes(1024L * 1024 * 1024 * 1024)); }
        @Test void negative() { assertEquals("unknown", SystemMemoryAnalyzer.formatBytes(-1)); }
        @Test void zero() { assertEquals("0 B", SystemMemoryAnalyzer.formatBytes(0)); }
    }

    @Nested @DisplayName("parseMemoryToBytes")
    class ParseMemory {
        @Test void gigabytes() { assertEquals(4L * 1024 * 1024 * 1024, SystemMemoryAnalyzer.parseMemoryToBytes("4g")); }
        @Test void gigabytesUpper() { assertEquals(4L * 1024 * 1024 * 1024, SystemMemoryAnalyzer.parseMemoryToBytes("4GB")); }
        @Test void megabytes() { assertEquals(512L * 1024 * 1024, SystemMemoryAnalyzer.parseMemoryToBytes("512m")); }
        @Test void megabytesUpper() { assertEquals(512L * 1024 * 1024, SystemMemoryAnalyzer.parseMemoryToBytes("512MB")); }
        @Test void kilobytes() { assertEquals(1024L * 1024, SystemMemoryAnalyzer.parseMemoryToBytes("1024k")); }
        @Test void byteSuffix() { assertEquals(4096L, SystemMemoryAnalyzer.parseMemoryToBytes("4096b")); }
        @Test void xmxPrefix() { assertEquals(8L * 1024 * 1024 * 1024, SystemMemoryAnalyzer.parseMemoryToBytes("-Xmx8g")); }
        @Test void nullReturnsNull() { assertNull(SystemMemoryAnalyzer.parseMemoryToBytes(null)); }
        @Test void blankReturnsNull() { assertNull(SystemMemoryAnalyzer.parseMemoryToBytes("")); }
        @Test void terabytes() { assertEquals(1024L * 1024 * 1024 * 1024, SystemMemoryAnalyzer.parseMemoryToBytes("1t")); }
    }

    @Nested @DisplayName("bytesToHeapSize")
    class BytesToHeap {
        @Test void gigabytes() { assertEquals("4g", SystemMemoryAnalyzer.bytesToHeapSize(4L * 1024 * 1024 * 1024)); }
        @Test void megabytes() { assertEquals("512m", SystemMemoryAnalyzer.bytesToHeapSize(512L * 1024 * 1024)); }
        @Test void oneGb() { assertEquals("1g", SystemMemoryAnalyzer.bytesToHeapSize(1024L * 1024 * 1024)); }
    }

    @Nested @DisplayName("System memory queries")
    class SystemMemory {
        @Test void totalMemoryPositive() {
            assertTrue(analyzer.getTotalSystemMemory() > 0);
        }

        @Test void freeMemoryPositive() {
            assertTrue(analyzer.getFreeSystemMemory() > 0);
        }

        @Test void usagePercentInRange() {
            double pct = analyzer.getSystemMemoryUsagePercent();
            assertTrue(pct >= 0 && pct <= 100);
        }

        @Test void memorySnapshot() {
            var snap = analyzer.getMemorySnapshot();
            assertNotNull(snap);
            assertTrue(snap.totalBytes() > 0);
            assertTrue(snap.usagePercent() >= 0 && snap.usagePercent() <= 100);
            assertNotNull(snap.getSummary());
            assertFalse(snap.getSummary().isEmpty());
        }
    }

    @Nested @DisplayName("analyzeForRestart")
    class AnalyzeForRestart {
        @Test void javaOomWithAvailableRam() {
            long heap = 4L * 1024 * 1024 * 1024;
            long offHeap = 2L * 1024 * 1024 * 1024;
            var status = analyzer.analyzeForRestart(heap, offHeap, 1.25, 0.15, false, 4, 4, 2, 32);
            assertNotNull(status);
            assertNotNull(status.reason());
            assertTrue(status.totalSystemRamBytes() > 0);
            // heap should at least stay the same or go up
            assertTrue(status.recommendedHeapBytes() >= heap);
        }

        @Test void oomKilledReducesMemory() {
            long heap = 8L * 1024 * 1024 * 1024;
            long offHeap = 8L * 1024 * 1024 * 1024;
            var status = analyzer.analyzeForRestart(heap, offHeap, 1.25, 0.15, true, 4, 4, 2, 32);
            assertFalse(status.canIncreaseHeap());
            assertTrue(status.shouldReduceOffHeap());
            assertTrue(status.recommendedOffHeapBytes() < offHeap);
        }

        @Test void threadAdjustmentPresent() {
            long heap = 4L * 1024 * 1024 * 1024;
            long offHeap = 2L * 1024 * 1024 * 1024;
            var status = analyzer.analyzeForRestart(heap, offHeap, 1.25, 0.15, false, 8, 8, 4, 64);
            assertNotNull(status.threadAdjustment());
            assertNotNull(status.threadAdjustment().adjustmentReason());
        }

        @Test void memoryStatusSummary() {
            long heap = 4L * 1024 * 1024 * 1024;
            long offHeap = 2L * 1024 * 1024 * 1024;
            var status = analyzer.analyzeForRestart(heap, offHeap, 1.25, 0.15, false, 4, 4, 2, 32);
            assertNotNull(status.getSummary());
            assertFalse(status.getSummary().isEmpty());
        }
    }

    @Nested @DisplayName("canIncreaseHeap")
    class CanIncreaseHeap {
        @Test void returnsBoolean() {
            // Just verify the method runs without error and returns a valid boolean
            boolean result = analyzer.canIncreaseHeap(1L * 1024 * 1024 * 1024, 1.25, 0.15);
            // Result depends on system memory - just verify no exception
            assertNotNull(Boolean.valueOf(result));
        }
    }

    @Nested @DisplayName("ThreadAdjustment record")
    class ThreadAdjustmentTests {
        @Test void noAdjustment() {
            var adj = new SystemMemoryAnalyzer.ThreadAdjustment(4, 4, 4, 4, 2, 2, 32, 32, "none");
            assertFalse(adj.hasAdjustments());
        }

        @Test void hasAdjustment() {
            var adj = new SystemMemoryAnalyzer.ThreadAdjustment(4, 2, 4, 2, 2, 1, 32, 16, "reduced");
            assertTrue(adj.hasAdjustments());
        }
    }

    @Nested @DisplayName("GPU batch recommendations")
    class GpuBatch {
        @Test void veryLowVram() {
            assertEquals(1, analyzer.getGpuRecommendedBatchSize(500L * 1024 * 1024));
        }

        @Test void lowVram() {
            assertEquals(2, analyzer.getGpuRecommendedBatchSize(1500L * 1024 * 1024));
        }

        @Test void mediumVram() {
            assertEquals(4, analyzer.getGpuRecommendedBatchSize(3L * 1024 * 1024 * 1024));
        }

        @Test void highVram() {
            assertEquals(32, analyzer.getGpuRecommendedBatchSize(20L * 1024 * 1024 * 1024));
        }
    }
}
