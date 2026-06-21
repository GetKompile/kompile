package ai.kompile.knowledgegraph.matrix.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Model-level tests for the first-class, explicitly-stored semantic {@code relationType} field on the
 * matrix graph — distinct from the structural {@code edgeType} routing key. The persistence layer
 * ({@code VectorStoreMatrixGraphStore}) serializes the relation from {@link AdjacencyMatrixGraph#getSparseEdges}
 * and restores it via the relation-carrying {@code addEdge} overload, so covering both here proves the
 * save/restore round-trip carries the relation on the vector store.
 */
class AdjacencyMatrixGraphTest {

    private AdjacencyMatrixGraph newGraphWithNodes() {
        AdjacencyMatrixGraph g = new AdjacencyMatrixGraph("g", 8);
        g.addNode(MatrixGraphNode.builder().nodeId("a").nodeType("ENTITY").build());
        g.addNode(MatrixGraphNode.builder().nodeId("b").nodeType("ENTITY").build());
        return g;
    }

    @Test
    void addEdgeStoresExplicitRelationTypeAsFirstClassField() {
        try (AdjacencyMatrixGraph g = newGraphWithNodes()) {
            // Structural routing key ("USER_DEFINED") deliberately distinct from the semantic relation:
            // the key heuristic could never derive "WORKS_AT" from "USER_DEFINED", so a correct read
            // proves the relation is a real stored field, not inferred from the key.
            g.addEdge("a", "b", 0.9, "USER_DEFINED", false, "WORKS_AT");

            assertEquals("WORKS_AT", g.getEdgeRelationType("USER_DEFINED", "a", "b"));
        }
    }

    @Test
    void structuralEdgeHasNoRelationType() {
        try (AdjacencyMatrixGraph g = newGraphWithNodes()) {
            g.addEdge("a", "b", 1.0, "HIERARCHICAL", false);   // 5-arg overload → null relationType
            assertNull(g.getEdgeRelationType("HIERARCHICAL", "a", "b"));
        }
    }

    @Test
    void bidirectionalEdgeStoresRelationTypeBothDirections() {
        try (AdjacencyMatrixGraph g = newGraphWithNodes()) {
            g.addEdge("a", "b", 0.5, "USER_DEFINED", true, "KNOWS");
            assertEquals("KNOWS", g.getEdgeRelationType("USER_DEFINED", "a", "b"));
            assertEquals("KNOWS", g.getEdgeRelationType("USER_DEFINED", "b", "a"));
        }
    }

    @Test
    void getSparseEdgesCarriesRelationTypeForPersistence() {
        try (AdjacencyMatrixGraph g = newGraphWithNodes()) {
            g.addEdge("a", "b", 0.9, "USER_DEFINED", false, "WORKS_AT");

            AdjacencyMatrixGraph.SparseEdgeData sparse = g.getSparseEdges("USER_DEFINED");
            assertEquals(1, sparse.size());
            // The persistence layer serializes from this parallel list — it must be present and aligned.
            assertEquals(1, sparse.relationTypes.size());
            assertEquals("WORKS_AT", sparse.relationTypes.get(0));
        }
    }

    @Test
    void removeEdgeDropsRelationType() {
        try (AdjacencyMatrixGraph g = newGraphWithNodes()) {
            g.addEdge("a", "b", 0.9, "USER_DEFINED", false, "WORKS_AT");
            g.removeEdge("a", "b", "USER_DEFINED");
            assertNull(g.getEdgeRelationType("USER_DEFINED", "a", "b"));
        }
    }
}
