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

package ai.kompile.cli.common.logs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Metadata sidecar for a single subprocess run, written as
 * {@code <runId>.meta.json} next to the log file.
 *
 * <p>Populated on start and rewritten on end by {@link SubprocessLogWriter}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubprocessLogMetadata {

    private String subprocessType;
    private String runId;
    private String parentTaskId;
    private List<String> command;
    private String workingDirectory;
    private Instant startedAt;
    private Instant endedAt;
    private Long durationMs;
    private String state;
    private Integer exitCode;
    private String errorMessage;
    private Integer linesWritten;
    private Long pid;
    private String heapSize;
    private Boolean oomDetected;
    private Boolean gpuOomDetected;
}
