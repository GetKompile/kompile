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
package ai.kompile.knowledgegraph.matrix.integration;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.service.MatrixGraphConstructor;
import ai.kompile.knowledgegraph.matrix.service.MatrixGraphRagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that exercises the full graph extraction → persist → RAG query flow.
 * <p>
 * Uses real {@link InMemoryMatrixGraphStore} (with actual cosine similarity),
 * deterministic {@link TestEmbeddingModel}, and configurable {@link TestLLMChat}.
 * No mocks — verifies the actual data path from document ingestion through graph
 * construction to RAG query answering.
 * </p>
 */
@DisplayName("Graph Extraction → RAG Integration Tests")
class GraphExtractionToRagIntegrationTest {

    private InMemoryMatrixGraphStore graphStore;
    private TestEmbeddingModel embeddingModel;
    private TestLLMChat llmChat;

    private MatrixGraphConstructor graphConstructor;
    private MatrixGraphRagService ragService;

    @BeforeEach
    void setUp() {
        graphStore = new InMemoryMatrixGraphStore();
        embeddingModel = new TestEmbeddingModel();
        llmChat = new TestLLMChat();

        graphConstructor = new MatrixGraphConstructor(graphStore, llmChat, embeddingModel, new ObjectMapper());
        ragService = new MatrixGraphRagService(graphStore, embeddingModel, llmChat);
    }

    @AfterEach
    void tearDown() {
        graphStore.clearAll();
    }

    // ========================================
    // Full Pipeline: Extract → Store → Query
    // ========================================

    @Nested
    @DisplayName("Full Pipeline Tests")
    class FullPipelineTests {

        @Test
        @DisplayName("Extracted entities are queryable via RAG service")
        void extractedEntitiesAreQueryable() {
            // Configure LLM to return extraction JSON, then answer RAG queries
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("Extract entities")) {
                    return extractionJson(
                            List.of(
                                    entityJson("e1", "Kompile", "TECHNOLOGY", "An AI/ML platform"),
                                    entityJson("e2", "Spring Boot", "TECHNOLOGY", "Java web framework")
                            ),
                            List.of(
                                    relJson("e1", "e2", "USES", "Kompile uses Spring Boot")
                            )
                    );
                }
                return "Based on the knowledge graph: Kompile is an AI/ML platform that uses Spring Boot.";
            });

            // Step 1: Extract and persist
            Graph graph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("d1", "Kompile is an AI/ML platform built with Spring Boot.")),
                    null, SchemaEnforcementMode.NONE
            );

            assertNotNull(graph);
            assertEquals(2, graph.getEntities().size());
            assertEquals(1, graph.getRelationships().size());

            // Step 2: Verify entities are stored
            List<String> graphIds = graphStore.listGraphs();
            assertFalse(graphIds.isEmpty());

            Optional<AdjacencyMatrixGraph> storedGraph = graphStore.loadGraph(graph.getId());
            assertTrue(storedGraph.isPresent());
            assertTrue(storedGraph.get().getNodeCount() >= 2);

            // Step 3: Query via RAG
            GraphRagResult result = ragService.answerQuery(GraphRagQuery.builder()
                    .query("What is Kompile?")
                    .graph(graph)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build());

            assertNotNull(result);
            assertNotNull(result.getAnswer());
            assertFalse(result.getAnswer().contains("don't have any knowledge graph data"));
            assertNotNull(result.getFormattedContext());
        }

        @Test
        @DisplayName("Separate extraction calls produce queryable isolated graphs")
        void incrementalExtractionAccumulates() {
            // First ingestion: technology entities
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("Extract entities") && prompt.contains("Kubernetes")) {
                    return extractionJson(
                            List.of(entityJson("e1", "Kubernetes", "TECHNOLOGY", "Container orchestrator")),
                            List.of()
                    );
                }
                if (prompt.contains("Extract entities") && prompt.contains("Docker")) {
                    return extractionJson(
                            List.of(entityJson("e2", "Docker", "TECHNOLOGY", "Container runtime")),
                            List.of()
                    );
                }
                return "Answer based on graph context";
            });

            // First ingestion
            Graph kubernetesGraph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("d1", "Kubernetes is a container orchestrator.")),
                    null, SchemaEnforcementMode.NONE
            );

            // Second ingestion — separate graph scope
            Graph dockerGraph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("d2", "Docker is a container runtime.")),
                    null, SchemaEnforcementMode.NONE
            );

            // Verify both scoped graphs exist independently
            assertTrue(graphStore.loadGraph(kubernetesGraph.getId()).isPresent());
            assertTrue(graphStore.loadGraph(dockerGraph.getId()).isPresent());
            assertNotEquals(kubernetesGraph.getId(), dockerGraph.getId());

            // RAG query should find entities when scoped to the intended graph
            GraphRagResult result = ragService.answerQuery(GraphRagQuery.builder()
                    .query("What is Docker?")
                    .graph(dockerGraph)
                    .searchType(SearchType.LOCAL)
                    .k(10)
                    .build());

            assertNotNull(result);
            assertNotNull(result.getAnswer());
        }

        @Test
        @DisplayName("Local search returns answers with extracted entities")
        void localSearchWithExtractedEntities() {
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("Extract entities")) {
                    return extractionJson(
                            List.of(
                                    entityJson("e1", "Alice", "PERSON", "CEO"),
                                    entityJson("e2", "TechCorp", "ORGANIZATION", "Tech company"),
                                    entityJson("e3", "Berlin", "LOCATION", "City")
                            ),
                            List.of(
                                    relJson("e1", "e2", "LEADS", "Alice leads TechCorp"),
                                    relJson("e2", "e3", "LOCATED_IN", "TechCorp in Berlin")
                            )
                    );
                }
                return "Overview: TechCorp is led by Alice and located in Berlin.";
            });

            Graph graph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice is the CEO of TechCorp based in Berlin.")),
                    null, SchemaEnforcementMode.NONE
            );

            GraphRagResult result = ragService.answerQuery(GraphRagQuery.builder()
                    .query("Tell me about Alice and TechCorp")
                    .graph(graph)
                    .searchType(SearchType.LOCAL)
                    .k(10)
                    .build());

            assertNotNull(result);
            assertNotNull(result.getAnswer());
            assertFalse(result.getAnswer().contains("don't have any knowledge graph data"),
                    "Should find extracted entities via local search");
        }
    }

    // ========================================
    // Entity Type Preservation Through Pipeline
    // ========================================

    @Nested
    @DisplayName("Type Preservation Through Pipeline")
    class TypePreservation {

        @Test
        @DisplayName("Entity types survive extraction → storage → query context")
        void entityTypesSurvivePipeline() {
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("Extract entities")) {
                    return extractionJson(
                            List.of(
                                    entityJson("e1", "Alice", "PERSON", "Engineer"),
                                    entityJson("e2", "Python", "TECHNOLOGY", "Programming language")
                            ),
                            List.of()
                    );
                }
                return "Context-based answer";
            });

            Graph extractedGraph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice writes Python code.")),
                    null, SchemaEnforcementMode.NONE
            );

            // Verify types in stored nodes
            Optional<AdjacencyMatrixGraph> storedGraph = graphStore.loadGraph(extractedGraph.getId());
            assertTrue(storedGraph.isPresent());

            AdjacencyMatrixGraph graph = storedGraph.get();
            boolean foundPerson = false;
            boolean foundTech = false;
            for (MatrixGraphNode node : graphStore.getAllNodes(extractedGraph.getId())) {
                if ("Alice".equals(node.getTitle())) {
                    assertEquals("PERSON", node.getNodeType());
                    foundPerson = true;
                }
                if ("Python".equals(node.getTitle())) {
                    assertEquals("TECHNOLOGY", node.getNodeType());
                    foundTech = true;
                }
            }
            assertTrue(foundPerson, "PERSON node should be stored");
            assertTrue(foundTech, "TECHNOLOGY node should be stored");
        }

        @Test
        @DisplayName("Relationship types survive extraction → edge storage")
        void relationshipTypesSurvivePipeline() {
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("Extract entities")) {
                    return extractionJson(
                            List.of(
                                    entityJson("e1", "Alice", "PERSON", "Developer"),
                                    entityJson("e2", "TechCorp", "ORGANIZATION", "Company")
                            ),
                            List.of(relJson("e1", "e2", "EMPLOYED_BY", "Alice is employed by TechCorp"))
                    );
                }
                return "Answer";
            });

            Graph graph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("d1", "Alice is employed by TechCorp.")),
                    null, SchemaEnforcementMode.NONE
            );

            // Verify relationship type in core Graph model
            assertEquals(1, graph.getRelationships().size());
            assertEquals("EMPLOYED_BY", graph.getRelationships().get(0).getType());

            // Verify edge exists in graph store (use getEdgeTypes which doesn't trigger ND4J matrix ops)
            AdjacencyMatrixGraph storedGraph = graphStore.loadGraph(graph.getId()).orElseThrow();
            assertFalse(storedGraph.getEdgeTypes().isEmpty(), "Edges should be stored in graph");
        }
    }

    // ========================================
    // No LLM / No Embedding Degraded Mode
    // ========================================

    @Nested
    @DisplayName("Degraded Mode Tests")
    class DegradedMode {

        @Test
        @DisplayName("RAG service works without LLM (context-only mode)")
        void ragWithoutLlm() {
            // First, construct graph normally
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("Extract entities")) {
                    return extractionJson(
                            List.of(entityJson("e1", "TestEntity", "CONCEPT", "A test entity")),
                            List.of()
                    );
                }
                return "answer";
            });

            Graph graph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("d1", "TestEntity is an important concept.")),
                    null, SchemaEnforcementMode.NONE
            );

            // Now query without LLM
            MatrixGraphRagService ragWithoutLlm = new MatrixGraphRagService(graphStore, embeddingModel, null);

            GraphRagResult result = ragWithoutLlm.answerQuery(GraphRagQuery.builder()
                    .query("What is TestEntity?")
                    .graph(graph)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build());

            assertNotNull(result);
            assertTrue(result.getAnswer().contains("No LLM configured"));
        }

        @Test
        @DisplayName("RAG service works without embedding model (text search)")
        void ragWithoutEmbedding() {
            // First, construct graph normally
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("Extract entities")) {
                    return extractionJson(
                            List.of(entityJson("e1", "SearchableEntity", "CONCEPT", "A searchable entity")),
                            List.of()
                    );
                }
                return "Text search based answer";
            });

            Graph graph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("d1", "SearchableEntity is an important concept.")),
                    null, SchemaEnforcementMode.NONE
            );

            // Now query without embedding model — falls back to text search
            MatrixGraphRagService ragWithoutEmbed = new MatrixGraphRagService(graphStore, null, llmChat);

            GraphRagResult result = ragWithoutEmbed.answerQuery(GraphRagQuery.builder()
                    .query("SearchableEntity")
                    .graph(graph)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build());

            assertNotNull(result);
            assertNotNull(result.getAnswer());
        }
    }

    // ========================================
    // Edge Cases
    // ========================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty extraction result does not break RAG query")
        void emptyExtractionDoesNotBreakRag() {
            // LLM returns empty entities
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("Extract entities")) {
                    return "{\"entities\":[],\"relationships\":[]}";
                }
                return "No data available";
            });

            Graph graph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("d1", "Minimal text")),
                    null, SchemaEnforcementMode.NONE
            );

            assertNotNull(graph);
            assertTrue(graph.getEntities().isEmpty());

            // RAG query should still work (empty context)
            GraphRagResult result = ragService.answerQuery(GraphRagQuery.builder()
                    .query("What is there?")
                    .graph(graph)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build());

            assertNotNull(result);
        }

        @Test
        @DisplayName("LLM extraction failure does not prevent RAG from working on existing data")
        void extractionFailureDoesNotBreakExistingData() {
            // First: successful extraction
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("Extract entities") && prompt.contains("Existing")) {
                    return extractionJson(
                            List.of(entityJson("e1", "ExistingEntity", "CONCEPT", "Already in graph")),
                            List.of()
                    );
                }
                if (prompt.contains("Extract entities")) {
                    throw new RuntimeException("LLM failure");
                }
                return "Answer about existing entity";
            });

            // First ingestion succeeds
            Graph existingGraph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("d1", "ExistingEntity is important.")),
                    null, SchemaEnforcementMode.NONE
            );

            // Second ingestion fails
            Graph failedGraph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("d2", "This will fail.")),
                    null, SchemaEnforcementMode.NONE
            );
            // Failed extraction returns empty graph
            assertTrue(failedGraph.getEntities().isEmpty());

            // RAG query should still find the existing entity
            GraphRagResult result = ragService.answerQuery(GraphRagQuery.builder()
                    .query("ExistingEntity")
                    .graph(existingGraph)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build());

            assertNotNull(result);
            assertFalse(result.getAnswer().contains("don't have any knowledge graph data"),
                    "Should find existing data despite failed second extraction");
        }

        @Test
        @DisplayName("Multiple documents produce unique entity IDs")
        void multipleDocsProduceUniqueIds() {
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("Extract entities")) {
                    // Both docs return entity with same base ID "e1"
                    return extractionJsonArray(2,
                            List.of(entityJson("e1", "Entity", "CONCEPT", "A concept")),
                            List.of()
                    );
                }
                return "Answer";
            });

            Graph graph = graphConstructor.constructGraphFromDocs(
                    List.of(doc("doc-A", "First document"), doc("doc-B", "Second document")),
                    null, SchemaEnforcementMode.NONE
            );

            // Both entities should exist with different IDs
            assertEquals(2, graph.getEntities().size());
            assertNotEquals(
                    graph.getEntities().get(0).getId(),
                    graph.getEntities().get(1).getId(),
                    "Entity IDs should be prefixed with document ID to ensure uniqueness"
            );
        }
    }

    // ========================================
    // Helpers
    // ========================================

    private static RetrievedDoc doc(String id, String text) {
        return new RetrievedDoc(id, text, new HashMap<>());
    }

    private static Map<String, Object> entityJson(String id, String title, String label, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("label", label);
        m.put("description", desc);
        return m;
    }

    private static Map<String, Object> relJson(String source, String target, String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("target", target);
        m.put("type", type);
        m.put("description", desc);
        m.put("weight", 1.0);
        return m;
    }

    private static String extractionJson(List<Map<String, Object>> entities,
                                          List<Map<String, Object>> relationships) {
        StringBuilder sb = new StringBuilder("{\"entities\":[");
        for (int i = 0; i < entities.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(toJson(entities.get(i)));
        }
        sb.append("],\"relationships\":[");
        for (int i = 0; i < relationships.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(toJson(relationships.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String extractionJsonArray(int documentCount,
                                              List<Map<String, Object>> entities,
                                              List<Map<String, Object>> relationships) {
        String singleDocJson = extractionJson(entities, relationships);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < documentCount; i++) {
            if (i > 0) sb.append(",");
            sb.append(singleDocJson);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String toJson(Map<String, Object> map) {
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
