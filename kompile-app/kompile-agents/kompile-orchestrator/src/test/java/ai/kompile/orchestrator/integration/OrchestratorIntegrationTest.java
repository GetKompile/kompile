/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 */
package ai.kompile.orchestrator.integration;

import ai.kompile.orchestrator.TestApplication;
import ai.kompile.orchestrator.api.*;
import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.OrchestratorStatus;
import ai.kompile.orchestrator.model.state.StateCategory;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.task.*;
import ai.kompile.orchestrator.repository.OrchestratorInstanceRepository;
import ai.kompile.orchestrator.repository.TaskInstanceRepository;
import ai.kompile.orchestrator.service.registry.StateRegistry;
import ai.kompile.orchestrator.service.registry.TaskExecutorRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that orchestrate real processes through the state machine,
 * task execution, and workflow services.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class OrchestratorIntegrationTest {

    @Autowired private StateMachineService stateMachineService;
    @Autowired private TaskExecutionService taskExecutionService;
    @Autowired private OrchestratorInstanceRepository instanceRepository;
    @Autowired private TaskInstanceRepository taskInstanceRepository;
    @Autowired private StateRegistry stateRegistry;
    @Autowired private TaskExecutorRegistry executorRegistry;

    private String orchestratorId;

    @BeforeEach
    void setup() {
        orchestratorId = "test-orch-" + UUID.randomUUID();
        OrchestratorInstance instance = OrchestratorInstance.builder()
                .instanceId(orchestratorId)
                .name("Test Orchestrator")
                .status(OrchestratorStatus.CREATED)
                .build();
        instanceRepository.save(instance);
    }

    // ==================== Executor Registry Tests ====================

    @Test
    void allExecutorsRegistered() {
        assertTrue(executorRegistry.hasExecutor(TaskType.SHELL), "ShellTaskExecutor not registered");
        assertTrue(executorRegistry.hasExecutor(TaskType.HTTP), "HttpTaskExecutor not registered");
        assertTrue(executorRegistry.hasExecutor(TaskType.CODE), "CodeTaskExecutor not registered");
        assertTrue(executorRegistry.hasExecutor(TaskType.LLM_QUERY), "LlmQueryTaskExecutor not registered");
    }

    // ==================== Multi-Task Orchestration ====================

    @Test
    void orchestrateShellThenCodePipeline() {
        // Step 1: Shell task to generate data
        TaskDefinition shellDef = TaskDefinition.builder()
                .taskId("pipeline-shell-" + orchestratorId)
                .name("Generate Data")
                .taskType(TaskType.SHELL)
                .command("echo '42'")
                .build();
        taskExecutionService.registerTaskDefinition(shellDef);

        TaskInstance shellResult = taskExecutionService.executeTask(
                shellDef.getTaskId(), Collections.emptyMap(), orchestratorId,
                TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, shellResult.getStatus());
        String dataValue = shellResult.getOutput().trim();

        // Step 2: Feed shell output into Python code task
        TaskDefinition codeDef = TaskDefinition.builder()
                .taskId("pipeline-code-" + orchestratorId)
                .name("Process Data")
                .taskType(TaskType.CODE)
                .command("value = int('${data}'); result = value * 2 + 10; print(f'result={result}')")
                .build();
        taskExecutionService.registerTaskDefinition(codeDef);

        Map<String, String> codeVars = Map.of("data", dataValue);
        TaskInstance codeResult = taskExecutionService.executeTask(
                codeDef.getTaskId(), codeVars, orchestratorId,
                TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, codeResult.getStatus());
        assertTrue(codeResult.getOutput().contains("result=94"));

        // Verify both tasks are tracked under the same orchestrator
        List<TaskInstance> allTasks = taskInstanceRepository.findByOrchestratorInstanceId(orchestratorId);
        assertEquals(2, allTasks.size());
    }

    @Test
    void orchestrateParallelAsyncTasks() throws Exception {
        // Launch 3 async tasks simultaneously
        TaskDefinition def1 = TaskDefinition.builder()
                .taskId("parallel-1-" + orchestratorId)
                .name("Task 1").taskType(TaskType.SHELL)
                .command("echo 'task1 done'")
                .build();
        TaskDefinition def2 = TaskDefinition.builder()
                .taskId("parallel-2-" + orchestratorId)
                .name("Task 2").taskType(TaskType.CODE)
                .command("print('task2 done')")
                .build();
        TaskDefinition def3 = TaskDefinition.builder()
                .taskId("parallel-3-" + orchestratorId)
                .name("Task 3").taskType(TaskType.SHELL)
                .command("echo 'task3 done'")
                .build();

        taskExecutionService.registerTaskDefinition(def1);
        taskExecutionService.registerTaskDefinition(def2);
        taskExecutionService.registerTaskDefinition(def3);

        TaskInstance r1 = taskExecutionService.executeTask(def1.getTaskId(),
                Collections.emptyMap(), orchestratorId, TaskExecutionOptions.defaults());
        TaskInstance r2 = taskExecutionService.executeTask(def2.getTaskId(),
                Collections.emptyMap(), orchestratorId, TaskExecutionOptions.defaults());
        TaskInstance r3 = taskExecutionService.executeTask(def3.getTaskId(),
                Collections.emptyMap(), orchestratorId, TaskExecutionOptions.defaults());

        // Poll all three to completion
        Set<Long> pending = new HashSet<>(Set.of(r1.getId(), r2.getId(), r3.getId()));
        for (int i = 0; i < 100 && !pending.isEmpty(); i++) {
            Thread.sleep(100);
            pending.removeIf(id -> taskInstanceRepository.findById(id)
                    .map(t -> t.getStatus().isTerminal()).orElse(false));
        }

        assertTrue(pending.isEmpty(), "Not all parallel tasks completed in time");

        // Verify all succeeded
        assertEquals(TaskStatus.SUCCESS, taskInstanceRepository.findById(r1.getId()).orElseThrow().getStatus());
        assertEquals(TaskStatus.SUCCESS, taskInstanceRepository.findById(r2.getId()).orElseThrow().getStatus());
        assertEquals(TaskStatus.SUCCESS, taskInstanceRepository.findById(r3.getId()).orElseThrow().getStatus());
    }

    // ==================== State Machine with Task Hooks ====================

    @Test
    void stateMachineTransitionExecutesOnEnterTask() throws Exception {
        // Register a task to be triggered on state enter
        TaskDefinition onEnterDef = TaskDefinition.builder()
                .taskId("on-enter-task-" + orchestratorId)
                .name("State Enter Hook")
                .taskType(TaskType.SHELL)
                .command("echo 'entered processing state'")
                .build();
        taskExecutionService.registerTaskDefinition(onEnterDef);

        // Register custom states with onEnterTaskId
        StateDefinition startState = StateDefinition.builder()
                .stateId("custom-start-" + orchestratorId)
                .name("Start")
                .category(StateCategory.INITIAL)
                .allowedNextStates(Set.of("custom-process-" + orchestratorId))
                .build();

        StateDefinition processState = StateDefinition.builder()
                .stateId("custom-process-" + orchestratorId)
                .name("Processing")
                .category(StateCategory.PROCESSING)
                .onEnterTaskId("on-enter-task-" + orchestratorId)
                .allowedNextStates(Set.of("custom-done-" + orchestratorId))
                .build();

        StateDefinition doneState = StateDefinition.builder()
                .stateId("custom-done-" + orchestratorId)
                .name("Done")
                .category(StateCategory.TERMINAL)
                .build();

        stateMachineService.registerState(startState);
        stateMachineService.registerState(processState);
        stateMachineService.registerState(doneState);

        // Initialize into start state
        stateMachineService.initialize(orchestratorId, startState.getStateId());
        assertEquals(startState.getStateId(), stateMachineService.getCurrentStateId(orchestratorId));

        // Transition to processing — should fire the onEnter task
        stateMachineService.transitionTo(orchestratorId, processState.getStateId());
        assertEquals(processState.getStateId(), stateMachineService.getCurrentStateId(orchestratorId));

        // The onEnter task is async — wait for it
        Thread.sleep(1000);

        // Verify a task was created for this orchestrator
        List<TaskInstance> tasks = taskInstanceRepository.findByOrchestratorInstanceId(orchestratorId);
        assertFalse(tasks.isEmpty(), "No tasks were created by onEnter hook");

        // Find the hook task
        TaskInstance hookTask = tasks.stream()
                .filter(t -> t.getTaskDefinitionId().equals(onEnterDef.getTaskId()))
                .findFirst()
                .orElse(null);
        assertNotNull(hookTask, "onEnter task was not dispatched");

        // Wait for it to complete
        for (int i = 0; i < 50; i++) {
            hookTask = taskInstanceRepository.findById(hookTask.getId()).orElseThrow();
            if (hookTask.getStatus().isTerminal()) break;
            Thread.sleep(100);
        }

        assertEquals(TaskStatus.SUCCESS, hookTask.getStatus());
        assertTrue(hookTask.getOutput().contains("entered processing state"));
    }

    @Test
    void stateMachineTransitionExecutesOnExitTask() throws Exception {
        TaskDefinition onExitDef = TaskDefinition.builder()
                .taskId("on-exit-task-" + orchestratorId)
                .name("State Exit Hook")
                .taskType(TaskType.SHELL)
                .command("echo 'exiting start state'")
                .build();
        taskExecutionService.registerTaskDefinition(onExitDef);

        StateDefinition startState = StateDefinition.builder()
                .stateId("exit-start-" + orchestratorId)
                .name("Start")
                .category(StateCategory.INITIAL)
                .onExitTaskId("on-exit-task-" + orchestratorId)
                .allowedNextStates(Set.of("exit-end-" + orchestratorId))
                .build();

        StateDefinition endState = StateDefinition.builder()
                .stateId("exit-end-" + orchestratorId)
                .name("End")
                .category(StateCategory.TERMINAL)
                .build();

        stateMachineService.registerState(startState);
        stateMachineService.registerState(endState);

        stateMachineService.initialize(orchestratorId, startState.getStateId());
        stateMachineService.transitionTo(orchestratorId, endState.getStateId());

        Thread.sleep(1000);

        List<TaskInstance> tasks = taskInstanceRepository.findByOrchestratorInstanceId(orchestratorId);
        TaskInstance exitTask = tasks.stream()
                .filter(t -> t.getTaskDefinitionId().equals(onExitDef.getTaskId()))
                .findFirst()
                .orElse(null);
        assertNotNull(exitTask, "onExit task was not dispatched");

        for (int i = 0; i < 50; i++) {
            exitTask = taskInstanceRepository.findById(exitTask.getId()).orElseThrow();
            if (exitTask.getStatus().isTerminal()) break;
            Thread.sleep(100);
        }

        assertEquals(TaskStatus.SUCCESS, exitTask.getStatus());
        assertTrue(exitTask.getOutput().contains("exiting start state"));
    }

    @Test
    void stateMachineFullLifecycle() {
        // Walk through: INITIAL → INITIALIZING → PROCESSING → TERMINAL
        OrchestratorInstance instance = instanceRepository.findById(orchestratorId).orElseThrow();
        assertEquals(OrchestratorStatus.CREATED, instance.getStatus());

        // Register states (using default states from DefaultState enum)
        stateMachineService.initialize(orchestratorId, "idle");
        instance = instanceRepository.findById(orchestratorId).orElseThrow();
        assertEquals("idle", instance.getCurrentStateId());

        // Transition idle → initializing (idle only allows initializing and cancelled)
        stateMachineService.transitionTo(orchestratorId, "initializing");
        instance = instanceRepository.findById(orchestratorId).orElseThrow();
        assertEquals("initializing", instance.getCurrentStateId());
        assertEquals(OrchestratorStatus.RUNNING, instance.getStatus());

        // Transition initializing → processing
        stateMachineService.transitionTo(orchestratorId, "processing");
        instance = instanceRepository.findById(orchestratorId).orElseThrow();
        assertEquals("processing", instance.getCurrentStateId());
        assertEquals(OrchestratorStatus.RUNNING, instance.getStatus());

        // Transition processing → success
        stateMachineService.transitionTo(orchestratorId, "success");
        instance = instanceRepository.findById(orchestratorId).orElseThrow();
        assertEquals("success", instance.getCurrentStateId());
        assertEquals(OrchestratorStatus.COMPLETED, instance.getStatus());
    }

    @Test
    void stateMachineRejectsInvalidTransition() {
        stateMachineService.initialize(orchestratorId, "idle");

        // idle cannot directly transition to success (must go through processing)
        assertThrows(IllegalStateException.class, () ->
                stateMachineService.transitionTo(orchestratorId, "success"));

        // Verify state didn't change
        assertEquals("idle", stateMachineService.getCurrentStateId(orchestratorId));
    }

    @Test
    void forceTransitionBypasses() {
        stateMachineService.initialize(orchestratorId, "idle");

        // Force transition to failed state directly
        stateMachineService.forceTransitionTo(orchestratorId, "failed");

        OrchestratorInstance instance = instanceRepository.findById(orchestratorId).orElseThrow();
        assertEquals("failed", instance.getCurrentStateId());
        assertEquals(OrchestratorStatus.FAILED, instance.getStatus());
    }

    // ==================== Task Type Mixing ====================

    @Test
    void executeDifferentTaskTypesSequentially() {
        // Shell task
        TaskInstance shellResult = taskExecutionService.executeCommand(
                "echo 'step 1'", orchestratorId, TaskExecutionOptions.synchronous());
        assertEquals(TaskStatus.SUCCESS, shellResult.getStatus());

        // Code task
        TaskDefinition codeDef = TaskDefinition.builder()
                .taskId("seq-code-" + orchestratorId)
                .name("Seq Code").taskType(TaskType.CODE)
                .command("print('step 2: ' + str(2 + 2))")
                .build();
        taskExecutionService.registerTaskDefinition(codeDef);
        TaskInstance codeResult = taskExecutionService.executeTask(
                codeDef.getTaskId(), Collections.emptyMap(), orchestratorId,
                TaskExecutionOptions.synchronous());
        assertEquals(TaskStatus.SUCCESS, codeResult.getStatus());
        assertTrue(codeResult.getOutput().contains("step 2: 4"));

        // Verify all tasks recorded
        List<TaskInstance> all = taskInstanceRepository.findByOrchestratorInstanceId(orchestratorId);
        assertEquals(2, all.size());
        assertTrue(all.stream().allMatch(t -> t.getStatus() == TaskStatus.SUCCESS));
    }

    // ==================== Task Failure and Recovery ====================

    @Test
    void taskFailureRecordedCorrectly() {
        TaskDefinition failDef = TaskDefinition.builder()
                .taskId("fail-record-" + orchestratorId)
                .name("Fail Record")
                .taskType(TaskType.SHELL)
                .command("echo 'error output' >&2 && exit 1")
                .build();
        taskExecutionService.registerTaskDefinition(failDef);

        TaskInstance result = taskExecutionService.executeTask(
                failDef.getTaskId(), Collections.emptyMap(), orchestratorId,
                TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertEquals(1, result.getExitCode());
        assertNotNull(result.getErrorMessage());
        assertNotNull(result.getStartTime());
        assertNotNull(result.getEndTime());

        // Verify persisted to DB
        TaskInstance fromDb = taskInstanceRepository.findById(result.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, fromDb.getStatus());
    }

    @Test
    void taskSuccessPatternMatching() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("pattern-match-" + orchestratorId)
                .name("Pattern Match")
                .taskType(TaskType.SHELL)
                .command("echo 'BUILD SUCCESS in 5.2s'")
                .successPattern("BUILD SUCCESS")
                .failurePattern("BUILD FAILURE")
                .build();
        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask(
                def.getTaskId(), Collections.emptyMap(), orchestratorId,
                TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(def.matchesSuccessPattern(result.getOutput()));
        assertFalse(def.matchesFailurePattern(result.getOutput()));
    }

    // ==================== Context Propagation ====================

    @Test
    void contextPreservedAcrossStateTransitions() {
        StateDefinition startState = StateDefinition.builder()
                .stateId("ctx-start-" + orchestratorId)
                .name("Start")
                .category(StateCategory.INITIAL)
                .allowedNextStates(Set.of("ctx-mid-" + orchestratorId))
                .build();
        StateDefinition midState = StateDefinition.builder()
                .stateId("ctx-mid-" + orchestratorId)
                .name("Middle")
                .category(StateCategory.PROCESSING)
                .allowedNextStates(Set.of("ctx-end-" + orchestratorId))
                .build();
        StateDefinition endState = StateDefinition.builder()
                .stateId("ctx-end-" + orchestratorId)
                .name("End")
                .category(StateCategory.TERMINAL)
                .build();

        stateMachineService.registerState(startState);
        stateMachineService.registerState(midState);
        stateMachineService.registerState(endState);

        stateMachineService.initialize(orchestratorId, startState.getStateId());

        // Transition with context
        Map<String, Object> ctx1 = Map.of("key1", "value1", "step", 1);
        stateMachineService.transitionTo(orchestratorId, midState.getStateId(), ctx1);

        OrchestratorInstance inst = instanceRepository.findById(orchestratorId).orElseThrow();
        assertEquals("value1", inst.getContextValue("key1", String.class));
        assertEquals(1, inst.getContextValue("step", Integer.class));

        // Transition with additional context — should merge
        Map<String, Object> ctx2 = Map.of("key2", "value2", "step", 2);
        stateMachineService.transitionTo(orchestratorId, endState.getStateId(), ctx2);

        inst = instanceRepository.findById(orchestratorId).orElseThrow();
        assertEquals("value1", inst.getContextValue("key1", String.class)); // preserved
        assertEquals("value2", inst.getContextValue("key2", String.class)); // added
        assertEquals(2, inst.getContextValue("step", Integer.class)); // updated
    }

    // ==================== Required Variables Validation ====================

    @Test
    void missingRequiredVariablesThrows() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("req-vars-" + orchestratorId)
                .name("Required Vars")
                .taskType(TaskType.SHELL)
                .command("echo ${name}")
                .requiredVariables(Set.of("name", "email"))
                .build();
        taskExecutionService.registerTaskDefinition(def);

        assertThrows(IllegalArgumentException.class, () ->
                taskExecutionService.executeTask(def.getTaskId(),
                        Map.of("name", "Alice"), // missing "email"
                        orchestratorId, TaskExecutionOptions.synchronous()));
    }

    @Test
    void defaultVariablesFillInMissing() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("default-vars-" + orchestratorId)
                .name("Default Vars")
                .taskType(TaskType.SHELL)
                .command("echo '${greeting} ${name}'")
                .requiredVariables(Set.of("name"))
                .defaultVariables(Map.of("greeting", "Hello", "name", "World"))
                .build();
        taskExecutionService.registerTaskDefinition(def);

        // name is required but has a default, so empty map should work
        TaskInstance result = taskExecutionService.executeTask(def.getTaskId(),
                Collections.emptyMap(), orchestratorId, TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("Hello World"));
    }
}
