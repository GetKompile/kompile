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
 * MCP tool: {@code ask_graph_verify}
 *
 * <p>Verify a factual claim against the production knowledge base via
 * {@code POST /api/kb-grounding/verify}. Returns SUPPORTED, REFUTED, or UNKNOWN with
 * calibrated confidence and evidence atoms.</p>
 */
public class AskGraphVerifyTool implements CliTool {

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;

    public AskGraphVerifyTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() { return "ask_graph_verify"; }

    @Override
    public String description() {
        return "Verify a factual claim against the production knowledge base. " +
                "Returns SUPPORTED, REFUTED, or UNKNOWN with a calibrated confidence " +
                "score [0,1] and the supporting evidence keys + activated rules that justify " +
                "the verdict. Use this before accepting any LLM-generated claim as fact. " +
                "Specify asOf for temporal point-in-time verification.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("atom")
                .put("type", "string")
                .put("description", "Canonical atom key, e.g. 'isEmployedBy(Alice, Acme)'. "
                        + "Predicate name is case-sensitive. Arguments separated by ', ' (comma-space).");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Scope verification to a specific fact sheet. Null or absent = search all active fact sheets.");
        props.putObject("asOf")
                .put("type", "string")
                .put("description", "ISO-8601 instant for temporal point-in-time verification. Absent = current truth.");
        props.putObject("minConfidence")
                .put("type", "number")
                .put("description", "Override the default confidence threshold [0,1]. Default: 0.5.");
        props.putObject("sessionId")
                .put("type", "string")
                .put("description", "Optional agent session ID for grounding-session tracking. Echoed in meta.");

        schema.putArray("required").add("atom");
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_verify"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Verify KB claim");

        String atom = params.path("atom").asText("");
        if (atom.isBlank()) {
            return ToolResult.error("atom is required");
        }

        if (!backend.isAvailable()) {
            return ToolResult.error("ask_graph_verify requires a running kompile-app. " +
                    "Start kompile-app or use --url to connect.");
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("atom", atom);
            if (!params.path("factSheetId").isMissingNode()) body.set("factSheetId", params.get("factSheetId"));
            if (!params.path("asOf").isMissingNode())        body.set("asOf", params.get("asOf"));
            if (!params.path("minConfidence").isMissingNode()) body.set("minConfidence", params.get("minConfidence"));
            if (!params.path("sessionId").isMissingNode())   body.set("sessionId", params.get("sessionId"));

            var resp = backend.post("/api/kb-grounding/verify",
                    objectMapper.writeValueAsString(body), Duration.ofSeconds(30));

            if (resp.statusCode() != 200) {
                return ToolResult.error("ask_graph_verify failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            String verdict              = result.path("verdict").asText("UNKNOWN");
            double conf                 = result.path("confidence").asDouble(0.0);
            double calibratedConfidence = result.path("calibratedConfidence").asDouble(conf);
            String strengthBand         = result.path("strengthBand").asText("");
            JsonNode evidence           = result.path("evidenceAtoms");
            boolean stale               = result.path("meta").path("stale").asBoolean(false);

            return ToolResult.success("ask_graph_verify: " + atom,
                    formatVerifyResult(atom, verdict, conf, calibratedConfidence,
                            strengthBand, evidence, stale),
                    Map.of("verdict", verdict, "confidence", conf,
                           "calibratedConfidence", calibratedConfidence,
                           "strengthBand", strengthBand, "stale", stale));

        } catch (ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app. " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("ask_graph_verify error: " + e.getMessage());
        }
    }

    private String formatVerifyResult(String atom, String verdict, double conf,
                                       double calibratedConfidence, String strengthBand,
                                       JsonNode evidence, boolean stale) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(verdict).append("** — ").append(atom);
        sb.append("\nConfidence: ").append(String.format("%.3f", conf));
        sb.append("\nCalibrated confidence: ").append(String.format("%.3f", calibratedConfidence));
        if (!strengthBand.isBlank()) {
            sb.append("\nStrength band: ").append(strengthBand);
        }
        if (evidence.isArray() && evidence.size() > 0) {
            sb.append("\nEvidence:");
            evidence.forEach(e -> sb.append("\n  - ").append(e.asText()));
        }
        if (stale) {
            sb.append("\nWARNING: KB is pending a cascade update — consider retrying.");
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
