/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.executor;

import ai.kompile.orchestrator.api.LlmIntegrationService;
import ai.kompile.orchestrator.api.TaskExecutor;
import ai.kompile.orchestrator.model.event.TaskEvent;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmSessionRequest;
import ai.kompile.orchestrator.model.task.*;
import ai.kompile.orchestrator.repository.TaskInstanceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task executor for LLM query tasks.
 * Delegates to LlmIntegrationService to start a session and capture the response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmQueryTaskExecutor implements TaskExecutor {

    private final TaskInstanceRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final LlmIntegrationService llmService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final Map<Long, StringBuilder> outputBuffers = new ConcurrentHashMap<>();
    private final Map<Long, Long> sessionMapping = new ConcurrentHashMap<>();

    @Override
    public Set<TaskType> getSupportedTypes() {
        return Set.of(TaskType.LLM_QUERY);
    }

    @Override
    public TaskInstance execute(TaskDefinition definition, Map<String, String> variables, TaskExecutionOptions options) {
        TaskInstance instance = TaskInstance.fromDefinition(definition, variables);
        if (options.getWorkingDirectory() != null) {
            instance.setWorkingDirectory(options.getWorkingDirectory());
        }
        if (options.getTimeout() != null) {
            instance.setTimeout(options.getTimeout());
        }
        instance = taskRepository.save(instance);
        return execute(instance, options);
    }

    @Override
    public TaskInstance execute(TaskInstance taskInstance, TaskExecutionOptions options) {
        if (options.isAsync()) {
            executeAsync(taskInstance, options);
            return taskInstance;
        } else {
            return executeSync(taskInstance, options);
        }
    }

    private TaskInstance executeSync(TaskInstance task, TaskExecutionOptions options) {
        try {
            task.markRunning();
            task = taskRepository.save(task);
            eventPublisher.publishEvent(TaskEvent.started(this, task));

            StringBuilder output = new StringBuilder();
            outputBuffers.put(task.getId(), output);

            String prompt = extractPrompt(task);
            String systemPrompt = extractField(task, "systemPrompt");
            String agentName = extractField(task, "agentName");

            LlmSessionRequest.LlmSessionRequestBuilder requestBuilder = LlmSessionRequest.builder()
                    .prompt(prompt)
                    .orchestratorInstanceId(task.getOrchestratorInstanceId());

            if (systemPrompt != null) {
                requestBuilder.systemPrompt(systemPrompt);
            }

            LlmSessionRequest request = requestBuilder.build();

            // Start LLM session
            LlmSession session;
            String providerId = options.getLlmProviderId();
            if (providerId != null) {
                session = llmService.startSession(providerId, request);
            } else {
                session = llmService.startSession(request);
            }

            sessionMapping.put(task.getId(), session.getId());
            task.setLlmSessionId(session.getId());

            // Wait for session completion by polling
            String sessionOutput = waitForSession(session.getId(), task);

            output.append(sessionOutput != null ? sessionOutput : "");
            task.markSuccess(output.toString(), 0);
            eventPublisher.publishEvent(TaskEvent.completed(this, task));

        } catch (Exception e) {
            log.error("LLM query task execution failed: {}", task.getId(), e);
            task.markFailed(e.getMessage());
            eventPublisher.publishEvent(TaskEvent.failed(this, task));
        } finally {
            cancelFlags.remove(task.getId());
            outputBuffers.remove(task.getId());
            sessionMapping.remove(task.getId());
        }

        return taskRepository.save(task);
    }

    private void executeAsync(TaskInstance task, TaskExecutionOptions options) {
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(task.getId(), cancelFlag);

        Thread executorThread = new Thread(() -> {
            try {
                task.markRunning();
                taskRepository.save(task);
                eventPublisher.publishEvent(TaskEvent.started(this, task));

                if (cancelFlag.get()) {
                    task.markCancelled();
                    taskRepository.save(task);
                    eventPublisher.publishEvent(TaskEvent.cancelled(this, task));
                    return;
                }

                StringBuilder output = new StringBuilder();
                outputBuffers.put(task.getId(), output);

                String prompt = extractPrompt(task);
                String systemPrompt = extractField(task, "systemPrompt");

                LlmSessionRequest.LlmSessionRequestBuilder requestBuilder = LlmSessionRequest.builder()
                        .prompt(prompt)
                        .orchestratorInstanceId(task.getOrchestratorInstanceId());

                if (systemPrompt != null) {
                    requestBuilder.systemPrompt(systemPrompt);
                }

                LlmSessionRequest request = requestBuilder.build();

                LlmSession session;
                String providerId = options.getLlmProviderId();
                if (providerId != null) {
                    session = llmService.startSession(providerId, request);
                } else {
                    session = llmService.startSession(request);
                }

                sessionMapping.put(task.getId(), session.getId());
                task.setLlmSessionId(session.getId());
                taskRepository.save(task);

                String sessionOutput = waitForSession(session.getId(), task);

                if (cancelFlag.get()) {
                    task.markCancelled();
                    taskRepository.save(task);
                    eventPublisher.publishEvent(TaskEvent.cancelled(this, task));
                    return;
                }

                output.append(sessionOutput != null ? sessionOutput : "");
                task.markSuccess(output.toString(), 0);
                eventPublisher.publishEvent(TaskEvent.completed(this, task));
                taskRepository.save(task);

            } catch (Exception e) {
                log.error("Async LLM query task execution failed: {}", task.getId(), e);
                task.markFailed(e.getMessage());
                taskRepository.save(task);
                eventPublisher.publishEvent(TaskEvent.failed(this, task));
            } finally {
                cancelFlags.remove(task.getId());
                outputBuffers.remove(task.getId());
                sessionMapping.remove(task.getId());
            }
        });

        executorThread.setName("llm-task-executor-" + task.getId());
        executorThread.start();
    }

    private String waitForSession(Long sessionId, TaskInstance task) throws InterruptedException {
        long timeoutMs = (task.getTimeoutSeconds() != null ? task.getTimeoutSeconds() : 300) * 1000L;
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            AtomicBoolean cancelFlag = cancelFlags.get(task.getId());
            if (cancelFlag != null && cancelFlag.get()) {
                llmService.cancelSession(sessionId);
                throw new InterruptedException("Task cancelled");
            }

            Optional<LlmSession> sessionOpt = llmService.getSession(sessionId);
            if (sessionOpt.isPresent() && !llmService.isSessionActive(sessionId)) {
                return sessionOpt.get().getOutput();
            }

            Thread.sleep(500);
        }

        llmService.cancelSession(sessionId);
        throw new RuntimeException("LLM session timed out after " + (timeoutMs / 1000) + "s");
    }

    private String extractPrompt(TaskInstance task) {
        // Try command field first (set from resolveCommand which uses promptTemplate)
        if (task.getCommand() != null && !task.getCommand().isBlank()) {
            return task.getCommand();
        }

        // Try metadata
        String metaPrompt = extractField(task, "prompt");
        if (metaPrompt != null) {
            return metaPrompt;
        }

        return "No prompt provided";
    }

    private String extractField(TaskInstance task, String fieldName) {
        if (task.getMetadataJson() != null && !task.getMetadataJson().isEmpty()) {
            try {
                Map<String, String> meta = objectMapper.readValue(task.getMetadataJson(),
                        new TypeReference<Map<String, String>>() {});
                return meta.get(fieldName);
            } catch (Exception e) {
                log.warn("Failed to parse metadata for task {}: {}", task.getId(), e.getMessage());
            }
        }
        return null;
    }

    @Override
    public void cancel(Long taskInstanceId) {
        AtomicBoolean flag = cancelFlags.get(taskInstanceId);
        if (flag != null) {
            flag.set(true);
        }

        Long sessionId = sessionMapping.get(taskInstanceId);
        if (sessionId != null) {
            try {
                llmService.cancelSession(sessionId);
            } catch (Exception e) {
                log.warn("Error cancelling LLM session for task {}: {}", taskInstanceId, e.getMessage());
            }
        }
    }

    @Override
    public boolean isRunning(Long taskInstanceId) {
        return cancelFlags.containsKey(taskInstanceId);
    }

    @Override
    public String getCurrentOutput(Long taskInstanceId) {
        StringBuilder buffer = outputBuffers.get(taskInstanceId);
        return buffer != null ? buffer.toString() : null;
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
