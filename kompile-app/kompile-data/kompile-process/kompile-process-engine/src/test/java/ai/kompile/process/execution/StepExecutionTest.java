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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StepExecution}.
 */
class StepExecutionTest {

    @Test
    void noArgConstructor_createsEmptyInstance() {
        StepExecution step = new StepExecution();
        assertThat(step.getStepId()).isNull();
        assertThat(step.getStatus()).isNull();
        assertThat(step.getError()).isNull();
    }

    @Test
    void builder_setsBasicFields() {
        Instant now = Instant.now();
        StepExecution step = StepExecution.builder()
                .stepId("step-01")
                .stepName("Load Data")
                .status(StepExecutionStatus.COMPLETED)
                .startedAt(now)
                .executedBy("agent-1")
                .build();

        assertThat(step.getStepId()).isEqualTo("step-01");
        assertThat(step.getStepName()).isEqualTo("Load Data");
        assertThat(step.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(step.getStartedAt()).isEqualTo(now);
        assertThat(step.getExecutedBy()).isEqualTo("agent-1");
    }

    @Test
    void builder_withInputsAndOutputs_setsMapFields() {
        Map<String, Object> inputs = Map.of("file", "data.csv");
        Map<String, Object> outputs = Map.of("rows", 1000);

        StepExecution step = StepExecution.builder()
                .stepId("step-02")
                .inputs(inputs)
                .outputs(outputs)
                .build();

        assertThat(step.getInputs()).containsEntry("file", "data.csv");
        assertThat(step.getOutputs()).containsEntry("rows", 1000);
    }

    @Test
    void builder_withHashes_setsHashFields() {
        StepExecution step = StepExecution.builder()
                .stepId("step-03")
                .inputHash("sha256:abc123")
                .outputHash("sha256:def456")
                .build();

        assertThat(step.getInputHash()).isEqualTo("sha256:abc123");
        assertThat(step.getOutputHash()).isEqualTo("sha256:def456");
    }

    @Test
    void builder_withEvidence_setsListField() {
        StepExecution step = StepExecution.builder()
                .stepId("step-04")
                .evidenceReliedOn(List.of("doc-1", "doc-2"))
                .graphNodeIds(List.of("node-a"))
                .build();

        assertThat(step.getEvidenceReliedOn()).containsExactly("doc-1", "doc-2");
        assertThat(step.getGraphNodeIds()).containsExactly("node-a");
    }

    @Test
    void builder_withError_setsErrorField() {
        StepExecution step = StepExecution.builder()
                .stepId("step-fail")
                .status(StepExecutionStatus.FAILED)
                .error("NullPointerException in validator")
                .build();

        assertThat(step.getStatus()).isEqualTo(StepExecutionStatus.FAILED);
        assertThat(step.getError()).isEqualTo("NullPointerException in validator");
    }

    @Test
    void stepExecutionStatus_allValuesExist() {
        assertThat(StepExecutionStatus.values()).containsExactlyInAnyOrder(
                StepExecutionStatus.PENDING,
                StepExecutionStatus.RUNNING,
                StepExecutionStatus.AWAITING_APPROVAL,
                StepExecutionStatus.COMPLETED,
                StepExecutionStatus.FAILED,
                StepExecutionStatus.SKIPPED
        );
    }

    @Test
    void equalsAndHashCode_symmetry() {
        StepExecution a = StepExecution.builder().stepId("s1").stepName("name").build();
        StepExecution b = StepExecution.builder().stepId("s1").stepName("name").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_containsClassName() {
        StepExecution step = StepExecution.builder().stepId("x").build();
        assertThat(step.toString()).contains("StepExecution");
    }
}
