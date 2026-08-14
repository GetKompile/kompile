/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
 * MCP tool: {@code graph_reasoning_query}
 *
 * <p>Single unified interface for querying and reasoning over the knowledge graph.
 * Wraps {@code POST /api/graph/reasoning/query} — the same operation the backend
 * {@code @Tool} uses, so cross-surface results are identical.</p>
 *
 * <p>Supported intents (use {@code operation=CAPABILITIES} to discover the full contract):</p>
 * <ul>
 *   <li>CAPABILITIES — list all operations and their required fields (no graph loaded)</li>
 *   <li>OVERVIEW — entity/relation counts, density, top types</li>
 *   <li>SCHEMA — predicate / type vocabulary</li>
 *   <li>SEARCH — text search over node labels and attributes</li>
 *   <li>DESCRIBE — full description of a named entity</li>
 *   <li>NEIGHBORS — immediate neighbors of an entity</li>
 *   <li>PATH — shortest path between two entities</li>
 *   <li>TIMELINE — time-ordered events for an entity</li>
 *   <li>FACTS — grounded facts matching optional text filter</li>
 *   <li>SIMILAR — semantically similar entities</li>
 *   <li>VERIFY — check whether a typed relation is supported or refuted</li>
 *   <li>WHY — explain why a relation holds</li>
 *   <li>WHY_NOT — explain why a relation does not hold and what would change it</li>
 *   <li>RANK — hybrid-scored entity ranking</li>
 *   <li>ASSETS — vector layers and weight maps</li>
 *   <li>ARTIFACT — retrieve a named model artifact from the graph bundle</li>
 * </ul>
 */
public class GraphReasoningQueryTool implements CliTool {

    private final GroundingBackendClient groundingClient;
    private final ObjectMapper objectMapper;
    private final LocalProjectGraphBackend localBackend;

    /** Production constructor. */
    public GraphReasoningQueryTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper    = objectMapper;
        this.groundingClient = new GroundingBackendClient(baseUrl);
        this.localBackend = new LocalProjectGraphBackend(objectMapper);
    }

    /** Testing constructor — accepts an injected {@code GroundingBackendClient} so
     *  a {@code MockRestServiceServer} can intercept HTTP calls. */
    GraphReasoningQueryTool(GroundingBackendClient groundingClient, ObjectMapper objectMapper) {
        this.objectMapper    = objectMapper;
        this.groundingClient = groundingClient;
        this.localBackend = new LocalProjectGraphBackend(objectMapper);
    }

    @Override
    public String id() { return "graph_reasoning_query"; }

    @Override
    public String description() {
        return "One tool to ask the knowledge graph anything — query, search, describe, verify, " +
                "explain, find paths, rank, or list assets. Start with operation=CAPABILITIES to " +
                "see the full list. Entity names and ids are resolved automatically. Results include " +
                "ranked answers, matching entities/relations, and a reasoning trace showing how the " +
                "answer was derived. Runs against the project-local graph over stdio by default; a " +
                "configured graph URL is an optional remote override.";
    }

    @Override
    public String compactHint() {
        return "One tool to ask the graph anything: operation=capabilities|overview|schema|search|" +
                "describe|neighbors|path|timeline|facts|similar|verify|why|why_not|rank (or just " +
                "question=...). Start with operation=capabilities to see what the graph can answer. " +
                "Local stdio defaults to and initializes the current folder; factSheetId is an optional remote/legacy override.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("operation")
                .put("type", "string")
                .put("description", "The query intent. Use CAPABILITIES to list all options and their required fields.");

        props.putObject("question")
                .put("type", "string")
                .put("description", "Natural-language question or search text (alias for queryText; convenient shorthand).");

        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Optional remote/legacy graph selector; omit locally to use the current folder's knowledge base.");

        props.putObject("knowledgeBase")
                .put("type", "string")
                .put("description", "Project-local knowledge-base id returned in crawlResult; selects that crawl's graph.");

        props.putObject("entityId")
                .put("type", "string")
                .put("description", "Source entity id or name. Resolved automatically from human-readable phrases.");

        props.putObject("targetId")
                .put("type", "string")
                .put("description", "Destination entity id or name for PATH, VERIFY, WHY, and WHY_NOT.");

        props.putObject("direction")
                .put("type", "string")
                .put("description", "Traversal direction: OUTGOING, INCOMING, or BOTH.");

        ObjectNode rtNode = props.putObject("relationTypes");
        rtNode.put("type", "array");
        rtNode.putObject("items").put("type", "string");
        rtNode.put("description", "Relation type filters. For VERIFY/WHY/WHY_NOT pass the single claimed relation type.");

        props.putObject("maxDepth")
                .put("type", "integer")
                .put("description", "Maximum PATH hops. Default 4, maximum 12.");

        props.putObject("topK")
                .put("type", "integer")
                .put("description", "Maximum result rows for SEARCH, RANK, ASSETS, etc.");

        props.putObject("structural")
                .put("type", "string")
                .put("description", "Hybrid structural engine: PSL (default) or BAYESIAN.");

        props.putObject("queryText")
                .put("type", "string")
                .put("description", "Search text, relation filter, vector-layer selector, or artifact name.");

        return schema;
    }

    @Override
    public String permissionKey() { return "graph_reasoning_query"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Query knowledge graph");

        if (!groundingClient.isAvailable()) {
            try {
                return formatResult(localBackend.reasoningQuery(params, context));
            } catch (Exception e) {
                return ToolResult.error("graph_reasoning_query local error: " + e.getMessage());
            }
        }

        try {
            ObjectNode body = buildRequestBody(params);
            GroundingBackendClient.GroundingResponse resp =
                    groundingClient.post("/api/graph/reasoning/query",
                            objectMapper.writeValueAsString(body));

            if (resp.statusCode() == 400) {
                JsonNode err = objectMapper.readTree(resp.body());
                String msg = err.path("error").asText("Invalid request");
                return ToolResult.error("graph_reasoning_query: " + msg +
                        ". Use operation=CAPABILITIES to see valid intents.");
            }
            if (resp.statusCode() != 200) {
                return ToolResult.error("graph_reasoning_query failed (HTTP " + resp.statusCode() + "): " +
                        extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            return formatResult(result);

        } catch (Exception e) {
            return ToolResult.error("graph_reasoning_query error: " + e.getMessage());
        }
    }

    // ── Request building ──────────────────────────────────────────────────────

    private ObjectNode buildRequestBody(JsonNode params) {
        ObjectNode body = objectMapper.createObjectNode();
        copyString(params, body, "operation");
        copyString(params, body, "question");
        copyLong(params, body, "factSheetId");
        copyString(params, body, "entityId");
        copyString(params, body, "targetId");
        copyString(params, body, "direction");
        copyArray(params, body, "relationTypes");
        copyInt(params, body, "maxDepth");
        copyInt(params, body, "topK");
        copyArray(params, body, "queryEmbedding");
        copyString(params, body, "structural");
        copyString(params, body, "queryText");
        return body;
    }

    // ── Output formatter ──────────────────────────────────────────────────────

    /**
     * Render the {@link ai.kompile.graph.reasoning.query.GraphQueryEngine.Result} returned
     * by the server in plain English. Answers first, trace summary last.
     */
    ToolResult formatResult(JsonNode result) {
        String status  = result.path("status").asText("UNKNOWN");
        String intent  = result.path("intent").asText("");
        String summary = result.path("summary").asText("").trim();

        StringBuilder sb = new StringBuilder();

        // ── Headline ──────────────────────────────────────────────────────────
        if (!status.equalsIgnoreCase("OK") && !status.equalsIgnoreCase("UNKNOWN")) {
            sb.append("**").append(status).append("**");
            if (!intent.isEmpty()) sb.append(" — ").append(intent);
            sb.append("\n\n");
        }

        // ── Plain-English answer ──────────────────────────────────────────────
        if (!summary.isEmpty()) {
            sb.append(summary).append("\n");
        }

        // ── Capabilities list ─────────────────────────────────────────────────
        JsonNode caps = result.path("capabilities");
        if (caps.isArray() && !caps.isEmpty()) {
            sb.append("\nAvailable operations:\n");
            caps.forEach(cap -> {
                String name    = cap.path("intent").asText();
                String purpose = cap.path("purpose").asText();
                sb.append("  ").append(name).append(" — ").append(purpose).append("\n");
            });
        }

        // ── Entities ──────────────────────────────────────────────────────────
        JsonNode entities = result.path("entities");
        if (entities.isArray() && !entities.isEmpty()) {
            sb.append("\nEntities (").append(entities.size()).append("):\n");
            entities.forEach(e -> {
                String label = e.path("label").asText(e.path("id").asText("?"));
                String type  = e.path("type").asText("");
                double score = e.path("score").asDouble(0.0);
                sb.append("  - ").append(label);
                if (!type.isEmpty()) sb.append(" [").append(type).append("]");
                if (score > 0.0)     sb.append("  score=").append(String.format("%.2f", score));
                sb.append("\n");
            });
        }

        // ── Relations ─────────────────────────────────────────────────────────
        JsonNode relations = result.path("relations");
        if (relations.isArray() && !relations.isEmpty()) {
            sb.append("\nRelations (").append(relations.size()).append("):\n");
            relations.forEach(r -> {
                String src    = r.path("sourceLabel").asText(r.path("sourceId").asText("?"));
                String type   = r.path("type").asText("?");
                String tgt    = r.path("targetLabel").asText(r.path("targetId").asText("?"));
                double weight = r.path("weight").asDouble(0.0);
                sb.append("  - ").append(src).append(" -[").append(type).append("]-> ").append(tgt);
                if (weight > 0.0) sb.append("  weight=").append(String.format("%.2f", weight));
                sb.append("\n");
            });
        }

        // ── Ranked facts ──────────────────────────────────────────────────────
        JsonNode facts = result.path("data").path("facts");
        if (!facts.isArray()) {
            facts = result.path("facts");
        }
        if (facts.isArray() && !facts.isEmpty()) {
            sb.append("\nFacts (").append(facts.size()).append("):\n");
            facts.forEach(fact -> {
                String atom = fact.path("atom").asText("").trim();
                if (!atom.isEmpty()) {
                    sb.append("  - ").append(atom);
                    String kind = fact.path("kind").asText("").trim();
                    if (!kind.isEmpty()) {
                        sb.append(" [").append(kind).append("]");
                    }
                    if (fact.has("confidence")) {
                        sb.append("  confidence=")
                                .append(String.format("%.2f", fact.path("confidence").asDouble()));
                    }
                    sb.append("\n");
                }
            });
        }

        // ── Path ──────────────────────────────────────────────────────────────
        JsonNode path = result.path("path");
        if (path.isArray() && !path.isEmpty()) {
            sb.append("\nPath (").append(path.size()).append(" steps):\n");
            for (int i = 0; i < path.size(); i++) {
                JsonNode step   = path.get(i);
                String   label  = step.path("entity").path("label").asText(
                                  step.path("entity").path("id").asText("?"));
                String   via    = step.path("via").path("type").asText("");
                sb.append("  ").append(i + 1).append(". ").append(label);
                if (!via.isEmpty()) sb.append(" via ").append(via);
                sb.append("\n");
            }
        }

        // ── Guidance ──────────────────────────────────────────────────────────
        JsonNode guidance = result.path("guidance");
        if (guidance.isArray() && !guidance.isEmpty()) {
            sb.append("\nGuidance:\n");
            guidance.forEach(g -> sb.append("  - ").append(g.asText()).append("\n"));
        }

        // ── Trace summary ─────────────────────────────────────────────────────
        JsonNode trace = result.path("trace");
        if (!trace.isMissingNode() && !trace.isNull()) {
            String conclusion = trace.path("conclusion").asText("").trim();
            if (!conclusion.isEmpty()) {
                sb.append("\nReasoning: ").append(conclusion).append("\n");
            }
            JsonNode steps = trace.path("steps");
            if (steps.isArray() && !steps.isEmpty()) {
                sb.append("Steps: ").append(steps.size()).append(" derivation step(s)\n");
            }
        }

        // ── Resolutions (entity disambiguation log) ───────────────────────────
        JsonNode resolutions = result.path("resolutions");
        if (resolutions.isArray() && !resolutions.isEmpty()) {
            sb.append("\nResolved inputs:\n");
            resolutions.forEach(r -> {
                String role     = r.path("role").asText("");
                String input    = r.path("input").asText("");
                String resolved = r.path("resolvedLabel").asText(r.path("resolvedId").asText(""));
                if (!resolved.isEmpty()) {
                    sb.append("  ").append(role).append(" '").append(input)
                      .append("' → ").append(resolved).append("\n");
                }
            });
        }

        Map<String, Object> meta = Map.of(
                "status", status,
                "intent", intent,
                "entityCount",   entities.isArray()   ? entities.size()   : 0,
                "relationCount", relations.isArray()  ? relations.size()  : 0,
                "factCount",     facts.isArray()      ? facts.size()      : 0);

        return ToolResult.success("graph_reasoning_query: " + status, sb.toString().trim(), meta);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private static void copyString(JsonNode src, ObjectNode dst, String key) {
        if (!src.path(key).isMissingNode() && !src.path(key).isNull()) dst.set(key, src.get(key));
    }

    private static void copyLong(JsonNode src, ObjectNode dst, String key) {
        if (!src.path(key).isMissingNode() && !src.path(key).isNull()) dst.set(key, src.get(key));
    }

    private static void copyInt(JsonNode src, ObjectNode dst, String key) {
        if (!src.path(key).isMissingNode() && !src.path(key).isNull()) dst.set(key, src.get(key));
    }

    private static void copyArray(JsonNode src, ObjectNode dst, String key) {
        JsonNode node = src.path(key);
        if (node.isArray()) dst.set(key, node);
    }
}
