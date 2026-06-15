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

package ai.kompile.a2a.server;

import ai.kompile.a2a.model.*;
import ai.kompile.a2a.model.A2ATask.TaskState;
import ai.kompile.a2a.model.A2ATask.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Bridges kompile's agent execution (subprocess or API) into A2A protocol tasks.
 * <p>
 * Each incoming {@code message/send} or {@code message/stream} JSON-RPC call is
 * converted into an {@link A2ATask}, dispatched to the configured agent backend
 * (via the supplied {@link AgentBackend}), and the result is packaged as A2A
 * artifacts.
 */
public class KompileAgentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(KompileAgentExecutor.class);

    private final ConcurrentMap<String, A2ATask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Future<?>> runningFutures = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "a2a-executor");
        t.setDaemon(true);
        return t;
    });

    /**
     * Functional interface that delegates actual agent work.
     * The first argument is the user prompt text, the second receives streaming chunks.
     * Returns the final complete response text.
     */
    @FunctionalInterface
    public interface AgentBackend {
        String execute(String agentName, String prompt, BiConsumer<String, Boolean> chunkConsumer) throws Exception;
    }

    private final AgentBackend backend;
    private final String defaultAgentName;

    public KompileAgentExecutor(AgentBackend backend, String defaultAgentName) {
        this.backend = backend;
        this.defaultAgentName = defaultAgentName;
    }

    /**
     * Handle a synchronous {@code message/send} — blocks until the agent completes.
     */
    public A2ATask handleMessageSend(A2AMessage message, String contextId) {
        String taskId = UUID.randomUUID().toString();
        String ctx = contextId != null ? contextId : UUID.randomUUID().toString();

        A2ATask task = A2ATask.builder()
                .id(taskId)
                .contextId(ctx)
                .status(TaskStatus.builder()
                        .state(TaskState.SUBMITTED)
                        .timestamp(Instant.now())
                        .build())
                .history(message)
                .build();
        tasks.put(taskId, task);

        String prompt = extractTextFromMessage(message);
        logger.info("A2A message/send: taskId={}, prompt length={}", taskId, prompt.length());

        task.getStatus().setState(TaskState.WORKING);
        task.getStatus().setTimestamp(Instant.now());

        try {
            String response = backend.execute(defaultAgentName, prompt, null);

            A2AArtifact artifact = A2AArtifact.builder()
                    .name("response")
                    .parts(List.of(A2AMessage.Part.text(response)))
                    .index(0)
                    .build();

            task.setArtifacts(List.of(artifact));
            task.getStatus().setState(TaskState.COMPLETED);
            task.getStatus().setTimestamp(Instant.now());
        } catch (Exception e) {
            logger.error("A2A task {} failed", taskId, e);
            task.getStatus().setState(TaskState.FAILED);
            task.getStatus().setMessage(e.getMessage());
            task.getStatus().setTimestamp(Instant.now());
        }

        return task;
    }

    /**
     * Handle a streaming {@code message/stream} — sends SSE events as the agent produces output.
     */
    public void handleMessageStream(A2AMessage message, String contextId, SseEmitter emitter) {
        String taskId = UUID.randomUUID().toString();
        String ctx = contextId != null ? contextId : UUID.randomUUID().toString();

        A2ATask task = A2ATask.builder()
                .id(taskId)
                .contextId(ctx)
                .status(TaskStatus.builder()
                        .state(TaskState.SUBMITTED)
                        .timestamp(Instant.now())
                        .build())
                .history(message)
                .build();
        tasks.put(taskId, task);

        String prompt = extractTextFromMessage(message);
        logger.info("A2A message/stream: taskId={}, prompt length={}", taskId, prompt.length());

        Future<?> future = executor.submit(() -> {
            try {
                // Send initial status
                task.getStatus().setState(TaskState.WORKING);
                task.getStatus().setTimestamp(Instant.now());
                sendSseEvent(emitter, "status", Map.of(
                        "taskId", taskId,
                        "contextId", ctx,
                        "state", TaskState.WORKING.name(),
                        "timestamp", Instant.now().toString()
                ));

                StringBuilder fullResponse = new StringBuilder();
                int chunkIndex = 0;

                String response = backend.execute(defaultAgentName, prompt, (chunk, isFinal) -> {
                    fullResponse.append(chunk);
                    sendSseEvent(emitter, "artifact", Map.of(
                            "taskId", taskId,
                            "artifact", Map.of(
                                    "parts", List.of(Map.of("type", "text", "text", chunk)),
                                    "append", true,
                                    "lastChunk", isFinal
                            )
                    ));
                });

                // If backend didn't use chunk consumer, send full response as single artifact
                if (fullResponse.length() == 0 && response != null) {
                    sendSseEvent(emitter, "artifact", Map.of(
                            "taskId", taskId,
                            "artifact", Map.of(
                                    "parts", List.of(Map.of("type", "text", "text", response)),
                                    "index", 0,
                                    "lastChunk", true
                            )
                    ));
                }

                task.setArtifacts(List.of(A2AArtifact.builder()
                        .name("response")
                        .parts(List.of(A2AMessage.Part.text(
                                fullResponse.length() > 0 ? fullResponse.toString() : response)))
                        .build()));

                task.getStatus().setState(TaskState.COMPLETED);
                task.getStatus().setTimestamp(Instant.now());
                sendSseEvent(emitter, "status", Map.of(
                        "taskId", taskId,
                        "state", TaskState.COMPLETED.name(),
                        "timestamp", Instant.now().toString()
                ));

                emitter.complete();
            } catch (Exception e) {
                logger.error("A2A stream task {} failed", taskId, e);
                task.getStatus().setState(TaskState.FAILED);
                task.getStatus().setMessage(e.getMessage());
                task.getStatus().setTimestamp(Instant.now());
                try {
                    sendSseEvent(emitter, "status", Map.of(
                            "taskId", taskId,
                            "state", TaskState.FAILED.name(),
                            "message", e.getMessage(),
                            "timestamp", Instant.now().toString()
                    ));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            } finally {
                runningFutures.remove(taskId);
            }
        });

        runningFutures.put(taskId, future);
    }

    /**
     * Get a task by ID.
     */
    public A2ATask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * Cancel a running task.
     */
    public boolean cancelTask(String taskId) {
        A2ATask task = tasks.get(taskId);
        if (task == null) return false;

        TaskState state = task.getStatus().getState();
        if (state == TaskState.COMPLETED || state == TaskState.CANCELED || state == TaskState.FAILED) {
            return false;
        }

        Future<?> future = runningFutures.remove(taskId);
        if (future != null) {
            future.cancel(true);
        }

        task.getStatus().setState(TaskState.CANCELED);
        task.getStatus().setTimestamp(Instant.now());
        return true;
    }

    /**
     * List all tasks, optionally filtered by context.
     */
    public List<A2ATask> listTasks(String contextId) {
        if (contextId == null) {
            return List.copyOf(tasks.values());
        }
        return tasks.values().stream()
                .filter(t -> contextId.equals(t.getContextId()))
                .toList();
    }

    private String extractTextFromMessage(A2AMessage message) {
        if (message == null || message.getParts() == null) return "";
        return message.getParts().stream()
                .filter(p -> "text".equals(p.getType()) && p.getText() != null)
                .map(A2AMessage.Part::getText)
                .reduce("", (a, b) -> a + "\n" + b)
                .trim();
    }

    private void sendSseEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (Exception e) {
            logger.warn("Failed to send SSE event '{}': {}", eventName, e.getMessage());
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
