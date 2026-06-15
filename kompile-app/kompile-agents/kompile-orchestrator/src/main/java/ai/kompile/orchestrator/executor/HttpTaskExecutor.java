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
import ai.kompile.orchestrator.model.task.*;
import ai.kompile.orchestrator.repository.TaskInstanceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task executor for HTTP requests.
 * Reads httpUrl, httpMethod, httpHeadersJson, httpBodyTemplate from TaskDefinition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpTaskExecutor implements TaskExecutor {

    private final TaskInstanceRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final Map<Long, StringBuilder> outputBuffers = new ConcurrentHashMap<>();

    @Override
    public Set<TaskType> getSupportedTypes() {
        return Set.of(TaskType.HTTP);
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

            HttpResponse<String> response = executeHttpRequest(task, options);

            output.append("HTTP ").append(response.statusCode()).append("\n");
            response.headers().map().forEach((key, values) ->
                    values.forEach(v -> output.append(key).append(": ").append(v).append("\n")));
            output.append("\n").append(response.body());

            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 400) {
                task.markSuccess(output.toString(), 0);
                eventPublisher.publishEvent(TaskEvent.completed(this, task));
            } else {
                task.markFailed(output.toString(), statusCode, "HTTP " + statusCode);
                eventPublisher.publishEvent(TaskEvent.failed(this, task));
            }

        } catch (Exception e) {
            log.error("HTTP task execution failed: {}", task.getId(), e);
            String errorMsg = e.getMessage();
            if (errorMsg == null && e.getCause() != null) {
                errorMsg = e.getCause().getMessage();
            }
            if (errorMsg == null) {
                errorMsg = e.getClass().getSimpleName();
            }
            task.markFailed(errorMsg);
            eventPublisher.publishEvent(TaskEvent.failed(this, task));
        } finally {
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

                if (cancelFlag.get()) {
                    task.markCancelled();
                    taskRepository.save(task);
                    eventPublisher.publishEvent(TaskEvent.cancelled(this, task));
                    return;
                }

                StringBuilder output = new StringBuilder();
                outputBuffers.put(task.getId(), output);

                HttpResponse<String> response = executeHttpRequest(task, options);

                output.append("HTTP ").append(response.statusCode()).append("\n");
                response.headers().map().forEach((key, values) ->
                        values.forEach(v -> output.append(key).append(": ").append(v).append("\n")));
                output.append("\n").append(response.body());

                int statusCode = response.statusCode();
                if (statusCode >= 200 && statusCode < 400) {
                    task.markSuccess(output.toString(), 0);
                    eventPublisher.publishEvent(TaskEvent.completed(this, task));
                } else {
                    task.markFailed(output.toString(), statusCode, "HTTP " + statusCode);
                    eventPublisher.publishEvent(TaskEvent.failed(this, task));
                }

                taskRepository.save(task);

            } catch (Exception e) {
                log.error("Async HTTP task execution failed: {}", task.getId(), e);
                task.markFailed(e.getMessage());
                taskRepository.save(task);
                eventPublisher.publishEvent(TaskEvent.failed(this, task));
            } finally {
                cancelFlags.remove(task.getId());
                outputBuffers.remove(task.getId());
            }
        });

        executorThread.setName("http-task-executor-" + task.getId());
        executorThread.start();
    }

    private HttpResponse<String> executeHttpRequest(TaskInstance task, TaskExecutionOptions options)
            throws Exception {
        // The command field stores the URL for HTTP tasks, but prefer httpUrl from definition metadata
        String url = task.getCommand();
        String method = "GET";
        String body = null;

        // Parse metadata for HTTP-specific fields
        if (task.getMetadataJson() != null && !task.getMetadataJson().isEmpty()) {
            try {
                Map<String, String> meta = objectMapper.readValue(task.getMetadataJson(),
                        new TypeReference<Map<String, String>>() {});
                if (meta.containsKey("httpUrl")) {
                    url = meta.get("httpUrl");
                }
                if (meta.containsKey("httpMethod")) {
                    method = meta.get("httpMethod");
                }
                if (meta.containsKey("httpBody")) {
                    body = meta.get("httpBody");
                }
            } catch (Exception e) {
                log.warn("Failed to parse HTTP metadata for task {}: {}", task.getId(), e.getMessage());
            }
        }

        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("HTTP URL is required for HTTP task type");
        }

        long timeoutSeconds = task.getTimeoutSeconds() != null ? task.getTimeoutSeconds() : 300;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        // Parse and apply headers
        if (task.getMetadataJson() != null) {
            try {
                Map<String, String> meta = objectMapper.readValue(task.getMetadataJson(),
                        new TypeReference<Map<String, String>>() {});
                String headersJson = meta.get("httpHeaders");
                if (headersJson != null && !headersJson.isEmpty()) {
                    Map<String, String> headers = objectMapper.readValue(headersJson,
                            new TypeReference<Map<String, String>>() {});
                    headers.forEach(requestBuilder::header);
                }
            } catch (Exception e) {
                log.warn("Failed to parse HTTP headers for task {}: {}", task.getId(), e.getMessage());
            }
        }

        // Set method and body
        HttpRequest.BodyPublisher bodyPublisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();

        switch (method.toUpperCase()) {
            case "POST":
                requestBuilder.POST(bodyPublisher);
                break;
            case "PUT":
                requestBuilder.PUT(bodyPublisher);
                break;
            case "DELETE":
                requestBuilder.DELETE();
                break;
            case "PATCH":
                requestBuilder.method("PATCH", bodyPublisher);
                break;
            default:
                requestBuilder.GET();
                break;
        }

        return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void cancel(Long taskInstanceId) {
        AtomicBoolean flag = cancelFlags.get(taskInstanceId);
        if (flag != null) {
            flag.set(true);
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
