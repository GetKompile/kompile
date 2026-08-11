/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.app.services.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A2A-shaped adapter for app-managed bundles.
 *
 * <p>It deliberately delegates every task operation to {@link AgentBundleRunManager}; callers
 * cannot create a second execution path that would skip bundle validation, event persistence,
 * cancellation, or restart recovery.</p>
 */
@RestController
@RequestMapping("/api/agent-bundles")
public class AgentBundleA2AController {

    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final AgentBundleRunManager manager;

    public AgentBundleA2AController(AgentBundleRunManager manager) {
        this.manager = manager;
    }

    @GetMapping("/{bundleId}/agent-card.json")
    public ResponseEntity<?> agentCard(@PathVariable String bundleId) {
        return manager.getBundle(bundleId)
                .<ResponseEntity<?>>map(bundle -> ResponseEntity.ok(Map.of(
                        "name", bundle.name(),
                        "description", "Kompile managed agent bundle",
                        "version", "1",
                        "capabilities", Map.of("streaming", false),
                        "engine", bundle.engine(),
                        "bundleId", bundle.id())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{bundleId}/a2a")
    public ResponseEntity<Map<String, Object>> jsonRpc(
            @PathVariable String bundleId, @RequestBody JsonNode request) {
        Object id = jsonRpcId(request == null ? null : request.get("id"));
        if (request == null || !"2.0".equals(request.path("jsonrpc").asText())) {
            return ResponseEntity.ok(error(id, INVALID_REQUEST, "Invalid JSON-RPC request"));
        }
        String method = request.path("method").asText("");
        try {
            return switch (method) {
                case "message/send", "tasks/send" -> ResponseEntity.ok(
                        success(id, task(manager.startRun(bundleId, extractPrompt(request),
                                request.path("params").path("timeoutSeconds").asLong(0)))));
                case "tasks/get" -> manager.getRun(request.path("params").path("taskId").asText())
                        .map(run -> ResponseEntity.ok(success(id, task(run))))
                        .orElseGet(() -> ResponseEntity.ok(error(id, INVALID_PARAMS, "Task not found")));
                case "tasks/cancel" -> manager.cancelRun(request.path("params").path("taskId").asText())
                        .map(run -> ResponseEntity.ok(success(id, task(run))))
                        .orElseGet(() -> ResponseEntity.ok(error(id, INVALID_PARAMS, "Task not found")));
                default -> ResponseEntity.ok(error(id, METHOD_NOT_FOUND, "Unknown method: " + method));
            };
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(error(id, INVALID_PARAMS, String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.ok(error(id, INTERNAL_ERROR, String.valueOf(e.getMessage())));
        }
    }

    private static String extractPrompt(JsonNode request) {
        JsonNode params = request.path("params");
        JsonNode message = params.path("message");
        List<String> parts = new ArrayList<>();
        message.path("parts").forEach(part -> {
            if (part.has("text")) parts.add(part.path("text").asText());
        });
        if (parts.isEmpty() && message.has("text")) parts.add(message.path("text").asText());
        if (parts.isEmpty() && params.has("prompt")) parts.add(params.path("prompt").asText());
        return String.join("\n", parts).trim();
    }

    private static Map<String, Object> task(AgentBundleRunManager.RunSummary run) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", run.runId());
        task.put("contextId", run.bundleId());
        task.put("status", Map.of("state", a2aState(run.state().name()),
                "timestamp", run.startedAt().toString()));
        if (run.error() != null) task.put("error", run.error());
        task.put("metadata", Map.of("bundleId", run.bundleId(), "lastSequence", run.lastSequence()));
        return task;
    }

    private static String a2aState(String state) {
        return switch (state) {
            case "STARTING" -> "submitted";
            case "RUNNING" -> "working";
            case "COMPLETED" -> "completed";
            case "CANCELLED" -> "canceled";
            case "ORPHANED", "FAILED" -> "failed";
            default -> "unknown";
        };
    }

    private static Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    private static Object jsonRpcId(JsonNode id) {
        if (id == null || id.isNull()) return null;
        if (id.isIntegralNumber()) return id.longValue();
        if (id.isFloatingPointNumber()) return id.doubleValue();
        return id.asText();
    }
}
