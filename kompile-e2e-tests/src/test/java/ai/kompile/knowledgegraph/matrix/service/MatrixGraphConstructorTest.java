/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MatrixGraphConstructor}.
 * <p>
 * Covers:
 * <ul>
 *   <li>DEFAULT_GRAPH_ID usage and consistency with MatrixGraphRagService</li>
 *   <li>Incremental graph building (load existing graph before create)</li>
 *   <li>Entity type and relationship type preservation in convertToGraph()</li>
 *   <li>Schema enforcement (STRICT filters, LENIENT/NONE keeps all)</li>
 *   <li>Embedding generation and storage</li>
 *   <li>Error handling (LLM failures, parse errors)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MatrixGraphConstructor Tests")
class MatrixGraphConstructorTest {

    @Mock
    private MatrixGraphStore graphStore;

    @Captor
    private ArgumentCaptor<MatrixGraphNode> nodeCaptor;

    @Captor
    private ArgumentCaptor<List<String>> nodeIdsCaptor;

    @Captor
    private ArgumentCaptor<INDArray> embeddingsCaptor;

    private StubEmbeddingModel embeddingModel;
    private StubLLMChat llmChat;
    private MatrixGraphConstructor constructor;

    private static final String DEFAULT_GRAPH_ID = "default-knowledge-graph";

    @BeforeEach
    void setUp() {
        embeddingModel = new StubEmbeddingModel();
        llmChat = new StubLLMChat();
        constructor = new MatrixGraphConstructor(graphStore, llmChat, embeddingModel, new ObjectMapper());
    }

    // ========================================
    // Graph ID Consistency Tests
    // ========================================

    @Nested
    @DisplayName("Graph ID Consistency")
    class GraphIdConsistency {

        @Test
        @DisplayName("constructGraphFromDocs uses an isolated graph ID")
        void usesDefaultGraphId() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.empty());
            when(graphStore.createGraph(eq(DEFAULT_GRAPH_ID), isNull())).thenReturn(graph);

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(entity("e1", "Alice", "PERSON", "A person")),
                    List.of()
            ));

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice is a software engineer.")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — graph store operations used the returned isolated graph ID
            assertNotNull(result.getId());
            assertTrue(result.getId().startsWith("graph-"));
            assertNotEquals(DEFAULT_GRAPH_ID, result.getId());
            verify(graphStore).createGraph(eq(result.getId()), isNull());
            verify(graphStore).addNode(eq(result.getId()), any(MatrixGraphNode.class));
            verify(graphStore).storeNodeEmbeddings(eq(result.getId()), anyList(), any(INDArray.class));
        }

        @Test
        @DisplayName("DEFAULT_GRAPH_ID constant matches MatrixGraphRagService")
        void defaultGraphIdMatchesRagService() {
            assertEquals("default-knowledge-graph", DEFAULT_GRAPH_ID);
        }
    }

    // ========================================
    // Incremental Graph Building Tests
    // ========================================

    @Nested
    @DisplayName("Incremental Graph Building")
    class IncrementalBuilding {

        @Test
        @DisplayName("Reuses existing graph when one exists for DEFAULT_GRAPH_ID")
        void reusesExistingGraph() {
            // Arrange — graph already exists
            AdjacencyMatrixGraph existingGraph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            existingGraph.addNode(MatrixGraphNode.builder()
                    .nodeId("existing-node")
                    .title("Existing Entity")
                    .nodeType("CONCEPT")
                    .build());

            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(existingGraph));

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(entity("e1", "New Entity", "CONCEPT", "A new concept")),
                    List.of()
            ));

            // Act
            constructor.constructGraphFromDocs(
                    List.of(doc("d1", "New concept text")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — constructGraphFromDocs creates an isolated graph per extraction
            verify(graphStore).createGraph(argThat(id -> id != null && id.startsWith("graph-")), isNull());
            // Should still add the new node
            verify(graphStore).addNode(argThat(id -> id != null && id.startsWith("graph-")), any(MatrixGraphNode.class));
        }

        @Test
        @DisplayName("Creates new graph when none exists")
        void createsNewGraph() {
            // Arrange
            AdjacencyMatrixGraph newGraph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.empty());
            when(graphStore.createGraph(DEFAULT_GRAPH_ID, null)).thenReturn(newGraph);

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(entity("e1", "Entity", "CONCEPT", "desc")),
                    List.of()
            ));

            // Act
            constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Some text")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — createGraph is called with an isolated graph ID
            verify(graphStore).createGraph(argThat(id -> id != null && id.startsWith("graph-")), isNull());
        }

        @Test
        @DisplayName("Multiple calls accumulate nodes in same graph")
        void multipleCallsAccumulate() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            // First call: graph does not exist
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID))
                    .thenReturn(Optional.empty())      // first call's initial load
                    .thenReturn(Optional.of(graph))     // first call's save load
                    .thenReturn(Optional.of(graph))     // second call's initial load
                    .thenReturn(Optional.of(graph));    // second call's save load
            when(graphStore.createGraph(DEFAULT_GRAPH_ID, null)).thenReturn(graph);

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(entity("e1", "Entity1", "CONCEPT", "first")),
                    List.of()
            ));

            // Act — first ingestion
            constructor.constructGraphFromDocs(
                    List.of(doc("d1", "First doc")),
                    null, SchemaEnforcementMode.NONE
            );

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(entity("e2", "Entity2", "CONCEPT", "second")),
                    List.of()
            ));

            // Act — second ingestion
            constructor.constructGraphFromDocs(
                    List.of(doc("d2", "Second doc")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — each extraction receives an isolated graph ID
            verify(graphStore, times(2)).createGraph(argThat(id -> id != null && id.startsWith("graph-")), isNull());
            verify(graphStore, times(2)).addNode(argThat(id -> id != null && id.startsWith("graph-")), any(MatrixGraphNode.class));
        }
    }

    // ========================================
    // Entity Type Preservation Tests
    // ========================================

    @Nested
    @DisplayName("Entity Type Preservation")
    class EntityTypePreservation {

        @Test
        @DisplayName("Entity type is preserved from LLM extraction (label → type)")
        void entityTypePreserved() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(
                            entity("e1", "Alice", "PERSON", "A person"),
                            entity("e2", "TechCorp", "ORGANIZATION", "A company"),
                            entity("e3", "Berlin", "LOCATION", "A city")
                    ),
                    List.of()
            ));

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice works at TechCorp in Berlin")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — entity types are preserved in the core Graph model
            assertNotNull(result.getEntities());
            assertEquals(3, result.getEntities().size());

            assertTrue(result.getEntities().stream()
                    .anyMatch(e -> e.getTitle().equals("Alice") && "PERSON".equals(e.getType())));
            assertTrue(result.getEntities().stream()
                    .anyMatch(e -> e.getTitle().equals("TechCorp") && "ORGANIZATION".equals(e.getType())));
            assertTrue(result.getEntities().stream()
                    .anyMatch(e -> e.getTitle().equals("Berlin") && "LOCATION".equals(e.getType())));
        }

        @Test
        @DisplayName("Entity with null label defaults to ENTITY type")
        void nullLabelDefaultsToEntity() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            // JSON with no "label" field for entity
            String json = "{\"entities\":[{\"id\":\"e1\",\"title\":\"Unknown\",\"description\":\"No label\"}],\"relationships\":[]}";
            llmChat.setFixedResponse(json);

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Unknown entity")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert
            assertEquals(1, result.getEntities().size());
            assertEquals("ENTITY", result.getEntities().get(0).getType());
        }

        @Test
        @DisplayName("Relationship type is preserved from LLM extraction")
        void relationshipTypePreserved() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(
                            entity("e1", "Alice", "PERSON", "Employee"),
                            entity("e2", "TechCorp", "ORGANIZATION", "Company")
                    ),
                    List.of(relationship("e1", "e2", "WORKS_AT", "Alice works at TechCorp", 0.9))
            ));

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice works at TechCorp")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert
            assertNotNull(result.getRelationships());
            assertEquals(1, result.getRelationships().size());
            assertEquals("WORKS_AT", result.getRelationships().get(0).getType());
        }

        @Test
        @DisplayName("Relationship with null type defaults to RELATED_TO")
        void nullRelTypeDefaultsToRelatedTo() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            // JSON with no "type" field for relationship
            String json = "{\"entities\":[" +
                    "{\"id\":\"e1\",\"title\":\"A\",\"label\":\"CONCEPT\",\"description\":\"A\"}," +
                    "{\"id\":\"e2\",\"title\":\"B\",\"label\":\"CONCEPT\",\"description\":\"B\"}" +
                    "],\"relationships\":[" +
                    "{\"source\":\"e1\",\"target\":\"e2\",\"description\":\"connected\"}" +
                    "]}";
            llmChat.setFixedResponse(json);

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("d1", "A and B are connected")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert
            assertEquals(1, result.getRelationships().size());
            assertEquals("RELATED_TO", result.getRelationships().get(0).getType());
        }

        @Test
        @DisplayName("Node type is set on MatrixGraphNode stored in graph store")
        void nodeTypeStoredInGraphStore() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(entity("e1", "Alice", "PERSON", "A person")),
                    List.of()
            ));

            // Act
            constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice is a person")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — verify node type was set on the MatrixGraphNode
            verify(graphStore).addNode(anyString(), nodeCaptor.capture());
            MatrixGraphNode storedNode = nodeCaptor.getValue();
            assertEquals("PERSON", storedNode.getNodeType());
            assertEquals("Alice", storedNode.getTitle());
        }
    }

    // ========================================
    // Embedding Storage Tests
    // ========================================

    @Nested
    @DisplayName("Embedding Generation and Storage")
    class EmbeddingStorage {

        @Test
        @DisplayName("Generates and stores embeddings for extracted entities")
        void generatesAndStoresEmbeddings() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            // loadGraph is called twice: once at initial load, once at save step
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph))
                    .thenReturn(Optional.of(graph));

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(
                            entity("e1", "Alice", "PERSON", "An engineer"),
                            entity("e2", "TechCorp", "ORGANIZATION", "A company")
                    ),
                    List.of()
            ));

            // Act
            constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice works at TechCorp")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert
            verify(graphStore).storeNodeEmbeddings(
                    anyString(), nodeIdsCaptor.capture(), embeddingsCaptor.capture());

            List<String> storedNodeIds = nodeIdsCaptor.getValue();
            assertEquals(2, storedNodeIds.size());
            // Node IDs are prefixed with doc ID
            assertTrue(storedNodeIds.get(0).contains("e1"));
            assertTrue(storedNodeIds.get(1).contains("e2"));

            INDArray embeddings = embeddingsCaptor.getValue();
            assertNotNull(embeddings);
            assertEquals(2, embeddings.rows());
        }

        @Test
        @DisplayName("Skips embedding storage when no entities extracted")
        void skipsEmbeddingWhenNoEntities() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            // LLM returns empty extraction
            llmChat.setFixedResponse("{\"entities\":[],\"relationships\":[]}");

            // Act
            constructor.constructGraphFromDocs(
                    List.of(doc("d1", "No entities here")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert
            verify(graphStore, never()).storeNodeEmbeddings(anyString(), anyList(), any(INDArray.class));
        }

        @Test
        @DisplayName("Handles embedding model failure gracefully")
        void handlesEmbeddingFailure() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            embeddingModel.setShouldReturnNull(true);

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(entity("e1", "Alice", "PERSON", "A person")),
                    List.of()
            ));

            // Act — should not throw
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — graph is still returned even if embeddings fail
            assertNotNull(result);
            assertEquals(1, result.getEntities().size());
        }
    }

    // ========================================
    // Schema Enforcement Tests
    // ========================================

    @Nested
    @DisplayName("Schema Enforcement")
    class SchemaEnforcement {

        @Test
        @DisplayName("STRICT mode filters entities not matching schema labels")
        void strictModeFiltersEntities() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            GraphSchema schema = new GraphSchema(
                    List.of(new NodeType("PERSON", "People only", null)),
                    List.of(new RelationshipType("KNOWS", "Knowing", null)),
                    null
            );

            // LLM returns entities with mixed labels (PERSON + ORGANIZATION)
            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(
                            entity("e1", "Alice", "PERSON", "A person"),
                            entity("e2", "TechCorp", "ORGANIZATION", "Not in schema")
                    ),
                    List.of(relationship("e1", "e2", "WORKS_AT", "Not in schema", 0.9))
            ));

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice works at TechCorp")),
                    schema, SchemaEnforcementMode.STRICT
            );

            // Assert — ORGANIZATION should be filtered out
            assertEquals(1, result.getEntities().size());
            assertEquals("PERSON", result.getEntities().get(0).getType());
            // WORKS_AT relationship should also be filtered (references filtered entity)
            assertEquals(0, result.getRelationships().size());
        }

        @Test
        @DisplayName("NONE mode keeps all entities regardless of schema")
        void noneModeKeepsAll() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            GraphSchema schema = new GraphSchema(
                    List.of(new NodeType("PERSON", "People only", null)),
                    List.of(),
                    null
            );

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(
                            entity("e1", "Alice", "PERSON", "A person"),
                            entity("e2", "TechCorp", "ORGANIZATION", "Not in schema")
                    ),
                    List.of()
            ));

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice at TechCorp")),
                    schema, SchemaEnforcementMode.NONE
            );

            // Assert — NONE mode keeps all
            assertEquals(2, result.getEntities().size());
        }

        @Test
        @DisplayName("Null enforcement mode defaults to NONE")
        void nullModeDefaultsToNone() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            GraphSchema schema = new GraphSchema(
                    List.of(new NodeType("PERSON", "People only", null)),
                    List.of(),
                    null
            );

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(
                            entity("e1", "Alice", "PERSON", "A person"),
                            entity("e2", "TechCorp", "ORGANIZATION", "Out of schema")
                    ),
                    List.of()
            ));

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice at TechCorp")),
                    schema, null  // null enforcement mode
            );

            // Assert — null defaults to NONE, keeps all
            assertEquals(2, result.getEntities().size());
        }
    }

    // ========================================
    // Multi-Document Processing Tests
    // ========================================

    @Nested
    @DisplayName("Multi-Document Processing")
    class MultiDocument {

        @Test
        @DisplayName("Processes multiple documents and merges results")
        void processesMultipleDocs() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            llmChat.setFixedResponse(multiDocExtractionJson(3,
                    List.of(entity("e1", "Entity", "CONCEPT", "A concept")),
                    List.of()
            ));

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(
                            doc("d1", "First document"),
                            doc("d2", "Second document"),
                            doc("d3", "Third document")
                    ),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — 3 entities (one per doc, each with doc-prefixed ID)
            assertEquals(3, result.getEntities().size());
            // Each entity has a unique ID prefixed by document ID
            Set<String> entityIds = new HashSet<>();
            result.getEntities().forEach(e -> entityIds.add(e.getId()));
            assertEquals(3, entityIds.size());
        }

        @Test
        @DisplayName("Document ID prefixes ensure unique entity IDs across documents")
        void entityIdPrefixing() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            // Same entity ID "e1" returned for both docs
            llmChat.setFixedResponse(multiDocExtractionJson(2,
                    List.of(entity("e1", "Shared Name", "CONCEPT", "desc")),
                    List.of()
            ));

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("doc-A", "text A"), doc("doc-B", "text B")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — both entities should exist with different prefixed IDs
            assertEquals(2, result.getEntities().size());
            assertTrue(result.getEntities().get(0).getId().contains("doc-A"));
            assertTrue(result.getEntities().get(1).getId().contains("doc-B"));
        }

        @Test
        @DisplayName("Source document ID stored in entity metadata")
        void sourceDocIdInMetadata() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(entity("e1", "Alice", "PERSON", "A person")),
                    List.of()
            ));

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("my-doc-123", "Alice is a person")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert
            assertEquals(1, result.getEntities().size());
            assertNotNull(result.getEntities().get(0).getMetadata());
            assertEquals("my-doc-123", result.getEntities().get(0).getMetadata().get("sourceDocumentId"));
        }
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("LLM failure for one document does not prevent others")
        void llmFailurePartialSuccess() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            // First call throws, second succeeds
            StubLLMChat errorThenSuccess = new StubLLMChat();
            errorThenSuccess.setShouldThrowException(true);
            MatrixGraphConstructor errorConstructor = new MatrixGraphConstructor(
                    graphStore, errorThenSuccess, embeddingModel, new ObjectMapper());

            // Act — should not throw
            Graph result = errorConstructor.constructGraphFromDocs(
                    List.of(doc("d1", "Will fail"), doc("d2", "Will also fail")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — empty graph, not an exception
            assertNotNull(result);
            assertTrue(result.getEntities().isEmpty());
        }

        @Test
        @DisplayName("Invalid JSON from LLM returns empty graph")
        void invalidJsonReturnsEmptyGraph() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            llmChat.setFixedResponse("This is not valid JSON at all");

            // Act
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Some text")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert
            assertNotNull(result);
            assertTrue(result.getEntities().isEmpty());
        }

        @Test
        @DisplayName("Graph save failure does not prevent result return")
        void graphSaveFailure() throws IOException {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));
            doThrow(new IOException("Save failed")).when(graphStore).saveGraph(any());

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(entity("e1", "Alice", "PERSON", "A person")),
                    List.of()
            ));

            // Act — should not throw despite save failure
            Graph result = constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — graph is still returned
            assertNotNull(result);
            assertEquals(1, result.getEntities().size());
        }
    }

    // ========================================
    // constructGraphWithId Tests
    // ========================================

    @Nested
    @DisplayName("constructGraphWithId")
    class ConstructGraphWithId {

        @Test
        @DisplayName("Uses unique graph ID, not DEFAULT_GRAPH_ID")
        void usesUniqueId() {
            // Arrange
            when(graphStore.createGraph(argThat(id -> id != null && id.startsWith("graph-")), isNull()))
                    .thenAnswer(inv -> new AdjacencyMatrixGraph(inv.getArgument(0), 100));

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(entity("e1", "Alice", "PERSON", "A person")),
                    List.of()
            ));

            // Act
            MatrixGraphConstructor.GraphConstructionResult result = constructor.constructGraphWithId(
                    List.of(doc("d1", "Alice")),
                    null, SchemaEnforcementMode.NONE, null
            );

            // Assert — graph ID should NOT be DEFAULT_GRAPH_ID
            assertNotNull(result.graphId());
            assertTrue(result.graphId().startsWith("graph-"));
            assertNotEquals(DEFAULT_GRAPH_ID, result.graphId());
            verify(graphStore, never()).loadGraph(DEFAULT_GRAPH_ID);
        }

        @Test
        @DisplayName("Includes factSheetId in graph ID when provided")
        void includesFactSheetId() {
            // Arrange
            when(graphStore.createGraph(argThat(id -> id != null && id.contains("42")), eq(42L)))
                    .thenAnswer(inv -> new AdjacencyMatrixGraph(inv.getArgument(0), 100));

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(entity("e1", "Entity", "CONCEPT", "desc")),
                    List.of()
            ));

            // Act
            MatrixGraphConstructor.GraphConstructionResult result = constructor.constructGraphWithId(
                    List.of(doc("d1", "text")),
                    null, SchemaEnforcementMode.NONE, 42L
            );

            // Assert
            assertTrue(result.graphId().contains("42"));
        }
    }

    // ========================================
    // Edge Persistence Tests
    // ========================================

    @Nested
    @DisplayName("Edge Persistence")
    class EdgePersistence {

        @Test
        @DisplayName("Relationships are stored as edges in graph store")
        void relationshipsStoredAsEdges() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            llmChat.setFixedResponse(llmExtractionJson(
                    List.of(
                            entity("e1", "Alice", "PERSON", "Employee"),
                            entity("e2", "TechCorp", "ORGANIZATION", "Company")
                    ),
                    List.of(relationship("e1", "e2", "WORKS_AT", "Employment", 0.85))
            ));

            // Act
            constructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice works at TechCorp")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert — edge added with correct type and weight
            verify(graphStore).addEdge(
                    anyString(),
                    contains("e1"),     // source (prefixed with doc ID)
                    contains("e2"),     // target (prefixed with doc ID)
                    eq(0.85),           // weight
                    eq("WORKS_AT"),     // edge type
                    eq(false)           // not bidirectional
            );
        }

        @Test
        @DisplayName("Relationship with null weight defaults to 1.0")
        void nullWeightDefaultsToOne() {
            // Arrange
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            // JSON with no weight field
            String json = "{\"entities\":[" +
                    "{\"id\":\"e1\",\"title\":\"A\",\"label\":\"CONCEPT\",\"description\":\"A\"}," +
                    "{\"id\":\"e2\",\"title\":\"B\",\"label\":\"CONCEPT\",\"description\":\"B\"}" +
                    "],\"relationships\":[" +
                    "{\"source\":\"e1\",\"target\":\"e2\",\"type\":\"LINKED\",\"description\":\"linked\"}" +
                    "]}";
            llmChat.setFixedResponse(json);

            // Act
            constructor.constructGraphFromDocs(
                    List.of(doc("d1", "A and B")),
                    null, SchemaEnforcementMode.NONE
            );

            // Assert
            verify(graphStore).addEdge(
                    anyString(), anyString(), anyString(),
                    eq(1.0),  // default weight
                    eq("LINKED"), eq(false)
            );
        }
    }

    // ========================================
    // Helpers
    // ========================================

    private static RetrievedDoc doc(String id, String text) {
        return new RetrievedDoc(id, text, new HashMap<>());
    }

    private static Map<String, Object> entity(String id, String title, String label, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("label", label);
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> relationship(String source, String target, String type,
                                                      String description, double weight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("target", target);
        m.put("type", type);
        m.put("description", description);
        m.put("weight", weight);
        return m;
    }

    private static String llmExtractionJson(List<Map<String, Object>> entities,
                                             List<Map<String, Object>> relationships) {
        StringBuilder sb = new StringBuilder("{\"entities\":[");
        for (int i = 0; i < entities.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(mapToJson(entities.get(i)));
        }
        sb.append("],\"relationships\":[");
        for (int i = 0; i < relationships.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(mapToJson(relationships.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String multiDocExtractionJson(int documentCount,
                                                 List<Map<String, Object>> entities,
                                                 List<Map<String, Object>> relationships) {
        String singleDocJson = llmExtractionJson(entities, relationships);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < documentCount; i++) {
            if (i > 0) sb.append(",");
            sb.append(singleDocJson);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (i++ > 0) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            if (entry.getValue() instanceof Number) {
                sb.append(entry.getValue());
            } else {
                sb.append("\"").append(entry.getValue()).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
