/*
 * Copyright 2025 Kompile Inc. (derived from Konduit K.K. original)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * // ... (license text)
 * SPDX-License-Identifier: Apache-2.0
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
