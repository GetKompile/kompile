/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GpuDevice} record.
 */
@DisplayName("GpuDevice Tests")
class GpuDeviceTest {

    private static final long ONE_GB = 1024L * 1024L * 1024L;

    @Nested
    @DisplayName("Record construction")
    class Construction {

        @Test
        @DisplayName("should create device with all fields")
        void fullConstruction() {
            GpuDevice device = new GpuDevice(0, 1, "RTX 4090", 24L * ONE_GB, "local");

            assertEquals(0, device.nvidiaSmiIndex());
            assertEquals(1, device.cudaRuntimeIndex());
            assertEquals("RTX 4090", device.name());
            assertEquals(24L * ONE_GB, device.totalMemoryBytes());
            assertEquals("local", device.nodeId());
        }

        @Test
        @DisplayName("should support zero memory bytes")
        void zeroMemory() {
            GpuDevice device = new GpuDevice(0, 0, "Test GPU", 0, "local");
            assertEquals(0, device.totalMemoryBytes());
            assertEquals(0, device.totalMemoryMb());
        }
    }

    @Nested
    @DisplayName("totalMemoryMb()")
    class TotalMemoryMb {

        @Test
        @DisplayName("should convert bytes to megabytes")
        void convertsBytesToMb() {
            GpuDevice device = GpuDevice.local(0, 0, "RTX 4090", 24564L * 1024 * 1024);
            assertEquals(24564, device.totalMemoryMb());
        }

        @Test
        @DisplayName("should handle exact gigabytes")
        void exactGigabytes() {
            GpuDevice device = GpuDevice.local(0, 0, "GPU", 8L * ONE_GB);
            assertEquals(8192, device.totalMemoryMb());
        }

        @Test
        @DisplayName("should truncate partial megabytes")
        void truncatesPartialMb() {
            // 1 MB + 500 KB = 1.5 MB -> 1 MB after integer division
            long bytes = 1024L * 1024L + 512L * 1024L;
            GpuDevice device = GpuDevice.local(0, 0, "GPU", bytes);
            assertEquals(1, device.totalMemoryMb());
        }
    }

    @Nested
    @DisplayName("local() factory method")
    class LocalFactory {

        @Test
        @DisplayName("should create device with nodeId 'local'")
        void setsLocalNodeId() {
            GpuDevice device = GpuDevice.local(1, 0, "RTX 3070 Ti", 8192L * 1024 * 1024);
            assertEquals("local", device.nodeId());
        }

        @Test
        @DisplayName("should set all fields correctly")
        void setsAllFields() {
            GpuDevice device = GpuDevice.local(1, 0, "RTX 3070 Ti", 8192L * 1024 * 1024);
            assertEquals(1, device.nvidiaSmiIndex());
            assertEquals(0, device.cudaRuntimeIndex());
            assertEquals("RTX 3070 Ti", device.name());
            assertEquals(8192L * 1024 * 1024, device.totalMemoryBytes());
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should include all relevant fields")
        void includesAllFields() {
            GpuDevice device = new GpuDevice(0, 1, "RTX 4090", 24564L * 1024 * 1024, "local");
            String str = device.toString();

            assertTrue(str.contains("smi=0"));
            assertTrue(str.contains("cuda=1"));
            assertTrue(str.contains("RTX 4090"));
            assertTrue(str.contains("24564MB"));
            assertTrue(str.contains("node=local"));
        }

        @Test
        @DisplayName("should include remote nodeId")
        void remoteNodeId() {
            GpuDevice device = new GpuDevice(0, 0, "A100", 80L * ONE_GB, "node-2");
            String str = device.toString();
            assertTrue(str.contains("node=node-2"));
        }
    }

    @Nested
    @DisplayName("Record equality")
    class Equality {

        @Test
        @DisplayName("equal devices should be equal")
        void equalDevices() {
            GpuDevice a = GpuDevice.local(0, 1, "RTX 4090", 24L * ONE_GB);
            GpuDevice b = GpuDevice.local(0, 1, "RTX 4090", 24L * ONE_GB);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("devices with different indices should not be equal")
        void differentIndices() {
            GpuDevice a = GpuDevice.local(0, 0, "RTX 4090", 24L * ONE_GB);
            GpuDevice b = GpuDevice.local(1, 0, "RTX 4090", 24L * ONE_GB);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("devices on different nodes should not be equal")
        void differentNodes() {
            GpuDevice a = new GpuDevice(0, 0, "A100", 80L * ONE_GB, "local");
            GpuDevice b = new GpuDevice(0, 0, "A100", 80L * ONE_GB, "node-2");
            assertNotEquals(a, b);
        }
    }

    @Nested
    @DisplayName("JSON serialization")
    class JsonSerialization {

        @Test
        @DisplayName("should round-trip through Jackson")
        void jacksonRoundTrip() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            GpuDevice original = GpuDevice.local(0, 1, "RTX 4090", 24564L * 1024 * 1024);

            String json = mapper.writeValueAsString(original);
            GpuDevice deserialized = mapper.readValue(json, GpuDevice.class);

            assertEquals(original, deserialized);
        }

        @Test
        @DisplayName("should ignore unknown properties during deserialization")
        void ignoresUnknownProperties() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String json = """
                    {"nvidiaSmiIndex":0,"cudaRuntimeIndex":1,"name":"RTX 4090",
                     "totalMemoryBytes":25769803776,"nodeId":"local","extraField":"ignored"}
                    """;

            GpuDevice device = mapper.readValue(json, GpuDevice.class);
            assertEquals(0, device.nvidiaSmiIndex());
            assertEquals("RTX 4090", device.name());
        }
    }
}
