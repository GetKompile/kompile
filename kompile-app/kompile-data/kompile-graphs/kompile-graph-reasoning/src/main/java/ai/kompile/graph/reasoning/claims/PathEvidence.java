/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.claims;

import java.util.List;
import java.util.Objects;

/**
 * The output of {@link KnowledgeLinkerScorer}: a scored path between a subject and object in the graph.
 *
 * <p>Path weight W = ∏ over INTERMEDIATE nodes v of 1/log(max(e, degree(v))),
 * where degree = total number of incident relations (both directions).
 * A direct-neighbor pair with no intermediates has score 1.0.</p>
 *
 * <p>Reference: Ciampaglia et al., "Computational Fact-Checking from Knowledge Networks,"
 * <em>PLoS ONE</em>, 2015.</p>
 *
 * @param score        path weight in {@code (0, 1]} (higher = more specific path)
 * @param pathNodeIds  ordered list of entity ids along the path, including subject and object
 * @param pathEdgeTypes ordered list of relation types traversed (length = pathNodeIds.size() - 1)
 */
public record PathEvidence(double score, List<String> pathNodeIds, List<String> pathEdgeTypes) {

    public PathEvidence {
        if (score <= 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be in (0,1], got " + score);
        }
        pathNodeIds  = List.copyOf(Objects.requireNonNull(pathNodeIds,  "pathNodeIds"));
        pathEdgeTypes = List.copyOf(Objects.requireNonNull(pathEdgeTypes, "pathEdgeTypes"));
    }

    /** Number of hops (edges) in the path. */
    public int hops() {
        return pathEdgeTypes.size();
    }

    /** Number of intermediate nodes (excludes subject and object). */
    public int intermediates() {
        return Math.max(0, pathNodeIds.size() - 2);
    }
}
