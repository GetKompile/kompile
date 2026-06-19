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

/**
 * A single JSON-lines record inside an agent log file.
 *
 * <p>Fields are deliberately short to keep disk footprint low. The file itself
 * carries the per-run context ({@code processId}, {@code agentName}); these
 * fields are populated only when records are aggregated across files.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentLogRecord {

    public enum Stream {
        STDOUT,
        STDERR,
        SYSTEM
    }

    private Instant ts;
    private Stream stream;
    private String line;
    private Integer seq;

    private String processId;
    private String agentName;
    private String orchestratorInstanceId;
    private Long sessionId;

    public AgentLogRecord(Instant ts, Stream stream, String line, Integer seq) {
        this.ts = ts;
        this.stream = stream;
        this.line = line;
        this.seq = seq;
    }
}
