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
 *  limitations under the License.
 */

package ai.kompile.utils;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeRuntimePathSelectorTest {

    @Test
    void cudaRuntimeComesBeforePollutedCommonCacheAndVulkanIsExcluded() {
        String separator = File.pathSeparator;
        String common = "/cache/openblas/linux-x86_64";
        String vulkan = "/cache/nd4j-vulkan/linux-x86_64";
        String cuda = "/cache/nd4j-cuda-12.9/linux-x86_64";

        assertEquals(
                cuda + separator + common,
                NativeRuntimePathSelector.forChild(
                        common + separator + vulkan + separator + cuda,
                        "/app/nd4j-cuda-12.9.jar"));
    }

    @Test
    void vulkanRuntimeComesBeforeCommonCacheAndCudaIsExcluded() {
        String separator = File.pathSeparator;
        String common = "/cache/openblas/linux-x86_64";
        String vulkan = "/cache/nd4j-vulkan/linux-x86_64";
        String cuda = "/cache/nd4j-cuda-12.9/linux-x86_64";

        assertEquals(
                vulkan + separator + common,
                NativeRuntimePathSelector.forChild(
                        cuda + separator + common + separator + vulkan,
                        "/app/nd4j-vulkan.jar"));
    }

    @Test
    void cpuRuntimeExcludesGpuBackendsAndComesFirst() {
        String separator = File.pathSeparator;
        String common = "/cache/openblas/linux-x86_64";
        String cpu = "/cache/nd4j-native/linux-x86_64";

        assertEquals(
                cpu + separator + common,
                NativeRuntimePathSelector.forChild(
                        "/cache/nd4j-cuda/linux-x86_64" + separator
                                + common + separator + cpu + separator
                                + "/cache/nd4j-vulkan/linux-x86_64",
                        "/app/nd4j-native.jar"));
    }

    @Test
    void classpathlessNativeChildRetainsFlatDistributionPath() {
        String original = "/opt/kompile/lib";
        assertEquals(original, NativeRuntimePathSelector.forChild(original, null));
    }
}
