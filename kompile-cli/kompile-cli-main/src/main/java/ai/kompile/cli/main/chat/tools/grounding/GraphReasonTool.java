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
 * MCP tool: {@code graph_reason}
 *
 * <p>Unified, algorithm-agnostic reasoning facade over the knowledge graph.
 * Wraps {@code POST /api/explain} and lets the server auto-route to the correct
 * reasoning engine based on the shape of the {@code target}:</p>
 * <ul>
 *   <li>Bare entity id (e.g. {@code "Alice Smith"}) → structural + semantic analysis</li>
 *   <li>Fact / predicate form (e.g. {@code "isEmployedBy(Alice, Acme)"}) → KB derivation trace</li>
 *   <li>{@code causal:<target>} prefix → causal attribution chains</li>
 * </ul>
 *
 * <p>The LLM is never exposed to the internal reasoning engine names (MEBN, PSL, SSBN,
 * GROUNDING, HYBRID, CAUSAL). The formatted output translates everything into plain English:
 * confidence → words, derivation tree → readable trace, evidence → bulleted list,
 * activated rules → reasoning steps.</p>
 *
 * <p>Server-side {@code TraceHumanizer} already converts atom keys → entity display titles
 * and rule strings → human-readable sentences for GROUNDING and PSL modes. This tool
 * passes those humanized strings through without further processing.</p>
 */
public class GraphReasonTool implements CliTool {

    private final GroundingBackendClient groundingClient;
    private final ObjectMapper objectMapper;
    private final LocalProjectGraphBackend localBackend;

    public GraphReasonTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = new GroundingBackendClient(baseUrl);
        this.localBackend = new LocalProjectGraphBackend(objectMapper);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    GraphReasonTool(GroundingBackendClient groundingClient, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = groundingClient;
        this.localBackend = new LocalProjectGraphBackend(objectMapper);
    }

    @Override
    public String id() { return "graph_reason"; }

    @Override
    public String description() {
        return "Ask the knowledge graph to explain or justify an entity, fact, or claim. " +
                "Returns a plain-English answer with a confidence level " +
                "(well-supported / likely / uncertain / weakly supported / unsupported), " +
                "the supporting evidence with sources, and a step-by-step reasoning trace. " +
                "The system automatically selects the best reasoning approach for your query — " +
                "no knowledge of the underlying reasoning engine is required. " +
                "Use when you need traceable evidence behind a fact, want to understand why " +
                "the knowledge base believes something, or need to audit a claim. " +
                "Requires a running kompile-app.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("target")
                .put("type", "string")
                .put("description", "The entity, fact, or claim to explain or justify. " +
                        "Examples: an entity name or id ('Alice Smith', 'node_42'), " +
                        "a fact to check ('isEmployedBy(Alice, Acme)'), " +
                        "or an event to trace causes for ('causal:revenue_decline_q3'). " +
                        "The system auto-detects the best reasoning approach.");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Scope the reasoning to a specific fact sheet. " +
                        "Absent or null = reason over all fact sheets.");
        props.putObject("depth")
                .put("type", "integer")
                .put("description", "How many reasoning steps deep to trace. Default: 3. Maximum: 5.")
                .put("default", 3);

        schema.putArray("required").add("target");
        return schema;
    }

    @Override
    public String permissionKey() { return "graph_reason"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Reason over knowledge graph");

        String target = params.path("target").asText("");
        if (target.isBlank()) {
            return ToolResult.error("target is required");
        }

        if (!groundingClient.isAvailable()) {
            return localBackend.reason(params, context);
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("target", target);
            // Forward optional scope/depth params but never a mode — auto-routing only
            if (!params.path("factSheetId").isMissingNode()) body.set("factSheetId", params.get("factSheetId"));
            if (!params.path("depth").isMissingNode())       body.set("depth", params.get("depth"));

            var resp = groundingClient.post("/api/explain",
                    objectMapper.writeValueAsString(body));

            if (resp.statusCode() != 200) {
                return ToolResult.error("graph_reason failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result   = objectMapper.readTree(resp.body());
            String verdict    = result.path("verdict").asText("");
            double conf       = result.path("confidence").asDouble(0.0);
            String summary    = result.path("naturalLanguageSummary").asText("");
            String derivation = result.path("derivationTreeJson").asText(null);
            JsonNode evidence = result.path("evidence");
            JsonNode rules    = result.path("activatedRules");

            Map<String, Object> meta = Map.of(
                    "target",     target,
                    "verdict",    verdict.isBlank() ? "none" : verdict,
                    "confidence", conf);

            return ToolResult.success("graph_reason: " + target,
                    formatReasonResult(target, verdict, conf, summary, derivation, evidence, rules),
                    meta);

        } catch (Exception e) {
            return ToolResult.error("graph_reason error: " + e.getMessage());
        }
    }

    // package-private — allows direct unit testing of the formatter without a backend
    String formatReasonResult(String target, String verdict, double conf, String summary,
                              String derivationJson, JsonNode evidenceNode, JsonNode rulesNode) {
        StringBuilder sb = new StringBuilder();

        // ── Headline ────────────────────────────────────────────────────────
        if (!verdict.isBlank() && !"null".equalsIgnoreCase(verdict)) {
            sb.append("**").append(verdict).append("**\n");
        }
        sb.append("Target: ").append(target);
        sb.append("\nConfidence: ").append(confidenceToWords(conf))
          .append(" (").append(String.format("%.0f%%", conf * 100)).append(")");

        // ── Plain-English answer ─────────────────────────────────────────────
        if (summary != null && !summary.isBlank()) {
            sb.append("\n\nAnswer:\n").append(summary);
        }

        // ── Supporting evidence (server already humanized atom keys → titles) ──
        if (evidenceNode.isArray() && !evidenceNode.isEmpty()) {
            sb.append("\n\nSupporting evidence:");
            evidenceNode.forEach(e -> sb.append("\n  - ").append(e.asText()));
        }

        // ── Reasoning steps (server already humanized rule strings) ──────────
        if (rulesNode.isArray() && !rulesNode.isEmpty()) {
            sb.append("\n\nReasoning steps:");
            rulesNode.forEach(r -> sb.append("\n  - ").append(r.asText()));
        }

        // ── Detailed derivation trace (included when server produced one) ─────
        if (derivationJson != null && !derivationJson.isBlank()) {
            sb.append("\n\nDetailed trace:\n").append(derivationJson);
        }

        return sb.toString();
    }

    /**
     * Translate a numeric confidence [0,1] into a plain-English tier.
     * Used to avoid surfacing raw probability numbers as the primary signal.
     */
    static String confidenceToWords(double conf) {
        if (conf >= 0.85) return "well-supported";
        if (conf >= 0.65) return "likely";
        if (conf >= 0.40) return "uncertain";
        if (conf >= 0.15) return "weakly supported";
        return "unsupported";
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
