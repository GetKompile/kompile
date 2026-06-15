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

package ai.kompile.cli.main.pomfileappender.impl;

import ai.kompile.cli.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * GraalVM native image configuration appender for OpenBLAS JavaCPP presets.
 * Ensures that the JNI bridge classes for OpenBLAS are initialized at runtime.
 */
public class KompileOpenblasPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.OPENBLAS;
    }

    @Override
    public List<String> classesToAppend() {
        // These are the key classes for JavaCPP's OpenBLAS integration.
        // Initializing them at runtime ensures native libraries are loaded correctly.
        return Arrays.asList(
                "org.bytedeco.openblas.presets.openblas",    // Preset loader for openblas
                "org.bytedeco.openblas.global.openblas",     // Global JNI bindings for openblas
                "org.bytedeco.openblas.global.openblas_nolapack" // Global JNI for openblas without LAPACK
                // Consider "org.bytedeco.openblas.*" if more classes from this package are needed
        );
    }

    @Override
    public List<String> classesToReInitialize() {
        // The original had this commented out. Re-initialization is rarely needed
        // unless static state set during build time by these classes is problematic at runtime.
        // For JNI loaders, re-initialization is usually not what you want.
        // If issues arise with OpenBLAS not loading correctly, this could be investigated,
        // but typically, runtime init of the Loader/presets is sufficient.
        return Collections.emptyList();
    }

    @Override
    public InitializeType initializeType() {
        // JNI library loading is a runtime activity.
        return InitializeType.RUNTIME;
    }

    // isNative() default is true, which is correct as OpenBLAS is a native library.
}
