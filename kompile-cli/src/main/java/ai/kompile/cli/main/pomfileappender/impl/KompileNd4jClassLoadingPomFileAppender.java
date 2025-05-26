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
import java.util.List;

/**
 * GraalVM native image configuration appender for ND4J core class loading
 * and precondition checks. These are foundational for ND4J operations.
 */
public class KompileNd4jClassLoadingPomFileAppender implements PomFileAppender { // Renamed class

    @Override
    public DependencyType dependencyType() {
        // Using ND4J_CLASSLOADING from the original PomFileAppender interface's enum.
        // Could be mapped to a broader ND4J_CORE if the enum is consolidated.
        return DependencyType.KOMPILE_ND4J_CLASSLOADING;
    }

    @Override
    public List<String> classesToAppend() {
        // These are core ND4J utility classes. Their package names are stable.
        // Runtime initialization is often safer for ND4J components due to potential
        // static initializers that might try to access native backends or resources
        // that are only fully available at runtime in a native image.
        return Arrays.asList(
                "org.nd4j.common.config.ND4JClassLoading",
                "org.nd4j.common.base.Preconditions",
                "org.nd4j.common.config.ND4JEnvironmentVars", // Environment variable checks
                "org.nd4j.common.util. refleja.ReflectionUtil", // If ND4J uses its own reflection utils
                "org.nd4j.common.io.ReflectionUtils" // Another common reflection utility in ND4J
        );
    }

    @Override
    public InitializeType initializeType() {
        // While some utilities might seem safe for BUILD_TIME, ND4J's ecosystem
        // often has static blocks that interact with deeper systems (like backend loading).
        // RUNTIME is generally a safer default for ND4J core components to ensure
        // the full environment (including native libraries) is ready.
        return InitializeType.RUNTIME;
    }

    // isNative() default is true. ND4J is heavily JNI-based.
}
