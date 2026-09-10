/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.tool.graph;

import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single, self-describing query tool for common graph reasoning questions.
 *
 * <p>This is a thin delegate — all logic lives in {@link GraphReasoningQueryService}
 * so the REST controller and this tool stay in lockstep with zero duplication.</p>
 */
@Component
@ConditionalOnBean(UnifiedGraphBridge.class)
public class GraphReasoningQueryTool {

    private final GraphReasoningQueryService service;

    /**
     * @param operation constrained operation listed by CAPABILITIES
     * @param entityId entity id or human-readable name; automatically resolved and ranked
     * @param targetId destination id or name for PATH, VERIFY, WHY, and WHY_NOT
     * @param direction OUTGOING, INCOMING, or BOTH
     * @param relationTypes normalized relation filters; one claimed type for VERIFY, WHY, and WHY_NOT
     * @param maxDepth PATH depth, default 4
     * @param topK result/asset row limit
     * @param queryEmbedding optional vector for semantic+structural RANK
     * @param structural PSL or BAYESIAN, default PSL
     * @param queryText search text, relation text filter, asset selector, vector-layer name, or artifact name
     */
    public record QueryInput(
            @ToolParam(description = "Fact-sheet graph scope; null selects the current/default graph", required = false)
            Long factSheetId,
            @ToolParam(description = "Optional operation from CAPABILITIES; omit with queryText to SEARCH, or omit both for CAPABILITIES", required = false)
            String operation,
            @ToolParam(description = "Optional source/entity id or human-readable phrase; resolved automatically", required = false)
            String entityId,
            @ToolParam(description = "Optional destination id or phrase for PATH, VERIFY, WHY, and WHY_NOT", required = false)
            String targetId,
            @ToolParam(description = "Traversal direction: OUTGOING, INCOMING, or BOTH", required = false)
            String direction,
            @ToolParam(description = "Relation type filters; phrases normalize to predicates, e.g. 'sent to' -> SENT_TO", required = false)
            List<String> relationTypes,
            @ToolParam(description = "Maximum PATH hops; default 4, maximum 12", required = false)
            Integer maxDepth,
            @ToolParam(description = "Maximum ranked results or selected asset rows", required = false)
            Integer topK,
            @ToolParam(description = "Optional semantic query vector for RANK; omit for structural reasoning", required = false)
            List<Double> queryEmbedding,
            @ToolParam(description = "Hybrid structural engine: PSL (default) or BAYESIAN", required = false)
            String structural,
            @ToolParam(description = "Search/filter text, vector-layer or weight-map selector, relation id, global vector key, or artifact name", required = false)
            String queryText,
            @ToolParam(description = "Natural-language alias for queryText; queryText wins when both are present", required = false)
            String question) {

        public QueryInput(
                Long factSheetId, String operation, String entityId, String targetId,
                String direction, List<String> relationTypes, Integer maxDepth, Integer topK,
                List<Double> queryEmbedding, String structural, String queryText) {
            this(factSheetId, operation, entityId, targetId, direction, relationTypes,
                    maxDepth, topK, queryEmbedding, structural, queryText, null);
        }
    }

    @Autowired
    public GraphReasoningQueryTool(GraphReasoningQueryService service) {
        this.service = service;
    }

    @Tool(name = "graph_reasoning_query",
          description = "Universal read/query/reason tool for a live unified graph. "
                  + "Use CAPABILITIES when unsure. Omit operation with queryText for SEARCH, or omit "
                  + "both for CAPABILITIES. Operations: CAPABILITIES, OVERVIEW, SCHEMA, SEARCH, "
                  + "RELATIONS, DESCRIBE, NEIGHBORS, PATH, TIMELINE, FACTS, SIMILAR, VERIFY, WHY, "
                  + "WHY_NOT, RANK, ASSETS, ARTIFACT. Entity fields accept ids or names and resolve "
                  + "automatically. Responses contain ranked results, resolution candidates, evidence, "
                  + "recovery guidance, and a canonical reasoning trace.")
    public GraphQueryEngine.Result query(QueryInput input) {
        if (input == null) {
            return GraphReasoningQueryService.invalid("query input is required");
        }
        return service.execute(new GraphReasoningQueryService.QueryRequest(
                input.factSheetId(),
                input.operation(),
                input.entityId(),
                input.targetId(),
                input.direction(),
                input.relationTypes(),
                input.maxDepth(),
                input.topK(),
                input.queryEmbedding(),
                input.structural(),
                input.queryText(),
                input.question()));
    }
}
