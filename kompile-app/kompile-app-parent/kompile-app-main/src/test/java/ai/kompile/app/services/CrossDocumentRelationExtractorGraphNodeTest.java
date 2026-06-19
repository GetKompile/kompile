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
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CrossDocumentRelationExtractor#extractRelationsFromGraphNodes(Long)}.
 * Covers all six strategies (A–F) operating on persisted GraphNode objects.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossDocumentRelationExtractorGraphNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Long FACT_SHEET_ID = 42L;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private CrossDocumentRelationExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new CrossDocumentRelationExtractor(knowledgeGraphService);
        // Default: no edges exist yet (both 2-arg and 5-arg overloads)
        when(knowledgeGraphService.edgeExists(anyString(), anyString())).thenReturn(false);
        when(knowledgeGraphService.edgeExists(anyString(), anyString(), any(), anyString(), anyLong())).thenReturn(false);
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    private GraphNode makeDocNode(String nodeId, String title, Map<String, Object> metadata) {
        GraphNode node = new GraphNode();
        node.setNodeId(nodeId);
        node.setTitle(title);
        node.setNodeType(NodeLevel.DOCUMENT);
        if (metadata != null && !metadata.isEmpty()) {
            try {
                node.setMetadataJson(MAPPER.writeValueAsString(metadata));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return node;
    }

    private GraphNode makeDocNode(String nodeId, String title) {
        return makeDocNode(nodeId, title, null);
    }

    private GraphNode makeTableNode(String nodeId, String title, GraphNode parent, Map<String, Object> metadata) {
        GraphNode node = new GraphNode();
        node.setNodeId(nodeId);
        node.setTitle(title);
        node.setNodeType(NodeLevel.TABLE);
        node.setParent(parent);
        if (metadata != null && !metadata.isEmpty()) {
            try {
                node.setMetadataJson(MAPPER.writeValueAsString(metadata));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return node;
    }

    private void stubDocNodes(Long factSheetId, GraphNode... nodes) {
        List<GraphNode> list = Arrays.asList(nodes);
        if (factSheetId != null) {
            when(knowledgeGraphService.getNodesByTypeInFactSheet(eq(factSheetId), eq(NodeLevel.DOCUMENT)))
                    .thenReturn(list);
        } else {
            when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(list);
        }
    }

    private void stubTableNodes(Long factSheetId, GraphNode... nodes) {
        List<GraphNode> list = Arrays.asList(nodes);
        if (factSheetId != null) {
            when(knowledgeGraphService.getNodesByTypeInFactSheet(eq(factSheetId), eq(NodeLevel.TABLE)))
                    .thenReturn(list);
        } else {
            when(knowledgeGraphService.getNodesByType(NodeLevel.TABLE)).thenReturn(list);
        }
    }

    // =========================================================================
    // Guard / boundary tests
    // =========================================================================

    @Nested
    class Guards {

        @Test
        void returnsZeroWhenFewerThanTwoDocNodes() {
            GraphNode single = makeDocNode("n1", "report.xlsx");
            stubDocNodes(FACT_SHEET_ID, single);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            assertEquals(0, result);
            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    anyString(), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void returnsZeroWhenNoDocNodes() {
            when(knowledgeGraphService.getNodesByTypeInFactSheet(FACT_SHEET_ID, NodeLevel.DOCUMENT))
                    .thenReturn(Collections.emptyList());

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);
            assertEquals(0, result);
        }

        @Test
        void returnsZeroWhenRepositoryReturnsNull() {
            when(knowledgeGraphService.getNodesByTypeInFactSheet(FACT_SHEET_ID, NodeLevel.DOCUMENT))
                    .thenReturn(null);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);
            assertEquals(0, result);
        }

        @Test
        void usesGlobalQueryWhenFactSheetIdIsNull() {
            GraphNode n1 = makeDocNode("n1", "report_v1.xlsx");
            GraphNode n2 = makeDocNode("n2", "report_v2.xlsx");
            stubDocNodes(null, n1, n2);
            // TABLE query for strategy C
            when(knowledgeGraphService.getNodesByType(NodeLevel.TABLE))
                    .thenReturn(Collections.emptyList());

            extractor.extractRelationsFromGraphNodes(null);

            verify(knowledgeGraphService).getNodesByType(NodeLevel.DOCUMENT);
            verify(knowledgeGraphService, never()).getNodesByTypeInFactSheet(anyLong(), eq(NodeLevel.DOCUMENT));
        }

        @Test
        void doesNotPropagateExceptionFromStrategy() {
            GraphNode n1 = makeDocNode("n1", "report_v1.xlsx");
            GraphNode n2 = makeDocNode("n2", "report_v2.xlsx");
            stubDocNodes(FACT_SHEET_ID, n1, n2);
            stubTableNodes(FACT_SHEET_ID);

            // Make version chain creation throw — should be caught, not propagated
            when(knowledgeGraphService.createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    anyString(), anyString(), anyString(), any(), anyLong()))
                    .thenThrow(new RuntimeException("DB down"));

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);
            // Should not throw; returns 0 since no edges were successfully created
            assertEquals(0, result);
        }
    }

    // =========================================================================
    // Strategy A: Attachment resolution
    // =========================================================================

    @Nested
    class StrategyA_Attachments {

        @Test
        void resolvesEmailAttachmentByExactName() {
            Map<String, Object> emailMeta = new LinkedHashMap<>();
            emailMeta.put("email.attachmentNames", List.of("data_report.xlsx"));
            emailMeta.put("email.subject", "Please review");

            GraphNode email = makeDocNode("email-1", "email_notification.html", emailMeta);
            GraphNode attachment = makeDocNode("att-1", "data_report.xlsx");

            stubDocNodes(FACT_SHEET_ID, email, attachment);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            assertTrue(result >= 1, "Should create ATTACHMENT_OF edge");
            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("email-1"), eq("att-1"), eq(EdgeType.USER_DEFINED),
                    eq(0.95), eq("ATTACHMENT_OF"),
                    anyString(), contains("ATTACHMENT_OF"), eq(EdgeProvenance.EXTRACTED), eq(FACT_SHEET_ID));
        }

        @Test
        void resolvesSingleAttachmentNameFallback() {
            Map<String, Object> emailMeta = new LinkedHashMap<>();
            emailMeta.put("email.attachmentName", "budget.pdf");

            GraphNode email = makeDocNode("email-2", "message.eml", emailMeta);
            GraphNode attachment = makeDocNode("att-2", "budget.pdf");

            stubDocNodes(FACT_SHEET_ID, email, attachment);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            assertTrue(result >= 1);
            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("email-2"), eq("att-2"), eq(EdgeType.USER_DEFINED),
                    eq(0.95), eq("ATTACHMENT_OF"),
                    anyString(), anyString(), eq(EdgeProvenance.EXTRACTED), eq(FACT_SHEET_ID));
        }

        @Test
        void skipsAttachmentWhenEdgeAlreadyExists() {
            Map<String, Object> emailMeta = new LinkedHashMap<>();
            emailMeta.put("email.attachmentNames", List.of("data.xlsx"));

            GraphNode email = makeDocNode("email-3", "msg.html", emailMeta);
            GraphNode attachment = makeDocNode("att-3", "data.xlsx");

            stubDocNodes(FACT_SHEET_ID, email, attachment);
            stubTableNodes(FACT_SHEET_ID);

            when(knowledgeGraphService.edgeExists(eq("email-3"), eq("att-3"), any(), eq("ATTACHMENT_OF"), eq(FACT_SHEET_ID))).thenReturn(true);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // Only version/author/keyword strategies may fire, but attachment should not
            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    eq("email-3"), eq("att-3"), any(), anyDouble(),
                    eq("ATTACHMENT_OF"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void resolvesAttachmentByFuzzyMatch() {
            Map<String, Object> emailMeta = new LinkedHashMap<>();
            // Email references "data_report.xlsx" but the file is versioned as "data_report_v2.xlsx"
            emailMeta.put("email.attachmentNames", List.of("data_report.xlsx"));

            GraphNode email = makeDocNode("email-4", "msg.html", emailMeta);
            GraphNode attachment = makeDocNode("att-4", "data_report_v2.xlsx");

            stubDocNodes(FACT_SHEET_ID, email, attachment);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // findNodeByFileName should fuzzy-match stripping _v2
            verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                    eq("email-4"), eq("att-4"), eq(EdgeType.USER_DEFINED),
                    anyDouble(), eq("ATTACHMENT_OF"),
                    anyString(), anyString(), any(EdgeProvenance.class), eq(FACT_SHEET_ID));
        }

        @Test
        void resolvesMultipleAttachments() {
            Map<String, Object> emailMeta = new LinkedHashMap<>();
            emailMeta.put("email.attachmentNames", List.of("report.pdf", "budget.xlsx"));

            GraphNode email = makeDocNode("email-5", "memo.html", emailMeta);
            GraphNode att1 = makeDocNode("att-5a", "report.pdf");
            GraphNode att2 = makeDocNode("att-5b", "budget.xlsx");

            stubDocNodes(FACT_SHEET_ID, email, att1, att2);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("email-5"), eq("att-5a"), eq(EdgeType.USER_DEFINED),
                    eq(0.95), eq("ATTACHMENT_OF"),
                    anyString(), anyString(), eq(EdgeProvenance.EXTRACTED), eq(FACT_SHEET_ID));
            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("email-5"), eq("att-5b"), eq(EdgeType.USER_DEFINED),
                    eq(0.95), eq("ATTACHMENT_OF"),
                    anyString(), anyString(), eq(EdgeProvenance.EXTRACTED), eq(FACT_SHEET_ID));
        }
    }

    // =========================================================================
    // Strategy B: Version chain detection
    // =========================================================================

    @Nested
    class StrategyB_VersionChains {

        @Test
        void detectsVersionChainBySuffix() {
            GraphNode v1 = makeDocNode("node-v1", "forecast_v1.xlsx");
            GraphNode v2 = makeDocNode("node-v2", "forecast_v2.xlsx");

            stubDocNodes(FACT_SHEET_ID, v1, v2);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            assertTrue(result >= 1, "Should create VERSION_OF edge");
            // Newer (v2) → older (v1)
            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("node-v2"), eq("node-v1"), eq(EdgeType.USER_DEFINED),
                    eq(0.85), eq("VERSION_OF"),
                    anyString(), contains("VERSION_OF"), eq(EdgeProvenance.INFERRED), eq(FACT_SHEET_ID));
        }

        @Test
        void detectsFINALSuffix() {
            GraphNode v1 = makeDocNode("node-d", "report_v2.xlsx");
            GraphNode vFinal = makeDocNode("node-f", "report_v2_FINAL.xlsx");

            stubDocNodes(FACT_SHEET_ID, v1, vFinal);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            assertTrue(result >= 1, "Should create VERSION_OF for _FINAL variant");
        }

        @Test
        void threeVersionsCreateTwoEdges() {
            GraphNode v1 = makeDocNode("n-v1", "budget_v1.xlsx");
            GraphNode v2 = makeDocNode("n-v2", "budget_v2.xlsx");
            GraphNode v3 = makeDocNode("n-v3", "budget_v3.xlsx");

            stubDocNodes(FACT_SHEET_ID, v3, v1, v2); // out of order
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // v2→v1, v3→v2
            assertTrue(result >= 2, "Three versions should create 2 chain edges");
        }

        @Test
        void unrelatedFilesDoNotCreateVersionEdge() {
            GraphNode a = makeDocNode("a", "budget.xlsx");
            GraphNode b = makeDocNode("b", "inventory.pdf");

            stubDocNodes(FACT_SHEET_ID, a, b);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("VERSION_OF"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void skipsVersionEdgeWhenAlreadyExists() {
            GraphNode v1 = makeDocNode("n1", "data_v1.csv");
            GraphNode v2 = makeDocNode("n2", "data_v2.csv");

            stubDocNodes(FACT_SHEET_ID, v1, v2);
            stubTableNodes(FACT_SHEET_ID);

            when(knowledgeGraphService.edgeExists(eq("n2"), eq("n1"), any(), eq("VERSION_OF"), eq(FACT_SHEET_ID))).thenReturn(true);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    eq("n2"), eq("n1"), any(), anyDouble(),
                    eq("VERSION_OF"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void detectsDateBasedVersions() {
            GraphNode a = makeDocNode("nd1", "report_2024-01-15.xlsx");
            GraphNode b = makeDocNode("nd2", "report_2024-02-15.xlsx");

            stubDocNodes(FACT_SHEET_ID, a, b);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // Both normalize to "report" base name, so should create a VERSION_OF edge
            assertTrue(result >= 1, "Date-suffixed versions should be chained");
        }
    }

    // =========================================================================
    // Strategy C: Process-to-data references
    // =========================================================================

    @Nested
    class StrategyC_ProcessDataReferences {

        @Test
        void detectsSheetNameReferenceInContentPreview() {
            GraphNode processDoc = makeDocNode("proc-1", "process_map.html");
            processDoc.setDescription("The reconciliation procedure validates the Budget Summary sheet.");
            processDoc.setContentPreview("Cross-reference data from Budget Summary for accuracy.");

            GraphNode spreadsheetDoc = makeDocNode("sheet-1", "report.xlsx");

            GraphNode tableNode = makeTableNode("tbl-1", "Budget Summary", spreadsheetDoc, null);

            stubDocNodes(FACT_SHEET_ID, processDoc, spreadsheetDoc);
            stubTableNodes(FACT_SHEET_ID, tableNode);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("proc-1"), eq("sheet-1"), eq(EdgeType.USER_DEFINED),
                    eq(0.7), eq("REFERENCES_DATA"),
                    anyString(), contains("REFERENCES_DATA"), eq(EdgeProvenance.INFERRED), eq(FACT_SHEET_ID));
        }

        @Test
        void detectsColumnHeaderReference() {
            GraphNode processDoc = makeDocNode("proc-2", "workflow.html");
            processDoc.setDescription("Check the Revenue Forecast column for quarterly projections and trends.");
            processDoc.setContentPreview("The Revenue Forecast column is critical for planning.");

            GraphNode spreadsheetDoc = makeDocNode("sheet-2", "data.xlsx");

            Map<String, Object> tableMeta = Map.of("table_headers", "Date,Revenue Forecast,Cost,Margin");
            GraphNode tableNode = makeTableNode("tbl-2", "Sheet1", spreadsheetDoc, tableMeta);

            stubDocNodes(FACT_SHEET_ID, processDoc, spreadsheetDoc);
            stubTableNodes(FACT_SHEET_ID, tableNode);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                    eq("proc-2"), eq("sheet-2"), eq(EdgeType.USER_DEFINED),
                    eq(0.7), eq("REFERENCES_DATA"),
                    anyString(), anyString(), eq(EdgeProvenance.INFERRED), eq(FACT_SHEET_ID));
        }

        @Test
        void detectsHeaderFromListMetadata() {
            GraphNode processDoc = makeDocNode("proc-3", "audit.html");
            processDoc.setDescription("Review the Employee Benefits section carefully for compliance.");
            processDoc.setContentPreview("Employee Benefits data must match accounting records.");

            GraphNode spreadsheetDoc = makeDocNode("sheet-3", "hr_data.xlsx");

            Map<String, Object> tableMeta = Map.of("headers", List.of("Employee Benefits", "Salary", "Tax Rate"));
            GraphNode tableNode = makeTableNode("tbl-3", "HR Sheet", spreadsheetDoc, tableMeta);

            stubDocNodes(FACT_SHEET_ID, processDoc, spreadsheetDoc);
            stubTableNodes(FACT_SHEET_ID, tableNode);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                    eq("proc-3"), eq("sheet-3"), eq(EdgeType.USER_DEFINED),
                    eq(0.7), eq("REFERENCES_DATA"),
                    anyString(), anyString(), eq(EdgeProvenance.INFERRED), eq(FACT_SHEET_ID));
        }

        @Test
        void skipsShortContentUnder50Chars() {
            GraphNode processDoc = makeDocNode("proc-4", "short.html");
            processDoc.setDescription("Short text");
            processDoc.setContentPreview(null);

            GraphNode spreadsheetDoc = makeDocNode("sheet-4", "data.xlsx");
            GraphNode tableNode = makeTableNode("tbl-4", "Budget", spreadsheetDoc, null);

            stubDocNodes(FACT_SHEET_ID, processDoc, spreadsheetDoc);
            stubTableNodes(FACT_SHEET_ID, tableNode);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("REFERENCES_DATA"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void skipsSelfLinks() {
            GraphNode doc = makeDocNode("doc-same", "report.xlsx");
            doc.setDescription("This report contains the Budget Summary sheet with detailed breakdown of quarterly data across all departments.");
            doc.setContentPreview("Budget Summary sheet contains quarterly projections and monthly actuals.");

            GraphNode tableNode = makeTableNode("tbl-same", "Budget Summary", doc, null);

            stubDocNodes(FACT_SHEET_ID, doc);
            stubTableNodes(FACT_SHEET_ID, tableNode);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // Should not create self-referencing REFERENCES_DATA edge
            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    eq("doc-same"), eq("doc-same"), any(), anyDouble(),
                    eq("REFERENCES_DATA"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void ignoresGenericHeaders() {
            GraphNode processDoc = makeDocNode("proc-gen", "workflow.html");
            processDoc.setDescription("Check the name, value, type, date, status, notes, total, description, id, count, amount columns.");
            processDoc.setContentPreview("All these generic column names should be ignored for matching purposes in the system.");

            GraphNode spreadsheetDoc = makeDocNode("sheet-gen", "data.xlsx");

            Map<String, Object> tableMeta = Map.of("table_headers", "name,value,type,date,status");
            GraphNode tableNode = makeTableNode("tbl-gen", "Sheet1", spreadsheetDoc, tableMeta);

            stubDocNodes(FACT_SHEET_ID, processDoc, spreadsheetDoc);
            stubTableNodes(FACT_SHEET_ID, tableNode);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // Generic headers should be filtered out — no REFERENCES_DATA edges from them
            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("REFERENCES_DATA"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void ignoresShortHeaders() {
            GraphNode processDoc = makeDocNode("proc-short", "workflow.html");
            processDoc.setDescription("Check column A, B, CC, and ID for correctness and validate against reference data in the system.");
            processDoc.setContentPreview("Columns A, B, CC, ID must all be reviewed carefully in every single quarterly report.");

            GraphNode spreadsheetDoc = makeDocNode("sheet-short", "data.xlsx");

            Map<String, Object> tableMeta = Map.of("table_headers", "A,B,CC,ID");
            GraphNode tableNode = makeTableNode("tbl-short", "Sheet1", spreadsheetDoc, tableMeta);

            stubDocNodes(FACT_SHEET_ID, processDoc, spreadsheetDoc);
            stubTableNodes(FACT_SHEET_ID, tableNode);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // Headers ≤4 chars should be skipped
            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("REFERENCES_DATA"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void noTableNodesReturnsZeroForStrategyC() {
            GraphNode doc1 = makeDocNode("d1", "doc_v1.xlsx");
            GraphNode doc2 = makeDocNode("d2", "other.pdf");

            stubDocNodes(FACT_SHEET_ID, doc1, doc2);
            stubTableNodes(FACT_SHEET_ID); // empty

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("REFERENCES_DATA"), anyString(), anyString(), any(), anyLong());
        }
    }

    // =========================================================================
    // Strategy D: Hyperlink resolution
    // =========================================================================

    @Nested
    class StrategyD_Hyperlinks {

        @Test
        void resolvesUrlToKnownDocument() {
            Map<String, Object> annotMeta = Map.of("extractionType", "annotations");
            GraphNode annotDoc = makeDocNode("ann-1", "report.pdf", annotMeta);
            annotDoc.setContentPreview("See https://sharepoint.example.com/docs/budget.xlsx for details");

            GraphNode target = makeDocNode("tgt-1", "budget.xlsx");

            stubDocNodes(FACT_SHEET_ID, annotDoc, target);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("ann-1"), eq("tgt-1"), eq(EdgeType.USER_DEFINED),
                    eq(0.9), eq("HYPERLINK_TO"),
                    anyString(), contains("HYPERLINK_TO"), eq(EdgeProvenance.EXTRACTED), eq(FACT_SHEET_ID));
        }

        @Test
        void skipsNonAnnotationNodes() {
            Map<String, Object> regularMeta = Map.of("extractionType", "text");
            GraphNode doc = makeDocNode("reg-1", "report.pdf", regularMeta);
            doc.setContentPreview("See https://sharepoint.example.com/docs/budget.xlsx for details");

            GraphNode target = makeDocNode("tgt-2", "budget.xlsx");

            stubDocNodes(FACT_SHEET_ID, doc, target);
            stubTableNodes(FACT_SHEET_ID);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("HYPERLINK_TO"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void decodesPercentEncodedFilenames() {
            Map<String, Object> annotMeta = Map.of("extractionType", "annotations");
            GraphNode annotDoc = makeDocNode("ann-2", "index.html", annotMeta);
            annotDoc.setContentPreview("Link: https://server.com/files/my%20report.pdf");

            GraphNode target = makeDocNode("tgt-3", "my report.pdf");

            stubDocNodes(FACT_SHEET_ID, annotDoc, target);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("ann-2"), eq("tgt-3"), eq(EdgeType.USER_DEFINED),
                    eq(0.9), eq("HYPERLINK_TO"),
                    anyString(), anyString(), eq(EdgeProvenance.EXTRACTED), eq(FACT_SHEET_ID));
        }

        @Test
        void stripsQueryStringFromUrl() {
            Map<String, Object> annotMeta = Map.of("extractionType", "annotations");
            GraphNode annotDoc = makeDocNode("ann-3", "links.html", annotMeta);
            annotDoc.setContentPreview("Download: https://storage.example.com/files/data.csv?token=abc123");

            GraphNode target = makeDocNode("tgt-4", "data.csv");

            stubDocNodes(FACT_SHEET_ID, annotDoc, target);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("ann-3"), eq("tgt-4"), eq(EdgeType.USER_DEFINED),
                    eq(0.9), eq("HYPERLINK_TO"),
                    anyString(), anyString(), eq(EdgeProvenance.EXTRACTED), eq(FACT_SHEET_ID));
        }

        @Test
        void doesNotMatchUrlToSelf() {
            Map<String, Object> annotMeta = Map.of("extractionType", "annotations");
            GraphNode annotDoc = makeDocNode("ann-self", "report.pdf", annotMeta);
            annotDoc.setContentPreview("See https://server.com/docs/report.pdf for this file");

            stubDocNodes(FACT_SHEET_ID, annotDoc);
            stubTableNodes(FACT_SHEET_ID);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    eq("ann-self"), eq("ann-self"), any(), anyDouble(),
                    eq("HYPERLINK_TO"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void deduplicatesSameUrlInContent() {
            Map<String, Object> annotMeta = Map.of("extractionType", "annotations");
            GraphNode annotDoc = makeDocNode("ann-dup", "links.html", annotMeta);
            annotDoc.setContentPreview(
                    "See https://server.com/data.xlsx and again https://server.com/data.xlsx end");

            GraphNode target = makeDocNode("tgt-dup", "data.xlsx");

            stubDocNodes(FACT_SHEET_ID, annotDoc, target);
            stubTableNodes(FACT_SHEET_ID);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // Only one edge even though URL appears twice
            verify(knowledgeGraphService, times(1)).createEdgeWithMetadata(
                    eq("ann-dup"), eq("tgt-dup"), any(), anyDouble(),
                    eq("HYPERLINK_TO"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void fallsBackToDescriptionWhenContentPreviewEmpty() {
            Map<String, Object> annotMeta = Map.of("extractionType", "annotations");
            GraphNode annotDoc = makeDocNode("ann-desc", "links.html", annotMeta);
            annotDoc.setContentPreview(null);
            annotDoc.setDescription("Link to https://server.com/budget.xlsx found");

            GraphNode target = makeDocNode("tgt-desc", "budget.xlsx");

            stubDocNodes(FACT_SHEET_ID, annotDoc, target);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("ann-desc"), eq("tgt-desc"), eq(EdgeType.USER_DEFINED),
                    eq(0.9), eq("HYPERLINK_TO"),
                    anyString(), anyString(), eq(EdgeProvenance.EXTRACTED), eq(FACT_SHEET_ID));
        }
    }

    // =========================================================================
    // Strategy E: Shared author linking
    // =========================================================================

    @Nested
    class StrategyE_SharedAuthors {

        @Test
        void linksTwoDocsBySameAuthor() {
            Map<String, Object> meta1 = Map.of("author", "John Smith");
            Map<String, Object> meta2 = Map.of("author", "John Smith");

            GraphNode doc1 = makeDocNode("auth-1", "report.pdf", meta1);
            GraphNode doc2 = makeDocNode("auth-2", "summary.docx", meta2);

            stubDocNodes(FACT_SHEET_ID, doc1, doc2);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("auth-1"), eq("auth-2"), eq(EdgeType.USER_DEFINED),
                    eq(0.75), eq("SHARED_AUTHOR"),
                    anyString(), contains("SHARED_AUTHOR"), eq(EdgeProvenance.INFERRED), eq(FACT_SHEET_ID));
        }

        @Test
        void caseInsensitiveAuthorMatching() {
            Map<String, Object> meta1 = Map.of("author", "Jane DOE");
            Map<String, Object> meta2 = Map.of("author", "jane doe");

            GraphNode doc1 = makeDocNode("ci-1", "a.pdf", meta1);
            GraphNode doc2 = makeDocNode("ci-2", "b.pdf", meta2);

            stubDocNodes(FACT_SHEET_ID, doc1, doc2);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                    anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                    eq(0.75), eq("SHARED_AUTHOR"),
                    anyString(), anyString(), eq(EdgeProvenance.INFERRED), eq(FACT_SHEET_ID));
        }

        @Test
        void threeDocsCreateThreeEdges() {
            Map<String, Object> meta = Map.of("author", "Alice");

            GraphNode d1 = makeDocNode("a1", "r1.pdf", meta);
            GraphNode d2 = makeDocNode("a2", "r2.pdf", meta);
            GraphNode d3 = makeDocNode("a3", "r3.pdf", meta);

            stubDocNodes(FACT_SHEET_ID, d1, d2, d3);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // C(3,2) = 3 edges
            verify(knowledgeGraphService, times(3)).createEdgeWithMetadata(
                    anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                    eq(0.75), eq("SHARED_AUTHOR"),
                    anyString(), anyString(), eq(EdgeProvenance.INFERRED), eq(FACT_SHEET_ID));
        }

        @Test
        void differentAuthorsNoEdge() {
            Map<String, Object> meta1 = Map.of("author", "Alice");
            Map<String, Object> meta2 = Map.of("author", "Bob");

            GraphNode d1 = makeDocNode("diff-1", "a.pdf", meta1);
            GraphNode d2 = makeDocNode("diff-2", "b.pdf", meta2);

            stubDocNodes(FACT_SHEET_ID, d1, d2);
            stubTableNodes(FACT_SHEET_ID);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("SHARED_AUTHOR"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void blankAuthorIsIgnored() {
            Map<String, Object> meta1 = Map.of("author", "  ");
            Map<String, Object> meta2 = Map.of("author", "");

            GraphNode d1 = makeDocNode("blank-1", "a.pdf", meta1);
            GraphNode d2 = makeDocNode("blank-2", "b.pdf", meta2);

            stubDocNodes(FACT_SHEET_ID, d1, d2);
            stubTableNodes(FACT_SHEET_ID);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("SHARED_AUTHOR"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void skipsWhenEdgeAlreadyExists() {
            Map<String, Object> meta = Map.of("author", "Bob");
            GraphNode d1 = makeDocNode("ex-1", "a.pdf", meta);
            GraphNode d2 = makeDocNode("ex-2", "b.pdf", meta);

            stubDocNodes(FACT_SHEET_ID, d1, d2);
            stubTableNodes(FACT_SHEET_ID);

            when(knowledgeGraphService.edgeExists(eq("ex-1"), eq("ex-2"), any(), eq("SHARED_AUTHOR"), eq(FACT_SHEET_ID))).thenReturn(true);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("SHARED_AUTHOR"), anyString(), anyString(), any(), anyLong());
        }
    }

    // =========================================================================
    // Strategy F: Shared keywords/topics
    // =========================================================================

    @Nested
    class StrategyF_SharedKeywords {

        @Test
        void linksTwoDocsBySharedKeyword() {
            Map<String, Object> meta1 = Map.of("keywords", "finance,audit,compliance");
            Map<String, Object> meta2 = Map.of("keywords", "compliance,legal,risk");

            GraphNode d1 = makeDocNode("kw-1", "doc1.pdf", meta1);
            GraphNode d2 = makeDocNode("kw-2", "doc2.pdf", meta2);

            stubDocNodes(FACT_SHEET_ID, d1, d2);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                    anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                    eq(0.65), eq("SHARED_KEYWORD"),
                    anyString(), contains("SHARED_KEYWORD"), eq(EdgeProvenance.INFERRED), eq(FACT_SHEET_ID));
        }

        @Test
        void ignoresShortKeywords() {
            Map<String, Object> meta1 = Map.of("keywords", "AI,ML");
            Map<String, Object> meta2 = Map.of("keywords", "AI,DL");

            GraphNode d1 = makeDocNode("sk-1", "a.pdf", meta1);
            GraphNode d2 = makeDocNode("sk-2", "b.pdf", meta2);

            stubDocNodes(FACT_SHEET_ID, d1, d2);
            stubTableNodes(FACT_SHEET_ID);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // "AI" and "ML" are ≤2 chars — should be ignored
            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("SHARED_KEYWORD"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void noOverlapNoEdge() {
            Map<String, Object> meta1 = Map.of("keywords", "finance,budget");
            Map<String, Object> meta2 = Map.of("keywords", "engineering,testing");

            GraphNode d1 = makeDocNode("no-1", "a.pdf", meta1);
            GraphNode d2 = makeDocNode("no-2", "b.pdf", meta2);

            stubDocNodes(FACT_SHEET_ID, d1, d2);
            stubTableNodes(FACT_SHEET_ID);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("SHARED_KEYWORD"), anyString(), anyString(), any(), anyLong());
        }

        @Test
        void deduplicatesPairsAcrossMultipleSharedKeywords() {
            // Two docs share both "finance" and "audit" — should only create one edge
            Map<String, Object> meta1 = Map.of("keywords", "finance,audit");
            Map<String, Object> meta2 = Map.of("keywords", "audit,finance");

            GraphNode d1 = makeDocNode("dup-1", "a.pdf", meta1);
            GraphNode d2 = makeDocNode("dup-2", "b.pdf", meta2);

            stubDocNodes(FACT_SHEET_ID, d1, d2);
            stubTableNodes(FACT_SHEET_ID);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // Only one edge created despite two shared keywords
            verify(knowledgeGraphService, times(1)).createEdgeWithMetadata(
                    anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                    eq(0.65), eq("SHARED_KEYWORD"),
                    anyString(), anyString(), eq(EdgeProvenance.INFERRED), eq(FACT_SHEET_ID));
        }

        @Test
        void supportsSemicolonAndPipeDelimiters() {
            Map<String, Object> meta1 = Map.of("keywords", "finance;audit|compliance");
            Map<String, Object> meta2 = Map.of("keywords", "compliance,legal");

            GraphNode d1 = makeDocNode("delim-1", "a.pdf", meta1);
            GraphNode d2 = makeDocNode("delim-2", "b.pdf", meta2);

            stubDocNodes(FACT_SHEET_ID, d1, d2);
            stubTableNodes(FACT_SHEET_ID);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                    anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                    eq(0.65), eq("SHARED_KEYWORD"),
                    anyString(), anyString(), eq(EdgeProvenance.INFERRED), eq(FACT_SHEET_ID));
        }

        @Test
        void skipsWhenEdgeAlreadyExists() {
            Map<String, Object> meta = Map.of("keywords", "finance,audit");
            GraphNode d1 = makeDocNode("kex-1", "a.pdf", meta);
            GraphNode d2 = makeDocNode("kex-2", "b.pdf", meta);

            stubDocNodes(FACT_SHEET_ID, d1, d2);
            stubTableNodes(FACT_SHEET_ID);

            when(knowledgeGraphService.edgeExists(anyString(), anyString(), any(), eq("SHARED_KEYWORD"), eq(FACT_SHEET_ID))).thenReturn(true);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), anyDouble(),
                    eq("SHARED_KEYWORD"), anyString(), anyString(), any(), anyLong());
        }
    }

    // =========================================================================
    // All strategies combined
    // =========================================================================

    @Nested
    class AllStrategiesCombined {

        @Test
        void allSixStrategiesFireTogether() {
            // Set up nodes that trigger every strategy

            // Strategy A: email with attachment
            Map<String, Object> emailMeta = new LinkedHashMap<>();
            emailMeta.put("email.attachmentNames", List.of("budget.xlsx"));
            emailMeta.put("email.subject", "Review");
            emailMeta.put("author", "Alice");        // also triggers E
            emailMeta.put("keywords", "finance");    // also triggers F
            GraphNode emailNode = makeDocNode("email", "email.html", emailMeta);
            emailNode.setDescription("Placeholder content for email that is long enough to pass the minimum threshold check");
            emailNode.setContentPreview("Please review the attached budget spreadsheet for Q4 projections and submit by Friday. Details in Budget Summary.");

            // Strategy B: versioned file + same author + keywords
            Map<String, Object> v1Meta = new LinkedHashMap<>();
            v1Meta.put("author", "Alice");
            v1Meta.put("keywords", "finance");
            GraphNode v1Node = makeDocNode("v1", "budget_v1.xlsx", v1Meta);

            Map<String, Object> v2Meta = new LinkedHashMap<>();
            v2Meta.put("author", "Alice");
            v2Meta.put("keywords", "finance");
            GraphNode v2Node = makeDocNode("v2", "budget_v2.xlsx", v2Meta);

            // Strategy D: annotation doc with hyperlinks
            Map<String, Object> annotMeta = new LinkedHashMap<>();
            annotMeta.put("extractionType", "annotations");
            annotMeta.put("author", "Alice");
            annotMeta.put("keywords", "finance");
            GraphNode annotNode = makeDocNode("annot", "index.html", annotMeta);
            annotNode.setContentPreview("See https://server.com/docs/budget_v1.xlsx for details");

            // Strategy C: table node for process reference
            GraphNode tableParent = v1Node; // budget_v1.xlsx owns the table
            GraphNode tableNode = makeTableNode("tbl-combo", "Budget Summary", tableParent, null);

            stubDocNodes(FACT_SHEET_ID, emailNode, v1Node, v2Node, annotNode);
            stubTableNodes(FACT_SHEET_ID, tableNode);

            int result = extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // Should fire multiple strategies — at minimum:
            // A: email→budget (attachment)
            // B: v2→v1 (version)
            // C: email→v1 (references "Budget Summary" table)
            // D: annot→v1 (hyperlink to budget_v1.xlsx)
            // E: multiple shared author edges (all 4 docs by Alice = C(4,2)=6)
            // F: shared keyword edges
            assertTrue(result >= 4, "All six strategies should collectively create ≥4 edges, got " + result);
        }

        @Test
        void allEdgesUseUserDefinedEdgeType() {
            Map<String, Object> meta = Map.of("author", "Bob", "keywords", "testing");
            GraphNode d1 = makeDocNode("ud-1", "report_v1.pdf", meta);
            GraphNode d2 = makeDocNode("ud-2", "report_v2.pdf", meta);

            stubDocNodes(FACT_SHEET_ID, d1, d2);
            stubTableNodes(FACT_SHEET_ID);

            extractor.extractRelationsFromGraphNodes(FACT_SHEET_ID);

            // Verify all created edges use USER_DEFINED type
            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), argThat(type -> type != EdgeType.USER_DEFINED),
                    anyDouble(), anyString(), anyString(), anyString(),
                    any(EdgeProvenance.class), anyLong());
        }
    }
}
