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

package ai.kompile.pipeline.serving.launcher;

import java.time.Instant;

/**
 * Immutable snapshot of a running pipeline serving subprocess.
 * Stored in the launcher's active handles map, keyed by pipelineId.
 */
public record PipelineServingHandle(
        /** Pipeline definition ID */
        String pipelineId,

        /** Pipeline kind (LLM, VLM, RAG, GENERIC) */
        String kind,

        /** The OS process handle */
        Process process,

        /** HTTP port the subprocess is serving on */
        int port,

        /** Subprocess PID */
        long pid,

        /** When the subprocess started */
        Instant startedAt,

        /** Task ID for correlating messages */
        String taskId
) {
    /**
     * Check if the subprocess is still alive.
     */
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Get the base URL for HTTP calls to this subprocess.
     */
    public String baseUrl() {
        return "http://localhost:" + port;
    }
}
