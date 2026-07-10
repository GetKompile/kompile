/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.service;

import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports that the standalone process has assembled its graph storage and API boundary. */
@Component("graphService")
final class GraphServiceHealthIndicator implements HealthIndicator {

    private final KnowledgeGraphService knowledgeGraphService;

    GraphServiceHealthIndicator(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("storage", knowledgeGraphService.getClass().getSimpleName())
                .withDetail("unifiedGraphApi", "/api/graph/unified")
                .withDetail("reasoningApi", "/api/graph/reasoning")
                .build();
    }
}
