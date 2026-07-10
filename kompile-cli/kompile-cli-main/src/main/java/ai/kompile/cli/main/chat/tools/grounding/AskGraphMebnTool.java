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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: {@code ask_graph_mebn}
 *
 * <p>Run Multi-Entity Bayesian Network (MEBN) inference over the knowledge graph,
 * anchored at a target node, via
 * {@code GET /api/attribution/bayesian/mebn/query?nodeId=...&maxDepth=...&maxNodes=...}.</p>
 *
 * <p>Returns the top-N variables by belief update (|posterior − prior|), with per-variable
 * prior → posterior delta, entity type, and the MFrag each variable belongs to.
 * Uses {@link ai.kompile.graph.reasoning.domain.BayesianInferenceResult} fields:
 * {@code posteriors}, {@code priors}, {@code variableToTitle}, and
 * {@code variableToMebnMeta}.</p>
 */
public class AskGraphMebnTool implements CliTool {

    /** Maximum number of variables shown in the formatted summary. */
    static final int MAX_VARIABLES_DISPLAY = 10;

    private final GroundingBackendClient groundingClient;
    private final ObjectMapper objectMapper;

    public AskGraphMebnTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = new GroundingBackendClient(baseUrl);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    AskGraphMebnTool(GroundingBackendClient groundingClient, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = groundingClient;
    }

    @Override
    public String compactHint() {
        return "Probabilistic network reasoning anchored at a graph node. "
                + "nodeId = graph node UUID — find it with knowledge_graph search_nodes or list_nodes. "
                + "Returns updated probability estimates for all related nodes reachable within maxDepth hops, "
                + "showing before/after probability deltas sorted by how much each changed. "
                + "factSheetId optional — discover via knowledge_graph list_fact_sheets; omit for the global graph. "
                + "Output: prior→posterior deltas for each related node; biggest deltas = most influenced by the anchor.";
    }

    @Override
    public String id() { return "ask_graph_mebn"; }

    @Override
    public String description() {
        return "Run probabilistic network reasoning over the knowledge graph anchored at a target node. " +
                "Returns updated probability estimates for all related variables reachable within maxDepth hops, " +
                "with before/after probability deltas, entity type, and the reasoning group each variable belongs to. " +
                "Use this to assess probabilistic risk, influence, or belief state for any knowledge graph entity. " +
                "Requires a running kompile-app.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("nodeId")
                .put("type", "string")
                .put("description", "KG node ID to anchor the reasoning subgraph. " +
                        "All variables reachable within maxDepth hops are included in inference.");
        props.putObject("maxDepth")
                .put("type", "integer")
                .put("description", "Maximum graph traversal depth from nodeId. Default: 3.")
                .put("default", 3);
        props.putObject("maxNodes")
                .put("type", "integer")
                .put("description", "Maximum number of nodes to include in the reasoning subgraph. Default: 100.")
                .put("default", 100);
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Optional fact sheet ID to scope MEBN inference. " +
                        "When provided, nodes discovered during BFS are filtered to those belonging " +
                        "to this fact sheet, preventing cross-sheet contamination. " +
                        "Omit to use the global/default graph. " +
                        "Use knowledge_graph action=list_fact_sheets to discover valid IDs.");

        schema.putArray("required").add("nodeId");
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_mebn"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Run probabilistic network inference");

        String nodeId = params.path("nodeId").asText("");
        if (nodeId.isBlank()) {
            return ToolResult.error("nodeId is required");
        }

        if (!groundingClient.isAvailable()) {
            return ToolResult.error("ask_graph_mebn requires a running kompile-app.");
        }

        int maxDepth = params.path("maxDepth").asInt(3);
        int maxNodes = params.path("maxNodes").asInt(100);
        long factSheetId = params.path("factSheetId").asLong(0);

        try {
            StringBuilder pathBuilder = new StringBuilder("/api/attribution/bayesian/mebn/query")
                    .append("?nodeId=").append(URLEncoder.encode(nodeId, StandardCharsets.UTF_8))
                    .append("&maxDepth=").append(maxDepth)
                    .append("&maxNodes=").append(maxNodes);
            if (factSheetId > 0) {
                pathBuilder.append("&factSheetId=").append(factSheetId);
            }
            String path = pathBuilder.toString();

            var resp = groundingClient.get(path);

            if (resp.statusCode() != 200) {
                return ToolResult.error("ask_graph_mebn failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            JsonNode posteriors       = result.path("posteriors");
            JsonNode priors           = result.path("priors");
            JsonNode variableToTitle  = result.path("variableToTitle");
            JsonNode variableToMebnMeta = result.path("variableToMebnMeta");
            long computationTimeMs    = result.path("computationTimeMs").asLong(0);

            int totalVars = posteriors.isObject() ? posteriors.size() : 0;

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("nodeId", nodeId);
            metadata.put("totalVariables", totalVars);
            metadata.put("computationTimeMs", computationTimeMs);
            if (factSheetId > 0) metadata.put("factSheetId", factSheetId);

            return ToolResult.success("ask_graph_mebn: " + nodeId,
                    formatMebnResult(nodeId, posteriors, priors, variableToTitle,
                            variableToMebnMeta, totalVars, computationTimeMs),
                    metadata);

        } catch (Exception e) {
            return ToolResult.error("ask_graph_mebn error: " + e.getMessage());
        }
    }

    // package-private to allow direct unit testing of the formatter
    String formatMebnResult(String nodeId, JsonNode posteriors, JsonNode priors,
                             JsonNode variableToTitle, JsonNode variableToMebnMeta,
                             int totalVars, long computationTimeMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Probabilistic network analysis anchored at node: ").append(nodeId);
        sb.append("\nTotal variables: ").append(totalVars);
        sb.append("\nComputation time: ").append(computationTimeMs).append(" ms");

        if (!posteriors.isObject() || posteriors.isEmpty()) {
            sb.append("\n\nNo variables found in subgraph.");
            return sb.toString();
        }

        // Sort variables by |posterior - prior| descending (highest belief update first)
        List<String> sortedVars = new ArrayList<>();
        posteriors.fieldNames().forEachRemaining(sortedVars::add);
        sortedVars.sort((a, b) -> {
            double postA  = posteriors.path(a).asDouble(0.0);
            double priorA = priors.path(a).asDouble(0.5);
            double postB  = posteriors.path(b).asDouble(0.0);
            double priorB = priors.path(b).asDouble(0.5);
            return Double.compare(Math.abs(postB - priorB), Math.abs(postA - priorA));
        });

        int cap = Math.min(sortedVars.size(), MAX_VARIABLES_DISPLAY);
        sb.append("\n\nTop ").append(cap);
        if (totalVars > cap) sb.append("/").append(totalVars);
        sb.append(" variables by belief update (prior→posterior):");

        for (int i = 0; i < cap; i++) {
            String var        = sortedVars.get(i);
            double posterior  = posteriors.path(var).asDouble(0.0);
            double prior      = priors.path(var).asDouble(0.5);
            String title      = variableToTitle.path(var).asText(var);

            sb.append("\n\n[").append(i + 1).append("] ").append(title);
            sb.append("\n    prior=").append(String.format("%.3f", prior))
              .append(" → posterior=").append(String.format("%.3f", posterior));
            double delta = posterior - prior;
            sb.append(" (Δ").append(delta >= 0 ? "+" : "").append(String.format("%.3f", delta)).append(")");

            if (variableToMebnMeta.isObject() && variableToMebnMeta.has(var)) {
                JsonNode meta      = variableToMebnMeta.path(var);
                String entityType  = meta.path("entityType").asText("");
                String mfragName   = meta.path("mfragName").asText("");
                String nodeRole    = meta.path("nodeRole").asText("");
                if (!entityType.isBlank()) sb.append("\n    entityType=").append(entityType);
                if (!mfragName.isBlank())  sb.append("  group=").append(mfragName);
                if (!nodeRole.isBlank())   sb.append("  role=").append(nodeRole);
            }
        }

        if (totalVars > cap) {
            sb.append("\n\n... and ").append(totalVars - cap)
              .append(" more variable(s) (increase maxNodes/maxDepth to expand subgraph).");
        }
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
