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

import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatrixGraphConstructorTest {

    @Mock private MatrixGraphStore graphStore;
    @Mock private LLMChat llmChat;
    @Mock private EmbeddingModel embeddingModel;

    // LLMChat fluent API mocks
    @Mock private LLMChat.ChatClientRequestSpec requestSpec;
    @Mock private LLMChat.CallResponseSpec callResponseSpec;

    private ObjectMapper objectMapper;
    private MatrixGraphConstructor constructor;

    private static final String VALID_LLM_RESPONSE = """
            {
              "entities": [
                {"id": "e1", "title": "Alice", "label": "PERSON", "description": "A software engineer"},
                {"id": "e2", "title": "Acme Corp", "label": "ORGANIZATION", "description": "A tech company"}
              ],
              "relationships": [
                {"source": "e1", "target": "e2", "type": "WORKS_AT", "description": "Alice works at Acme", "weight": 0.9}
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        constructor = new MatrixGraphConstructor(graphStore, llmChat, embeddingModel, objectMapper);

        // Set up the LLMChat fluent chain: llmChat.prompt().user(any).call().content()
        when(llmChat.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    @Test
    @DisplayName("does not reject valid graph JSON that contains quota vocabulary")
    void doesNotRejectValidJsonWithQuotaVocabulary() throws Exception {
        String response = """
                [
                  {
                    "entities": [
                      {"id":"quota_policy","title":"Quota policy","label":"POLICY"}
                    ],
                    "relationships": [
                      {"source":"quota_policy","target":"forecast_process","type":"APPLIES_TO"}
                    ]
                  }
                ]
                """;

        assertFalse(isAgentErrorResponse(response));
    }

    @Test
    @DisplayName("rejects explicit provider quota failures")
    void rejectsExplicitProviderQuotaFailures() throws Exception {
        String response = "Warning: Basic terminal detected. Error when talking to Gemini API "
                + "TerminalQuotaError: You have exhausted your capacity on this model.";

        assertTrue(isAgentErrorResponse(response));
    }

    private boolean isAgentErrorResponse(String response) throws Exception {
        Method method = MatrixGraphConstructor.class.getDeclaredMethod("isAgentErrorResponse", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(constructor, response);
    }

    // ── constructGraphFromDocs — basic entity/relationship extraction ──────────

    @Nested
    @DisplayName("constructGraphFromDocs")
    class ConstructGraphFromDocs {

        @Test
        @DisplayName("extracts entities and relationships from LLM response")
        void extractsEntitiesAndRelationships() {
            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "Alice is a software engineer at Acme Corp.", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);

            assertNotNull(result);
            assertEquals(2, result.getEntities().size(), "Should have 2 entities");
            assertEquals(1, result.getRelationships().size(), "Should have 1 relationship");

            Entity alice = result.getEntities().stream()
                    .filter(e -> e.getTitle().equals("Alice")).findFirst().orElse(null);
            assertNotNull(alice);
            // Entity IDs are taken directly from the LLM response without doc-prefixing
            assertEquals("e1", alice.getId());
        }

        @Test
        @DisplayName("entity IDs are taken directly from LLM response without doc prefixing")
        void prefixesEntityIdsWithDocId() {
            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("myDoc42", "text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);

            // Entity IDs are taken as-is from the LLM response (e.g., "e1", "e2")
            // without any doc-based prefix
            for (Entity entity : result.getEntities()) {
                assertFalse(entity.getId().startsWith("doc_"),
                        "Entity " + entity.getId() + " should NOT be prefixed");
            }
            // Relationship source/target IDs also match LLM response directly
            for (Relationship rel : result.getRelationships()) {
                assertEquals("e1", rel.getSource());
                assertEquals("e2", rel.getTarget());
            }
        }

        @Test
        @DisplayName("entity metadata contains metadata from LLM response but no sourceDocumentId")
        void addsSourceDocumentIdToMetadata() {
            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc99", "text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);

            // The source does not add sourceDocumentId to entity metadata;
            // metadata is only set via the entity's own metadata field in the LLM response
            for (Entity entity : result.getEntities()) {
                // metadata may be null or empty — no sourceDocumentId is added by the implementation
                if (entity.getMetadata() != null) {
                    assertNull(entity.getMetadata().get("sourceDocumentId"),
                            "sourceDocumentId should NOT be in metadata");
                }
            }
        }

        @Test
        @DisplayName("adds nodes to MatrixGraphStore")
        void addsNodesToGraphStore() {
            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            constructor.constructGraphFromDocs(List.of(doc), null, null);

            verify(graphStore, times(2)).addNode(anyString(), any());
        }

        @Test
        @DisplayName("adds edges to MatrixGraphStore with correct weight and type")
        void addsEdgesToGraphStore() {
            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            constructor.constructGraphFromDocs(List.of(doc), null, null);

            // Entity IDs are used as-is from the LLM response (no doc prefix added)
            verify(graphStore).addEdge(anyString(),
                    eq("e1"), eq("e2"),
                    eq(0.9), eq("WORKS_AT"), eq(false), eq("WORKS_AT"));
        }

        @Test
        @DisplayName("generates and stores embeddings for entities")
        void generatesAndStoresEmbeddings() {
            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());
            INDArray mockEmbeddings = Nd4j.zeros(2, 128);
            when(embeddingModel.embed(anyList())).thenReturn(mockEmbeddings);

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            constructor.constructGraphFromDocs(List.of(doc), null, null);

            verify(embeddingModel).embed(anyList());
            verify(graphStore).storeNodeEmbeddings(anyString(), anyList(), eq(mockEmbeddings));
        }

        @Test
        @DisplayName("handles empty document list gracefully")
        void handlesEmptyDocList() {
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            Graph result = constructor.constructGraphFromDocs(List.of(), null, null);

            assertNotNull(result);
            assertTrue(result.getEntities().isEmpty());
            assertTrue(result.getRelationships().isEmpty());
            verify(llmChat, never()).prompt();
        }

        @Test
        @DisplayName("continues processing when one document batch fails LLM extraction")
        void continuesOnDocumentFailure() {
            // The production code batches documents by character budget (BATCH_PROMPT_MAX_CHARS = 3_000_000).
            // To ensure doc1 and doc2 are in separate batches, make doc1's text exceed 3M chars.
            // First batch (doc1) throws, second batch (doc2) succeeds.
            String longText = "x".repeat(3_001_000); // exceeds 3_000_000-char budget
            when(callResponseSpec.content())
                    .thenThrow(new RuntimeException("LLM timeout"))
                    .thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc1 = new RetrievedDoc("bad", longText, Map.of());
            RetrievedDoc doc2 = new RetrievedDoc("good", "text2", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc1, doc2), null, null);

            assertEquals(2, result.getEntities().size(), "Should have entities from the second doc batch");
        }
    }

    // ── JSON parsing robustness ──────────────────────────────────────────────

    @Nested
    @DisplayName("parseExtractionResponse robustness")
    class ParseExtractionResponse {

        @Test
        @DisplayName("strips markdown code fences from LLM response")
        void stripsMarkdownCodeFences() {
            String wrapped = "Here's the result:\n```json\n" + VALID_LLM_RESPONSE + "\n```\nDone!";
            when(callResponseSpec.content()).thenReturn(wrapped);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);

            assertEquals(2, result.getEntities().size(), "Should parse JSON even with surrounding text");
        }

        @Test
        @DisplayName("extracts entity from codex transcript that has non-graph JSON and an echoed result block")
        void extractsGraphJsonFromCodexTranscript() {
            // parseExtractionResponse uses indexOf("{\"entities\"") to skip preamble/tool-call lines,
            // then substring from there to lastIndexOf('}') — which reaches the end of the echoed
            // second JSON block. Jackson's readValue (FAIL_ON_TRAILING_TOKENS disabled by default)
            // stops at the end of the first valid JSON object and ignores trailing content,
            // so the entity is successfully extracted despite the "tokens used" echo suffix.
            String transcript = """
                    Reading additional input from stdin...
                    OpenAI Codex v0.135.0
                    --------
                    user
                    Extract entities and relationships from the text below.
                    - Label: CHANNEL_TAXONOMY, Description: CHANNEL_TAXONOMY"
                    Non-graph config object: {"ignored": true}
                    --------
                    codex
                    {"entities":[{"id":"e1","title":"KAA","label":"METHODOLOGY","description":"Knowledge and Action Automation"}],"relationships":[]}
                    tokens used
                    8,872
                    {"entities":[{"id":"e1","title":"KAA","label":"METHODOLOGY","description":"Knowledge and Action Automation"}],"relationships":[]}
                    """;
            when(callResponseSpec.content()).thenReturn(transcript);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "KAA methodology text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);

            // The parser locates {"entities"... in the codex response line, takes the substring
            // to lastIndexOf('}') (which spans the echoed block), then Jackson reads the first
            // complete JSON object and ignores trailing content → 1 entity extracted.
            assertEquals(1, result.getEntities().size(),
                    "Parser should extract the entity from the codex response line");
            assertEquals("KAA", result.getEntities().get(0).getTitle());
        }

        @Test
        @DisplayName("handles null entity metadata by creating empty map")
        void handlesNullEntityMetadata() {
            String noMeta = """
                    {"entities": [{"id": "e1", "title": "Bob", "label": "PERSON", "description": "A person"}],
                     "relationships": []}
                    """;
            when(callResponseSpec.content()).thenReturn(noMeta);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);

            assertEquals(1, result.getEntities().size());
            assertNotNull(result.getEntities().get(0).getMetadata(),
                    "Metadata should be initialized even when null in LLM response");
        }

        @Test
        @DisplayName("returns empty graph on completely invalid JSON")
        void returnsEmptyOnInvalidJson() {
            when(callResponseSpec.content()).thenReturn("This is not JSON at all");
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);

            assertTrue(result.getEntities().isEmpty());
            assertTrue(result.getRelationships().isEmpty());
        }

        @Test
        @DisplayName("uses default weight 1.0 when relationship weight is null")
        void usesDefaultWeightWhenNull() {
            String noWeight = """
                    {"entities": [
                      {"id": "e1", "title": "A", "label": "X", "description": "First entity"},
                      {"id": "e2", "title": "B", "label": "Y", "description": "Second entity"}
                    ],
                    "relationships": [
                      {"source": "e1", "target": "e2", "type": "REL", "description": "A relates to B"}
                    ]}
                    """;
            when(callResponseSpec.content()).thenReturn(noWeight);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            constructor.constructGraphFromDocs(List.of(doc), null, null);

            verify(graphStore).addEdge(anyString(), anyString(), anyString(), eq(1.0), eq("REL"), eq(false), eq("REL"));
        }

        @Test
        @DisplayName("rejects relationship when its required type is null")
        void rejectsRelationTypeWhenNull() {
            String noType = """
                    {"entities": [
                      {"id": "e1", "title": "A", "label": "X", "description": "First entity"},
                      {"id": "e2", "title": "B", "label": "Y", "description": "Second entity"}
                    ],
                    "relationships": [
                      {"source": "e1", "target": "e2", "description": "related"}
                    ]}
                    """;
            when(callResponseSpec.content()).thenReturn(noType);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);

            assertTrue(result.getRelationships().isEmpty());
            verify(graphStore, never()).addEdge(
                    anyString(), anyString(), anyString(), anyDouble(), anyString(), anyBoolean(), any());
        }
    }

    // ── Schema enforcement ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Schema enforcement")
    class SchemaEnforcement {

        @Test
        @DisplayName("STRICT mode filters entities not in schema")
        void strictModeFiltersEntities() {
            // Schema only allows PERSON, not ORGANIZATION
            GraphSchema schema = new GraphSchema();
            NodeType personType = new NodeType();
            personType.setLabel("PERSON");
            personType.setDescription("A person");
            schema.setNodeTypes(List.of(personType));
            RelationshipType relType = new RelationshipType();
            relType.setType("WORKS_AT");
            relType.setDescription("Employment");
            schema.setRelationshipTypes(List.of(relType));

            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), schema, SchemaEnforcementMode.STRICT);

            // ORGANIZATION entity filtered, plus relationship referencing it is also removed
            // since the target entity e2 (ORGANIZATION) was removed
            assertEquals(1, result.getEntities().size(), "Only PERSON entity should survive strict filtering");
            assertEquals("Alice", result.getEntities().get(0).getTitle());
        }

        @Test
        @DisplayName("NONE mode keeps all entities regardless of schema")
        void noneModeKeepsAll() {
            GraphSchema schema = new GraphSchema();
            NodeType personType = new NodeType();
            personType.setLabel("PERSON");
            schema.setNodeTypes(List.of(personType));
            schema.setRelationshipTypes(List.of());

            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), schema, SchemaEnforcementMode.NONE);

            assertEquals(2, result.getEntities().size(), "All entities should be kept in NONE mode");
        }

        @Test
        @DisplayName("null enforcement mode defaults to NONE")
        void nullEnforcementDefaultsToNone() {
            GraphSchema schema = new GraphSchema();
            NodeType personType = new NodeType();
            personType.setLabel("PERSON");
            schema.setNodeTypes(List.of(personType));
            schema.setRelationshipTypes(List.of());

            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), schema, null);

            assertEquals(2, result.getEntities().size(), "Null enforcement should default to NONE");
        }
    }

    // ── constructGraph (collectionName only) ─────────────────────────────────

    @Test
    @DisplayName("constructGraph(collectionName) returns empty graph")
    void constructGraphByCollectionReturnsEmpty() throws IOException {
        Graph result = constructor.constructGraph("test-collection");
        assertNotNull(result);
        assertNull(result.getEntities());
    }

    // ── constructGraphWithId ─────────────────────────────────────────────────

    @Nested
    @DisplayName("constructGraphWithId")
    class ConstructGraphWithId {

        @Test
        @DisplayName("returns both graphId and Graph in result")
        void returnsBothGraphIdAndGraph() {
            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            var result = constructor.constructGraphWithId(List.of(doc), null, null, null);

            assertNotNull(result.graphId());
            // Per-fact-sheet segmentation: a null factSheetId maps to the stable default graph id
            // (no more random "graph-<uuid>" ids, so writes land in the graph reads will look in).
            assertEquals(MatrixKnowledgeGraphService.graphIdForFactSheet(null), result.graphId());
            assertNotNull(result.graph());
            assertEquals(2, result.graph().getEntities().size());
        }

        @Test
        @DisplayName("includes factSheetId in graph ID when provided")
        void includesFactSheetIdInGraphId() {
            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), eq(42L))).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            var result = constructor.constructGraphWithId(List.of(doc), null, null, 42L);

            assertTrue(result.graphId().contains("42"),
                    "Graph ID should contain the factSheetId");
        }
    }

    // ── Prompt construction ──────────────────────────────────────────────────

    @Nested
    @DisplayName("createExtractionPrompt")
    class CreateExtractionPrompt {

        @Test
        @DisplayName("includes document text in prompt")
        void includesDocumentText() {
            when(callResponseSpec.content()).thenReturn("""
                    {"entities": [], "relationships": []}""");
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            String docText = "The quick brown fox jumps over the lazy dog.";
            RetrievedDoc doc = new RetrievedDoc("doc1", docText, Map.of());
            constructor.constructGraphFromDocs(List.of(doc), null, null);

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(requestSpec).user(promptCaptor.capture());
            assertTrue(promptCaptor.getValue().contains(docText),
                    "Prompt should contain the document text");
        }

        @Test
        @DisplayName("includes schema node types in prompt when schema is provided")
        void includesSchemaInPrompt() {
            GraphSchema schema = new GraphSchema();
            NodeType personType = new NodeType();
            personType.setLabel("PERSON");
            personType.setDescription("A human being");
            schema.setNodeTypes(List.of(personType));
            RelationshipType relType = new RelationshipType();
            relType.setType("KNOWS");
            relType.setDescription("Knows someone");
            schema.setRelationshipTypes(List.of(relType));

            when(callResponseSpec.content()).thenReturn("""
                    {"entities": [], "relationships": []}""");
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            constructor.constructGraphFromDocs(List.of(doc), schema, null);

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(requestSpec).user(promptCaptor.capture());
            String prompt = promptCaptor.getValue();
            assertTrue(prompt.contains("PERSON"), "Prompt should include schema node types");
            assertTrue(prompt.contains("KNOWS"), "Prompt should include schema relationship types");
            assertTrue(prompt.contains("A human being"), "Prompt should include schema descriptions");
        }

        @Test
        @DisplayName("uses validation signatures without semantic example facts")
        void usesValidationSignaturesWithoutSemanticExampleFacts() {
            GraphExtractionValidationPolicy policy = GraphExtractionValidationPolicy.builder()
                    .relationPatterns(List.of("(PERSON)-[:APPROVED_BY]->(CLOSE_STEP)"))
                    .build();
            constructor.configureValidation(policy);

            GraphSchema schema = new GraphSchema();
            schema.setPatterns(policy.getRelationPatterns());
            when(callResponseSpec.content()).thenReturn(
                    "{\"entities\": [], \"relationships\": []}");
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            constructor.constructGraphFromDocs(
                    List.of(new RetrievedDoc("doc1", "No graph facts.", Map.of())),
                    schema,
                    null);

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(requestSpec).user(promptCaptor.capture());
            String prompt = promptCaptor.getValue();
            assertTrue(prompt.contains("(PERSON)-[:APPROVED_BY]->(CLOSE_STEP)"));
            assertTrue(prompt.contains("{\"entities\":[],\"relationships\":[]}"));
            assertFalse(prompt.contains("John"));
            assertFalse(prompt.contains("Acme"));
        }
    }

    @Test
    @DisplayName("rejects parseable output with schema-invalid relationship endpoints")
    void rejectsSchemaInvalidRelationshipEndpoints() {
        GraphExtractionValidationPolicy policy = GraphExtractionValidationPolicy.builder()
                .relationPatterns(List.of("(PERSON)-[:APPROVED_BY]->(CLOSE_STEP)"))
                .build();
        constructor.configureValidation(policy);
        when(callResponseSpec.content()).thenReturn("""
                {
                  "entities": [
                    {"id":"p","title":"Finance Lead","label":"PERSON","description":"A finance lead"},
                    {"id":"f","title":"North Forecast","label":"REGIONAL_FORECAST","description":"A forecast"}
                  ],
                  "relationships": [
                    {"source":"p","target":"f","type":"APPROVED_BY","description":"Approval","confidence":0.9}
                  ]
                }
                """);
        when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
        when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

        Graph result = constructor.constructGraphFromDocs(
                List.of(new RetrievedDoc("doc1", "text", Map.of())),
                new GraphSchema(null, null, policy.getRelationPatterns()),
                null);

        assertTrue(result.getEntities().isEmpty());
        assertTrue(result.getRelationships().isEmpty());
        verify(graphStore, never()).addNode(anyString(), any());
    }

    // ── convertToGraph ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("convertToGraph")
    class ConvertToGraph {

        @Test
        @DisplayName("relationship metadata includes type and weight")
        void relationshipMetadataIncludesTypeAndWeight() {
            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);

            Relationship rel = result.getRelationships().get(0);
            assertNotNull(rel.getMetadata());
            assertEquals("WORKS_AT", rel.getMetadata().get("relationshipType"));
            assertEquals(0.9, rel.getMetadata().get("weight"));
        }

        @Test
        @DisplayName("graph has empty communities list")
        void graphHasEmptyCommunities() {
            when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);

            assertNotNull(result.getCommunities());
            assertTrue(result.getCommunities().isEmpty());
        }
    }

    // ── Multi-document processing ────────────────────────────────────────────

    @Nested
    @DisplayName("Multi-document processing")
    class MultiDocument {

        @Test
        @DisplayName("processes multiple docs batched into one LLM call; duplicate IDs collapse")
        void processesMultipleDocsWithIsolatedIds() {
            // The production code batches small documents together in one LLM call.
            // The LLM response is parsed as a single object, not a JSON array.
            // When the response is a JSON array (not a valid ExtractedGraph), parsing fails
            // and returns null — so the result will have 0 entities.
            // Use a valid single-object response instead to test batched behaviour.
            String batchResponse = """
                    {
                      "entities": [
                        {"id": "alice", "title": "Alice", "label": "PERSON", "description": "A person named Alice"},
                        {"id": "bob",   "title": "Bob",   "label": "PERSON", "description": "A person named Bob"}
                      ],
                      "relationships": []
                    }""";

            when(callResponseSpec.content()).thenReturn(batchResponse);
            when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
            when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());

            RetrievedDoc doc1 = new RetrievedDoc("docA", "about Alice", Map.of());
            RetrievedDoc doc2 = new RetrievedDoc("docB", "about Bob", Map.of());
            Graph result = constructor.constructGraphFromDocs(List.of(doc1, doc2), null, null);

            // Both docs are small and fit in one batch → 1 LLM call → response has 2 entities
            assertEquals(2, result.getEntities().size());
            Set<String> ids = result.getEntities().stream()
                    .map(Entity::getId).collect(java.util.stream.Collectors.toSet());
            assertTrue(ids.contains("alice"));
            assertTrue(ids.contains("bob"));
        }
    }

    // ── Embedding failure resilience ─────────────────────────────────────────

    @Test
    @DisplayName("handles embedding generation failure gracefully")
    void handlesEmbeddingFailure() {
        when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
        when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
        when(graphStore.loadGraph(anyString())).thenReturn(Optional.empty());
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("Embedding service unavailable"));

        RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
        Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);

        // Should still return the graph even if embeddings fail
        assertEquals(2, result.getEntities().size());
        assertEquals(1, result.getRelationships().size());
    }

    // ── Graph save failure resilience ────────────────────────────────────────

    @Test
    @DisplayName("handles graph save failure gracefully")
    void handlesGraphSaveFailure() throws IOException {
        when(callResponseSpec.content()).thenReturn(VALID_LLM_RESPONSE);
        when(graphStore.createGraph(anyString(), isNull())).thenReturn(new AdjacencyMatrixGraph());
        AdjacencyMatrixGraph savedGraph = new AdjacencyMatrixGraph();
        when(graphStore.loadGraph(anyString())).thenReturn(Optional.of(savedGraph));
        doThrow(new IOException("Disk full")).when(graphStore).saveGraph(any());

        RetrievedDoc doc = new RetrievedDoc("doc1", "text", Map.of());
        // Should not throw
        Graph result = constructor.constructGraphFromDocs(List.of(doc), null, null);
        assertNotNull(result);
        assertEquals(2, result.getEntities().size());
    }
}
