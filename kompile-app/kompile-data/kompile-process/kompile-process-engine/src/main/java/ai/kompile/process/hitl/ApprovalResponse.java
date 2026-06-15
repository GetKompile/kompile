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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * A human's response to an {@link ApprovalRequest}.
 * Supports full approve, partial approve, reject, escalate, delegate, and request-info actions.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ApprovalResponse {

    private String requestId;
    private String respondedBy;
    private Instant respondedAt;
    private ApprovalAction action;
    /** Per-item decisions used when the action is partial approval. */
    private List<ItemDecision> itemDecisions;
    /** Field-level edits applied when the action is {@link ApprovalAction#APPROVE_WITH_EDITS}. */
    private List<FieldEdit> edits;
    /** Risk assumptions tagged onto individual line items during review. */
    private List<Assumption> assumptions;
    /** Ranges or sheets explicitly excluded from processing. */
    private List<CellExclusion> exclusions;
    private String comment;
    /** Target person/role identifier for {@link ApprovalAction#DELEGATE} action. */
    private String delegateTo;
    /** Hash of the evidence snapshot that was reviewed — used for immutability audit. */
    private String evidenceHash;
}
