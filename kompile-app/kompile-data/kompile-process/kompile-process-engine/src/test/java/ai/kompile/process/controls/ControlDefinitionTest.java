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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ControlDefinition}.
 */
class ControlDefinitionTest {

    @Test
    void noArgConstructor_createsEmptyInstance() {
        ControlDefinition cd = new ControlDefinition();
        assertThat(cd.getId()).isNull();
        assertThat(cd.getName()).isNull();
        assertThat(cd.getGateType()).isNull();
        assertThat(cd.getSeverity()).isNull();
    }

    @Test
    void builder_setsAllStringFields() {
        ControlDefinition cd = ControlDefinition.builder()
                .id("C-01")
                .name("Trial Balance Ties")
                .description("Debit total must equal credit total")
                .expression("TB.debit_total == TB.credit_total")
                .triggerAfterStep("step-trial-balance")
                .regulatoryReference("ICFR C-04")
                .build();

        assertThat(cd.getId()).isEqualTo("C-01");
        assertThat(cd.getName()).isEqualTo("Trial Balance Ties");
        assertThat(cd.getDescription()).isEqualTo("Debit total must equal credit total");
        assertThat(cd.getExpression()).isEqualTo("TB.debit_total == TB.credit_total");
        assertThat(cd.getTriggerAfterStep()).isEqualTo("step-trial-balance");
        assertThat(cd.getRegulatoryReference()).isEqualTo("ICFR C-04");
    }

    @Test
    void builder_setsGateTypeAndSeverity() {
        ControlDefinition cd = ControlDefinition.builder()
                .id("C-02")
                .gateType(ControlGateType.HARD)
                .severity(ControlSeverity.CRITICAL)
                .build();

        assertThat(cd.getGateType()).isEqualTo(ControlGateType.HARD);
        assertThat(cd.getSeverity()).isEqualTo(ControlSeverity.CRITICAL);
    }

    @Test
    void builder_withInputKeys_setsListField() {
        ControlDefinition cd = ControlDefinition.builder()
                .id("C-03")
                .inputKeys(List.of("TB.debit_total", "TB.credit_total"))
                .build();

        assertThat(cd.getInputKeys()).containsExactly("TB.debit_total", "TB.credit_total");
    }

    @Test
    void builder_withOnFailurePolicy_setsFieldCorrectly() {
        ControlFailurePolicy policy = ControlFailurePolicy.builder()
                .action(ControlFailureAction.HALT)
                .escalateTo("cfo@company.com")
                .requireDualApprovalToOverride(true)
                .remediationInstructions("Contact CFO immediately")
                .build();

        ControlDefinition cd = ControlDefinition.builder()
                .id("C-04")
                .onFailure(policy)
                .build();

        assertThat(cd.getOnFailure()).isNotNull();
        assertThat(cd.getOnFailure().getEscalateTo()).isEqualTo("cfo@company.com");
        assertThat(cd.getOnFailure().isRequireDualApprovalToOverride()).isTrue();
    }

    @Test
    void controlGateType_allValuesExist() {
        assertThat(ControlGateType.values()).containsExactlyInAnyOrder(
                ControlGateType.HARD,
                ControlGateType.SOFT
        );
    }

    @Test
    void controlSeverity_allValuesExist() {
        assertThat(ControlSeverity.values()).containsExactlyInAnyOrder(
                ControlSeverity.LOW,
                ControlSeverity.MEDIUM,
                ControlSeverity.HIGH,
                ControlSeverity.CRITICAL
        );
    }

    @Test
    void equalsAndHashCode_symmetry() {
        ControlDefinition a = ControlDefinition.builder().id("C-05").name("Test").build();
        ControlDefinition b = ControlDefinition.builder().id("C-05").name("Test").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_containsClassName() {
        ControlDefinition cd = ControlDefinition.builder().id("C-06").build();
        assertThat(cd.toString()).contains("ControlDefinition");
    }
}
