/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 */
package ai.kompile.orchestrator.executor;

import ai.kompile.orchestrator.TestApplication;
import ai.kompile.orchestrator.api.TaskExecutionService;
import ai.kompile.orchestrator.model.task.*;
import ai.kompile.orchestrator.repository.TaskInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ShellTaskExecutor — verifies actual shell process execution.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class ShellTaskExecutorTest {

    @Autowired
    private TaskExecutionService taskExecutionService;

    @Autowired
    private TaskInstanceRepository taskInstanceRepository;

    @Test
    void executeEchoCommandSync() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-echo-sync")
                .name("Echo Test")
                .taskType(TaskType.SHELL)
                .command("echo 'hello orchestrator'")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskExecutionOptions options = TaskExecutionOptions.synchronous();
        TaskInstance result = taskExecutionService.executeTask("test-echo-sync",
                Collections.emptyMap(), "test-instance", options);

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("hello orchestrator"));
        assertEquals(0, result.getExitCode());
        assertNotNull(result.getStartTime());
        assertNotNull(result.getEndTime());
    }

    @Test
    void executeFailingCommand() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-fail")
                .name("Failing Command")
                .taskType(TaskType.SHELL)
                .command("exit 42")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-fail",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertEquals(42, result.getExitCode());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void executeWithVariableSubstitution() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-vars")
                .name("Variable Test")
                .taskType(TaskType.SHELL)
                .command("echo 'Hello ${name}, you are ${role}'")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        Map<String, String> vars = Map.of("name", "Alice", "role", "admin");
        TaskInstance result = taskExecutionService.executeTask("test-vars",
                vars, "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("Hello Alice, you are admin"));
    }

    @Test
    void executeAsyncCommand() throws Exception {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-async")
                .name("Async Test")
                .taskType(TaskType.SHELL)
                .command("echo 'async done'")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskExecutionOptions options = TaskExecutionOptions.defaults(); // async by default
        TaskInstance result = taskExecutionService.executeTask("test-async",
                Collections.emptyMap(), "test-instance", options);

        assertNotNull(result.getId());

        // Poll for completion
        TaskInstance finalResult = pollForCompletion(result.getId(), 10);
        assertEquals(TaskStatus.SUCCESS, finalResult.getStatus());
        assertTrue(finalResult.getOutput().contains("async done"));
    }

    @Test
    void executeMultilineOutput() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-multiline")
                .name("Multiline Test")
                .taskType(TaskType.SHELL)
                .command("echo 'line1' && echo 'line2' && echo 'line3'")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-multiline",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        String output = result.getOutput();
        assertTrue(output.contains("line1"));
        assertTrue(output.contains("line2"));
        assertTrue(output.contains("line3"));
    }

    @Test
    void executeWithTimeout() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-timeout")
                .name("Timeout Test")
                .taskType(TaskType.SHELL)
                .command("sleep 30")
                .timeoutSeconds(1L)
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-timeout",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.TIMEOUT, result.getStatus());
    }

    @Test
    void executePipelineCommand() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-pipe")
                .name("Pipeline Test")
                .taskType(TaskType.SHELL)
                .command("echo -e 'apple\\nbanana\\ncherry' | grep 'ban'")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-pipe",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().trim().contains("banana"));
    }

    @Test
    void cancelRunningTask() throws Exception {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-cancel")
                .name("Cancel Test")
                .taskType(TaskType.SHELL)
                .command("sleep 60")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-cancel",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.defaults());

        Thread.sleep(500); // Let it start

        // Verify it's actually running before we cancel
        TaskInstance running = taskInstanceRepository.findById(result.getId()).orElseThrow();
        assertEquals(TaskStatus.RUNNING, running.getStatus());
        assertNotNull(running.getStartTime());

        taskExecutionService.cancelTask(result.getId());

        TaskInstance cancelled = pollForTerminal(result.getId(), 10);
        assertEquals(TaskStatus.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getEndTime());
    }

    @Test
    void adHocCommand() {
        TaskInstance result = taskExecutionService.executeCommand(
                "echo 'ad hoc'", "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("ad hoc"));
    }

    private TaskInstance pollForCompletion(Long id, int maxSeconds) throws InterruptedException {
        for (int i = 0; i < maxSeconds * 10; i++) {
            TaskInstance t = taskInstanceRepository.findById(id).orElseThrow();
            if (t.getStatus().isTerminal()) {
                return t;
            }
            Thread.sleep(100);
        }
        return taskInstanceRepository.findById(id).orElseThrow();
    }

    private TaskInstance pollForTerminal(Long id, int maxSeconds) throws InterruptedException {
        return pollForCompletion(id, maxSeconds);
    }
}
