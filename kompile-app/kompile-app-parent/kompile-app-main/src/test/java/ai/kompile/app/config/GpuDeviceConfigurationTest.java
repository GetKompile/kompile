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

package ai.kompile.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The nvidia-smi-era {@code gpu-device-config.json} is read only to warn that it is ignored. Applying it
 * to ND4J-indexed devices swapped the two cards on a 4090 + 3070 Ti host, so nothing may apply it.
 */
class GpuDeviceConfigurationTest {

    /** The mapping a 4090 + 3070 Ti host still carried from nvidia-smi discovery (smi order is PCI order). */
    private static final String NVIDIA_SMI_ERA_MAPPING = """
            {"cudaIndexMappings":[
              {"nvidiaSmiIndex":0,"cudaRuntimeIndex":1,"deviceName":"NVIDIA GeForce RTX 3070 Ti"},
              {"nvidiaSmiIndex":1,"cudaRuntimeIndex":0,"deviceName":"NVIDIA GeForce RTX 4090"}]}
            """;

    @Test
    void staleMappingFileIsCountedAndIgnored(@TempDir Path dataDir) throws Exception {
        writeConfig(dataDir, NVIDIA_SMI_ERA_MAPPING);
        GpuDeviceConfiguration configuration = new GpuDeviceConfiguration(dataDir.toString());

        assertEquals(2, configuration.obsoleteMappingCount());
        assertDoesNotThrow(configuration::configure);
    }

    @Test
    void missingOrMalformedFileCountsNothing(@TempDir Path dataDir) throws Exception {
        GpuDeviceConfiguration configuration = new GpuDeviceConfiguration(dataDir.toString());
        assertEquals(0, configuration.obsoleteMappingCount());
        assertDoesNotThrow(configuration::configure);

        writeConfig(dataDir, "{not json");
        assertEquals(0, configuration.obsoleteMappingCount());
        assertDoesNotThrow(configuration::configure);
    }

    private static void writeConfig(Path dataDir, String json) throws Exception {
        Path configDir = Files.createDirectories(dataDir.resolve("config"));
        Files.writeString(configDir.resolve("gpu-device-config.json"), json);
    }
}
