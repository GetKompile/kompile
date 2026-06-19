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
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.FieldDefinition;
import ai.kompile.process.ontology.OntologySchema;
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

    private GraphOntologyBindingService service;

    private static final Long FS = 1L;

    @BeforeEach
    void setUp() {
        service = new GraphOntologyBindingService(processEngineService, knowledgeGraphService);
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
        when(knowledgeGraphService.getNodesByTypeInFactSheet(FS, NodeLevel.ENTITY)).thenReturn(List.of(
                entity("n1", "{\"entity_type\":\"Account\",\"code\":\"1234\"}"), // conformant
                entity("n2", "{\"entity_type\":\"Vendor\"}"),                     // unknown type
                entity("n3", "{\"entity_type\":\"Account\"}")));                  // missing required 'code'

        GraphConformanceReport report = service.checkConformance(FS);

        assertTrue(report.ontologyBound());
        assertEquals("ont-1", report.ontologyId());
        assertEquals(2, report.ontologyVersion());
        assertEquals(3, report.entitiesChecked());
        assertEquals(1, report.unknownTypeCount());
        assertEquals(2, report.nonConformantCount());
        assertEquals(2, report.violations().size());
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
        when(knowledgeGraphService.getNodesByTypeInFactSheet(FS, NodeLevel.ENTITY))
                .thenReturn(List.of(entity("n2", "{\"entity_type\":\"Vendor\"}")));

        GraphConformanceSummary summary = service.checkFactSheet(FS);

        assertTrue(summary.ontologyBound());
        assertEquals("FPnA", summary.ontologyName());
        assertEquals(1, summary.entitiesChecked());
        assertEquals(1, summary.unknownTypeCount());
        assertEquals(1, summary.nonConformantCount());
    }
}
