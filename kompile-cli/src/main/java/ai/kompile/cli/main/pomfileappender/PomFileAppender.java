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

package ai.kompile.cli.main.pomfileappender; // Package may change based on final CLI structure

import java.util.Collections;
import java.util.List;

/**
 * Interface for components that append GraalVM native image build arguments,
 * typically for class initialization configurations.
 */
public interface PomFileAppender {

    /**
     * Defines the types of dependencies or components these appenders cater to.
     * This enum should be updated to reflect Kompile modules and common libraries.
     */
    enum DependencyType {
        // Kompile Pipelines Steps
        KOMPILE_PIPELINE_PYTHON,
        KOMPILE_PIPELINE_ONNX,
        KOMPILE_PIPELINE_DL4J,
        KOMPILE_PIPELINE_SAMEDIFF,
        KOMPILE_PIPELINE_TENSORFLOW,
        KOMPILE_PIPELINE_TVM,
        KOMPILE_PIPELINE_IMAGE, // For ImageToNDArray steps etc.
        KOMPILE_PIPELINE_DOCUMENT_PARSER,

        // Kompile App Providers & Tools
        KOMPILE_APP_SPRING_BOOT, // For Spring Boot specific native configurations
        KOMPILE_APP_SPRING_AI,   // For general Spring AI components
        KOMPILE_APP_OPENAI_LLM,
        KOMPILE_APP_ANTHROPIC_LLM,
        KOMPILE_APP_GEMINI_LLM,
        KOMPILE_EMBEDDING_OPENAI,
        KOMPILE_EMBEDDING_SENTENCE_TRANSFORMER,
        KOMPILE_VECTORSTORE_CHROMA,
        KOMPILE_VECTORSTORE_PGVECTOR,
        KOMPILE_LOADER_TIKA,
        KOMPILE_LOADER_PDF,
        KOMPILE_TOOL_RAG,
        KOMPILE_TOOL_FILESYSTEM,

        // Core Java / Common Libraries
        JAVACPP,        // For org.bytedeco related configurations
        ND4J_CORE,      // For core ND4J (classloading, shaded Jackson, etc.)
        OPENBLAS,       // If OpenBLAS JNI needs specific flags beyond JavaCPP generic
        APACHE_COMMONS,
        JODA_TIME,      // If Joda Time is still heavily used and needs native hints
        SUN_XML,        // For JAXB/XML bindings if used and problematic in native

        // Generic or other categories
        OTHER_NATIVE_LIB,
        KOMPILE_ND4J_CLASSLOADING
    }

    enum InitializeType {
        RUNTIME,
        BUILD_TIME
    }

    /**
     * Returns the specific type of dependency or component this appender targets.
     * This helps in selectively applying appenders.
     * @return The dependency type.
     */
    DependencyType dependencyType();

    /**
     * The list of fully qualified class names or package names (e.g., "com.example.MyClass", "com.example.mypackage.*")
     * to be passed to GraalVM's --initialize-at-build-time or --initialize-at-run-time.
     * @return A list of class/package strings.
     */
    List<String> classesToAppend();

    /**
     * The list of fully qualified class names or package names
     * to be passed to GraalVM's --rerun-class-initialization-at-runtime.
     * @return A list of class/package strings. Defaults to empty list.
     */
    default List<String> classesToReInitialize() {
        return Collections.emptyList();
    }

    /**
     * Appends the --rerun-class-initialization-at-runtime arguments to the StringBuilder.
     * @param stringBuilder The StringBuilder to append to.
     */
    default void appendReInitialize(StringBuilder stringBuilder) {
        for(String clazzOrPackage : classesToReInitialize()) {
            stringBuilder.append(String.format("--rerun-class-initialization-at-runtime=%s%n", clazzOrPackage));
        }
    }

    /**
     * Appends the --initialize-at-build-time or --initialize-at-run-time arguments
     * based on the {@link #initializeType()}.
     * @param stringBuilder The StringBuilder to append to.
     */
    default void append(StringBuilder stringBuilder) {
        for(String clazzOrPackage : classesToAppend()) {
            if(initializeType() == InitializeType.BUILD_TIME)
                stringBuilder.append(String.format("--initialize-at-build-time=%s%n", clazzOrPackage));
            else if(initializeType() == InitializeType.RUNTIME) {
                stringBuilder.append(String.format("--initialize-at-run-time=%s%n", clazzOrPackage));
            }
        }
    }

    /**
     * Specifies whether the listed classes/packages should be initialized at GraalVM build time or at application run time.
     * @return The initialization type.
     */
    InitializeType initializeType();

    /**
     * Indicates if this appender is primarily for components that involve native libraries (JNI).
     * This flag's usage might be reviewed or used by PomGenerator to conditionally apply appenders.
     * @return true if related to native components, false otherwise. Defaults to true.
     */
    default boolean isNative() {
        return true;
    }
}
