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

package ai.kompile.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeLibraryResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void detectsNativeRuntimeWithoutImageInfoReflection() {
        String previous = System.getProperty(NativeImageInfo.IMAGE_CODE_PROPERTY);
        try {
            System.setProperty(NativeImageInfo.IMAGE_CODE_PROPERTY, "runtime");
            assertEquals(true, NativeImageInfo.detectNativeImage());
        } finally {
            restoreProperty(NativeImageInfo.IMAGE_CODE_PROPERTY, previous);
        }
    }

    @Test
    void runtimeImageCodeOverridesHostedProcessCaches() throws Exception {
        String previousProperty = System.getProperty(NativeImageInfo.IMAGE_CODE_PROPERTY);
        Field nativeImage = NativeImageInfo.class.getDeclaredField("isNativeImage");
        Field executablePath = NativeImageInfo.class.getDeclaredField("executablePath");
        Field executablePathResolved = NativeImageInfo.class.getDeclaredField("executablePathResolved");
        nativeImage.setAccessible(true);
        executablePath.setAccessible(true);
        executablePathResolved.setAccessible(true);
        Object previousNativeImage = nativeImage.get(null);
        Object previousExecutablePath = executablePath.get(null);
        boolean previousExecutablePathResolved = executablePathResolved.getBoolean(null);
        try {
            nativeImage.set(null, Boolean.FALSE);
            executablePath.set(null, "/hosted/builder/java");
            executablePathResolved.setBoolean(null, true);
            System.setProperty(NativeImageInfo.IMAGE_CODE_PROPERTY, "runtime");

            assertEquals(true, NativeImageInfo.isRunningInNativeImage());
            assertEquals(false, "/hosted/builder/java".equals(
                    NativeImageInfo.getExecutablePath()));
        } finally {
            nativeImage.set(null, previousNativeImage);
            executablePath.set(null, previousExecutablePath);
            executablePathResolved.setBoolean(null, previousExecutablePathResolved);
            restoreProperty(NativeImageInfo.IMAGE_CODE_PROPERTY, previousProperty);
        }
    }

    @Test
    void acceptsProducerManifestWithCompleteRuntimeClosure() throws Exception {
        writeNative("libnd4jcpu.so");
        writeNative("libjnind4jcpu.so");
        writeNative("libLLVM.so.22");
        Files.writeString(
                temporaryDirectory.resolve(NativeLibraryResolver.SHARED_RUNTIME_MANIFEST),
                "# nd4j-shared-runtime-manifest-v1\n"
                        + "# runtime-count=1\n"
                        + "libLLVM.so.22\n");

        assertDoesNotThrow(() ->
                NativeLibraryResolver.validateSideLoadedRuntime(
                        List.of(temporaryDirectory)));
    }

    @Test
    void acceptsBackendWithAnExplicitEmptyRuntimeClosure() throws Exception {
        writeNative("libnd4jcpu.so");
        writeNative("libjnind4jcpu.so");
        Files.writeString(
                temporaryDirectory.resolve(NativeLibraryResolver.SHARED_RUNTIME_MANIFEST),
                "# nd4j-shared-runtime-manifest-v1\n"
                        + "# runtime-count=0\n");

        assertDoesNotThrow(() ->
                NativeLibraryResolver.validateSideLoadedRuntime(
                        List.of(temporaryDirectory)));
    }

    @Test
    void rejectsMissingManifestOwnedRuntime() throws Exception {
        writeNative("libnd4jcuda.so");
        writeNative("libjnind4jcuda.so");
        Files.writeString(
                temporaryDirectory.resolve(NativeLibraryResolver.SHARED_RUNTIME_MANIFEST),
                "# nd4j-shared-runtime-manifest-v1\n"
                        + "# runtime-count=1\n"
                        + "libMLIR.so.22\n");

        assertThrows(IllegalStateException.class, () ->
                NativeLibraryResolver.validateSideLoadedRuntime(
                        List.of(temporaryDirectory)));
    }

    @Test
    void rejectsNd4jRuntimeWithoutProducerManifest() throws Exception {
        writeNative("libnd4jcpu.so");
        writeNative("libjnind4jcpu.so");

        assertThrows(IllegalStateException.class, () ->
                NativeLibraryResolver.validateSideLoadedRuntime(
                        List.of(temporaryDirectory)));
    }

    @Test
    void rejectsManifestCountMismatch() throws Exception {
        writeNative("libnd4jcpu.so");
        writeNative("libjnind4jcpu.so");
        writeNative("libLLVM.so.22");
        Files.writeString(
                temporaryDirectory.resolve(NativeLibraryResolver.SHARED_RUNTIME_MANIFEST),
                "# nd4j-shared-runtime-manifest-v1\n"
                        + "# runtime-count=2\n"
                        + "libLLVM.so.22\n");

        assertThrows(IllegalStateException.class, () ->
                NativeLibraryResolver.validateSideLoadedRuntime(
                        List.of(temporaryDirectory)));
    }

    @Test
    void plansCudaJniBridgesInDependencyOrder() throws Exception {
        writeNative("libjnind4jcuda.so");
        writeNative("libjnicublas.so");
        writeNative("libjvm.so");
        writeNative("libjnijavacpp.so");
        writeNative("libjnicudart.so");
        writeNative("libjnitokenizers.so");

        assertEquals(
                List.of(
                        temporaryDirectory.resolve("libjvm.so"),
                        temporaryDirectory.resolve("libjnijavacpp.so"),
                        temporaryDirectory.resolve("libjnicudart.so"),
                        temporaryDirectory.resolve("libjnicublas.so"),
                        temporaryDirectory.resolve("libjnind4jcuda.so")),
                NativeLibraryResolver.sideLoadedJniLoadPlan(List.of(temporaryDirectory)));
    }

    @Test
    void resolvesVersionedHostPtxCompilerForCudaBackend() throws Exception {
        writeNative("libjnind4jcuda.so");
        Path driverDirectory = Files.createDirectory(temporaryDirectory.resolve("driver"));
        Path ptxCompiler = driverDirectory.resolve(
                "libnvidia-ptxjitcompiler.so.570.144");
        Files.writeString(ptxCompiler, "test");

        assertEquals(
                List.of(ptxCompiler),
                NativeLibraryResolver.cudaDriverCompanionLoadPlan(
                        List.of(temporaryDirectory), List.of(driverDirectory)));
    }

    @Test
    void failsLoudlyWhenCudaDriverCompanionIsMissing() throws Exception {
        writeNative("libjnind4jcuda.so");
        Path emptyDriverDirectory = Files.createDirectory(
                temporaryDirectory.resolve("empty-driver"));

        assertThrows(IllegalStateException.class, () ->
                NativeLibraryResolver.cudaDriverCompanionLoadPlan(
                        List.of(temporaryDirectory), List.of(emptyDriverDirectory)));
    }

    @Test
    void nonCudaBackendDoesNotRequireNvidiaDriverCompanions() throws Exception {
        writeNative("libjnind4jcpu.so");

        assertEquals(
                List.of(),
                NativeLibraryResolver.cudaDriverCompanionLoadPlan(
                        List.of(temporaryDirectory), List.of()));
    }

    @Test
    void coreBootstrapDoesNotLoadPackagedModelBackend() throws Exception {
        for (String name : List.of(
                "libjvm.so", "libjnijavacpp.so", "libjnicudart.so",
                "libjnicublas.so", "libjnind4jcuda.so", "libsqlitejdbc.so")) {
            writeNative(name);
        }
        writeJniEntrypointManifest("libsqlitejdbc.so");

        assertEquals(
                List.of(temporaryDirectory.resolve("libsqlitejdbc.so")),
                NativeLibraryResolver.sideLoadedJniLoadPlan(
                        List.of(temporaryDirectory), NativeLibraryResolver.BootstrapMode.CORE));
    }

    @Test
    void modelBootstrapDoesNotLoadApplicationDirectJni() throws Exception {
        for (String name : List.of(
                "libjvm.so", "libjnijavacpp.so", "libjnicudart.so",
                "libjnicublas.so", "libjnind4jcuda.so", "libsqlitejdbc.so")) {
            writeNative(name);
        }
        writeJniEntrypointManifest("libsqlitejdbc.so");

        assertEquals(
                List.of(
                        temporaryDirectory.resolve("libjvm.so"),
                        temporaryDirectory.resolve("libjnijavacpp.so"),
                        temporaryDirectory.resolve("libjnicudart.so"),
                        temporaryDirectory.resolve("libjnicublas.so"),
                        temporaryDirectory.resolve("libjnind4jcuda.so")),
                NativeLibraryResolver.sideLoadedJniLoadPlan(
                        List.of(temporaryDirectory),
                        NativeLibraryResolver.BootstrapMode.MODEL_EXECUTION));
    }

    @Test
    void plansGenericJniPayloadWithoutJavaCppRuntime() throws Exception {
        writeNative("libsqlitejdbc.so");
        writeJniEntrypointManifest("libsqlitejdbc.so");

        assertEquals(
                List.of(temporaryDirectory.resolve("libsqlitejdbc.so")),
                NativeLibraryResolver.sideLoadedJniLoadPlan(List.of(temporaryDirectory)));
    }

    @Test
    void doesNotEagerlyLoadUnlistedNativeOrJavaCppPresetBridge() throws Exception {
        writeNative("libsqlitejdbc.so");
        writeNative("libjnitokenizers.so");
        writeNative("libtokenizers_wrapper.so");
        writeJniEntrypointManifest("libsqlitejdbc.so");

        assertEquals(
                List.of(temporaryDirectory.resolve("libsqlitejdbc.so")),
                NativeLibraryResolver.sideLoadedJniLoadPlan(List.of(temporaryDirectory)));
    }

    @Test
    void rejectsMissingManifestDeclaredJniEntrypoint() throws Exception {
        writeJniEntrypointManifest("libsqlitejdbc.so");

        assertThrows(IllegalStateException.class, () ->
                NativeLibraryResolver.sideLoadedJniLoadPlan(List.of(temporaryDirectory)));
    }

    @Test
    void plansImageShimBeforeBridgesAcrossDistributionDirectories() throws Exception {
        Path imageDirectory = Files.createDirectory(temporaryDirectory.resolve("bin"));
        Path runtimeDirectory = Files.createDirectory(temporaryDirectory.resolve("lib"));
        Files.writeString(imageDirectory.resolve("libjvm.so"), "test");
        for (String name : List.of(
                "libjnijavacpp.so", "libjnicudart.so", "libjnicublas.so", "libjnind4jcuda.so")) {
            Files.writeString(runtimeDirectory.resolve(name), "test");
        }

        assertEquals(
                List.of(
                        imageDirectory.resolve("libjvm.so"),
                        runtimeDirectory.resolve("libjnijavacpp.so"),
                        runtimeDirectory.resolve("libjnicudart.so"),
                        runtimeDirectory.resolve("libjnicublas.so"),
                        runtimeDirectory.resolve("libjnind4jcuda.so")),
                NativeLibraryResolver.sideLoadedJniLoadPlan(
                        List.of(imageDirectory, runtimeDirectory)));
    }

    @Test
    void rejectsIncompleteCudaJniClosure() throws Exception {
        writeNative("libjvm.so");
        writeNative("libjnijavacpp.so");
        writeNative("libjnind4jcuda.so");

        assertThrows(IllegalStateException.class, () ->
                NativeLibraryResolver.sideLoadedJniLoadPlan(List.of(temporaryDirectory)));
    }

    @Test
    void selectsAcceleratorBackendFromPackagedCudaBridge() throws Exception {
        writeNative("libjnind4jcuda.so");
        String previousCpu = System.getProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY);
        String previousGpu = System.getProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY);
        try {
            System.clearProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY);
            System.clearProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY);

            NativeLibraryResolver.configureNd4jBackendPriorities(List.of(temporaryDirectory));

            assertEquals("0", System.getProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY));
            assertEquals("100", System.getProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY));
        } finally {
            restoreProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY, previousCpu);
            restoreProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY, previousGpu);
        }
    }

    @Test
    void selectsCpuBackendFromPackagedCpuBridge() throws Exception {
        writeNative("libjnind4jcpu.so");
        String previousCpu = System.getProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY);
        String previousGpu = System.getProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY);
        try {
            System.clearProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY);
            System.clearProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY);

            NativeLibraryResolver.configureNd4jBackendPriorities(List.of(temporaryDirectory));

            assertEquals("100", System.getProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY));
            assertEquals("0", System.getProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY));
        } finally {
            restoreProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY, previousCpu);
            restoreProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY, previousGpu);
        }
    }

    @Test
    void preservesExplicitNd4jBackendPriorities() throws Exception {
        writeNative("libjnind4jcuda.so");
        String previousCpu = System.getProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY);
        String previousGpu = System.getProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY);
        try {
            System.setProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY, "250");
            System.setProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY, "5");

            NativeLibraryResolver.configureNd4jBackendPriorities(List.of(temporaryDirectory));

            assertEquals("250", System.getProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY));
            assertEquals("5", System.getProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY));
        } finally {
            restoreProperty(NativeLibraryResolver.ND4J_CPU_PRIORITY, previousCpu);
            restoreProperty(NativeLibraryResolver.ND4J_GPU_PRIORITY, previousGpu);
        }
    }

    @Test
    void publishesTheSameExactRuntimeSelectionToNd4jAndJavaCpp() throws Exception {
        Path first = Files.createDirectory(temporaryDirectory.resolve("first"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("second"));
        String expected = first.toAbsolutePath() + java.io.File.pathSeparator
                + second.toAbsolutePath();
        String previousNd4j = System.getProperty(
                NativeLibraryResolver.ND4J_SHARED_RUNTIME_PATH);
        String previousJavaCpp = System.getProperty(
                NativeLibraryResolver.JAVACPP_LIBRARY_PATH);
        try {
            NativeLibraryResolver.configureJavaCpp(List.of(first, second));

            assertEquals(expected, System.getProperty(
                    NativeLibraryResolver.ND4J_SHARED_RUNTIME_PATH));
            assertEquals(expected, System.getProperty(
                    NativeLibraryResolver.JAVACPP_LIBRARY_PATH));
        } finally {
            restoreProperty(NativeLibraryResolver.ND4J_SHARED_RUNTIME_PATH, previousNd4j);
            restoreProperty(NativeLibraryResolver.JAVACPP_LIBRARY_PATH, previousJavaCpp);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private void writeNative(String name) throws Exception {
        Files.writeString(temporaryDirectory.resolve(name), "test");
    }

    private void writeJniEntrypointManifest(String... names) throws Exception {
        Files.writeString(
                temporaryDirectory.resolve(NativeLibraryResolver.JNI_ENTRYPOINT_MANIFEST),
                "# kompile-jni-entrypoint-manifest-v1\n"
                        + "# entry-count=" + names.length + "\n"
                        + String.join("\n", names)
                        + (names.length == 0 ? "" : "\n"));
    }
}
