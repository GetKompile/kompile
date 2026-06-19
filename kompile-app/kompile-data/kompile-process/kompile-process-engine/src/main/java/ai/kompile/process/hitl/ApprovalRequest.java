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

package ai.kompile.process.hitl;

import ai.kompile.process.ontology.ProvenanceCitation;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A pending human approval request. Created when a workflow step pauses for HITL.
 * This is the generalized version of the exception queue in the FP&amp;A dashboard.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApprovalRequest {

    private String id;
    private String workflowRunId;
    private String stepId;
    private String stepName;
    private ApprovalRequestStatus status;
    private Instant createdAt;
    private Instant slaDeadline;
    private String assignedTo;
    /** Individual items the approver must accept or reject. */
    private List<ApprovalItem> items;
    /** Contextual data visible to the approver in the UI. */
    private Map<String, Object> context;
    private List<ProvenanceCitation> evidenceReliedOn;
    /** Immutable SHA-256 snapshot of evidence at the time this request was created. */
    private String evidenceHashAtCreation;
}
