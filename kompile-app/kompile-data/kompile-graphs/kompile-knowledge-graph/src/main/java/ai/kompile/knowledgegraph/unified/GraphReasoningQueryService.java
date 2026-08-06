/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Transport-neutral facade shared by the graph reasoning REST endpoint and MCP tool.
 * It owns request normalization and validation so every transport exposes the same contract.
 */
@Service
public class GraphReasoningQueryService {

    private static final List<GraphQueryEngine.Capability> QUERY_REQUEST_CAPABILITIES =
            GraphQueryEngine.capabilityContract().stream()
                    .filter(GraphReasoningQueryService::supportedByQueryRequest)
                    .toList();
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

    /** JSON request contract used by REST and the stdio MCP proxy. */
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
            String question) {

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
                    maxDepth, topK, queryEmbedding, structural, queryText, null);
        }
    }

    /** Capabilities executable through {@link QueryRequest}; quantitative intents use their own API. */
    public static List<GraphQueryEngine.Capability> queryRequestCapabilities() {
        return QUERY_REQUEST_CAPABILITIES;
    }

    /** Exact operation enum for model and transport schemas backed by {@link QueryRequest}. */
    public static List<String> queryRequestOperations() {
        return QUERY_REQUEST_OPERATIONS;
    }

    /** Compact operation/required-field guide derived from the engine capability contract. */
    public static String queryRequestOperationGuide() {
        List<String> operations = QUERY_REQUEST_CAPABILITIES.stream()
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

        GraphQueryEngine.Intent intent;
        try {
            intent = parseIntent(request.operation());
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
        }

        if (!QUERY_REQUEST_OPERATIONS.contains(intent.name())) {
            return invalid("operation " + intent
                    + " is not supported by this graph query request contract. "
                    + "Use operation=CAPABILITIES.");
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

        GraphQueryEngine.Query query = new GraphQueryEngine.Query(
                intent,
                blankToNull(request.entityId()),
                blankToNull(request.targetId()),
                direction,
                request.relationTypes(),
                request.maxDepth(),
                request.topK(),
                toVector(request.queryEmbedding()),
                structural,
                firstNonBlank(request.queryText(), request.question()));

        // CAPABILITIES is deliberately graph-free, keeping discovery available before a project is open.
        UnifiedGraph graph = intent == GraphQueryEngine.Intent.CAPABILITIES
                ? new UnifiedGraph()
                : useSuppliedGraph
                        ? (suppliedGraph == null ? new UnifiedGraph() : suppliedGraph)
                        : bridge.export(request.factSheetId());
        GraphQueryEngine.Result result = engine.query(graph, query);
        return intent == GraphQueryEngine.Intent.CAPABILITIES
                ? queryRequestCapabilitiesResult(result)
                : result;
    }

    private static boolean supportedByQueryRequest(GraphQueryEngine.Capability capability) {
        GraphQueryEngine.Intent intent = GraphQueryEngine.Intent.valueOf(capability.intent());
        return switch (intent) {
            case MODELS, CALCULATE, SCENARIO, SOLVE_TARGET -> false;
            default -> true;
        };
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

    private static GraphQueryEngine.Result queryRequestCapabilitiesResult(
            GraphQueryEngine.Result result) {
        return new GraphQueryEngine.Result(
                result.status(),
                result.intent(),
                "Supports the transport-neutral read-only graph query contract.",
                result.entities(),
                result.relations(),
                result.path(),
                QUERY_REQUEST_CAPABILITIES,
                result.guidance(),
                result.data(),
                result.resolutions(),
                result.trace());
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
