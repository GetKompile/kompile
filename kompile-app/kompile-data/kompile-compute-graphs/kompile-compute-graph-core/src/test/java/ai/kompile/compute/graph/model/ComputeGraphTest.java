package ai.kompile.compute.graph.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComputeGraphTest {

    @Test
    void testTopologicalSort_linearChain() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("test-graph")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("Node A").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("B").name("Node B").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("C").name("Node C").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder().id("e1").sourceNodeId("A").targetNodeId("B").build(),
                        ComputeEdge.builder().id("e2").sourceNodeId("B").targetNodeId("C").build()
                ))
                .build();

        List<ComputeNode> sorted = graph.topologicalSort();
        assertEquals(3, sorted.size());
        assertEquals("A", sorted.get(0).getId());
        assertEquals("B", sorted.get(1).getId());
        assertEquals("C", sorted.get(2).getId());
    }

    @Test
    void testTopologicalSort_diamond() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("diamond")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("root").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("B").name("left").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("C").name("right").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("D").name("sink").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder().id("e1").sourceNodeId("A").targetNodeId("B").build(),
                        ComputeEdge.builder().id("e2").sourceNodeId("A").targetNodeId("C").build(),
                        ComputeEdge.builder().id("e3").sourceNodeId("B").targetNodeId("D").build(),
                        ComputeEdge.builder().id("e4").sourceNodeId("C").targetNodeId("D").build()
                ))
                .build();

        List<ComputeNode> sorted = graph.topologicalSort();
        assertEquals(4, sorted.size());
        assertEquals("A", sorted.get(0).getId());
        // B and C can be in either order, but both must come before D
        assertTrue(sorted.indexOf(graph.findNode("B").get()) < sorted.indexOf(graph.findNode("D").get()));
        assertTrue(sorted.indexOf(graph.findNode("C").get()) < sorted.indexOf(graph.findNode("D").get()));
        assertEquals("D", sorted.get(3).getId());
    }

    @Test
    void testTopologicalSort_detectsCycle() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("cycle")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("A").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("B").name("B").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder().id("e1").sourceNodeId("A").targetNodeId("B").build(),
                        ComputeEdge.builder().id("e2").sourceNodeId("B").targetNodeId("A").build()
                ))
                .build();

        assertThrows(IllegalStateException.class, graph::topologicalSort);
    }

    @Test
    void testGetRootNodes() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("test")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("A").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("B").name("B").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("C").name("C").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder().id("e1").sourceNodeId("A").targetNodeId("B").build(),
                        ComputeEdge.builder().id("e2").sourceNodeId("A").targetNodeId("C").build()
                ))
                .build();

        List<ComputeNode> roots = graph.getRootNodes();
        assertEquals(1, roots.size());
        assertEquals("A", roots.get(0).getId());
    }

    @Test
    void testGetTerminalNodes() {
        ComputeGraph graph = ComputeGraph.builder()
                .id("test")
                .nodes(List.of(
                        ComputeNode.builder().id("A").name("A").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("B").name("B").executionType(NodeExecutionType.PASSTHROUGH).build(),
                        ComputeNode.builder().id("C").name("C").executionType(NodeExecutionType.PASSTHROUGH).build()
                ))
                .edges(List.of(
                        ComputeEdge.builder().id("e1").sourceNodeId("A").targetNodeId("B").build(),
                        ComputeEdge.builder().id("e2").sourceNodeId("A").targetNodeId("C").build()
                ))
                .build();

        List<ComputeNode> terminals = graph.getTerminalNodes();
        assertEquals(2, terminals.size());
        assertTrue(terminals.stream().anyMatch(n -> n.getId().equals("B")));
        assertTrue(terminals.stream().anyMatch(n -> n.getId().equals("C")));
    }
}
