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

package ai.kompile.knowledgegraph.builder.impl;

import ai.kompile.core.graphbuilder.*;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.builder.repository.ExtractionJobRepository;
import ai.kompile.knowledgegraph.builder.repository.ExtractionLogRepository;
import ai.kompile.knowledgegraph.matrix.service.StubLLMChat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmKnowledgeGraphBuilderTest {

    @Mock
    private ExtractionJobRepository jobRepository;

    @Mock
    private ExtractionLogRepository logRepository;

    private StubLLMChat stubLlm;
    private ObjectMapper objectMapper;
    private LlmKnowledgeGraphBuilder builder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        stubLlm = new StubLLMChat();
        builder = new LlmKnowledgeGraphBuilder(objectMapper, jobRepository, logRepository);
    }

    @Nested
    @DisplayName("Builder Metadata")
    class MetadataTests {

        @Test
        void testBuilderId() {
            assertEquals("llm-builder", builder.getId());
        }

        @Test
        void testDisplayName() {
            assertEquals("LLM Entity Extractor", builder.getDisplayName());
        }

        @Test
        void testType() {
            assertEquals(GraphBuilderType.LLM, builder.getType());
        }

        @Test
        void testNotReadyWithoutLlm() {
            assertFalse(builder.isReady());
        }

        @Test
        void testReadyWithLlm() {
            builder.setLlmChat(stubLlm);
            assertTrue(builder.isReady());
        }

        @Test
        void testSupportsExtractionLog() {
            assertTrue(builder.supportsExtractionLog());
        }

        @Test
        void testSupportsConcurrentIndexing() {
            assertTrue(builder.supportsConcurrentIndexing());
        }
    }

    @Nested
    @DisplayName("Extraction")
    class ExtractionTests {

        @Test
        void testExtractsEntitiesAndRelationships() {
            String llmResponse = """
                    {
                        "entities": [
                            {"id": "e1", "title": "Acme Corp", "label": "ORGANIZATION", "description": "A tech company"},
                            {"id": "e2", "title": "John Doe", "label": "PERSON", "description": "CEO of Acme"}
                        ],
                        "relationships": [
                            {"source": "e1", "target": "e2", "type": "EMPLOYS", "description": "CEO", "confidence": 0.9}
                        ]
                    }
                    """;

            stubLlm.setFixedResponse(llmResponse);
            builder.setLlmChat(stubLlm);

            RetrievedDoc chunk = createChunk("chunk1", "doc1", "Acme Corp is led by CEO John Doe.");
            GraphBuildContext context = GraphBuildContext.forFactSheet(1L).withJobId("job1");

            List<ProposedTriple> proposals = builder.buildFromChunks(
                    List.of(chunk), context, null);

            assertFalse(proposals.isEmpty());
            assertEquals(1, proposals.size());

            ProposedTriple triple = proposals.get(0);
            assertEquals("Acme Corp", triple.subjectName());
            assertEquals("John Doe", triple.objectName());
            assertEquals("EMPLOYS", triple.predicateName());
        }

        @Test
        void testHandlesMarkdownCodeBlocks() {
            String llmResponse = """
                    ```json
                    {
                        "entities": [
                            {"id": "e1", "title": "Widget Inc", "label": "ORGANIZATION", "description": "Makes widgets"}
                        ],
                        "relationships": []
                    }
                    ```
                    """;

            stubLlm.setFixedResponse(llmResponse);
            builder.setLlmChat(stubLlm);

            RetrievedDoc chunk = createChunk("chunk1", "doc1", "Widget Inc makes widgets.");
            GraphBuildContext context = GraphBuildContext.forFactSheet(1L).withJobId("job1");

            List<ProposedTriple> proposals = builder.buildFromChunks(
                    List.of(chunk), context, null);

            assertNotNull(proposals);
        }

        @Test
        void testHandlesInvalidJsonGracefully() {
            stubLlm.setFixedResponse("This is totally not JSON");
            builder.setLlmChat(stubLlm);

            RetrievedDoc chunk = createChunk("chunk1", "doc1", "Some text.");
            GraphBuildContext context = GraphBuildContext.forFactSheet(1L).withJobId("job1");

            List<ProposedTriple> proposals = builder.buildFromChunks(
                    List.of(chunk), context, null);

            assertNotNull(proposals);
            assertTrue(proposals.isEmpty());
        }

        @Test
        void testFiltersLowConfidenceResults() {
            String llmResponse = """
                    {
                        "entities": [
                            {"id": "e1", "title": "A", "label": "CONCEPT", "description": "First"},
                            {"id": "e2", "title": "B", "label": "CONCEPT", "description": "Second"}
                        ],
                        "relationships": [
                            {"source": "e1", "target": "e2", "type": "RELATED", "description": "Low conf", "confidence": 0.3}
                        ]
                    }
                    """;

            stubLlm.setFixedResponse(llmResponse);
            builder.setLlmChat(stubLlm);

            BuilderConfig strictConfig = new BuilderConfig(
                    "default", null, 0.0, 4096,
                    List.of("CONCEPT"), null,
                    0.8, null, null, null, null);
            builder.configure(strictConfig);

            RetrievedDoc chunk = createChunk("chunk1", "doc1", "A relates to B.");
            GraphBuildContext context = GraphBuildContext.forFactSheet(1L).withJobId("job1");

            List<ProposedTriple> proposals = builder.buildFromChunks(
                    List.of(chunk), context, null);

            assertTrue(proposals.isEmpty(), "Low confidence proposals should be filtered out");
        }
    }

    @Nested
    @DisplayName("Empty/Null Input Handling")
    class EmptyInputTests {

        @Test
        void testEmptyChunksList() {
            builder.setLlmChat(stubLlm);

            GraphBuildContext context = GraphBuildContext.forFactSheet(1L).withJobId("job1");
            List<ProposedTriple> proposals = builder.buildFromChunks(
                    List.of(), context, null);

            assertTrue(proposals.isEmpty());
        }

        @Test
        void testNullChunksList() {
            builder.setLlmChat(stubLlm);

            GraphBuildContext context = GraphBuildContext.forFactSheet(1L).withJobId("job1");
            List<ProposedTriple> proposals = builder.buildFromChunks(
                    null, context, null);

            assertTrue(proposals.isEmpty());
        }

        @Test
        void testNoLlmConfigured() {
            GraphBuildContext context = GraphBuildContext.forFactSheet(1L).withJobId("job1");
            RetrievedDoc chunk = createChunk("chunk1", "doc1", "text");

            List<ProposedTriple> proposals = builder.buildFromChunks(
                    List.of(chunk), context, null);

            assertTrue(proposals.isEmpty());
        }
    }

    @Nested
    @DisplayName("Progress Callback")
    class ProgressCallbackTests {

        @Test
        void testReportsProgress() {
            String llmResponse = """
                    {
                        "entities": [
                            {"id": "e1", "title": "X", "label": "CONCEPT", "description": "test"},
                            {"id": "e2", "title": "Y", "label": "CONCEPT", "description": "test2"}
                        ],
                        "relationships": [
                            {"source": "e1", "target": "e2", "type": "RELATED", "description": "rel", "confidence": 0.9}
                        ]
                    }
                    """;

            stubLlm.setFixedResponse(llmResponse);
            builder.setLlmChat(stubLlm);

            List<BuildProgress> progressList = new ArrayList<>();
            RetrievedDoc chunk1 = createChunk("chunk1", "doc1", "X relates to Y.");
            RetrievedDoc chunk2 = createChunk("chunk2", "doc1", "More about X and Y.");
            GraphBuildContext context = GraphBuildContext.forFactSheet(1L).withJobId("job1");

            builder.buildFromChunks(List.of(chunk1, chunk2), context, progressList::add);

            assertFalse(progressList.isEmpty());
            assertTrue(progressList.size() >= 2);
            BuildProgress last = progressList.get(progressList.size() - 1);
            assertEquals(BuildProgress.Status.COMPLETED, last.status());
        }

        @Test
        void testReportsCompletionForEmptyInput() {
            builder.setLlmChat(stubLlm);

            AtomicReference<BuildProgress> captured = new AtomicReference<>();
            GraphBuildContext context = GraphBuildContext.forFactSheet(1L).withJobId("job1");

            builder.buildFromChunks(List.of(), context, captured::set);

            assertNotNull(captured.get());
            assertEquals(BuildProgress.Status.COMPLETED, captured.get().status());
        }
    }

    @Nested
    @DisplayName("Extraction Log")
    class ExtractionLogTests {

        @Test
        void testCachesExtractionLogs() {
            String llmResponse = """
                    {
                        "entities": [
                            {"id": "e1", "title": "Test", "label": "CONCEPT", "description": "test"}
                        ],
                        "relationships": []
                    }
                    """;

            stubLlm.setFixedResponse(llmResponse);
            builder.setLlmChat(stubLlm);

            RetrievedDoc chunk = createChunk("chunk1", "doc1", "Test text.");
            GraphBuildContext context = GraphBuildContext.forFactSheet(1L).withJobId("job-log-test");

            builder.buildFromChunks(List.of(chunk), context, null);

            Optional<List<ExtractionLogEntry>> logOpt = builder.getExtractionLog("job-log-test");
            assertTrue(logOpt.isPresent());
            assertFalse(logOpt.get().isEmpty());

            ExtractionLogEntry entry = logOpt.get().get(0);
            assertEquals("chunk1", entry.chunkId());
            assertTrue(entry.success());
            assertNotNull(entry.prompt());
            assertNotNull(entry.response());
        }

        @Test
        void testReturnsEmptyForUnknownJob() {
            when(logRepository.findByJobId("unknown")).thenReturn(List.of());

            Optional<List<ExtractionLogEntry>> logOpt = builder.getExtractionLog("unknown");
            assertTrue(logOpt.isEmpty());
        }
    }

    @Nested
    @DisplayName("Configuration")
    class ConfigurationTests {

        @Test
        void testDefaultConfig() {
            BuilderConfig config = builder.getConfig();
            assertNotNull(config);
        }

        @Test
        void testCustomConfig() {
            BuilderConfig custom = new BuilderConfig(
                    "openai", "gpt-4o", 0.2, 8192,
                    List.of("PERSON", "ORGANIZATION"),
                    List.of("EMPLOYS", "WORKS_AT"),
                    0.7, true, 0.9, null, null);

            builder.configure(custom);
            assertEquals(custom, builder.getConfig());
        }

        @Test
        void testNullConfigUsesDefaults() {
            builder.configure(null);
            assertNotNull(builder.getConfig());
        }
    }

    private RetrievedDoc createChunk(String chunkId, String docId, String text) {
        return RetrievedDoc.builder()
                .id(chunkId)
                .text(text)
                .metadata("documentId", docId)
                .build();
    }
}
