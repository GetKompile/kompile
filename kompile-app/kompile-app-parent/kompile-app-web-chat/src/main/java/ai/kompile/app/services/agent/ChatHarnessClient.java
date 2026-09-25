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

    /** Model context budget projected from {@link #capabilities}. */
    Map<String, Object> contextBudget(String agentName, String workingDirectory);
}
