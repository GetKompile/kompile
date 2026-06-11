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

package ai.kompile.app.services.subprocess;

import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigResponse;
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SubprocessConfigService}.
 * Tests configuration management, getters/setters, and defaults
 * without loading Spring context or ND4J.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubprocessConfigService")
class SubprocessConfigServiceTest {

    @TempDir
    Path tempDir;

    private SubprocessConfigService service;

    /**
     * Creates a SubprocessConfigService with null ServerPortService and a temp data dir.
     * Then sets default @Value fields via reflection and calls loadPersistedConfig().
     */
    @BeforeEach
    void setUp() throws Exception {
        service = new SubprocessConfigService(null, tempDir.toString());
        // Set default @Value fields that would normally be injected by Spring
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

        // Initialize runtime values from defaults
        service.loadPersistedConfig();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // =========================================================================
    // Default values
    // =========================================================================

    @Nested
    @DisplayName("Default values after initialization")
    class DefaultValues {

        @Test
        @DisplayName("isEnabled returns false by default")
        void isEnabledReturnsFalseByDefault() {
            assertFalse(service.isEnabled());
        }

        @Test
        @DisplayName("default heapSize is 4g")
        void defaultHeapSize() {
            assertEquals("4g", service.getHeapSize());
        }

        @Test
        @DisplayName("default timeoutMinutes is 60")
        void defaultTimeoutMinutes() {
            assertEquals(60, service.getTimeoutMinutes());
        }

        @Test
        @DisplayName("default heartbeatIntervalSeconds is 10")
        void defaultHeartbeatInterval() {
            assertEquals(10, service.getHeartbeatIntervalSeconds());
        }

        @Test
        @DisplayName("default staleThresholdSeconds is 120")
        void defaultStaleThreshold() {
            assertEquals(120, service.getStaleThresholdSeconds());
        }

        @Test
        @DisplayName("default queueCapacity is 1000")
        void defaultQueueCapacity() {
            assertEquals(1000, service.getQueueCapacity());
        }

        @Test
        @DisplayName("default parallelIndexing is true")
        void defaultParallelIndexing() {
            assertTrue(service.isParallelIndexing());
        }

        @Test
        @DisplayName("default indexingWorkers is 4")
        void defaultIndexingWorkers() {
            assertEquals(4, service.getIndexingWorkers());
        }
    }

    // =========================================================================
    // setEnabled / isEnabled
    // =========================================================================

    @Nested
    @DisplayName("setEnabled / isEnabled")
    class EnabledToggle {

        @Test
        @DisplayName("setEnabled changes value to true")
        void setEnabledTrue() {
            service.setEnabled(true);
            assertTrue(service.isEnabled());
        }

        @Test
        @DisplayName("setEnabled changes value to false")
        void setEnabledFalse() {
            service.setEnabled(true);
            service.setEnabled(false);
            assertFalse(service.isEnabled());
        }

        @Test
        @DisplayName("isEnabled is volatile - toggle round-trip")
        void enabledRoundTrip() {
            assertFalse(service.isEnabled());
            service.setEnabled(true);
            assertTrue(service.isEnabled());
            service.setEnabled(false);
            assertFalse(service.isEnabled());
        }
    }

    // =========================================================================
    // getConfiguration
    // =========================================================================

    @Nested
    @DisplayName("getConfiguration")
    class GetConfiguration {

        @Test
        @DisplayName("returns non-null response with default values")
        void returnsNonNullResponse() {
            SubprocessConfigResponse config = service.getConfiguration();
            assertNotNull(config);
        }

        @Test
        @DisplayName("response reflects enabled state")
        void responseReflectsEnabled() {
            service.setEnabled(true);
            SubprocessConfigResponse config = service.getConfiguration();
            assertTrue(config.enabled());
        }

        @Test
        @DisplayName("response reflects heapSize")
        void responseReflectsHeapSize() {
            SubprocessConfigResponse config = service.getConfiguration();
            assertEquals("4g", config.heapSize());
        }

        @Test
        @DisplayName("response includes system information")
        void responseIncludesSystemInfo() {
            SubprocessConfigResponse config = service.getConfiguration();
            assertNotNull(config.osName());
            assertNotNull(config.javaVersion());
            assertTrue(config.availableProcessors() > 0);
        }
    }

    // =========================================================================
    // getHeapSizeOptions
    // =========================================================================

    @Nested
    @DisplayName("getHeapSizeOptions")
    class GetHeapSizeOptions {

        @Test
        @DisplayName("returns non-empty list of sizes")
        void returnsNonEmptyList() {
            List<String> options = service.getHeapSizeOptions();
            assertNotNull(options);
            assertFalse(options.isEmpty());
        }

        @Test
        @DisplayName("always contains small sizes: 1g, 2g, 4g")
        void alwaysContainsSmallSizes() {
            List<String> options = service.getHeapSizeOptions();
            assertTrue(options.contains("1g"));
            assertTrue(options.contains("2g"));
            assertTrue(options.contains("4g"));
        }

        @Test
        @DisplayName("options are in ascending order")
        void optionsInAscendingOrder() {
            List<String> options = service.getHeapSizeOptions();
            for (int i = 1; i < options.size(); i++) {
                int prev = parseGigabytes(options.get(i - 1));
                int curr = parseGigabytes(options.get(i));
                assertTrue(curr > prev,
                        "Expected " + options.get(i) + " > " + options.get(i - 1));
            }
        }

        private int parseGigabytes(String size) {
            return Integer.parseInt(size.replace("g", ""));
        }
    }

    // =========================================================================
    // resetToDefaults
    // =========================================================================

    @Nested
    @DisplayName("resetToDefaults")
    class ResetToDefaults {

        @Test
        @DisplayName("restores enabled to default after change")
        void restoresEnabledToDefault() {
            service.setEnabled(true);
            assertTrue(service.isEnabled());

            service.resetToDefaults();
            assertFalse(service.isEnabled());
        }

        @Test
        @DisplayName("restores heapSize to default after change")
        void restoresHeapSizeToDefault() {
            service.setHeapSize("16g");
            assertEquals("16g", service.getHeapSize());

            service.resetToDefaults();
            assertEquals("4g", service.getHeapSize());
        }

        @Test
        @DisplayName("restores timeoutMinutes to default after change")
        void restoresTimeoutToDefault() {
            service.setTimeoutMinutes(120);
            assertEquals(120, service.getTimeoutMinutes());

            service.resetToDefaults();
            assertEquals(60, service.getTimeoutMinutes());
        }
    }

    // =========================================================================
    // updateConfiguration
    // =========================================================================

    @Nested
    @DisplayName("updateConfiguration")
    class UpdateConfiguration {

        @Test
        @DisplayName("updates only specified fields, leaving others unchanged")
        void updatesOnlySpecifiedFields() {
            SubprocessConfigUpdate update = new SubprocessConfigUpdate(
                    true,    // enabled
                    null,    // javaPath - not changed
                    "8g",    // heapSize
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    null); // gpuSoftLimitPercent

            service.updateConfiguration(update);

            assertTrue(service.isEnabled());
            assertEquals("8g", service.getHeapSize());
            // javaPath should remain default
            assertEquals("java", service.getJavaPath());
        }

        @Test
        @DisplayName("updates timeout when specified")
        void updatesTimeout() {
            SubprocessConfigUpdate update = new SubprocessConfigUpdate(
                    null, null, null, null, null,
                    90,    // timeoutMinutes
                    null, null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    null); // gpuSoftLimitPercent

            service.updateConfiguration(update);

            assertEquals(90, service.getTimeoutMinutes());
        }
    }
}
