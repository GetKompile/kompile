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
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeLibraryResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void classpathNativeCacheChangesWhenAnArtifactIsReplaced() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        Path artifact = Files.writeString(temporaryDirectory.resolve("backend.jar"), "old1");
        FileTime fixedTime = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(artifact, fixedTime);
        Path first = NativeLibraryResolver.versionedClasspathCache(cache, List.of(artifact));

        Files.writeString(artifact, "new2");
        Files.setLastModifiedTime(artifact, fixedTime);
        Path second = NativeLibraryResolver.versionedClasspathCache(cache, List.of(artifact));

        assertNotEquals(first, second);
        assertEquals(cache, first.getParent());
        assertEquals(cache, second.getParent());
    }

    @Test
    void classpathNativeCachePrunesOldFingerprintsToABoundedSet() throws Exception {
        Path cache = Files.createDirectories(temporaryDirectory.resolve("cache"));
        for (int index = 0; index < 10; index++) {
            Path old = Files.createDirectories(cache.resolve("classpath-old-" + index));
            Files.writeString(old.resolve("libnative.so"), "old-" + index);
            Files.setLastModifiedTime(old, FileTime.fromMillis(1_000L + index));
        }
        Path artifact = Files.writeString(temporaryDirectory.resolve("backend.bin"), "current");

        Path selected = NativeLibraryResolver.versionedClasspathCache(cache, List.of(artifact));

        try (var children = Files.list(cache)) {
            assertEquals(8L, children.filter(Files::isDirectory).count());
        }
        assertEquals(cache, selected.getParent());
    }

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

    @Test
    void acceptsCurrentCudaProducerManifestWithZeroResources() throws Exception {
        writeNative("libnd4jcuda.so");
        writeNative("libMLIR.so.22.0git");
        writeNative("libLLVM.so.22.0git");
        writeRuntimeManifest("# runtime-count=2\n# resource-count=0\n"
                + "libMLIR.so.22.0git\nlibLLVM.so.22.0git\n");
        assertDoesNotThrow(this::validateRuntimeManifest);
    }

    @Test
    void acceptsProducerResourcesAndPackagedAliasesWithoutPreloadingThem() throws Exception {
        writeNative("libnd4jzluda.so");
        writeNative("libnvcuda.so");
        writeNative("libcuda.so"); // Classifier aliases can be regular copies.
        Files.createSymbolicLink(temporaryDirectory.resolve("libcuda.so.1"), Path.of("libnvcuda.so"));
        Files.createDirectories(temporaryDirectory.resolve("rocblas/library"));
        Files.createDirectories(temporaryDirectory.resolve(".kpack"));
        writeNative("rocblas/library/TensileLibrary.dat");
        writeNative(".kpack/blas_lib_gfx1103.kpack");
        writeRuntimeManifest("# runtime-count=1\n# runtime-alias-count=2\n"
                + "# runtime-alias=libcuda.so->libnvcuda.so\n"
                + "# runtime-alias=libcuda.so.1->libnvcuda.so\n"
                + "# resource-count=2\n# resource=rocblas/library/TensileLibrary.dat\n"
                + "# resource=.kpack/blas_lib_gfx1103.kpack\nlibnvcuda.so\n");
        assertDoesNotThrow(this::validateRuntimeManifest);
    }

    @Test
    void rejectsMalformedOrInconsistentProducerMetadata() throws Exception {
        writeNative("libnd4jcpu.so");
        writeNative("libLLVM.so");
        writeNative("libalias.so");
        Files.createDirectories(temporaryDirectory.resolve(".kpack"));
        writeNative(".kpack/test.kpack");
        for (String metadata : List.of(
                "# resource-count=-1\n", "# resource-count=not-a-count\n",
                "# resource-count=2147483648\n", "# resource-count=+0\n",
                "# resource-count=0\n# resource-count=0\n",
                "# resource-count=1\n", "# resource=.kpack/test.kpack\n",
                "# resource-count=2\n# resource=.kpack/test.kpack\n# resource=.kpack/test.kpack\n",
                "# runtime-alias-count=-1\n", "# runtime-alias-count=x\n",
                "# runtime-alias-count=2147483648\n",
                "# runtime-alias-count=0\n# runtime-alias-count=0\n",
                "# runtime-alias-count=1\n", "# runtime-alias=libalias.so->libLLVM.so\n",
                "# runtime-alias-count=2\n# runtime-alias=libalias.so->libLLVM.so\n# runtime-alias=libalias.so->libLLVM.so\n",
                "# runtime-alias-count=1\n# runtime-alias=libalias.so->missing.so\n",
                "# runtime-alias-count=1\n# runtime-alias=libLLVM.so->libLLVM.so\n",
                "# runtime-alias-count=1\n# runtime-alias=missing.so->libLLVM.so\n",
                "# runtime-alias-count=1\n# runtime-alias=libalias.so->libLLVM.so->other.so\n",
                "# runtime-alias-count=1\n# runtime-alias=../libalias.so->libLLVM.so\n",
                "# resource-count=1\n# resource=.kpack/missing.kpack\n",
                "# unknown-metadata=0\n", "# runtime-count=1\n")) {
            writeRuntimeManifest("# runtime-count=1\n" + metadata + "libLLVM.so\n");
            assertThrows(IllegalStateException.class, this::validateRuntimeManifest, metadata);
        }
    }

    @Test
    void rejectsUnsafeResourcePathsEvenWhenNormalizationWouldFindAFile() throws Exception {
        writeNative("libnd4jcpu.so");
        Files.createDirectories(temporaryDirectory.resolve(".kpack"));
        writeNative(".kpack/test.kpack");
        for (String resource : List.of("", "/tmp/test.kpack", "C:/test.kpack",
                "../test.kpack", ".kpack/../.kpack/test.kpack", ".kpack/./test.kpack",
                ".kpack//test.kpack", ".kpack/", ".kpack/..", ".kpack/.",
                ".kpack\\test.kpack", ".kpack/C:test.kpack", ".kpack/te\u0000st.kpack",
                "other/test.kpack")) {
            writeRuntimeManifest("# runtime-count=0\n# resource-count=1\n# resource=" + resource + "\n");
            assertThrows(IllegalStateException.class, this::validateRuntimeManifest, resource);
        }
    }

    @Test
    void rejectsResourceDirectoriesAndEscapingOrDanglingSymlinks() throws Exception {
        Path lib = Files.createDirectory(temporaryDirectory.resolve("lib"));
        Files.writeString(lib.resolve("libnd4jcpu.so"), "test");
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("test.kpack"), "test");
        Files.createSymbolicLink(lib.resolve(".kpack"), outside);
        Path manifest = lib.resolve(NativeLibraryResolver.SHARED_RUNTIME_MANIFEST);
        Files.writeString(manifest, "# nd4j-shared-runtime-manifest-v1\n"
                + "# runtime-count=0\n# resource-count=1\n# resource=.kpack/test.kpack\n");
        assertThrows(IllegalStateException.class, () ->
                NativeLibraryResolver.validateSideLoadedRuntime(List.of(lib)));
        Files.delete(lib.resolve(".kpack"));
        Files.createDirectory(lib.resolve(".kpack"));
        Files.createDirectory(lib.resolve(".kpack/test.kpack"));
        assertThrows(IllegalStateException.class, () ->
                NativeLibraryResolver.validateSideLoadedRuntime(List.of(lib)));
        Files.delete(lib.resolve(".kpack/test.kpack"));
        Files.createSymbolicLink(lib.resolve(".kpack/test.kpack"), outside.resolve("test.kpack"));
        assertThrows(IllegalStateException.class, () ->
                NativeLibraryResolver.validateSideLoadedRuntime(List.of(lib)));
        Files.delete(outside.resolve("test.kpack"));
        assertThrows(IllegalStateException.class, () ->
                NativeLibraryResolver.validateSideLoadedRuntime(List.of(lib)));
    }

    @Test
    void rejectsUnsafeDuplicateAndEscapingRuntimeEntries() throws Exception {
        Path lib = Files.createDirectory(temporaryDirectory.resolve("lib"));
        Files.writeString(lib.resolve("libnd4jcpu.so"), "test");
        writeNative("libLLVM.so");
        Files.createSymbolicLink(lib.resolve("libLLVM.so"), temporaryDirectory.resolve("libLLVM.so"));
        for (String entry : List.of("../libLLVM.so", "libLLVM.so", "C:libLLVM.so",
                ".", "..", "dir/libLLVM.so", "dir\\libLLVM.so")) {
            Files.writeString(lib.resolve(NativeLibraryResolver.SHARED_RUNTIME_MANIFEST),
                    "# nd4j-shared-runtime-manifest-v1\n# runtime-count=1\n" + entry + "\n");
            assertThrows(IllegalStateException.class, () ->
                    NativeLibraryResolver.validateSideLoadedRuntime(List.of(lib)), entry);
        }
        writeNative("libnd4jcpu.so");
        writeRuntimeManifest("# runtime-count=2\nlibLLVM.so\nlibLLVM.so\n");
        assertThrows(IllegalStateException.class, this::validateRuntimeManifest);
    }

    private void writeRuntimeManifest(String body) throws Exception {
        Files.writeString(temporaryDirectory.resolve(NativeLibraryResolver.SHARED_RUNTIME_MANIFEST),
                "# nd4j-shared-runtime-manifest-v1\n" + body);
    }

    @Test
    void acceptsCurrentProducerEmptySections() throws Exception {
        writeNative("libnd4jcpu.so");
        writeRuntimeManifest("# runtime-count=0\n# runtime-alias-count=0\n# resource-count=0\n");
        assertDoesNotThrow(this::validateRuntimeManifest);
    }

    @Test
    void rejectsAliasSymlinkOutsideDistribution() throws Exception {
        Path lib = Files.createDirectory(temporaryDirectory.resolve("lib"));
        Files.writeString(lib.resolve("libnd4jcpu.so"), "test");
        Files.writeString(lib.resolve("libLLVM.so"), "test");
        writeNative("libalias.so");
        Files.createSymbolicLink(lib.resolve("libalias.so"), temporaryDirectory.resolve("libalias.so"));
        Files.writeString(lib.resolve(NativeLibraryResolver.SHARED_RUNTIME_MANIFEST),
                "# nd4j-shared-runtime-manifest-v1\n# runtime-count=1\n"
                        + "# runtime-alias-count=1\n# runtime-alias=libalias.so->libLLVM.so\n"
                        + "# resource-count=0\nlibLLVM.so\n");
        assertThrows(IllegalStateException.class, () ->
                NativeLibraryResolver.validateSideLoadedRuntime(List.of(lib)));
    }

    private void validateRuntimeManifest() {
        NativeLibraryResolver.validateSideLoadedRuntime(List.of(temporaryDirectory));
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
