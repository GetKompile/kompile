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

import ai.kompile.orchestrator.api.TaskExecutor;
import ai.kompile.orchestrator.model.event.TaskEvent;
import ai.kompile.orchestrator.model.event.TaskOutputEvent;
import ai.kompile.orchestrator.model.task.*;
import ai.kompile.orchestrator.repository.TaskInstanceRepository;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task executor for code execution tasks.
 * Supports Python, Node.js, and other scripting languages via ProcessBuilder.
 * The command field contains the code to execute; the language is inferred
 * from the executor class name or defaults to Python.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeTaskExecutor implements TaskExecutor {

    private final TaskInstanceRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final Map<Long, StringBuilder> outputBuffers = new ConcurrentHashMap<>();

    @Override
    public Set<TaskType> getSupportedTypes() {
        return Set.of(TaskType.CODE);
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

            String language = detectLanguage(task);
            String code = task.getCommand();

            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Code content is required for CODE task type");
            }

            ProcessBuilder pb = buildProcess(language, code);

            if (task.getWorkingDirectory() != null) {
                pb.directory(new File(task.getWorkingDirectory()));
            }
            pb.redirectErrorStream(!options.isCaptureStderr());

            if (options.getEnvironment() != null && !options.getEnvironment().isEmpty()) {
                pb.environment().putAll(options.getEnvironment());
            }

            Process process = pb.start();
            runningProcesses.put(task.getId(), process);

            final TaskInstance savedTask = task;
            StringBuilder output = new StringBuilder();
            outputBuffers.put(task.getId(), output);

            // Read output in a background thread so waitFor timeout can fire
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");

                        if (options.isStreamOutput()) {
                            eventPublisher.publishEvent(new TaskOutputEvent(
                                    this, savedTask.getOrchestratorInstanceId(), savedTask.getId(), line));
                        }
                    }
                } catch (Exception e) {
                    // Process destroyed or stream closed — expected on timeout/cancel
                }
            });
            outputReader.setDaemon(true);
            outputReader.start();

            long timeoutSeconds = task.getTimeoutSeconds() != null ? task.getTimeoutSeconds() : 300;
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                outputReader.join(1000);
                task.markTimeout();
                eventPublisher.publishEvent(TaskEvent.timeout(this, task));
            } else {
                outputReader.join(5000);
                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    task.markSuccess(output.toString(), exitCode);
                    eventPublisher.publishEvent(TaskEvent.completed(this, task));
                } else {
                    task.markFailed(output.toString(), exitCode, "Code execution failed with exit code: " + exitCode);
                    eventPublisher.publishEvent(TaskEvent.failed(this, task));
                }
            }

        } catch (Exception e) {
            log.error("Code task execution failed: {}", task.getId(), e);
            task.markFailed(e.getMessage());
            eventPublisher.publishEvent(TaskEvent.failed(this, task));
        } finally {
            runningProcesses.remove(task.getId());
            cancelFlags.remove(task.getId());
            outputBuffers.remove(task.getId());
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

                String language = detectLanguage(task);
                String code = task.getCommand();

                if (code == null || code.isBlank()) {
                    throw new IllegalArgumentException("Code content is required for CODE task type");
                }

                ProcessBuilder pb = buildProcess(language, code);

                if (task.getWorkingDirectory() != null) {
                    pb.directory(new File(task.getWorkingDirectory()));
                }
                pb.redirectErrorStream(!options.isCaptureStderr());

                if (options.getEnvironment() != null && !options.getEnvironment().isEmpty()) {
                    pb.environment().putAll(options.getEnvironment());
                }

                Process process = pb.start();
                runningProcesses.put(task.getId(), process);
                task.setProcessId((long) process.pid());
                taskRepository.save(task);

                StringBuilder output = new StringBuilder();
                outputBuffers.put(task.getId(), output);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    int lineCount = 0;

                    while ((line = reader.readLine()) != null) {
                        if (cancelFlag.get()) {
                            process.destroyForcibly();
                            task.markCancelled();
                            taskRepository.save(task);
                            eventPublisher.publishEvent(TaskEvent.cancelled(this, task));
                            return;
                        }

                        output.append(line).append("\n");

                        if (options.isStreamOutput()) {
                            eventPublisher.publishEvent(new TaskOutputEvent(
                                    this, task.getOrchestratorInstanceId(), task.getId(), line));
                        }

                        lineCount++;
                        if (lineCount % 50 == 0) {
                            task.setOutput(output.toString());
                            taskRepository.save(task);
                        }
                    }
                }

                long timeoutSeconds = task.getTimeoutSeconds() != null ? task.getTimeoutSeconds() : 300;
                boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

                if (!completed) {
                    process.destroyForcibly();
                    task.markTimeout();
                    eventPublisher.publishEvent(TaskEvent.timeout(this, task));
                } else if (task.getStatus() != TaskStatus.CANCELLED) {
                    int exitCode = process.exitValue();
                    if (exitCode == 0) {
                        task.markSuccess(output.toString(), exitCode);
                        eventPublisher.publishEvent(TaskEvent.completed(this, task));
                    } else {
                        task.markFailed(output.toString(), exitCode, "Code execution failed with exit code: " + exitCode);
                        eventPublisher.publishEvent(TaskEvent.failed(this, task));
                    }
                }

                taskRepository.save(task);

            } catch (Exception e) {
                log.error("Async code task execution failed: {}", task.getId(), e);
                task.markFailed(e.getMessage());
                taskRepository.save(task);
                eventPublisher.publishEvent(TaskEvent.failed(this, task));
            } finally {
                runningProcesses.remove(task.getId());
                cancelFlags.remove(task.getId());
                outputBuffers.remove(task.getId());
            }
        });

        executorThread.setName("code-task-executor-" + task.getId());
        executorThread.start();
    }

    private ProcessBuilder buildProcess(String language, String code) {
        switch (language.toLowerCase()) {
            case "python":
            case "python3":
                return new ProcessBuilder("python3", "-c", code);
            case "node":
            case "nodejs":
            case "javascript":
                return new ProcessBuilder("node", "-e", code);
            case "ruby":
                return new ProcessBuilder("ruby", "-e", code);
            case "perl":
                return new ProcessBuilder("perl", "-e", code);
            case "bash":
            case "shell":
                return new ProcessBuilder("bash", "-c", code);
            default:
                // Default to python
                return new ProcessBuilder("python3", "-c", code);
        }
    }

    private String detectLanguage(TaskInstance task) {
        // Check metadata for explicit language specification
        if (task.getMetadataJson() != null && !task.getMetadataJson().isEmpty()) {
            try {
                Map<String, String> meta = objectMapper.readValue(task.getMetadataJson(),
                        new TypeReference<Map<String, String>>() {});
                // Check for language key in metadata (persisted from variables)
                String language = meta.get("language");
                if (language != null && !language.isBlank()) {
                    return language;
                }
                String executorClass = meta.get("executorClassName");
                if (executorClass != null && !executorClass.isBlank()) {
                    return executorClass.toLowerCase();
                }
            } catch (Exception e) {
                log.warn("Failed to parse metadata for task {}: {}", task.getId(), e.getMessage());
            }
        }

        // Check transient variables for language hint
        if (task.getVariables() != null && task.getVariables().containsKey("language")) {
            return task.getVariables().get("language");
        }

        return "python";
    }

    @Override
    public void cancel(Long taskInstanceId) {
        AtomicBoolean flag = cancelFlags.get(taskInstanceId);
        if (flag != null) {
            flag.set(true);
        }

        Process process = runningProcesses.get(taskInstanceId);
        if (process != null) {
            process.destroyForcibly();
        }
    }

    @Override
    public boolean isRunning(Long taskInstanceId) {
        return runningProcesses.containsKey(taskInstanceId);
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
