/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.service;

import ai.kompile.knowledgegraph.matrix.service.MatrixKnowledgeGraphService;
import ai.kompile.knowledgegraph.matrix.store.InMemoryMatrixGraphStore;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryController;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import ai.kompile.knowledgegraph.unified.GraphSnapshotService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphAnalysisAssetStore;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import ai.kompile.knowledgegraph.unified.UnifiedGraphIOController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Standalone host for graph persistence and analysis contracts.
 *
 * <p>The imports are intentionally narrow. The graph library contains integrations that still
 * belong to the main application; this process starts the current matrix graph implementation and
 * the transport-neutral unified graph/reasoning surface needed for service extraction.</p>
 */
@SpringBootApplication(scanBasePackageClasses = GraphServiceApplication.class)
@Import({
        MatrixKnowledgeGraphService.class,
        UnifiedGraphAnalysisAssetStore.class,
        UnifiedGraphBridge.class,
        GraphSnapshotService.class,
        GraphReasoningQueryController.class,
        UnifiedGraphIOController.class
})
public class GraphServiceApplication {

    /**
     * The standalone HTTP boundary owns an isolated matrix store. Production app bundles use the
     * persistent vector-backed graph subprocess; this service remains self-contained for isolated use.
     */
    @Bean
    MatrixGraphStore matrixGraphStore() {
        return new InMemoryMatrixGraphStore();
    }

    /** Explicit factory selects the production constructor from the service's testable overloads. */
    @Bean
    GraphReasoningQueryService graphReasoningQueryService(UnifiedGraphBridge bridge) {
        return new GraphReasoningQueryService(bridge);
    }

    public static void main(String[] args) {
        SpringApplication.run(GraphServiceApplication.class, args);
    }
}
