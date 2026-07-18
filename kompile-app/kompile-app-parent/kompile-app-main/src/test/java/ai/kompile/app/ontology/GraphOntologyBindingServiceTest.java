/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.ontology;

import ai.kompile.app.web.dto.ontology.GraphConformanceReport;
import ai.kompile.core.graphrag.conformance.GraphConformanceSummary;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.Cardinality;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.FieldDefinition;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GraphOntologyBindingService} — the app-main bridge that resolves the active
 * ontology for a fact sheet (A-2: via the ProcessDefinition link) and validates the graph's ENTITY
 * nodes against it using the A-1 conformance engine.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphOntologyBindingServiceTest {

    @Mock private ProcessEngineService processEngineService;
    @Mock private KnowledgeGraphService knowledgeGraphService;
    @Mock private OntologyDerivationService ontologyDerivationService;

    private GraphOntologyBindingService service;

    private static final Long FS = 1L;

    @BeforeEach
    void setUp() {
        service = new GraphOntologyBindingService(processEngineService, knowledgeGraphService,
                ontologyDerivationService);
    }

    @Test
    void autoProvision_whenAlreadyBound_returnsExistingWithoutDeriving() {
        OntologySchema bound = OntologySchema.builder().id("o1").version(2).name("bound").build();
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet("__graph_ontology_binding__", NodeLevel.CUSTOM, FS))
                .thenReturn(Optional.of(bindingNode("binding-1", "o1", 2)));
        when(processEngineService.getOntology("o1", 2)).thenReturn(bound);

        Optional<OntologySchema> result = service.autoProvisionStructuralOntology(FS);

        assertTrue(result.isPresent());
        assertEquals("o1", result.get().getId());
        verify(ontologyDerivationService, never()).deriveStructuralDraft(any());
        verify(processEngineService, never()).createOntology(any());
    }

    @Test
    void autoProvision_whenUnbound_derivesSavesAndBinds() {
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet("__graph_ontology_binding__", NodeLevel.CUSTOM, FS))
                .thenReturn(Optional.empty());
        when(processEngineService.listProcessDefinitions()).thenReturn(List.of());

        OntologySchema draft = OntologySchema.builder().name("auto").build();
        when(ontologyDerivationService.deriveStructuralDraft(FS)).thenReturn(draft);
        OntologySchema saved = OntologySchema.builder().id("auto-1").version(1).name("auto").build();
        when(processEngineService.createOntology(draft)).thenReturn(saved);
        when(processEngineService.getOntology("auto-1", 1)).thenReturn(saved);
        when(knowledgeGraphService.createNode(eq(NodeLevel.CUSTOM), eq("__graph_ontology_binding__"),
                anyString(), anyString(), anyMap(), eq(FS)))
                .thenReturn(bindingNode("binding-1", "auto-1", 1));

        Optional<OntologySchema> result = service.autoProvisionStructuralOntology(FS);

        assertTrue(result.isPresent());
        assertEquals("auto-1", result.get().getId());
        verify(processEngineService).createOntology(draft);
        verify(knowledgeGraphService).createNode(eq(NodeLevel.CUSTOM), eq("__graph_ontology_binding__"),
                anyString(), anyString(), anyMap(), eq(FS));
    }

    private GraphNode bindingNode(String nodeId, String ontologyId, Integer version) {
        return GraphNode.builder().nodeId(nodeId).externalId("__graph_ontology_binding__")
                .nodeType(NodeLevel.CUSTOM).factSheetId(FS)
                .title("Graph ontology binding")
                .metadataJson("{\"ontologySchemaId\":\"" + ontologyId
                        + "\",\"ontologyVersion\":" + version + "}").build();
    }

    private OntologySchema accountOntology() {
        return OntologySchema.builder().id("ont-1").name("FPnA").version(2)
                .entityTypes(List.of(EntityTypeDefinition.builder().name("Account")
                        .fields(List.of(FieldDefinition.builder().name("code").required(true).regex("[0-9]{4}").build()))
                        .build()))
                .build();
    }

    private ProcessDefinition def(String id, String ontologyId, int ontologyVersion, Long factSheetId,
                                  ProcessStatus status) {
        return ProcessDefinition.builder()
                .id(id).ontologySchemaId(ontologyId).ontologyVersion(ontologyVersion)
                .factSheetId(factSheetId).status(status).build();
    }

    private GraphNode entity(String id, String metadataJson) {
        return GraphNode.builder().nodeId(id).nodeType(NodeLevel.ENTITY).title(id).metadataJson(metadataJson).build();
    }

    private GraphEdge edge(String edgeId, GraphNode source, GraphNode target, String relationType) {
        return GraphEdge.builder().edgeId(edgeId).sourceNode(source).targetNode(target)
                .relationType(relationType).edgeType(EdgeType.USER_DEFINED).weight(1.0).build();
    }

    @Test
    void resolvesOntologyViaProcessDefinitionLink() {
        when(processEngineService.listProcessDefinitions())
                .thenReturn(List.of(def("p1", "ont-1", 2, FS, ProcessStatus.APPROVED)));
        when(processEngineService.getOntology("ont-1", 2)).thenReturn(accountOntology());

        Optional<OntologySchema> resolved = service.resolveActiveOntology(FS);

        assertTrue(resolved.isPresent());
        assertEquals("ont-1", resolved.get().getId());
    }

    @Test
    void emptyWhenNoDefinitionBindsThisFactSheet() {
        when(processEngineService.listProcessDefinitions())
                .thenReturn(List.of(def("p1", "ont-1", 2, 999L, ProcessStatus.APPROVED)));
        assertTrue(service.resolveActiveOntology(FS).isEmpty());
    }

    @Test
    void prefersApprovedDefinitionOverDraft() {
        when(processEngineService.listProcessDefinitions()).thenReturn(List.of(
                def("draft", "draft-ont", 1, FS, ProcessStatus.DRAFT),
                def("appr", "ont-1", 2, FS, ProcessStatus.APPROVED)));
        when(processEngineService.getOntology("ont-1", 2)).thenReturn(accountOntology());

        Optional<OntologySchema> resolved = service.resolveActiveOntology(FS);

        assertTrue(resolved.isPresent());
        assertEquals("ont-1", resolved.get().getId());
        verify(processEngineService, never()).getOntology(eq("draft-ont"), anyInt());
    }

    @Test
    void checkConformanceCountsUnknownTypeAndFieldViolations() {
        when(processEngineService.listProcessDefinitions())
                .thenReturn(List.of(def("p1", "ont-1", 2, FS, ProcessStatus.APPROVED)));
        when(processEngineService.getOntology("ont-1", 2)).thenReturn(accountOntology());
        when(knowledgeGraphService.getNodesInFactSheetPage(FS, 0, 1_000)).thenReturn(
                new KnowledgeGraphService.GraphPage<>(List.of(
                        entity("n1", "{\"entity_type\":\"Account\",\"code\":\"1234\"}"),
                        entity("n2", "{\"entity_type\":\"Vendor\"}"),
                        entity("n3", "{\"entity_type\":\"Account\"}")), 3, false));
        when(knowledgeGraphService.getEdgesInFactSheetPage(FS, 0, 1_000)).thenReturn(
                new KnowledgeGraphService.GraphPage<>(List.of(), 0, false));

        GraphConformanceReport report = service.checkConformance(FS);

        assertTrue(report.ontologyBound());
        assertEquals("ont-1", report.ontologyId());
        assertEquals(2, report.ontologyVersion());
        assertEquals(3, report.entitiesChecked());
        assertEquals(1, report.unknownTypeCount());
        assertEquals(2, report.nonConformantCount());
        assertEquals(2, report.violations().size());
        assertEquals(0.3333, report.conformanceScore(), 1e-9, "1 of 3 entities conform");
    }

    @Test
    void checkConformanceValidatesRelationshipsAgainstOntology() {
        OntologySchema schema = OntologySchema.builder().id("ont-1").name("FPnA").version(2)
                .entityTypes(List.of(EntityTypeDefinition.builder().name("Account").build()))
                .relationshipTypes(List.of(RelationshipTypeDefinition.builder()
                        .type("FEEDS_INTO").sourceEntityType("Account").targetEntityType("Account")
                        .cardinality(Cardinality.ONE_TO_MANY).build()))
                .build();
        when(processEngineService.listProcessDefinitions())
                .thenReturn(List.of(def("p1", "ont-1", 2, FS, ProcessStatus.APPROVED)));
        when(processEngineService.getOntology("ont-1", 2)).thenReturn(schema);
        GraphNode a = entity("a", "{\"entity_type\":\"Account\"}");
        GraphNode b = entity("b", "{\"entity_type\":\"Account\"}");
        when(knowledgeGraphService.getNodesInFactSheetPage(FS, 0, 1_000)).thenReturn(
                new KnowledgeGraphService.GraphPage<>(List.of(a, b), 2, false));
        when(knowledgeGraphService.getNodesByIds(anyList())).thenReturn(List.of(a, b));
        when(knowledgeGraphService.getEdgesInFactSheetPage(FS, 0, 1_000)).thenReturn(
                new KnowledgeGraphService.GraphPage<>(List.of(
                        edge("e1", a, b, "FEEDS_INTO"),
                        edge("e2", a, b, "MENTORS"),
                        edge("e3", a, b, null)), 3, false));

        GraphConformanceReport report = service.checkConformance(FS);

        assertEquals(2, report.edgesChecked(), "the structural (null relationType) edge is skipped");
        assertEquals(1, report.nonConformantEdgeCount());
        assertEquals(1, report.edgeViolations().size());
        assertEquals("MENTORS", report.edgeViolations().get(0).relationshipType());
    }

    @Test
    void checkConformanceFlagsCardinalityBreachOncePerSource() {
        OntologySchema schema = OntologySchema.builder().id("ont-1").name("FPnA").version(2)
                .entityTypes(List.of(EntityTypeDefinition.builder().name("Account").build()))
                .relationshipTypes(List.of(RelationshipTypeDefinition.builder()
                        .type("REPORTS_TO").sourceEntityType("Account").targetEntityType("Account")
                        .cardinality(Cardinality.MANY_TO_ONE).build()))  // each source reports to ≤1 target
                .build();
        when(processEngineService.listProcessDefinitions())
                .thenReturn(List.of(def("p1", "ont-1", 2, FS, ProcessStatus.APPROVED)));
        when(processEngineService.getOntology("ont-1", 2)).thenReturn(schema);
        GraphNode a = entity("a", "{\"entity_type\":\"Account\"}");
        GraphNode b = entity("b", "{\"entity_type\":\"Account\"}");
        GraphNode c = entity("c", "{\"entity_type\":\"Account\"}");
        when(knowledgeGraphService.getNodesInFactSheetPage(FS, 0, 1_000)).thenReturn(
                new KnowledgeGraphService.GraphPage<>(List.of(a, b, c), 3, false));
        when(knowledgeGraphService.getNodesByIds(anyList())).thenReturn(List.of(a, b, c));
        when(knowledgeGraphService.getEdgesInFactSheetPage(FS, 0, 1_000)).thenReturn(
                new KnowledgeGraphService.GraphPage<>(List.of(
                        edge("e1", a, b, "REPORTS_TO"),
                        edge("e2", a, c, "REPORTS_TO")), 2, false));

        GraphConformanceReport report = service.checkConformance(FS);

        assertEquals(2, report.edgesChecked());
        assertEquals(1, report.nonConformantEdgeCount(), "the (source, relation) breach is flagged once");
        assertTrue(report.edgeViolations().get(0).reason().contains("Cardinality"));
    }

    @Test
    void reportsNotBoundWhenNoOntologyResolves() {
        when(processEngineService.listProcessDefinitions()).thenReturn(List.of());

        GraphConformanceReport report = service.checkConformance(FS);

        assertFalse(report.ontologyBound());
        assertEquals(0, report.entitiesChecked());
        verify(knowledgeGraphService, never()).getNodesByTypeInFactSheet(anyLong(), any());
    }

    @Test
    void checkFactSheetExposesSummaryViaSpi() {
        when(processEngineService.listProcessDefinitions())
                .thenReturn(List.of(def("p1", "ont-1", 2, FS, ProcessStatus.APPROVED)));
        when(processEngineService.getOntology("ont-1", 2)).thenReturn(accountOntology());
        when(knowledgeGraphService.getNodesInFactSheetPage(FS, 0, 1_000)).thenReturn(
                new KnowledgeGraphService.GraphPage<>(
                        List.of(entity("n2", "{\"entity_type\":\"Vendor\"}")), 1, false));
        when(knowledgeGraphService.getEdgesInFactSheetPage(FS, 0, 1_000)).thenReturn(
                new KnowledgeGraphService.GraphPage<>(List.of(), 0, false));

        GraphConformanceSummary summary = service.checkFactSheet(FS);

        assertTrue(summary.ontologyBound());
        assertEquals("FPnA", summary.ontologyName());
        assertEquals(1, summary.entitiesChecked());
        assertEquals(1, summary.unknownTypeCount());
        assertEquals(1, summary.nonConformantCount());
        assertEquals(0.0, summary.conformanceScore(), 1e-9, "the only entity is non-conformant");
    }

    // ── explicit graph-level binding (priority 1) ──────────────────────────────────

    @Test
    void resolvesOntologyViaExplicitGraphBinding() {
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet(
                "__graph_ontology_binding__", NodeLevel.CUSTOM, FS))
                .thenReturn(Optional.of(bindingNode("binding-1", "ont-1", 2)));
        when(processEngineService.getOntology("ont-1", 2)).thenReturn(accountOntology());

        Optional<OntologySchema> resolved = service.resolveActiveOntology(FS);

        assertTrue(resolved.isPresent());
        assertEquals("ont-1", resolved.get().getId());
        // Explicit binding short-circuits before the process-definition fallback is even consulted.
        verify(processEngineService, never()).listProcessDefinitions();
    }

    @Test
    void explicitGraphBindingTakesPriorityOverProcessLink() {
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet(
                "__graph_ontology_binding__", NodeLevel.CUSTOM, FS))
                .thenReturn(Optional.of(bindingNode("binding-1", "ont-explicit", 1)));
        when(processEngineService.getOntology("ont-explicit", 1)).thenReturn(
                OntologySchema.builder().id("ont-explicit").name("Explicit").version(1).build());

        Optional<OntologySchema> resolved = service.resolveActiveOntology(FS);

        assertTrue(resolved.isPresent());
        assertEquals("ont-explicit", resolved.get().getId());
        verify(processEngineService, never()).listProcessDefinitions();
    }

    @Test
    void missingExplicitBindingFallsThroughToProcessLink() {
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet(
                "__graph_ontology_binding__", NodeLevel.CUSTOM, FS)).thenReturn(Optional.empty());
        when(processEngineService.listProcessDefinitions())
                .thenReturn(List.of(def("p1", "ont-1", 2, FS, ProcessStatus.APPROVED)));
        when(processEngineService.getOntology("ont-1", 2)).thenReturn(accountOntology());

        Optional<OntologySchema> resolved = service.resolveActiveOntology(FS);

        assertTrue(resolved.isPresent());
        assertEquals("ont-1", resolved.get().getId());
    }

    // ── bind / unbind management ───────────────────────────────────────────────────

    @Test
    void bindOntologyUpdatesExistingLuceneDescriptor() {
        GraphNode existing = bindingNode("binding-1", "old", 1);
        GraphNode updated = bindingNode("binding-1", "ont-1", 2);
        when(processEngineService.getOntology("ont-1", 2)).thenReturn(accountOntology());
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet(
                "__graph_ontology_binding__", NodeLevel.CUSTOM, FS)).thenReturn(Optional.of(existing));
        when(knowledgeGraphService.updateNode(eq("binding-1"), anyString(), nullable(String.class), anyMap()))
                .thenReturn(updated);

        GraphOntologyBindingService.OntologyBinding result = service.bindOntology(FS, "ont-1", 2);

        assertEquals("ont-1", result.ontologySchemaId());
        assertEquals(2, result.ontologyVersion());
        verify(knowledgeGraphService).updateNode(eq("binding-1"), anyString(), nullable(String.class),
                argThat(metadata -> "ont-1".equals(metadata.get("ontologySchemaId"))
                        && Integer.valueOf(2).equals(metadata.get("ontologyVersion"))));
        verify(knowledgeGraphService, never()).createNode(any(), any(), any(), any(), any(), any());
    }

    @Test
    void bindOntologyCreatesLuceneDescriptorWhenMissing() {
        when(processEngineService.getOntology("ont-1", 2)).thenReturn(accountOntology());
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet(
                "__graph_ontology_binding__", NodeLevel.CUSTOM, FS)).thenReturn(Optional.empty());
        when(knowledgeGraphService.createNode(eq(NodeLevel.CUSTOM), eq("__graph_ontology_binding__"),
                anyString(), anyString(), anyMap(), eq(FS)))
                .thenReturn(bindingNode("binding-new", "ont-1", 2));

        GraphOntologyBindingService.OntologyBinding result = service.bindOntology(FS, "ont-1", 2);

        assertEquals("binding-new", result.descriptorNodeId());
        verify(knowledgeGraphService).createNode(eq(NodeLevel.CUSTOM), eq("__graph_ontology_binding__"),
                anyString(), anyString(), anyMap(), eq(FS));
    }

    @Test
    void bindOntologyRejectsAnUnknownOntologyWithoutWritingGraph() {
        when(processEngineService.getOntology("ghost", 9)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.bindOntology(FS, "ghost", 9));
        verify(knowledgeGraphService, never()).updateNode(anyString(), any(), any(), any());
        verify(knowledgeGraphService, never()).createNode(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unbindOntologyDeletesLuceneDescriptor() {
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet(
                "__graph_ontology_binding__", NodeLevel.CUSTOM, FS))
                .thenReturn(Optional.of(bindingNode("binding-1", "ont-1", 2)));

        service.unbindOntology(FS);

        verify(knowledgeGraphService).deleteNode("binding-1");
    }
}
