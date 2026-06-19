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

package ai.kompile.process.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runtime record of a single step execution within a {@link WorkflowRun}.
 * Contains input/output snapshots and hashes for full reproducibility auditing.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StepExecution {

    private String stepId;
    private String stepName;
    private StepExecutionStatus status;
    private Instant startedAt;
    private Instant completedAt;
    /** Agent name or person ID that executed this step. */
    private String executedBy;
    /** Snapshot of input data consumed by this step at execution time. */
    private Map<String, Object> inputs;
    /** Snapshot of output data produced by this step. */
    private Map<String, Object> outputs;
    /** SHA-256 hash of the inputs snapshot. */
    private String inputHash;
    /** SHA-256 hash of the outputs snapshot. */
    private String outputHash;
    /** Source IDs of evidence documents consulted during this step. */
    private List<String> evidenceReliedOn;
    /** Structured evidence entries (key-value maps) attached during step execution. */
    private List<Map<String, Object>> structuredEvidence;
    /** Knowledge graph node IDs of documents/entities accessed or produced by this step. */
    private List<String> graphNodeIds;
    /** Error message if the step failed. */
    private String error;
}
