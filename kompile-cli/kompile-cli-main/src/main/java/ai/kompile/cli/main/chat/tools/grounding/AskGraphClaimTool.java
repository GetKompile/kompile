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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: {@code ask_graph_claim}
 *
 * <p>Assesses a (subject, predicate, object) claim against the knowledge base via
 * {@code POST /api/kb-grounding/claim}. Fuses five evidence channels into a single
 * verdict + signal breakdown.</p>
 */
public class AskGraphClaimTool implements CliTool {

    private final GroundingBackendClient groundingClient;
    private final ObjectMapper objectMapper;

    public AskGraphClaimTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = new GroundingBackendClient(baseUrl);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    AskGraphClaimTool(GroundingBackendClient groundingClient, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = groundingClient;
    }

    @Override
    public String id() { return "ask_graph_claim"; }

    @Override
    public String description() {
        return "Assess a factual claim (subject, predicate, object) against the knowledge base. " +
                "Fuses five evidence lines of attack — direct connections in the graph, verified " +
                "facts from logical inference, connecting path scores, link plausibility from " +
                "embeddings, and learned rules — into a single fused confidence score and a " +
                "per-signal verdict breakdown. Useful when you need to know not just WHETHER a " +
                "claim holds but WHICH evidence types support or attack it.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("subject")
                .put("type", "string")
                .put("description", "Entity id of the claim subject (e.g. 'alice' or an internal node id).");
        props.putObject("predicate")
                .put("type", "string")
                .put("description", "Relation type to check (e.g. 'worksFor', 'isLocatedIn'). Case-insensitive.");
        props.putObject("object")
                .put("type", "string")
                .put("description", "Entity id of the claim object (e.g. 'acme_corp').");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Optional remote/legacy graph selector; omit locally to use the current folder's knowledge base.");
        props.putObject("sessionId")
                .put("type", "string")
                .put("description", "Optional correlation id echoed in the response meta.");

        schema.putArray("required").add("subject").add("predicate").add("object");
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_claim"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public String compactHint() {
        return "Assess a claim: POST /api/kb-grounding/claim {subject, predicate, object, factSheetId?}. " +
               "Returns verdict (SUPPORTED/REFUTED/UNCERTAIN), fusedScore [0,1], and which evidence " +
               "lines of attack support or attack it: direct connection (graph edge), verified facts " +
               "(logical inference), connecting paths, link plausibility (embeddings), learned rules. " +
               "Use this when you need BOTH a verdict and a per-signal breakdown explaining WHY.";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Assess KB claim");

        String subject   = params.path("subject").asText("");
        String predicate = params.path("predicate").asText("");
        String object    = params.path("object").asText("");

        if (subject.isBlank())   return ToolResult.error("subject is required");
        if (predicate.isBlank()) return ToolResult.error("predicate is required");
        if (object.isBlank())    return ToolResult.error("object is required");

        if (!groundingClient.isAvailable()) {
            return OfflineToolRuntime.execute(id(), params, context, objectMapper);
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("subject", subject);
            body.put("predicate", predicate);
            body.put("object", object);
            if (!params.path("factSheetId").isMissingNode()) body.set("factSheetId", params.get("factSheetId"));
            if (!params.path("sessionId").isMissingNode())   body.set("sessionId", params.get("sessionId"));

            var resp = groundingClient.post("/api/kb-grounding/claim",
                    objectMapper.writeValueAsString(body));

            if (resp.statusCode() != 200) {
                return ToolResult.error("ask_graph_claim failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            String  verdict    = result.path("verdict").asText("UNCERTAIN");
            double  fusedScore = result.path("fusedScore").asDouble(0.5);
            String  claimAtom  = result.path("claimAtom").asText(subject + " " + predicate + " " + object);
            JsonNode supporting = result.path("supporting");
            JsonNode refuting   = result.path("refuting");
            boolean stale      = result.path("meta").path("stale").asBoolean(false);

            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("verdict", verdict);
            structured.put("fusedScore", fusedScore);
            structured.put("claimAtom", claimAtom);
            structured.put("stale", stale);

            return ToolResult.success("ask_graph_claim: " + claimAtom,
                    formatClaimResult(claimAtom, verdict, fusedScore, supporting, refuting, stale),
                    structured);

        } catch (Exception e) {
            return ToolResult.error("ask_graph_claim error: " + e.getMessage());
        }
    }

    private String formatClaimResult(String claimAtom, String verdict, double fusedScore,
                                     JsonNode supporting, JsonNode refuting, boolean stale) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(verdict).append("** — ").append(claimAtom);
        sb.append("\nFused confidence: ").append(String.format("%.3f", fusedScore));
        if (stale) sb.append(" *(KB may be stale — cascade in progress)*");

        if (supporting != null && supporting.isArray() && supporting.size() > 0) {
            sb.append("\n\nSupporting evidence:");
            supporting.forEach(item -> {
                String signal = item.path("signal").asText("?");
                String desc   = item.path("description").asText("");
                double prob   = item.path("probability").asDouble(0.0);
                sb.append("\n  [").append(signal).append("] ");
                sb.append(desc);
                sb.append(" (p=").append(String.format("%.3f", prob)).append(")");
            });
        }

        if (refuting != null && refuting.isArray() && refuting.size() > 0) {
            sb.append("\n\nRefuting evidence:");
            refuting.forEach(item -> {
                String signal = item.path("signal").asText("?");
                String desc   = item.path("description").asText("");
                double prob   = item.path("probability").asDouble(0.0);
                sb.append("\n  [").append(signal).append("] ");
                sb.append(desc);
                sb.append(" (p=").append(String.format("%.3f", prob)).append(")");
            });
        }

        if ((supporting == null || !supporting.isArray() || supporting.size() == 0)
                && (refuting == null || !refuting.isArray() || refuting.size() == 0)) {
            sb.append("\nNo evidence found in the current fact sheet. Try after a crawl has completed.");
        }

        return sb.toString();
    }

    private String extractError(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has("message")) return node.get("message").asText();
            if (node.has("error"))   return node.get("error").asText();
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
