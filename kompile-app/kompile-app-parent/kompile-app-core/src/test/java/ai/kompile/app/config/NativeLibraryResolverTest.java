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

package ai.kompile.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLibraryResolverTest {

    private static final String PATHS_FIRST =
            "org.bytedeco.javacpp.pathsFirst";
    private static final String CACHE_DIR =
            "org.bytedeco.javacpp.cachedir";
    private static final String RESOURCE_DIR =
            "org.bytedeco.javacpp.platform.resourcedir";
    private static final String SHARED_RUNTIME_PATH =
            "org.nd4j.presets.sharedRuntimePath";
    private static final String JAVA_LIBRARY_PATH = "java.library.path";

    @TempDir
    Path tempDirectory;

    @Test
    void publishesExactSelectedDirectoriesForNativeLoading()
            throws Exception {
        Path first = Files.createDirectory(tempDirectory.resolve("first"))
                .toRealPath();
        Path second = Files.createDirectory(tempDirectory.resolve("second"))
                .toRealPath();
        String expectedPath = first + File.pathSeparator + second;

        String oldPathsFirst = System.getProperty(PATHS_FIRST);
        String oldSharedRuntimePath = System.getProperty(SHARED_RUNTIME_PATH);
        String oldJavaLibraryPath = System.getProperty(JAVA_LIBRARY_PATH);
        try {
            NativeLibraryResolver.configureJavaCpp(List.of(first, second));

            assertEquals("true", System.getProperty(PATHS_FIRST));
            assertEquals(expectedPath, System.getProperty(SHARED_RUNTIME_PATH));
            assertTrue(System.getProperty(JAVA_LIBRARY_PATH)
                    .startsWith(expectedPath));
        } finally {
            restoreProperty(PATHS_FIRST, oldPathsFirst);
            restoreProperty(SHARED_RUNTIME_PATH, oldSharedRuntimePath);
            restoreProperty(JAVA_LIBRARY_PATH, oldJavaLibraryPath);
        }
    }

    @Test
    void keepsSingleFlatLibDirectoryAsTheCanonicalJavaCppRoot() throws Exception {
        Path selected = Files.createDirectory(tempDirectory.resolve("dist-lib"))
                .toRealPath();

        String oldPathsFirst = System.getProperty(PATHS_FIRST);
        String oldCacheDir = System.getProperty(CACHE_DIR);
        String oldResourceDir = System.getProperty(RESOURCE_DIR);
        String oldSharedRuntimePath = System.getProperty(SHARED_RUNTIME_PATH);
        String oldJavaLibraryPath = System.getProperty(JAVA_LIBRARY_PATH);
        try {
            System.setProperty(RESOURCE_DIR, "leave-existing-resource-root-alone");

            NativeLibraryResolver.configureJavaCpp(List.of(selected));

            assertEquals(selected.toString(), System.getProperty(CACHE_DIR));
            assertEquals(selected.toString(), System.getProperty(SHARED_RUNTIME_PATH));
            assertEquals("leave-existing-resource-root-alone",
                    System.getProperty(RESOURCE_DIR));
        } finally {
            restoreProperty(PATHS_FIRST, oldPathsFirst);
            restoreProperty(CACHE_DIR, oldCacheDir);
            restoreProperty(RESOURCE_DIR, oldResourceDir);
            restoreProperty(SHARED_RUNTIME_PATH, oldSharedRuntimePath);
            restoreProperty(JAVA_LIBRARY_PATH, oldJavaLibraryPath);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
