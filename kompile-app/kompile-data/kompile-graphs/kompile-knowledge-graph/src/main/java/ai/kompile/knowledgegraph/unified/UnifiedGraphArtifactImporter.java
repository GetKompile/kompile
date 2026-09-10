/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.Set;

/**
 * Extension point for restoring domain-owned artifacts from a unified graph import.
 */
@FunctionalInterface
public interface UnifiedGraphArtifactImporter {

    /** Stable participant identity used for deterministic ordering and diagnostics. */
    default String participantId() {
        return getClass().getName();
    }

    /** Lower values commit first and compensate last. */
    default int order() {
        return 500;
    }

    /** Reserved artifact names or prefixes this participant restores or deliberately consumes. */
    default Set<String> managedArtifactPrefixes() {
        return Set.of();
    }

    /** One side-effect-free prepared artifact transaction. */
    interface PreparedImport {
        int commit();
        void rollback();
    }

    /**
     * Validate supported artifacts before any live graph or domain state is mutated.
     * Implementations must be deterministic and side-effect free.
     */
    default void validateArtifacts(Long factSheetId, UnifiedGraph graph) {
    }

    /**
     * Capture any before-state required to commit or roll back this importer. Preparation must not
     * mutate live state. The default preserves legacy behavior by importing the incoming graph on
     * commit and the bridge-captured previous graph on rollback.
     */
    default PreparedImport prepareArtifacts(
            Long factSheetId, UnifiedGraph incoming, UnifiedGraph previous) {
        return new PreparedImport() {
            @Override
            public int commit() {
                return importArtifacts(factSheetId, incoming);
            }

            @Override
            public void rollback() {
                if (previous != null) importArtifacts(factSheetId, previous);
            }
        };
    }

    /** Whether the returned count represents embeddings actually applied to the live store. */
    default boolean reportsAppliedEmbeddings() {
        return false;
    }

    /** Whether this importer provides logically exact, idempotent prepared rollback semantics. */
    default boolean supportsExactRollback() {
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
