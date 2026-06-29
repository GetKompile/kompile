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
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.app.web.dto.ontology.OwlClassificationResponse;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        when(binding.resolveActiveOntology(7L)).thenReturn(Optional.of(schema));

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

    @Test
    void buildAbox_preservesDeterministicTypeMembershipMetadata() throws Exception {
        GraphOntologyBindingService binding = mock(GraphOntologyBindingService.class);
        KnowledgeGraphService kg = mock(KnowledgeGraphService.class);
        GraphNode entity = entityNode("acct-1", Map.of(
                "entity_type", "Account",
                "additionalTypes", List.of("Customer"),
                "ontology.typeCandidates", List.of(
                        Map.of("type", "AuditableEntity", "source", "owl-rl", "confidence", 1.0d),
                        Map.of("type", "NeuralGuess", "source", "samediff", "confidence", 0.99d))));
        when(kg.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY)).thenReturn(List.of(entity));
        when(kg.getEdgesInFactSheet(7L)).thenReturn(List.of());

        OwlReasoningService service = new OwlReasoningService(binding, new OwlOntologyBridge(), kg);
        Method method = OwlReasoningService.class.getDeclaredMethod("buildAbox", long.class);
        method.setAccessible(true);

        ReasoningGraph abox = (ReasoningGraph) method.invoke(service, 7L);
        GraphEntity graphEntity = abox.entity("acct-1").orElseThrow();

        assertTrue(graphEntity.typeMemberships().contains("Account"));
        assertTrue(graphEntity.typeMemberships().contains("Customer"));
        assertTrue(graphEntity.typeMemberships().contains("AuditableEntity"));
        assertFalse(graphEntity.typeMemberships().contains("NeuralGuess"),
                "SameDiff/neural scores must not be promoted to crisp ABox type assertions");
    }

    @Test
    void classify_overRealAbox_materializesSubclassDomainAndRangeTypes() {
        OntologySchema schema = OntologySchema.builder()
                .id("workforce")
                .name("workforce")
                .entityTypes(List.of(
                        EntityTypeDefinition.builder().name("Person").build(),
                        EntityTypeDefinition.builder().name("Employee").parentType("Person").build(),
                        EntityTypeDefinition.builder().name("Animal").build(),
                        EntityTypeDefinition.builder().name("Dog").parentType("Animal").build()))
                .relationshipTypes(List.of(
                        RelationshipTypeDefinition.builder()
                                .type("MANAGES")
                                .sourceEntityType("Employee")
                                .targetEntityType("Person")
                                .build()))
                .build();

        GraphOntologyBindingService binding = mock(GraphOntologyBindingService.class);
        when(binding.autoProvisionStructuralOntology(7L)).thenReturn(Optional.of(schema));

        GraphNode alice = entityNode("alice", Map.of("entity_type", "Thing"));
        GraphNode bob = entityNode("bob", Map.of("entity_type", "Thing"));
        GraphNode fido = entityNode("fido", Map.of("entity_type", "Dog"));

        KnowledgeGraphService kg = mock(KnowledgeGraphService.class);
        when(kg.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY)).thenReturn(List.of(alice, bob, fido));
        when(kg.getEdgesInFactSheet(7L)).thenReturn(List.of(
                relationEdge("e-manages", alice, bob, "MANAGES")));

        OwlReasoningService service = new OwlReasoningService(binding, new OwlOntologyBridge(), kg);
        OwlClassificationResponse response = service.classify(7L);

        assertEquals(4, response.getInferredTypeCount(),
                "Domain, range, and subclass closure should produce four inferred type memberships");
        assertEquals(3, response.getEntitiesClassified(),
                "All three entities should receive persisted OWL-RL type candidates");

        ArgumentCaptor<String> nodeIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kg, times(3)).updateNode(nodeIdCaptor.capture(), isNull(), isNull(), metadataCaptor.capture());

        Map<String, Map<String, Object>> metadataByNode = new LinkedHashMap<>();
        for (int i = 0; i < nodeIdCaptor.getAllValues().size(); i++) {
            metadataByNode.put(nodeIdCaptor.getAllValues().get(i), metadataCaptor.getAllValues().get(i));
        }

        assertEquals(List.of("Employee", "Person"), metadataByNode.get("alice").get("owlInferredTypes"),
                "MANAGES source must materialize Employee domain and Person superclass");
        assertEquals(List.of("Person"), metadataByNode.get("bob").get("owlInferredTypes"),
                "MANAGES target must materialize Person range");
        assertEquals(List.of("Animal"), metadataByNode.get("fido").get("owlInferredTypes"),
                "Dog instance must materialize Animal superclass");
        List<?> fidoHierarchy = assertInstanceOf(List.class,
                metadataByNode.get("fido").get("ontology.typeHierarchy"));
        Map<?, ?> dogHierarchy = assertInstanceOf(Map.class, fidoHierarchy.get(0));
        assertEquals("Dog", dogHierarchy.get("type"));
        assertEquals("Animal", dogHierarchy.get("parentType"));
        assertEquals("owl-rl", dogHierarchy.get("source"));
        assertEquals("schema-parentType", dogHierarchy.get("basis"));

        List<?> aliceCandidates = assertInstanceOf(List.class,
                metadataByNode.get("alice").get("ontology.typeCandidates"));
        assertEquals("owl-rl", candidateByType(aliceCandidates, "Employee").get("source"));
        assertEquals("owl-rl", candidateByType(aliceCandidates, "Person").get("source"));
    }

    @Test
    void owlInferredTypeMaterialization_writesStructuredTypeCandidates() throws Exception {
        GraphOntologyBindingService binding = mock(GraphOntologyBindingService.class);
        KnowledgeGraphService kg = mock(KnowledgeGraphService.class);
        GraphNode entity = entityNode("a", Map.of(
                "entity_type", "Thing",
                "ontology.typeCandidates", List.of(Map.of(
                        "type", "ExistingCandidate",
                        "confidence", 0.25d,
                        "source", "llm"))));
        when(kg.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY)).thenReturn(List.of(entity));

        OwlReasoningService service = new OwlReasoningService(binding, new OwlOntologyBridge(), kg);
        Method method = OwlReasoningService.class.getDeclaredMethod(
                "materializeInferredTypes", long.class, Map.class);
        method.setAccessible(true);

        Object updated = method.invoke(service, 7L, Map.of("a", "https://example.org/ontology#Assembly"));

        assertEquals(1, updated);
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kg).updateNode(eq("a"), isNull(), isNull(), metadataCaptor.capture());
        Map<String, Object> metadata = metadataCaptor.getValue();
        assertEquals(List.of("Assembly"), metadata.get("owlInferredTypes"));

        Object rawCandidates = metadata.get("ontology.typeCandidates");
        assertNotNull(rawCandidates);
        List<?> candidates = assertInstanceOf(List.class, rawCandidates);
        assertEquals(2, candidates.size());

        Map<?, ?> existingCandidate = assertInstanceOf(Map.class, candidates.get(0));
        assertEquals("ExistingCandidate", existingCandidate.get("type"));

        Map<?, ?> owlCandidate = assertInstanceOf(Map.class, candidates.get(1));
        assertEquals("Assembly", owlCandidate.get("type"));
        assertEquals(1.0d, owlCandidate.get("confidence"));
        assertEquals("owl-rl", owlCandidate.get("source"));
        assertEquals("class-subsumption", owlCandidate.get("basis"));
        assertEquals(List.of("https://example.org/ontology#Assembly"), owlCandidate.get("evidence"));
    }

    @Test
    void owlInferredTypeMaterialization_writesMultipleStructuredTypeCandidatesForEntity() throws Exception {
        GraphOntologyBindingService binding = mock(GraphOntologyBindingService.class);
        KnowledgeGraphService kg = mock(KnowledgeGraphService.class);
        GraphNode entity = entityNode("a", Map.of("entity_type", "Thing"));
        when(kg.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY)).thenReturn(List.of(entity));

        OwlReasoningService service = new OwlReasoningService(binding, new OwlOntologyBridge(), kg);
        Method method = OwlReasoningService.class.getDeclaredMethod(
                "materializeInferredTypes", long.class, Map.class);
        method.setAccessible(true);

        Object updated = method.invoke(service, 7L, Map.of(
                "a", List.of(
                        "https://example.org/ontology#Assembly",
                        "https://example.org/ontology#Component")));

        assertEquals(1, updated);
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kg).updateNode(eq("a"), isNull(), isNull(), metadataCaptor.capture());
        Map<String, Object> metadata = metadataCaptor.getValue();
        assertEquals(List.of("Assembly", "Component"), metadata.get("owlInferredTypes"));

        List<?> candidates = assertInstanceOf(List.class, metadata.get("ontology.typeCandidates"));
        assertEquals(2, candidates.size());

        Map<?, ?> assemblyCandidate = assertInstanceOf(Map.class, candidates.get(0));
        assertEquals("Assembly", assemblyCandidate.get("type"));
        assertEquals("owl-rl", assemblyCandidate.get("source"));
        assertEquals(List.of("https://example.org/ontology#Assembly"), assemblyCandidate.get("evidence"));

        Map<?, ?> componentCandidate = assertInstanceOf(Map.class, candidates.get(1));
        assertEquals("Component", componentCandidate.get("type"));
        assertEquals("owl-rl", componentCandidate.get("source"));
        assertEquals(List.of("https://example.org/ontology#Component"), componentCandidate.get("evidence"));
    }

    @Test
    void owlInferredTypeMaterialization_normalizesMapCandidatesAndDeduplicatesOwlRows() throws Exception {
        GraphOntologyBindingService binding = mock(GraphOntologyBindingService.class);
        KnowledgeGraphService kg = mock(KnowledgeGraphService.class);
        GraphNode entity = entityNode("a", Map.of(
                "entity_type", "Thing",
                "ontology.typeCandidates", Map.of(
                        "Person", 0.35d,
                        "Assembly", Map.of(
                                "confidence", 1.0d,
                                "source", "owl-rl",
                                "basis", "class-subsumption",
                                "evidence", List.of("https://example.org/ontology#Assembly")))));
        when(kg.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY)).thenReturn(List.of(entity));

        OwlReasoningService service = new OwlReasoningService(binding, new OwlOntologyBridge(), kg);
        Method method = OwlReasoningService.class.getDeclaredMethod(
                "materializeInferredTypes", long.class, Map.class);
        method.setAccessible(true);

        Object updated = method.invoke(service, 7L, Map.of("a", "https://example.org/ontology#Assembly"));

        assertEquals(1, updated);
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kg).updateNode(eq("a"), isNull(), isNull(), metadataCaptor.capture());
        List<?> candidates = assertInstanceOf(List.class,
                metadataCaptor.getValue().get("ontology.typeCandidates"));
        assertEquals(2, candidates.size());

        Map<?, ?> personCandidate = candidateByType(candidates, "Person");
        assertEquals("Person", personCandidate.get("type"));
        assertEquals(0.35d, personCandidate.get("confidence"));

        Map<?, ?> owlCandidate = candidateByType(candidates, "Assembly");
        assertEquals("Assembly", owlCandidate.get("type"));
        assertEquals("owl-rl", owlCandidate.get("source"));
        assertEquals("class-subsumption", owlCandidate.get("basis"));
    }

    @Test
    void owlInferredTypeMaterialization_writesCrawlTypeHierarchyMetadata() throws Exception {
        GraphOntologyBindingService binding = mock(GraphOntologyBindingService.class);
        KnowledgeGraphService kg = mock(KnowledgeGraphService.class);
        GraphNode entity = entityNode("wine-red", Map.of(
                "entity_category", "Wine",
                "entity_type", "RedWine",
                "typeInferenceScore", 0.93d));
        when(kg.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY)).thenReturn(List.of(entity));

        OwlReasoningService service = new OwlReasoningService(binding, new OwlOntologyBridge(), kg);
        Method method = OwlReasoningService.class.getDeclaredMethod(
                "materializeInferredTypes", long.class, Map.class);
        method.setAccessible(true);

        Object updated = method.invoke(service, 7L, Map.of("wine-red", "https://example.org/ontology#Wine"));

        assertEquals(1, updated);
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kg).updateNode(eq("wine-red"), isNull(), isNull(), metadataCaptor.capture());
        List<?> hierarchy = assertInstanceOf(List.class, metadataCaptor.getValue().get("ontology.typeHierarchy"));
        Map<?, ?> row = assertInstanceOf(Map.class, hierarchy.get(0));
        assertEquals("RedWine", row.get("type"));
        assertEquals("Wine", row.get("parentType"));
        assertEquals(0.93d, row.get("confidence"));
        assertEquals("crawl-schema", row.get("source"));
        assertEquals("entity_type/entity_category", row.get("basis"));
    }

    private static Map<?, ?> candidateByType(List<?> candidates, String type) {
        Map<?, ?> found = null;
        for (Object candidate : candidates) {
            Map<?, ?> map = assertInstanceOf(Map.class, candidate);
            if (type.equals(map.get("type"))) {
                found = map;
                break;
            }
        }
        assertNotNull(found, "Expected candidate with type=" + type);
        return found;
    }

    private static GraphNode entityNode(String id) {
        return entityNode(id, Map.of("entity_type", "Thing"));
    }

    private static GraphNode entityNode(String id, Map<String, Object> metadata) {
        GraphNode node = mock(GraphNode.class);
        when(node.getNodeId()).thenReturn(id);
        when(node.getMetadata()).thenReturn(metadata);
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

    private static GraphEdge relationEdge(String id, GraphNode source, GraphNode target, String relationType) {
        return GraphEdge.builder()
                .edgeId(id)
                .sourceNode(source)
                .targetNode(target)
                .edgeType(EdgeType.HIERARCHICAL)
                .relationType(relationType)
                .build();
    }
}
