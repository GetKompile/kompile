/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.cli.main.pomfileappender.impl;

import ai.kompile.cli.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.List;

/**
 * GraalVM native image configuration appender for JavaCPP (org.bytedeco).
 * Ensures the core JavaCPP loader and related classes are initialized at runtime.
 */
public class KompileJavaCppPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.JAVACPP;
    }

    @Override
    public List<String> classesToAppend() {
        // Initializing the entire org.bytedeco package at runtime is a common strategy
        // because JavaCPP involves loading native libraries and JNI, which is best
        // handled when the application environment is fully set up.
        return Arrays.asList(
                "org.bytedeco.javacpp.Loader", // Core loader class
                "org.bytedeco.javacpp.*"       // Initialize all classes in the base JavaCPP package
                // Specific preset packages might be added if they have static blocks causing issues:
                // "org.bytedeco.openblas.*",
                // "org.bytedeco.dnnl.*",
                // etc.
        );
    }

    @Override
    public InitializeType initializeType() {
        return InitializeType.RUNTIME;
    }
}
