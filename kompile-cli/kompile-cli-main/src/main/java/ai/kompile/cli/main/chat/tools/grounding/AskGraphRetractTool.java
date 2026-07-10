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
 * MCP tool: {@code ask_graph_retract}
 *
 * <p>True TMS retraction of a fact from the knowledge base via
 * {@code POST /api/kb-grounding/retract}. Unlike {@code ask_graph_assert} with
 * {@code value=0.0} (which performs a soft overwrite to zero), this tool physically removes
 * the atom key from the observed fact store and returns a full dependency analysis:
 * which atoms lost all support ({@code dependentAtomsUnsupported}) and which were
 * weakened but retain other support ({@code dependentAtomsWeakened}).
 * A background re-reasoning cascade is always triggered after a true retraction.</p>
 *
 * <h3>When to use this vs. {@code ask_graph_assert} with {@code value=0.0}</h3>
 * <ul>
 *   <li>{@code ask_graph_retract} — use when correctness matters: the atom is physically
 *       removed, dependent atoms are identified, and downstream queries will no longer see it.
 *       The cascade re-reasons after the retraction.</li>
 *   <li>{@code ask_graph_assert value=0.0} — soft overwrite: the key stays at 0.0 rather
 *       than being removed. No dependency analysis. Use for quick probabilistic downgrades.</li>
 * </ul>
 */
public class AskGraphRetractTool implements CliTool {

    private final GroundingBackendClient groundingClient;
    private final ObjectMapper objectMapper;

    public AskGraphRetractTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = new GroundingBackendClient(baseUrl);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    AskGraphRetractTool(GroundingBackendClient groundingClient, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = groundingClient;
    }

    @Override
    public String id() { return "ask_graph_retract"; }

    @Override
    public String description() {
        return "True TMS retraction: physically removes an atom from the knowledge base and " +
                "returns which dependent atoms became unsupported or weakened. A background " +
                "re-reasoning cascade is always triggered. " +
                "Use mode='retract' (default) for single-atom retraction; " +
                "mode='revise' additionally removes sole-dependent atoms synchronously. " +
                "Prefer this over ask_graph_assert(value=0.0) when dependency correctness matters.";
    }

    @Override
    public String compactHint() {
        return "Physically remove an atom from the KB; returns which dependent atoms became unsupported or weakened. " +
                "Triggers re-reasoning. Use mode=revise for synchronous propagation. " +
                "factSheetId optional — omit to use the active sheet; discover ids via knowledge_graph list_fact_sheets.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("atomKey")
                .put("type", "string")
                .put("description", "Atom key to retract, e.g. 'trusts(Alice, Bob)'.");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Fact sheet to retract from. Optional — omit to use the active sheet; " +
                        "discover ids via knowledge_graph list_fact_sheets.");
        props.putObject("mode")
                .put("type", "string")
                .put("description", "Retraction mode: 'retract' (default) = retraction + dependency " +
                        "analysis, re-reasoning runs in background; 'revise' = also removes sole-dependent atoms " +
                        "before returning.");

        schema.putArray("required").add("atomKey");
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_retract"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Retract KB fact");

        String atomKey = params.path("atomKey").asText("").trim();
        if (atomKey.isBlank()) {
            return ToolResult.error("atomKey is required");
        }

        if (!groundingClient.isAvailable()) {
            return ToolResult.error("ask_graph_retract requires a running kompile-app.");
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("atomKey", atomKey);
            if (!params.path("factSheetId").isMissingNode() && !params.path("factSheetId").isNull()) {
                body.set("factSheetId", params.get("factSheetId"));
            }
            if (!params.path("mode").isMissingNode()) {
                body.set("mode", params.get("mode"));
            }

            var resp = groundingClient.post("/api/kb-grounding/retract",
                    objectMapper.writeValueAsString(body));

            if (resp.statusCode() != 200) {
                return ToolResult.error("ask_graph_retract failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            String status     = result.path("status").asText("UNKNOWN");
            String modeUsed   = result.path("mode").asText("retract");
            boolean cascade   = result.path("cascadeTriggered").asBoolean(false);

            StringBuilder sb = new StringBuilder();
            sb.append("**").append(status).append("** — ").append(atomKey);
            sb.append("\nMode: ").append(modeUsed);

            JsonNode unsupported = result.path("dependentAtomsUnsupported");
            if (unsupported.isArray() && unsupported.size() > 0) {
                sb.append("\nAtoms that lost all support (").append(unsupported.size()).append("):");
                unsupported.forEach(a -> sb.append("\n  - ").append(a.asText()));
            }
            JsonNode weakened = result.path("dependentAtomsWeakened");
            if (weakened.isArray() && weakened.size() > 0) {
                sb.append("\nAtoms weakened (").append(weakened.size()).append("):");
                weakened.forEach(a -> sb.append("\n  - ").append(a.asText()));
            }
            if (cascade) {
                sb.append("\nBackground re-reasoning cascade triggered.");
            }

            return ToolResult.success("ask_graph_retract: " + atomKey, sb.toString(),
                    Map.of("status", status, "mode", modeUsed,
                            "unsupportedCount", unsupported.size(),
                            "weakenedCount", weakened.size(),
                            "cascadeTriggered", cascade));

        } catch (Exception e) {
            return ToolResult.error("ask_graph_retract error: " + e.getMessage());
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
