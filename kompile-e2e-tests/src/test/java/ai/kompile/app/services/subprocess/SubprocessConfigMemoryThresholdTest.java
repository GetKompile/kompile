package ai.kompile.app.services.subprocess;

import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigResponse;
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigUpdate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SubprocessConfigService off-heap and GPU memory threshold
 * configuration, including defaults, updates, reset, and persistence.
 */
@DisplayName("SubprocessConfigService Memory Thresholds")
class SubprocessConfigMemoryThresholdTest {

    @TempDir Path tempDir;

    private SubprocessConfigService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SubprocessConfigService(null, tempDir.toString());
        // Set all default @Value fields via reflection
        setField(service, "defaultEnabled", false);
        setField(service, "defaultJavaPath", "java");
        setField(service, "defaultHeapSize", "4g");
        setField(service, "defaultOffHeapMaxBytes", "");
        setField(service, "defaultOffHeapMultiplier", 2);
        setField(service, "defaultTimeoutMinutes", 60);
        setField(service, "defaultVlmHeapSize", "16g");
        setField(service, "defaultVlmOffHeapMultiplier", 3);
        setField(service, "defaultVlmTimeoutMinutes", 30);
        setField(service, "defaultVlmCudaPinnedHostLimitMb", 8192);
        setField(service, "defaultHeartbeatIntervalSeconds", 10);
        setField(service, "defaultStaleThresholdSeconds", 120);
        setField(service, "defaultQueueCapacity", 1000);
        setField(service, "defaultParallelIndexing", true);
        setField(service, "defaultIndexingWorkers", 4);
        setField(service, "defaultIndexingBatchAccumulationSize", 8);
        setField(service, "defaultRestartEnabled", true);
        setField(service, "defaultMaxRestartAttempts", 3);
        setField(service, "defaultInitialBackoffMs", 5000);
        setField(service, "defaultBackoffMultiplier", 2.0);
        setField(service, "defaultHeapIncreaseFactor", 1.25);
        setField(service, "defaultSystemRamSafetyMargin", 0.15);
        setField(service, "defaultRestartOnStall", true);
        setField(service, "defaultRestartOnTimeout", true);
        setField(service, "defaultStallDetectionThresholdSeconds", 300);
        setField(service, "defaultProgressStallWarningSeconds", 60);
        setField(service, "defaultNativeExecutableMode", "auto");
        setField(service, "defaultNativeExecutablePath", "");
        setField(service, "defaultIngestExecutablePath", "");
        setField(service, "defaultVectorPopulationExecutablePath", "");
        setField(service, "defaultEmbeddingExecutablePath", "");
        setField(service, "defaultModelInitExecutablePath", "");
        setField(service, "defaultSubprocessTypeFlag", "--subprocess=");
        // Memory threshold defaults
        setField(service, "defaultOffHeapThresholdPercent", 80);
        setField(service, "defaultOffHeapCriticalPercent", 90);
        setField(service, "defaultOffHeapKillThresholdPercent", 95);
        setField(service, "defaultGpuMemoryThresholdPercent", 75);
        setField(service, "defaultGpuMemoryCriticalPercent", 85);
        setField(service, "defaultGpuMemoryKillThresholdPercent", 92);

        service.loadPersistedConfig();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // Helper to create an update with only memory threshold fields set
    private SubprocessConfigUpdate memoryThresholdUpdate(
            Integer offHeapThreshold, Integer offHeapCritical, Integer offHeapKill,
            Integer gpuThreshold, Integer gpuCritical, Integer gpuKill) {
        return new SubprocessConfigUpdate(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null,
                offHeapThreshold, offHeapCritical, offHeapKill,
                gpuThreshold, gpuCritical, gpuKill,
                null); // gpuSoftLimitPercent
    }

    @Nested @DisplayName("Default memory threshold values")
    class DefaultValues {

        @Test @DisplayName("off-heap threshold defaults to 80%")
        void offHeapThresholdDefault() {
            assertEquals(80, service.getOffHeapThresholdPercent());
        }

        @Test @DisplayName("off-heap critical defaults to 90%")
        void offHeapCriticalDefault() {
            assertEquals(90, service.getOffHeapCriticalPercent());
        }

        @Test @DisplayName("off-heap kill defaults to 95%")
        void offHeapKillDefault() {
            assertEquals(95, service.getOffHeapKillThresholdPercent());
        }

        @Test @DisplayName("GPU threshold defaults to 75%")
        void gpuThresholdDefault() {
            assertEquals(75, service.getGpuMemoryThresholdPercent());
        }

        @Test @DisplayName("GPU critical defaults to 85%")
        void gpuCriticalDefault() {
            assertEquals(85, service.getGpuMemoryCriticalPercent());
        }

        @Test @DisplayName("GPU kill defaults to 92%")
        void gpuKillDefault() {
            assertEquals(92, service.getGpuMemoryKillThresholdPercent());
        }

        @Test @DisplayName("getConfiguration includes all memory thresholds")
        void getConfigurationIncludesThresholds() {
            SubprocessConfigResponse config = service.getConfiguration();
            assertEquals(80, config.offHeapThresholdPercent());
            assertEquals(90, config.offHeapCriticalPercent());
            assertEquals(95, config.offHeapKillThresholdPercent());
            assertEquals(75, config.gpuMemoryThresholdPercent());
            assertEquals(85, config.gpuMemoryCriticalPercent());
            assertEquals(92, config.gpuMemoryKillThresholdPercent());
        }
    }

    @Nested @DisplayName("Update memory thresholds")
    class UpdateThresholds {

        @Test @DisplayName("updates off-heap thresholds")
        void updatesOffHeapThresholds() {
            service.updateConfiguration(memoryThresholdUpdate(70, 85, 92, null, null, null));

            assertEquals(70, service.getOffHeapThresholdPercent());
            assertEquals(85, service.getOffHeapCriticalPercent());
            assertEquals(92, service.getOffHeapKillThresholdPercent());
        }

        @Test @DisplayName("updates GPU thresholds")
        void updatesGpuThresholds() {
            service.updateConfiguration(memoryThresholdUpdate(null, null, null, 60, 80, 90));

            assertEquals(60, service.getGpuMemoryThresholdPercent());
            assertEquals(80, service.getGpuMemoryCriticalPercent());
            assertEquals(90, service.getGpuMemoryKillThresholdPercent());
        }

        @Test @DisplayName("updates both off-heap and GPU simultaneously")
        void updatesBothSimultaneously() {
            service.updateConfiguration(memoryThresholdUpdate(65, 78, 88, 55, 70, 82));

            assertEquals(65, service.getOffHeapThresholdPercent());
            assertEquals(78, service.getOffHeapCriticalPercent());
            assertEquals(88, service.getOffHeapKillThresholdPercent());
            assertEquals(55, service.getGpuMemoryThresholdPercent());
            assertEquals(70, service.getGpuMemoryCriticalPercent());
            assertEquals(82, service.getGpuMemoryKillThresholdPercent());
        }

        @Test @DisplayName("null values leave thresholds unchanged")
        void nullValuesUnchanged() {
            // First set custom values
            service.updateConfiguration(memoryThresholdUpdate(70, 85, 92, 60, 80, 90));

            // Then update with all nulls
            service.updateConfiguration(memoryThresholdUpdate(null, null, null, null, null, null));

            // Values should remain at previous settings
            assertEquals(70, service.getOffHeapThresholdPercent());
            assertEquals(85, service.getOffHeapCriticalPercent());
            assertEquals(92, service.getOffHeapKillThresholdPercent());
            assertEquals(60, service.getGpuMemoryThresholdPercent());
            assertEquals(80, service.getGpuMemoryCriticalPercent());
            assertEquals(90, service.getGpuMemoryKillThresholdPercent());
        }

        @Test @DisplayName("partial update only changes specified fields")
        void partialUpdateOnlyChangesSpecified() {
            // Update only off-heap threshold
            service.updateConfiguration(memoryThresholdUpdate(50, null, null, null, null, null));

            assertEquals(50, service.getOffHeapThresholdPercent());
            // Others should remain at defaults
            assertEquals(90, service.getOffHeapCriticalPercent());
            assertEquals(95, service.getOffHeapKillThresholdPercent());
            assertEquals(75, service.getGpuMemoryThresholdPercent());
        }

        @Test @DisplayName("getConfiguration reflects updated values")
        void getConfigurationReflectsUpdates() {
            service.updateConfiguration(memoryThresholdUpdate(65, 78, 88, 55, 70, 82));

            SubprocessConfigResponse config = service.getConfiguration();
            assertEquals(65, config.offHeapThresholdPercent());
            assertEquals(78, config.offHeapCriticalPercent());
            assertEquals(88, config.offHeapKillThresholdPercent());
            assertEquals(55, config.gpuMemoryThresholdPercent());
            assertEquals(70, config.gpuMemoryCriticalPercent());
            assertEquals(82, config.gpuMemoryKillThresholdPercent());
        }
    }

    @Nested @DisplayName("Reset to defaults")
    class ResetToDefaults {

        @Test @DisplayName("resets off-heap thresholds to defaults")
        void resetsOffHeapThresholds() {
            service.updateConfiguration(memoryThresholdUpdate(50, 60, 70, null, null, null));
            assertEquals(50, service.getOffHeapThresholdPercent());

            service.resetToDefaults();

            assertEquals(80, service.getOffHeapThresholdPercent());
            assertEquals(90, service.getOffHeapCriticalPercent());
            assertEquals(95, service.getOffHeapKillThresholdPercent());
        }

        @Test @DisplayName("resets GPU thresholds to defaults")
        void resetsGpuThresholds() {
            service.updateConfiguration(memoryThresholdUpdate(null, null, null, 40, 50, 60));
            assertEquals(40, service.getGpuMemoryThresholdPercent());

            service.resetToDefaults();

            assertEquals(75, service.getGpuMemoryThresholdPercent());
            assertEquals(85, service.getGpuMemoryCriticalPercent());
            assertEquals(92, service.getGpuMemoryKillThresholdPercent());
        }
    }

    @Nested @DisplayName("Persistence")
    class Persistence {

        @Test @DisplayName("memory thresholds survive reload")
        void thresholdsSurviveReload() throws Exception {
            // Update thresholds
            service.updateConfiguration(memoryThresholdUpdate(65, 78, 88, 55, 70, 82));

            // Create a new service instance pointing to same temp dir
            SubprocessConfigService service2 = new SubprocessConfigService(null, tempDir.toString());
            // Set defaults
            setField(service2, "defaultEnabled", false);
            setField(service2, "defaultJavaPath", "java");
            setField(service2, "defaultHeapSize", "4g");
            setField(service2, "defaultOffHeapMaxBytes", "");
            setField(service2, "defaultOffHeapMultiplier", 2);
            setField(service2, "defaultTimeoutMinutes", 60);
            setField(service2, "defaultVlmHeapSize", "16g");
            setField(service2, "defaultVlmOffHeapMultiplier", 3);
            setField(service2, "defaultVlmTimeoutMinutes", 30);
            setField(service2, "defaultVlmCudaPinnedHostLimitMb", 8192);
            setField(service2, "defaultHeartbeatIntervalSeconds", 10);
            setField(service2, "defaultStaleThresholdSeconds", 120);
            setField(service2, "defaultQueueCapacity", 1000);
            setField(service2, "defaultParallelIndexing", true);
            setField(service2, "defaultIndexingWorkers", 4);
            setField(service2, "defaultIndexingBatchAccumulationSize", 8);
            setField(service2, "defaultRestartEnabled", true);
            setField(service2, "defaultMaxRestartAttempts", 3);
            setField(service2, "defaultInitialBackoffMs", 5000);
            setField(service2, "defaultBackoffMultiplier", 2.0);
            setField(service2, "defaultHeapIncreaseFactor", 1.25);
            setField(service2, "defaultSystemRamSafetyMargin", 0.15);
            setField(service2, "defaultRestartOnStall", true);
            setField(service2, "defaultRestartOnTimeout", true);
            setField(service2, "defaultStallDetectionThresholdSeconds", 300);
            setField(service2, "defaultProgressStallWarningSeconds", 60);
            setField(service2, "defaultNativeExecutableMode", "auto");
            setField(service2, "defaultNativeExecutablePath", "");
            setField(service2, "defaultIngestExecutablePath", "");
            setField(service2, "defaultVectorPopulationExecutablePath", "");
            setField(service2, "defaultEmbeddingExecutablePath", "");
            setField(service2, "defaultModelInitExecutablePath", "");
            setField(service2, "defaultSubprocessTypeFlag", "--subprocess=");
            setField(service2, "defaultOffHeapThresholdPercent", 80);
            setField(service2, "defaultOffHeapCriticalPercent", 90);
            setField(service2, "defaultOffHeapKillThresholdPercent", 95);
            setField(service2, "defaultGpuMemoryThresholdPercent", 75);
            setField(service2, "defaultGpuMemoryCriticalPercent", 85);
            setField(service2, "defaultGpuMemoryKillThresholdPercent", 92);

            service2.loadPersistedConfig();

            // Persisted values should override defaults
            assertEquals(65, service2.getOffHeapThresholdPercent());
            assertEquals(78, service2.getOffHeapCriticalPercent());
            assertEquals(88, service2.getOffHeapKillThresholdPercent());
            assertEquals(55, service2.getGpuMemoryThresholdPercent());
            assertEquals(70, service2.getGpuMemoryCriticalPercent());
            assertEquals(82, service2.getGpuMemoryKillThresholdPercent());
        }
    }

    @Nested @DisplayName("Memory threshold update does not affect other config")
    class Isolation {

        @Test @DisplayName("updating memory thresholds does not change heap size")
        void doesNotChangeHeapSize() {
            String before = service.getHeapSize();
            service.updateConfiguration(memoryThresholdUpdate(50, 60, 70, 40, 50, 60));
            assertEquals(before, service.getHeapSize());
        }

        @Test @DisplayName("updating memory thresholds does not change enabled state")
        void doesNotChangeEnabledState() {
            boolean before = service.isEnabled();
            service.updateConfiguration(memoryThresholdUpdate(50, 60, 70, 40, 50, 60));
            assertEquals(before, service.isEnabled());
        }

        @Test @DisplayName("updating memory thresholds does not change timeout")
        void doesNotChangeTimeout() {
            int before = service.getTimeoutMinutes();
            service.updateConfiguration(memoryThresholdUpdate(50, 60, 70, 40, 50, 60));
            assertEquals(before, service.getTimeoutMinutes());
        }
    }
}
