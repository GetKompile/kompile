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

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Selects side-loaded native runtime directories for an isolated backend subprocess.
 *
 * <p>JavaCPP may create same-SONAME compiler-runtime symlinks in shared cache directories
 * such as OpenBLAS. A child must therefore both exclude other ND4J backends and put its own
 * backend directory before common directories. This keeps LLVM/MLIR selection deterministic
 * without relying on {@code LD_PRELOAD} or embedding backend libraries in the native image.</p>
 */
public final class NativeRuntimePathSelector {

    private NativeRuntimePathSelector() {
    }

    /**
     * Return a backend-compatible, backend-first runtime path for the supplied child classpath.
     * Unknown or classpathless children retain the original path unchanged.
     */
    public static String forChild(String runtimePath, String childClasspath) {
        if (runtimePath == null || runtimePath.isBlank()
                || childClasspath == null || childClasspath.isBlank()) {
            return runtimePath;
        }

        String normalizedClasspath = childClasspath.toLowerCase(Locale.ROOT);
        Backend backend = normalizedClasspath.contains("nd4j-cuda")
                ? Backend.CUDA
                : normalizedClasspath.contains("nd4j-vulkan")
                        ? Backend.VULKAN
                        : normalizedClasspath.contains("nd4j-native")
                                ? Backend.CPU
                                : null;
        if (backend == null) {
            return runtimePath;
        }

        return Arrays.stream(runtimePath.split(File.pathSeparator))
                .filter(path -> backend.accepts(path.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingInt(path ->
                        backend.isOwnRuntime(path.toLowerCase(Locale.ROOT)) ? 0 : 1))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private enum Backend {
        CUDA {
            @Override
            boolean accepts(String path) {
                return !path.contains("nd4j-vulkan");
            }

            @Override
            boolean isOwnRuntime(String path) {
                return path.contains("nd4j-cuda");
            }
        },
        VULKAN {
            @Override
            boolean accepts(String path) {
                return !path.contains("nd4j-cuda");
            }

            @Override
            boolean isOwnRuntime(String path) {
                return path.contains("nd4j-vulkan");
            }
        },
        CPU {
            @Override
            boolean accepts(String path) {
                return !path.contains("nd4j-cuda") && !path.contains("nd4j-vulkan");
            }

            @Override
            boolean isOwnRuntime(String path) {
                return path.contains("nd4j-native");
            }
        };

        abstract boolean accepts(String path);

        abstract boolean isOwnRuntime(String path);
    }
}
