/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 */
package ai.kompile.orchestrator.executor;

import ai.kompile.orchestrator.TestApplication;
import ai.kompile.orchestrator.api.LlmIntegrationService;
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
 * Tests for LlmQueryTaskExecutor — uses the stub LLM provider to verify
 * that LLM query tasks are properly dispatched and their output captured.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class LlmQueryTaskExecutorTest {

    @Autowired
    private TaskExecutionService taskExecutionService;

    @Autowired
    private TaskInstanceRepository taskInstanceRepository;

    @Autowired
    private TestApplication.StubLlmProvider stubLlmProvider;

    @Autowired
    private LlmIntegrationService llmIntegrationService;

    @Test
    void executeLlmQuerySync() {
        stubLlmProvider.setNextResponse("The answer to your question is 42.");

        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-llm-query")
                .name("LLM Query Test")
                .taskType(TaskType.LLM_QUERY)
                .promptTemplate("What is the meaning of life?")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-llm-query",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("42"));
        assertEquals(0, result.getExitCode());
    }

    @Test
    void executeLlmQueryWithVariables() {
        stubLlmProvider.setNextResponse("Analysis complete: code is clean.");

        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-llm-vars")
                .name("LLM Variable Test")
                .taskType(TaskType.LLM_QUERY)
                .promptTemplate("Analyze the following code: ${code}")
                .systemPrompt("You are a code reviewer.")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        Map<String, String> vars = Map.of("code", "print('hello world')");
        TaskInstance result = taskExecutionService.executeTask("test-llm-vars",
                vars, "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("Analysis complete"));
    }

    @Test
    void executeLlmQueryAsync() throws Exception {
        stubLlmProvider.setNextResponse("Async LLM response received.");

        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-llm-async")
                .name("LLM Async Test")
                .taskType(TaskType.LLM_QUERY)
                .promptTemplate("Summarize this document")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-llm-async",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.defaults());

        assertNotNull(result.getId());

        // Poll for completion
        for (int i = 0; i < 100; i++) {
            TaskInstance t = taskInstanceRepository.findById(result.getId()).orElseThrow();
            if (t.getStatus().isTerminal()) {
                assertEquals(TaskStatus.SUCCESS, t.getStatus());
                assertTrue(t.getOutput().contains("Async LLM response"));
                return;
            }
            Thread.sleep(100);
        }
        fail("Async LLM task did not complete in time");
    }

    @Test
    void executeLlmQueryWithSystemPrompt() {
        stubLlmProvider.setNextResponse("Expert analysis: all systems nominal.");

        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-llm-system")
                .name("LLM System Prompt Test")
                .taskType(TaskType.LLM_QUERY)
                .promptTemplate("Check system status")
                .systemPrompt("You are a systems monitoring expert. Be concise.")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-llm-system",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("Expert analysis"));
    }

    @Test
    void llmQuerySessionTracked() {
        stubLlmProvider.setNextResponse("Tracked response.");

        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-llm-tracked")
                .name("LLM Tracked Test")
                .taskType(TaskType.LLM_QUERY)
                .promptTemplate("Track this query")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-llm-tracked",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        // The LLM session ID should be recorded on the task instance
        assertNotNull(result.getLlmSessionId());
    }

    @Test
    void executorRegisteredForLlmQueryType() {
        assertTrue(taskExecutionService.getExecutor(TaskType.LLM_QUERY).isPresent());
        assertInstanceOf(LlmQueryTaskExecutor.class,
                taskExecutionService.getExecutor(TaskType.LLM_QUERY).get());
    }

    @Test
    void differentLlmResponsesPerTask() {
        // First task
        stubLlmProvider.setNextResponse("Response A");
        TaskDefinition def1 = TaskDefinition.builder()
                .taskId("test-llm-a")
                .name("LLM A").taskType(TaskType.LLM_QUERY)
                .promptTemplate("Query A")
                .build();
        taskExecutionService.registerTaskDefinition(def1);
        TaskInstance r1 = taskExecutionService.executeTask("test-llm-a",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        // Second task with different response
        stubLlmProvider.setNextResponse("Response B");
        TaskDefinition def2 = TaskDefinition.builder()
                .taskId("test-llm-b")
                .name("LLM B").taskType(TaskType.LLM_QUERY)
                .promptTemplate("Query B")
                .build();
        taskExecutionService.registerTaskDefinition(def2);
        TaskInstance r2 = taskExecutionService.executeTask("test-llm-b",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertTrue(r1.getOutput().contains("Response A"));
        assertTrue(r2.getOutput().contains("Response B"));
    }
}
