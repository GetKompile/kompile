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

package ai.kompile.pipeline.serving.subprocess;

import ai.kompile.app.subprocess.SubprocessArgsIo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Arguments passed to the pipeline serving subprocess via a temp JSON file.
 *
 * <p>Following the established pattern from {@code SubprocessArgs} in kompile-app-core:
 * the parent serializes this record to a temp file, passes the path as args[0] to the
 * subprocess, and the subprocess reads/deserializes on startup.</p>
 *
 * <p>This avoids command-line length limits and escaping issues.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PipelineServingSubprocessArgs(
        /** Unique task ID for this execution */
        String taskId,

        /** Serialized UnifiedPipelineDefinition JSON */
        String pipelineDefinitionJson,

        /** Execution mode: "ONE_SHOT" or "PERSISTENT_SERVING" */
        String executionMode,

        /** For ONE_SHOT: serialized input Data as JSON (null for PERSISTENT_SERVING) */
        String requestDataJson,

        /** For PERSISTENT_SERVING: HTTP port (0 = use definition's port or auto-assign) */
        int servingPort,

        /** Forwarded ND4J configuration JSON (may be null) */
        String nd4jConfigJson,

        /** JVM heap memory stop threshold percent */
        int memoryStopPercent,

        /** JVM heap memory critical threshold percent */
        int memoryCriticalPercent,

        /** JVM heap memory kill threshold percent */
        int memoryKillPercent,

        /** Memory check interval in milliseconds */
        long memoryCheckIntervalMs,

        /** GPU memory stop threshold percent */
        int gpuMemoryStopPercent,

        /** GPU memory critical threshold percent */
        int gpuMemoryCriticalPercent,

        /** GPU memory kill threshold percent */
        int gpuMemoryKillPercent,

        /** Heartbeat interval in milliseconds */
        long heartbeatIntervalMs,

        /** Callback base URL for event persistence (may be null) */
        String callbackBaseUrl
) {

    /** Execution mode constants */
    public static final String MODE_ONE_SHOT = "ONE_SHOT";
    public static final String MODE_PERSISTENT_SERVING = "PERSISTENT_SERVING";

    /**
     * Read args from a JSON file (subprocess entry point).
     */
    public static PipelineServingSubprocessArgs fromFile(Path path) throws IOException {
        return SubprocessArgsIo.fromFile(path, PipelineServingSubprocessArgs.class);
    }

    /**
     * Write args to a temp file (parent side, before launching subprocess).
     * Returns the path to the created temp file.
     */
    public Path writeToTempFile() throws IOException {
        return SubprocessArgsIo.writeToTempFile(this, "pipeline-serving-args-");
    }
}
