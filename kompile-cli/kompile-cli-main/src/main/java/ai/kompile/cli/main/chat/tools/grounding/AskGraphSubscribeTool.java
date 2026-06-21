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

/**
 * MCP tool: {@code ask_graph_subscribe}
 *
 * <p>Subscribe to KB changes matching a predicate pattern. Phase 2 — not available
 * in initial deployment. This tool always returns a documented "not-yet-implemented"
 * result. The agent can use {@code ask_graph_verify} with retry after
 * {@code meta.stalenessBudgetMs} as the polling alternative.</p>
 */
public class AskGraphSubscribeTool implements CliTool {

    private final ObjectMapper objectMapper;

    public AskGraphSubscribeTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() { return "ask_graph_subscribe"; }

    @Override
    public String description() {
        return "Subscribe to KB changes matching a predicate pattern. Returns a " +
                "subscriptionId. Changes are delivered via SSE. " +
                "Use this for cascade update notifications: after ask_graph_assert, " +
                "subscribe to a dependent atom to know when re-reasoning completes. " +
                "Phase 2 — not available in initial deployment. " +
                "Use ask_graph_verify with retry after meta.stalenessBudgetMs instead.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode predsNode = props.putObject("predicates");
        predsNode.put("type", "array");
        predsNode.putObject("items").put("type", "string");
        predsNode.put("description", "Predicate names to watch.");

        props.putObject("factSheetId")
                .put("type", "integer");
        props.putObject("sessionId")
                .put("type", "string");

        schema.putArray("required").add("predicates");
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_subscribe"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        // Phase 2 — documented not-yet-implemented response
        return ToolResult.error(
                "ask_graph_subscribe is not yet available (Phase 2). " +
                "Use ask_graph_verify with polling after meta.stalenessBudgetMs instead: " +
                "call ask_graph_assert, read meta.stale and meta.stalenessBudgetMs, " +
                "then re-call ask_graph_verify after the budget interval."
        );
    }
}
