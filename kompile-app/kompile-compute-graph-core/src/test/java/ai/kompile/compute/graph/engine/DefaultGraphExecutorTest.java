package ai.kompile.compute.graph.engine;

import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultGraphExecutorTest {

    private DefaultGraphExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DefaultGraphExecutor(
                List.of(new PassthroughNodeExecutor()),
                new InMemoryArtifactStore()
        );
    }

    @Test
    void testExecute_singlePassthroughNode() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("single")
                .nodes(List.of(
                        ComputeNode.builder()
                                .id("node1")
                                .name("Single Node")
                                .executionType(NodeExecutionType.PASSTHROUGH)
                                .build()
                ))
                .build();

        GraphExecutionResult result = executor.execute(graph, Map.of("x", 42, "y", "hello"));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(1, result.getNodeResults().size());
        assertEquals(42, result.getFinalOutputs().get("x"));
        assertEquals("hello", result.getFinalOutputs().get("y"));
    }

    @Test
    void testExecute_linearChain_passesDataThrough() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("chain")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("A").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("B").name("B").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("C").name("C").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder().id("e1").sourceNodeId("A").targetNodeId("B").build(),
                        ComputeEdge.builder().id("e2").sourceNodeId("B").targetNodeId("C").build()
                ))
                .build();

        GraphExecutionResult result = executor.execute(graph, Map.of("value", 100));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(3, result.getNodeResults().size());
        assertEquals(100, result.getFinalOutputs().get("value"));
        assertEquals(List.of("A", "B", "C"), result.getExecutionOrder());
    }

    @Test
    void testExecute_conditionalEdge_satisfied() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("conditional")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("A").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("B").name("B").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder()
                                .id("e1")
                                .sourceNodeId("A")
                                .targetNodeId("B")
                                .condition("#score > 0.5")
                                .build()
                ))
                .build();

        GraphExecutionResult result = executor.execute(graph, Map.of("score", 0.8));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        // B should have executed since score > 0.5
        assertEquals(ExecutionStatus.COMPLETED, result.getNodeResults().get("B").getStatus());
    }

    @Test
    void testExecute_conditionalEdge_notSatisfied() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("conditional")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("A").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("B").name("B").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder()
                                .id("e1")
                                .sourceNodeId("A")
                                .targetNodeId("B")
                                .condition("#score > 0.5")
                                .build()
                ))
                .build();

        GraphExecutionResult result = executor.execute(graph, Map.of("score", 0.2));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        // B should be skipped since score <= 0.5
        assertEquals(ExecutionStatus.SKIPPED, result.getNodeResults().get("B").getStatus());
        assertTrue(result.getSkippedNodes().contains("B"));
    }

    @Test
    void testExecute_dataMapping() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("mapping")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("A").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("B").name("B").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder()
                                .id("e1")
                                .sourceNodeId("A")
                                .targetNodeId("B")
                                .dataMapping(Map.of("originalName", "renamedName"))
                                .build()
                ))
                .build();

        GraphExecutionResult result = executor.execute(graph, Map.of("originalName", "value123"));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        Map<String, Object> bOutputs = result.getNodeResults().get("B").getOutputs();
        assertEquals("value123", bOutputs.get("renamedName"));
        assertNull(bOutputs.get("originalName"));
    }

    @Test
    void testExecute_nodeWithParameters() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("params")
                .nodes(List.of(
                        ComputeNode.builder()
                                .id("A")
                                .name("A")
                                .executionType(NodeExecutionType.PASSTHROUGH)
                                .parameters(Map.of("threshold", 0.75, "label", "classifier"))
                                .build()
                ))
                .build();

        GraphExecutionResult result = executor.execute(graph, Map.of("input", "data"));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        Map<String, Object> outputs = result.getFinalOutputs();
        // Passthrough merges inputs + parameters
        assertEquals("data", outputs.get("input"));
        assertEquals(0.75, outputs.get("threshold"));
        assertEquals("classifier", outputs.get("label"));
    }

    @Test
    void testExecute_noExecutorForType_fails() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("missing-executor")
                .nodes(List.of(
                        ComputeNode.builder()
                                .id("A")
                                .name("A")
                                .executionType(NodeExecutionType.JAVASCRIPT) // No JS executor registered
                                .build()
                ))
                .build();

        GraphExecutionResult result = executor.execute(graph, Map.of());

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().contains("No executor registered"));
    }

    @Test
    void testValidate_validGraph() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("valid")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("A").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("B").name("B").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder().id("e1").sourceNodeId("A").targetNodeId("B").build()
                ))
                .build();

        assertNull(executor.validate(graph));
    }

    @Test
    void testValidate_missingEdgeReference() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("invalid")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("A").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder().id("e1").sourceNodeId("A").targetNodeId("NONEXISTENT").build()
                ))
                .build();

        String errors = executor.validate(graph);
        assertNotNull(errors);
        assertTrue(errors.contains("non-existent target node"));
    }

    @Test
    void testExecuteSingleNode() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("test")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("A").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("B").name("B").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder().id("e1").sourceNodeId("A").targetNodeId("B").build()
                ))
                .build();

        GraphExecutionResult result = executor.executeSingleNode(graph, "B", Map.of("test", "value"));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(1, result.getNodeResults().size());
        assertEquals("value", result.getFinalOutputs().get("test"));
    }
}
