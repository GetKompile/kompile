package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.ExtractionMode;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusSnapshot;
import ai.kompile.knowledgegraph.service.ConceptExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorpusSchemaPrepassIntegrationTest {

    @Test
    void productionPrepassCombinesSeedsExtractorsAndModelBeforeExtraction() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.conceptExtractor = mock(ConceptExtractor.class);
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);

        when(orchestrator.conceptExtractor.extractConceptsFromPassages(anyMap(), any()))
                .thenReturn(Map.of("chunk-1", new ConceptExtractor.ExtractionResult(
                        List.of(new ConceptExtractor.ExtractedConcept(
                                "Approver", "approver", "APPROVAL_ROLE",
                                0.96d, 2, "Approver reviewed the release.")),
                        List.of(),
                        Map.of())));
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-integration");
        stubTypePasses(
                orchestrator.llmDispatcher, job,
                nodeResponse(List.of("MODEL_INFERRED_TOPIC")),
                relationshipResponse(List.of()));
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .extractionMode(ExtractionMode.DECOMPOSED)
                .decomposedPassStrategy(
                        GraphExtractionConfig.DecomposedPassStrategy.ENTITIES_THEN_RELATIONS)
                .schemaMode(SchemaEnforcementMode.LENIENT)
                .standardizedSchema(new GraphSchema(
                        List.of(new NodeType(
                                "SEEDED_DOCUMENT", "A configured document type.", null)),
                        null,
                        null))
                .build();
        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-integration",
                List.of(new CrawlCorpusPassage(
                        "chunk-1", 0,
                        "Approver reviewed a release message about the monthly forecast.",
                        "hash-1", Map.of(), true)));
        Entity message = entity("message", "EXTRACTOR_MESSAGE");
        Entity actor = entity("actor", "DETERMINISTIC_ACTOR");
        Relationship emittedBy = new Relationship();
        emittedBy.setSource("message");
        emittedBy.setTarget("actor");
        emittedBy.setType("EMITTED_BY");
        Graph deterministicGraph = Graph.builder()
                .entities(new ArrayList<>(List.of(message, actor)))
                .relationships(new ArrayList<>(List.of(emittedBy)))
                .build();
        GraphSchema schema = orchestrator.deriveCorpusSchema(
                job, corpus, config, deterministicGraph);

        assertTrue(schema.getAllNodeLabels().containsAll(List.of(
                "SEEDED_DOCUMENT",
                "EXTRACTOR_MESSAGE",
                "DETERMINISTIC_ACTOR",
                "APPROVAL_ROLE",
                "MODEL_INFERRED_TOPIC")));
        assertTrue(schema.getAllRelationshipTypes().contains("EMITTED_BY"));
        assertTrue(schema.getPatterns().contains(
                "(EXTRACTOR_MESSAGE)-[:EMITTED_BY]->(DETERMINISTIC_ACTOR)"));

        ArgumentCaptor<StructuredChatLanguageModel.Request> request =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        ArgumentCaptor<CrawlLlmDispatcher.LlmCallScope> scope =
                ArgumentCaptor.forClass(CrawlLlmDispatcher.LlmCallScope.class);
        verify(orchestrator.llmDispatcher, times(3)).promptStructuredWithCapacityFallback(
                request.capture(), eq("llm"), same(job), scope.capture());
        assertEquals(List.of(
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME),
                request.getAllValues().stream()
                        .map(value -> value.tools().get(0).name()).toList());
        assertTrue(scope.getAllValues().stream().allMatch(
                value -> "SCHEMA_PREPASS".equals(value.phase())));
        assertEquals(List.of(
                        "node-types-1", "node-types-consolidation", "relationship-types-1"),
                scope.getAllValues().stream().map(
                        CrawlLlmDispatcher.LlmCallScope::passId).toList());
        assertTrue(request.getAllValues().stream().allMatch(value -> {
            String prompt = value.messages().get(1).content();
            return prompt.contains("SEEDED_DOCUMENT")
                    && prompt.contains("EXTRACTOR_MESSAGE")
                    && prompt.contains("APPROVAL_ROLE");
        }));
        assertTrue(request.getAllValues().get(0).messages().get(1).content().contains(
                "monthly forecast"));
        assertTrue(request.getAllValues().get(2).messages().get(1).content().contains(
                "monthly forecast"));
        assertTrue(request.getAllValues().get(1).messages().get(1).content().contains(
                "MODEL_INFERRED_TOPIC"));
    }

    @Test
    void productionPrepassUsesTypeOnlyToolAndRejectsIncidentalAssertionVocabulary() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-type-only");
        stubTypePasses(
                orchestrator.llmDispatcher, job,
                nodeResponse(List.of("PERSON", "ORGANIZATION")),
                relationshipResponse(List.of("WORKS_FOR")));

        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-type-only",
                List.of(new CrawlCorpusPassage(
                        "incidental-1", 0,
                        "Slack DMs are the actual channel. SKUs are AU-001. "
                                + "KAA is a one-shot consulting deliverable. "
                                + "The platform thesis: customer-N is faster than customer-(N-1). "
                                + "The SKU master is on our shared drive. "
                                + "Mei Chen works for Northstar Goods.",
                        "hash-incidental", Map.of(), true)));

        GraphSchema schema = orchestrator.deriveCorpusSchema(
                job,
                corpus,
                GraphExtractionConfig.builder()
                        .schemaMode(SchemaEnforcementMode.LENIENT)
                        .build(),
                Graph.builder().build());

        assertEquals(java.util.Set.of("PERSON", "ORGANIZATION"), schema.getAllNodeLabels());
        assertEquals(java.util.Set.of("WORKS_FOR"), schema.getAllRelationshipTypes());
        assertTrue(schema.getPatterns() == null || schema.getPatterns().isEmpty());
        assertFalse(schema.getAllNodeLabels().stream().anyMatch(List.of(
                "ACTUAL", "AU_001", "ONE_SHOT", "FASTER", "ON")::contains));
        assertFalse(schema.getAllRelationshipTypes().stream().anyMatch(List.of(
                "EW", "EXECUTES_FOR")::contains));

        ArgumentCaptor<StructuredChatLanguageModel.Request> request =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(orchestrator.llmDispatcher, times(4)).promptStructuredWithCapacityFallback(
                request.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        assertEquals(List.of(
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME),
                request.getAllValues().stream()
                        .map(value -> value.tools().get(0).name()).toList());
        assertTrue(request.getAllValues().stream().allMatch(value ->
                value.messages().get(0).content().contains(
                        "Do not extract entities or relations")));
    }

    @Test
    void failedModelOntologyPathsDoNotReturnConfiguredOrDeterministicSchema() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-fail-loudly");
        when(orchestrator.llmDispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(null);

        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-fail-loudly",
                List.of(new CrawlCorpusPassage(
                        "founding-1", 0,
                        "Jordan Lee is a person. Helios Dynamics is a company. "
                                + "Jordan Lee founded Helios Dynamics.",
                        "hash-founding", Map.of(), true)));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> orchestrator.deriveCorpusSchema(
                        job,
                        corpus,
                        GraphExtractionConfig.builder()
                                .schemaMode(SchemaEnforcementMode.LENIENT)
                                .standardizedSchema(new GraphSchema(
                                        List.of(new NodeType(
                                                "SEEDED_DOCUMENT",
                                                "A configured seed that must not mask model failure.",
                                                null)),
                                        null,
                                        null))
                                .build(),
                        Graph.builder().build()));

        assertTrue(failure.getMessage().contains("Structured model response was null"));
    }

    @Test
    void conceptCandidateExtractionFailureStopsTheOntologyPrepass() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.conceptExtractor = mock(ConceptExtractor.class);
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(orchestrator.conceptExtractor.extractConceptsFromPassages(anyMap(), any()))
                .thenThrow(new IllegalStateException("concept prepass unavailable"));
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-concept-failure");
        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-concept-failure",
                List.of(new CrawlCorpusPassage(
                        "chunk-1", 0, "Jordan Lee founded Helios Dynamics.",
                        "hash-1", Map.of(), true)));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> orchestrator.deriveCorpusSchema(
                        job,
                        corpus,
                        GraphExtractionConfig.builder()
                                .schemaMode(SchemaEnforcementMode.LENIENT)
                                .build(),
                        Graph.builder().build()));

        assertTrue(failure.getMessage().contains("concept prepass unavailable"));
    }

    @Test
    void productionPrepassDoesNotTruncateLongCorpusPassages() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-full-passage");
        stubTypePasses(
                orchestrator.llmDispatcher, job,
                nodeResponse(List.of()), relationshipResponse(List.of()));

        String tailMarker = "FULL_CORPUS_TAIL_MARKER";
        String longPassage = "x".repeat(13_500) + tailMarker;
        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-full-passage",
                List.of(new CrawlCorpusPassage(
                        "long-chunk", 0, longPassage, "hash-long", Map.of(), true)));

        GraphSchema schema = orchestrator.deriveCorpusSchema(
                job,
                corpus,
                GraphExtractionConfig.builder()
                        .schemaMode(SchemaEnforcementMode.LENIENT)
                        .build(),
                Graph.builder().build());

        assertNotNull(schema);
        assertTrue(schema.getAllNodeLabels().isEmpty());
        assertTrue(schema.getAllRelationshipTypes().isEmpty());
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(orchestrator.llmDispatcher, atLeast(2)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        String modelPrompts = requests.getAllValues().stream()
                .map(request -> request.messages().get(1).content())
                .reduce("", String::concat);
        assertTrue(modelPrompts.contains(tailMarker),
                "corpus schema pre-pass truncated the tail of a long passage");
    }

    private static StructuredChatLanguageModel.Response nodeResponse(List<String> labels) {
        return typeResponse(
                CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                Map.of("nodeTypes", labels));
    }

    private static StructuredChatLanguageModel.Response relationshipResponse(List<String> labels) {
        return typeResponse(
                CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                Map.of("relationshipTypes", labels));
    }

    private static StructuredChatLanguageModel.Response typeResponse(
            String toolName, Map<String, Object> arguments) {
        return new StructuredChatLanguageModel.Response(
                "<native-tool-call>",
                "",
                List.of(new StructuredChatLanguageModel.ToolCall(
                        "schema-call", toolName, arguments)),
                List.of());
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

    private static Entity entity(String id, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setType(type);
        return entity;
    }
}
