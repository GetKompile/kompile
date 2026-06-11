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

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for StepExecutionDispatcherImpl.resolveExcelGraphJson() —
 * verifies that formula graph JSON can be resolved from KG node metadata.
 */
@ExtendWith(MockitoExtension.class)
class ResolveExcelGraphJsonTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private ApplicationContext applicationContext;

    private StepExecutionDispatcherImpl dispatcher;

    private static final String FORMULA_GRAPH_JSON = "{\"entities\":[{\"id\":\"cell:A1\",\"type\":\"CELL\"}],\"relationships\":[]}";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        dispatcher = new StepExecutionDispatcherImpl(applicationContext, new ObjectMapper(), null);
        // Inject the mock KG service via reflection (it's an @Autowired field)
        Field kgField = StepExecutionDispatcherImpl.class.getDeclaredField("knowledgeGraphService");
        kgField.setAccessible(true);
        kgField.set(dispatcher, knowledgeGraphService);
    }

    @Test
    void resolveExcelGraphJson_findsFormulaGraphInDocumentNodeMetadata() throws Exception {
        String nodeId = "doc-node-1";
        Map<String, Object> meta = Map.of("formulaGraph", FORMULA_GRAPH_JSON, "source_path", "/tmp/test.xlsx");
        String metaJson = new ObjectMapper().writeValueAsString(meta);

        GraphNode docNode = GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.DOCUMENT)
                .title("test.xlsx")
                .metadataJson(metaJson)
                .build();

        when(knowledgeGraphService.getNode(nodeId)).thenReturn(Optional.of(docNode));

        String result = dispatcher.resolveExcelGraphJson(List.of(nodeId));

        assertNotNull(result);
        assertNormalizedSpreadsheetGraph(result);
    }

    @Test
    void resolveExcelGraphJson_returnsNullForNullInput() {
        assertNull(dispatcher.resolveExcelGraphJson(null));
    }

    @Test
    void resolveExcelGraphJson_returnsNullForEmptyList() {
        assertNull(dispatcher.resolveExcelGraphJson(List.of()));
    }

    @Test
    void resolveExcelGraphJson_returnsNullWhenNodeHasNoFormulaGraph() throws Exception {
        String nodeId = "doc-node-2";
        Map<String, Object> meta = Map.of("source_path", "/tmp/data.csv");
        String metaJson = new ObjectMapper().writeValueAsString(meta);

        GraphNode docNode = GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.DOCUMENT)
                .title("data.csv")
                .metadataJson(metaJson)
                .build();

        when(knowledgeGraphService.getNode(nodeId)).thenReturn(Optional.of(docNode));

        assertNull(dispatcher.resolveExcelGraphJson(List.of(nodeId)));
    }

    @Test
    void resolveExcelGraphJson_returnsNullWhenNodeNotFound() {
        when(knowledgeGraphService.getNode("missing-id")).thenReturn(Optional.empty());

        assertNull(dispatcher.resolveExcelGraphJson(List.of("missing-id")));
    }

    @Test
    void resolveExcelGraphJson_findsFormulaGraphOnSecondNode() throws Exception {
        // First node has no formula graph, second does
        GraphNode noGraphNode = GraphNode.builder()
                .nodeId("node-1")
                .nodeType(NodeLevel.DOCUMENT)
                .metadataJson("{\"source_path\":\"/tmp/readme.txt\"}")
                .build();

        Map<String, Object> meta = Map.of("formulaGraph", FORMULA_GRAPH_JSON);
        String metaJson = new ObjectMapper().writeValueAsString(meta);
        GraphNode withGraphNode = GraphNode.builder()
                .nodeId("node-2")
                .nodeType(NodeLevel.DOCUMENT)
                .metadataJson(metaJson)
                .build();

        when(knowledgeGraphService.getNode("node-1")).thenReturn(Optional.of(noGraphNode));
        when(knowledgeGraphService.getNode("node-2")).thenReturn(Optional.of(withGraphNode));

        String result = dispatcher.resolveExcelGraphJson(List.of("node-1", "node-2"));

        assertNormalizedSpreadsheetGraph(result);
    }

    private void assertNormalizedSpreadsheetGraph(String result) throws Exception {
        var node = OBJECT_MAPPER.readTree(result);
        assertEquals("Table", node.path("workbookName").asText());
        assertTrue(node.path("cells").has("Table!A1"));
        assertTrue(node.path("dependencies").isArray());
    }

    @Test
    void resolveExcelGraphJson_handlesExceptionGracefully() {
        when(knowledgeGraphService.getNode("bad-node")).thenThrow(new RuntimeException("DB error"));

        // Should not throw, should return null
        assertNull(dispatcher.resolveExcelGraphJson(List.of("bad-node")));
    }

    @Test
    void resolveExcelGraphJson_returnsNullWhenKgServiceIsNull() throws Exception {
        // Set KG service to null
        Field kgField = StepExecutionDispatcherImpl.class.getDeclaredField("knowledgeGraphService");
        kgField.setAccessible(true);
        kgField.set(dispatcher, null);

        assertNull(dispatcher.resolveExcelGraphJson(List.of("any-id")));
    }
}
