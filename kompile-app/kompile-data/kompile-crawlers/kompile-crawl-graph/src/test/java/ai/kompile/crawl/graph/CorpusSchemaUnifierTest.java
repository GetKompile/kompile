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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorpusSchemaUnifierTest {

    @Test
    void derivesDomainSchemaThroughRequiredStructuredTool() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-1");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(domainSchemaArguments()));

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of(
                        "email-window", "From: mei@example.com\nSubject: Q3 forecast",
                        "workbook-window", "Workbook Q3 forecast contains a variance bridge"),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null,
                job,
                "snapshot-1",
                dispatcher);

        assertEquals(
                List.of("EMAIL_MESSAGE", "PERSON"),
                schema.getNodeTypes().stream().map(NodeType::getLabel).toList());
        assertEquals("SENT_BY", schema.getRelationshipTypes().get(0).getType());
        assertEquals(
                List.of("(EMAIL_MESSAGE)-[:SENT_BY]->(PERSON)"),
                schema.getPatterns());

        ArgumentCaptor<StructuredChatLanguageModel.Request> request =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher).promptStructuredWithCapacityFallback(
                request.capture(),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        assertEquals(
                StructuredChatLanguageModel.ToolChoice.REQUIRED,
                request.getValue().toolChoice());
        assertEquals(
                CorpusSchemaUnifier.SCHEMA_TOOL_NAME,
                request.getValue().tools().get(0).name());
        Map<String, Object> topProperties = asMap(
                request.getValue().tools().get(0).parameters().get("properties"));
        Map<String, Object> nodeType = asMap(
                asMap(topProperties.get("nodeTypes")).get("items"));
        assertEquals("string", nodeType.get("type"));
        assertEquals(48, nodeType.get("maxLength"));
        assertEquals(true, asMap(topProperties.get("nodeTypes")).get("uniqueItems"));
        assertEquals(32, asMap(topProperties.get("nodeTypes")).get("maxItems"));
        Map<String, Object> relationshipType = asMap(
                asMap(topProperties.get("relationshipTypes")).get("items"));
        assertEquals("string", relationshipType.get("type"));
        assertEquals(48, relationshipType.get("maxLength"));
        Map<String, Object> patternProperties = asMap(asMap(
                asMap(topProperties.get("patterns")).get("items")).get("properties"));
        assertEquals(48, asMap(patternProperties.get("sourceType")).get("maxLength"));
        assertEquals(48, asMap(patternProperties.get("relationshipType")).get("maxLength"));
        assertEquals(48, asMap(patternProperties.get("targetType")).get("maxLength"));
        assertTrue(request.getValue().messages().get(1).content().contains("email-window"));
        assertTrue(request.getValue().messages().get(1).content().contains("workbook-window"));
    }

    @Test
    void invalidGenericModelSchemaFailsInsteadOfReturningExtractorCategories() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-2");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(Map.of(
                        "nodeTypes", List.of(Map.of(
                                "label", "KEYWORD",
                                "description", "A statistical keyword.")),
                        "relationshipTypes", List.of(),
                        "patterns", List.of())));

        CorpusSchemaCandidates.Inventory fallbackInventory =
                new CorpusSchemaCandidates.Inventory(
                        List.of(new CorpusSchemaCandidates.NodeCandidate(
                                "mei chen",
                                List.of("Mei Chen"),
                                List.of("PERSON"),
                                1,
                                0.95d,
                                List.of("window"),
                                List.of("Mei Chen sent the forecast."))),
                        List.of());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Mei Chen sent the forecast."),
                        fallbackInventory,
                        null,
                        job,
                        "snapshot-2",
                        dispatcher));

        assertTrue(failure.getMessage().contains("SCHEMA_GENERIC_TYPE"));
    }

    @Test
    void invalidModelOverlayFailsInsteadOfReturningConfiguredSchema() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-configured-fallback");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(Map.of(
                        "nodeTypes", List.of(Map.of(
                                "label", "SOURCE_TYPE",
                                "description", "An invalid generic placeholder.")),
                        "relationshipTypes", List.of(),
                        "patterns", List.of())));

        GraphSchema configured = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A named person.", null),
                        new NodeType("COMPANY", "A named company.", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "A person works at a company.", null)),
                List.of("(PERSON)-[:WORKS_AT]->(COMPANY)"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Alice works at Acme."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        configured,
                        job,
                        "snapshot-configured-fallback",
                        dispatcher));

        assertTrue(failure.getMessage().contains("SCHEMA_GENERIC_TYPE"));
    }

    @Test
    void successfulModelOverlayPreservesSeededAndDeterministicTypesInOneSchema() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-merged-schema");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(Map.of(
                        "nodeTypes", List.of(Map.of(
                                "label", "MODEL_INFERRED_TOPIC",
                                "description", "A reusable topic inferred from corpus text.")),
                        "relationshipTypes", List.of(),
                        "patterns", List.of())));

        CorpusSchemaCandidates.Inventory deterministicCandidates =
                new CorpusSchemaCandidates.Inventory(
                        List.of(new CorpusSchemaCandidates.NodeCandidate(
                                "reviewer", List.of("Reviewer"),
                                List.of("DETERMINISTIC_ACTOR"), 2, 0.96d,
                                List.of("window"), List.of("Reviewer approved the document."))),
                        List.of());
        GraphSchema seeded = new GraphSchema(
                List.of(new NodeType(
                        "SEEDED_DOCUMENT", "A configured document type.", null)),
                null,
                null);
        GraphSchema sourceNative = new GraphSchema(
                List.of(
                        new NodeType("EXTRACTOR_MESSAGE", "A source-native message.", null),
                        new NodeType("DETERMINISTIC_ACTOR", "A source-native actor.", null)),
                List.of(new RelationshipType(
                        "EMITTED_BY", "A message was emitted by an actor.", null)),
                List.of("(EXTRACTOR_MESSAGE)-[:EMITTED_BY]->(DETERMINISTIC_ACTOR)"));

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "Reviewer approved the monthly document."),
                deterministicCandidates,
                seeded,
                sourceNative,
                job,
                "snapshot-merged",
                dispatcher);

        assertTrue(schema.getAllNodeLabels().containsAll(List.of(
                "SEEDED_DOCUMENT", "EXTRACTOR_MESSAGE",
                "DETERMINISTIC_ACTOR", "MODEL_INFERRED_TOPIC")));
        assertEquals(java.util.Set.of("EMITTED_BY"), schema.getAllRelationshipTypes());
        assertEquals(
                List.of("(EXTRACTOR_MESSAGE)-[:EMITTED_BY]->(DETERMINISTIC_ACTOR)"),
                schema.getPatterns());

        ArgumentCaptor<StructuredChatLanguageModel.Request> request =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher).promptStructuredWithCapacityFallback(
                request.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        String prompt = request.getValue().messages().get(1).content();
        assertTrue(prompt.contains("SEEDED_DOCUMENT"));
        assertTrue(prompt.contains("EXTRACTOR_MESSAGE"));
        assertTrue(prompt.contains("DETERMINISTIC_ACTOR"));
    }

    @Test
    void semanticPrepassBatchesEveryCorpusPassageThroughTheSmallModel() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-full-corpus");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(Map.of(
                        "nodeTypes", List.of(),
                        "relationshipTypes", List.of(),
                        "patterns", List.of())));

        Map<String, String> passages = new LinkedHashMap<>();
        for (int index = 0; index < 20; index++) {
            passages.put("window-" + index,
                    "UNIQUE_CORPUS_MARKER_" + index + " " + "x".repeat(900));
        }
        GraphSchema seeded = new GraphSchema(
                List.of(new NodeType("SEEDED_TYPE", "A configured seed.", null)),
                null,
                null);

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                passages,
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                seeded,
                null,
                job,
                "snapshot-full-corpus",
                dispatcher);

        assertEquals(java.util.Set.of("SEEDED_TYPE"), schema.getAllNodeLabels());
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, org.mockito.Mockito.atLeast(2))
                .promptStructuredWithCapacityFallback(
                        requests.capture(), eq("llm"), same(job),
                        any(CrawlLlmDispatcher.LlmCallScope.class));
        String allPrompts = requests.getAllValues().stream()
                .map(request -> request.messages().get(1).content())
                .reduce("", (left, right) -> left + "\n" + right);
        for (int index = 0; index < 20; index++) {
            assertTrue(allPrompts.contains("UNIQUE_CORPUS_MARKER_" + index),
                    "full-corpus prepass omitted passage " + index);
        }
    }

    @Test
    void validEmptyOverlayWithoutEstablishedSchemaIsNotAFailure() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-empty-overlay");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(Map.of(
                        "nodeTypes", List.of(),
                        "relationshipTypes", List.of(),
                        "patterns", List.of())));

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "A passage with no reusable domain vocabulary."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null,
                job,
                "snapshot-empty-overlay",
                dispatcher);

        assertNull(schema);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static StructuredChatLanguageModel.Response structuredSchemaResponse(
            Map<String, Object> arguments) {
        return new StructuredChatLanguageModel.Response(
                "<native-tool-call>",
                "",
                List.of(new StructuredChatLanguageModel.ToolCall(
                        "schema-call",
                        CorpusSchemaUnifier.SCHEMA_TOOL_NAME,
                        arguments)),
                List.of());
    }

    private static Map<String, Object> domainSchemaArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("nodeTypes", List.of(
                Map.of(
                        "label", "EMAIL_MESSAGE",
                        "description", "An email message in the corpus."),
                Map.of(
                        "label", "PERSON",
                        "description", "A human participant in the corpus.")));
        arguments.put("relationshipTypes", List.of(Map.of(
                "type", "SENT_BY",
                "description", "An email message was sent by a person.",
                "aliases", List.of("from", "sent by"))));
        arguments.put(
                "patterns",
                List.of("(EMAIL_MESSAGE)-[:SENT_BY]->(PERSON)"));
        return arguments;
    }
}
