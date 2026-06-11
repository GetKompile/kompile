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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApprovalRequest}.
 */
class ApprovalRequestTest {

    @Test
    void noArgConstructor_createsEmptyInstance() {
        ApprovalRequest req = new ApprovalRequest();
        assertThat(req.getId()).isNull();
        assertThat(req.getStatus()).isNull();
        assertThat(req.getItems()).isNull();
    }

    @Test
    void builder_setsAllBasicFields() {
        Instant created = Instant.now();
        Instant sla = created.plusSeconds(86400);

        ApprovalRequest req = ApprovalRequest.builder()
                .id("req-001")
                .workflowRunId("wf-001")
                .stepId("step-review")
                .stepName("CFO Review")
                .status(ApprovalRequestStatus.PENDING)
                .createdAt(created)
                .slaDeadline(sla)
                .assignedTo("user-cfo")
                .evidenceHashAtCreation("sha256:abc123")
                .build();

        assertThat(req.getId()).isEqualTo("req-001");
        assertThat(req.getWorkflowRunId()).isEqualTo("wf-001");
        assertThat(req.getStepId()).isEqualTo("step-review");
        assertThat(req.getStepName()).isEqualTo("CFO Review");
        assertThat(req.getStatus()).isEqualTo(ApprovalRequestStatus.PENDING);
        assertThat(req.getCreatedAt()).isEqualTo(created);
        assertThat(req.getSlaDeadline()).isEqualTo(sla);
        assertThat(req.getAssignedTo()).isEqualTo("user-cfo");
        assertThat(req.getEvidenceHashAtCreation()).isEqualTo("sha256:abc123");
    }

    @Test
    void builder_withContext_preservesMap() {
        ApprovalRequest req = ApprovalRequest.builder()
                .id("req-002")
                .context(Map.of("totalExceptions", 5, "region", "EMEA"))
                .build();

        assertThat(req.getContext()).containsEntry("totalExceptions", 5);
        assertThat(req.getContext()).containsEntry("region", "EMEA");
    }

    @Test
    void approvalRequestStatus_allValuesExist() {
        assertThat(ApprovalRequestStatus.values()).containsExactlyInAnyOrder(
                ApprovalRequestStatus.PENDING,
                ApprovalRequestStatus.APPROVED,
                ApprovalRequestStatus.REJECTED,
                ApprovalRequestStatus.PARTIALLY_APPROVED,
                ApprovalRequestStatus.ESCALATED,
                ApprovalRequestStatus.DELEGATED,
                ApprovalRequestStatus.EXPIRED
        );
    }

    @Test
    void equalsAndHashCode_symmetry() {
        Instant t = Instant.parse("2026-03-01T08:00:00Z");
        ApprovalRequest a = ApprovalRequest.builder().id("req-x").createdAt(t).build();
        ApprovalRequest b = ApprovalRequest.builder().id("req-x").createdAt(t).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_containsClassName() {
        ApprovalRequest req = ApprovalRequest.builder().id("req-z").build();
        assertThat(req.toString()).contains("ApprovalRequest");
    }
}
