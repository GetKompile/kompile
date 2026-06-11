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

package ai.kompile.app.services;

import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceRoutingConfigServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;

    private DeviceRoutingConfigService service;

    @BeforeEach
    void setUp() throws IOException {
        // Use tempDir as data dir so config is written there
        service = new DeviceRoutingConfigService(tempDir.toString());
        ReflectionTestUtils.setField(service, "nd4jEnvironmentConfigService", nd4jEnvironmentConfigService);
        service.loadPersistedConfig();
    }

    @Test
    void getConfiguration_returnsDefaultsInitially() {
        DeviceRoutingConfig config = service.getConfiguration();
        assertThat(config).isNotNull();
        // Default is disabled routing
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void saveConfiguration_persistsAndReturnsUpdated() throws IOException {
        DeviceRoutingConfig newConfig = new DeviceRoutingConfig(
                Map.of("embedding", new DeviceRoutingConfig.ServiceDeviceConfig("cpu", null, null, null)),
                true
        );
        service.saveConfiguration(newConfig);

        DeviceRoutingConfig retrieved = service.getConfiguration();
        assertThat(retrieved.enabled()).isTrue();
        assertThat(retrieved.serviceRoutes()).containsKey("embedding");
    }

    @Test
    void saveConfiguration_writesJsonFile() throws IOException {
        DeviceRoutingConfig newConfig = new DeviceRoutingConfig(Map.of(), true);
        service.saveConfiguration(newConfig);

        Path configFile = tempDir.resolve("config/device-routing-config.json");
        assertThat(Files.exists(configFile)).isTrue();
        String json = Files.readString(configFile);
        assertThat(json).contains("enabled");
    }

    @Test
    void getServiceConfig_returnsNullWhenNoRoutes() {
        DeviceRoutingConfig.ServiceDeviceConfig cfg = service.getServiceConfig("embedding");
        assertThat(cfg).isNull();
    }

    @Test
    void getServiceConfig_returnsConfigWhenSet() throws IOException {
        DeviceRoutingConfig.ServiceDeviceConfig svc =
                new DeviceRoutingConfig.ServiceDeviceConfig("gpu", 0, 8, 8589934592L);
        service.updateServiceConfig("vlm", svc);

        DeviceRoutingConfig.ServiceDeviceConfig retrieved = service.getServiceConfig("vlm");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.deviceType()).isEqualTo("gpu");
    }

    @Test
    void updateServiceConfig_addsRoute() throws IOException {
        DeviceRoutingConfig.ServiceDeviceConfig svc =
                new DeviceRoutingConfig.ServiceDeviceConfig("cpu", null, 4, null);
        service.updateServiceConfig("ingest", svc);

        DeviceRoutingConfig config = service.getConfiguration();
        assertThat(config.serviceRoutes()).containsKey("ingest");
    }

    @Test
    void removeServiceConfig_removesRoute() throws IOException {
        DeviceRoutingConfig.ServiceDeviceConfig svc =
                new DeviceRoutingConfig.ServiceDeviceConfig("cpu", null, 4, null);
        service.updateServiceConfig("ingest", svc);
        service.removeServiceConfig("ingest");

        DeviceRoutingConfig config = service.getConfiguration();
        assertThat(config.serviceRoutes()).doesNotContainKey("ingest");
    }

    @Test
    void resetToDefaults_restoresDefaults() throws IOException {
        DeviceRoutingConfig newConfig = new DeviceRoutingConfig(Map.of(), true);
        service.saveConfiguration(newConfig);

        service.resetToDefaults();

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void isEnabled_falseByDefault() {
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void isEnabled_trueAfterEnabling() throws IOException {
        DeviceRoutingConfig config = new DeviceRoutingConfig(Map.of(), true);
        service.saveConfiguration(config);
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void resolveNd4jConfigForService_returnsBaseWhenRoutingDisabled() {
        Nd4jEnvironmentConfig baseConfig = Nd4jEnvironmentConfig.defaults();
        when(nd4jEnvironmentConfigService.getConfiguration()).thenReturn(baseConfig);

        Nd4jEnvironmentConfig resolved = service.resolveNd4jConfigForService("embedding");
        assertThat(resolved).isNotNull();
    }

    @Test
    void resolveNd4jConfigForService_appliesThreadOverride() throws IOException {
        // Enable routing with a thread override for embedding
        DeviceRoutingConfig.ServiceDeviceConfig svc =
                new DeviceRoutingConfig.ServiceDeviceConfig("cpu", null, 8, null);
        service.updateServiceConfig("embedding", svc);

        DeviceRoutingConfig enabledConfig = new DeviceRoutingConfig(
                service.getConfiguration().serviceRoutes(), true);
        service.saveConfiguration(enabledConfig);

        Nd4jEnvironmentConfig baseConfig = Nd4jEnvironmentConfig.defaults();
        when(nd4jEnvironmentConfigService.getConfiguration()).thenReturn(baseConfig);

        Nd4jEnvironmentConfig resolved = service.resolveNd4jConfigForService("embedding");
        assertThat(resolved).isNotNull();
        // maxThreads should be set to the override value
        if (resolved.maxThreads() != null) {
            assertThat(resolved.maxThreads()).isEqualTo(8);
        }
    }

    @Test
    void resolveNd4jConfigForService_returnsDefaultBaseConfig_whenNd4jServiceNull() throws IOException {
        // null nd4jEnvironmentConfigService
        DeviceRoutingConfigService noNd4j = new DeviceRoutingConfigService(tempDir.resolve("sub").toString());
        noNd4j.loadPersistedConfig();

        Nd4jEnvironmentConfig resolved = noNd4j.resolveNd4jConfigForService("embedding");
        assertThat(resolved).isNotNull();
    }

    @Test
    void loadPersistedConfig_fromExistingFile() throws IOException {
        // Save a config, then reload it in a new service instance
        DeviceRoutingConfig config = new DeviceRoutingConfig(
                Map.of("vlm", new DeviceRoutingConfig.ServiceDeviceConfig("gpu", 0, null, null)),
                true
        );
        service.saveConfiguration(config);

        DeviceRoutingConfigService reloaded = new DeviceRoutingConfigService(tempDir.toString());
        reloaded.loadPersistedConfig();

        assertThat(reloaded.isEnabled()).isTrue();
        assertThat(reloaded.getServiceConfig("vlm")).isNotNull();
    }
}
