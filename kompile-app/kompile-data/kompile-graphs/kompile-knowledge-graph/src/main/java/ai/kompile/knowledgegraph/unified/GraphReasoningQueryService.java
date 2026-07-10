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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Transport-neutral facade shared by the graph reasoning REST endpoint and MCP tool.
 * It owns request normalization and validation so every transport exposes the same contract.
 */
@Service
public class GraphReasoningQueryService {

    private final UnifiedGraphBridge bridge;
    private final GraphQueryEngine engine;

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

    /** Normalize, validate, load the selected graph, and execute the reasoning query. */
    public GraphQueryEngine.Result execute(QueryRequest request) {
        if (request == null) {
            return invalid("operation is required. Use operation=CAPABILITIES.");
        }

        GraphQueryEngine.Intent intent;
        try {
            intent = parseIntent(request.operation());
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
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
                : bridge.export(request.factSheetId());
        return engine.query(graph, query);
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
