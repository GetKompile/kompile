/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.ontology;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test for the crawl-enrichment OWL seam. {@code OwlReasoningService.owlDerivedPslRules}
 * is the {@code OwlDerivedRuleProvider} that {@code IncrementalReasoningOrchestrator.injectOwlDerivedRules}
 * calls during the crawl ENRICHMENT phase. This verifies it now reasons over the <b>real</b> crawled
 * graph (entities + their inter-entity edges) rather than an empty ABox: a transitive
 * {@code CONTAINS} chain {@code a→b→c} (has-a / part-of) yields an OWL-RL transitive-closure edge
 * {@code a→c}, which {@code owlDerivedPslRules} turns into a grounded transitive PSL rule the cascade
 * can use for has-a navigation.
 *
 * <p>This asserts the reliable BFS-closure path (mirrors {@code OwlRlReasonerTest.t4_prpTrp_bfs}); the
 * complementary is-a (subClassOf → cax-sco) structural mapping is covered by
 * {@link OwlOntologyBridgeTransitiveTest}.</p>
 */
class OwlReasoningServiceRealAboxTest {

    @Test
    void owlDerivedPslRules_overRealCrawledGraph_emitsTransitiveClosureRule() {
        // Ontology bound to the fact sheet: a transitive CONTAINS relationship (has-a / part-of).
        OntologySchema schema = OntologySchema.builder()
                .id("composition").name("composition")
                .relationshipTypes(List.of(
                        RelationshipTypeDefinition.builder()
                                .type("CONTAINS").sourceEntityType("Assembly").targetEntityType("Part")
                                .transitive(true).build()))
                .build();

        GraphOntologyBindingService binding = mock(GraphOntologyBindingService.class);
        when(binding.autoProvisionStructuralOntology(7L)).thenReturn(Optional.of(schema));

        // Real crawled entities a, b, c connected by a CONTAINS chain a→b→c.
        GraphNode a = entityNode("a");
        GraphNode b = entityNode("b");
        GraphNode c = entityNode("c");

        KnowledgeGraphService kg = mock(KnowledgeGraphService.class);
        when(kg.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY)).thenReturn(List.of(a, b, c));
        when(kg.getEdgesInFactSheet(7L)).thenReturn(List.of(
                containsEdge("e-ab", a, b),
                containsEdge("e-bc", b, c)));

        OwlReasoningService service = new OwlReasoningService(binding, new OwlOntologyBridge(), kg);
        List<String> rules = service.owlDerivedPslRules(7L, 1.0);

        assertTrue(rules.stream().anyMatch(r -> r.contains("contains(?X, ?Y) & contains(?Y, ?Z)")),
                "OWL-RL transitive closure over the real CONTAINS chain (a→b→c) should emit a "
                        + "transitive PSL rule for has-a navigation; got: " + rules);

        // A3: the inferred closure edge a→c is materialized back as an INFERRED has-a edge.
        verify(kg).createEdgeWithMetadata(eq("a"), eq("c"), eq(EdgeType.HIERARCHICAL), anyDouble(),
                eq("CONTAINS"), anyString(), isNull(), eq(EdgeProvenance.INFERRED), eq(7L));
    }

    private static GraphNode entityNode(String id) {
        GraphNode node = mock(GraphNode.class);
        when(node.getNodeId()).thenReturn(id);
        when(node.getMetadata()).thenReturn(Map.of("entity_type", "Thing"));
        return node;
    }

    private static GraphEdge containsEdge(String id, GraphNode source, GraphNode target) {
        // Real edge: relationType carries the semantic ontology relationship ("CONTAINS"),
        // distinct from the structural EdgeType — this is what buildAbox keys the ABox edge on.
        return GraphEdge.builder()
                .edgeId(id)
                .sourceNode(source)
                .targetNode(target)
                .edgeType(EdgeType.CONTAINS)
                .relationType("CONTAINS")
                .build();
    }
}
