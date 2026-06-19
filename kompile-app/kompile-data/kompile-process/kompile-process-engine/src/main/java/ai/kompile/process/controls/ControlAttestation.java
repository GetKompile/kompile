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

package ai.kompile.process.controls;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable record of a single control evaluation event.
 * Once written this record must never be modified — it constitutes the SOX audit trail.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ControlAttestation {

    private String id;
    private String controlId;
    private String workflowRunId;
    private String stepId;
    private Instant evaluatedAt;
    private boolean passed;
    /** The exact expression string that was evaluated at attestation time. */
    private String expressionEvaluated;
    /** Snapshot of all input values used during evaluation (for reproducibility). */
    private Map<String, Object> inputValues;
    /** SHA-256 of the input data snapshot. */
    private String inputHash;
    /** SHA-256 of the step output data (written after step completion). */
    private String outputHash;
    /** "system" for automated evaluation, or the person ID who provided a manual override. */
    private String evaluatedBy;
    /** Documented justification when a human overrides a failed control. */
    private String overrideReason;
    /** Second approver's ID for dual-approval overrides. */
    private String overrideApprovedBy;
}
