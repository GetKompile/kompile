package ai.kompile.e2e;

import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.OrchestratorStatus;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.task.TaskStatus;
import ai.kompile.orchestrator.model.task.TaskType;
import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStatus;
import ai.kompile.orchestrator.model.workflow.WorkflowStep;
import ai.kompile.orchestrator.model.workflow.WorkflowStepStatus;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for orchestrator model objects: OrchestratorInstance, TaskInstance,
 * Workflow, WorkflowStep, and their status enums / state transitions.
 */
@Tag("e2e")
@DisplayName("Orchestrator Models")
class OrchestratorInstanceTest {

    // ══════════ OrchestratorInstance ══════════

    @Nested
    @DisplayName("OrchestratorInstance")
    class OrchestratorInstanceTests {

        @Test
        @DisplayName("Default status is CREATED")
        void testDefaultStatus() {
            OrchestratorInstance instance = OrchestratorInstance.builder()
                    .instanceId("orch-1")
                    .name("Test Orchestrator")
                    .build();

            assertEquals(OrchestratorStatus.CREATED, instance.getStatus());
            assertNotNull(instance.getCreatedAt());
        }

        @Test
        @DisplayName("isActive returns true for RUNNING and PAUSED")
        void testIsActive() {
            OrchestratorInstance instance = OrchestratorInstance.builder()
                    .instanceId("orch-1")
                    .name("Test")
                    .status(OrchestratorStatus.RUNNING)
                    .build();

            assertTrue(instance.isActive());

            instance.setStatus(OrchestratorStatus.PAUSED);
            assertTrue(instance.isActive());

            instance.setStatus(OrchestratorStatus.COMPLETED);
            assertFalse(instance.isActive());
        }

        @Test
        @DisplayName("isTerminal returns true for COMPLETED, FAILED, CANCELLED")
        void testIsTerminal() {
            OrchestratorInstance instance = OrchestratorInstance.builder()
                    .instanceId("orch-1")
                    .name("Test")
                    .build();

            instance.setStatus(OrchestratorStatus.COMPLETED);
            assertTrue(instance.isTerminal());

            instance.setStatus(OrchestratorStatus.FAILED);
            assertTrue(instance.isTerminal());

            instance.setStatus(OrchestratorStatus.CANCELLED);
            assertTrue(instance.isTerminal());

            instance.setStatus(OrchestratorStatus.RUNNING);
            assertFalse(instance.isTerminal());
        }

        @Test
        @DisplayName("Context update merges values")
        void testContextUpdate() {
            OrchestratorInstance instance = OrchestratorInstance.builder()
                    .instanceId("orch-1")
                    .name("Test")
                    .build();

            instance.updateContext(Map.of("key1", "value1"));
            instance.updateContext(Map.of("key2", "value2"));

            assertEquals("value1", instance.getContext().get("key1"));
            assertEquals("value2", instance.getContext().get("key2"));
        }

        @Test
        @DisplayName("getContextValue returns typed value")
        void testGetContextValue() {
            OrchestratorInstance instance = OrchestratorInstance.builder()
                    .instanceId("orch-1")
                    .name("Test")
                    .build();

            instance.updateContext(Map.of("count", 42, "name", "test"));

            assertEquals(42, instance.getContextValue("count", Integer.class));
            assertEquals("test", instance.getContextValue("name", String.class));
            assertNull(instance.getContextValue("missing", String.class));
        }

        @Test
        @DisplayName("getContextValue with default returns default for missing key")
        void testGetContextValueWithDefault() {
            OrchestratorInstance instance = OrchestratorInstance.builder()
                    .instanceId("orch-1")
                    .name("Test")
                    .build();

            assertEquals("default", instance.getContextValue("missing", "default"));
        }

        @Test
        @DisplayName("Context update on null context initializes map")
        void testContextUpdateNullContext() {
            OrchestratorInstance instance = OrchestratorInstance.builder()
                    .instanceId("orch-1")
                    .name("Test")
                    .build();

            instance.setContext(null);
            instance.updateContext(Map.of("key", "value"));

            assertEquals("value", instance.getContext().get("key"));
        }
    }

    // ══════════ OrchestratorStatus ══════════

    @Nested
    @DisplayName("OrchestratorStatus Transitions")
    class StatusTransitionTests {

        @Test
        @DisplayName("CREATED can transition to INITIALIZING or CANCELLED")
        void testCreatedTransitions() {
            assertTrue(OrchestratorStatus.CREATED.canTransitionTo(OrchestratorStatus.INITIALIZING));
            assertTrue(OrchestratorStatus.CREATED.canTransitionTo(OrchestratorStatus.CANCELLED));
            assertFalse(OrchestratorStatus.CREATED.canTransitionTo(OrchestratorStatus.RUNNING));
            assertFalse(OrchestratorStatus.CREATED.canTransitionTo(OrchestratorStatus.COMPLETED));
        }

        @Test
        @DisplayName("INITIALIZING can transition to RUNNING, FAILED, or CANCELLED")
        void testInitializingTransitions() {
            assertTrue(OrchestratorStatus.INITIALIZING.canTransitionTo(OrchestratorStatus.RUNNING));
            assertTrue(OrchestratorStatus.INITIALIZING.canTransitionTo(OrchestratorStatus.FAILED));
            assertTrue(OrchestratorStatus.INITIALIZING.canTransitionTo(OrchestratorStatus.CANCELLED));
            assertFalse(OrchestratorStatus.INITIALIZING.canTransitionTo(OrchestratorStatus.PAUSED));
        }

        @Test
        @DisplayName("RUNNING can transition to PAUSED, COMPLETED, FAILED, or CANCELLED")
        void testRunningTransitions() {
            assertTrue(OrchestratorStatus.RUNNING.canTransitionTo(OrchestratorStatus.PAUSED));
            assertTrue(OrchestratorStatus.RUNNING.canTransitionTo(OrchestratorStatus.COMPLETED));
            assertTrue(OrchestratorStatus.RUNNING.canTransitionTo(OrchestratorStatus.FAILED));
            assertTrue(OrchestratorStatus.RUNNING.canTransitionTo(OrchestratorStatus.CANCELLED));
        }

        @Test
        @DisplayName("PAUSED can transition to RUNNING or CANCELLED")
        void testPausedTransitions() {
            assertTrue(OrchestratorStatus.PAUSED.canTransitionTo(OrchestratorStatus.RUNNING));
            assertTrue(OrchestratorStatus.PAUSED.canTransitionTo(OrchestratorStatus.CANCELLED));
            assertFalse(OrchestratorStatus.PAUSED.canTransitionTo(OrchestratorStatus.COMPLETED));
        }

        @Test
        @DisplayName("Terminal states cannot transition")
        void testTerminalStatesCannotTransition() {
            for (OrchestratorStatus terminal : List.of(
                    OrchestratorStatus.COMPLETED,
                    OrchestratorStatus.FAILED,
                    OrchestratorStatus.CANCELLED)) {
                for (OrchestratorStatus target : OrchestratorStatus.values()) {
                    assertFalse(terminal.canTransitionTo(target),
                            terminal + " should not transition to " + target);
                }
            }
        }

        @Test
        @DisplayName("isActive returns true for active states")
        void testIsActive() {
            assertTrue(OrchestratorStatus.RUNNING.isActive());
            assertTrue(OrchestratorStatus.PAUSED.isActive());
            assertTrue(OrchestratorStatus.INITIALIZING.isActive());
            assertTrue(OrchestratorStatus.RECOVERING.isActive());
            assertFalse(OrchestratorStatus.CREATED.isActive());
            assertFalse(OrchestratorStatus.COMPLETED.isActive());
        }
    }

    // ══════════ TaskInstance ══════════

    @Nested
    @DisplayName("TaskInstance")
    class TaskInstanceTests {

        @Test
        @DisplayName("Default status is PENDING")
        void testDefaultStatus() {
            TaskInstance task = TaskInstance.builder()
                    .name("test-task")
                    .build();

            assertEquals(TaskStatus.PENDING, task.getStatus());
            assertEquals(TaskType.SHELL, task.getTaskType());
        }

        @Test
        @DisplayName("markRunning sets status and start time")
        void testMarkRunning() {
            TaskInstance task = TaskInstance.builder().name("task").build();
            task.markRunning();

            assertEquals(TaskStatus.RUNNING, task.getStatus());
            assertNotNull(task.getStartTime());
            assertTrue(task.isRunning());
        }

        @Test
        @DisplayName("markSuccess sets output and exit code")
        void testMarkSuccess() {
            TaskInstance task = TaskInstance.builder().name("task").build();
            task.markRunning();
            task.markSuccess("output text", 0);

            assertTrue(task.isSuccess());
            assertTrue(task.isComplete());
            assertEquals("output text", task.getOutput());
            assertEquals(0, task.getExitCode());
            assertNotNull(task.getEndTime());
        }

        @Test
        @DisplayName("markFailed sets error message")
        void testMarkFailed() {
            TaskInstance task = TaskInstance.builder().name("task").build();
            task.markRunning();
            task.markFailed("Something went wrong");

            assertTrue(task.isFailed());
            assertTrue(task.isComplete());
            assertEquals("Something went wrong", task.getErrorMessage());
        }

        @Test
        @DisplayName("markFailed with output sets output, exit code, and error")
        void testMarkFailedWithOutput() {
            TaskInstance task = TaskInstance.builder().name("task").build();
            task.markRunning();
            task.markFailed("error output", 1, "Non-zero exit");

            assertTrue(task.isFailed());
            assertEquals("error output", task.getOutput());
            assertEquals(1, task.getExitCode());
            assertEquals("Non-zero exit", task.getErrorMessage());
        }

        @Test
        @DisplayName("markTimeout sets timeout message")
        void testMarkTimeout() {
            TaskInstance task = TaskInstance.builder()
                    .name("task")
                    .timeoutSeconds(60L)
                    .build();
            task.markTimeout();

            assertEquals(TaskStatus.TIMEOUT, task.getStatus());
            assertTrue(task.isFailed());
            assertTrue(task.getErrorMessage().contains("60 seconds"));
        }

        @Test
        @DisplayName("markCancelled sets status")
        void testMarkCancelled() {
            TaskInstance task = TaskInstance.builder().name("task").build();
            task.markCancelled();

            assertEquals(TaskStatus.CANCELLED, task.getStatus());
            assertNotNull(task.getEndTime());
        }

        @Test
        @DisplayName("appendOutput concatenates with newline")
        void testAppendOutput() {
            TaskInstance task = TaskInstance.builder().name("task").build();
            task.appendOutput("line 1");
            task.appendOutput("line 2");

            assertEquals("line 1\nline 2", task.getOutput());
        }

        @Test
        @DisplayName("appendOutput to null initializes output")
        void testAppendOutputNull() {
            TaskInstance task = TaskInstance.builder().name("task").build();
            task.appendOutput("first line");

            assertEquals("first line", task.getOutput());
        }

        @Test
        @DisplayName("getTimeout returns Duration")
        void testGetTimeout() {
            TaskInstance task = TaskInstance.builder()
                    .name("task")
                    .timeoutSeconds(120L)
                    .build();

            assertEquals(Duration.ofSeconds(120), task.getTimeout());
        }

        @Test
        @DisplayName("getDuration returns elapsed time")
        void testGetDuration() {
            TaskInstance task = TaskInstance.builder().name("task").build();

            // Before start, duration is zero
            assertEquals(Duration.ZERO, task.getDuration());

            task.setStartTime(LocalDateTime.now().minusSeconds(10));
            task.setEndTime(LocalDateTime.now());

            Duration d = task.getDuration();
            assertTrue(d.getSeconds() >= 9 && d.getSeconds() <= 11);
        }
    }

    // ══════════ TaskStatus ══════════

    @Nested
    @DisplayName("TaskStatus")
    class TaskStatusTests {

        @Test
        @DisplayName("Terminal states are SUCCESS, FAILED, TIMEOUT, CANCELLED")
        void testTerminalStates() {
            assertTrue(TaskStatus.SUCCESS.isTerminal());
            assertTrue(TaskStatus.FAILED.isTerminal());
            assertTrue(TaskStatus.TIMEOUT.isTerminal());
            assertTrue(TaskStatus.CANCELLED.isTerminal());
            assertFalse(TaskStatus.PENDING.isTerminal());
            assertFalse(TaskStatus.RUNNING.isTerminal());
        }

        @Test
        @DisplayName("isRunning for PENDING and RUNNING")
        void testIsRunning() {
            assertTrue(TaskStatus.PENDING.isRunning());
            assertTrue(TaskStatus.RUNNING.isRunning());
            assertFalse(TaskStatus.SUCCESS.isRunning());
        }

        @Test
        @DisplayName("isFailure for FAILED and TIMEOUT")
        void testIsFailure() {
            assertTrue(TaskStatus.FAILED.isFailure());
            assertTrue(TaskStatus.TIMEOUT.isFailure());
            assertFalse(TaskStatus.SUCCESS.isFailure());
            assertFalse(TaskStatus.CANCELLED.isFailure());
        }
    }

    // ══════════ Workflow ══════════

    @Nested
    @DisplayName("Workflow")
    class WorkflowTests {

        @Test
        @DisplayName("Default status is IN_PROGRESS")
        void testDefaultStatus() {
            Workflow workflow = Workflow.builder()
                    .name("test-workflow")
                    .build();

            assertEquals(WorkflowStatus.IN_PROGRESS, workflow.getStatus());
            assertEquals(0, workflow.getCurrentStepNumber());
            assertEquals(0, workflow.getCompletedSteps());
        }

        @Test
        @DisplayName("addStep creates step with correct number")
        void testAddStep() {
            Workflow workflow = Workflow.builder()
                    .name("test-workflow")
                    .build();

            WorkflowStep step0 = workflow.addStep("First step");
            WorkflowStep step1 = workflow.addStep("Second step");

            assertEquals(0, step0.getStepNumber());
            assertEquals(1, step1.getStepNumber());
            assertEquals(2, workflow.getSteps().size());
            assertEquals(WorkflowStepStatus.PENDING, step0.getStatus());
        }

        @Test
        @DisplayName("getCurrentStep returns step matching currentStepNumber")
        void testGetCurrentStep() {
            Workflow workflow = Workflow.builder()
                    .name("test-workflow")
                    .build();

            workflow.addStep("Step 0");
            workflow.addStep("Step 1");
            workflow.setCurrentStepNumber(1);

            WorkflowStep current = workflow.getCurrentStep();
            assertNotNull(current);
            assertEquals(1, current.getStepNumber());
            assertEquals("Step 1", current.getDescription());
        }

        @Test
        @DisplayName("getStep by number returns correct step")
        void testGetStep() {
            Workflow workflow = Workflow.builder()
                    .name("test-workflow")
                    .build();

            workflow.addStep("A");
            workflow.addStep("B");

            assertEquals("B", workflow.getStep(1).getDescription());
            assertNull(workflow.getStep(99));
        }

        @Test
        @DisplayName("advanceStep increments current step number")
        void testAdvanceStep() {
            Workflow workflow = Workflow.builder()
                    .name("test-workflow")
                    .build();

            assertEquals(0, workflow.getCurrentStepNumber());
            workflow.advanceStep();
            assertEquals(1, workflow.getCurrentStepNumber());
            workflow.advanceStep();
            assertEquals(2, workflow.getCurrentStepNumber());
        }

        @Test
        @DisplayName("incrementCompletedSteps increments counter")
        void testIncrementCompletedSteps() {
            Workflow workflow = Workflow.builder()
                    .name("test-workflow")
                    .build();

            workflow.incrementCompletedSteps();
            workflow.incrementCompletedSteps();
            assertEquals(2, workflow.getCompletedSteps());
        }

        @Test
        @DisplayName("hasReachedMaxSteps returns true when limit reached")
        void testMaxSteps() {
            Workflow workflow = Workflow.builder()
                    .name("test-workflow")
                    .maxSteps(3)
                    .build();

            assertFalse(workflow.hasReachedMaxSteps());

            workflow.setCompletedSteps(3);
            assertTrue(workflow.hasReachedMaxSteps());
        }

        @Test
        @DisplayName("markCompleted sets status and summary")
        void testMarkCompleted() {
            Workflow workflow = Workflow.builder()
                    .name("test-workflow")
                    .build();

            workflow.markCompleted("All done");

            assertEquals(WorkflowStatus.COMPLETED, workflow.getStatus());
            assertEquals("All done", workflow.getSummary());
            assertNotNull(workflow.getEndTime());
            assertFalse(workflow.isActive());
        }

        @Test
        @DisplayName("markFailed sets status and error")
        void testMarkFailed() {
            Workflow workflow = Workflow.builder()
                    .name("test-workflow")
                    .build();

            workflow.markFailed("LLM timeout");

            assertEquals(WorkflowStatus.FAILED, workflow.getStatus());
            assertEquals("LLM timeout", workflow.getErrorMessage());
        }

        @Test
        @DisplayName("markCancelled sets status")
        void testMarkCancelled() {
            Workflow workflow = Workflow.builder()
                    .name("test-workflow")
                    .build();

            workflow.markCancelled();

            assertEquals(WorkflowStatus.CANCELLED, workflow.getStatus());
            assertFalse(workflow.isActive());
        }

        @Test
        @DisplayName("isActive for active workflow statuses")
        void testIsActive() {
            Workflow workflow = Workflow.builder().name("test").build();

            workflow.setStatus(WorkflowStatus.IN_PROGRESS);
            assertTrue(workflow.isActive());

            workflow.setStatus(WorkflowStatus.WAITING_APPROVAL);
            assertTrue(workflow.isActive());

            workflow.setStatus(WorkflowStatus.COMPLETED);
            assertFalse(workflow.isActive());
        }
    }

    // ══════════ WorkflowStep ══════════

    @Nested
    @DisplayName("WorkflowStep")
    class WorkflowStepTests {

        @Test
        @DisplayName("Default status is PENDING")
        void testDefaultStatus() {
            WorkflowStep step = WorkflowStep.builder()
                    .stepNumber(0)
                    .description("Test step")
                    .build();

            assertEquals(WorkflowStepStatus.PENDING, step.getStatus());
            assertFalse(step.isUserApproved());
        }

        @Test
        @DisplayName("markRunning sets status and start time")
        void testMarkRunning() {
            WorkflowStep step = WorkflowStep.builder().stepNumber(0).build();
            step.markRunning();

            assertEquals(WorkflowStepStatus.RUNNING, step.getStatus());
            assertNotNull(step.getStartTime());
        }

        @Test
        @DisplayName("markFailed sets error and end time")
        void testMarkFailed() {
            WorkflowStep step = WorkflowStep.builder().stepNumber(0).build();
            step.markFailed("Task failed");

            assertEquals(WorkflowStepStatus.FAILED, step.getStatus());
            assertEquals("Task failed", step.getErrorMessage());
            assertNotNull(step.getEndTime());
        }

        @Test
        @DisplayName("markSkipped sets status with reason")
        void testMarkSkipped() {
            WorkflowStep step = WorkflowStep.builder().stepNumber(0).build();
            step.markSkipped("Not needed");

            assertEquals(WorkflowStepStatus.SKIPPED, step.getStatus());
            assertTrue(step.isTerminal());
        }

        @Test
        @DisplayName("markRejected sets status and reason")
        void testMarkRejected() {
            WorkflowStep step = WorkflowStep.builder().stepNumber(0).build();
            step.markRejected("Bad approach");

            assertEquals(WorkflowStepStatus.REJECTED, step.getStatus());
            assertEquals("Bad approach", step.getRejectionReason());
            assertFalse(step.isUserApproved());
        }

        @Test
        @DisplayName("approve changes waiting_approval to pending")
        void testApprove() {
            WorkflowStep step = WorkflowStep.builder()
                    .stepNumber(0)
                    .status(WorkflowStepStatus.WAITING_APPROVAL)
                    .build();

            step.approve();

            assertTrue(step.isUserApproved());
            assertEquals(WorkflowStepStatus.PENDING, step.getStatus());
        }

        @Test
        @DisplayName("needsApproval for WAITING_APPROVAL status")
        void testNeedsApproval() {
            WorkflowStep step = WorkflowStep.builder()
                    .stepNumber(0)
                    .status(WorkflowStepStatus.WAITING_APPROVAL)
                    .build();

            assertTrue(step.needsApproval());
        }

        @Test
        @DisplayName("Terminal statuses are COMPLETED, FAILED, SKIPPED, REJECTED")
        void testTerminalStatuses() {
            assertTrue(WorkflowStepStatus.COMPLETED.isTerminal());
            assertTrue(WorkflowStepStatus.FAILED.isTerminal());
            assertTrue(WorkflowStepStatus.SKIPPED.isTerminal());
            assertTrue(WorkflowStepStatus.REJECTED.isTerminal());
            assertFalse(WorkflowStepStatus.PENDING.isTerminal());
            assertFalse(WorkflowStepStatus.RUNNING.isTerminal());
        }
    }
}
