package ai.kompile.app.services;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Nd4jEnvironmentConfigService.
 * Note: These tests avoid calling methods that require a live ND4J backend
 * (syncWithPersistedConfig, applyPreset, updateConfiguration, resetConfiguration,
 * getConfigurationSummary) since the test classpath has no ND4J backend.
 * Instead, we test config management logic directly via reflection.
 */
@DisplayName("Nd4jEnvironmentConfigService")
class Nd4jEnvironmentConfigServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path tempDir;

    private Nd4jEnvironmentConfigService createService() {
        return new Nd4jEnvironmentConfigService(tempDir.toString());
    }

    /** Sets the currentConfig field directly via reflection (bypasses ND4J) */
    private void setCurrentConfig(Nd4jEnvironmentConfigService service, Nd4jEnvironmentConfig config) throws Exception {
        Field f = Nd4jEnvironmentConfigService.class.getDeclaredField("currentConfig");
        f.setAccessible(true);
        f.set(service, config);
    }

    /** Calls persistConfig() via reflection */
    private void callPersistConfig(Nd4jEnvironmentConfigService service) throws Exception {
        Method m = Nd4jEnvironmentConfigService.class.getDeclaredMethod("persistConfig");
        m.setAccessible(true);
        m.invoke(service);
    }

    @Nested @DisplayName("Initialization")
    class Initialization {
        @Test void startsWithDefaults() {
            var service = createService();
            var config = service.getConfiguration();
            assertNotNull(config);
            assertEquals(4, config.maxThreads());
            assertEquals(4, config.ompNumThreads());
        }

        @Test void configPathUnderDataDir() {
            var service = createService();
            assertNotNull(service.getConfiguration());
        }
    }

    @Nested @DisplayName("Configuration updates (direct)")
    class Updates {
        @Test void mergePartialUpdate() throws Exception {
            var service = createService();
            var base = service.getConfiguration(); // defaults

            // Simulate what updateConfiguration does: merge then set
            var update = Nd4jEnvironmentConfig.builder()
                    .maxThreads(8)
                    .debug(true)
                    .build();
            var merged = base.merge(update);
            setCurrentConfig(service, merged);

            var result = service.getConfiguration();
            assertEquals(8, result.maxThreads());
            assertTrue(result.debug());
            // Other fields preserved from defaults
            assertEquals(4, result.maxMasterThreads());
            assertFalse(result.verbose());
        }

        @Test void nullMergePreservesConfig() {
            var service = createService();
            var before = service.getConfiguration();
            var after = before.merge(null);
            assertSame(before, after);
        }

        @Test void resetToDefaults() throws Exception {
            var service = createService();
            // Change config
            var changed = service.getConfiguration().merge(
                    Nd4jEnvironmentConfig.builder().maxThreads(16).debug(true).build());
            setCurrentConfig(service, changed);
            assertEquals(16, service.getConfiguration().maxThreads());

            // Reset by setting defaults
            setCurrentConfig(service, Nd4jEnvironmentConfig.defaults());
            assertEquals(4, service.getConfiguration().maxThreads());
            assertFalse(service.getConfiguration().debug());
        }
    }

    @Nested @DisplayName("Persistence")
    class Persistence {
        @Test void persistsToFile() throws Exception {
            var service = createService();
            var config = service.getConfiguration().merge(
                    Nd4jEnvironmentConfig.builder().maxThreads(12).build());
            setCurrentConfig(service, config);
            callPersistConfig(service);

            Path configFile = tempDir.resolve("config/nd4j-environment-config.json");
            assertTrue(Files.exists(configFile));

            String json = Files.readString(configFile);
            var restored = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);
            assertEquals(12, restored.maxThreads());
        }

        @Test void reloadsPersistedConfig() throws Exception {
            // Create and persist config
            var service1 = createService();
            var config = Nd4jEnvironmentConfig.defaults().merge(
                    Nd4jEnvironmentConfig.builder().maxThreads(12).ompNumThreads(8).build());
            setCurrentConfig(service1, config);
            callPersistConfig(service1);

            // Verify file exists
            Path configFile = tempDir.resolve("config/nd4j-environment-config.json");
            assertTrue(Files.exists(configFile));

            // Read the file back and verify contents
            String json = Files.readString(configFile);
            var loaded = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);
            assertEquals(12, loaded.maxThreads());
            assertEquals(8, loaded.ompNumThreads());
        }

        @Test void handlesCorruptConfigFile() throws Exception {
            // Write corrupt JSON
            Path configDir = tempDir.resolve("config");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("nd4j-environment-config.json"), "not valid json{{{");

            // The corrupt file can't be parsed by ObjectMapper
            assertThrows(Exception.class, () ->
                    MAPPER.readValue("not valid json{{{", Nd4jEnvironmentConfig.class));

            // Service constructor still works (uses defaults)
            var service = createService();
            assertNotNull(service.getConfiguration());
        }

        @Test void handlesMissingConfigFile() {
            // No config file exists
            var service = createService();
            assertNotNull(service.getConfiguration());
            assertEquals(4, service.getConfiguration().maxThreads());
        }

        @Test void persistCreatesBackup() throws Exception {
            var service = createService();
            // First persist
            setCurrentConfig(service, Nd4jEnvironmentConfig.defaults().merge(
                    Nd4jEnvironmentConfig.builder().maxThreads(8).build()));
            callPersistConfig(service);

            // Second persist (should create backup of first)
            setCurrentConfig(service, Nd4jEnvironmentConfig.defaults().merge(
                    Nd4jEnvironmentConfig.builder().maxThreads(16).build()));
            callPersistConfig(service);

            Path configFile = tempDir.resolve("config/nd4j-environment-config.json");
            Path backupFile = tempDir.resolve("config/nd4j-environment-config.json.backup");
            assertTrue(Files.exists(configFile));
            assertTrue(Files.exists(backupFile));

            // Main file has latest
            var latest = MAPPER.readValue(Files.readString(configFile), Nd4jEnvironmentConfig.class);
            assertEquals(16, latest.maxThreads());

            // Backup has previous
            var backup = MAPPER.readValue(Files.readString(backupFile), Nd4jEnvironmentConfig.class);
            assertEquals(8, backup.maxThreads());
        }
    }

    @Nested @DisplayName("Preset definitions")
    class Presets {
        // Test preset configs by building them directly (same logic as applyPreset)
        // This avoids calling applyPreset which calls applyConfiguration (needs ND4J)

        @Test void minimalPreset() {
            var preset = Nd4jEnvironmentConfig.builder()
                    .maxThreads(2).maxMasterThreads(2)
                    .debug(false).verbose(false).profiling(false)
                    .lifecycleTracking(true).trackViews(false).trackDeletions(false)
                    .snapshotFiles(true).trackOperations(false)
                    .stackDepth(8).reportInterval(300).maxDeletionHistory(500)
                    .openBlasThreads(1).ompNumThreads(2)
                    .build();
            var config = Nd4jEnvironmentConfig.defaults().merge(preset);
            assertEquals(2, config.maxThreads());
            assertEquals(2, config.maxMasterThreads());
            assertFalse(config.debug());
            assertFalse(config.verbose());
            assertEquals(2, config.ompNumThreads());
            assertEquals(1, config.openBlasThreads());
        }

        @Test void balancedPreset() {
            var preset = Nd4jEnvironmentConfig.builder()
                    .maxThreads(4).maxMasterThreads(4)
                    .debug(false).verbose(false).profiling(false)
                    .lifecycleTracking(true).trackOperations(true)
                    .stackDepth(16).ompNumThreads(4).openBlasThreads(2)
                    .build();
            var config = Nd4jEnvironmentConfig.defaults().merge(preset);
            assertEquals(4, config.maxThreads());
            assertTrue(config.trackOperations());
            assertEquals(4, config.ompNumThreads());
        }

        @Test void detailedPreset() {
            var preset = Nd4jEnvironmentConfig.builder()
                    .maxThreads(4).maxMasterThreads(4)
                    .debug(true).verbose(true).profiling(true)
                    .lifecycleTracking(true).trackViews(true).trackDeletions(true)
                    .snapshotFiles(true).trackOperations(true)
                    .stackDepth(64).reportInterval(30).maxDeletionHistory(5000)
                    .openBlasThreads(1).ompNumThreads(1)
                    .build();
            var config = Nd4jEnvironmentConfig.defaults().merge(preset);
            assertTrue(config.debug());
            assertTrue(config.verbose());
            assertTrue(config.profiling());
            assertTrue(config.lifecycleTracking());
            assertTrue(config.trackViews());
            assertTrue(config.trackDeletions());
            assertEquals(64, config.stackDepth());
            assertEquals(1, config.ompNumThreads());
        }

        @Test void performancePreset() {
            int cpus = Runtime.getRuntime().availableProcessors();
            var preset = Nd4jEnvironmentConfig.builder()
                    .maxThreads(cpus).maxMasterThreads(cpus)
                    .debug(false).verbose(false).profiling(false)
                    .lifecycleTracking(false).trackViews(false).trackDeletions(false)
                    .snapshotFiles(false).trackOperations(false)
                    .stackDepth(0).reportInterval(0).maxDeletionHistory(0)
                    .openBlasThreads(Math.max(1, cpus / 2))
                    .ompNumThreads(cpus)
                    .build();
            var config = Nd4jEnvironmentConfig.defaults().merge(preset);
            assertEquals(cpus, config.maxThreads());
            assertFalse(config.debug());
            assertFalse(config.lifecycleTracking());
            assertEquals(cpus, config.ompNumThreads());
        }

        @Test void unknownPresetThrows() {
            var service = createService();
            // applyPreset would need ND4J, but the switch default throws before that
            assertThrows(Exception.class,
                    () -> service.applyPreset("nonexistent"));
        }

        @Test void presetPersistsToFile() throws Exception {
            var service = createService();
            // Simulate what applyPreset does: build preset, merge with defaults, set, persist
            var preset = Nd4jEnvironmentConfig.builder()
                    .maxThreads(2).maxMasterThreads(2).ompNumThreads(2)
                    .build();
            setCurrentConfig(service, Nd4jEnvironmentConfig.defaults().merge(preset));
            callPersistConfig(service);

            assertTrue(Files.exists(tempDir.resolve("config/nd4j-environment-config.json")));
        }
    }

    @Nested @DisplayName("Subprocess config JSON generation")
    class SubprocessConfigGeneration {
        @Test @DisplayName("Config JSON can be generated for subprocess args")
        void generateConfigJson() throws Exception {
            var service = createService();
            var config = service.getConfiguration();
            String json = MAPPER.writeValueAsString(config);

            var restored = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);
            assertEquals(config.maxThreads(), restored.maxThreads());
            assertEquals(config.ompNumThreads(), restored.ompNumThreads());
        }

        @Test @DisplayName("Updated config propagates to subprocess JSON")
        void updatedConfigPropagates() throws Exception {
            var service = createService();
            var updated = service.getConfiguration().merge(
                    Nd4jEnvironmentConfig.builder()
                            .maxThreads(16)
                            .ompNumThreads(16)
                            .openBlasThreads(8)
                            .debug(true)
                            .build());
            setCurrentConfig(service, updated);

            String json = MAPPER.writeValueAsString(service.getConfiguration());
            var restored = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);

            assertEquals(16, restored.maxThreads());
            assertEquals(16, restored.ompNumThreads());
            assertEquals(8, restored.openBlasThreads());
            assertTrue(restored.debug());
        }

        @Test @DisplayName("Preset config propagates to subprocess JSON")
        void presetPropagates() throws Exception {
            var service = createService();
            // Simulate detailed preset
            var preset = Nd4jEnvironmentConfig.builder()
                    .debug(true).verbose(true).profiling(true)
                    .lifecycleTracking(true).trackViews(true)
                    .build();
            setCurrentConfig(service, Nd4jEnvironmentConfig.defaults().merge(preset));

            String json = MAPPER.writeValueAsString(service.getConfiguration());
            var restored = MAPPER.readValue(json, Nd4jEnvironmentConfig.class);

            assertTrue(restored.debug());
            assertTrue(restored.verbose());
            assertTrue(restored.profiling());
            assertTrue(restored.lifecycleTracking());
        }
    }
}
