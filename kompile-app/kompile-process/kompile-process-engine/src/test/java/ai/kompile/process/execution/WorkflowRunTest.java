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
 * Unit tests for {@link WorkflowRun}.
 */
class WorkflowRunTest {

    @Test
    void noArgConstructor_createsInstanceWithNullFields() {
        WorkflowRun run = new WorkflowRun();
        assertThat(run.getId()).isNull();
        assertThat(run.getStatus()).isNull();
        assertThat(run.getStepExecutions()).isNull();
    }

    @Test
    void builder_setsAllFields() {
        Instant now = Instant.now();
        WorkflowRun run = WorkflowRun.builder()
                .id("wf-001")
                .processDefinitionId("pd-sales")
                .processVersion(2)
                .ontologySnapshotId("snap-42")
                .status(RunStatus.RUNNING)
                .startedAt(now)
                .build();

        assertThat(run.getId()).isEqualTo("wf-001");
        assertThat(run.getProcessDefinitionId()).isEqualTo("pd-sales");
        assertThat(run.getProcessVersion()).isEqualTo(2);
        assertThat(run.getOntologySnapshotId()).isEqualTo("snap-42");
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getStartedAt()).isEqualTo(now);
    }

    @Test
    void builder_withStepExecutions_setsListField() {
        StepExecution step = StepExecution.builder()
                .stepId("step-1")
                .status(StepExecutionStatus.COMPLETED)
                .build();

        WorkflowRun run = WorkflowRun.builder()
                .id("wf-002")
                .stepExecutions(List.of(step))
                .build();

        assertThat(run.getStepExecutions()).hasSize(1);
        assertThat(run.getStepExecutions().get(0).getStepId()).isEqualTo("step-1");
    }

    @Test
    void builder_withRunData_preservesMap() {
        Map<String, Object> data = Map.of("revenue", 100000, "region", "EMEA");

        WorkflowRun run = WorkflowRun.builder()
                .id("wf-003")
                .runData(data)
                .build();

        assertThat(run.getRunData()).containsEntry("revenue", 100000);
        assertThat(run.getRunData()).containsEntry("region", "EMEA");
    }

    @Test
    void allArgsConstructor_matchesBuilderOutput() {
        Instant start = Instant.now();
        WorkflowRun fromBuilder = WorkflowRun.builder()
                .id("wf-004")
                .processDefinitionId("pd-1")
                .processVersion(1)
                .ontologySnapshotId("snap-1")
                .status(RunStatus.COMPLETED)
                .startedAt(start)
                .completedAt(null)
                .estimatedCompletion(null)
                .stepExecutions(null)
                .pendingApprovals(null)
                .controlResults(null)
                .runData(null)
                .graphNodeIds(null)
                .metrics(null)
                .build();

        WorkflowRun fromAllArgs = new WorkflowRun(
                "wf-004", "pd-1", 1, "snap-1", RunStatus.COMPLETED,
                start, null, null, null, null, null, null, null, null,
                0.0, null, null
        );

        assertThat(fromBuilder).isEqualTo(fromAllArgs);
    }

    @Test
    void runStatus_allValuesExist() {
        assertThat(RunStatus.values()).containsExactlyInAnyOrder(
                RunStatus.RUNNING,
                RunStatus.PAUSED_FOR_APPROVAL,
                RunStatus.PAUSED_FOR_HUMAN,
                RunStatus.COMPLETED,
                RunStatus.FAILED,
                RunStatus.CANCELLED
        );
    }

    @Test
    void setters_updateFields() {
        WorkflowRun run = new WorkflowRun();
        run.setId("wf-updated");
        run.setStatus(RunStatus.FAILED);

        assertThat(run.getId()).isEqualTo("wf-updated");
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void equalsAndHashCode_worksForEqualInstances() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        WorkflowRun a = WorkflowRun.builder().id("x").startedAt(now).status(RunStatus.RUNNING).build();
        WorkflowRun b = WorkflowRun.builder().id("x").startedAt(now).status(RunStatus.RUNNING).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
