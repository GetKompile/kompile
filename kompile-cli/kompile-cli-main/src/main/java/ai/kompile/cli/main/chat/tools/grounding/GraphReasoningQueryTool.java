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
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTraceRenderer;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.query.QuantitativeRequestParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 *   <li>RELATIONS — search and rank relations by type, endpoint, text, or metadata</li>
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
 *   <li>MODELS — ranked executable formula models for a measure, with dependency gaps</li>
 *   <li>CALCULATE — evaluate a graph-resident measure</li>
 *   <li>SCENARIO — apply interventions to a copy of the inputs and compare with the baseline</li>
 *   <li>SOLVE_TARGET — goal-seek one bounded input so a measure reaches a target value</li>
 * </ul>
 *
 * <p>The last four read a {@code quantitative} object and never change the graph.</p>
 */
public class GraphReasoningQueryTool implements CliTool {

    private static final List<String> SUPPORTED_OPERATIONS = GraphQueryEngine.capabilityContract().stream()
            .map(GraphQueryEngine.Capability::intent)
            .toList();
    /** Operations that evaluate graph formulas from the {@code quantitative} object. */
    private static final List<String> QUANTITATIVE_OPERATIONS = QuantitativeRequestParser.quantitativeIntents()
            .stream()
            .map(Enum::name)
            .toList();

    /** Trace lines shown: the budget the app uses when it puts a trace in LLM context. */
    private static final int MAX_TRACE_LINES = ReasoningTraceRenderer.DEFAULT_MAX_LINES;
    /** Characters per structured {@code data} field; ASSETS can carry raw vectors. */
    private static final int MAX_DATA_CHARS = 4_000;
    /** Characters of one entity's or relation's attribute map. */
    private static final int MAX_ATTRIBUTE_CHARS = 600;
    /** Characters of one string value inside structured output. */
    private static final int MAX_VALUE_CHARS = 300;
    /** Characters of a free-text {@code data} field, the engine's own ARTIFACT cap. */
    private static final int MAX_TEXT_CHARS = 20_000;

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
                "explain, find paths, rank, or list assets. " + String.join(", ", QUANTITATIVE_OPERATIONS) +
                " evaluate formulas stored in the graph from a quantitative object and never change " +
                "the graph. Start with operation=CAPABILITIES to " +
                "see the full list. Entity names and ids are resolved automatically. Results include " +
                "ranked answers, matching entities/relations, and a reasoning trace showing how the " +
                "answer was derived. Runs against the project-local graph over stdio by default; a " +
                "configured graph URL is an optional remote override. Optional chatModel.provider/modelId " +
                "interprets engine evidence using native chat (not capability/schema/asset inspection).";
    }

    /**
     * MCP listings cut hints at 200 characters. The compact schema keeps the whole
     * {@code operation} enum, so the hint does not repeat it.
     */
    @Override
    public String compactHint() {
        return "Ask the current folder's graph (factSheetId: optional remote/legacy); start with " +
                "operation=CAPABILITIES. " + String.join("|", QUANTITATIVE_OPERATIONS) + " need " +
                QuantitativeRequestParser.example(GraphQueryEngine.Intent.CALCULATE) + ".";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        GraphChatSupport.addSchema(props);

        ObjectNode operation = props.putObject("operation");
        operation
                .put("type", "string")
                .put("description", "The query intent. Use CAPABILITIES to list all options and their required fields.");
        SUPPORTED_OPERATIONS.forEach(operation.putArray("enum")::add);

        props.putObject("question")
                .put("type", "string")
                .put("description", "Natural-language question or search text. When operation is omitted, this executes SEARCH.");

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

        props.set(QuantitativeRequestParser.FIELD,
                objectMapper.valueToTree(QuantitativeRequestParser.jsonSchema()));

        return schema;
    }

    @Override
    public String permissionKey() { return "graph_reasoning_query"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Query knowledge graph");
        return GraphChatSupport.execute(id(), params, context, objectMapper, p -> executeGraph(p, context));
    }

    private ToolResult executeGraph(JsonNode params, ToolContext context) {
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
                String msg = extractError(resp.body());
                return ToolResult.error("graph_reasoning_query: " + (msg.isBlank() ? "Invalid request" : msg) +
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
        copyValue(params, body, QuantitativeRequestParser.FIELD);
        return body;
    }

    // ── Output formatter ──────────────────────────────────────────────────────

    /**
     * Render the {@link ai.kompile.graph.reasoning.query.GraphQueryEngine.Result} returned
     * by the server in plain English. Answers first, then every other {@code data} field and
     * guidance, then the reasoning trace in the shape {@link ReasoningTraceRenderer} gives the
     * app's LLM context, then how each input was resolved.
     */
    ToolResult formatResult(JsonNode result) {
        String status = result.path("status").asText("UNKNOWN");
        JsonNode entities = result.path("entities");
        JsonNode relations = result.path("relations");
        JsonNode facts = result.path("data").path("facts");
        if (!facts.isArray()) {
            facts = result.path("facts");
        }
        ReasoningTrace trace = readTrace(result.path("trace"));
        Map<String, Object> meta = Map.of(
                "status", status,
                "intent", result.path("intent").asText(""),
                "entityCount",   entities.isArray()   ? entities.size()   : 0,
                "relationCount", relations.isArray()  ? relations.size()  : 0,
                "factCount",     facts.isArray()      ? facts.size()      : 0,
                "traceSteps",    trace == null        ? 0                 : trace.size());
        return ToolResult.success("graph_reasoning_query: " + status, render(result, trace, true), meta);
    }

    /**
     * The text {@link #formatResult} renders minus its status headline, for tools that answer
     * through this engine and print their own headline.
     */
    static String renderBody(JsonNode result) {
        return render(result, readTrace(result.path("trace")), false);
    }

    private static String render(JsonNode result, ReasoningTrace trace, boolean headline) {
        String status  = result.path("status").asText("UNKNOWN");
        String intent  = result.path("intent").asText("");
        String summary = result.path("summary").asText("").trim();
        Map<String, Opinion> opinions = opinionsBySource(trace);
        Map<String, String> learnedScores = learnedScoresBySource(trace);

        StringBuilder sb = new StringBuilder();

        // ── Headline ──────────────────────────────────────────────────────────
        if (headline && !status.equalsIgnoreCase("OK") && !status.equalsIgnoreCase("UNKNOWN")) {
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
                sb.append("  ").append(name).append(" — ").append(purpose);
                String required = joinText(cap.path("requiredFields"));
                if (!required.isEmpty()) sb.append("  requires=").append(required);
                JsonNode defaults = cap.path("defaults");
                if (defaults.isObject() && !defaults.isEmpty()) {
                    sb.append("  defaults=").append(boundedJson(defaults, MAX_VALUE_CHARS));
                }
                sb.append("\n");
            });
        }

        // ── Entities ──────────────────────────────────────────────────────────
        JsonNode entities = result.path("entities");
        if (entities.isArray() && !entities.isEmpty()) {
            sb.append("\nEntities (").append(entities.size()).append("):\n");
            entities.forEach(e -> {
                String label = e.path("label").asText(e.path("id").asText("?"));
                String id    = e.path("id").asText("");
                String type  = e.path("type").asText("");
                double score = e.path("score").asDouble(0.0);
                double confidence   = e.path("confidence").asDouble(0.0);
                JsonNode memberships = e.path("typeMemberships");
                sb.append("  - ").append(label);
                if (!type.isEmpty()) sb.append(" [").append(type).append("]");
                if (score > 0.0)     sb.append("  score=").append(fmt(score));
                appendNumber(sb, "structural", e.path("structuralScore"));
                appendNumber(sb, "semantic", e.path("semanticScore"));
                if (confidence > 0.0)       sb.append("  confidence=").append(fmt(confidence));
                if (memberships.size() > 1) sb.append("  types=").append(joinText(memberships));
                appendOpinion(sb, opinions.get(id));
                appendLearnedScore(sb, learnedScores.get(id));
                if (!id.isEmpty())   sb.append("  id=").append(id);
                sb.append("\n");
                appendViewDetails(sb, e);
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
                double confidence = r.path("confidence").asDouble(0.0);
                String id     = r.path("id").asText("");
                String arrow  = r.path("directed").asBoolean(true) ? "]-> " : "]- ";
                sb.append("  - ").append(src).append(" -[").append(type).append(arrow).append(tgt);
                if (weight > 0.0)     sb.append("  weight=").append(fmt(weight));
                if (confidence > 0.0) sb.append("  confidence=").append(fmt(confidence));
                appendOpinion(sb, opinions.get(id));
                appendLearnedScore(sb, learnedScores.get(id));
                if (!id.isEmpty())    sb.append("  id=").append(id);
                sb.append("\n");
                appendViewDetails(sb, r);
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
                        sb.append("  confidence=").append(fmt(fact.path("confidence").asDouble()));
                    }
                    String source = fact.path("source").asText("").trim();
                    if (!source.isEmpty()) sb.append("  source=").append(source);
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

        // ── Data fields ───────────────────────────────────────────────────────
        JsonNode data = result.path("data");
        StringBuilder fields = new StringBuilder();
        data.fieldNames().forEachRemaining(key -> {
            JsonNode value = data.get(key);
            if (value.isNull() || (key.equals("facts") && value.isArray())) return;
            if (value.isContainerNode()) {
                fields.append("  ").append(key).append(" (").append(value.size()).append("): ")
                      .append(boundedJson(value, MAX_DATA_CHARS)).append("\n");
            } else if (value.isTextual()
                    && (value.asText().length() > MAX_VALUE_CHARS || value.asText().contains("\n"))) {
                String text = value.asText();
                if (text.length() > MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS) + "...";
                fields.append("  ").append(key).append(":\n");
                text.lines().forEach(line -> fields.append("    ").append(line).append("\n"));
            } else {
                fields.append("  ").append(key).append(": ").append(value.asText()).append("\n");
            }
        });
        if (fields.length() > 0) sb.append("\nData:\n").append(fields);

        // ── Guidance ──────────────────────────────────────────────────────────
        JsonNode guidance = result.path("guidance");
        if (guidance.isArray() && !guidance.isEmpty()) {
            sb.append("\nGuidance:\n");
            guidance.forEach(g -> sb.append("  - ").append(g.asText()).append("\n"));
        }

        // ── Trace summary ─────────────────────────────────────────────────────
        JsonNode traceNode = result.path("trace");
        if (trace != null) {
            String rendered = ReasoningTraceRenderer.toLlmContext(trace, MAX_TRACE_LINES);
            sb.append("\n").append(rendered).append("\n");
            int shown = (int) rendered.lines().count() - 1;
            if (trace.size() > shown) {
                sb.append("... ").append(trace.size() - shown).append(" more trace step(s) not shown\n");
            }
        } else if (traceNode.isObject()) {
            // A trace this client cannot rebuild still shows its conclusion.
            JsonNode head = traceNode.path("conclusion");
            String conclusion = (head.isObject() ? head.path("conclusion") : head).asText("").trim();
            if (!conclusion.isEmpty()) {
                sb.append("\nReasoning: ").append(conclusion).append("\n");
            }
        }

        // ── Resolutions (entity disambiguation log) ───────────────────────────
        JsonNode resolutions = result.path("resolutions");
        if (resolutions.isArray() && !resolutions.isEmpty()) {
            sb.append("\nInput resolution:\n");
            resolutions.forEach(r -> {
                String role       = r.path("role").asText("");
                String input      = r.path("input").asText("");
                String resolvedId = r.path("resolvedId").asText("");
                String resolved   = r.path("resolvedLabel").asText(resolvedId);
                sb.append("  ").append(role).append(" '").append(input).append("'");
                if (resolved.isEmpty()) {
                    sb.append(" unresolved");
                } else {
                    sb.append(" → ").append(resolved);
                    if (!resolvedId.isEmpty() && !resolvedId.equals(resolved)) {
                        sb.append(" (id=").append(resolvedId).append(")");
                    }
                    sb.append("  score=").append(fmt(r.path("score").asDouble()));
                }
                sb.append("\n");
                // Unresolved or ambiguous inputs list what the id could have meant.
                JsonNode candidates = r.path("candidates");
                if (candidates.size() > (resolved.isEmpty() ? 0 : 1)) {
                    List<String> options = new ArrayList<>();
                    candidates.forEach(c -> options.add(c.path("label").asText(c.path("id").asText("?"))
                            + " (id=" + c.path("id").asText("") + ", score=" + fmt(c.path("score").asDouble()) + ")"));
                    sb.append("    candidates: ").append(String.join("; ", options)).append("\n");
                }
            });
        }

        return sb.toString().trim();
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

    /** Copies an object, or its JSON string, unchanged; the server validates it. */
    private static void copyValue(JsonNode src, ObjectNode dst, String key) {
        if (!src.path(key).isMissingNode() && !src.path(key).isNull()) dst.set(key, src.get(key));
    }

    /** Rebuild the serialized {@link ReasoningTrace}; null when absent or not in that shape. */
    static ReasoningTrace readTrace(JsonNode trace) {
        JsonNode root = trace.path("conclusion");
        if (!root.isObject()) return null;
        try {
            return ReasoningTrace.of(readStep(root));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ReasoningTrace.Step readStep(JsonNode node) {
        List<ReasoningTrace.Step> premises = new ArrayList<>();
        node.path("premises").forEach(premise -> premises.add(readStep(premise)));
        Map<String, String> meta = new LinkedHashMap<>();
        JsonNode metaNode = node.path("meta");
        metaNode.fieldNames().forEachRemaining(key -> meta.put(key, metaNode.get(key).asText()));
        return new ReasoningTrace.Step(
                ReasoningTrace.StepKind.valueOf(node.path("kind").asText()),
                node.path("conclusion").asText(""),
                node.path("operation").asText(""),
                node.path("confidence").asDouble(),
                node.path("source").asText(null),
                premises, readOpinion(node.path("opinion")), meta);
    }

    /** A malformed opinion drops only the opinion, not its step. */
    private static Opinion readOpinion(JsonNode node) {
        if (!node.isObject()) return null;
        try {
            return new Opinion(node.path("belief").asDouble(), node.path("disbelief").asDouble(),
                    node.path("uncertainty").asDouble(), node.path("baseRate").asDouble());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The engine attaches each stored opinion to the trace step whose source is the entity or relation id. */
    private static Map<String, Opinion> opinionsBySource(ReasoningTrace trace) {
        Map<String, Opinion> opinions = new HashMap<>();
        if (trace == null) return opinions;
        for (ReasoningTrace.Step step : trace.steps()) {
            if (step.opinion() != null && step.source() != null && !step.source().isBlank()) {
                opinions.putIfAbsent(step.source(), step.opinion());
            }
        }
        return opinions;
    }

    /**
     * The engine labels an entity's or relation's learned PSL/MEBN score in the meta of the trace
     * step whose source is its id, rather than attaching it as that step's opinion.
     */
    private static Map<String, String> learnedScoresBySource(ReasoningTrace trace) {
        Map<String, String> scores = new HashMap<>();
        if (trace == null) return scores;
        for (ReasoningTrace.Step step : trace.steps()) {
            String score = step.meta().get("learnedScore");
            if (score != null && !score.isBlank() && step.source() != null && !step.source().isBlank()) {
                scores.putIfAbsent(step.source(), score.trim());
            }
        }
        return scores;
    }

    private static void appendViewDetails(StringBuilder sb, JsonNode view) {
        List<String> details = new ArrayList<>();
        String tags = joinText(view.path("tags"));
        if (!tags.isEmpty()) details.add("tags=" + tags);
        String time = view.path("timestamp").asText("");
        if (!time.isEmpty()) details.add("time=" + time);
        String from  = view.path("validFrom").asText("");
        String until = view.path("validUntil").asText("");
        if (!from.isEmpty() || !until.isEmpty()) details.add("valid=" + from + ".." + until);
        if (!details.isEmpty()) sb.append("    ").append(String.join("  ", details)).append("\n");
        JsonNode attributes = view.path("attributes");
        if (attributes.isObject() && !attributes.isEmpty()) {
            sb.append("    attributes: ").append(boundedJson(attributes, MAX_ATTRIBUTE_CHARS)).append("\n");
        }
    }

    private static void appendOpinion(StringBuilder sb, Opinion opinion) {
        if (opinion == null) return;
        sb.append("  opinion(b=").append(fmt(opinion.belief()))
          .append(" d=").append(fmt(opinion.disbelief()))
          .append(" u=").append(fmt(opinion.uncertainty()))
          .append(" a=").append(fmt(opinion.baseRate())).append(")");
    }

    private static void appendLearnedScore(StringBuilder sb, String score) {
        if (score == null) return;
        double value;
        try {
            value = Double.parseDouble(score);
        } catch (NumberFormatException e) {
            return;
        }
        sb.append("  learnedScore=").append(fmt(value));
    }

    private static void appendNumber(StringBuilder sb, String name, JsonNode value) {
        if (value.isNumber()) sb.append("  ").append(name).append("=").append(fmt(value.asDouble()));
    }

    private static String joinText(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> {
            String text = v.asText("").trim();
            if (!text.isEmpty()) values.add(text);
        });
        return String.join(",", values);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** One line of at most {@code max} characters. */
    private static String clip(String text, int max) {
        String line = text.replaceAll("\\s+", " ").trim();
        return line.length() <= max ? line : line.substring(0, max - 3) + "...";
    }

    /** Compact JSON-like text that stops adding entries once {@code limit} characters are written. */
    private static String boundedJson(JsonNode node, int limit) {
        StringBuilder out = new StringBuilder();
        appendBoundedJson(out, node, limit);
        return out.toString();
    }

    private static void appendBoundedJson(StringBuilder out, JsonNode node, int limit) {
        if (node.isObject()) {
            out.append('{');
            Iterator<String> names = node.fieldNames();
            for (int i = 0; names.hasNext(); i++) {
                String name = names.next();
                if (i > 0) out.append(", ");
                if (out.length() >= limit) {
                    out.append("... +").append(node.size() - i).append(" more");
                    break;
                }
                out.append(name).append(": ");
                appendBoundedJson(out, node.get(name), limit);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(", ");
                if (out.length() >= limit) {
                    out.append("... +").append(node.size() - i).append(" more");
                    break;
                }
                appendBoundedJson(out, node.get(i), limit);
            }
            out.append(']');
        } else if (node.isTextual()) {
            out.append(TextNode.valueOf(clip(node.asText(), MAX_VALUE_CHARS)));
        } else {
            out.append(node);
        }
    }
}
