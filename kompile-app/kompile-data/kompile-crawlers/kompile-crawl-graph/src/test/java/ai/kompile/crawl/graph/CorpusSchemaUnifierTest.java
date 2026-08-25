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
        stubTypePasses(
                dispatcher, job,
                nodeTypes("EMAIL_MESSAGE", "PERSON"),
                relationshipTypes("SENT_BY"));

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
        assertTrue(schema.getPatterns() == null || schema.getPatterns().isEmpty());

        ArgumentCaptor<StructuredChatLanguageModel.Request> request =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, times(4)).promptStructuredWithCapacityFallback(
                request.capture(),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        List<StructuredChatLanguageModel.Request> typeRequests = request.getAllValues();
        assertEquals(List.of(
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME),
                typeRequests.stream().map(value -> value.tools().get(0).name()).toList());
        assertTrue(typeRequests.stream().allMatch(value ->
                value.toolChoice() == StructuredChatLanguageModel.ToolChoice.REQUIRED));
        Map<String, Object> topProperties = asMap(
                typeRequests.get(0).tools().get(0).parameters().get("properties"));
        Map<String, Object> nodeType = asMap(
                asMap(topProperties.get("nodeTypes")).get("items"));
        assertEquals("string", nodeType.get("type"));
        assertEquals("^[A-Z][A-Z0-9_]*$", nodeType.get("pattern"));
        assertEquals(48, nodeType.get("maxLength"));
        assertEquals(true, asMap(topProperties.get("nodeTypes")).get("uniqueItems"));
        assertEquals(32, asMap(topProperties.get("nodeTypes")).get("maxItems"));
        assertEquals(java.util.Set.of("nodeTypes"), topProperties.keySet());
        Map<String, Object> relationshipProperties = asMap(
                typeRequests.get(2).tools().get(0).parameters().get("properties"));
        Map<String, Object> relationshipType = asMap(
                asMap(relationshipProperties.get("relationshipTypes")).get("items"));
        assertEquals("string", relationshipType.get("type"));
        assertEquals("^[A-Z][A-Z0-9_]*$", relationshipType.get("pattern"));
        assertEquals(48, relationshipType.get("maxLength"));
        assertEquals(java.util.Set.of("relationshipTypes"), relationshipProperties.keySet());
        assertTrue(typeRequests.get(0).messages().get(1).content().contains("email-window"));
        assertTrue(typeRequests.get(2).messages().get(1).content().contains("workbook-window"));
        assertTrue(typeRequests.get(1).messages().get(1).content().contains(
                "UNTRUSTED BATCH PROPOSALS"));
        assertTrue(typeRequests.get(3).messages().get(1).content().contains(
                "UNTRUSTED BATCH PROPOSALS"));
    }

    @Test
    void invalidGenericModelSchemaFailsInsteadOfReturningExtractorCategories() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-2");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(dispatcher, job, nodeTypes("KEYWORD"), relationshipTypes());

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
        stubTypePasses(dispatcher, job, nodeTypes("SOURCE_TYPE"), relationshipTypes());

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
    void retriesInvalidNodeTypesWithValidatorFeedbackThenRunsRelationshipPass() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-schema-repair");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(2)
                .build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);

        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(nodeTypes("KEYWORD"))
                .thenReturn(nodeTypes("EMAIL_MESSAGE", "PERSON"))
                .thenReturn(nodeTypes("EMAIL_MESSAGE", "PERSON"))
                .thenReturn(relationshipTypes("SENT_BY"))
                .thenReturn(relationshipTypes("SENT_BY"));

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
        verify(dispatcher, times(5)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job), scopes.capture());
        assertEquals(List.of(
                        "node-types-1", "node-types-1", "node-types-consolidation",
                        "relationship-types-1", "relationship-types-consolidation"),
                scopes.getAllValues().stream()
                .map(CrawlLlmDispatcher.LlmCallScope::passId).toList());
        assertEquals(List.of(1, 2, 1, 1, 1), scopes.getAllValues().stream()
                .map(CrawlLlmDispatcher.LlmCallScope::passInvocation).toList());
        assertEquals("job-schema-repair:corpus-schema:node-types:1",
                scopes.getAllValues().get(0).taskId());
        assertEquals("job-schema-repair:corpus-schema:node-types:1:attempt:2",
                scopes.getAllValues().get(1).taskId());
        String retryPrompt = requests.getAllValues().get(1).messages().get(1).content();
        assertTrue(retryPrompt.contains("TYPE-SCHEMA REPAIR REQUIRED (attempt 2 of 3)"));
        assertTrue(retryPrompt.contains("SCHEMA_GENERIC_TYPE"));
        assertTrue(retryPrompt.contains("KEYWORD"));
        assertTrue(retryPrompt.contains("Sarah Chen sent the Q3 forecast."));
        assertFalse(retryPrompt.contains("sourceType"));
        assertFalse(retryPrompt.contains("targetType"));
    }

    @Test
    void exhaustsConfiguredNodeTypeValidationRetriesWithoutStartingRelations() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-schema-exhausted");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(2)
                .build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);

        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(nodeTypes("KEYWORD"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("email-window", "The customer requested more margin."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null,
                        job,
                        "snapshot-schema-exhausted",
                        dispatcher));

        assertTrue(failure.getMessage().contains("SCHEMA_GENERIC_TYPE"));
        assertTrue(failure.getMessage().contains("KEYWORD"));
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
        stubTypePasses(
                dispatcher, job,
                nodeTypes("MODEL_INFERRED_TOPIC"),
                relationshipTypes());

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
        verify(dispatcher, times(3)).promptStructuredWithCapacityFallback(
                request.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        assertTrue(request.getAllValues().stream().allMatch(value ->
                value.messages().get(1).content().contains("SEEDED_DOCUMENT")));
        assertTrue(request.getAllValues().stream().allMatch(value ->
                value.messages().get(1).content().contains("EXTRACTOR_MESSAGE")));
        assertTrue(request.getAllValues().stream().allMatch(value ->
                value.messages().get(1).content().contains("DETERMINISTIC_ACTOR")));
    }

    @Test
    void consolidatesUntrustedProposalsBeforeCommitAndRejectsNodeNounRelations() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-consolidation");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(1)
                .build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(nodeTypes("AMER_FORECAST_Q3_FINAL", "FORECAST"))
                .thenReturn(nodeTypes("FORECAST"))
                .thenReturn(relationshipTypes("FORECAST", "USES_CURRENCY"))
                .thenReturn(relationshipTypes("FORECAST"))
                .thenReturn(relationshipTypes("USES_CURRENCY"));

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "The regional forecast uses local currency."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "snapshot-consolidation", dispatcher);

        assertEquals(java.util.Set.of("FORECAST"), schema.getAllNodeLabels());
        assertEquals(java.util.Set.of("USES_CURRENCY"), schema.getAllRelationshipTypes());

        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        ArgumentCaptor<CrawlLlmDispatcher.LlmCallScope> scopes =
                ArgumentCaptor.forClass(CrawlLlmDispatcher.LlmCallScope.class);
        verify(dispatcher, times(5)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job), scopes.capture());
        assertTrue(requests.getAllValues().get(1).messages().get(1).content().contains(
                "AMER_FORECAST_Q3_FINAL"));
        assertTrue(requests.getAllValues().get(1).messages().get(1).content().contains(
                "untrusted suggestions"));
        String relationshipRepair = requests.getAllValues().get(4).messages().get(1).content();
        assertTrue(relationshipRepair.contains("SCHEMA_TYPE_CATEGORY"));
        assertTrue(relationshipRepair.contains("FORECAST"));
        assertEquals("relationship-types-consolidation",
                scopes.getAllValues().get(4).passId());
        assertEquals(2, scopes.getAllValues().get(4).passInvocation());
    }

    @Test
    void semanticPrepassBatchesEveryCorpusPassageThroughTheSmallModel() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-full-corpus");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(dispatcher, job, nodeTypes(), relationshipTypes());

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
        assertTrue(missingTool.getMessage().contains("did not call submit_node_types"));
    }

    @Test
    void rejectsObjectDefinitionsEvenWhenBackendViolatesToolSchema() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-object-type");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        Map.of("nodeTypes", List.of(Map.of(
                                "label", "PERSON",
                                "description", "An extracted person.")))));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Mei Chen submitted the forecast."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null,
                        job,
                        "snapshot-object-type",
                        dispatcher));

        assertTrue(failure.getMessage().contains("plain label strings, never objects"));
    }

    @Test
    void validEmptyOverlayProducesAnExplicitFrozenEmptySchema() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-empty-overlay");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(dispatcher, job, nodeTypes(), relationshipTypes());

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
            String toolName, Map<String, Object> arguments) {
        return new StructuredChatLanguageModel.Response(
                "<native-tool-call>",
                "",
                List.of(new StructuredChatLanguageModel.ToolCall(
                        "schema-call", toolName, arguments)),
                List.of());
    }

    private static StructuredChatLanguageModel.Response nodeTypes(String... labels) {
        return structuredSchemaResponse(
                CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                Map.of("nodeTypes", List.of(labels)));
    }

    private static StructuredChatLanguageModel.Response relationshipTypes(String... labels) {
        return structuredSchemaResponse(
                CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                Map.of("relationshipTypes", List.of(labels)));
    }

    private static void stubTypePasses(
            CrawlLlmDispatcher dispatcher,
            UnifiedCrawlJob job,
            StructuredChatLanguageModel.Response nodeResponse,
            StructuredChatLanguageModel.Response relationshipResponse) {
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        return nodeResponse;
                    }
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return relationshipResponse;
                    }
                    throw new AssertionError("Unexpected schema type tool: " + tool);
                });
    }
}
