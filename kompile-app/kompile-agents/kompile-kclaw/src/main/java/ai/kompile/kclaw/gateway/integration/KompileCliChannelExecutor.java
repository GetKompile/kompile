/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import ai.kompile.gateway.core.model.AgentRequest;
import ai.kompile.gateway.core.model.AgentResponse;
import ai.kompile.gateway.core.service.AgentExecutor;
import ai.kompile.gateway.core.service.SessionService;
import ai.kompile.kclaw.task.KompileCliRunner;
import ai.kompile.react.model.ReActMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Runs inbound channel turns through {@code kompile exec --json} with persisted history. */
public final class KompileCliChannelExecutor implements AgentExecutor {

    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int MAX_PROMPT_CHARS = 64_000;

    private final KompileCliRunner runner;
    private final SessionService sessions;
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public KompileCliChannelExecutor(KompileCliRunner runner, SessionService sessions) {
        this.runner = runner;
        this.sessions = sessions;
    }

    public boolean isAvailable() {
        return runner.isAvailable();
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        String sessionKey = request.getSessionKey();
        Object lock = sessionLocks.computeIfAbsent(sessionKey, ignored -> new Object());
        synchronized (lock) {
            List<ReActMessage> history = sessions.loadSession(sessionKey);
            String prompt = renderPrompt(history, request.getMessage());
            String model = request.getMetadata() == null
                    ? null
                    : string(request.getMetadata().get("model"));
            KompileCliRunner.Result result = runner.run(prompt, model);
            if (!result.success()) {
                return AgentResponse.error(result.error());
            }
            sessions.appendMessage(sessionKey, ReActMessage.user(request.getMessage()));
            sessions.appendMessage(sessionKey, ReActMessage.assistant(result.output()));
            return AgentResponse.builder()
                    .success(true)
                    .response(result.output())
                    .sessionKey(sessionKey)
                    .agentId(request.getAgentId())
                    .timestamp(Instant.now())
                    .build();
        }
    }

    static String renderPrompt(List<ReActMessage> history, String currentMessage) {
        StringBuilder prompt = new StringBuilder();
        List<String> retained = new java.util.ArrayList<>();
        int remaining = Math.max(0, MAX_PROMPT_CHARS
                - currentMessage.length() - 128);
        int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        for (int index = history.size() - 1; index >= from && remaining > 0; index--) {
            ReActMessage message = history.get(index);
            if (message.getContent() == null || message.getContent().isBlank()) continue;
            String prefix = switch (message.getRole()) {
                case USER -> "User: ";
                case ASSISTANT -> "Assistant: ";
                case SYSTEM -> "System: ";
                case TOOL -> "Tool: ";
            };
            int contentLimit = Math.max(0, remaining - prefix.length() - 1);
            String content = message.getContent();
            if (content.length() > contentLimit) {
                content = content.substring(content.length() - contentLimit);
            }
            String line = prefix + content + '\n';
            retained.add(0, line);
            remaining -= line.length();
        }
        if (!retained.isEmpty()) {
            prompt.append("Continue this conversation consistently.\n\nConversation history:\n");
            retained.forEach(prompt::append);
            prompt.append('\n');
        }
        return prompt.append("User: ").append(currentMessage).toString();
    }

    private static String string(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
