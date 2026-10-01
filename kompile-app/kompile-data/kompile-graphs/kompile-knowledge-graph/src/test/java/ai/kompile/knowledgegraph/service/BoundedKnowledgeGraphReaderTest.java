/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.service;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader.Direction;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader.Neighborhood;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedKnowledgeGraphReaderTest {

    @Test
    void seedsArePointLoadedOnceAndOnlyRootsFoundInScopeExpand() {
        FakeReader reader = new FakeReader().node("a").node("b").edge("a", "b");

        Neighborhood hood = reader.getNeighborhood(
                7L, List.of("a", "missing"), List.of("a", "missing", "a"), 1, 10, Direction.BOTH, 10);

        assertEquals(List.of("a", "b"), nodeIds(hood));
        assertEquals(List.of("a::b"), edgeIds(hood));
        assertFalse(hood.truncated());
        assertEquals(Map.of("a", 1, "missing", 1, "b", 1), reader.pointReads);
        assertEquals(List.of("a"), reader.incidentReads, "a repeated root is expanded once");
    }

    @Test
    void edgeBudgetStopsExpansionButKeepsTheNodesAlreadyReached() {
        FakeReader reader = new FakeReader().node("a").node("b").node("c").node("d")
                .edge("a", "b").edge("a", "c").edge("b", "d");

        Neighborhood hood = reader.getNeighborhood(
                7L, List.of("a"), List.of("a"), 2, 10, Direction.BOTH, 2);

        assertEquals(List.of("a", "b", "c"), nodeIds(hood));
        assertEquals(List.of("a::b", "a::c"), edgeIds(hood),
                "edges collected within the budget survive once expansion stops");
        assertTrue(hood.truncated());
        assertEquals(List.of("a"), reader.incidentReads);
    }

    @Test
    void nodeBudgetDropsTheEdgesToNeighborsItCannotHold() {
        FakeReader reader = new FakeReader().node("a").node("b").node("c")
                .edge("a", "b").edge("a", "c");

        Neighborhood hood = reader.getNeighborhood(
                7L, List.of("a"), List.of("a"), 1, 2, Direction.BOTH, 10);

        assertEquals(List.of("a", "b"), nodeIds(hood));
        assertEquals(List.of("a::b"), edgeIds(hood));
        assertTrue(hood.truncated());
    }

    @Test
    void edgesToNodesOutsideTheScopeAreNotReturned() {
        FakeReader reader = new FakeReader().node("a").edge("a", "elsewhere");

        Neighborhood hood = reader.getNeighborhood(
                7L, List.of("a"), List.of("a"), 1, 10, Direction.BOTH, 10);

        assertEquals(List.of("a"), nodeIds(hood));
        assertEquals(List.of(), edgeIds(hood));
        assertFalse(hood.truncated());
    }

    @Test
    void directionIsPassedToTheIncidentRead() {
        FakeReader reader = new FakeReader().node("a").node("b").node("c")
                .edge("a", "b").edge("c", "a");

        Neighborhood hood = reader.getNeighborhood(
                7L, List.of("a"), List.of("a"), 1, 10, Direction.INCOMING, 10);

        assertEquals(List.of("a", "c"), nodeIds(hood));
        assertEquals(List.of("c::a"), edgeIds(hood));
    }

    private static List<String> nodeIds(Neighborhood hood) {
        return hood.nodes().stream().map(GraphNode::getNodeId).toList();
    }

    private static List<String> edgeIds(Neighborhood hood) {
        return hood.edges().stream().map(GraphEdge::getEdgeId).toList();
    }

    /** In-memory store implementing only the two point reads; the traversal is the interface default. */
    private static final class FakeReader implements BoundedKnowledgeGraphReader {
        private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
        private final List<GraphEdge> edges = new ArrayList<>();
        private final Map<String, Integer> pointReads = new HashMap<>();
        private final List<String> incidentReads = new ArrayList<>();

        FakeReader node(String id) {
            nodes.put(id, GraphNode.builder().nodeId(id).factSheetId(7L).build());
            return this;
        }

        FakeReader edge(String source, String target) {
            edges.add(GraphEdge.builder().edgeId(source + "::" + target)
                    .sourceNodeId(source).targetNodeId(target).build());
            return this;
        }

        @Override
        public Optional<GraphNode> getNodeInScope(String nodeId, Long factSheetId) {
            pointReads.merge(nodeId, 1, Integer::sum);
            return Optional.ofNullable(nodes.get(nodeId));
        }

        @Override
        public IncidentEdges getIncidentEdges(String nodeId, Long factSheetId, Direction direction, int maxEdges) {
            incidentReads.add(nodeId);
            List<GraphEdge> incident = edges.stream()
                    .filter(edge -> (direction != Direction.INCOMING && nodeId.equals(edge.getSourceNodeId()))
                            || (direction != Direction.OUTGOING && nodeId.equals(edge.getTargetNodeId())))
                    .toList();
            return new IncidentEdges(incident.stream().limit(maxEdges).toList(), incident.size() > maxEdges);
        }
    }
}
