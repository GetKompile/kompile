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

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.execution.RunStatus;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
 * Unit tests for {@link ProcessGraphWritebackService}.
 *
 * <p>Because {@code @Async} is a Spring AOP concern and this test does not load a Spring
 * context, all methods execute synchronously.</p>
 *
 * <p>The service now uses {@code KnowledgeGraphService.createEdgesBatch} for all edge
 * creation (edges are batched per writeback operation). Tests verify edge labels via the
 * {@link KnowledgeGraphService.EdgeSpec} list passed to {@code createEdgesBatch}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessGraphWritebackServiceTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private ObjectMapper objectMapper;
    private ProcessGraphWritebackService service;

    private GraphNode stubStepNode;
    private GraphNode stubRunNode;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Use no-dataDir path to keep tests file-system-free
        service = new ProcessGraphWritebackService(knowledgeGraphService, objectMapper);

        stubStepNode = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .externalId("step-exec:run-1/step-A")
                .title("Step A (completed)")
                .build();

        stubRunNode = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .externalId("process-run:run-1")
                .title("Process Run: proc-def (completed)")
                .build();

        when(knowledgeGraphService.createNode(any(), any(), any(), any(), any()))
                .thenReturn(stubStepNode);
        when(knowledgeGraphService.getNode(anyString())).thenReturn(Optional.empty());
        when(knowledgeGraphService.getNodeByExternalId(anyString(), any())).thenReturn(Optional.empty());
        when(knowledgeGraphService.edgeExists(anyString(), anyString())).thenReturn(false);
        when(knowledgeGraphService.getNodesByExternalIds(any())).thenReturn(List.of());
    }

    // ── Helper to capture EdgeSpec batches ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> captureEdgeLabels() {
        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(knowledgeGraphService, atLeastOnce()).createEdgesBatch(captor.capture());
        return captor.getAllValues().stream()
                .flatMap(Collection::stream)
                .map(KnowledgeGraphService.EdgeSpec::label)
                .toList();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────

    @Test
    void onStepCompleted_createsEntityNodeAndEdges() {
        String graphNodeId1 = UUID.randomUUID().toString();
        String graphNodeId2 = UUID.randomUUID().toString();

        StepExecution step = StepExecution.builder()
                .stepId("step-A")
                .stepName("Review Documents")
                .status(StepExecutionStatus.RUNNING)
                .graphNodeIds(List.of(graphNodeId1, graphNodeId2))
                .build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-1")
                .processDefinitionId("proc-def")
                .status(RunStatus.RUNNING)
                .build();

        service.onStepCompleted(run, step);

        ArgumentCaptor<String> extIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService).createNode(
                eq(NodeLevel.ENTITY),
                extIdCaptor.capture(),
                anyString(), anyString(), anyMap());

        assertEquals("step-exec:run-1/step-A", extIdCaptor.getValue());

        // Both EXECUTED_ON edges must appear in the batch
        List<String> labels = captureEdgeLabels();
        long executedOnCount = labels.stream().filter("EXECUTED_ON"::equals).count();
        assertEquals(2, executedOnCount, "Expected 2 EXECUTED_ON edges, got: " + labels);
    }

    @Test
    void onStepCompleted_skipsWhenNoGraphNodeIds_null() {
        StepExecution step = StepExecution.builder()
                .stepId("step-B").stepName("Idle Step")
                .status(StepExecutionStatus.COMPLETED).graphNodeIds(null).build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-2").processDefinitionId("proc-def").status(RunStatus.RUNNING).build();

        service.onStepCompleted(run, step);

        verify(knowledgeGraphService, never()).createNode(any(), any(), any(), any(), any());
    }

    @Test
    void onStepCompleted_skipsWhenNoGraphNodeIds_empty() {
        StepExecution step = StepExecution.builder()
                .stepId("step-C").stepName("Empty Step")
                .status(StepExecutionStatus.COMPLETED).graphNodeIds(Collections.emptyList()).build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-3").processDefinitionId("proc-def").status(RunStatus.RUNNING).build();

        service.onStepCompleted(run, step);

        verify(knowledgeGraphService, never()).createNode(any(), any(), any(), any(), any());
    }

    @Test
    void onStepCompleted_updatesWithOutputSummaryAndCreatesOutputEntity() {
        String graphNodeId = UUID.randomUUID().toString();

        GraphNode outputNode = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .externalId("step-output:run-4/step-D")
                .title("Output: Summarizer")
                .build();

        // First createNode call returns stubStepNode (step entity),
        // second returns outputNode (output entity)
        when(knowledgeGraphService.createNode(any(), any(), any(), any(), any()))
                .thenReturn(stubStepNode)
                .thenReturn(outputNode);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("result", "success");
        outputs.put("count", 42);

        StepExecution step = StepExecution.builder()
                .stepId("step-D").stepName("Summarizer")
                .status(StepExecutionStatus.COMPLETED)
                .graphNodeIds(List.of(graphNodeId)).outputs(outputs).build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-4").processDefinitionId("proc-def").status(RunStatus.RUNNING).build();

        service.onStepCompleted(run, step);

        // Verify step description updated with summary
        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService).updateNode(
                eq(stubStepNode.getNodeId()), isNull(), descCaptor.capture(), isNull());

        String summary = descCaptor.getValue();
        assertNotNull(summary);
        assertTrue(summary.contains("result") || summary.contains("count"),
                "Summary should reference output keys; got: " + summary);

        // Verify OUTPUT entity was created
        ArgumentCaptor<String> extIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService, atLeast(2)).createNode(
                any(), extIdCaptor.capture(), anyString(), anyString(), anyMap());
        assertTrue(extIdCaptor.getAllValues().contains("step-output:run-4/step-D"),
                "Should create output entity; got: " + extIdCaptor.getAllValues());

        // Verify PRODUCED_OUTPUT edge appears in the batch
        List<String> labels = captureEdgeLabels();
        assertTrue(labels.contains("PRODUCED_OUTPUT"),
                "Expected PRODUCED_OUTPUT edge in batch; got: " + labels);
    }

    @Test
    void onStepCompleted_createsErrorEntityForFailedStep() {
        String graphNodeId = UUID.randomUUID().toString();

        GraphNode errorNode = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .externalId("step-error:run-err/step-F")
                .title("Error: Failing Analyzer")
                .build();

        when(knowledgeGraphService.createNode(any(), any(), any(), any(), any()))
                .thenReturn(stubStepNode)
                .thenReturn(errorNode);

        StepExecution step = StepExecution.builder()
                .stepId("step-F").stepName("Failing Analyzer")
                .status(StepExecutionStatus.FAILED)
                .error("NullPointerException at line 42")
                .graphNodeIds(List.of(graphNodeId)).build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-err").processDefinitionId("proc-def").status(RunStatus.RUNNING).build();

        service.onStepCompleted(run, step);

        // Verify ERROR entity was created
        ArgumentCaptor<String> extIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService, atLeast(2)).createNode(
                any(), extIdCaptor.capture(), anyString(), anyString(), anyMap());
        assertTrue(extIdCaptor.getAllValues().contains("step-error:run-err/step-F"),
                "Should create error entity; got: " + extIdCaptor.getAllValues());

        // Verify PRODUCED_ERROR edge in batch
        List<String> labels = captureEdgeLabels();
        assertTrue(labels.contains("PRODUCED_ERROR"),
                "Expected PRODUCED_ERROR edge in batch; got: " + labels);
    }

    @Test
    void onStepCompleted_createsUsesToolEdgeForToolCallStep() {
        String graphNodeId = UUID.randomUUID().toString();

        GraphNode toolNode = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .externalId("executor:tool:ragQuery")
                .title("Tool: ragQuery")
                .build();

        // First call returns step node, second returns tool node
        when(knowledgeGraphService.createNode(any(), any(), any(), any(), any()))
                .thenReturn(stubStepNode)
                .thenReturn(toolNode);
        when(knowledgeGraphService.getNodeByExternalId("executor:tool:ragQuery", NodeLevel.ENTITY))
                .thenReturn(Optional.empty()); // tool entity doesn't exist yet

        StepExecution step = StepExecution.builder()
                .stepId("step-T").stepName("RAG Query Step")
                .status(StepExecutionStatus.COMPLETED)
                .executedBy("tool:ragQuery")
                .graphNodeIds(List.of(graphNodeId)).build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-tool").processDefinitionId("proc-def").status(RunStatus.RUNNING).build();

        service.onStepCompleted(run, step);

        // Verify USES_TOOL edge in batch
        List<String> labels = captureEdgeLabels();
        assertTrue(labels.contains("USES_TOOL"),
                "Expected USES_TOOL edge in batch; got: " + labels);

        // Verify tool entity was created with correct external ID
        ArgumentCaptor<String> extIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService, atLeast(2)).createNode(
                any(), extIdCaptor.capture(), anyString(), anyString(), anyMap());
        assertTrue(extIdCaptor.getAllValues().contains("executor:tool:ragQuery"),
                "Should create tool entity; got: " + extIdCaptor.getAllValues());
    }

    @Test
    void onRunCompleted_createsRunNodeAndLinksSteps() {
        String graphNodeId = UUID.randomUUID().toString();

        GraphNode existingStepNode = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString()).title("Step A (completed)").build();
        when(knowledgeGraphService.getNodeByExternalId("step-exec:run-5/step-A", NodeLevel.ENTITY))
                .thenReturn(Optional.of(existingStepNode));

        when(knowledgeGraphService.createNode(
                eq(NodeLevel.ENTITY), eq("process-run:run-5"),
                anyString(), anyString(), anyMap()))
                .thenReturn(stubRunNode);

        StepExecution step = StepExecution.builder()
                .stepId("step-A").stepName("Analyze Invoice")
                .status(StepExecutionStatus.COMPLETED).graphNodeIds(List.of(graphNodeId)).build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-5").processDefinitionId("invoice-proc").status(RunStatus.COMPLETED)
                .stepExecutions(List.of(step)).graphNodeIds(null).build();

        service.onRunCompleted(run);

        verify(knowledgeGraphService).createNode(
                eq(NodeLevel.ENTITY), eq("process-run:run-5"),
                anyString(), anyString(), anyMap());

        // Verify INSTANCE_OF and REFERENCES_DATA edges in batch
        List<String> labels = captureEdgeLabels();
        assertTrue(labels.contains("INSTANCE_OF") || labels.contains("REFERENCES_DATA"),
                "Expected INSTANCE_OF or REFERENCES_DATA edge, got: " + labels);
        assertTrue(labels.contains("INSTANCE_OF"),
                "Expected INSTANCE_OF edge from run to process definition, got: " + labels);
    }

    @Test
    void onStepCompleted_handlesExceptionsGracefully() {
        when(knowledgeGraphService.createNode(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("KG service unavailable"));

        StepExecution step = StepExecution.builder()
                .stepId("step-E").stepName("Failing Step")
                .status(StepExecutionStatus.FAILED)
                .graphNodeIds(List.of(UUID.randomUUID().toString())).build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-6").processDefinitionId("proc-def").status(RunStatus.FAILED).build();

        assertDoesNotThrow(() -> service.onStepCompleted(run, step));
    }

    @Test
    void onRunCompleted_handlesExceptionsGracefully() {
        when(knowledgeGraphService.createNode(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("KG service unavailable"));

        WorkflowRun run = WorkflowRun.builder()
                .id("run-7").processDefinitionId("proc-def").status(RunStatus.FAILED)
                .stepExecutions(Collections.emptyList()).build();

        assertDoesNotThrow(() -> service.onRunCompleted(run));
    }
}
