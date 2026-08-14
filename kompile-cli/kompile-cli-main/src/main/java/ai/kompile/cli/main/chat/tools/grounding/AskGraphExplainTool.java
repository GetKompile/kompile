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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool: {@code ask_graph_explain}
 *
 * <p>Produce a derivation trace explaining why the KB believes (or disbelieves)
 * a specific fact via the unified {@code POST /api/explain} endpoint.</p>
 *
 * <p>The response surfaces the full {@link ai.kompile.graph.reasoning.explain.ReasoningTrail}:
 * verdict, calibrated confidence, NL summary, derivation-tree JSON, evidence atoms,
 * activated rules, and the provenance run-id.</p>
 */
public class AskGraphExplainTool implements CliTool {

    private final GroundingBackendClient groundingClient;
    private final ObjectMapper objectMapper;

    public AskGraphExplainTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = new GroundingBackendClient(baseUrl);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    AskGraphExplainTool(GroundingBackendClient groundingClient, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = groundingClient;
    }

    @Override
    public String compactHint() {
        return "Explain why the KB believes (or disbelieves) a fact. "
                + "atom format: 'predicate(arg1, arg2)' — case-sensitive predicate, comma-space separated args. "
                + "Reasoning approach is auto-selected from the atom shape; mode is an advanced override. "
                + "Output: step-by-step derivation tree showing which rules fired, which supporting facts "
                + "were used at each hop, and a plain-English summary. "
                + "depth default 3 (how many inference hops to trace back).";
    }

    @Override
    public String id() { return "ask_graph_explain"; }

    @Override
    public String description() {
        return "Produce a derivation trace explaining why the knowledge base believes (or disbelieves) " +
                "a specific fact. Returns a derivation tree with rule applications and " +
                "supporting facts at each hop, plus a natural-language summary. Use this to audit " +
                "an LLM's reasoning or to present grounded explanations to end users.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("atom")
                .put("type", "string")
                .put("description", "The atom key or entity id to explain, "
                        + "e.g. 'isEmployedBy(Alice, Acme)'. "
                        + "Atom keys (with parentheses) default to GROUNDING mode; "
                        + "bare entity ids default to HYBRID mode.");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Optional remote/legacy graph selector; omit locally to use the current folder's knowledge base.");
        props.putObject("depth")
                .put("type", "integer")
                .put("description", "Maximum derivation hops. Default: 3. Maximum: 5.")
                .put("default", 3);
        ObjectNode modeNode = props.putObject("mode");
        modeNode.put("type", "string");
        modeNode.put("description", "Reasoning engine override: GROUNDING (KB derivation tree), "
                + "HYBRID (structural + semantic), CAUSAL (event attribution), "
                + "PSL (soft-rule reasoning), or MEBN (probabilistic network reasoning). "
                + "Default: auto-detected from atom shape.");
        modeNode.putArray("enum").add("GROUNDING").add("HYBRID").add("CAUSAL").add("PSL").add("MEBN");
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

        if (!groundingClient.isAvailable()) {
            return OfflineToolRuntime.execute(id(), params, context, objectMapper);
        }

        try {
            // POST /api/explain uses "target" (not "atom"); mode is optional
            ObjectNode body = objectMapper.createObjectNode();
            body.put("target", atom);
            if (!params.path("factSheetId").isMissingNode()) body.set("factSheetId", params.get("factSheetId"));
            if (!params.path("depth").isMissingNode())       body.set("depth", params.get("depth"));
            if (!params.path("mode").isMissingNode())        body.set("mode", params.get("mode"));
            if (!params.path("sessionId").isMissingNode())   body.set("sessionId", params.get("sessionId"));

            var resp = groundingClient.post("/api/explain",
                    objectMapper.writeValueAsString(body));

            if (resp.statusCode() != 200) {
                return ToolResult.error("ask_graph_explain failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            String verdict        = result.path("verdict").asText("UNKNOWN");
            double conf           = result.path("confidence").asDouble(0.0);
            String inferenceMode  = result.path("inferenceMode").asText("");
            String summary        = result.path("naturalLanguageSummary").asText("");
            String derivationJson = result.path("derivationTreeJson").asText(null);
            JsonNode evidenceNode = result.path("evidence");
            JsonNode rulesNode    = result.path("activatedRules");
            String runId          = result.path("trail").path("runId").asText("");

            StringBuilder sb = new StringBuilder();
            if (verdict != null && !verdict.isEmpty() && !verdict.equals("UNKNOWN") || conf > 0.0) {
                sb.append("**").append(verdict).append("** — ");
            }
            sb.append(atom);
            sb.append("\nApproach: ").append(translateInferenceMode(inferenceMode));
            sb.append("\nConfidence: ").append(String.format("%.3f", conf));
            if (!runId.isBlank()) sb.append("\nRunId: ").append(runId);
            if (!summary.isBlank()) sb.append("\n\n").append(summary);
            if (evidenceNode.isArray() && evidenceNode.size() > 0) {
                sb.append("\n\nEvidence:");
                evidenceNode.forEach(e -> sb.append("\n  - ").append(e.asText()));
            }
            if (rulesNode.isArray() && rulesNode.size() > 0) {
                sb.append("\nActivated rules:");
                rulesNode.forEach(r -> sb.append("\n  - ").append(r.asText()));
            }
            if (derivationJson != null && !derivationJson.isBlank()) {
                sb.append("\n\nDerivation tree:\n").append(derivationJson);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("target", atom);
            metadata.put("verdict", verdict);
            metadata.put("confidence", conf);
            metadata.put("inferenceMode", inferenceMode);
            metadata.put("runId", runId);
            if (!summary.isBlank()) {
                metadata.put("naturalLanguageSummary", summary);
            }
            if (derivationJson != null && !derivationJson.isBlank()) {
                metadata.put("derivationTreeJson", derivationJson);
            }
            putJsonMetadata(metadata, "trail", result.path("trail"));
            putJsonMetadata(metadata, "evidence", evidenceNode);
            putJsonMetadata(metadata, "activatedRules", rulesNode);

            return ToolResult.success("ask_graph_explain: " + atom, sb.toString(),
                    metadata);

        } catch (Exception e) {
            return ToolResult.error("ask_graph_explain error: " + e.getMessage());
        }
    }

    /** Translate internal inference-mode codes to plain language for LLM output. */
    static String translateInferenceMode(String mode) {
        if (mode == null || mode.isBlank()) return "auto";
        return switch (mode.toUpperCase()) {
            case "GROUNDING" -> "rule-based derivation";
            case "HYBRID"    -> "structural + semantic analysis";
            case "CAUSAL"    -> "causal attribution";
            case "PSL"       -> "soft-rule reasoning";
            case "MEBN"      -> "probabilistic network reasoning";
            default          -> mode.toLowerCase();
        };
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

    private void putJsonMetadata(Map<String, Object> metadata, String key, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        metadata.put(key, objectMapper.convertValue(value, Object.class));
    }
}
