/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.services.agent;

import ai.kompile.app.web.dto.AgentChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** Browser-facing transport adapter for the authoritative kompile-cli-main chat harness. */
public interface ChatHarnessClient {

    /** Start one asynchronous harness turn and return its cancellable run id. */
    String executeChat(AgentChatRequest request, SseEmitter emitter);

    /** Cancel an exact run owned by this client. */
    boolean cancel(String runId);

    /** Write a control to an exact active run; execution is acknowledged separately over SSE. */
    default Map<String, Object> control(String runId, JsonNode frame) {
        return Map.of("accepted", false, "message", "Live controls unavailable");
    }

    /** Attach to buffered/live events of an existing run; never starts model work. */
    default void reconnect(String runId, long after, SseEmitter emitter) {
        throw new IllegalStateException("Run replay unavailable");
    }

    /** Non-secret harness/provider/persona capabilities for a project directory. */
    JsonNode capabilities(String workingDirectory, boolean refresh);

    default JsonNode capabilities(String workingDirectory, boolean refresh, String sessionId) {
        return capabilities(workingDirectory, refresh);
    }

    /**
     * Quiet session-configuration snapshot (model / role / fast / reminders /
     * loops / queue menus) resolved headlessly through the CLI. Sends no chat
     * messages and writes no transcript entries. {@code modelVendor} scopes
     * the model section to one vendor's models (quiet vendor browsing).
     */
    default JsonNode configSnapshot(String browserSessionId, String workingDirectory,
                                    String modelVendor) {
        throw new IllegalStateException("Session configuration snapshot unavailable");
    }

    /**
     * Approves a gate of the workflow team a browser session recorded, between runs, as
     * {@code /workflow approve} does in the terminal; an empty {@code gate} approves the one
     * that blocks next. Returns {@code ok} and a {@code message}, and on success the
     * {@code workflow}, the {@code gate} approved and every gate {@code approved} so far.
     */
    default Map<String, Object> approveWorkflowGate(String browserSessionId, String workingDirectory,
                                                    String gate) {
        throw new IllegalStateException("Workflow gate approval unavailable");
    }

    /**
     * The session's insights as the terminal's dashboard area shows them (judge flags, tool
     * counts and latency, the last test milestone, crawl progress), read headlessly through the
     * CLI. Starts no model work and writes no transcript entry. Returns the rows ({@code lines},
     * {@code live}, ...) or {@code available:false} with a {@code status}.
     */
    default JsonNode insightsSnapshot(String browserSessionId, String workingDirectory) {
        throw new IllegalStateException("Session insights unavailable");
    }

    /**
     * One topic's insights report over every session, as the CLI's {@code insights} tool answers it
     * ({@code headline}, {@code text}, optional {@code chart}), read headlessly through the CLI. For
     * the topics only the CLI reads, such as the project's crawls and graphs, and the overview.
     * Starts no model work and writes no transcript entry. Returns {@code available:false} with a
     * {@code status} when the CLI cannot answer.
     *
     * @throws IllegalArgumentException when the topic or question is invalid, or the CLI knows no
     *                                  such topic
     */
    default JsonNode insightsReport(String topic, String question, String workingDirectory) {
        throw new IllegalStateException("Insights reports unavailable");
    }

    /** Quiet wizard operation; credentials are supplied on stdin, never process arguments. */
    default JsonNode setupChat(String workingDirectory, JsonNode payload) {
        throw new IllegalStateException("Chat setup unavailable");
    }

    /** Model context budget projected from {@link #capabilities}. */
    Map<String, Object> contextBudget(String agentName, String workingDirectory);
}
