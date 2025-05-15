/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai.kompile.cli.main.pomfileappender.impl;

import ai.kompile.cli.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.List;

/**
 * GraalVM native image configuration appender for Kompile Python Pipeline Steps.
 * Ensures necessary Python integration classes are initialized at runtime.
 */
public class KompilePythonPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        // Matches the updated enum in PomFileAppender
        return DependencyType.KOMPILE_PIPELINE_PYTHON;
    }

    @Override
    public List<String> classesToAppend() {
        // These classes are crucial for the Python step execution and configuration
        // and often involve JNI or dynamic aspects needing runtime initialization.
        return Arrays.asList(
                // Core Python step runner from Kompile Pipelines Framework
                "ai.kompile.pipelines.steps.python.PythonRunner",
                // Configuration class for the Python step
                "ai.kompile.pipelines.steps.python.PythonConfig",
                // Python interop helpers, if any, that need specific initialization
                // "ai.kompile.pipelines.steps.python.PythonDataTypeConverter",
                // "ai.kompile.pipelines.steps.python.PythonEnvironmentManager",
                // Consider initializing the whole package if many classes are problematic:
                "ai.kompile.pipelines.steps.python.*" // Initializes all classes in this package at runtime
        );
    }

    @Override
    public InitializeType initializeType() {
        // Python integration typically requires runtime initialization due to the
        // dynamic nature of Python script execution and potential JNI interactions.
        return InitializeType.RUNTIME;
    }

    // isNative() default is true, which is appropriate as Python steps involve native Python interpreter.
}
