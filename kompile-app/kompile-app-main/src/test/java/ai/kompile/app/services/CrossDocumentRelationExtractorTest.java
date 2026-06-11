/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CrossDocumentRelationExtractor}.
 * All tests use synthetic document metadata.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossDocumentRelationExtractorTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private GraphNodeRepository graphNodeRepository;

    private CrossDocumentRelationExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new CrossDocumentRelationExtractor(knowledgeGraphService, graphNodeRepository);
    }

    private Document makeDoc(String fileName, Map<String, Object> extraMeta) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("fileName", fileName);
        meta.put("source", "/uploads/" + fileName);
        if (extraMeta != null) meta.putAll(extraMeta);
        return new Document("content of " + fileName, meta);
    }

    private void stubNodeLookup(String sourcePath, String nodeId) {
        GraphNode node = new GraphNode();
        node.setNodeId(nodeId);
        when(graphNodeRepository.findByExternalIdAndNodeType(eq(sourcePath), eq(NodeLevel.DOCUMENT)))
                .thenReturn(Optional.of(node));
        when(graphNodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                eq(sourcePath), eq(NodeLevel.DOCUMENT), anyLong()))
                .thenReturn(Optional.of(node));
    }

    @Test
    void returnsZeroForSingleDocument() {
        List<Document> docs = List.of(makeDoc("report.xlsx", null));
        int result = extractor.extractRelations(docs, 1L);
        assertEquals(0, result);
    }

    @Test
    void returnsZeroForNullInput() {
        assertEquals(0, extractor.extractRelations(null, null));
    }

    @Test
    void detectsVersionChainBySuffix() {
        Document v1 = makeDoc("forecast_v1.xlsx", null);
        Document v2 = makeDoc("forecast_v2.xlsx", null);

        stubNodeLookup("/uploads/forecast_v1.xlsx", "node-v1");
        stubNodeLookup("/uploads/forecast_v2.xlsx", "node-v2");

        int result = extractor.extractRelations(List.of(v1, v2), 1L);

        assertTrue(result >= 1, "Should create at least one VERSION_OF edge");
        verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("VERSION_OF"),
                anyString(), contains("VERSION_OF"), any(EdgeProvenance.class), anyLong());
    }

    @Test
    void skipsExistingVersionChainBySemanticLabel() {
        Document v1 = makeDoc("forecast_v1.xlsx", null);
        Document v2 = makeDoc("forecast_v2.xlsx", null);

        stubNodeLookup("/uploads/forecast_v1.xlsx", "node-v1");
        stubNodeLookup("/uploads/forecast_v2.xlsx", "node-v2");
        when(knowledgeGraphService.edgeExists(eq("node-v2"), eq("node-v1"),
                eq(EdgeType.USER_DEFINED), eq("VERSION_OF"), eq(1L))).thenReturn(true);

        int result = extractor.extractRelations(List.of(v1, v2), 1L);

        assertEquals(0, result, "Existing VERSION_OF relation should not be counted again");
        verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("VERSION_OF"),
                anyString(), anyString(), any(EdgeProvenance.class), anyLong());
    }

    @Test
    void detectsVersionChainWithFINALSuffix() {
        Document draft = makeDoc("report_v2.xlsx", null);
        Document finalDoc = makeDoc("report_v2_FINAL.xlsx", null);

        stubNodeLookup("/uploads/report_v2.xlsx", "node-draft");
        stubNodeLookup("/uploads/report_v2_FINAL.xlsx", "node-final");

        int result = extractor.extractRelations(List.of(draft, finalDoc), 1L);

        assertTrue(result >= 1, "Should create VERSION_OF edge for _FINAL variant");
    }

    @Test
    void doesNotCreateVersionChainForUnrelatedFiles() {
        Document fileA = makeDoc("budget.xlsx", null);
        Document fileB = makeDoc("inventory.pdf", null);

        stubNodeLookup("/uploads/budget.xlsx", "node-a");
        stubNodeLookup("/uploads/inventory.pdf", "node-b");

        int result = extractor.extractRelations(List.of(fileA, fileB), 1L);

        assertEquals(0, result, "Unrelated filenames should not create version edges");
    }

    @Test
    void resolvesEmailAttachments() {
        Map<String, Object> emailMeta = new HashMap<>();
        emailMeta.put("email.subject", "Please review");
        emailMeta.put("email.from", "sender@example.com");
        emailMeta.put("email.attachmentNames", List.of("data_report.xlsx"));

        Document email = makeDoc("email_notification.html", emailMeta);
        Document attachment = makeDoc("data_report.xlsx", null);

        stubNodeLookup("/uploads/email_notification.html", "node-email");
        stubNodeLookup("/uploads/data_report.xlsx", "node-attachment");

        int result = extractor.extractRelations(List.of(email, attachment), 1L);

        assertTrue(result >= 1, "Should create ATTACHMENT_OF edge");
        verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                eq("node-email"), eq("node-attachment"), eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("ATTACHMENT_OF"),
                anyString(), contains("ATTACHMENT_OF"), any(EdgeProvenance.class), anyLong());
    }

    @Test
    void detectsProcessToDataReferences() {
        Document process = new Document(
                "The reconciliation procedure requires validating data from the Budget Summary " +
                "sheet. Each entry must be cross-referenced with the control library.",
                Map.of("fileName", "process_map.html",
                       "source", "/uploads/process_map.html",
                       "content_type", "html"));

        Document spreadsheet = new Document(
                "Pipeline data content...",
                Map.of("fileName", "report.xlsx",
                       "source", "/uploads/report.xlsx",
                       "content_type", "table",
                       "sheetName", "Budget Summary"));

        stubNodeLookup("/uploads/process_map.html", "node-process");
        stubNodeLookup("/uploads/report.xlsx", "node-spreadsheet");

        int result = extractor.extractRelations(List.of(process, spreadsheet), 1L);

        assertTrue(result >= 1, "Should detect process document referencing sheet name");
    }

    @Test
    void skipsExistingProcessToDataReferenceBySemanticLabel() {
        Document process = new Document(
                "The reconciliation procedure requires validating data from the Budget Summary " +
                "sheet. Each entry must be cross-referenced with the control library.",
                Map.of("fileName", "process_map.html",
                       "source", "/uploads/process_map.html",
                       "content_type", "html"));

        Document spreadsheet = new Document(
                "Pipeline data content...",
                Map.of("fileName", "report.xlsx",
                       "source", "/uploads/report.xlsx",
                       "content_type", "table",
                       "sheetName", "Budget Summary"));

        stubNodeLookup("/uploads/process_map.html", "node-process");
        stubNodeLookup("/uploads/report.xlsx", "node-spreadsheet");
        when(knowledgeGraphService.edgeExists(eq("node-process"), eq("node-spreadsheet"),
                eq(EdgeType.USER_DEFINED), eq("REFERENCES_DATA"), eq(1L))).thenReturn(true);

        int result = extractor.extractRelations(List.of(process, spreadsheet), 1L);

        assertEquals(0, result, "Existing REFERENCES_DATA relation should not be counted again");
        verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("REFERENCES_DATA"),
                anyString(), anyString(), any(EdgeProvenance.class), anyLong());
    }

    @Test
    void skipsExistingHyperlinkBySemanticLabel() {
        Map<String, Object> annotationMeta = new HashMap<>();
        annotationMeta.put("fileName", "report_annotations.pdf");
        annotationMeta.put("source", "/uploads/report_annotations.pdf");
        annotationMeta.put("extractionType", "annotations");

        Document annotations = new Document(
                "See also: https://example.com/docs/technical_spec.pdf for implementation details.",
                annotationMeta);
        Document target = makeDoc("technical_spec.pdf", null);

        stubNodeLookup("/uploads/report_annotations.pdf", "node-annotations");
        stubNodeLookup("/uploads/technical_spec.pdf", "node-target");
        when(knowledgeGraphService.edgeExists(eq("node-annotations"), eq("node-target"),
                eq(EdgeType.USER_DEFINED), eq("HYPERLINK_TO"), eq(1L))).thenReturn(true);

        int result = extractor.extractRelations(List.of(annotations, target), 1L);

        assertEquals(0, result, "Existing HYPERLINK_TO relation should not be counted again");
        verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("HYPERLINK_TO"),
                anyString(), anyString(), any(EdgeProvenance.class), anyLong());
    }

    @Test
    void allEdgesUseUserDefinedType() {
        Document v1 = makeDoc("report_v1.xlsx", null);
        Document v2 = makeDoc("report_v2.xlsx", null);

        stubNodeLookup("/uploads/report_v1.xlsx", "node-v1");
        stubNodeLookup("/uploads/report_v2.xlsx", "node-v2");

        extractor.extractRelations(List.of(v1, v2), 1L);

        verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                anyString(), anyString(), not(eq(EdgeType.USER_DEFINED)),
                anyDouble(), anyString(), anyString(), anyString(),
                any(EdgeProvenance.class), anyLong());
    }
}
