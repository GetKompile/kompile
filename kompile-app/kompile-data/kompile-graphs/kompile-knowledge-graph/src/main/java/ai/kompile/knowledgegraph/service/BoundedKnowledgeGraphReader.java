/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.knowledgegraph.service;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;

import java.util.List;
import java.util.Optional;

/**
 * Optional storage-backed read capability for callers that must not materialize an entire graph.
 *
 * <p>The ordinary {@link KnowledgeGraphService} API remains the compatibility surface for stores
 * that expose whole collections. Large matrix/vector stores implement this capability so exact
 * node lookup and neighborhood expansion stay bounded and use their persisted topology directly.</p>
 */
public interface BoundedKnowledgeGraphReader {

    enum Direction { OUTGOING, INCOMING, BOTH }

    /**
     * A bounded incident-edge result. {@code truncated} means additional matching edges exist and
     * callers must not interpret absence from {@code edges} as proof that an edge does not exist.
     */
    record IncidentEdges(List<GraphEdge> edges, boolean truncated) {
        public IncidentEdges {
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    /** Exact node-id lookup within the selected fact-sheet graph; null selects the global scope. */
    Optional<GraphNode> getNodeInScope(String nodeId, Long factSheetId);

    /**
     * Read at most {@code maxEdges} incident edges in the requested direction without loading the
     * graph matrix or returning an unbounded collection.
     */
    IncidentEdges getIncidentEdges(
            String nodeId, Long factSheetId, Direction direction, int maxEdges);
}
