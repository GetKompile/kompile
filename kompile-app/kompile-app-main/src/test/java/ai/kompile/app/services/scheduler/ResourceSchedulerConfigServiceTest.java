package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.ResourceSchedulerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResourceSchedulerConfigService")
class ResourceSchedulerConfigServiceTest {

    @TempDir
    Path tempDir;

    private ResourceSchedulerConfigService configService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        configService = new ResourceSchedulerConfigService(tempDir.toString());
    }

    @Test
    void defaultConfigurationIsReturned() {
        ResourceSchedulerConfig config = configService.getConfiguration();
        assertNotNull(config);
        assertTrue(config.isEnabled());
        assertEquals("PRIORITY", config.getSchedulingAlgorithm());
    }

    @Test
    void saveConfigurationPersistsToDisk() throws Exception {
        ResourceSchedulerConfig config = new ResourceSchedulerConfig();
        config.setEnabled(false);
        config.setSchedulingAlgorithm("FAIR");
        config.setGlobalQueueDepth(100);

        configService.saveConfiguration(config);

        Path configFile = tempDir.resolve("config/resource-scheduler-config.json");
        assertTrue(Files.exists(configFile));

        ResourceSchedulerConfig loaded = objectMapper.readValue(
                configFile.toFile(), ResourceSchedulerConfig.class);
        assertFalse(loaded.isEnabled());
        assertEquals("FAIR", loaded.getSchedulingAlgorithm());
        assertEquals(100, loaded.getGlobalQueueDepth());
    }

    @Test
    void saveConfigurationUpdatesInMemory() throws Exception {
        ResourceSchedulerConfig config = new ResourceSchedulerConfig();
        config.setEnabled(false);
        config.setSchedulingAlgorithm("ROUND_ROBIN");

        configService.saveConfiguration(config);

        ResourceSchedulerConfig current = configService.getConfiguration();
        assertFalse(current.isEnabled());
        assertEquals("ROUND_ROBIN", current.getSchedulingAlgorithm());
    }

    @Test
    void loadPersistedConfigReadsExistingFile() throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Path configFile = configDir.resolve("resource-scheduler-config.json");

        ResourceSchedulerConfig toWrite = new ResourceSchedulerConfig();
        toWrite.setEnabled(false);
        toWrite.setGlobalQueueDepth(25);
        toWrite.setExternalSchedulerMode("kubernetes");
        objectMapper.writeValue(configFile.toFile(), toWrite);

        configService.loadPersistedConfig();

        ResourceSchedulerConfig config = configService.getConfiguration();
        assertFalse(config.isEnabled());
        assertEquals(25, config.getGlobalQueueDepth());
        assertEquals("kubernetes", config.getExternalSchedulerMode());
    }

    @Test
    void loadPersistedConfigUsesDefaultsWhenFileAbsent() {
        configService.loadPersistedConfig();

        ResourceSchedulerConfig config = configService.getConfiguration();
        assertTrue(config.isEnabled());
        assertEquals(50, config.getGlobalQueueDepth());
    }

    @Test
    void loadPersistedConfigUsesDefaultsOnCorruptFile() throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Path configFile = configDir.resolve("resource-scheduler-config.json");
        Files.writeString(configFile, "not valid json{{{");

        configService.loadPersistedConfig();

        ResourceSchedulerConfig config = configService.getConfiguration();
        assertTrue(config.isEnabled()); // defaults
    }

    @Test
    void resetToDefaultsRestoresDefaults() throws Exception {
        ResourceSchedulerConfig custom = new ResourceSchedulerConfig();
        custom.setEnabled(false);
        custom.setGlobalQueueDepth(999);
        configService.saveConfiguration(custom);

        configService.resetToDefaults();

        ResourceSchedulerConfig config = configService.getConfiguration();
        assertTrue(config.isEnabled());
        assertEquals(50, config.getGlobalQueueDepth());
    }

    @Test
    void resetToDefaultsPersistsToDisk() throws Exception {
        configService.resetToDefaults();

        Path configFile = tempDir.resolve("config/resource-scheduler-config.json");
        assertTrue(Files.exists(configFile));

        ResourceSchedulerConfig loaded = objectMapper.readValue(
                configFile.toFile(), ResourceSchedulerConfig.class);
        assertTrue(loaded.isEnabled());
    }
}
