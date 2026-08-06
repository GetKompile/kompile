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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeviceRoutingConfigServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void cudaRouteWithoutDeviceIdLeavesDeviceSelectionToNd4j() throws IOException {
        DeviceRoutingConfigService service = new DeviceRoutingConfigService(tempDir.toString());
        service.loadPersistedConfig();
        service.saveConfiguration(new DeviceRoutingConfig(
                Map.of("embedding", new DeviceRoutingConfig.ServiceDeviceConfig(
                        "cuda", null, null, 123456789L)),
                true));

        Nd4jEnvironmentConfig resolved = service.resolveNd4jConfigForService("embedding");

        assertNull(resolved.cudaCurrentDevice(), "cuda route without cudaDeviceId must not pin a device");
        assertEquals(123456789L, resolved.maxDeviceMemory());
    }

    @Test
    void explicitCudaDeviceOverrideStillPinsDevice() throws IOException {
        DeviceRoutingConfigService service = new DeviceRoutingConfigService(tempDir.toString());
        service.loadPersistedConfig();
        service.saveConfiguration(new DeviceRoutingConfig(
                Map.of("embedding", new DeviceRoutingConfig.ServiceDeviceConfig(
                        "cuda", 2, null, null)),
                true));

        Nd4jEnvironmentConfig resolved = service.resolveNd4jConfigForService("embedding");

        assertEquals(2, resolved.cudaCurrentDevice());
    }

    @Test
    void persistedServiceRouteRoundTripsThroughJackson() throws IOException {
        DeviceRoutingConfig expected = new DeviceRoutingConfig(
                Map.of("embedding", new DeviceRoutingConfig.ServiceDeviceConfig(
                        "cpu", null, 4, 123456789L)),
                true);
        DeviceRoutingConfigService writer = new DeviceRoutingConfigService(tempDir.toString());
        writer.saveConfiguration(expected);

        DeviceRoutingConfigService reader = new DeviceRoutingConfigService(tempDir.toString());
        reader.loadPersistedConfig();

        assertEquals(expected, reader.getConfiguration());
    }
}
