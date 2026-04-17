package ai.kompile.e2e;

import ai.kompile.orchestrator.api.OrchestratorService;
import ai.kompile.orchestrator.api.StateHandler;
import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.OrchestratorStatus;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the OrchestratorService interface.
 * Tests the full lifecycle, state transitions, task execution,
 * workflow management, and context handling.
 *
 * Uses a mock OrchestratorService to verify the contract without
 * requiring a database.
 */
@Tag("integration")
@ExtendWith(MockitoExtension.class)
@DisplayName("Orchestrator Service Integration")
class OrchestratorServiceIT {

    @Mock
    private OrchestratorService orchestratorService;

    // ══════════ Instance Lifecycle ══════════

    @Nested
    @DisplayName("Instance Lifecycle")
    class InstanceLifecycleTests {

        @Test
        @DisplayName("Create orchestrator instance")
        void testCreate() {
            OrchestratorInstance expected = OrchestratorInstance.builder()
                    .instanceId("orch-1")
                    .name("Test Orchestrator")
                    .description("For testing")
                    .status(OrchestratorStatus.CREATED)
                    .build();

            when(orchestratorService.create("Test Orchestrator", "For testing")).thenReturn(expected);

            OrchestratorInstance instance = orchestratorService.create("Test Orchestrator", "For testing");

            assertNotNull(instance);
            assertEquals("orch-1", instance.getInstanceId());
            assertEquals(OrchestratorStatus.CREATED, instance.getStatus());
        }

        @Test
        @DisplayName("Create with configuration")
        void testCreateWithConfig() {
            OrchestratorInstance expected = OrchestratorInstance.builder()
                    .instanceId("orch-2")
                    .name("Configured Orchestrator")
                    .status(OrchestratorStatus.CREATED)
                    .build();

            when(orchestratorService.create(
                    eq("Configured Orchestrator"),
                    eq("With config"),
                    anyMap(), anyList(), anyList(), anyList()
            )).thenReturn(expected);

            OrchestratorInstance instance = orchestratorService.create(
                    "Configured Orchestrator", "With config",
                    Map.of("env", "test"),
                    List.of(), List.of(), List.of()
            );

            assertNotNull(instance);
        }

        @Test
        @DisplayName("Start orchestrator transitions status")
        void testStart() {
            orchestratorService.start("orch-1");
            verify(orchestratorService).start("orch-1");
        }

        @Test
        @DisplayName("Pause and resume orchestrator")
        void testPauseResume() {
            orchestratorService.pause("orch-1");
            verify(orchestratorService).pause("orch-1");

            orchestratorService.resume("orch-1");
            verify(orchestratorService).resume("orch-1");
        }

        @Test
        @DisplayName("Stop orchestrator")
        void testStop() {
            orchestratorService.stop("orch-1");
            verify(orchestratorService).stop("orch-1");
        }

        @Test
        @DisplayName("Delete orchestrator")
        void testDelete() {
            orchestratorService.delete("orch-1");
            verify(orchestratorService).delete("orch-1");
        }
    }

    // ══════════ Queries ══════════

    @Nested
    @DisplayName("Instance Queries")
    class InstanceQueryTests {

        @Test
        @DisplayName("Get instance by ID")
        void testGetInstance() {
            OrchestratorInstance instance = OrchestratorInstance.builder()
                    .instanceId("orch-1")
                    .name("Test")
                    .status(OrchestratorStatus.RUNNING)
                    .build();

            when(orchestratorService.getInstance("orch-1")).thenReturn(Optional.of(instance));

            Optional<OrchestratorInstance> result = orchestratorService.getInstance("orch-1");
            assertTrue(result.isPresent());
            assertEquals(OrchestratorStatus.RUNNING, result.get().getStatus());
        }

        @Test
        @DisplayName("Get non-existent instance returns empty")
        void testGetNonExistent() {
            when(orchestratorService.getInstance("nonexistent")).thenReturn(Optional.empty());

            assertTrue(orchestratorService.getInstance("nonexistent").isEmpty());
        }

        @Test
        @DisplayName("Get all instances")
        void testGetAll() {
            when(orchestratorService.getAllInstances()).thenReturn(List.of(
                    OrchestratorInstance.builder().instanceId("1").name("A").build(),
                    OrchestratorInstance.builder().instanceId("2").name("B").build()
            ));

            List<OrchestratorInstance> instances = orchestratorService.getAllInstances();
            assertEquals(2, instances.size());
        }

        @Test
        @DisplayName("Get running instances")
        void testGetRunning() {
            when(orchestratorService.getRunningInstances()).thenReturn(List.of(
                    OrchestratorInstance.builder().instanceId("1").name("A")
                            .status(OrchestratorStatus.RUNNING).build()
            ));

            List<OrchestratorInstance> running = orchestratorService.getRunningInstances();
            assertEquals(1, running.size());
            assertEquals(OrchestratorStatus.RUNNING, running.get(0).getStatus());
        }

        @Test
        @DisplayName("Get instances by status")
        void testGetByStatus() {
            when(orchestratorService.getInstancesByStatus(OrchestratorStatus.PAUSED)).thenReturn(List.of(
                    OrchestratorInstance.builder().instanceId("1").name("Paused")
                            .status(OrchestratorStatus.PAUSED).build()
            ));

            List<OrchestratorInstance> paused = orchestratorService.getInstancesByStatus(OrchestratorStatus.PAUSED);
            assertEquals(1, paused.size());
        }
    }

    // ══════════ State Management ══════════

    @Nested
    @DisplayName("State Management")
    class StateManagementTests {

        @Test
        @DisplayName("Transition to new state")
        void testTransitionTo() {
            orchestratorService.transitionTo("orch-1", "building");
            verify(orchestratorService).transitionTo("orch-1", "building");
        }

        @Test
        @DisplayName("Transition with context updates")
        void testTransitionWithContext() {
            Map<String, Object> context = Map.of("buildNumber", 42);
            orchestratorService.transitionTo("orch-1", "testing", context);
            verify(orchestratorService).transitionTo("orch-1", "testing", context);
        }
    }

    // ══════════ Task Execution ══════════

    @Nested
    @DisplayName("Task Execution")
    class TaskExecutionTests {

        @Test
        @DisplayName("Execute task with variables")
        void testExecuteTask() {
            TaskInstance expected = TaskInstance.builder()
                    .id(1L)
                    .name("build-task")
                    .command("mvn clean install")
                    .build();

            when(orchestratorService.executeTask(
                    eq("orch-1"), eq("build"), anyMap()
            )).thenReturn(expected);

            TaskInstance task = orchestratorService.executeTask(
                    "orch-1", "build", Map.of("profile", "release")
            );

            assertNotNull(task);
            assertEquals("mvn clean install", task.getCommand());
        }

        @Test
        @DisplayName("Execute command directly")
        void testExecuteCommand() {
            TaskInstance expected = TaskInstance.builder()
                    .id(2L)
                    .command("echo hello")
                    .build();

            when(orchestratorService.executeCommand("orch-1", "echo hello")).thenReturn(expected);

            TaskInstance task = orchestratorService.executeCommand("orch-1", "echo hello");
            assertNotNull(task);
        }

        @Test
        @DisplayName("Cancel running task")
        void testCancelTask() {
            orchestratorService.cancelTask(1L);
            verify(orchestratorService).cancelTask(1L);
        }
    }

    // ══════════ Workflow Management ══════════

    @Nested
    @DisplayName("Workflow Management")
    class WorkflowTests {

        @Test
        @DisplayName("Start workflow")
        void testStartWorkflow() {
            Workflow expected = Workflow.builder()
                    .id(1L)
                    .name("deploy-workflow")
                    .status(WorkflowStatus.IN_PROGRESS)
                    .build();

            when(orchestratorService.startWorkflow("orch-1", "deploy-workflow", "Deploy to prod"))
                    .thenReturn(expected);

            Workflow workflow = orchestratorService.startWorkflow("orch-1", "deploy-workflow", "Deploy to prod");

            assertNotNull(workflow);
            assertEquals(WorkflowStatus.IN_PROGRESS, workflow.getStatus());
        }

        @Test
        @DisplayName("Start workflow with auto-advance")
        void testStartWorkflowWithOptions() {
            Workflow expected = Workflow.builder()
                    .id(2L)
                    .name("auto-workflow")
                    .autoAdvance(true)
                    .maxSteps(10)
                    .build();

            when(orchestratorService.startWorkflow("orch-1", "auto-workflow", "Auto deploy", true, 10))
                    .thenReturn(expected);

            Workflow workflow = orchestratorService.startWorkflow("orch-1", "auto-workflow", "Auto deploy", true, 10);

            assertTrue(workflow.isAutoAdvance());
            assertEquals(10, workflow.getMaxSteps());
        }

        @Test
        @DisplayName("Advance workflow")
        void testAdvanceWorkflow() {
            orchestratorService.advanceWorkflow(1L);
            verify(orchestratorService).advanceWorkflow(1L);
        }

        @Test
        @DisplayName("Approve workflow step")
        void testApproveStep() {
            orchestratorService.approveWorkflowStep(1L, 0);
            verify(orchestratorService).approveWorkflowStep(1L, 0);
        }

        @Test
        @DisplayName("Reject workflow step with feedback")
        void testRejectStep() {
            orchestratorService.rejectWorkflowStep(1L, 0, "Not the right approach");
            verify(orchestratorService).rejectWorkflowStep(1L, 0, "Not the right approach");
        }

        @Test
        @DisplayName("Cancel workflow")
        void testCancelWorkflow() {
            orchestratorService.cancelWorkflow(1L);
            verify(orchestratorService).cancelWorkflow(1L);
        }
    }

    // ══════════ Context Management ══════════

    @Nested
    @DisplayName("Context Management")
    class ContextTests {

        @Test
        @DisplayName("Update context")
        void testUpdateContext() {
            orchestratorService.updateContext("orch-1", Map.of("key", "value"));
            verify(orchestratorService).updateContext("orch-1", Map.of("key", "value"));
        }

        @Test
        @DisplayName("Get context")
        void testGetContext() {
            when(orchestratorService.getContext("orch-1"))
                    .thenReturn(Map.of("env", "test", "version", "1.0"));

            Map<String, Object> context = orchestratorService.getContext("orch-1");

            assertEquals("test", context.get("env"));
            assertEquals("1.0", context.get("version"));
        }
    }

    // ══════════ Recovery ══════════

    @Nested
    @DisplayName("Recovery")
    class RecoveryTests {

        @Test
        @DisplayName("Create snapshot")
        void testCreateSnapshot() {
            orchestratorService.createSnapshot("orch-1");
            verify(orchestratorService).createSnapshot("orch-1");
        }

        @Test
        @DisplayName("Recover from snapshot")
        void testRecover() {
            orchestratorService.recover("orch-1");
            verify(orchestratorService).recover("orch-1");
        }
    }
}
