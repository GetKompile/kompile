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

import ai.kompile.process.controls.ControlAttestation;
import ai.kompile.process.hitl.ApprovalRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A single execution of a {@link ai.kompile.process.workflow.ProcessDefinition}.
 * Tracks step-by-step progress, paused human gates, and the full audit trail.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowRun {

    /** Unique run identifier, e.g., "wf-2026-05-142". */
    private String id;
    private String processDefinitionId;
    private int processVersion;
    /** ID of the frozen ontology snapshot bound to this run. */
    private String ontologySnapshotId;
    private RunStatus status;
    private Instant startedAt;
    private Instant completedAt;
    private Instant estimatedCompletion;
    private List<StepExecution> stepExecutions;
    /** Approval requests currently waiting for human response. */
    private List<ApprovalRequest> pendingApprovals;
    /** Immutable control attestation records generated during this run. */
    private List<ControlAttestation> controlResults;
    /** Accumulated data state shared across steps (keyed by output key). */
    private Map<String, Object> runData;
    /** Knowledge graph node IDs of all documents/entities involved in this run. */
    private List<String> graphNodeIds;
    /** KPI metrics for this run: cycle time, auto-fix rate, exception count, etc. */
    private Map<String, Object> metrics;
    /** Overall likelihood score (0.0–1.0) computed from causal attribution chains. */
    private double overallLikelihood;
    /** ID of the risk assessment produced for this run (if any). */
    private String riskAssessmentId;
    /** ID of the process suggestion that triggered this run. */
    private String sourceSuggestionId;
}
