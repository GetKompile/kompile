/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;

/**
 * Extension point for adding domain-owned portable artifacts to a unified graph export.
 *
 * <p>Implementations own their artifact names and serialization. The knowledge-graph exporter
 * invokes contributors without depending on process, agent, or other higher-level modules.
 */
@FunctionalInterface
public interface UnifiedGraphArtifactContributor {

    /**
     * Add artifacts for the requested fact sheet to {@code graph}.
     *
     * @param factSheetId fact sheet being exported, or {@code null} for an unscoped graph
     * @param graph mutable unified graph receiving the artifacts
     */
    void contribute(Long factSheetId, UnifiedGraph graph);
}
