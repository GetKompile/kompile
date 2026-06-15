/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.neo4j.cypher;

import org.neo4j.driver.Driver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoints for arbitrary Cypher passthrough. Activated only when a Neo4j
 * {@link Driver} bean is present, so deployments without Neo4j get a clean 404.
 */
@RestController
@RequestMapping("/api/graph/cypher")
@ConditionalOnBean(Driver.class)
public class CypherController {

    private final CypherQueryService cypherQueryService;

    public CypherController(CypherQueryService cypherQueryService) {
        this.cypherQueryService = cypherQueryService;
    }

    @PostMapping("/query")
    public ResponseEntity<CypherQueryResult> query(@RequestBody CypherRequest req) {
        boolean readOnly = Boolean.TRUE.equals(req.readOnly());
        Map<String, Object> params = req.params() == null ? Map.of() : req.params();
        CypherQueryResult result = readOnly
                ? cypherQueryService.executeRead(req.cypher(), params)
                : cypherQueryService.execute(req.cypher(), params);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(cypherQueryService.serverInfo());
    }

    public record CypherRequest(String cypher, Map<String, Object> params, Boolean readOnly) {}
}
