/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.model.output;

/**
 * Types of output classifications for pattern matching.
 */
public enum ClassificationType {
    /**
     * Compilation error (Java, C++, etc.).
     */
    COMPILATION_ERROR,

    /**
     * Runtime error during execution.
     */
    RUNTIME_ERROR,

    /**
     * Linker error (undefined reference, missing symbols).
     */
    LINKER_ERROR,

    /**
     * Test failure.
     */
    TEST_FAILURE,

    /**
     * Memory-related error (leak, OOM).
     */
    MEMORY_ERROR,

    /**
     * Permission or access denied.
     */
    PERMISSION_ERROR,

    /**
     * Network or connection error.
     */
    NETWORK_ERROR,

    /**
     * Configuration or setup error.
     */
    CONFIGURATION_ERROR,

    /**
     * Timeout exceeded.
     */
    TIMEOUT,

    /**
     * Successful completion.
     */
    SUCCESS,

    /**
     * Warning (non-fatal).
     */
    WARNING,

    /**
     * Informational message.
     */
    INFO,

    /**
     * Custom/user-defined type.
     */
    CUSTOM
}
