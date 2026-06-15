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

package ai.kompile.process.service;

import ai.kompile.process.execution.RunStatus;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStatus;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that ProcessEngineServiceImpl fires ProcessGraphCallback
 * at the correct points: onStepCompleted for newly-terminal steps,
 * onRunCompleted for terminal run statuses.
 */
class ProcessEngineGraphCallbackTest {

    private static final String ORIGINAL_USER_HOME = System.getProperty("user.home");

    private Path tempHome;
    private ProcessEngineServiceImpl service;
    private RecordingCallback callback;

    @BeforeEach
    void setUp() throws IOException {
        tempHome = Files.createTempDirectory("kompile-callback-test-");
        System.setProperty("user.home", tempHome.toString());

        service = new ProcessEngineServiceImpl();
        service.init();

        callback = new RecordingCallback();
        service.setProcessGraphCallback(callback);
    }

    @AfterEach
    void tearDown() throws IOException {
        System.setProperty("user.home", ORIGINAL_USER_HOME);
        if (tempHome != null && Files.exists(tempHome)) {
            Files.walk(tempHome)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> {
                     try { Files.delete(p); } catch (IOException ignored) {}
                 });
        }
    }

    @Test
    void autoStep_firesOnStepCompleted_andOnRunCompleted() {
        // Create a simple process with one AUTO step
        ProcessDefinition def = createProcessWithSteps(
                createAutoStep("1.1", "Compute total", "result", "#amount * 2"));
        service.createProcess(def);
        service.approveProcess(def.getId(), "tester");

        // Start run with initial data
        Map<String, Object> initialData = new HashMap<>();
        initialData.put("amount", 100);
        WorkflowRun run = service.startRun(def.getId(), initialData);

        // AUTO step should have completed, triggering callbacks
        assertEquals(RunStatus.COMPLETED, run.getStatus());

        // onStepCompleted should have fired exactly once
        assertEquals(1, callback.stepEvents.size(), "Expected 1 step callback");
        assertEquals("1.1", callback.stepEvents.get(0).step.getStepId());
        assertEquals(StepExecutionStatus.COMPLETED, callback.stepEvents.get(0).step.getStatus());

        // onRunCompleted should have fired exactly once
        assertEquals(1, callback.runEvents.size(), "Expected 1 run callback");
        assertEquals(RunStatus.COMPLETED, callback.runEvents.get(0).getStatus());
    }

    @Test
    void multipleAutoSteps_firesCallbackForEachStep() {
        ProcessDefinition def = createProcessWithSteps(
                createAutoStep("1.1", "Step A", "a", "'hello'"),
                createAutoStep("1.2", "Step B", "b", "'world'"));
        service.createProcess(def);
        service.approveProcess(def.getId(), "tester");

        WorkflowRun run = service.startRun(def.getId(), new HashMap<>());

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals(2, callback.stepEvents.size(), "Expected 2 step callbacks");
        assertEquals("1.1", callback.stepEvents.get(0).step.getStepId());
        assertEquals("1.2", callback.stepEvents.get(1).step.getStepId());
        assertEquals(1, callback.runEvents.size());
    }

    @Test
    void pausedRun_doesNotFireRunCompleted() {
        // APPROVE step pauses the run
        ProcessDefinition def = createProcessWithSteps(
                createApproveStep("1.1", "Review"));
        service.createProcess(def);
        service.approveProcess(def.getId(), "tester");

        WorkflowRun run = service.startRun(def.getId(), new HashMap<>());

        // Run should be PAUSED (waiting for approval)
        assertEquals(RunStatus.PAUSED_FOR_APPROVAL, run.getStatus());

        // No step completed yet (it's awaiting approval), no run completed
        assertEquals(0, callback.stepEvents.size(), "No step should have completed yet");
        assertEquals(0, callback.runEvents.size(), "Run should not be completed while paused");
    }

    @Test
    void cancelledRun_firesOnRunCompleted() {
        ProcessDefinition def = createProcessWithSteps(
                createApproveStep("1.1", "Review"));
        service.createProcess(def);
        service.approveProcess(def.getId(), "tester");

        WorkflowRun run = service.startRun(def.getId(), new HashMap<>());
        assertEquals(RunStatus.PAUSED_FOR_APPROVAL, run.getStatus());

        // Cancel the run
        WorkflowRun cancelled = service.cancelRun(run.getId());
        assertEquals(RunStatus.CANCELLED, cancelled.getStatus());

        // onRunCompleted should fire for CANCELLED
        assertEquals(1, callback.runEvents.size(), "Expected run callback on cancel");
        assertEquals(RunStatus.CANCELLED, callback.runEvents.get(0).getStatus());
    }

    @Test
    void callbackException_doesNotPreventRunCompletion() {
        // Use a callback that throws
        service.setProcessGraphCallback(new ProcessGraphCallback() {
            @Override
            public void onStepCompleted(WorkflowRun run, StepExecution step) {
                throw new RuntimeException("Simulated callback failure");
            }

            @Override
            public void onRunCompleted(WorkflowRun run) {
                throw new RuntimeException("Simulated callback failure");
            }
        });

        ProcessDefinition def = createProcessWithSteps(
                createAutoStep("1.1", "Compute", "x", "42"));
        service.createProcess(def);
        service.approveProcess(def.getId(), "tester");

        // Should not throw — callback failures are swallowed
        WorkflowRun run = service.startRun(def.getId(), new HashMap<>());
        assertEquals(RunStatus.COMPLETED, run.getStatus());
    }

    @Test
    void noCallback_runsNormally() {
        // Remove callback
        service.setProcessGraphCallback(null);

        ProcessDefinition def = createProcessWithSteps(
                createAutoStep("1.1", "Compute", "x", "42"));
        service.createProcess(def);
        service.approveProcess(def.getId(), "tester");

        WorkflowRun run = service.startRun(def.getId(), new HashMap<>());
        assertEquals(RunStatus.COMPLETED, run.getStatus());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ProcessDefinition createProcessWithSteps(ProcessStep... steps) {
        ProcessPhase phase = ProcessPhase.builder()
                .id("phase-1")
                .name("Main")
                .order(1)
                .steps(List.of(steps))
                .build();

        return ProcessDefinition.builder()
                .id("test-process-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Test Process")
                .version(1)
                .status(ProcessStatus.DRAFT)
                .phases(List.of(phase))
                .build();
    }

    private ProcessStep createAutoStep(String id, String name, String outputKey, String expression) {
        return ProcessStep.builder()
                .id(id)
                .name(name)
                .stepType(StepType.AUTO)
                .executionExpressions(Map.of(outputKey, expression))
                .build();
    }

    private ProcessStep createApproveStep(String id, String name) {
        return ProcessStep.builder()
                .id(id)
                .name(name)
                .stepType(StepType.APPROVE)
                .build();
    }

    /**
     * Recording callback that captures all invocations for assertion.
     */
    static class RecordingCallback implements ProcessGraphCallback {
        final List<StepEvent> stepEvents = new CopyOnWriteArrayList<>();
        final List<WorkflowRun> runEvents = new CopyOnWriteArrayList<>();

        @Override
        public void onStepCompleted(WorkflowRun run, StepExecution step) {
            stepEvents.add(new StepEvent(run, step));
        }

        @Override
        public void onRunCompleted(WorkflowRun run) {
            runEvents.add(run);
        }

        record StepEvent(WorkflowRun run, StepExecution step) {}
    }
}
