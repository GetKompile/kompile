package ai.kompile.app.services;

import ai.kompile.app.config.ModelAdmissionConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModelAdmissionConfigService")
class ModelAdmissionConfigServiceTest {

    @TempDir Path tempDir;

    private ModelAdmissionConfigService createService() {
        return new ModelAdmissionConfigService(tempDir.toString());
    }

    @Nested @DisplayName("Defaults")
    class Defaults {
        @Test void defaultValues() {
            var service = createService();
            var config = service.getConfiguration();
            assertTrue(config.isEnabled());
            assertEquals(10, config.getMaxLoadedModels());
            assertEquals(2, config.getMaxConcurrentLoads());
            assertEquals(512L * 1024 * 1024, config.getMemoryReserveBytes());
            assertEquals(2, config.getBackgroundLoadThreads());
            assertTrue(config.isWarmupAfterLoad());
        }
    }

    @Nested @DisplayName("Save and load")
    class SaveAndLoad {
        @Test void persistsToFile() throws Exception {
            var service = createService();
            service.saveConfiguration(ModelAdmissionConfig.builder()
                    .maxLoadedModels(5)
                    .maxConcurrentLoads(1)
                    .build());

            var reloaded = new ModelAdmissionConfigService(tempDir.toString());
            reloaded.loadPersistedConfig();
            assertEquals(5, reloaded.getConfiguration().getMaxLoadedModels());
            assertEquals(1, reloaded.getConfiguration().getMaxConcurrentLoads());
        }

        @Test void resetToDefaults() throws Exception {
            var service = createService();
            service.saveConfiguration(ModelAdmissionConfig.builder().maxLoadedModels(1).build());
            service.resetToDefaults();
            assertEquals(10, service.getConfiguration().getMaxLoadedModels());
        }
    }

    @Nested @DisplayName("Corrupted config")
    class CorruptedConfig {
        @Test void fallsBackToDefaultsOnCorruptFile() throws Exception {
            // Write garbage to config file
            var configDir = tempDir.resolve("config");
            java.nio.file.Files.createDirectories(configDir);
            java.nio.file.Files.writeString(configDir.resolve("model-admission-config.json"), "not json{{{");

            var service = new ModelAdmissionConfigService(tempDir.toString());
            service.loadPersistedConfig();
            // Should fall back to defaults
            assertEquals(10, service.getConfiguration().getMaxLoadedModels());
        }
    }
}
