/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian.mebn;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KgMTheoryBuilderTest {

    @Mock
    private KnowledgeGraphService graphService;

    private KgMTheoryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new KgMTheoryBuilder(graphService);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EMPTY / MISSING SEEDS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void build_noSeedNodes_returnsEmptyMTheory() {
        when(graphService.getNode(anyString())).thenReturn(Optional.empty());
        MTheory mTheory = builder.build(List.of("missing-node"));
        assertTrue(mTheory.getMFrags().isEmpty());
    }

    @Test
    void build_emptySeedList_returnsEmptyMTheory() {
        MTheory mTheory = builder.build(List.of());
        assertTrue(mTheory.getMFrags().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SINGLE NODE — ENTITY RELEVANCE ONLY
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void build_singleEntityNode_producesEntityRelevanceMFrag() {
        GraphNode node = GraphNode.builder()
                .nodeId("entity-1")
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-1")
                .title("Invoice 42")
                .confidence(0.9)
                .edgeCount(5)
                .build();

        when(graphService.getNode("entity-1")).thenReturn(Optional.of(node));
        when(graphService.getEdgesForNode("entity-1")).thenReturn(List.of());

        MTheory mTheory = builder.build(List.of("entity-1"));

        // Should have EntityRelevance + RiskPropagation (no causal/info edges)
        assertNotNull(mTheory.getMFrag("EntityRelevance"));
        assertNotNull(mTheory.getMFrag("RiskPropagation"));
        assertNull(mTheory.getMFrag("CausalInfluence"));
        assertNull(mTheory.getMFrag("InformationFlow"));

        // Entity types: ENTITY + AllNodes
        assertEquals(2, mTheory.getEntityTypes().size());
        EntityType allNodes = mTheory.getEntityType("AllNodes");
        assertNotNull(allNodes);
        assertTrue(allNodes.hasEntity("entity-1"));
    }

    @Test
    void build_singleNode_withoutRiskMFrag() {
        GraphNode node = GraphNode.builder()
                .nodeId("entity-1")
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-1")
                .title("Test")
                .confidence(0.8)
                .edgeCount(0)
                .build();

        when(graphService.getNode("entity-1")).thenReturn(Optional.of(node));
        when(graphService.getEdgesForNode("entity-1")).thenReturn(List.of());

        MTheory mTheory = builder.includeRiskMFrag(false).build(List.of("entity-1"));

        assertNotNull(mTheory.getMFrag("EntityRelevance"));
        assertNull(mTheory.getMFrag("RiskPropagation"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-NODE WITH CAUSAL EDGES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void build_twoNodesWithCausalEdge_producesCausalInfluenceMFrag() {
        GraphNode nodeA = GraphNode.builder()
                .nodeId("node-a")
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-a")
                .title("Entity A")
                .confidence(0.8)
                .edgeCount(3)
                .build();
        GraphNode nodeB = GraphNode.builder()
                .nodeId("node-b")
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-b")
                .title("Entity B")
                .confidence(0.7)
                .edgeCount(2)
                .build();

        GraphEdge edge = GraphEdge.builder()
                .edgeId("edge-ab")
                .sourceNode(nodeA)
                .targetNode(nodeB)
                .edgeType(EdgeType.SHARED_ENTITY)
                .weight(0.85)
                .confidence(0.9)
                .provenance("EXTRACTED")
                .build();

        when(graphService.getNode("node-a")).thenReturn(Optional.of(nodeA));
        when(graphService.getNode("node-b")).thenReturn(Optional.of(nodeB));
        when(graphService.getEdgesForNode("node-a")).thenReturn(List.of(edge));
        when(graphService.getEdgesForNode("node-b")).thenReturn(List.of());

        MTheory mTheory = builder.build(List.of("node-a"));

        assertNotNull(mTheory.getMFrag("EntityRelevance"));
        assertNotNull(mTheory.getMFrag("CausalInfluence"));

        // CausalInfluence should have isRelevant as input
        MFrag causalFrag = mTheory.getMFrag("CausalInfluence");
        assertEquals(1, causalFrag.getResidentNodes().size());
        assertEquals("influences", causalFrag.getResidentNodes().get(0).getName());
        assertEquals(1, causalFrag.getInputNodes().size());
        assertEquals("isRelevant", causalFrag.getInputNodes().get(0).getName());

        // AllNodes should contain both
        EntityType allNodes = mTheory.getEntityType("AllNodes");
        assertTrue(allNodes.hasEntity("node-a"));
        assertTrue(allNodes.hasEntity("node-b"));
    }

    @Test
    void build_edgeBelowMinWeight_filtered() {
        GraphNode node = GraphNode.builder()
                .nodeId("node-a")
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-a")
                .title("Entity A")
                .confidence(0.8)
                .edgeCount(1)
                .build();
        GraphNode neighborNode = GraphNode.builder()
                .nodeId("node-b")
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-b")
                .title("Entity B")
                .confidence(0.5)
                .edgeCount(1)
                .build();

        GraphEdge weakEdge = GraphEdge.builder()
                .edgeId("edge-weak")
                .sourceNode(node)
                .targetNode(neighborNode)
                .edgeType(EdgeType.SHARED_ENTITY)
                .weight(0.01)  // Below default minEdgeWeight of 0.05
                .confidence(0.3)
                .build();

        when(graphService.getNode("node-a")).thenReturn(Optional.of(node));
        when(graphService.getEdgesForNode("node-a")).thenReturn(List.of(weakEdge));

        MTheory mTheory = builder.build(List.of("node-a"));

        // Only node-a discovered (weak edge filtered), so no causal MFrag
        assertNull(mTheory.getMFrag("CausalInfluence"));
        // AllNodes should only have node-a
        assertEquals(1, mTheory.getEntityType("AllNodes").getEntityIds().size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INFORMATION FLOW (DOCUMENT + ENTITY)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void build_documentAndEntity_producesInformationFlowMFrag() {
        GraphNode docNode = GraphNode.builder()
                .nodeId("doc-1")
                .nodeType(NodeLevel.DOCUMENT)
                .externalId("doc-ext-1")
                .title("Financial Report Q4")
                .confidence(0.95)
                .edgeCount(10)
                .build();
        GraphNode entityNode = GraphNode.builder()
                .nodeId("entity-1")
                .nodeType(NodeLevel.ENTITY)
                .externalId("entity-ext-1")
                .title("Revenue")
                .confidence(0.85)
                .edgeCount(3)
                .build();

        GraphEdge containsEdge = GraphEdge.builder()
                .edgeId("edge-contains")
                .sourceNode(docNode)
                .targetNode(entityNode)
                .edgeType(EdgeType.CONTAINS)
                .weight(0.9)
                .confidence(0.95)
                .provenance("EXTRACTED")
                .build();

        when(graphService.getNode("doc-1")).thenReturn(Optional.of(docNode));
        when(graphService.getNode("entity-1")).thenReturn(Optional.of(entityNode));
        when(graphService.getEdgesForNode("doc-1")).thenReturn(List.of(containsEdge));
        when(graphService.getEdgesForNode("entity-1")).thenReturn(List.of());

        MTheory mTheory = builder.build(List.of("doc-1"));

        assertNotNull(mTheory.getMFrag("InformationFlow"));

        MFrag infoFrag = mTheory.getMFrag("InformationFlow");
        assertEquals(1, infoFrag.getResidentNodes().size());
        assertEquals("informedBy", infoFrag.getResidentNodes().get(0).getName());

        // Entity types: DOCUMENT, ENTITY, AllNodes
        assertNotNull(mTheory.getEntityType("DOCUMENT"));
        assertNotNull(mTheory.getEntityType("ENTITY"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY TYPE HIERARCHY
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void build_entityTypesHierarchy_subtypesSetCorrectly() {
        GraphNode docNode = GraphNode.builder()
                .nodeId("doc-1").nodeType(NodeLevel.DOCUMENT)
                .externalId("d1").title("Doc").confidence(0.9).edgeCount(0).build();
        GraphNode entityNode = GraphNode.builder()
                .nodeId("entity-1").nodeType(NodeLevel.ENTITY)
                .externalId("e1").title("Entity").confidence(0.8).edgeCount(0).build();
        GraphNode tableNode = GraphNode.builder()
                .nodeId("table-1").nodeType(NodeLevel.TABLE)
                .externalId("t1").title("Table").confidence(0.7).edgeCount(0).build();
        GraphNode attachNode = GraphNode.builder()
                .nodeId("attach-1").nodeType(NodeLevel.ATTACHMENT)
                .externalId("a1").title("Attachment").confidence(0.6).edgeCount(0).build();

        // Set up edges so all get discovered from doc-1
        GraphEdge e1 = makeEdge("e-de", docNode, entityNode, EdgeType.CONTAINS, 0.8);
        GraphEdge e2 = makeEdge("e-dt", docNode, tableNode, EdgeType.CONTAINS, 0.7);
        GraphEdge e3 = makeEdge("e-da", docNode, attachNode, EdgeType.CONTAINS, 0.6);

        when(graphService.getNode("doc-1")).thenReturn(Optional.of(docNode));
        when(graphService.getNode("entity-1")).thenReturn(Optional.of(entityNode));
        when(graphService.getNode("table-1")).thenReturn(Optional.of(tableNode));
        when(graphService.getNode("attach-1")).thenReturn(Optional.of(attachNode));
        when(graphService.getEdgesForNode("doc-1")).thenReturn(List.of(e1, e2, e3));
        when(graphService.getEdgesForNode("entity-1")).thenReturn(List.of());
        when(graphService.getEdgesForNode("table-1")).thenReturn(List.of());
        when(graphService.getEdgesForNode("attach-1")).thenReturn(List.of());

        MTheory mTheory = builder.build(List.of("doc-1"));

        EntityType docType = mTheory.getEntityType("DOCUMENT");
        EntityType entType = mTheory.getEntityType("ENTITY");
        EntityType tblType = mTheory.getEntityType("TABLE");
        EntityType attType = mTheory.getEntityType("ATTACHMENT");

        assertNotNull(docType);
        assertNotNull(entType);
        assertNotNull(tblType);
        assertNotNull(attType);

        // ENTITY, TABLE, ATTACHMENT should all be subtypes of DOCUMENT
        assertTrue(entType.isSubtypeOf(docType));
        assertTrue(tblType.isSubtypeOf(docType));
        assertTrue(attType.isSubtypeOf(docType));
        assertFalse(docType.isSubtypeOf(entType));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAX DEPTH / MAX NODES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void build_respectsMaxNodes() {
        // Create chain: A → B, but limit to 2 nodes so C is never reached
        GraphNode nodeA = makeNode("a", NodeLevel.ENTITY, 0.8);
        GraphNode nodeB = makeNode("b", NodeLevel.ENTITY, 0.7);

        GraphEdge edgeAB = makeEdge("e-ab", nodeA, nodeB, EdgeType.SHARED_ENTITY, 0.9);

        when(graphService.getNode("a")).thenReturn(Optional.of(nodeA));
        when(graphService.getNode("b")).thenReturn(Optional.of(nodeB));
        when(graphService.getEdgesForNode("a")).thenReturn(List.of(edgeAB));

        MTheory mTheory = builder.maxNodes(2).build(List.of("a"));

        // AllNodes should have exactly 2 entities
        EntityType allNodes = mTheory.getEntityType("AllNodes");
        assertEquals(2, allNodes.getEntityIds().size());
    }

    @Test
    void build_respectsMaxDepth() {
        // Chain A → B, maxDepth=1 means B is discovered but not expanded
        GraphNode nodeA = makeNode("a", NodeLevel.ENTITY, 0.8);
        GraphNode nodeB = makeNode("b", NodeLevel.ENTITY, 0.7);

        GraphEdge edgeAB = makeEdge("e-ab", nodeA, nodeB, EdgeType.SHARED_ENTITY, 0.9);

        when(graphService.getNode("a")).thenReturn(Optional.of(nodeA));
        when(graphService.getNode("b")).thenReturn(Optional.of(nodeB));
        when(graphService.getEdgesForNode("a")).thenReturn(List.of(edgeAB));

        MTheory mTheory = builder.maxDepth(1).build(List.of("a"));

        EntityType allNodes = mTheory.getEntityType("AllNodes");
        assertTrue(allNodes.hasEntity("a"));
        assertTrue(allNodes.hasEntity("b"));
        // Only 2 nodes discovered at depth 1
        assertEquals(2, allNodes.getEntityIds().size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void build_statistics_reflectStructure() {
        GraphNode nodeA = makeNode("a", NodeLevel.ENTITY, 0.8);
        GraphNode nodeB = makeNode("b", NodeLevel.ENTITY, 0.7);

        GraphEdge edge = makeEdge("e-ab", nodeA, nodeB, EdgeType.SHARED_ENTITY, 0.9);

        when(graphService.getNode("a")).thenReturn(Optional.of(nodeA));
        when(graphService.getNode("b")).thenReturn(Optional.of(nodeB));
        when(graphService.getEdgesForNode("a")).thenReturn(List.of(edge));
        when(graphService.getEdgesForNode("b")).thenReturn(List.of());

        MTheory mTheory = builder.build(List.of("a"));
        Map<String, Object> stats = mTheory.getStatistics();

        assertNotNull(stats.get("name"));
        assertTrue((int) stats.get("mFrags") >= 2); // EntityRelevance + CausalInfluence at minimum
        assertTrue((int) stats.get("entityTypes") >= 2); // ENTITY + AllNodes
        assertTrue((int) stats.get("residentVariables") >= 2);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS (extractEntityId, extractBinaryEntityIds)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void extractEntityId_validUnary() {
        assertEquals("node-123", KgMTheoryBuilder.extractEntityId("isRelevant(node-123)"));
    }

    @Test
    void extractEntityId_noParentheses_returnsNull() {
        assertNull(KgMTheoryBuilder.extractEntityId("isRelevant"));
    }

    @Test
    void extractEntityId_emptyParentheses_returnsEmpty() {
        assertEquals("", KgMTheoryBuilder.extractEntityId("fn()"));
    }

    @Test
    void extractBinaryEntityIds_validBinary() {
        String[] ids = KgMTheoryBuilder.extractBinaryEntityIds("influences(nodeA,nodeB)");
        assertNotNull(ids);
        assertEquals("nodeA", ids[0]);
        assertEquals("nodeB", ids[1]);
    }

    @Test
    void extractBinaryEntityIds_noComma_returnsNull() {
        assertNull(KgMTheoryBuilder.extractBinaryEntityIds("influences(nodeA)"));
    }

    @Test
    void extractBinaryEntityIds_noParens_returnsNull() {
        assertNull(KgMTheoryBuilder.extractBinaryEntityIds("influences"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MTHEORY VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void build_fullGraph_passesValidation() {
        GraphNode docNode = GraphNode.builder()
                .nodeId("doc-1").nodeType(NodeLevel.DOCUMENT)
                .externalId("d1").title("Doc").confidence(0.9).edgeCount(5).build();
        GraphNode entityNode = GraphNode.builder()
                .nodeId("entity-1").nodeType(NodeLevel.ENTITY)
                .externalId("e1").title("Entity").confidence(0.8).edgeCount(3).build();

        GraphEdge containsEdge = makeEdge("e-1", docNode, entityNode, EdgeType.CONTAINS, 0.85);
        GraphEdge sharedEdge = makeEdge("e-2", docNode, entityNode, EdgeType.SHARED_ENTITY, 0.7);

        when(graphService.getNode("doc-1")).thenReturn(Optional.of(docNode));
        when(graphService.getNode("entity-1")).thenReturn(Optional.of(entityNode));
        when(graphService.getEdgesForNode("doc-1")).thenReturn(List.of(containsEdge, sharedEdge));
        when(graphService.getEdgesForNode("entity-1")).thenReturn(List.of());

        MTheory mTheory = builder.build(List.of("doc-1"));

        List<String> errors = mTheory.validate();
        // Validation errors are warnings, not failures, but should be minimal
        // Input RVs reference isRelevant which has a home MFrag — should be clean
        for (String err : errors) {
            assertFalse(err.contains("isRelevant"), "isRelevant should have a home: " + err);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphNode makeNode(String id, NodeLevel level, double confidence) {
        return GraphNode.builder()
                .nodeId(id)
                .nodeType(level)
                .externalId("ext-" + id)
                .title("Node " + id)
                .confidence(confidence)
                .edgeCount(2)
                .build();
    }

    private GraphEdge makeEdge(String edgeId, GraphNode source, GraphNode target,
                               EdgeType type, double weight) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .sourceNode(source)
                .targetNode(target)
                .edgeType(type)
                .weight(weight)
                .confidence(0.8)
                .provenance("EXTRACTED")
                .build();
    }
}
