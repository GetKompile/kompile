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
import ai.kompile.cli.main.chat.tools.KompileBackendClient;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.ConnectException;
import java.time.Duration;
import java.util.Map;

/**
 * MCP tool: {@code ask_graph_explain}
 *
 * <p>Produce a derivation trace explaining why the KB believes (or disbelieves)
 * a specific fact via {@code POST /api/kb-grounding/explain}.</p>
 */
public class AskGraphExplainTool implements CliTool {

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;

    public AskGraphExplainTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() { return "ask_graph_explain"; }

    @Override
    public String description() {
        return "Produce a derivation trace explaining why the KB believes (or disbelieves) " +
                "a specific fact. Returns a derivation tree with rule applications and " +
                "supporting atoms at each hop, plus an NL summary. Use this to audit " +
                "an LLM's reasoning or to present grounded explanations to end users.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("atom")
                .put("type", "string")
                .put("description", "The atom key to explain, e.g. 'isEmployedBy(Alice, Acme)'.");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Fact sheet scope. Null = global.");
        props.putObject("depth")
                .put("type", "integer")
                .put("description", "Maximum derivation hops. Default: 3. Maximum: 5.")
                .put("default", 3);
        props.putObject("sessionId")
                .put("type", "string");

        schema.putArray("required").add("atom");
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_explain"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Explain KB derivation");

        String atom = params.path("atom").asText("");
        if (atom.isBlank()) {
            return ToolResult.error("atom is required");
        }

        if (!backend.isAvailable()) {
            return ToolResult.error("ask_graph_explain requires a running kompile-app.");
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("atom", atom);
            if (!params.path("factSheetId").isMissingNode()) body.set("factSheetId", params.get("factSheetId"));
            if (!params.path("depth").isMissingNode())       body.set("depth", params.get("depth"));
            if (!params.path("sessionId").isMissingNode())   body.set("sessionId", params.get("sessionId"));

            var resp = backend.post("/api/kb-grounding/explain",
                    objectMapper.writeValueAsString(body), Duration.ofSeconds(30));

            if (resp.statusCode() != 200) {
                return ToolResult.error("ask_graph_explain failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            String verdict  = result.path("verdict").asText("UNKNOWN");
            double conf     = result.path("confidence").asDouble(0.0);
            String summary  = result.path("summary").asText("");
            boolean stale   = result.path("meta").path("stale").asBoolean(false);

            StringBuilder sb = new StringBuilder();
            sb.append("**").append(verdict).append("** — ").append(atom);
            sb.append("\nConfidence: ").append(String.format("%.3f", conf));
            sb.append("\n\n").append(summary);
            if (stale) sb.append("\n\nWARNING: KB is pending a cascade update.");

            return ToolResult.success("ask_graph_explain: " + atom, sb.toString(),
                    Map.of("verdict", verdict, "confidence", conf));

        } catch (ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app. " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("ask_graph_explain error: " + e.getMessage());
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
