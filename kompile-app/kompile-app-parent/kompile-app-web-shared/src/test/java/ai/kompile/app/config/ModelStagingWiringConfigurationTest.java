package ai.kompile.app.config;

import ai.kompile.app.staging.domain.StagingServiceConfig;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelStagingWiringConfigurationTest {

    @Test
    void migratesLegacyEndpointIntoCanonicalManagedFile(@TempDir Path tmpDir) throws Exception {
        Path configFile = tmpDir.resolve(ServiceEndpointsConfigManager.FILENAME);
        ServiceEndpointsConfigManager manager = new ServiceEndpointsConfigManager(configFile);
        StagingServiceConfigService legacyService = mock(StagingServiceConfigService.class);
        when(legacyService.getActiveConfig()).thenReturn(Optional.of(legacy("http://legacy.example:18090/")));

        new ModelStagingWiringConfiguration(legacyService, manager).refreshConfiguration();

        assertEquals("http://legacy.example:18090", manager.current().stagingUrl());
        assertTrue(Files.readString(configFile).contains("http://legacy.example:18090"));
    }

    @Test
    void explicitManagedEndpointWinsOverLegacyMetadata(@TempDir Path tmpDir) throws Exception {
        ServiceEndpointsConfigManager manager = new ServiceEndpointsConfigManager(
                tmpDir.resolve(ServiceEndpointsConfigManager.FILENAME));
        manager.update(Map.of(ServiceEndpointsConfigManager.STAGING_URL_KEY,
                "https://managed.example:28090/"));
        StagingServiceConfigService legacyService = mock(StagingServiceConfigService.class);
        when(legacyService.getActiveConfig()).thenReturn(Optional.of(legacy("http://legacy.example:18090")));

        new ModelStagingWiringConfiguration(legacyService, manager).refreshConfiguration();

        assertEquals("https://managed.example:28090", manager.current().stagingUrl());
    }

    private static StagingServiceConfig legacy(String endpoint) {
        return StagingServiceConfig.builder()
                .name("legacy")
                .endpointUrl(endpoint)
                .apiKey("secret")
                .retryPollIntervalSeconds(17)
                .active(true)
                .build();
    }
}
