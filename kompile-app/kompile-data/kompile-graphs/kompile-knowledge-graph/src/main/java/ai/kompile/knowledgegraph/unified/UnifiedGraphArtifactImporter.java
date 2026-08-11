/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;

/**
 * Extension point for restoring domain-owned artifacts from a unified graph import.
 */
@FunctionalInterface
public interface UnifiedGraphArtifactImporter {

    /** Whether the returned count represents embeddings actually applied to the live store. */
    default boolean reportsAppliedEmbeddings() {
        return false;
    }

    /**
     * Restore supported artifacts from {@code graph}.
     *
     * @param factSheetId destination fact sheet, or {@code null} when the artifact retains its scope
     * @param graph imported unified graph
     * @return number of domain objects restored
     */
    int importArtifacts(Long factSheetId, UnifiedGraph graph);
}
