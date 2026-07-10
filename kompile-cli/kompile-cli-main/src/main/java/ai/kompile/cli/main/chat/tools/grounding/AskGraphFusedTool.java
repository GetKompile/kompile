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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool: {@code ask_graph_explain_fused}
 *
 * <p>Runs ALL applicable reasoning engines concurrently (GROUNDING, HYBRID, PSL, MEBN,
 * CAUSAL, and Graph-RAG) for a given target and returns a single merged evidence trace
 * showing how each modality contributed to the fused answer.</p>
 *
 * <p>Calls {@code POST /api/explain/fused} which is backed by
 * {@code FusedReasonerService.explainAll()} in kompile-app-main.</p>
 */
public class AskGraphFusedTool implements CliTool {

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;

    public AskGraphFusedTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() { return "ask_graph_explain_fused"; }

    @Override
    public String description() {
        return "Run ALL reasoning engines concurrently (rule-based grounding, soft-logic PSL, "
                + "Bayesian MEBN, causal attribution, structural/semantic hybrid, and graph-RAG) "
                + "for a target and return a single unified evidence trace showing how each modality "
                + "contributed. Use this when you need a comprehensive, multi-perspective explanation "
                + "rather than a single-mode derivation.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("target")
                .put("type", "string")
                .put("description", "The atom key, entity id, or natural-language question to explain. "
                        + "All applicable engines will run concurrently against this target.");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Fact sheet scope. Null = global (all fact sheets).");
        props.putObject("depth")
                .put("type", "integer")
                .put("description", "Derivation depth cap for the grounding engine. Default: 3.")
                .put("default", 3);
        props.putObject("sessionId")
                .put("type", "string")
                .put("description", "Optional session id for audit correlation.");

        schema.putArray("required").add("target");
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_explain_fused"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Fused multi-modal explain");

        String target = params.path("target").asText("");
        if (target.isBlank()) {
            return ToolResult.error("target is required");
        }

        if (!backend.isAvailable()) {
            return ToolResult.error("ask_graph_explain_fused requires a running kompile-app.");
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("target", target);
            if (!params.path("factSheetId").isMissingNode()) body.set("factSheetId", params.get("factSheetId"));
            if (!params.path("depth").isMissingNode())       body.set("depth", params.get("depth"));
            if (!params.path("sessionId").isMissingNode())   body.set("sessionId", params.get("sessionId"));

            var resp = backend.post("/api/explain/fused",
                    objectMapper.writeValueAsString(body), Duration.ofSeconds(60));

            if (resp.statusCode() == 503) {
                return ToolResult.error("ask_graph_explain_fused: fused service unavailable on the server.");
            }
            if (resp.statusCode() != 200) {
                return ToolResult.error("ask_graph_explain_fused failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            double fusedConf = result.path("fusedConfidence").asDouble(0.0);
            int modalityCount = result.path("modalityCount").asInt(0);
            long activeCount = result.path("activeModalityCount").asLong(0);
            String nlAnswer = result.path("naturalLanguageAnswer").asText("");
            JsonNode summaries = result.path("summaries");
            JsonNode trail = result.path("trail");

            StringBuilder sb = new StringBuilder();
            sb.append("**Fused multi-modal explain** — ").append(target);
            sb.append("\nFused confidence: ").append(String.format("%.3f", fusedConf));
            sb.append("\nModalities ran: ").append(modalityCount)
              .append(" (").append(activeCount).append(" active)");

            if (!nlAnswer.isBlank()) {
                sb.append("\n\n**Synthesised answer:**\n").append(nlAnswer);
            }

            if (summaries.isArray() && summaries.size() > 0) {
                sb.append("\n\n**Per-modality evidence:**");
                JsonNode modalities = trail.path("modalities");
                for (int i = 0; i < summaries.size(); i++) {
                    String summary = summaries.get(i).asText();
                    String kind = modalities.isArray() && i < modalities.size()
                            ? modalities.get(i).path("kind").asText("")
                            : "";
                    sb.append("\n  [").append(kind).append("] ").append(summary);

                    // Show up to 3 detail lines per modality
                    JsonNode details = modalities.isArray() && i < modalities.size()
                            ? modalities.get(i).path("details") : null;
                    if (details != null && details.isArray()) {
                        int shown = 0;
                        for (JsonNode d : details) {
                            if (shown++ >= 3) { sb.append("\n    ..."); break; }
                            sb.append("\n    - ").append(d.asText());
                        }
                    }
                }
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("target", target);
            metadata.put("fusedConfidence", fusedConf);
            metadata.put("modalityCount", modalityCount);
            metadata.put("activeModalityCount", activeCount);
            if (!nlAnswer.isBlank()) metadata.put("naturalLanguageAnswer", nlAnswer);
            putJsonMetadata(metadata, "trail", trail);

            return ToolResult.success("ask_graph_explain_fused: " + target, sb.toString(), metadata);

        } catch (ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app. " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("ask_graph_explain_fused error: " + e.getMessage());
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

    private void putJsonMetadata(Map<String, Object> metadata, String key, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        metadata.put(key, objectMapper.convertValue(value, Object.class));
    }
}
