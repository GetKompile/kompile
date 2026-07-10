/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.app.ontology;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.Cardinality;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import ai.kompile.process.service.ProcessEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Schema resolution from labeled graph relations: domain/range from the dominant type pair
 * (alias-resolved onto canonical class names), cardinality from observed degrees, symmetry into
 * richer metadata — filling gaps only, never overwriting human-authored schema.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelationSchemaResolutionServiceTest {

    private static final long FS = 91L;

    @Mock
    private KnowledgeGraphService graph;

    @Mock
    private GraphOntologyBindingService bindingService;

    @Mock
    private ProcessEngineService processEngineService;

    @InjectMocks
    private RelationSchemaResolutionService service;

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();

    private void node(String id, String entityType) {
        nodes.add(GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).title(id)
                .metadataJson("{\"entity_type\":\"" + entityType + "\"}")
                .build());
    }

    private void edge(String from, String to, String label) {
        edges.add(GraphEdge.builder()
                .edgeId(from + ">" + to + ">" + label)
                .sourceNodeId(from).targetNodeId(to)
                .relationType(label)
                .build());
    }

    private static EntityTypeDefinition type(String name, String... aliases) {
        return EntityTypeDefinition.builder().name(name).aliases(List.of(aliases)).build();
    }

    @BeforeEach
    void buildGraphAndSchema() {
        node("p1", "PERSON");
        node("p2", "PERSON");
        node("p3", "PERSON");
        node("e1", "EMAIL_MESSAGE");
        node("e2", "EMAIL_MESSAGE");
        node("e3", "EMAIL_MESSAGE");
        node("e4", "EMAIL_MESSAGE");
        node("o1", "ORGANIZATION");
        node("i1", "INVOICE");
        node("i2", "INVOICE");

        // SENT_BY person→email ×4: 2 sources × 2 targets each → ONE_TO_MANY, dominant pair 100%.
        edge("p1", "e1", "SENT_BY");
        edge("p1", "e2", "SENT_BY");
        edge("p2", "e3", "SENT_BY");
        edge("p2", "e4", "SENT_BY");
        // BELONGS_TO person→org ×3 into ONE org → MANY_TO_ONE.
        edge("p1", "o1", "BELONGS_TO");
        edge("p2", "o1", "BELONGS_TO");
        edge("p3", "o1", "BELONGS_TO");
        // COLLABORATES_WITH: every edge has its reverse → symmetric, MANY_TO_MANY.
        edge("p1", "p2", "COLLABORATES_WITH");
        edge("p2", "p1", "COLLABORATES_WITH");
        edge("p1", "p3", "COLLABORATES_WITH");
        edge("p3", "p1", "COLLABORATES_WITH");
        // MENTIONS ×2 — below MIN_SUPPORT, must not become schema.
        edge("e1", "i1", "MENTIONS");
        edge("e2", "i2", "MENTIONS");

        when(graph.getNodesInFactSheet(FS)).thenReturn(nodes);
        when(graph.getEdgesInFactSheet(FS)).thenReturn(edges);
    }

    private OntologySchema schemaWithGaps() {
        // SENT_BY exists but untyped; BELONGS_TO carries a HUMAN-set source that must survive.
        return OntologySchema.builder()
                .id("ont-9").version(1)
                .entityTypes(List.of(
                        type("Person", "PERSON"),
                        type("EmailMessage", "EMAIL_MESSAGE"),
                        type("Organization", "ORGANIZATION")))
                .relationshipTypes(new ArrayList<>(List.of(
                        RelationshipTypeDefinition.builder().type("SENT_BY").build(),
                        RelationshipTypeDefinition.builder().type("BELONGS_TO")
                                .sourceEntityType("Member").build())))
                .build();
    }

    @Test
    void resolvesDomainRangeCardinalityAndSymmetry_fillingGapsOnly() {
        OntologySchema schema = schemaWithGaps();
        when(bindingService.resolveActiveOntology(FS)).thenReturn(Optional.of(schema));
        OntologySchema updated = OntologySchema.builder().id("ont-9").version(2).build();
        when(processEngineService.updateOntology(eq("ont-9"), any())).thenReturn(updated);

        RelationSchemaResolutionService.Result result = service.resolveRelationSchema(FS);

        assertTrue(result.changed());
        assertEquals(1, result.definitionsAdded(), "COLLABORATES_WITH is new");
        assertEquals(2, result.definitionsEnriched(), "SENT_BY and BELONGS_TO gained metadata/typing");

        ArgumentCaptor<OntologySchema> captor = ArgumentCaptor.forClass(OntologySchema.class);
        verify(processEngineService).updateOntology(eq("ont-9"), captor.capture());
        Map<String, RelationshipTypeDefinition> byType = byType(captor.getValue());

        RelationshipTypeDefinition sentBy = byType.get("SENT_BY");
        assertEquals("Person", sentBy.getSourceEntityType(), "alias PERSON resolves to canonical class");
        assertEquals("EmailMessage", sentBy.getTargetEntityType());
        assertEquals(Cardinality.ONE_TO_MANY, sentBy.getCardinality());
        assertTrue(sentBy.getMetadata().containsKey("relationResolution"));

        RelationshipTypeDefinition belongsTo = byType.get("BELONGS_TO");
        assertEquals("Member", belongsTo.getSourceEntityType(), "human-authored value must stand");
        assertEquals("Organization", belongsTo.getTargetEntityType(), "gap filled");
        assertEquals(Cardinality.MANY_TO_ONE, belongsTo.getCardinality());

        RelationshipTypeDefinition collaborates = byType.get("COLLABORATES_WITH");
        assertEquals(Cardinality.MANY_TO_MANY, collaborates.getCardinality());
        @SuppressWarnings("unchecked")
        Map<String, Object> resolution =
                (Map<String, Object>) collaborates.getMetadata().get("relationResolution");
        assertEquals(Boolean.TRUE, resolution.get("symmetric"),
                "fully-reversed relation is an owl:SymmetricProperty candidate");

        assertNull(byType.get("MENTIONS"), "below MIN_SUPPORT must not become schema");
        verify(bindingService).bindOntology(FS, "ont-9", 2);
    }

    @Test
    void secondPassOverSameGraph_isIdempotent() {
        OntologySchema schema = schemaWithGaps();
        when(bindingService.resolveActiveOntology(FS)).thenReturn(Optional.of(schema));
        when(processEngineService.updateOntology(eq("ont-9"), any()))
                .thenAnswer(inv -> inv.getArgument(1));

        assertTrue(service.resolveRelationSchema(FS).changed());
        // The (mutated) schema is what resolveActiveOntology now returns — nothing left to fill.
        RelationSchemaResolutionService.Result second = service.resolveRelationSchema(FS);
        assertFalse(second.changed(), "same graph, enriched schema → no further update");
        verify(processEngineService, org.mockito.Mockito.times(1)).updateOntology(any(), any());
    }

    @Test
    void noOntologyResolvable_isANoOp() {
        when(bindingService.resolveActiveOntology(FS)).thenReturn(Optional.empty());
        when(bindingService.autoProvisionStructuralOntology(FS)).thenReturn(Optional.empty());
        assertFalse(service.resolveRelationSchema(FS).changed());
        verify(processEngineService, never()).updateOntology(any(), any());
    }

    private static Map<String, RelationshipTypeDefinition> byType(OntologySchema schema) {
        Map<String, RelationshipTypeDefinition> map = new java.util.LinkedHashMap<>();
        schema.getRelationshipTypes().forEach(r -> map.put(r.getType(), r));
        return map;
    }
}
