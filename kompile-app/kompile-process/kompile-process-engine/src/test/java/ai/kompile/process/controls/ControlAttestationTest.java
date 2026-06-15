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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ControlAttestation}.
 */
class ControlAttestationTest {

    @Test
    void noArgConstructor_createsEmptyInstance() {
        ControlAttestation att = new ControlAttestation();
        assertThat(att.getId()).isNull();
        assertThat(att.getControlId()).isNull();
        assertThat(att.isPassed()).isFalse();
    }

    @Test
    void builder_setsAllStringFields() {
        Instant evalTime = Instant.now();
        ControlAttestation att = ControlAttestation.builder()
                .id("att-001")
                .controlId("C-01")
                .workflowRunId("wf-2026-001")
                .stepId("step-trial-balance")
                .evaluatedAt(evalTime)
                .passed(true)
                .expressionEvaluated("TB.debit_total == TB.credit_total")
                .evaluatedBy("system")
                .build();

        assertThat(att.getId()).isEqualTo("att-001");
        assertThat(att.getControlId()).isEqualTo("C-01");
        assertThat(att.getWorkflowRunId()).isEqualTo("wf-2026-001");
        assertThat(att.getStepId()).isEqualTo("step-trial-balance");
        assertThat(att.getEvaluatedAt()).isEqualTo(evalTime);
        assertThat(att.isPassed()).isTrue();
        assertThat(att.getExpressionEvaluated()).isEqualTo("TB.debit_total == TB.credit_total");
        assertThat(att.getEvaluatedBy()).isEqualTo("system");
    }

    @Test
    void builder_withInputValues_preservesMap() {
        Map<String, Object> inputs = Map.of("TB.debit_total", 100000.0, "TB.credit_total", 100000.0);

        ControlAttestation att = ControlAttestation.builder()
                .id("att-002")
                .inputValues(inputs)
                .build();

        assertThat(att.getInputValues()).containsEntry("TB.debit_total", 100000.0);
        assertThat(att.getInputValues()).containsEntry("TB.credit_total", 100000.0);
    }

    @Test
    void builder_withHashes_setsHashFields() {
        ControlAttestation att = ControlAttestation.builder()
                .id("att-003")
                .inputHash("sha256:abc")
                .outputHash("sha256:def")
                .build();

        assertThat(att.getInputHash()).isEqualTo("sha256:abc");
        assertThat(att.getOutputHash()).isEqualTo("sha256:def");
    }

    @Test
    void builder_withOverride_setsOverrideFields() {
        ControlAttestation att = ControlAttestation.builder()
                .id("att-004")
                .passed(false)
                .overrideReason("Approved by CFO due to year-end exception")
                .overrideApprovedBy("user-cfo-delegate")
                .build();

        assertThat(att.isPassed()).isFalse();
        assertThat(att.getOverrideReason()).isEqualTo("Approved by CFO due to year-end exception");
        assertThat(att.getOverrideApprovedBy()).isEqualTo("user-cfo-delegate");
    }

    @Test
    void passedDefaultsToFalse_inNoArgConstructor() {
        ControlAttestation att = new ControlAttestation();
        assertThat(att.isPassed()).isFalse();
    }

    @Test
    void equalsAndHashCode_symmetry() {
        Instant t = Instant.parse("2026-01-15T10:00:00Z");
        ControlAttestation a = ControlAttestation.builder()
                .id("att-x").controlId("C-01").passed(true).evaluatedAt(t).build();
        ControlAttestation b = ControlAttestation.builder()
                .id("att-x").controlId("C-01").passed(true).evaluatedAt(t).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_containsClassName() {
        ControlAttestation att = ControlAttestation.builder().id("att-y").build();
        assertThat(att.toString()).contains("ControlAttestation");
    }
}
