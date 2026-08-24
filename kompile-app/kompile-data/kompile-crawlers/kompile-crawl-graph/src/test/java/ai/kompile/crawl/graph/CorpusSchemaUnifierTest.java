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

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        assertEquals("^[A-Z][A-Z0-9_]*$", nodeType.get("pattern"));
        assertEquals(48, nodeType.get("maxLength"));
        assertEquals(true, asMap(topProperties.get("nodeTypes")).get("uniqueItems"));
        assertEquals(32, asMap(topProperties.get("nodeTypes")).get("maxItems"));
        Map<String, Object> relationshipType = asMap(
                asMap(topProperties.get("relationshipTypes")).get("items"));
        assertEquals("string", relationshipType.get("type"));
        assertEquals("^[A-Z][A-Z0-9_]*$", relationshipType.get("pattern"));
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
                        "nodeTypes", List.of("KEYWORD"),
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
                        "nodeTypes", List.of("SOURCE_TYPE"),
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
    void retriesInvalidSchemaWithValidatorFeedbackAndStableBatchContext() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-schema-repair");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(2)
                .build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);

        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("nodeTypes", List.of("AMER"));
        invalid.put("relationshipTypes", List.of());
        invalid.put("patterns", List.of(Map.of(
                "sourceType", "NORTHSTAR_GOODS",
                "relationshipType", "PURCHASED_FROM",
                "targetType", "FIRM")));
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(invalid))
                .thenReturn(structuredSchemaResponse(domainSchemaArguments()));

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("email-window", "Sarah Chen sent the Q3 forecast."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null,
                job,
                "snapshot-schema-repair",
                dispatcher);

        assertEquals(java.util.Set.of("EMAIL_MESSAGE", "PERSON"), schema.getAllNodeLabels());
        assertEquals(java.util.Set.of("SENT_BY"), schema.getAllRelationshipTypes());

        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        ArgumentCaptor<CrawlLlmDispatcher.LlmCallScope> scopes =
                ArgumentCaptor.forClass(CrawlLlmDispatcher.LlmCallScope.class);
        verify(dispatcher, times(2)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job), scopes.capture());
        assertEquals(List.of("corpus-schema-1", "corpus-schema-1"), scopes.getAllValues().stream()
                .map(CrawlLlmDispatcher.LlmCallScope::passId).toList());
        assertEquals(List.of(1, 2), scopes.getAllValues().stream()
                .map(CrawlLlmDispatcher.LlmCallScope::passInvocation).toList());
        assertEquals("job-schema-repair:corpus-schema:1", scopes.getAllValues().get(0).taskId());
        assertEquals("job-schema-repair:corpus-schema:1:attempt:2",
                scopes.getAllValues().get(1).taskId());
        String retryPrompt = requests.getAllValues().get(1).messages().get(1).content();
        assertTrue(retryPrompt.contains("SCHEMA REPAIR REQUIRED (attempt 2 of 3)"));
        assertTrue(retryPrompt.contains("SCHEMA_PATTERN_ENDPOINT"));
        assertTrue(retryPrompt.contains("NORTHSTAR_GOODS"));
        assertTrue(retryPrompt.contains("Sarah Chen sent the Q3 forecast."));
    }

    @Test
    void exhaustsConfiguredSchemaValidationRetriesWithoutPromotingPatternLabels() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-schema-exhausted");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(2)
                .build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);

        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("nodeTypes", List.of());
        invalid.put("relationshipTypes", List.of());
        invalid.put("patterns", List.of(Map.of(
                "sourceType", "TARGET_CUSTOMER",
                "relationshipType", "WANTED_BIGGER_MARGIN",
                "targetType", "CUSTOMERS")));
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(invalid));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("email-window", "The customer requested more margin."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null,
                        job,
                        "snapshot-schema-exhausted",
                        dispatcher));

        assertTrue(failure.getMessage().contains("SCHEMA_PATTERN_ENDPOINT"));
        assertTrue(failure.getMessage().contains("TARGET_CUSTOMER"));
        verify(dispatcher, times(3)).promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
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
                        "nodeTypes", List.of("MODEL_INFERRED_TOPIC"),
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
    void semanticBatchesPreserveSentenceAssertionsAndEverySourceCharacter() {
        String assertion = "Mei Chen is a finance leader.";
        String passage = "x".repeat(900) + ". " + assertion + " " + "y".repeat(1_500);

        List<Map<String, String>> batches = CorpusSchemaUnifier.modelPassageBatches(
                Map.of("long-window", passage));
        List<String> fragments = batches.stream()
                .flatMap(batch -> batch.values().stream())
                .toList();

        assertEquals(passage, String.join("", fragments));
        assertTrue(fragments.stream().allMatch(fragment -> fragment.length() <= 1_024));
        assertTrue(fragments.stream().anyMatch(fragment -> fragment.contains(assertion)),
                "the type assertion must not be split across semantic prepass windows");
    }

    @Test
    void rejectsBackendWithoutStructuredSchemaToolSupport() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-no-structured-tool");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Mei Chen submitted the forecast."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null,
                        job,
                        "snapshot-no-structured-tool",
                        dispatcher));

        assertTrue(failure.getMessage().contains("structured-chat tool support"));
    }

    @Test
    void rejectsWrongOrMissingStructuredToolCalls() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-wrong-tool");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(new StructuredChatLanguageModel.Response(
                        "<native-tool-call>",
                        "",
                        List.of(new StructuredChatLanguageModel.ToolCall(
                                "wrong-call", "submit_graph_delta", Map.of())),
                        List.of()));

        IllegalStateException wrongTool = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Mei Chen submitted the forecast."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null,
                        job,
                        "snapshot-wrong-tool",
                        dispatcher));
        assertTrue(wrongTool.getMessage().contains("wrong tool"));

        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(new StructuredChatLanguageModel.Response(
                        "{\"nodeTypes\":[\"PERSON\"],\"relationshipTypes\":[],\"patterns\":[]}",
                        "",
                        List.of(),
                        List.of()));
        IllegalStateException missingTool = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Mei Chen submitted the forecast."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null,
                        job,
                        "snapshot-missing-tool",
                        dispatcher));
        assertTrue(missingTool.getMessage().contains("did not call submit_corpus_schema"));
    }

    @Test
    void validEmptyOverlayProducesAnExplicitFrozenEmptySchema() {
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

        assertNotNull(schema);
        assertTrue(schema.getAllNodeLabels().isEmpty());
        assertTrue(schema.getAllRelationshipTypes().isEmpty());
        assertFalse(GraphExtractionOrchestrator.ontologyUpdatesAllowed(
                GraphExtractionConfig.builder().schemaMode(
                        ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode.LENIENT).build(),
                schema));
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
        arguments.put("nodeTypes", List.of("EMAIL_MESSAGE", "PERSON"));
        arguments.put("relationshipTypes", List.of("SENT_BY"));
        arguments.put("patterns", List.of(Map.of(
                "sourceType", "EMAIL_MESSAGE",
                "relationshipType", "SENT_BY",
                "targetType", "PERSON")));
        return arguments;
    }
}
