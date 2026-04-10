package ai.kompile.app.services;

import ai.kompile.app.config.MemoryPoolConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemoryPoolManager")
class MemoryPoolManagerTest {

    @TempDir Path tempDir;

    @Nested @DisplayName("MemoryPool unit tests")
    class MemoryPoolTests {
        @Test void allocateAndFree() {
            var pool = new MemoryPool(MemoryPool.PoolType.WEIGHTS, 1000L);
            assertTrue(pool.tryAllocate(500));
            assertEquals(500L, pool.getAllocatedBytes());
            assertEquals(0.5, pool.getUtilization(), 0.01);

            pool.free(200);
            assertEquals(300L, pool.getAllocatedBytes());

            assertFalse(pool.tryAllocate(800));
            assertEquals(300L, pool.getAllocatedBytes());
        }

        @Test void freeDoesNotGoNegative() {
            var pool = new MemoryPool(MemoryPool.PoolType.ACTIVATIONS, 1000L);
            pool.tryAllocate(100);
            pool.free(200);
            assertEquals(0L, pool.getAllocatedBytes());
        }

        @Test void zeroCapacityPool() {
            var pool = new MemoryPool(MemoryPool.PoolType.KV_CACHE, 0L);
            assertFalse(pool.tryAllocate(1));
            assertEquals(0.0, pool.getUtilization(), 0.01);
        }

        @Test void exactCapacityAllocation() {
            var pool = new MemoryPool(MemoryPool.PoolType.WEIGHTS, 500L);
            assertTrue(pool.tryAllocate(500));
            assertEquals(1.0, pool.getUtilization(), 0.01);
            assertFalse(pool.tryAllocate(1));
        }

        @Test void availableBytes() {
            var pool = new MemoryPool(MemoryPool.PoolType.WEIGHTS, 1000L);
            assertEquals(1000L, pool.getAvailableBytes());
            pool.tryAllocate(300);
            assertEquals(700L, pool.getAvailableBytes());
        }

        @Test void poolType() {
            var pool = new MemoryPool(MemoryPool.PoolType.KV_CACHE, 1000L);
            assertEquals(MemoryPool.PoolType.KV_CACHE, pool.getType());
        }

        @Test void multipleAllocations() {
            var pool = new MemoryPool(MemoryPool.PoolType.WEIGHTS, 1000L);
            assertTrue(pool.tryAllocate(300));
            assertTrue(pool.tryAllocate(300));
            assertTrue(pool.tryAllocate(300));
            assertFalse(pool.tryAllocate(200)); // only 100 left
            assertEquals(900L, pool.getAllocatedBytes());
        }
    }

    @Nested @DisplayName("MemoryPoolConfig defaults")
    class ConfigTests {
        @Test void defaultValues() {
            var config = MemoryPoolConfig.defaults();
            assertTrue(config.isEnabled());
            assertEquals(0.50, config.getWeightsFraction(), 0.01);
            assertEquals(0.25, config.getActivationsFraction(), 0.01);
            assertEquals(0.20, config.getKvCacheFraction(), 0.01);
            assertEquals(0.90, config.getPressureThreshold(), 0.01);
        }

        @Test void fractionsSum() {
            var config = MemoryPoolConfig.defaults();
            double sum = config.getWeightsFraction() + config.getActivationsFraction() + config.getKvCacheFraction();
            assertTrue(sum <= 1.0, "Fractions should sum to <= 1.0, got " + sum);
        }
    }

    @Nested @DisplayName("MemoryPoolManager with null GPU manager")
    class NullGpuManager {
        @Test void createsWithoutGpu() {
            // Should not throw during construction
            var manager = new MemoryPoolManager(null, tempDir.toString());
            assertNotNull(manager);
        }

        @Test void statusReturnsWithoutGpu() {
            var manager = new MemoryPoolManager(null, tempDir.toString());
            // init() not called (no @PostConstruct in test), but getStatus should work
            var status = manager.getStatus();
            assertNotNull(status);
            assertTrue((Boolean) status.get("enabled"));
        }

        @Test void isEnabledReflectsConfig() {
            var manager = new MemoryPoolManager(null, tempDir.toString());
            assertTrue(manager.isEnabled());
        }
    }
}
