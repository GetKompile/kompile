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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CodeTaskExecutor — verifies Python/script code execution.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class CodeTaskExecutorTest {

    @Autowired
    private TaskExecutionService taskExecutionService;

    @Autowired
    private TaskInstanceRepository taskInstanceRepository;

    @Test
    void executePythonCode() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-python")
                .name("Python Code Test")
                .taskType(TaskType.CODE)
                .command("print('hello from python')")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-python",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("hello from python"));
        assertEquals(0, result.getExitCode());
    }

    @Test
    void executePythonWithComputation() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-python-compute")
                .name("Python Compute Test")
                .taskType(TaskType.CODE)
                .command("import json; data = {'sum': sum(range(10)), 'product': 1}; " +
                         "exec('\\nfor i in range(1,6):\\n data[\"product\"] *= i'); " +
                         "print(json.dumps(data))")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-python-compute",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("\"sum\": 45"));
        assertTrue(result.getOutput().contains("\"product\": 120"));
    }

    @Test
    void executePythonSyntaxError() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-python-error")
                .name("Python Error Test")
                .taskType(TaskType.CODE)
                .command("print('missing paren'")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-python-error",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertNotEquals(0, result.getExitCode());
    }

    @Test
    void executePythonWithVariableSubstitution() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-python-vars")
                .name("Python Variable Test")
                .taskType(TaskType.CODE)
                .command("name = '${name}'; count = int('${count}'); " +
                         "print(f'{name} repeated {count} times: ' + name * count)")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        Map<String, String> vars = Map.of("name", "abc", "count", "3");
        TaskInstance result = taskExecutionService.executeTask("test-python-vars",
                vars, "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("abc repeated 3 times: abcabcabc"));
    }

    @Test
    void executeBashViaLanguageVariable() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-bash-code")
                .name("Bash Code Test")
                .taskType(TaskType.CODE)
                .command("echo 'bash code executor'")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        // Language specified via variables
        Map<String, String> vars = Map.of("language", "bash");
        TaskInstance result = taskExecutionService.executeTask("test-bash-code",
                vars, "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("bash code executor"));
    }

    @Test
    void executeCodeAsync() throws Exception {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-code-async")
                .name("Async Code Test")
                .taskType(TaskType.CODE)
                .command("import time; time.sleep(0.1); print('async code done')")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-code-async",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.defaults());

        assertNotNull(result.getId());

        // Poll for completion
        for (int i = 0; i < 100; i++) {
            TaskInstance t = taskInstanceRepository.findById(result.getId()).orElseThrow();
            if (t.getStatus().isTerminal()) {
                assertEquals(TaskStatus.SUCCESS, t.getStatus());
                assertTrue(t.getOutput().contains("async code done"));
                return;
            }
            Thread.sleep(100);
        }
        fail("Async code task did not complete in time");
    }

    @Test
    void executeCodeWithTimeout() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-code-timeout")
                .name("Code Timeout Test")
                .taskType(TaskType.CODE)
                .command("import time; time.sleep(30)")
                .timeoutSeconds(1L)
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-code-timeout",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.TIMEOUT, result.getStatus());
    }

    @Test
    void codeExecutorRegisteredForCorrectType() {
        assertTrue(taskExecutionService.getExecutor(TaskType.CODE).isPresent());
        assertInstanceOf(CodeTaskExecutor.class, taskExecutionService.getExecutor(TaskType.CODE).get());
    }
}
