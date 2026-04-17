package ai.kompile.app.services;

import ai.kompile.app.config.ModelSchedulerConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModelSchedulerConfigService")
class ModelSchedulerConfigServiceTest {

    @TempDir Path tempDir;

    private ModelSchedulerConfigService createService() {
        return new ModelSchedulerConfigService(tempDir.toString());
    }

    @Nested @DisplayName("Defaults")
    class Defaults {
        @Test void defaultValues() {
            var service = createService();
            var config = service.getConfiguration();
            assertTrue(config.isEnabled());
            assertEquals(32, config.getPreferredBatchSize());
            assertEquals(64, config.getMaxBatchSize());
            assertEquals(50, config.getMaxQueueDelayMs());
            assertEquals(1000, config.getQueueCapacity());
            assertTrue(config.isContinuousBatchingEnabled());
            assertEquals(16, config.getMaxConcurrentDecodes());
        }
    }

    @Nested @DisplayName("Save and load")
    class SaveAndLoad {
        @Test void persistsToFile() throws Exception {
            var service = createService();
            var config = ModelSchedulerConfig.builder()
                    .preferredBatchSize(16)
                    .maxBatchSize(128)
                    .maxQueueDelayMs(100)
                    .build();
            service.saveConfiguration(config);

            var reloaded = new ModelSchedulerConfigService(tempDir.toString());
            reloaded.loadPersistedConfig();
            assertEquals(16, reloaded.getConfiguration().getPreferredBatchSize());
            assertEquals(128, reloaded.getConfiguration().getMaxBatchSize());
            assertEquals(100, reloaded.getConfiguration().getMaxQueueDelayMs());
        }

        @Test void resetToDefaults() throws Exception {
            var service = createService();
            service.saveConfiguration(ModelSchedulerConfig.builder().preferredBatchSize(1).build());
            service.resetToDefaults();
            assertEquals(32, service.getConfiguration().getPreferredBatchSize());
        }
    }
}
