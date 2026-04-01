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

package ai.kompile.graph.neo4j;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.graph.neo4j.integration.TestLLMChat;
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Neo4jGraphConstructorTest {

    @Mock
    private Driver neo4jDriver;

    @Mock
    private Session neo4jSession;

    @Mock
    private Result neo4jResult;

    private TestLLMChat llmChat;
    private ObjectMapper objectMapper;
    private EntityResolutionService entityResolutionService;
    private Neo4jGraphConstructor constructor;

    @BeforeEach
    void setUp() {
        llmChat = new TestLLMChat();
        objectMapper = new ObjectMapper();
        entityResolutionService = new EntityResolutionService();

        // Simple text chunker that returns the doc as-is
        TextChunker noOpChunker = new TextChunker() {
            @Override
            public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
                return List.of(document);
            }
            @Override
            public String getName() { return "no-op"; }
            @Override
            public List<String> getSupportedLanguages() { return List.of("*"); }
        };

        constructor = new Neo4jGraphConstructor(
                neo4jDriver, llmChat, objectMapper, noOpChunker, entityResolutionService);

        // Default mock: neo4j session returns empty result
        lenient().when(neo4jDriver.session()).thenReturn(neo4jSession);
        lenient().when(neo4jSession.run(anyString(), any(org.neo4j.driver.Value.class))).thenReturn(neo4jResult);
    }

    @Nested
    @DisplayName("Standard Format Extraction")
    class StandardFormatTests {

        @Test
        void testExtractsEntitiesInStandardFormat() {
            String standardResponse = """
                    {
                        "$schema": "kompile-graph-extraction/v1",
                        "entities": [
                            {"id": "e1", "name": "Acme Corp", "type": "ORGANIZATION", "aliases": ["Acme"], "description": "A tech company", "confidence": 0.95, "properties": {}},
                            {"id": "e2", "name": "John Doe", "type": "PERSON", "aliases": [], "description": "CEO", "confidence": 0.9, "properties": {}}
                        ],
                        "relations": [
                            {"source": "e1", "target": "e2", "type": "EMPLOYS", "description": "CEO of Acme", "confidence": 0.85, "properties": {}}
                        ]
                    }
                    """;

            llmChat.setFixedResponse(standardResponse);

            RetrievedDoc doc = createDoc("doc1", "Acme Corp is led by CEO John Doe.");
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, SchemaEnforcementMode.NONE);

            assertNotNull(result);
            assertFalse(result.getEntities().isEmpty());
            assertFalse(result.getRelationships().isEmpty());
        }

        @Test
        void testExtractsWithMarkdownFences() {
            String fencedResponse = """
                    ```json
                    {
                        "$schema": "kompile-graph-extraction/v1",
                        "entities": [
                            {"id": "e1", "name": "Widget Inc", "type": "ORGANIZATION", "aliases": [], "description": "A company", "confidence": 0.9, "properties": {}}
                        ],
                        "relations": []
                    }
                    ```
                    """;

            llmChat.setFixedResponse(fencedResponse);

            RetrievedDoc doc = createDoc("doc1", "Widget Inc makes widgets.");
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, SchemaEnforcementMode.NONE);

            assertNotNull(result);
            assertFalse(result.getEntities().isEmpty());
        }
    }

    @Nested
    @DisplayName("Entity Resolution Integration")
    class EntityResolutionTests {

        @Test
        void testMergesDuplicateEntitiesAcrossChunks() {
            // Simulate two chunks that both mention "Acme" with slightly different names
            TextChunker twoChunkSplitter = new TextChunker() {
                @Override
                public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
                    RetrievedDoc chunk1 = RetrievedDoc.builder()
                            .id("chunk1")
                            .text("Acme Corp is a tech company.")
                            .build();
                    RetrievedDoc chunk2 = RetrievedDoc.builder()
                            .id("chunk2")
                            .text("ACME Corporation was founded in 2010.")
                            .build();
                    return List.of(chunk1, chunk2);
                }
                @Override
                public String getName() { return "two-chunk"; }
                @Override
                public List<String> getSupportedLanguages() { return List.of("*"); }
            };

            Neo4jGraphConstructor multiChunkConstructor = new Neo4jGraphConstructor(
                    neo4jDriver, llmChat, objectMapper, twoChunkSplitter, entityResolutionService);

            // Return different standard format for each call
            final int[] callCount = {0};
            llmChat.setResponseGenerator(prompt -> {
                callCount[0]++;
                if (callCount[0] == 1) {
                    return """
                            {
                                "$schema": "kompile-graph-extraction/v1",
                                "entities": [
                                    {"id": "e1", "name": "Acme Corp", "type": "ORGANIZATION", "aliases": ["Acme"], "description": "A tech company", "confidence": 0.9, "properties": {}}
                                ],
                                "relations": []
                            }
                            """;
                } else {
                    return """
                            {
                                "$schema": "kompile-graph-extraction/v1",
                                "entities": [
                                    {"id": "e1", "name": "ACME Corporation", "type": "ORGANIZATION", "aliases": [], "description": "Founded in 2010", "confidence": 0.85, "properties": {"founded": "2010"}}
                                ],
                                "relations": []
                            }
                            """;
                }
            });

            RetrievedDoc doc = createDoc("doc1", "full text about acme");
            Graph result = multiChunkConstructor.constructGraphFromDocs(List.of(doc), null, SchemaEnforcementMode.NONE);

            assertNotNull(result);
            // Entity resolution should merge the two ORGANIZATION entities
            long orgCount = result.getEntities().stream()
                    .filter(e -> "ORGANIZATION".equals(e.getType()))
                    .count();
            assertEquals(1, orgCount, "Should have merged the two ORGANIZATION entities into one");
        }
    }

    @Nested
    @DisplayName("Legacy Format Fallback")
    class LegacyFormatTests {

        @Test
        void testFallsBackToLegacyFormat() {
            String legacyResponse = """
                    {
                        "entities": [
                            {"id": "e1", "title": "Acme Corp", "label": "ORGANIZATION", "description": "A company"}
                        ],
                        "relationships": [
                        ]
                    }
                    """;

            llmChat.setFixedResponse(legacyResponse);

            RetrievedDoc doc = createDoc("doc1", "Acme Corp is a tech company.");
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, SchemaEnforcementMode.NONE);

            assertNotNull(result);
            assertFalse(result.getEntities().isEmpty());
            assertEquals("Acme Corp", result.getEntities().get(0).getTitle());
        }
    }

    @Nested
    @DisplayName("Configuration")
    class ConfigurationTests {

        @Test
        void testConfigureExtractionModel() {
            GraphConstructor.ExtractionModelConfig config =
                    new GraphConstructor.ExtractionModelConfig("openai", "gpt-4o", 0.2, 8192, null);
            constructor.configure(config);

            // Verify it doesn't throw and can be used
            String response = """
                    {
                        "$schema": "kompile-graph-extraction/v1",
                        "entities": [{"id": "e1", "name": "Test", "type": "CONCEPT", "aliases": [], "description": "test", "confidence": 0.9, "properties": {}}],
                        "relations": []
                    }
                    """;
            llmChat.setFixedResponse(response);

            RetrievedDoc doc = createDoc("doc1", "Test entity.");
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, SchemaEnforcementMode.NONE);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        void testHandlesInvalidJsonGracefully() {
            llmChat.setFixedResponse("This is not valid JSON at all");

            RetrievedDoc doc = createDoc("doc1", "Some text.");
            // Should not throw, should return empty graph
            Graph result = constructor.constructGraphFromDocs(List.of(doc), null, SchemaEnforcementMode.NONE);
            assertNotNull(result);
        }

        @Test
        void testHandlesEmptyDocsList() {
            Graph result = constructor.constructGraphFromDocs(List.of(), null, SchemaEnforcementMode.NONE);
            assertNotNull(result);
            assertTrue(result.getEntities().isEmpty());
            assertTrue(result.getRelationships().isEmpty());
        }
    }

    @Nested
    @DisplayName("Extraction Prompt")
    class ExtractionPromptTests {

        @Test
        void testPromptContainsStandardFormatInstructions() {
            llmChat.setFixedResponse("{\"entities\":[], \"relationships\":[]}");

            RetrievedDoc doc = createDoc("doc1", "Test text for extraction.");
            constructor.constructGraphFromDocs(List.of(doc), null, SchemaEnforcementMode.NONE);

            List<String> prompts = llmChat.getReceivedPrompts();
            assertFalse(prompts.isEmpty());
            String prompt = prompts.get(0);
            // Should contain standard format instructions
            assertTrue(prompt.contains("entities"), "Prompt should mention entities");
            assertTrue(prompt.contains("confidence"), "Prompt should mention confidence");
        }

        @Test
        void testCustomPromptReplacesText() {
            GraphConstructor.ExtractionModelConfig config =
                    new GraphConstructor.ExtractionModelConfig("default", null, 0.0, 4096,
                            "Custom prompt: extract from {{TEXT}}");
            constructor.configure(config);

            llmChat.setFixedResponse("{\"entities\":[], \"relationships\":[]}");

            RetrievedDoc doc = createDoc("doc1", "Hello world");
            constructor.constructGraphFromDocs(List.of(doc), null, SchemaEnforcementMode.NONE);

            List<String> prompts = llmChat.getReceivedPrompts();
            assertFalse(prompts.isEmpty());
            String prompt = prompts.get(0);
            assertTrue(prompt.contains("Hello world"), "Custom prompt should have text substituted");
            assertTrue(prompt.contains("Custom prompt"), "Should use custom prompt format");
        }
    }

    private RetrievedDoc createDoc(String id, String text) {
        return RetrievedDoc.builder()
                .id(id)
                .text(text)
                .metadata("documentId", id)
                .build();
    }
}
