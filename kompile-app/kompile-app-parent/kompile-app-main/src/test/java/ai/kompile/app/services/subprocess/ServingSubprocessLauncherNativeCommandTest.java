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

package ai.kompile.app.services.subprocess;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServingSubprocessLauncherNativeCommandTest {

    private static final String NATIVE_EXECUTABLE = "/opt/kompile/bin/kompile-server";
    private static final String MAX_BYTES_KEY = "org.bytedeco.javacpp.maxbytes";
    private static final String MAX_PHYSICAL_BYTES_KEY =
            "org.bytedeco.javacpp.maxphysicalbytes";
    private static final String PATHS_FIRST_KEY = "org.bytedeco.javacpp.pathsFirst";

    private ServingSubprocessLauncher launcher;

    @BeforeEach
    void setUp() {
        launcher = nativeLauncher(NATIVE_EXECUTABLE);
        launcher.init();
    }

    @Test
    void productionBranchUsesNativeDispatchWithoutJvmClasspath() throws Exception {
        Path argsFile = Path.of("target", "serving-subprocess-args.json").toAbsolutePath();
        String previousMaxBytes = System.getProperty(MAX_BYTES_KEY);
        String previousMaxPhysicalBytes = System.getProperty(MAX_PHYSICAL_BYTES_KEY);
        String previousPathsFirst = System.getProperty(PATHS_FIRST_KEY);

        List<String> command;
        try {
            System.setProperty(MAX_BYTES_KEY, "1");
            System.setProperty(MAX_PHYSICAL_BYTES_KEY, "2");
            System.setProperty(PATHS_FIRST_KEY, "false");
            command = launcher.buildCommand(argsFile, Nd4jEnvironmentConfig.defaults());
        } finally {
            restoreProperty(MAX_BYTES_KEY, previousMaxBytes);
            restoreProperty(MAX_PHYSICAL_BYTES_KEY, previousMaxPhysicalBytes);
            restoreProperty(PATHS_FIRST_KEY, previousPathsFirst);
        }

        assertEquals(NATIVE_EXECUTABLE, command.get(0));
        assertTrue(command.contains("-Xmx16g"));
        assertFalse(command.contains("-cp"));
        assertFalse(command.contains("ai.kompile.app.subprocess.ServingSubprocessMain"));
        assertTrue(command.stream().noneMatch(arg -> arg.startsWith("-XX:")));
        assertTrue(command.stream().anyMatch(
                arg -> arg.startsWith("-Dkompile.llm.cache.dir=")));

        String maxBytesArgument = lastArgumentStartingWith(
                command, "-D" + MAX_BYTES_KEY + "=");
        String maxPhysicalBytesArgument = lastArgumentStartingWith(
                command, "-D" + MAX_PHYSICAL_BYTES_KEY + "=");
        long maxBytes = Long.parseLong(maxBytesArgument.substring(maxBytesArgument.indexOf('=') + 1));
        long maxPhysicalBytes = Long.parseLong(
                maxPhysicalBytesArgument.substring(maxPhysicalBytesArgument.indexOf('=') + 1));
        assertTrue(maxPhysicalBytes >= maxBytes);
        assertTrue(command.lastIndexOf(maxBytesArgument)
                > command.indexOf("-D" + MAX_BYTES_KEY + "=1"));
        assertTrue(command.lastIndexOf(maxPhysicalBytesArgument)
                > command.indexOf("-D" + MAX_PHYSICAL_BYTES_KEY + "=2"));
        assertTrue(command.lastIndexOf("-D" + PATHS_FIRST_KEY + "=true")
                > command.indexOf("-D" + PATHS_FIRST_KEY + "=false"));

        int dispatchIndex = command.indexOf("--subprocess=serving");
        assertEquals(command.size() - 2, dispatchIndex);
        assertEquals(argsFile.toString(), command.get(dispatchIndex + 1));
    }

    @Test
    void dispatchedServingChildBindsOnlyToLoopback() {
        assertEquals("127.0.0.1", ServingSubprocessLauncher.SERVING_BIND_HOST);
    }

    @Test
    void cudaChildRuntimePrioritizesCudaAndExcludesSameSonameVulkanLibraries() {
        String separator = java.io.File.pathSeparator;
        String cuda = "/cache/nd4j-cuda-12.9/linux-x86_64";
        String vulkan = "/cache/nd4j-vulkan/linux-x86_64";
        String common = "/cache/tokenizers/linux-x86_64";

        String filtered = ServingSubprocessLauncher.backendCompatibleSharedRuntimePath(
                vulkan + separator + common + separator + cuda,
                "/app/nd4j-cuda-12.9.jar" + separator + "/app/samediff-llm.jar");

        assertEquals(cuda + separator + common, filtered);
    }

    @Test
    void cpuChildRuntimeExcludesGpuBackendLibraries() {
        String separator = java.io.File.pathSeparator;
        String cuda = "/cache/nd4j-cuda-12.9/linux-x86_64";
        String vulkan = "/cache/nd4j-vulkan/linux-x86_64";
        String cpu = "/cache/nd4j-native/linux-x86_64";

        String filtered = ServingSubprocessLauncher.backendCompatibleSharedRuntimePath(
                cuda + separator + cpu + separator + vulkan,
                "/app/nd4j-native.jar" + separator + "/app/samediff-llm.jar");

        assertEquals(cpu, filtered);
    }

    @Test
    void productionBranchFailsClearlyWithoutExecutable() {
        Path argsFile = Path.of("target", "serving-subprocess-args.json");
        ServingSubprocessLauncher missingExecutable = nativeLauncher(null);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> missingExecutable.buildCommand(
                        argsFile, Nd4jEnvironmentConfig.defaults()));

        assertTrue(failure.getMessage().contains("native self-executable"));
    }

    private static ServingSubprocessLauncher nativeLauncher(String executablePath) {
        return new ServingSubprocessLauncher() {
            @Override
            boolean shouldUseNativeSelfExec() {
                return true;
            }

            @Override
            String nativeSelfExecutablePath() {
                return executablePath;
            }
        };
    }

    private static String lastArgumentStartingWith(List<String> command, String prefix) {
        for (int i = command.size() - 1; i >= 0; i--) {
            if (command.get(i).startsWith(prefix)) {
                return command.get(i);
            }
        }
        throw new AssertionError("Missing command argument with prefix: " + prefix);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
