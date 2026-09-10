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
import ai.kompile.cli.main.chat.tools.OfflineToolRuntime;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * MCP tool: {@code ask_graph_query}
 *
 * <p>Conjunctive pattern query against the knowledge base via
 * {@code POST /api/kb-grounding/query}. Returns binding rows with per-row
 * confidence (Lukasiewicz T-norm).</p>
 */
public class AskGraphQueryTool implements CliTool {

    private final GroundingBackendClient groundingClient;
    private final ObjectMapper objectMapper;

    public AskGraphQueryTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = new GroundingBackendClient(baseUrl);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    AskGraphQueryTool(GroundingBackendClient groundingClient, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = groundingClient;
    }

    @Override
    public String compactHint() {
        return "Conjunctive pattern query: find all ?-variable bindings matching a set of predicates. "
                + "CRITICAL: prefix variables with '?' or they are treated as constants (zero bindings). "
                + "Example — who works for Acme: conjuncts=[{\"predicate\":\"worksFor\",\"args\":[\"?x\",\"Acme\"]}] "
                + "returns rows like {x: \"Alice\", confidence: 0.91}. "
                + "Multi-conjunct: [{\"predicate\":\"worksFor\",\"args\":[\"?x\",\"?org\"]},{\"predicate\":\"locatedIn\",\"args\":[\"?org\",\"London\"]}] "
                + "finds all people whose employer is in London. "
                + "Local stdio queries the current folder; factSheetId is an optional remote/legacy override. "
                + "minConfidence default 0.3; raise to 0.7+ to see only well-supported facts. "
                + "Zero results? Check predicate names with knowledge_graph list_predicates.";
    }

    @Override
    public String id() { return "ask_graph_query"; }

    @Override
    public String description() {
        return "Conjunctive pattern query against the knowledge base. Each conjunct " +
                "is a predicate pattern with '?'-prefixed variables and ground constants. " +
                "Returns all variable binding rows (up to maxResults) with per-row " +
                "minimum confidence (Lukasiewicz T-norm). Variables MUST use '?' prefix " +
                "to distinguish them from entity names which may start with uppercase.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode conjuncts = props.putObject("conjuncts");
        conjuncts.put("type", "array");
        conjuncts.put("description", "Ordered list of atom patterns forming the conjunctive query. " +
                "Each item: {predicate: string, args: [string,...]}.");
        conjuncts.put("minItems", 1);
        ObjectNode items = conjuncts.putObject("items");
        items.put("type", "object");
        ObjectNode itemProps = items.putObject("properties");
        itemProps.putObject("predicate").put("type", "string")
                .put("description", "Predicate name, e.g. 'worksFor'. Case-sensitive.");
        ObjectNode argsNode = itemProps.putObject("args");
        argsNode.put("type", "array");
        argsNode.putObject("items").put("type", "string");
        argsNode.put("description", "Arguments: use '?Name' for variables, bare string for constants.");
        items.putArray("required").add("predicate").add("args");

        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Optional remote/legacy graph selector; omit locally to use the current folder's knowledge base.");
        props.putObject("asOf")
                .put("type", "string")
                .put("description", "ISO-8601 snapshot time. Absent = current truth.");
        props.putObject("validAt")
                .put("type", "string")
                .put("description", "Local only: ISO-8601 valid-time filter over retained facts. Timeless facts included; does not reconstruct deleted history. Cannot combine with asOf.");
        props.putObject("maxWork")
                .put("type", "integer").put("minimum", 1).put("maximum", 1000000)
                .put("description", "Local only: relation-examination budget (default 100000). Exhaustion errors explicitly; never silently truncates joins.");
        props.putObject("maxResults")
                .put("type", "integer")
                .put("description", "Maximum binding rows returned. Default 50.");
        props.putObject("minConfidence")
                .put("type", "number")
                .put("description", "Filter rows with confidence >= this value. Default 0.3.");
        props.putObject("sessionId")
                .put("type", "string")
                .put("description", "Agent session ID for tracking.");

        schema.putArray("required").add("conjuncts");
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_query"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Query knowledge base");

        JsonNode conjunctsNode = params.path("conjuncts");
        if (!conjunctsNode.isArray() || conjunctsNode.isEmpty()) {
            return ToolResult.error("conjuncts array is required and must not be empty");
        }

        if (!groundingClient.isAvailable()) {
            return OfflineToolRuntime.execute(id(), params, context, objectMapper);
        }

        if (params.hasNonNull("validAt") || params.hasNonNull("maxWork")) {
            return ToolResult.error("validAt/maxWork are supported only by the project-local backend; no remote request was sent.");
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.set("conjuncts", conjunctsNode);
            if (!params.path("factSheetId").isMissingNode())   body.set("factSheetId", params.get("factSheetId"));
            if (!params.path("asOf").isMissingNode())          body.set("asOf", params.get("asOf"));
            if (!params.path("maxResults").isMissingNode())    body.set("maxResults", params.get("maxResults"));
            if (!params.path("minConfidence").isMissingNode()) body.set("minConfidence", params.get("minConfidence"));
            if (!params.path("sessionId").isMissingNode())     body.set("sessionId", params.get("sessionId"));

            var resp = groundingClient.post("/api/kb-grounding/query",
                    objectMapper.writeValueAsString(body));

            if (resp.statusCode() != 200) {
                return ToolResult.error("ask_graph_query failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            int total    = result.path("total").asInt(0);
            boolean trunc = result.path("truncated").asBoolean(false);
            JsonNode rows = result.path("bindings");
            boolean stale = result.path("meta").path("stale").asBoolean(false);

            return ToolResult.success("ask_graph_query: " + total + " binding(s)",
                    formatQueryResult(rows, total, trunc, stale),
                    Map.of("total", total, "truncated", trunc));

        } catch (Exception e) {
            return ToolResult.error("ask_graph_query error: " + e.getMessage());
        }
    }

    private String formatQueryResult(JsonNode rows, int total, boolean truncated, boolean stale) {
        StringBuilder sb = new StringBuilder();
        sb.append("KB Query Results: ").append(total).append(" binding(s)");
        if (truncated) sb.append(" (truncated — add more conjuncts to narrow)");
        sb.append("\n");
        if (rows.isArray()) {
            int idx = 0;
            for (JsonNode row : rows) {
                idx++;
                sb.append("\n[").append(idx).append("] confidence=")
                        .append(String.format("%.3f", row.path("confidence").asDouble()));
                // GAP 1: prefer displayVariables (human titles resolved server-side) over raw variables
                JsonNode displayVars = row.path("displayVariables");
                JsonNode rawVars = row.path("variables");
                // Use displayVariables when present and non-empty; fall back to variables
                JsonNode vars = (displayVars.isObject() && !displayVars.isEmpty()) ? displayVars : rawVars;
                vars.fieldNames().forEachRemaining(var ->
                        sb.append("\n    ").append(var).append(" = ").append(vars.path(var).asText()));
            }
        }
        if (total == 0) sb.append("No bindings found.");
        if (stale) sb.append("\nWARNING: KB is pending a cascade update.");
        return sb.toString();
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
