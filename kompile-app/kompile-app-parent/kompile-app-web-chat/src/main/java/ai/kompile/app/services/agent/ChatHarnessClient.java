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

    /** Non-secret harness/provider/persona capabilities for a project directory. */
    JsonNode capabilities(String workingDirectory, boolean refresh);

    /** Model context budget projected from {@link #capabilities}. */
    Map<String, Object> contextBudget(String agentName, String workingDirectory);
}
