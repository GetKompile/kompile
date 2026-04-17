package ai.kompile.app.services;

import ai.kompile.app.config.ModelWarmupConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModelWarmupService")
class ModelWarmupServiceTest {

    @TempDir Path tempDir;

    private ModelWarmupConfigService createConfigService() {
        return new ModelWarmupConfigService(tempDir.toString());
    }

    @Nested @DisplayName("Config persistence")
    class ConfigPersistence {
        @Test void defaultConfig() {
            var service = createConfigService();
            var config = service.getConfiguration();
            assertTrue(config.isEnabled());
            assertEquals(3, config.getIterations());
            assertEquals(60, config.getTimeoutSeconds());
            assertFalse(config.isFailFast());
        }

        @Test void saveAndReload() throws Exception {
            var service = createConfigService();
            var config = ModelWarmupConfig.builder()
                    .enabled(true)
                    .iterations(5)
                    .timeoutSeconds(120)
                    .warmupText("custom warmup text")
                    .failFast(true)
                    .build();
            service.saveConfiguration(config);

            var service2 = new ModelWarmupConfigService(tempDir.toString());
            service2.loadPersistedConfig();
            var loaded = service2.getConfiguration();
            assertEquals(5, loaded.getIterations());
            assertEquals(120, loaded.getTimeoutSeconds());
            assertEquals("custom warmup text", loaded.getWarmupText());
            assertTrue(loaded.isFailFast());
        }

        @Test void resetToDefaults() throws Exception {
            var service = createConfigService();
            service.saveConfiguration(ModelWarmupConfig.builder().iterations(99).build());
            service.resetToDefaults();
            assertEquals(3, service.getConfiguration().getIterations());
        }

        @Test void corruptedConfigFallsBackToDefaults() throws Exception {
            var configDir = tempDir.resolve("config");
            java.nio.file.Files.createDirectories(configDir);
            java.nio.file.Files.writeString(configDir.resolve("model-warmup-config.json"), "{{{bad json");

            var service = new ModelWarmupConfigService(tempDir.toString());
            service.loadPersistedConfig();
            // Should fall back to defaults
            assertEquals(3, service.getConfiguration().getIterations());
        }

        @Test void missingConfigDirIsCreatedOnSave() throws Exception {
            var service = new ModelWarmupConfigService(tempDir.resolve("newdir").toString());
            service.saveConfiguration(ModelWarmupConfig.builder().iterations(7).build());

            var service2 = new ModelWarmupConfigService(tempDir.resolve("newdir").toString());
            service2.loadPersistedConfig();
            assertEquals(7, service2.getConfiguration().getIterations());
        }
    }

    @Nested @DisplayName("WarmupResult record")
    class WarmupResultTests {
        @Test void successResult() {
            var result = new ModelWarmupService.WarmupResult(
                    "embedding", true, 150, 3, java.time.Instant.now(), null);
            assertEquals("embedding", result.serviceType());
            assertTrue(result.success());
            assertEquals(150, result.latencyMs());
            assertEquals(3, result.iterations());
            assertNull(result.error());
        }

        @Test void failedResult() {
            var result = new ModelWarmupService.WarmupResult(
                    "embedding", false, 50, 3, java.time.Instant.now(), "OOM");
            assertFalse(result.success());
            assertEquals("OOM", result.error());
        }
    }
}
