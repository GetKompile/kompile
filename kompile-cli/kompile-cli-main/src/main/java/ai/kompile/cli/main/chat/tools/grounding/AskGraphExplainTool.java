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
 * a specific fact via the unified {@code POST /api/explain} endpoint.</p>
 *
 * <p>The response surfaces the full {@link ai.kompile.graph.reasoning.explain.ReasoningTrail}:
 * verdict, calibrated confidence, NL summary, derivation-tree JSON, evidence atoms,
 * activated rules, and the provenance run-id.</p>
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
                .put("description", "Fact sheet scope. Null = global.");
        props.putObject("depth")
                .put("type", "integer")
                .put("description", "Maximum derivation hops. Default: 3. Maximum: 5.")
                .put("default", 3);
        ObjectNode modeNode = props.putObject("mode");
        modeNode.put("type", "string");
        modeNode.put("description", "Reasoning engine override: GROUNDING (KB derivation tree), "
                + "HYBRID (structural + semantic), or CAUSAL (event attribution). "
                + "Default: auto-detected from atom shape.");
        modeNode.putArray("enum").add("GROUNDING").add("HYBRID").add("CAUSAL");
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
            // POST /api/explain uses "target" (not "atom"); mode is optional
            ObjectNode body = objectMapper.createObjectNode();
            body.put("target", atom);
            if (!params.path("factSheetId").isMissingNode()) body.set("factSheetId", params.get("factSheetId"));
            if (!params.path("depth").isMissingNode())       body.set("depth", params.get("depth"));
            if (!params.path("mode").isMissingNode())        body.set("mode", params.get("mode"));
            if (!params.path("sessionId").isMissingNode())   body.set("sessionId", params.get("sessionId"));

            var resp = backend.post("/api/explain",
                    objectMapper.writeValueAsString(body), Duration.ofSeconds(30));

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

            return ToolResult.success("ask_graph_explain: " + atom, sb.toString(),
                    Map.of("verdict", verdict, "confidence", conf,
                           "inferenceMode", inferenceMode, "runId", runId));

        } catch (ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app. " + e.getMessage());
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
}
