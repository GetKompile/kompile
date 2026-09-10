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
 * MCP tool: {@code ask_graph_synthesize} (WP12d)
 *
 * <p>Synthesize ranked answers to a question against the production knowledge base via
 * {@code POST /api/kb-grounding/synthesize}. Each candidate entity is scored by fusing calibrated
 * signals — retrieval, ontology type, and (when the query implies a relation) KB verification and
 * calibrated KGE plausibility — into a likelihood with a re-checkable operator-tree trace. Prefer this
 * over free-form generation when the answer must be grounded and ranked; the returned likelihood
 * already accounts for KB support and consistency.</p>
 */
public class AskGraphSynthesizeTool implements CliTool {

    private static final int MAX_SHOWN = 10;

    private final GroundingBackendClient groundingClient;
    private final ObjectMapper objectMapper;

    public AskGraphSynthesizeTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = new GroundingBackendClient(baseUrl);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    AskGraphSynthesizeTool(GroundingBackendClient groundingClient, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = groundingClient;
    }

    @Override
    public String compactHint() {
        return "Ask a natural-language question and get ranked grounded answers from the knowledge base. "
                + "query = plain English, e.g. 'who leads Acme?' or 'which companies are based in London?'. "
                + "Returns candidates ranked by likelihood [0,1]; KB-contradicted answers are demoted automatically. "
                + "expectedType optional — narrow to a specific entity class, e.g. 'person', 'organization', 'place'. "
                + "Use this instead of guessing when the answer should come from the graph.";
    }

    @Override
    public String id() { return "ask_graph_synthesize"; }

    @Override
    public String description() {
        return "Synthesize ranked answers to a question from the knowledge base. The configured graph "
                + "backend supplies retrieval, verification and any available ontology/KGE signals. "
                + "Optional chatModel.provider/modelId uses native chat to interpret the returned evidence; "
                + "model answers do not replace engine scores, rankings or verification.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        GraphChatSupport.addSchema(props);

        props.putObject("query")
                .put("type", "string")
                .put("description", "The natural-language question to answer, e.g. 'who leads Acme?'.");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Optional remote/legacy graph selector; omit locally to use the current folder's knowledge base.");
        props.putObject("expectedType")
                .put("type", "string")
                .put("description", "Demand an answer of this entity type; other-typed candidates are demoted.");
        props.putObject("maxCandidates")
                .put("type", "integer")
                .put("description", "Cap how many candidates to score. 0/absent = configured default.");

        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_synthesize"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Synthesize KB answer");
        return GraphChatSupport.execute(id(), params, context, objectMapper, p -> executeGraph(p, context));
    }

    private ToolResult executeGraph(JsonNode params, ToolContext context) throws ToolExecutionException {
        String query = params.path("query").asText("");
        if (query.isBlank()) {
            return ToolResult.error("query is required");
        }

        if (!groundingClient.isAvailable()) {
            return OfflineToolRuntime.execute(id(), params, context, objectMapper);
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", query);
            if (!params.path("factSheetId").isMissingNode())   body.set("factSheetId", params.get("factSheetId"));
            if (!params.path("expectedType").isMissingNode())  body.set("expectedType", params.get("expectedType"));
            if (!params.path("maxCandidates").isMissingNode()) body.set("maxCandidates", params.get("maxCandidates"));

            var resp = groundingClient.post("/api/kb-grounding/synthesize",
                    objectMapper.writeValueAsString(body));

            if (resp.statusCode() != 200) {
                return ToolResult.error("ask_graph_synthesize failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            JsonNode answers = result.path("answers");
            int answerCount = result.path("answerCount").asInt(answers.isArray() ? answers.size() : 0);

            String topAnswer = answers.isArray() && answers.size() > 0
                    ? answers.get(0).path("answer").asText("") : "";
            double topLikelihood = answers.isArray() && answers.size() > 0
                    ? answers.get(0).path("likelihood").asDouble(0.0) : 0.0;
            // GAP 2: topAnswer is the human title resolved server-side; expose entityId as well
            String topEntityId = answers.isArray() && answers.size() > 0
                    ? answers.get(0).path("entityId").asText("") : "";

            return ToolResult.success("ask_graph_synthesize: " + query,
                    formatAnswers(query, answers),
                    Map.of("answerCount", answerCount,
                            "topAnswer", topAnswer,
                            "topEntityId", topEntityId,
                            "topLikelihood", topLikelihood));

        } catch (Exception e) {
            return ToolResult.error("ask_graph_synthesize error: " + e.getMessage());
        }
    }

    private String formatAnswers(String query, JsonNode answers) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ranked answers for: ").append(query);
        if (!answers.isArray() || answers.isEmpty()) {
            sb.append("\n(no grounded candidates found)");
            return sb.toString();
        }
        int shown = 0;
        for (JsonNode a : answers) {
            if (shown >= MAX_SHOWN) {
                sb.append("\n… ").append(answers.size() - MAX_SHOWN).append(" more");
                break;
            }
            double likelihood = a.path("likelihood").asDouble(0.0);
            double belief = a.path("belief").asDouble(0.0);
            double uncertainty = a.path("uncertainty").asDouble(0.0);
            // GAP 2: answer is the human title; show raw entityId as parenthetical when different
            String title = a.path("answer").asText("");
            String entityId = a.path("entityId").asText("");
            String display = (entityId.isBlank() || entityId.equals(title))
                    ? title
                    : title + " (" + entityId + ")";
            sb.append(String.format("%n%d. %s — likelihood %.3f (belief %.2f, uncertainty %.2f)",
                    ++shown, display, likelihood, belief, uncertainty));
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
