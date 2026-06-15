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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApprovalResponse}.
 */
class ApprovalResponseTest {

    @Test
    void noArgConstructor_createsEmptyInstance() {
        ApprovalResponse resp = new ApprovalResponse();
        assertThat(resp.getRequestId()).isNull();
        assertThat(resp.getAction()).isNull();
        assertThat(resp.getComment()).isNull();
    }

    @Test
    void builder_setsAllBasicFields() {
        Instant respondedAt = Instant.now();
        ApprovalResponse resp = ApprovalResponse.builder()
                .requestId("req-001")
                .respondedBy("user-cfo")
                .respondedAt(respondedAt)
                .action(ApprovalAction.APPROVE)
                .comment("Looks good, approved for Q1 close")
                .evidenceHash("sha256:xyz789")
                .build();

        assertThat(resp.getRequestId()).isEqualTo("req-001");
        assertThat(resp.getRespondedBy()).isEqualTo("user-cfo");
        assertThat(resp.getRespondedAt()).isEqualTo(respondedAt);
        assertThat(resp.getAction()).isEqualTo(ApprovalAction.APPROVE);
        assertThat(resp.getComment()).isEqualTo("Looks good, approved for Q1 close");
        assertThat(resp.getEvidenceHash()).isEqualTo("sha256:xyz789");
    }

    @Test
    void builder_withDelegateAction_setsDelegateTo() {
        ApprovalResponse resp = ApprovalResponse.builder()
                .requestId("req-002")
                .action(ApprovalAction.DELEGATE)
                .delegateTo("user-controller")
                .build();

        assertThat(resp.getAction()).isEqualTo(ApprovalAction.DELEGATE);
        assertThat(resp.getDelegateTo()).isEqualTo("user-controller");
    }

    @Test
    void approvalAction_allValuesExist() {
        assertThat(ApprovalAction.values()).containsExactlyInAnyOrder(
                ApprovalAction.APPROVE,
                ApprovalAction.APPROVE_WITH_EDITS,
                ApprovalAction.REJECT,
                ApprovalAction.ESCALATE,
                ApprovalAction.DELEGATE,
                ApprovalAction.REQUEST_INFO
        );
    }

    @Test
    void equalsAndHashCode_symmetry() {
        Instant t = Instant.parse("2026-04-01T09:00:00Z");
        ApprovalResponse a = ApprovalResponse.builder()
                .requestId("req-y").respondedAt(t).action(ApprovalAction.REJECT).build();
        ApprovalResponse b = ApprovalResponse.builder()
                .requestId("req-y").respondedAt(t).action(ApprovalAction.REJECT).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_containsClassName() {
        ApprovalResponse resp = ApprovalResponse.builder().requestId("req-z").build();
        assertThat(resp.toString()).contains("ApprovalResponse");
    }
}
