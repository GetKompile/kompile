/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.service;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.impl.KnowledgeGraphServiceImpl;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryController;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import ai.kompile.knowledgegraph.unified.GraphSnapshotService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphAnalysisAssetStore;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import ai.kompile.knowledgegraph.unified.UnifiedGraphIOController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Standalone host for graph persistence and analysis contracts.
 *
 * <p>The imports are intentionally narrow. The graph library contains integrations that still
 * belong to the main application; this process starts only the persistence implementation and the
 * transport-neutral unified graph/reasoning surface needed for service extraction.</p>
 */
@SpringBootApplication(scanBasePackageClasses = GraphServiceApplication.class)
@EntityScan(basePackageClasses = GraphNode.class)
@EnableJpaRepositories(basePackageClasses = GraphNodeRepository.class)
@Import({
        KnowledgeGraphServiceImpl.class,
        UnifiedGraphAnalysisAssetStore.class,
        UnifiedGraphBridge.class,
        GraphSnapshotService.class,
        GraphReasoningQueryController.class,
        UnifiedGraphIOController.class
})
public class GraphServiceApplication {

    /** Explicit factory selects the production constructor from the service's testable overloads. */
    @Bean
    GraphReasoningQueryService graphReasoningQueryService(UnifiedGraphBridge bridge) {
        return new GraphReasoningQueryService(bridge);
    }

    public static void main(String[] args) {
        SpringApplication.run(GraphServiceApplication.class, args);
    }
}
