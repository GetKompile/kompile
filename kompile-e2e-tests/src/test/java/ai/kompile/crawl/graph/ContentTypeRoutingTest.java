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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for content-type-aware routing in {@link ContentTypeRouter} (extracted from the crawl
 * impl into its own collaborator). Verifies that documents are routed correctly based on their
 * content_type metadata:
 * - formula_graph → knowledge graph persistence
 * - table → full content embedding + TABLE node creation
 * - image/chart → skipped from text pipeline
 * - text/other → passed through unchanged
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentTypeRoutingTest {

    @Mock private KnowledgeGraphService knowledgeGraphService;

    private ContentTypeRouter router;

    @BeforeEach
    void setUp() throws Exception {
        // Create a mock source node for knowledge graph linkage
        GraphNode sourceNode = GraphNode.builder()
                .nodeId("source-node-1")
                .externalId("task_test-1")
                .nodeType(NodeLevel.SOURCE)
                .title("Test")
                .build();
        when(knowledgeGraphService.createNode(eq(NodeLevel.SOURCE), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(sourceNode);

        // Create mock table node
        GraphNode tableNode = GraphNode.builder()
                .nodeId("table-node-1")
                .externalId("table_test-1_Sheet1")
                .nodeType(NodeLevel.TABLE)
                .title("Sheet1")
                .build();
        when(knowledgeGraphService.createTableNode(anyString(), anyString(), anyString(),
                anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(tableNode);

        // Create mock entity node
        GraphNode entityNode = GraphNode.builder()
                .nodeId("entity-node-1")
                .externalId("cell-1")
                .nodeType(NodeLevel.ENTITY)
                .title("Cell A1")
                .build();
        when(knowledgeGraphService.createNode(eq(NodeLevel.ENTITY), anyString(), anyString(), any(), anyMap()))
                .thenReturn(entityNode);
        when(knowledgeGraphService.createNode(eq(NodeLevel.TABLE), anyString(), anyString(), any(), anyMap()))
                .thenReturn(tableNode);

        // 6-parameter overload (with factSheetId) used by persistFormulaGraph
        when(knowledgeGraphService.createNode(eq(NodeLevel.ENTITY), anyString(), anyString(), any(), anyMap(), any()))
                .thenReturn(entityNode);
        when(knowledgeGraphService.createNode(eq(NodeLevel.TABLE), anyString(), anyString(), any(), anyMap(), any()))
                .thenReturn(tableNode);

        // Create mock edge
        GraphEdge edge = GraphEdge.builder()
                .edgeId("edge-1")
                .edgeType(EdgeType.HIERARCHICAL)
                .build();
        when(knowledgeGraphService.createEdge(anyString(), anyString(), any(EdgeType.class), anyDouble(), anyString()))
                .thenReturn(edge);
        when(knowledgeGraphService.createEdgeWithMetadata(anyString(), anyString(), any(EdgeType.class),
                anyDouble(), anyString(), any(), any(), any(EdgeProvenance.class), any()))
                .thenReturn(edge);

        // Mock getNodeByExternalId (returns empty for new entities)
        when(knowledgeGraphService.getNodeByExternalId(anyString(), any(NodeLevel.class)))
                .thenReturn(Optional.empty());

        // Mock edgeExists (no pre-existing edges)
        when(knowledgeGraphService.edgeExists(anyString(), anyString()))
                .thenReturn(false);

        // Mock addDocument (8 params: sourceExternalId, jobId, sourceType, sourcePath, fileName, contentPreview, docMeta, factSheetId)
        when(knowledgeGraphService.addDocument(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(sourceNode);

        // P3: createNodesBatch — persistGraphJson now batches node creation
        when(knowledgeGraphService.createNodesBatch(anyList(), any()))
                .thenAnswer(inv -> {
                    List<KnowledgeGraphService.NodeSpec> specs = inv.getArgument(0);
                    return specs.stream()
                            .map(spec -> GraphNode.builder()
                                    .nodeId(UUID.randomUUID().toString())
                                    .externalId(spec.externalId())
                                    .nodeType(spec.nodeType())
                                    .title(spec.title())
                                    .build())
                            .collect(java.util.stream.Collectors.toList());
                });

        // P3: createEdgesBatch — persistGraphJson now batches edge creation
        when(knowledgeGraphService.createEdgesBatch(anyList()))
                .thenReturn(0);

        router = buildRouter(knowledgeGraphService);
    }

    /** Wire a router the way the crawl slice does, without Spring (fields are @Autowired). */
    private static ContentTypeRouter buildRouter(KnowledgeGraphService kg) throws Exception {
        CrawlDocumentTracker tracker = new CrawlDocumentTracker();
        GraphPersistenceHelper persistence = new GraphPersistenceHelper();
        injectField(persistence, "knowledgeGraphService", kg);
        injectField(persistence, "documentTracker", tracker);
        ContentTypeRouter contentTypeRouter = new ContentTypeRouter();
        injectField(contentTypeRouter, "knowledgeGraphService", kg);
        injectField(contentTypeRouter, "graphPersistenceHelper", persistence);
        injectField(contentTypeRouter, "documentTracker", tracker);
        return contentTypeRouter;
    }

    private static UnifiedCrawlJob routingJob(String taskId) {
        return UnifiedCrawlJob.builder()
                .jobId(taskId)
                .request(UnifiedCrawlRequest.builder().sources(List.of()).build())
                .build();
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private List<Document> invokeRouteByContentType(String taskId, List<Document> docs) throws Exception {
        return router.routeByContentType(routingJob(taskId), docs);
    }

    @Test
    void textDocumentsPassThroughUnchanged() throws Exception {
        Document textDoc = new Document("plain text content", Map.of("source", "test.txt"));
        List<Document> result = invokeRouteByContentType("test-1", List.of(textDoc));

        assertEquals(1, result.size());
        assertEquals("plain text content", result.get(0).getText());
    }

    @Test
    void documentsWithNoContentTypePassThrough() throws Exception {
        Document noType = new Document("no type", new HashMap<>());
        List<Document> result = invokeRouteByContentType("test-1", List.of(noType));

        assertEquals(1, result.size());
        assertEquals("no type", result.get(0).getText());
    }

    @Test
    void imageDocumentsAreSkipped() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "image");
        meta.put("image_data_base64", "iVBORw0KGgo...");
        Document imageDoc = new Document("embedded image", meta);

        List<Document> result = invokeRouteByContentType("test-1", List.of(imageDoc));

        assertEquals(0, result.size(), "Image documents should be skipped from text pipeline");
    }

    @Test
    void chartDocumentsAreSkipped() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "chart");
        Document chartDoc = new Document("chart title", meta);

        List<Document> result = invokeRouteByContentType("test-1", List.of(chartDoc));

        assertEquals(0, result.size(), "Chart documents should be skipped from text pipeline");
    }

    @Test
    void tableDocumentsGetFullContentForEmbedding() throws Exception {
        String summary = "Sheet 'Sales' with 10 rows and 3 columns. Columns: Date, Product, Revenue";
        String fullTable = "| Date | Product | Revenue |\n|---|---|---|\n| 2025-01-01 | Widget | 1000 |";

        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("full_table_content", fullTable);
        meta.put("sheetName", "Sales");
        meta.put("rowCount", 10);
        meta.put("columnCount", 3);
        Document tableDoc = new Document(summary, meta);

        List<Document> result = invokeRouteByContentType("test-1", List.of(tableDoc));

        assertEquals(1, result.size());
        // The document text should be replaced with full table content
        assertEquals(fullTable, result.get(0).getText(),
                "Table document should use full_table_content for embedding, not the summary");
    }

    @Test
    void tableDocumentFallsBackToSummaryWhenNoFullContent() throws Exception {
        String summary = "Sheet 'Empty' with 0 rows";
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        // No full_table_content
        Document tableDoc = new Document(summary, meta);

        List<Document> result = invokeRouteByContentType("test-1", List.of(tableDoc));

        assertEquals(1, result.size());
        assertEquals(summary, result.get(0).getText());
    }

    @Test
    void formulaGraphDocumentIsPersisted() throws Exception {
        String graphJson = "{\"entities\":[" +
                "{\"id\":\"cell-1\",\"title\":\"A1\",\"type\":\"CELL\",\"description\":\"Cell A1\"}," +
                "{\"id\":\"cell-2\",\"title\":\"B1\",\"type\":\"FORMULA_CELL\",\"description\":\"=A1+1\"}" +
                "],\"relationships\":[" +
                "{\"source\":\"cell-2\",\"target\":\"cell-1\",\"type\":\"DEPENDS_ON\",\"description\":\"formula reference\"}" +
                "]}";

        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "formula_graph");
        meta.put("formulaGraph", graphJson);
        Document graphDoc = new Document("Formula graph summary", meta);

        List<Document> result = invokeRouteByContentType("test-1", List.of(graphDoc));

        // Formula graph doc should still be in the pipeline (for keyword indexing)
        assertEquals(1, result.size());
        assertEquals("Formula graph summary", result.get(0).getText());

        // P3: persistGraphJson now uses createNodesBatch; verify the batch was called with >= 2 specs
        // (cell-1 type CELL → ENTITY, cell-2 type FORMULA_CELL → ENTITY)
        org.mockito.ArgumentCaptor<List<KnowledgeGraphService.NodeSpec>> nodeSpecCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(knowledgeGraphService, atLeast(1)).createNodesBatch(nodeSpecCaptor.capture(), any());
        int totalNodes = nodeSpecCaptor.getAllValues().stream().mapToInt(List::size).sum();
        assertTrue(totalNodes >= 2,
                "Expected at least 2 NodeSpecs in createNodesBatch calls, got " + totalNodes);

        // Verify the DEPENDS_ON relationship was created via createEdgesBatch or createEdgeWithMetadata
        // (persistGraphJson delegates edge persistence through createEdgesInBoundedBatches → createEdgesBatch)
        verify(knowledgeGraphService, atLeast(1)).createEdgesBatch(argThat(specs ->
                specs.stream().anyMatch(s -> "DEPENDS_ON".equals(s.label()))));
    }

    @Test
    void formulaGraphWithSheetEntitiesUsesTableNodeLevel() throws Exception {
        String graphJson = "{\"entities\":[" +
                "{\"id\":\"sheet-1\",\"title\":\"Sheet1\",\"type\":\"SHEET\",\"description\":\"Main sheet\"}" +
                "],\"relationships\":[]}";

        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "formula_graph");
        meta.put("formulaGraph", graphJson);
        Document graphDoc = new Document("summary", meta);

        invokeRouteByContentType("test-1", List.of(graphDoc));

        // P3: persistGraphJson now uses createNodesBatch; verify the batch contains a TABLE-level spec
        // for the SHEET entity (SHEET → NodeLevel.TABLE mapping)
        org.mockito.ArgumentCaptor<List<KnowledgeGraphService.NodeSpec>> nodeSpecCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(knowledgeGraphService, atLeast(1)).createNodesBatch(nodeSpecCaptor.capture(), any());
        boolean hasTableSpec = nodeSpecCaptor.getAllValues().stream()
                .flatMap(List::stream)
                .anyMatch(s -> s.nodeType() == NodeLevel.TABLE
                        && "sheet-1".equals(s.externalId())
                        && "Sheet1".equals(s.title())
                        && "Main sheet".equals(s.description()));
        assertTrue(hasTableSpec, "Expected a TABLE NodeSpec for sheet-1 (SHEET type maps to TABLE level)");
    }

    @Test
    void mixedContentTypesAreRoutedCorrectly() throws Exception {
        List<Document> docs = new ArrayList<>();

        // Text doc
        docs.add(new Document("plain text", new HashMap<>()));

        // Table doc
        Map<String, Object> tableMeta = new HashMap<>();
        tableMeta.put("content_type", "table");
        tableMeta.put("full_table_content", "| col1 |\n|---|\n| val |");
        docs.add(new Document("table summary", tableMeta));

        // Image doc (should be skipped)
        Map<String, Object> imageMeta = new HashMap<>();
        imageMeta.put("content_type", "image");
        docs.add(new Document("image", imageMeta));

        // Chart doc (should be skipped)
        Map<String, Object> chartMeta = new HashMap<>();
        chartMeta.put("content_type", "chart");
        docs.add(new Document("chart", chartMeta));

        // Formula graph doc
        Map<String, Object> graphMeta = new HashMap<>();
        graphMeta.put("content_type", "formula_graph");
        graphMeta.put("formulaGraph", "{\"entities\":[],\"relationships\":[]}");
        docs.add(new Document("graph summary", graphMeta));

        List<Document> result = invokeRouteByContentType("test-1", docs);

        // Should have: text + table + formula_graph = 3 (image and chart skipped)
        assertEquals(3, result.size());

        // Verify content: text unchanged, table has full content, formula_graph summary kept
        assertEquals("plain text", result.get(0).getText());
        assertEquals("| col1 |\n|---|\n| val |", result.get(1).getText());
        assertEquals("graph summary", result.get(2).getText());
    }

    @Test
    void emptyDocumentsListReturnsEmpty() throws Exception {
        List<Document> result = invokeRouteByContentType("test-1", List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void routingWorksWithoutKnowledgeGraphService() throws Exception {
        // Router wired without a KnowledgeGraphService
        ContentTypeRouter routerNoKg = buildRouter(null);

        Map<String, Object> tableMeta = new HashMap<>();
        tableMeta.put("content_type", "table");
        tableMeta.put("full_table_content", "| A |\n|---|\n| 1 |");

        Map<String, Object> graphMeta = new HashMap<>();
        graphMeta.put("content_type", "formula_graph");
        graphMeta.put("formulaGraph", "{\"entities\":[{\"id\":\"c1\",\"title\":\"A1\",\"type\":\"CELL\"}],\"relationships\":[]}");

        List<Document> docs = List.of(
                new Document("text", new HashMap<>()),
                new Document("summary", tableMeta),
                new Document("graph", graphMeta));

        // Should not throw, just skip KG operations
        List<Document> result = routerNoKg.routeByContentType(routingJob("test-1"), docs);

        assertEquals(3, result.size());
        // Table should still get full content even without KG
        assertEquals("| A |\n|---|\n| 1 |", result.get(1).getText());
    }

    @Test
    void invalidFormulaGraphJsonDoesNotCrash() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "formula_graph");
        meta.put("formulaGraph", "not valid json{{{");
        Document graphDoc = new Document("summary", meta);

        // Should not throw
        List<Document> result = invokeRouteByContentType("test-1", List.of(graphDoc));

        // Document should still pass through for keyword indexing
        assertEquals(1, result.size());
    }
}
