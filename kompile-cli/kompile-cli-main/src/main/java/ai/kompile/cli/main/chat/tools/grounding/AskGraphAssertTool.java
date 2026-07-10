/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * MCP tool: {@code ask_graph_assert}
 *
 * <p>Assert a new fact into the knowledge base from agent output via
 * {@code POST /api/kb-grounding/assert}. Contradiction-checking runs
 * synchronously before returning. Background re-reasoning cascades asynchronously —
 * the 'stale' meta flag in subsequent verify/query calls will be true until the
 * cascade completes.</p>
 */
public class AskGraphAssertTool implements CliTool {

    private final GroundingBackendClient groundingClient;
    private final ObjectMapper objectMapper;

    public AskGraphAssertTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = new GroundingBackendClient(baseUrl);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    AskGraphAssertTool(GroundingBackendClient groundingClient, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = groundingClient;
    }

    @Override
    public String id() { return "ask_graph_assert"; }

    @Override
    public String description() {
        return "Assert a new fact into the knowledge base from agent output. " +
                "The fact is attributed to the calling agent session (provenance). " +
                "Contradiction-checking (TMS) runs synchronously before returning. " +
                "Background re-reasoning cascades asynchronously — the 'stale' meta " +
                "flag in subsequent verify/query calls will be true until the cascade " +
                "completes. For optimistic-concurrency: supply expectedVersion from " +
                "a prior verify or query response's meta.kbVersion.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("atom")
                .put("type", "string")
                .put("description", "Atom key to assert, e.g. 'isEmployedBy(Alice, Acme)'.");
        props.putObject("value")
                .put("type", "number")
                .put("description", "Soft-truth value [0,1]. Use 1.0 for hard facts, " +
                        "0.0 to explicitly retract/refute. Values in (0,1) are probabilistic.");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Target fact sheet (required for assert).");
        props.putObject("sessionId")
                .put("type", "string")
                .put("description", "Agent session — stored as provenance.");
        props.putObject("source")
                .put("type", "string")
                .put("description", "Human-readable provenance label, e.g. 'agent-extraction:run-42'.");
        props.putObject("expectedVersion")
                .put("type", "integer")
                .put("description", "If supplied, the assert is rejected with CONFLICT if the KB " +
                        "has been modified since this version (optimistic locking). " +
                        "Use meta.kbVersion from a prior query/verify response.");

        schema.putArray("required").add("atom").add("value");
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_assert"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Assert KB fact");

        String atom = params.path("atom").asText("");
        if (atom.isBlank()) {
            return ToolResult.error("atom is required");
        }
        if (params.path("value").isMissingNode()) {
            return ToolResult.error("value is required");
        }
        double value = params.path("value").asDouble(Double.NaN);
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            return ToolResult.error("value must be a number in [0,1]");
        }

        if (!groundingClient.isAvailable()) {
            return ToolResult.error("ask_graph_assert requires a running kompile-app.");
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("atom", atom);
            body.put("value", value);
            if (!params.path("factSheetId").isMissingNode())     body.set("factSheetId", params.get("factSheetId"));
            if (!params.path("sessionId").isMissingNode())       body.set("sessionId", params.get("sessionId"));
            if (!params.path("source").isMissingNode())          body.set("source", params.get("source"));
            if (!params.path("expectedVersion").isMissingNode()) body.set("expectedVersion", params.get("expectedVersion"));

            var resp = groundingClient.post("/api/kb-grounding/assert",
                    objectMapper.writeValueAsString(body));

            if (resp.statusCode() != 200) {
                return ToolResult.error("ask_graph_assert failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            String status     = result.path("status").asText("UNKNOWN");
            long version      = result.path("version").asLong(-1L);
            boolean cascade   = result.path("cascadeTriggered").asBoolean(false);
            boolean stale     = result.path("meta").path("stale").asBoolean(false);
            long staleBudgetMs = result.path("meta").path("stalenessBudgetMs").asLong(0L);

            StringBuilder sb = new StringBuilder();
            sb.append("**").append(status).append("** — ").append(atom);
            sb.append("\nKB version: ").append(version);
            if (cascade && stale) {
                sb.append("\nBackground cascade triggered. KB is stale — retry in ")
                        .append(staleBudgetMs).append("ms if needed.");
            }
            JsonNode contras = result.path("contradictions");
            if (contras.isArray() && contras.size() > 0) {
                sb.append("\nContradictions detected:");
                contras.forEach(c -> sb.append("\n  - ").append(c.asText()));
            }

            return ToolResult.success("ask_graph_assert: " + atom, sb.toString(),
                    Map.of("status", status, "version", version, "stale", stale));

        } catch (Exception e) {
            return ToolResult.error("ask_graph_assert error: " + e.getMessage());
        }
    }

    private String extractError(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String msg = json.path("message").asText(null);
            if (msg != null) return msg;
            msg = json.path("error").asText(null);
            if (msg != null) return msg;
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
