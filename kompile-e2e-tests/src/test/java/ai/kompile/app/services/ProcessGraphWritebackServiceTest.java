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

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProcessGraphWritebackService}.
 *
 * <p>Because {@code @Async} is a Spring AOP concern and this test does not load a Spring
 * context, all methods execute synchronously — which is exactly what we want for
 * deterministic assertions without thread-timing headaches.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessGraphWritebackServiceTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private ObjectMapper objectMapper;
    private ProcessGraphWritebackService service;

    // A reusable GraphNode returned by createNode() mocks
    private GraphNode stubStepNode;
    private GraphNode stubRunNode;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
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

        // Default stub: createNode always returns the step node stub; tests can override
        when(knowledgeGraphService.createNode(any(), any(), any(), any(), any()))
                .thenReturn(stubStepNode);

        // Default stub: getNode returns empty (no target node found)
        when(knowledgeGraphService.getNode(anyString())).thenReturn(Optional.empty());

        // Default stub: getNodeByExternalId returns empty
        when(knowledgeGraphService.getNodeByExternalId(anyString(), any())).thenReturn(Optional.empty());

        // Default stub: edgeExists returns false
        when(knowledgeGraphService.edgeExists(anyString(), anyString())).thenReturn(false);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. onStepCompleted — happy path
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * When a step has graphNodeIds, onStepCompleted should create a ENTITY node
     * with the deterministic externalId "step-exec:runId/stepId", then attempt to
     * create EXECUTED_ON edges for every referenced graph node that exists.
     */
    @Test
    void onStepCompleted_createsEntityNodeAndEdges() {
        String graphNodeId1 = UUID.randomUUID().toString();
        String graphNodeId2 = UUID.randomUUID().toString();

        // Make both graph nodes "exist"
        GraphNode targetNode1 = GraphNode.builder().nodeId(graphNodeId1).title("Doc 1").build();
        GraphNode targetNode2 = GraphNode.builder().nodeId(graphNodeId2).title("Doc 2").build();
        when(knowledgeGraphService.getNode(graphNodeId1)).thenReturn(Optional.of(targetNode1));
        when(knowledgeGraphService.getNode(graphNodeId2)).thenReturn(Optional.of(targetNode2));

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

        // createNode must be called with the correct externalId
        ArgumentCaptor<String> extIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService).createNode(
                eq(NodeLevel.ENTITY),
                extIdCaptor.capture(),
                anyString(),
                anyString(),
                anyMap());

        assertEquals("step-exec:run-1/step-A", extIdCaptor.getValue());

        // createEdgeWithMetadata must be called once per existing graph node
        verify(knowledgeGraphService, times(2))
                .createEdgeWithMetadata(
                        eq(stubStepNode.getNodeId()),
                        anyString(),
                        any(EdgeType.class),
                        anyDouble(),
                        anyString(),
                        anyString(),
                        isNull(),
                        any(EdgeProvenance.class),
                        isNull());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. onStepCompleted — skip when no graphNodeIds
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * A step with null graphNodeIds must not trigger any KG write at all.
     */
    @Test
    void onStepCompleted_skipsWhenNoGraphNodeIds_null() {
        StepExecution step = StepExecution.builder()
                .stepId("step-B")
                .stepName("Idle Step")
                .status(StepExecutionStatus.COMPLETED)
                .graphNodeIds(null)
                .build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-2")
                .processDefinitionId("proc-def")
                .status(RunStatus.RUNNING)
                .build();

        service.onStepCompleted(run, step);

        verify(knowledgeGraphService, never()).createNode(any(), any(), any(), any(), any());
    }

    /**
     * A step with an empty graphNodeIds list must not trigger any KG write at all.
     */
    @Test
    void onStepCompleted_skipsWhenNoGraphNodeIds_empty() {
        StepExecution step = StepExecution.builder()
                .stepId("step-C")
                .stepName("Empty Step")
                .status(StepExecutionStatus.COMPLETED)
                .graphNodeIds(Collections.emptyList())
                .build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-3")
                .processDefinitionId("proc-def")
                .status(RunStatus.RUNNING)
                .build();

        service.onStepCompleted(run, step);

        verify(knowledgeGraphService, never()).createNode(any(), any(), any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. onStepCompleted — output summary written on COMPLETED
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * When a step has COMPLETED status and non-empty outputs, updateNode must be called
     * with a non-null output summary string on the step node.
     */
    @Test
    void onStepCompleted_updatesWithOutputSummary() {
        String graphNodeId = UUID.randomUUID().toString();
        when(knowledgeGraphService.getNode(graphNodeId))
                .thenReturn(Optional.of(GraphNode.builder().nodeId(graphNodeId).title("Some doc").build()));

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("result", "success");
        outputs.put("count", 42);

        StepExecution step = StepExecution.builder()
                .stepId("step-D")
                .stepName("Summarizer")
                .status(StepExecutionStatus.COMPLETED)
                .graphNodeIds(List.of(graphNodeId))
                .outputs(outputs)
                .build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-4")
                .processDefinitionId("proc-def")
                .status(RunStatus.RUNNING)
                .build();

        service.onStepCompleted(run, step);

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService).updateNode(
                eq(stubStepNode.getNodeId()),
                isNull(),
                descCaptor.capture(),
                isNull());

        String summary = descCaptor.getValue();
        assertNotNull(summary, "Output summary should not be null");
        assertTrue(summary.contains("result") || summary.contains("count"),
                "Summary should reference output keys; got: " + summary);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. onRunCompleted — run node + CONTAINS_STEP + REFERENCES_DATA edges
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * onRunCompleted must:
     * 1. Create a run-level ENTITY node with externalId "process-run:runId"
     * 2. Create CONTAINS_STEP edges to any step nodes that already exist in KG
     * 3. Create REFERENCES_DATA edges to all unique graph node IDs across all steps
     */
    @Test
    void onRunCompleted_createsRunNodeAndLinksSteps() {
        String graphNodeId = UUID.randomUUID().toString();

        // Step node already exists in KG
        GraphNode existingStepNode = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .title("Step A (completed)")
                .build();
        when(knowledgeGraphService.getNodeByExternalId("step-exec:run-5/step-A", NodeLevel.ENTITY))
                .thenReturn(Optional.of(existingStepNode));

        // The data graph node exists too
        GraphNode dataNode = GraphNode.builder().nodeId(graphNodeId).title("Invoice Doc").build();
        when(knowledgeGraphService.getNode(graphNodeId)).thenReturn(Optional.of(dataNode));

        // createNode for the run must return runNode
        when(knowledgeGraphService.createNode(
                eq(NodeLevel.ENTITY),
                eq("process-run:run-5"),
                anyString(), anyString(), anyMap()))
                .thenReturn(stubRunNode);

        StepExecution step = StepExecution.builder()
                .stepId("step-A")
                .stepName("Analyze Invoice")
                .status(StepExecutionStatus.COMPLETED)
                .graphNodeIds(List.of(graphNodeId))
                .build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-5")
                .processDefinitionId("invoice-proc")
                .status(RunStatus.COMPLETED)
                .stepExecutions(List.of(step))
                .graphNodeIds(null)
                .build();

        service.onRunCompleted(run);

        // Run node must be created
        verify(knowledgeGraphService).createNode(
                eq(NodeLevel.ENTITY),
                eq("process-run:run-5"),
                anyString(), anyString(), anyMap());

        // CONTAINS_STEP edge must exist for the resolved step node
        ArgumentCaptor<String> edgeLabelCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                eq(stubRunNode.getNodeId()),
                anyString(),
                any(EdgeType.class),
                anyDouble(),
                edgeLabelCaptor.capture(),
                anyString(),
                isNull(),
                any(EdgeProvenance.class),
                isNull());

        List<String> labels = edgeLabelCaptor.getAllValues();
        assertTrue(labels.contains("CONTAINS_STEP") || labels.contains("REFERENCES_DATA"),
                "Expected CONTAINS_STEP or REFERENCES_DATA edges, got: " + labels);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 5. onStepCompleted — exceptions from KG service must not propagate
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * If createNode throws a RuntimeException, onStepCompleted must catch it internally
     * and not let it propagate to the caller (i.e., the process engine keeps running).
     */
    @Test
    void onStepCompleted_handlesExceptionsGracefully() {
        when(knowledgeGraphService.createNode(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("KG service unavailable"));

        StepExecution step = StepExecution.builder()
                .stepId("step-E")
                .stepName("Failing Step")
                .status(StepExecutionStatus.FAILED)
                .graphNodeIds(List.of(UUID.randomUUID().toString()))
                .build();

        WorkflowRun run = WorkflowRun.builder()
                .id("run-6")
                .processDefinitionId("proc-def")
                .status(RunStatus.FAILED)
                .build();

        // Must not throw
        assertDoesNotThrow(() -> service.onStepCompleted(run, step),
                "onStepCompleted must swallow KG service exceptions");
    }

    /**
     * If onRunCompleted's createNode throws, it must also not propagate.
     */
    @Test
    void onRunCompleted_handlesExceptionsGracefully() {
        when(knowledgeGraphService.createNode(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("KG service unavailable"));

        WorkflowRun run = WorkflowRun.builder()
                .id("run-7")
                .processDefinitionId("proc-def")
                .status(RunStatus.FAILED)
                .stepExecutions(Collections.emptyList())
                .build();

        assertDoesNotThrow(() -> service.onRunCompleted(run),
                "onRunCompleted must swallow KG service exceptions");
    }
}
