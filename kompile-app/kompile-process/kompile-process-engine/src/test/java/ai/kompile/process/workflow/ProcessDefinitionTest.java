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

package ai.kompile.process.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProcessDefinition} and its inner class
 * {@link ProcessDefinition.ControlDefinitionRef}.
 */
class ProcessDefinitionTest {

    @Test
    void noArgConstructor_createsEmptyInstance() {
        ProcessDefinition pd = new ProcessDefinition();
        assertThat(pd.getId()).isNull();
        assertThat(pd.getName()).isNull();
        assertThat(pd.getVersion()).isZero();
        assertThat(pd.getStatus()).isNull();
    }

    @Test
    void builder_setsAllBasicFields() {
        Instant approvedAt = Instant.now();
        ProcessDefinition pd = ProcessDefinition.builder()
                .id("pd-001")
                .name("Annual Close")
                .version(3)
                .ontologySchemaId("schema-v2")
                .ontologyVersion(2)
                .status(ProcessStatus.LIVE)
                .approvedBy("user-cfo")
                .approvedAt(approvedAt)
                .build();

        assertThat(pd.getId()).isEqualTo("pd-001");
        assertThat(pd.getName()).isEqualTo("Annual Close");
        assertThat(pd.getVersion()).isEqualTo(3);
        assertThat(pd.getOntologySchemaId()).isEqualTo("schema-v2");
        assertThat(pd.getOntologyVersion()).isEqualTo(2);
        assertThat(pd.getStatus()).isEqualTo(ProcessStatus.LIVE);
        assertThat(pd.getApprovedBy()).isEqualTo("user-cfo");
        assertThat(pd.getApprovedAt()).isEqualTo(approvedAt);
    }

    @Test
    void builder_withAgentSpecs_setsListField() {
        ProcessDefinition pd = ProcessDefinition.builder()
                .id("pd-002")
                .agentSpecs(List.of("agent-data-ingest", "agent-validator"))
                .build();

        assertThat(pd.getAgentSpecs()).containsExactly("agent-data-ingest", "agent-validator");
    }

    @Test
    void builder_withControls_setsControlRefs() {
        ProcessDefinition.ControlDefinitionRef ref = ProcessDefinition.ControlDefinitionRef.builder()
                .controlId("C-01")
                .triggerAfterStep("step-trial-balance")
                .build();

        ProcessDefinition pd = ProcessDefinition.builder()
                .id("pd-003")
                .controls(List.of(ref))
                .build();

        assertThat(pd.getControls()).hasSize(1);
        assertThat(pd.getControls().get(0).getControlId()).isEqualTo("C-01");
        assertThat(pd.getControls().get(0).getTriggerAfterStep()).isEqualTo("step-trial-balance");
    }

    @Test
    void builder_withMetadata_preservesMap() {
        ProcessDefinition pd = ProcessDefinition.builder()
                .id("pd-004")
                .metadata(Map.of("domain", "FP&A", "region", "APAC"))
                .build();

        assertThat(pd.getMetadata()).containsEntry("domain", "FP&A");
        assertThat(pd.getMetadata()).containsEntry("region", "APAC");
    }

    @Test
    void controlDefinitionRef_noArgConstructor_createsEmptyInstance() {
        ProcessDefinition.ControlDefinitionRef ref = new ProcessDefinition.ControlDefinitionRef();
        assertThat(ref.getControlId()).isNull();
        assertThat(ref.getTriggerAfterStep()).isNull();
    }

    @Test
    void controlDefinitionRef_builder_setsFields() {
        ProcessDefinition.ControlDefinitionRef ref = ProcessDefinition.ControlDefinitionRef.builder()
                .controlId("C-02")
                .triggerAfterStep("step-recon")
                .build();

        assertThat(ref.getControlId()).isEqualTo("C-02");
        assertThat(ref.getTriggerAfterStep()).isEqualTo("step-recon");
    }

    @Test
    void controlDefinitionRef_equalsAndHashCode() {
        ProcessDefinition.ControlDefinitionRef a = ProcessDefinition.ControlDefinitionRef.builder()
                .controlId("C-03").triggerAfterStep("s1").build();
        ProcessDefinition.ControlDefinitionRef b = ProcessDefinition.ControlDefinitionRef.builder()
                .controlId("C-03").triggerAfterStep("s1").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equalsAndHashCode_symmetry() {
        ProcessDefinition a = ProcessDefinition.builder().id("pd-x").name("Test").version(1).build();
        ProcessDefinition b = ProcessDefinition.builder().id("pd-x").name("Test").version(1).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_containsClassName() {
        ProcessDefinition pd = ProcessDefinition.builder().id("pd-y").build();
        assertThat(pd.toString()).contains("ProcessDefinition");
    }
}
