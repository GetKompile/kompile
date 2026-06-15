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
 * GraalVM native image configuration appender for commonly used Apache Commons IO classes.
 * These are often safe for build-time initialization.
 */
public class KompileApacheCommonsPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.APACHE_COMMONS;
    }

    @Override
    public List<String> classesToAppend() {
        // These Apache Commons IO classes are generally safe for build-time initialization
        // as they typically don't have complex static initializers tied to runtime state.
        return Arrays.asList(
                "org.apache.commons.io.FileUtils",
                "org.apache.commons.io.Charsets", // Though StandardCharsets is preferred
                "org.apache.commons.io.FilenameUtils",
                "org.apache.commons.io.IOUtils"
        );
    }

    @Override
    public InitializeType initializeType() {
        // Build-time initialization is usually fine for these utility classes.
        return InitializeType.BUILD_TIME;
    }

    @Override
    public boolean isNative() {
        return false; // These are pure Java libraries, not directly JNI-related.
    }
}
