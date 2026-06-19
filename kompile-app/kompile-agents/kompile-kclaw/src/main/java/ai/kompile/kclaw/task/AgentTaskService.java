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
package ai.kompile.kclaw.task;

import ai.kompile.chat.history.domain.ChatMessage;
import ai.kompile.chat.history.service.ChatHistoryService;
import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.model.KClawRequest;
import ai.kompile.kclaw.model.KClawResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs agents on tasks ("do work") and persists their output as retrievable artifacts.
 *
 * <p>Supports two engines per task ({@link TaskEngine}):
 * <ul>
 *   <li>{@link TaskEngine#REACT} — kclaw's native ReAct agents via {@link KClawAgentService}.</li>
 *   <li>{@link TaskEngine#KOMPILE_CLI} — the kompile CLI via {@link KompileCliRunner}.</li>
 * </ul>
 * Tasks run on a background pool by default; the result and a markdown artifact are saved via
 * {@link AgentTaskStore}.
 */
@Slf4j
public class AgentTaskService {

    private final KClawAgentService reactAgent; // nullable — absent when no LLM is configured
    private final KompileCliRunner kompileRunner;
    private final AgentTaskStore store;
    private final String defaultAgentId;
    private final ChatHistoryService chatHistory; // nullable — DB output destination
    private final ChannelManager channelManager;  // nullable — channel delivery
    private final ExecutorService executor;

    public AgentTaskService(KClawAgentService reactAgent,
                            KompileCliRunner kompileRunner,
                            AgentTaskStore store,
                            String defaultAgentId,
                            ChatHistoryService chatHistory,
                            ChannelManager channelManager) {
        this.reactAgent = reactAgent;
        this.kompileRunner = kompileRunner;
        this.store = store;
        this.defaultAgentId = defaultAgentId != null ? defaultAgentId : "jarvis";
        this.chatHistory = chatHistory;
        this.channelManager = channelManager;
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "kclaw-task-runner");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submit a task. Persists it as PENDING, then runs it (async by default) and returns the
     * current record. Poll {@link #get(String)} for completion.
     */
    public AgentTask submit(AgentTaskRequest req) {
        if (req == null || req.getTask() == null || req.getTask().isBlank()) {
            throw new IllegalArgumentException("'task' is required");
        }
        TaskEngine engine = TaskEngine.fromString(req.getEngine());
        AgentTask task = AgentTask.builder()
                .id(UUID.randomUUID().toString())
                .engine(engine)
                .agentId(engine == TaskEngine.REACT
                        ? (req.getAgentId() != null && !req.getAgentId().isBlank() ? req.getAgentId() : defaultAgentId)
                        : null)
                .task(req.getTask())
                .sessionKey(req.getSessionKey())
                .model(req.getModel())
                .channel(req.getChannel())
                .channelTarget(req.getChannelTarget())
                .status(AgentTask.Status.PENDING)
                .createdAt(System.currentTimeMillis())
                .build();
        store.save(task);

        boolean async = req.getAsync() == null || req.getAsync();
        if (async) {
            executor.submit(() -> run(task));
        } else {
            run(task);
        }
        return store.get(task.getId()).orElse(task);
    }

    private void run(AgentTask task) {
        task.setStatus(AgentTask.Status.RUNNING);
        task.setStartedAt(System.currentTimeMillis());
        store.save(task);
        try {
            if (task.getEngine() == TaskEngine.KOMPILE_CLI) {
                KompileCliRunner.Result r = kompileRunner.run(task.getTask(), task.getModel());
                task.setOutput(r.output());
                if (!r.success()) {
                    fail(task, r.error() != null ? r.error() : "kompile exec failed");
                    return;
                }
            } else {
                if (reactAgent == null) {
                    fail(task, "ReAct engine unavailable — configure an LLM provider, or use engine=kompile-cli");
                    return;
                }
                KClawRequest kr = KClawRequest.builder()
                        .agentId(task.getAgentId())
                        .sessionKey(task.getSessionKey())
                        .message(task.getTask())
                        .stream(false)
                        .build();
                KClawResponse resp = reactAgent.execute(kr);
                task.setOutput(resp.getResponse());
                if (task.getSessionKey() == null) {
                    task.setSessionKey(resp.getSessionKey());
                }
                if (!resp.isSuccess()) {
                    fail(task, resp.getError() != null ? resp.getError() : "agent execution failed");
                    return;
                }
            }
            task.setStatus(AgentTask.Status.SUCCEEDED);
            task.setFinishedAt(System.currentTimeMillis());
            writeArtifact(task);
            persistToHistory(task);
            deliverToChannel(task);
            store.save(task);
            log.info("Task {} ({}) succeeded", task.getId(), task.getEngine());
        } catch (Exception e) {
            log.error("Task {} failed", task.getId(), e);
            fail(task, e.getMessage());
        }
    }

    private void fail(AgentTask task, String error) {
        task.setStatus(AgentTask.Status.FAILED);
        task.setError(error);
        task.setFinishedAt(System.currentTimeMillis());
        writeArtifact(task);
        store.save(task);
    }

    private void writeArtifact(AgentTask task) {
        try {
            task.setOutputFile(store.writeOutput(task).toString());
        } catch (IOException e) {
            log.warn("Failed to write output artifact for task {}: {}", task.getId(), e.getMessage());
        }
    }

    /** Persist the result to the chat-history DB store (when available). */
    private void persistToHistory(AgentTask task) {
        if (chatHistory == null) {
            return;
        }
        try {
            String title = "Task: " + truncate(task.getTask(), 80);
            var session = chatHistory.createSession(title, "kclaw-task", null, "kclaw-task-" + task.getEngine());
            chatHistory.addMessage(session.getSessionId(), ChatMessage.MessageRole.USER, task.getTask(), null);
            chatHistory.addMessage(session.getSessionId(), ChatMessage.MessageRole.ASSISTANT,
                    task.getOutput() != null ? task.getOutput() : "", task.getModel());
            task.setDbSessionId(session.getSessionId());
        } catch (Exception e) {
            log.warn("Failed to persist task {} to chat history: {}", task.getId(), e.getMessage());
        }
    }

    /** Deliver the result to a channel (Discord/Slack/...) when requested and available. */
    private void deliverToChannel(AgentTask task) {
        if (channelManager == null || task.getChannel() == null || task.getChannel().isBlank()) {
            return;
        }
        if (task.getChannelTarget() == null || task.getChannelTarget().isBlank()) {
            log.warn("Task {} has channel '{}' but no channelTarget; skipping delivery",
                    task.getId(), task.getChannel());
            return;
        }
        channelManager.getAdapter(task.getChannel()).ifPresentOrElse(
                adapter -> {
                    try {
                        adapter.send(task.getChannelTarget(), task.getOutput() != null ? task.getOutput() : "");
                    } catch (Exception e) {
                        log.warn("Channel delivery failed for task {}: {}", task.getId(), e.getMessage());
                    }
                },
                () -> log.warn("No channel adapter '{}' for task {}", task.getChannel(), task.getId()));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public Optional<AgentTask> get(String id) {
        return store.get(id);
    }

    public List<AgentTask> list() {
        return store.list();
    }

    public boolean delete(String id) {
        return store.delete(id);
    }
}
