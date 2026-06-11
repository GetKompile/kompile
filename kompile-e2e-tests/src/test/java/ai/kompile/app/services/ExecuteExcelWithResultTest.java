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

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.ExecutionStatus;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.service.DispatchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link StepExecutionDispatcherImpl#executeExcelWithResult} —
 * verifies that execution outputs and discovered graph node IDs are returned.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ExecuteExcelWithResultTest {

    @Mock
    private ApplicationContext applicationContext;
    @Mock
    private KnowledgeGraphService knowledgeGraphService;
    @Mock
    private NodeExecutor excelExecutor;

    private StepExecutionDispatcherImpl dispatcher;

    private static final String GRAPH_JSON = "{\"entities\":["
            + "{\"id\":\"wb:test/sheet:Sheet1\",\"type\":\"SHEET\",\"title\":\"Sheet1\"},"
            + "{\"id\":\"wb:test/cell:Sheet1!A1\",\"type\":\"CELL\",\"title\":\"A1\"},"
            + "{\"id\":\"wb:test/cell:Sheet1!B1\",\"type\":\"FORMULA_CELL\",\"title\":\"B1\"}"
            + "],\"relationships\":[]}";

    @BeforeEach
    void setUp() throws Exception {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[0]);

        dispatcher = new StepExecutionDispatcherImpl(applicationContext, new ObjectMapper(), null);

        // Inject mocks via reflection
        setField("knowledgeGraphService", knowledgeGraphService);
        setField("excelExecutor", excelExecutor);

        // Configure executor to support EXCEL type
        when(excelExecutor.supportedTypes()).thenReturn(Set.of(NodeExecutionType.EXCEL));
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field f = StepExecutionDispatcherImpl.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(dispatcher, value);
    }

    @Test
    void returnsOutputsAndDiscoveredNodeIds() {
        // Mock executor returns outputs
        ExecutionResult execResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("B1", 42))
                .build();
        when(excelExecutor.execute(any(ComputeNode.class), any(), any(ExecutionContext.class)))
                .thenReturn(execResult);

        // Mock KG lookups — SHEET maps to TABLE, cells to ENTITY
        when(knowledgeGraphService.getNodeByExternalId("wb:test/sheet:Sheet1", NodeLevel.TABLE))
                .thenReturn(Optional.of(GraphNode.builder().nodeId("kg-sheet-1").build()));
        when(knowledgeGraphService.getNodeByExternalId("wb:test/cell:Sheet1!A1", NodeLevel.ENTITY))
                .thenReturn(Optional.of(GraphNode.builder().nodeId("kg-cell-a1").build()));
        when(knowledgeGraphService.getNodeByExternalId("wb:test/cell:Sheet1!B1", NodeLevel.ENTITY))
                .thenReturn(Optional.of(GraphNode.builder().nodeId("kg-cell-b1").build()));

        DispatchResult result = dispatcher.executeExcelWithResult(GRAPH_JSON, null, "javascript", null);

        assertNotNull(result);
        assertEquals(42, result.getOutputs().get("B1"));
        assertEquals(3, result.getDiscoveredGraphNodeIds().size());
        assertTrue(result.getDiscoveredGraphNodeIds().containsAll(
                List.of("kg-sheet-1", "kg-cell-a1", "kg-cell-b1")));
    }

    @Test
    void returnsEmptyNodeIdsWhenKgServiceIsNull() throws Exception {
        setField("knowledgeGraphService", null);

        ExecutionResult execResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("result", 100))
                .build();
        when(excelExecutor.execute(any(), any(), any())).thenReturn(execResult);

        DispatchResult result = dispatcher.executeExcelWithResult(GRAPH_JSON, null, null, null);

        assertNotNull(result);
        assertEquals(100, result.getOutputs().get("result"));
        assertTrue(result.getDiscoveredGraphNodeIds().isEmpty());
    }

    @Test
    void returnsEmptyNodeIdsWhenNoEntitiesMatchKg() {
        ExecutionResult execResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("x", 1))
                .build();
        when(excelExecutor.execute(any(), any(), any())).thenReturn(execResult);

        // All KG lookups return empty
        when(knowledgeGraphService.getNodeByExternalId(anyString(), any(NodeLevel.class)))
                .thenReturn(Optional.empty());

        DispatchResult result = dispatcher.executeExcelWithResult(GRAPH_JSON, null, null, null);

        assertNotNull(result);
        assertTrue(result.getDiscoveredGraphNodeIds().isEmpty());
    }

    @Test
    void handlesInvalidGraphJsonGracefully() {
        ExecutionResult execResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("ok", true))
                .build();
        when(excelExecutor.execute(any(), any(), any())).thenReturn(execResult);

        // Invalid JSON — node discovery should silently fail
        DispatchResult result = dispatcher.executeExcelWithResult("not-json{", null, null, null);

        assertNotNull(result);
        assertEquals(true, result.getOutputs().get("ok"));
        assertTrue(result.getDiscoveredGraphNodeIds().isEmpty());
    }

    @Test
    void handlesNullGraphJson() {
        ExecutionResult execResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of("val", 5))
                .build();
        when(excelExecutor.execute(any(), any(), any())).thenReturn(execResult);

        DispatchResult result = dispatcher.executeExcelWithResult(null, null, null, null);

        assertNotNull(result);
        assertTrue(result.getDiscoveredGraphNodeIds().isEmpty());
    }

    @Test
    void mapsSheetTypeToTableLevel() {
        ExecutionResult execResult = ExecutionResult.builder()
                .status(ExecutionStatus.COMPLETED)
                .outputs(Map.of())
                .build();
        when(excelExecutor.execute(any(), any(), any())).thenReturn(execResult);

        // Only mock TABLE level for SHEET entity
        when(knowledgeGraphService.getNodeByExternalId("wb:test/sheet:Sheet1", NodeLevel.TABLE))
                .thenReturn(Optional.of(GraphNode.builder().nodeId("table-node").build()));
        when(knowledgeGraphService.getNodeByExternalId(argThat(id -> id.contains("cell:")), eq(NodeLevel.ENTITY)))
                .thenReturn(Optional.empty());

        DispatchResult result = dispatcher.executeExcelWithResult(GRAPH_JSON, null, null, null);

        // Only the SHEET should be discovered since cells return empty
        assertEquals(1, result.getDiscoveredGraphNodeIds().size());
        assertEquals("table-node", result.getDiscoveredGraphNodeIds().get(0));
        // Verify correct NodeLevel was used
        verify(knowledgeGraphService).getNodeByExternalId("wb:test/sheet:Sheet1", NodeLevel.TABLE);
    }

    @Test
    void propagatesExecutorFailureAsException() {
        ExecutionResult execResult = ExecutionResult.builder()
                .status(ExecutionStatus.FAILED)
                .error("formula evaluation error")
                .build();
        when(excelExecutor.execute(any(), any(), any())).thenReturn(execResult);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dispatcher.executeExcelWithResult(GRAPH_JSON, null, null, null));
        assertTrue(ex.getMessage().contains("failed"));
    }
}
