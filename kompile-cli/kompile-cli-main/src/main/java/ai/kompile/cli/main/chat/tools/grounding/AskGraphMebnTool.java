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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool: {@code ask_graph_mebn}
 *
 * <p>Run Multi-Entity Bayesian Network (MEBN) inference over the knowledge graph,
 * anchored at a target node, via
 * {@code GET /api/attribution/bayesian/mebn/query?nodeId=...&maxDepth=...&maxNodes=...}, or
 * {@code POST} to the same path when evidence is given.</p>
 *
 * <p>Evidence maps grounded variable names, as returned in {@code posteriors}, to an observed
 * state. Without it every posterior equals its prior: the result is the model's probability for
 * each variable around the anchor, which only selects the variables. Inference ignores a name the
 * network does not contain, so both paths reject one rather than report priors as conditioned.
 * The formatter orders by |posterior − prior| first, then by posterior, with entity type and the
 * MFrag each variable belongs to.
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
        return "Model probabilities for the variables around a graph node (nodeId from knowledge_graph search_nodes). "
                + "Pass evidence {variable: true|false} to update them; without it prior = posterior.";
    }

    @Override
    public String id() { return "ask_graph_mebn"; }

    @Override
    public String description() {
        return "Probabilities from the knowledge graph's probabilistic model for the variables around one graph node " +
                "(within maxDepth hops, at most maxNodes), each with its entity type and the reasoning group it belongs to. " +
                "Without evidence every prior equals its posterior: the result reports the model's probabilities, " +
                "not a belief update, and the anchor node only selects which variables are included. " +
                "With evidence (variable names exactly as returned in posteriors, each true or false), posteriors are " +
                "conditioned on it while priors stay the model's probabilities; a name outside the network is rejected " +
                "with the valid names. graph_bayes takes evidence keyed by node id instead and adds mpe, sensitivity " +
                "and whatif actions. " +
                "Locally the model is the one learned when the folder was crawled; a configured remote server uses " +
                "the fact sheet's stored model or builds one from the graph. " +
                "Runs against the project-local graph unless a remote URL is explicitly configured.";
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
        props.putObject("evidence")
                .put("type", "object")
                .put("description", "Optional observations: variable names exactly as returned in posteriors "
                        + "(e.g. isRelevant(<entity id>)) mapped to true/false or 1/0. Posteriors are then "
                        + "conditioned on them; priors are unchanged.");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Optional remote/legacy graph selector; omit locally to use the current folder's knowledge base.");
        props.putObject("knowledgeBase")
                .put("type", "string")
                .put("description", "Optional project-local knowledge-base id; omit locally to use the current folder's knowledge base.");

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
        String selectorError = LocalProjectGraphBackend.selectorConflict(params);
        if (selectorError != null) {
            return ToolResult.error(selectorError);
        }
        Map<String, Integer> evidence;
        try {
            evidence = parseEvidence(params.get("evidence"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }

        if (!groundingClient.isAvailable()) {
            return OfflineToolRuntime.execute(id(), params, context, objectMapper);
        }

        int maxDepth = params.path("maxDepth").asInt(3);
        int maxNodes = params.path("maxNodes").asInt(100);
        long factSheetId = params.path("factSheetId").asLong(0);

        try {
            GroundingBackendClient.GroundingResponse resp;
            if (evidence.isEmpty()) {
                StringBuilder pathBuilder = new StringBuilder("/api/attribution/bayesian/mebn/query")
                        .append("?nodeId=").append(URLEncoder.encode(nodeId, StandardCharsets.UTF_8))
                        .append("&maxDepth=").append(maxDepth)
                        .append("&maxNodes=").append(maxNodes);
                if (factSheetId > 0) {
                    pathBuilder.append("&factSheetId=").append(factSheetId);
                }
                resp = groundingClient.get(pathBuilder.toString());
            } else {
                ObjectNode body = objectMapper.createObjectNode();
                body.putArray("seedNodeIds").add(nodeId);
                body.set("evidence", objectMapper.valueToTree(evidence));
                body.put("maxDepth", maxDepth);
                body.put("maxNodes", maxNodes);
                if (factSheetId > 0) {
                    body.put("factSheetId", factSheetId);
                }
                resp = groundingClient.post("/api/attribution/bayesian/mebn/query",
                        objectMapper.writeValueAsString(body));
            }

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

            Set<String> variables = new LinkedHashSet<>();
            posteriors.fieldNames().forEachRemaining(variables::add);
            String unknown = unknownEvidence(evidence, variables);
            if (unknown != null) {
                return ToolResult.error(unknown);
            }
            int totalVars = variables.size();

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("nodeId", nodeId);
            metadata.put("totalVariables", totalVars);
            metadata.put("computationTimeMs", computationTimeMs);
            metadata.put("evidenceApplied", !evidence.isEmpty());
            if (factSheetId > 0) metadata.put("factSheetId", factSheetId);

            return ToolResult.success("ask_graph_mebn: " + nodeId,
                    formatMebnResult(nodeId, posteriors, priors, variableToTitle,
                            variableToMebnMeta, evidence, totalVars, computationTimeMs),
                    metadata);

        } catch (Exception e) {
            return ToolResult.error("ask_graph_mebn error: " + e.getMessage());
        }
    }

    /**
     * Reads {@code evidence}: grounded variable names mapped to an observed state, {@code true}/1
     * or {@code false}/0. Absent or null means no evidence.
     *
     * @throws IllegalArgumentException when it is not an object or a state is anything else
     */
    static Map<String, Integer> parseEvidence(JsonNode evidence) {
        if (evidence == null || evidence.isNull() || evidence.isMissingNode()) {
            return Map.of();
        }
        if (!evidence.isObject()) {
            throw new IllegalArgumentException("evidence must be an object mapping variable names to true/false");
        }
        Map<String, Integer> states = new LinkedHashMap<>();
        evidence.fields().forEachRemaining(entry -> {
            JsonNode state = entry.getValue();
            if (state.isBoolean()) {
                states.put(entry.getKey(), state.booleanValue() ? 1 : 0);
            } else if (state.isIntegralNumber() && state.canConvertToInt()
                    && (state.intValue() == 0 || state.intValue() == 1)) {
                states.put(entry.getKey(), state.intValue());
            } else {
                throw new IllegalArgumentException(
                        "evidence states must be true/false or 1/0: " + entry.getKey());
            }
        });
        return states;
    }

    /**
     * Describes the evidence names the network does not contain, or returns null when there are
     * none. Inference skips such a name silently, which would report priors as conditioned.
     */
    static String unknownEvidence(Map<String, Integer> evidence, Set<String> variables) {
        List<String> unknown = evidence.keySet().stream().filter(name -> !variables.contains(name)).toList();
        if (unknown.isEmpty()) {
            return null;
        }
        List<String> known = variables.stream().sorted().limit(MAX_VARIABLES_DISPLAY).toList();
        return "Unknown evidence variable(s): " + String.join(", ", unknown) + ". "
                + (known.isEmpty()
                        ? "The network around this node has no variables."
                        : "Use names from posteriors, e.g. " + String.join(", ", known)
                                + (variables.size() > known.size() ? " (" + variables.size() + " in total)." : "."));
    }

    // package-private to allow direct unit testing of the formatter
    String formatMebnResult(String nodeId, JsonNode posteriors, JsonNode priors,
                             JsonNode variableToTitle, JsonNode variableToMebnMeta,
                             Map<String, Integer> evidence, int totalVars, long computationTimeMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Probabilistic network analysis anchored at node: ").append(nodeId);
        sb.append("\nTotal variables: ").append(totalVars);
        sb.append("\nComputation time: ").append(computationTimeMs).append(" ms");
        if (!evidence.isEmpty()) {
            List<String> observed = new ArrayList<>();
            evidence.forEach((variable, state) -> observed.add(variable + "=" + (state == 1 ? "TRUE" : "FALSE")));
            sb.append("\nEvidence: ").append(String.join(", ", observed));
        }

        if (!posteriors.isObject() || posteriors.isEmpty()) {
            sb.append("\n\nNo variables found in subgraph.");
            return sb.toString();
        }

        // Sort variables by |posterior - prior| descending (highest belief update first), then by
        // posterior so a result without evidence still leads with the likeliest variables
        List<String> sortedVars = new ArrayList<>();
        posteriors.fieldNames().forEachRemaining(sortedVars::add);
        sortedVars.sort((a, b) -> {
            double postA  = posteriors.path(a).asDouble(0.0);
            double priorA = priors.path(a).asDouble(0.5);
            double postB  = posteriors.path(b).asDouble(0.0);
            double priorB = priors.path(b).asDouble(0.5);
            int byUpdate = Double.compare(Math.abs(postB - priorB), Math.abs(postA - priorA));
            return byUpdate != 0 ? byUpdate : Double.compare(postB, postA);
        });
        boolean updated = sortedVars.stream().anyMatch(v ->
                Math.abs(posteriors.path(v).asDouble(0.0) - priors.path(v).asDouble(0.5)) > 1e-9);

        int cap = Math.min(sortedVars.size(), MAX_VARIABLES_DISPLAY);
        sb.append("\n\nTop ").append(cap);
        if (totalVars > cap) sb.append("/").append(totalVars);
        sb.append(updated ? " variables by belief update (prior→posterior):"
                          : " variables by probability (prior→posterior):");
        if (!updated && evidence.isEmpty()) {
            sb.append("\nNo evidence was applied, so each prior equals its posterior; ")
              .append("the anchor node only selects which variables are included.");
        }

        for (int i = 0; i < cap; i++) {
            String var        = sortedVars.get(i);
            double posterior  = posteriors.path(var).asDouble(0.0);
            double prior      = priors.path(var).asDouble(0.5);
            String title      = variableToTitle.path(var).asText(var);

            sb.append("\n\n[").append(i + 1).append("] ").append(title);
            // A title hides the name that evidence has to use.
            if (!title.equals(var)) sb.append("  variable=").append(var);
            sb.append("\n    prior=").append(String.format("%.3f", prior))
              .append(" → posterior=").append(String.format("%.3f", posterior));
            double delta = posterior - prior;
            sb.append(" (Δ").append(delta >= 0 ? "+" : "").append(String.format("%.3f", delta)).append(")");
            if (evidence.containsKey(var)) sb.append("  observed");

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
