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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.subprocess;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubprocessEnvironmentPropagatorTest {

    private static final String SHARED_RUNTIME_PATH = "org.nd4j.presets.sharedRuntimePath";

    @Test
    void cudaChildReceivesCudaFirstPathWithoutVulkanRuntime() {
        String cuda = "/cache/nd4j-cuda-12.9/runtime";
        String common = "/cache/openblas/runtime";
        String vulkan = "/cache/nd4j-vulkan/runtime";
        assertRuntimePath(cuda + File.pathSeparator + common,
                common + File.pathSeparator + vulkan + File.pathSeparator + cuda,
                "/deps/nd4j-cuda-12.9.jar");
    }

    @Test
    void cpuChildReceivesOnlyCpuCompatibleRuntimePaths() {
        String cpu = "/cache/nd4j-native/runtime";
        String common = "/cache/openblas/runtime";
        String cuda = "/cache/nd4j-cuda-12.9/runtime";
        assertRuntimePath(cpu + File.pathSeparator + common,
                common + File.pathSeparator + cuda + File.pathSeparator + cpu,
                "/deps/nd4j-native.jar");
    }

    private static void assertRuntimePath(String expected, String configured, String childClasspath) {
        String previous = System.getProperty(SHARED_RUNTIME_PATH);
        try {
            System.setProperty(SHARED_RUNTIME_PATH, configured);
            List<String> flags = SubprocessEnvironmentPropagator.buildSystemPropertyFlags(childClasspath);
            String prefix = "-D" + SHARED_RUNTIME_PATH + "=";
            String actual = flags.stream()
                    .filter(flag -> flag.startsWith(prefix))
                    .map(flag -> flag.substring(prefix.length()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(expected, actual);
        } finally {
            if (previous == null) {
                System.clearProperty(SHARED_RUNTIME_PATH);
            } else {
                System.setProperty(SHARED_RUNTIME_PATH, previous);
            }
        }
    }
}
