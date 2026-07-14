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
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Core tool handlers: {@code graph_load}, {@code graph_save}, {@code graph_reasoning_query},
 * and the {@code tools_catalog} self-discovery tool.
 *
 * <p>These are the only fully-implemented handlers in this initial module. The other three
 * handler groups ({@link GroundingHandlers}, {@link InferenceHandlers},
 * {@link AnalyticsHandlers}) are stubs that parallel agents will fill in.</p>
 *
 * <h3>JSON contract for {@code graph_reasoning_query}</h3>
 * <p>Input fields (all optional except {@code operation}):</p>
 * <ul>
 *   <li>{@code operation} — CAPABILITIES, OVERVIEW, SCHEMA, SEARCH, RELATIONS, DESCRIBE,
 *       NEIGHBORS, PATH, TIMELINE, FACTS, SIMILAR, VERIFY, WHY, WHY_NOT, RANK, ASSETS,
 *       ARTIFACT, MODELS, CALCULATE, SCENARIO, SOLVE_TARGET</li>
 *   <li>{@code entityId}, {@code targetId} — entity ids or search phrases</li>
 *   <li>{@code direction} — OUTGOING / INCOMING / BOTH (forward/reverse aliases accepted)</li>
 *   <li>{@code relationTypes} — JSON array of relation type strings</li>
 *   <li>{@code maxDepth}, {@code topK} — integers</li>
 *   <li>{@code queryText} / {@code question} — free text for SEARCH / FACTS / RELATIONS etc.</li>
 *   <li>{@code structural} — PSL or BAYESIAN (for RANK)</li>
 * </ul>
 * <p>Output mirrors the server's {@link GraphQueryEngine.Result} serialization:
 * {@code status}, {@code intent}, {@code summary}, {@code entities[]}, {@code relations[]},
 * {@code path[]}, {@code capabilities[]}, {@code guidance[]}, {@code data}, {@code trace}.</p>
 */
public final class CoreHandlers {

    private final GraphQueryEngine engine;

    /** Dispatcher back-reference for the tools_catalog handler. */
    private LocalToolDispatcher dispatcher;

    CoreHandlers() {
        this.engine = new GraphQueryEngine();
    }

    /** Called by {@link LocalToolDispatcher} after construction to wire the catalog access. */
    void setDispatcher(LocalToolDispatcher d) {
        this.dispatcher = d;
    }

    /**
     * Register all core handlers with the given dispatcher builder.
     *
     * @param builder the dispatcher builder to register handlers on
     * @param core    the CoreHandlers instance (needed to wire catalog after build)
     */
    static void register(LocalToolDispatcher.Builder builder, CoreHandlers core) {
        builder.handler("graph_load",
                schemaFor("graph_load", "Load a .kgraph file into the session (replaces current graph). " +
                        "The KB fact store is re-primed from the loaded graph.",
                        List.of("path"),
                        Map.of("path", stringProp("Absolute path to the .kgraph file to load"))),
                (session, args) -> core.handleGraphLoad(session, args));

        builder.handler("graph_save",
                schemaFor("graph_save", "Save the current session graph to a .kgraph file.",
                        List.of("path"),
                        Map.of("path", stringProp("Absolute path to write the .kgraph file"))),
                (session, args) -> core.handleGraphSave(session, args));

        builder.handler("graph_reasoning_query",
                schemaFor("graph_reasoning_query",
                        "Execute a reasoning query against the loaded graph. Use operation=CAPABILITIES " +
                        "to discover supported operations and required fields. Supports: " +
                        "CAPABILITIES, OVERVIEW, SCHEMA, SEARCH, RELATIONS, DESCRIBE, NEIGHBORS, " +
                        "PATH, TIMELINE, FACTS, SIMILAR, VERIFY, WHY, WHY_NOT, RANK, ASSETS, ARTIFACT.",
                        List.of("operation"),
                        buildQuerySchema()),
                (session, args) -> core.handleQuery(session, args));

        builder.handler("tools_catalog",
                schemaFor("tools_catalog", "List all registered tools with their descriptions and " +
                        "parameter schemas. Use this for self-discovery.",
                        List.of(), Map.of()),
                (session, args) -> core.handleCatalog(session, args));
    }

    // ── Handler implementations ──────────────────────────────────────────────

    private String handleGraphLoad(LocalReasoningSession session, Map<String, Object> args) {
        String pathStr = str(args, "path");
        if (pathStr == null || pathStr.isBlank()) {
            return error("graph_load requires a 'path' argument");
        }
        Path path = Paths.get(pathStr);
        try {
            UnifiedGraph g = UnifiedGraph.load(path);
            session.replaceGraph(g);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "OK");
            out.put("path", path.toAbsolutePath().toString());
            out.put("entityCount", g.entityCount());
            out.put("relationCount", g.relationCount());
            out.put("factsProjected", session.kbState().factStore().size());
            return MiniJson.write(out);
        } catch (IOException e) {
            return error("Failed to load .kgraph from '" + pathStr + "': " + e.getMessage());
        }
    }

    private String handleGraphSave(LocalReasoningSession session, Map<String, Object> args) {
        String pathStr = str(args, "path");
        if (pathStr == null || pathStr.isBlank()) {
            return error("graph_save requires a 'path' argument");
        }
        Path path = Paths.get(pathStr);
        String dtypeStr = str(args, "dtype");
        Dtype dtype = parseDtype(dtypeStr);
        try {
            session.save(path, dtype);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "OK");
            out.put("path", path.toAbsolutePath().toString());
            out.put("dtype", dtype.name());
            return MiniJson.write(out);
        } catch (IOException e) {
            return error("Failed to save .kgraph to '" + pathStr + "': " + e.getMessage());
        }
    }

    private String handleQuery(LocalReasoningSession session, Map<String, Object> args) {
        // Parse operation
        String opStr = str(args, "operation");
        GraphQueryEngine.Intent intent;
        try {
            intent = parseIntent(opStr);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }

        // Parse direction
        GraphQueryEngine.Direction direction;
        try {
            direction = parseDirection(str(args, "direction"));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }

        // Parse structural
        HybridReasoner.Structural structural;
        try {
            structural = parseStructural(str(args, "structural"));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }

        // Parse relation types
        List<String> relationTypes = parseStringList(args, "relationTypes");

        // Numeric args
        Integer maxDepth = intVal(args, "maxDepth");
        Integer topK = intVal(args, "topK");

        // Text
        String entityId = blankToNull(str(args, "entityId"));
        String targetId = blankToNull(str(args, "targetId"));
        String queryText = firstNonBlank(str(args, "queryText"), str(args, "question"));

        GraphQueryEngine.Query query = new GraphQueryEngine.Query(
                intent,
                entityId,
                targetId,
                direction,
                relationTypes,
                maxDepth,
                topK,
                null,         // queryEmbedding — local callers don't supply vectors via JSON
                structural,
                queryText);

        GraphQueryEngine.Result result = engine.query(session.graph(), query);
        return serializeResult(result);
    }

    private String handleCatalog(LocalReasoningSession session, Map<String, Object> args) {
        if (dispatcher == null) {
            return error("Tool catalog not yet available (dispatcher not wired)");
        }
        return dispatcher.catalog().toJson();
    }

    // ── Result serialization ─────────────────────────────────────────────────

    /**
     * Serialize a {@link GraphQueryEngine.Result} to JSON matching the server-side wire contract.
     * We use MiniJson to avoid any external JSON dependency.
     */
    private static String serializeResult(GraphQueryEngine.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status() != null ? r.status().name() : "UNKNOWN");
        out.put("intent", r.intent() != null ? r.intent().name() : null);
        out.put("summary", r.summary());

        // entities
        List<Object> entities = new ArrayList<>();
        for (GraphQueryEngine.EntityView ev : r.entities()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", ev.id());
            e.put("label", ev.label());
            e.put("type", ev.type());
            e.put("score", ev.score());
            e.put("weight", ev.weight());
            e.put("confidence", ev.confidence());
            if (ev.typeMemberships() != null && !ev.typeMemberships().isEmpty()) {
                e.put("typeMemberships", new ArrayList<>(ev.typeMemberships()));
            }
            if (ev.tags() != null && !ev.tags().isEmpty()) {
                e.put("tags", new ArrayList<>(ev.tags()));
            }
            if (ev.timestamp() != null) e.put("timestamp", ev.timestamp());
            if (ev.attributes() != null && !ev.attributes().isEmpty()) {
                e.put("attributes", ev.attributes());
            }
            entities.add(e);
        }
        out.put("entities", entities);

        // relations
        List<Object> relations = new ArrayList<>();
        for (GraphQueryEngine.RelationView rv : r.relations()) {
            Map<String, Object> rel = new LinkedHashMap<>();
            rel.put("id", rv.id());
            rel.put("type", rv.type());
            rel.put("sourceId", rv.sourceId());
            rel.put("sourceLabel", rv.sourceLabel());
            rel.put("targetId", rv.targetId());
            rel.put("targetLabel", rv.targetLabel());
            rel.put("weight", rv.weight());
            rel.put("confidence", rv.confidence());
            rel.put("directed", rv.directed());
            if (rv.tags() != null && !rv.tags().isEmpty()) {
                rel.put("tags", new ArrayList<>(rv.tags()));
            }
            if (rv.timestamp() != null) rel.put("timestamp", rv.timestamp());
            relations.add(rel);
        }
        out.put("relations", relations);

        // path
        List<Object> path = new ArrayList<>();
        for (GraphQueryEngine.PathStep step : r.path()) {
            Map<String, Object> ps = new LinkedHashMap<>();
            if (step.entity() != null) {
                Map<String, Object> e2 = new LinkedHashMap<>();
                e2.put("id", step.entity().id());
                e2.put("label", step.entity().label());
                e2.put("type", step.entity().type());
                e2.put("score", step.entity().score());
                ps.put("entity", e2);
            }
            if (step.via() != null) {
                Map<String, Object> rv2 = new LinkedHashMap<>();
                rv2.put("type", step.via().type());
                rv2.put("sourceId", step.via().sourceId());
                rv2.put("targetId", step.via().targetId());
                rv2.put("weight", step.via().weight());
                ps.put("via", rv2);
            }
            path.add(ps);
        }
        out.put("path", path);

        // capabilities
        List<Object> caps = new ArrayList<>();
        for (GraphQueryEngine.Capability c : r.capabilities()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("intent", c.intent());
            cm.put("purpose", c.purpose());
            cm.put("requiredFields", c.requiredFields());
            cm.put("defaults", c.defaults());
            caps.add(cm);
        }
        out.put("capabilities", caps);

        // guidance
        out.put("guidance", r.guidance());

        // data (arbitrary extra data from the engine)
        if (r.data() != null && !r.data().isEmpty()) {
            out.put("data", r.data());
        }

        // trace (simplified — full trace serialization is for inference handlers to extend)
        if (r.trace() != null) {
            out.put("trace", r.trace().toJson());
        }

        return MiniJson.write(out);
    }

    // ── Parsing helpers ──────────────────────────────────────────────────────

    private static GraphQueryEngine.Intent parseIntent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("operation is required. Use operation=CAPABILITIES.");
        }
        String normalized = normalize(value);
        try {
            return GraphQueryEngine.Intent.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown operation '" + value + "'. Use operation=CAPABILITIES.");
        }
    }

    private static GraphQueryEngine.Direction parseDirection(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = normalize(value);
        if ("FORWARD".equals(normalized)) normalized = "OUTGOING";
        if ("REVERSE".equals(normalized) || "BACKWARD".equals(normalized)) normalized = "INCOMING";
        try {
            return GraphQueryEngine.Direction.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "direction must be OUTGOING, INCOMING, or BOTH");
        }
    }

    private static HybridReasoner.Structural parseStructural(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return HybridReasoner.Structural.valueOf(normalize(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("structural must be PSL or BAYESIAN");
        }
    }

    private static Dtype parseDtype(String value) {
        if (value == null || value.isBlank()) return Dtype.F32;
        try {
            return Dtype.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Dtype.F32;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) result.add(item.toString());
            }
            return Collections.unmodifiableList(result);
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s.trim());
        }
        return List.of();
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer intVal(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        String va = blankToNull(a);
        return va != null ? va : blankToNull(b);
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    static String error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ERROR");
        m.put("message", Objects.requireNonNullElse(message, "Unknown error"));
        return MiniJson.write(m);
    }

    // ── Schema helpers ───────────────────────────────────────────────────────

    private static LocalToolCatalog.Entry schemaFor(
            String name, String description, List<String> required,
            Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return new LocalToolCatalog.Entry(name, description, schema);
    }

    private static Map<String, Object> stringProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> intProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> enumProp(String description, List<String> values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        p.put("enum", values);
        return p;
    }

    private static Map<String, Object> buildQuerySchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("operation", enumProp(
                "Reasoning operation to perform. Use CAPABILITIES to discover all options.",
                List.of("CAPABILITIES","OVERVIEW","SCHEMA","SEARCH","RELATIONS","DESCRIBE",
                        "NEIGHBORS","PATH","TIMELINE","FACTS","SIMILAR","VERIFY","WHY",
                        "WHY_NOT","RANK","ASSETS","ARTIFACT","MODELS","CALCULATE",
                        "SCENARIO","SOLVE_TARGET")));
        props.put("entityId", stringProp(
                "Entity id or phrase to resolve. Required for: DESCRIBE, NEIGHBORS, PATH, SIMILAR, VERIFY, WHY, WHY_NOT."));
        props.put("targetId", stringProp(
                "Target entity id or phrase. Required for: PATH, VERIFY, WHY, WHY_NOT."));
        props.put("direction", enumProp(
                "Edge traversal direction.",
                List.of("OUTGOING","INCOMING","BOTH")));
        props.put("relationTypes", Map.of(
                "type", "array",
                "description", "Relation types to filter or assert (array of strings). Required for VERIFY/WHY/WHY_NOT.",
                "items", Map.of("type", "string")));
        props.put("maxDepth", intProp("Maximum traversal depth for PATH (default 4)."));
        props.put("topK", intProp("Maximum result count (default varies by operation)."));
        props.put("queryText", stringProp(
                "Free-text query. Used by: SEARCH (required), RELATIONS, FACTS, ARTIFACT."));
        props.put("question", stringProp("Alias for queryText."));
        props.put("structural", enumProp(
                "Structural reasoner for RANK.",
                List.of("PSL","BAYESIAN")));
        return props;
    }
}
