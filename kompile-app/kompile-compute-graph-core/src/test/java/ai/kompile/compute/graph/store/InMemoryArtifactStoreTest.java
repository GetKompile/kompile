package ai.kompile.compute.graph.store;

import ai.kompile.compute.graph.model.ComputeArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryArtifactStoreTest {

    private InMemoryArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryArtifactStore();
    }

    @Test
    void testStoreAndRetrieve() {
        ComputeArtifact artifact = ComputeArtifact.builder()
                .id("art1")
                .executionId("exec1")
                .nodeId("node1")
                .name("output")
                .data(Map.of("score", 0.95))
                .build();

        store.store(artifact);

        Optional<ComputeArtifact> retrieved = store.get("art1");
        assertTrue(retrieved.isPresent());
        assertEquals("art1", retrieved.get().getId());
        assertEquals(0.95, retrieved.get().getData().get("score"));
    }

    @Test
    void testGetByExecutionId() {
        store.store(ComputeArtifact.builder().id("a1").executionId("exec1").nodeId("n1").name("out1").build());
        store.store(ComputeArtifact.builder().id("a2").executionId("exec1").nodeId("n2").name("out2").build());
        store.store(ComputeArtifact.builder().id("a3").executionId("exec2").nodeId("n1").name("out3").build());

        List<ComputeArtifact> exec1Artifacts = store.getByExecutionId("exec1");
        assertEquals(2, exec1Artifacts.size());

        List<ComputeArtifact> exec2Artifacts = store.getByExecutionId("exec2");
        assertEquals(1, exec2Artifacts.size());
    }

    @Test
    void testGetByNodeId() {
        store.store(ComputeArtifact.builder().id("a1").executionId("exec1").nodeId("nodeA").name("out1").build());
        store.store(ComputeArtifact.builder().id("a2").executionId("exec2").nodeId("nodeA").name("out2").build());
        store.store(ComputeArtifact.builder().id("a3").executionId("exec1").nodeId("nodeB").name("out3").build());

        List<ComputeArtifact> nodeAArtifacts = store.getByNodeId("nodeA");
        assertEquals(2, nodeAArtifacts.size());
    }

    @Test
    void testGetByExecutionAndNode() {
        store.store(ComputeArtifact.builder().id("a1").executionId("exec1").nodeId("n1").name("out1").build());
        store.store(ComputeArtifact.builder().id("a2").executionId("exec1").nodeId("n2").name("out2").build());
        store.store(ComputeArtifact.builder().id("a3").executionId("exec2").nodeId("n1").name("out3").build());

        List<ComputeArtifact> result = store.getByExecutionAndNode("exec1", "n1");
        assertEquals(1, result.size());
        assertEquals("a1", result.get(0).getId());
    }

    @Test
    void testDelete() {
        store.store(ComputeArtifact.builder().id("a1").executionId("exec1").nodeId("n1").name("out").build());
        assertTrue(store.get("a1").isPresent());

        store.delete("a1");
        assertFalse(store.get("a1").isPresent());
    }

    @Test
    void testDeleteByExecutionId() {
        store.store(ComputeArtifact.builder().id("a1").executionId("exec1").nodeId("n1").name("out1").build());
        store.store(ComputeArtifact.builder().id("a2").executionId("exec1").nodeId("n2").name("out2").build());
        store.store(ComputeArtifact.builder().id("a3").executionId("exec2").nodeId("n1").name("out3").build());

        store.deleteByExecutionId("exec1");

        assertEquals(0, store.getByExecutionId("exec1").size());
        assertEquals(1, store.getByExecutionId("exec2").size());
    }

    @Test
    void testGetNonExistent_returnsEmpty() {
        assertFalse(store.get("nonexistent").isPresent());
        assertEquals(0, store.getByExecutionId("nonexistent").size());
    }
}
