/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** HTTP transport for the generic graph reasoning query contract used by CLI and MCP clients. */
@RestController
@RequestMapping(GraphReasoningQueryController.BASE_PATH)
public class GraphReasoningQueryController {

    public static final String BASE_PATH = "/api/graph/reasoning";

    private final GraphReasoningQueryService queryService;

    public GraphReasoningQueryController(GraphReasoningQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/query")
    public ResponseEntity<?> query(
            @RequestBody(required = false) GraphReasoningQueryService.QueryRequest request) {
        GraphQueryEngine.Result result = queryService.execute(request);
        if (result.status() == GraphQueryEngine.Status.INVALID) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", result.status().name(),
                    "error", result.summary()));
        }
        return ResponseEntity.ok(result);
    }
}
