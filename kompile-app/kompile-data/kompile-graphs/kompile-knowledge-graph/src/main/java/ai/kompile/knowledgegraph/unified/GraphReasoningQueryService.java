/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.quantitative.QuantitativeQuery;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.query.QuantitativeRequestParser;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Transport-neutral facade shared by the graph reasoning REST endpoint and MCP tool.
 * It owns request normalization and validation so every transport exposes the same contract.
 */
@Service
public class GraphReasoningQueryService {

    private static final List<GraphQueryEngine.Capability> QUERY_REQUEST_CAPABILITIES =
            GraphQueryEngine.capabilityContract();
    private static final List<String> QUERY_REQUEST_OPERATIONS =
            QUERY_REQUEST_CAPABILITIES.stream()
                    .map(GraphQueryEngine.Capability::intent)
                    .toList();

    private final UnifiedGraphBridge bridge;
    private final GraphQueryEngine engine;

    @Autowired
    public GraphReasoningQueryService(UnifiedGraphBridge bridge) {
        this(bridge, new GraphQueryEngine());
    }

    GraphReasoningQueryService(UnifiedGraphBridge bridge, GraphQueryEngine engine) {
        this.bridge = bridge;
        this.engine = engine;
    }

    /**
     * JSON request contract used by REST and the stdio MCP proxy.
     *
     * <p>{@code quantitative} carries the MODELS, CALCULATE, SCENARIO, and SOLVE_TARGET request as a
     * JSON object or its JSON string; {@link QuantitativeRequestParser} reads it.</p>
     */
    public record QueryRequest(
            Long factSheetId,
            String operation,
            String entityId,
            String targetId,
            String direction,
            List<String> relationTypes,
            Integer maxDepth,
            Integer topK,
            List<Double> queryEmbedding,
            String structural,
            String queryText,
            String question,
            Object quantitative) {

        /** Constructor for requests without a quantitative object. */
        public QueryRequest(
                Long factSheetId,
                String operation,
                String entityId,
                String targetId,
                String direction,
                List<String> relationTypes,
                Integer maxDepth,
                Integer topK,
                List<Double> queryEmbedding,
                String structural,
                String queryText,
                String question) {
            this(factSheetId, operation, entityId, targetId, direction, relationTypes,
                    maxDepth, topK, queryEmbedding, structural, queryText, question, null);
        }

        /** Backward-compatible constructor used by the in-process graph tool. */
        public QueryRequest(
                Long factSheetId,
                String operation,
                String entityId,
                String targetId,
                String direction,
                List<String> relationTypes,
                Integer maxDepth,
                Integer topK,
                List<Double> queryEmbedding,
                String structural,
                String queryText) {
            this(factSheetId, operation, entityId, targetId, direction, relationTypes,
                    maxDepth, topK, queryEmbedding, structural, queryText, null, null);
        }
    }

    /** Every engine capability; the quantitative ones read {@link QueryRequest#quantitative()}. */
    public static List<GraphQueryEngine.Capability> queryRequestCapabilities() {
        return QUERY_REQUEST_CAPABILITIES;
    }

    /** Exact operation enum for model and transport schemas backed by {@link QueryRequest}. */
    public static List<String> queryRequestOperations() {
        return QUERY_REQUEST_OPERATIONS;
    }

    /** Compact operation/required-field guide derived from the engine capability contract. */
    public static String queryRequestOperationGuide() {
        return guide(QUERY_REQUEST_CAPABILITIES) + " " + QuantitativeRequestParser.guidance();
    }

    /** Operations that need no {@code quantitative} object, for tools that do not carry one. */
    public static List<String> nonQuantitativeOperations() {
        return nonQuantitativeCapabilities().stream()
                .map(GraphQueryEngine.Capability::intent)
                .toList();
    }

    /** {@link #queryRequestOperationGuide()} restricted to {@link #nonQuantitativeOperations()}. */
    public static String nonQuantitativeOperationGuide() {
        return guide(nonQuantitativeCapabilities());
    }

    private static List<GraphQueryEngine.Capability> nonQuantitativeCapabilities() {
        return QUERY_REQUEST_CAPABILITIES.stream()
                .filter(capability -> !QuantitativeRequestParser.isQuantitative(capability))
                .toList();
    }

    private static String guide(List<GraphQueryEngine.Capability> capabilities) {
        List<String> operations = capabilities.stream()
                .map(capability -> capability.requiredFields().isEmpty()
                        ? capability.intent()
                        : capability.intent() + "("
                                + String.join(",", capability.requiredFields()) + ")")
                .toList();
        return "Read-only graph operation and required fields: "
                + String.join("; ", operations)
                + ". entityId and targetId also accept names or phrases. "
                + "CAPABILITIES returns purposes and defaults.";
    }

    /** Normalize, validate, load the selected graph, and execute the reasoning query. */
    public GraphQueryEngine.Result execute(QueryRequest request) {
        return execute(null, request, false);
    }

    /**
     * Normalize, validate, and execute against a caller-supplied graph.
     *
     * <p>This keeps the REST/MCP query contract and all FOL, hybrid-ranking, schema, provenance,
     * and embedding behavior available to in-flight pipelines before their graph has been
     * persisted. A null graph is treated as a fresh empty graph.</p>
     */
    public GraphQueryEngine.Result execute(UnifiedGraph graph, QueryRequest request) {
        return execute(graph, request, true);
    }

    private GraphQueryEngine.Result execute(UnifiedGraph suppliedGraph,
                                            QueryRequest request,
                                            boolean useSuppliedGraph) {
        if (request == null) {
            return invalid("operation is required. Use operation=CAPABILITIES.");
        }

        String operation = blankToNull(request.operation());
        if (operation == null) {
            operation = firstNonBlank(request.queryText(), request.question()) == null
                    ? GraphQueryEngine.Intent.CAPABILITIES.name()
                    : GraphQueryEngine.Intent.SEARCH.name();
        }

        GraphQueryEngine.Intent intent;
        try {
            intent = parseIntent(operation);
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
        }

        List<String> missing = missingRequiredFields(request, intent);
        if (!missing.isEmpty()) {
            return invalid(intent + " requires " + String.join(", ", missing)
                    + ". Use operation=CAPABILITIES for the exact contract.");
        }

        GraphQueryEngine.Direction direction;
        try {
            direction = parseDirection(request.direction());
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
        }

        HybridReasoner.Structural structural;
        try {
            structural = parseStructural(request.structural());
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
        }

        double[] queryEmbedding = toVector(request.queryEmbedding());
        QuantitativeQuery quantitative;
        try {
            quantitative = QuantitativeRequestParser.parse(
                    intent, request.quantitative(), request.topK(), queryEmbedding);
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
        }

        GraphQueryEngine.Query query = new GraphQueryEngine.Query(
                intent,
                blankToNull(request.entityId()),
                blankToNull(request.targetId()),
                direction,
                request.relationTypes(),
                request.maxDepth(),
                request.topK(),
                queryEmbedding,
                structural,
                firstNonBlank(request.queryText(), request.question()),
                quantitative);

        // CAPABILITIES is deliberately graph-free, keeping discovery available before a project is open.
        UnifiedGraph graph = intent == GraphQueryEngine.Intent.CAPABILITIES
                ? new UnifiedGraph()
                : useSuppliedGraph
                        ? (suppliedGraph == null ? new UnifiedGraph() : suppliedGraph)
                        : persistedQueryGraph(request, intent, direction);
        GraphQueryEngine.Result result = engine.query(graph, query);
        if (Boolean.TRUE.equals(graph.meta().get("truncated"))) {
            result = markPartial(result, graph);
        }
        return result;
    }

    private UnifiedGraph persistedQueryGraph(
            QueryRequest request,
            GraphQueryEngine.Intent intent,
            GraphQueryEngine.Direction requestedDirection) {
        if (bridge == null) return new UnifiedGraph();
        String entityId = blankToNull(request.entityId());
        boolean boundedIntent = switch (intent) {
            case DESCRIBE, NEIGHBORS, PATH, VERIFY, WHY, WHY_NOT -> true;
            case RELATIONS, TIMELINE, FACTS -> entityId != null;
            default -> false;
        };
        if (!boundedIntent || entityId == null) return bridge.export(request.factSheetId());

        // Entity-scoped operations never fall back to a whole-graph export. An exact id seeds the
        // neighborhood as is; a name or phrase seeds it with a few bounded search candidates, which
        // the engine ranks and traces in resolutions.
        List<String> sourceSeeds = bridge.resolveSeedIds(request.factSheetId(), entityId);
        Set<String> seeds = new LinkedHashSet<>(sourceSeeds);
        String targetId = blankToNull(request.targetId());
        if (targetId != null) seeds.addAll(bridge.resolveSeedIds(request.factSheetId(), targetId));
        int depth = switch (intent) {
            case PATH -> request.maxDepth() == null || request.maxDepth() <= 0
                    ? 4 : Math.min(request.maxDepth(), 12);
            case WHY_NOT -> 3;
            default -> 1;
        };
        int maxNodes = Math.max(1, Integer.getInteger(
                "kompile.graph.query.maxMaterializedNodes", 10_000));
        int maxEdges = Math.max(1, Integer.getInteger(
                "kompile.graph.query.maxMaterializedEdges", 50_000));
        GraphQueryEngine.Direction materializationDirection = switch (intent) {
            case VERIFY, WHY -> GraphQueryEngine.Direction.OUTGOING;
            case WHY_NOT -> GraphQueryEngine.Direction.BOTH;
            case PATH -> requestedDirection == null
                    ? GraphQueryEngine.Direction.OUTGOING : requestedDirection;
            case NEIGHBORS -> requestedDirection == null
                    ? GraphQueryEngine.Direction.BOTH : requestedDirection;
            default -> GraphQueryEngine.Direction.BOTH;
        };
        return bridge.exportNeighborhood(
                request.factSheetId(), List.copyOf(seeds), sourceSeeds, depth, maxNodes,
                materializationDirection, maxEdges);
    }

    private static GraphQueryEngine.Result markPartial(
            GraphQueryEngine.Result result, UnifiedGraph graph) {
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>(result.data());
        data.put("boundedGraphTruncated", true);
        data.put("materializedNodes", graph.meta().get("materializedNodes"));
        data.put("materializedEdges", graph.meta().get("materializedEdges"));
        data.put("maxNodes", graph.meta().get("maxNodes"));
        data.put("maxEdges", graph.meta().get("maxEdges"));
        List<String> guidance = new ArrayList<>(result.guidance());
        guidance.add("The storage-backed neighborhood reached its materialization budget; absence is not conclusive.");
        return new GraphQueryEngine.Result(
                GraphQueryEngine.Status.PARTIAL,
                result.intent(),
                result.summary() + " Results are partial because the bounded graph budget was reached.",
                result.entities(),
                result.relations(),
                result.path(),
                result.capabilities(),
                guidance,
                data,
                result.resolutions(),
                result.trace());
    }

    private static List<String> missingRequiredFields(
            QueryRequest request, GraphQueryEngine.Intent intent) {
        GraphQueryEngine.Capability capability = QUERY_REQUEST_CAPABILITIES.stream()
                .filter(candidate -> candidate.intent().equals(intent.name()))
                .findFirst()
                .orElse(null);
        if (capability == null || capability.requiredFields().isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String field : capability.requiredFields()) {
            if (field.startsWith(QuantitativeRequestParser.FIELD + ".")) {
                continue; // The parser names missing quantitative members with a working example.
            }
            boolean present = switch (field) {
                case "entityId" -> blankToNull(request.entityId()) != null;
                case "targetId" -> blankToNull(request.targetId()) != null;
                case "queryText" ->
                        firstNonBlank(request.queryText(), request.question()) != null;
                case "relationTypes[0]" -> request.relationTypes() != null
                        && !request.relationTypes().isEmpty()
                        && blankToNull(request.relationTypes().get(0)) != null;
                default -> false;
            };
            if (!present) {
                missing.add(field);
            }
        }
        return List.copyOf(missing);
    }

    /** Stable invalid response helper used by both REST and in-process tool validation. */
    public static GraphQueryEngine.Result invalid(String message) {
        String summary = message == null || message.isBlank() ? "Invalid graph query" : message;
        ReasoningTrace trace = ReasoningTrace.of(ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.VALIDATION,
                summary,
                "validate graph query contract",
                0.0,
                List.of()));
        return new GraphQueryEngine.Result(
                GraphQueryEngine.Status.INVALID,
                null,
                summary,
                List.of(), List.of(), List.of(), List.of(),
                List.of("Use operation=CAPABILITIES to inspect valid operations and required fields."),
                java.util.Map.of(), List.of(), trace);
    }

    private static GraphQueryEngine.Intent parseIntent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("operation is required. Use operation=CAPABILITIES.");
        }
        String normalized = normalizeEnum(value);
        try {
            return GraphQueryEngine.Intent.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown operation '" + value + "'. Use operation=CAPABILITIES.");
        }
    }

    private static GraphQueryEngine.Direction parseDirection(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = normalizeEnum(value);
        if ("FORWARD".equals(normalized)) normalized = "OUTGOING";
        if ("REVERSE".equals(normalized) || "BACKWARD".equals(normalized)) normalized = "INCOMING";
        try {
            return GraphQueryEngine.Direction.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "direction must be OUTGOING, INCOMING, or BOTH (forward/reverse aliases are accepted)");
        }
    }

    private static HybridReasoner.Structural parseStructural(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return HybridReasoner.Structural.valueOf(normalizeEnum(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("structural must be PSL or BAYESIAN");
        }
    }

    private static double[] toVector(List<Double> values) {
        if (values == null || values.isEmpty()) return null;
        double[] vector = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i) == null ? 0.0 : values.get(i);
        }
        return vector;
    }

    private static String firstNonBlank(String first, String second) {
        String value = blankToNull(first);
        return value != null ? value : blankToNull(second);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeEnum(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
