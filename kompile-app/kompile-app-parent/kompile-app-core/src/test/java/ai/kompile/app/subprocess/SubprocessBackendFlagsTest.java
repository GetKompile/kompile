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

package ai.kompile.app.subprocess;

import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import org.junit.jupiter.api.Test;
import org.nd4j.common.config.ND4JSystemProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubprocessBackendFlagsTest {

    @Test
    void gpuPlacementEmitsPhysicalAndNativeMemoryCaps() {
        SubprocessPlacement placement = SubprocessPlacement.gpu(1, 12_345L);

        List<String> flags = SubprocessBackendFlags.jvmFlags(placement, BackendPreference.INHERIT);
        Map<String, String> env = new HashMap<>();
        SubprocessBackendFlags.applyEnv(env, placement);

        assertTrue(flags.contains("-Dorg.nd4j.gpu.priority=1000"));
        assertTrue(flags.contains("-Dorg.nd4j.cpu.priority=0"));
        assertTrue(flags.contains("-Dnd4j.placement.defaultDevice=1"));
        assertTrue(flags.contains("-D" + ND4JSystemProperties.ENV_MAX_DEVICE_MEMORY + "=12345"));
        assertEquals("12345", env.get(SubprocessBackendFlags.MAX_DEVICE_BYTES_ENV));
    }

    @Test
    void unboundedPlacementDoesNotEmitMemoryCaps() {
        SubprocessPlacement placement = SubprocessPlacement.gpu(0, 0L);

        List<String> flags = SubprocessBackendFlags.jvmFlags(placement, BackendPreference.INHERIT);
        Map<String, String> env = new HashMap<>();
        SubprocessBackendFlags.applyEnv(env, placement);

        assertFalse(flags.contains("-D" + ND4JSystemProperties.ENV_MAX_DEVICE_MEMORY + "=0"));
        assertFalse(env.containsKey(SubprocessBackendFlags.MAX_DEVICE_BYTES_ENV));
    }
}
