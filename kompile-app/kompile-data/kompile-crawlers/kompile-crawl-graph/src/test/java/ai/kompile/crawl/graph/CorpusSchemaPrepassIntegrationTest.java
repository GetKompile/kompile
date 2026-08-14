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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
        when(orchestrator.llmDispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(new StructuredChatLanguageModel.Response(
                        "<native-tool-call>",
                        "",
                        List.of(new StructuredChatLanguageModel.ToolCall(
                                "schema-call",
                                CorpusSchemaUnifier.SCHEMA_TOOL_NAME,
                                Map.of(
                                        "nodeTypes", List.of(Map.of(
                                                "label", "MODEL_INFERRED_TOPIC",
                                                "description", "A reusable corpus topic.")),
                                        "relationshipTypes", List.of(),
                                        "patterns", List.of()))),
                        List.of()));

        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .extractionMode(ExtractionMode.SINGLE_PASS)
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
        verify(orchestrator.llmDispatcher).promptStructuredWithCapacityFallback(
                request.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        String prompt = request.getValue().messages().get(1).content();
        assertTrue(prompt.contains("SEEDED_DOCUMENT"));
        assertTrue(prompt.contains("EXTRACTOR_MESSAGE"));
        assertTrue(prompt.contains("APPROVAL_ROLE"));
        assertTrue(prompt.contains("monthly forecast"));
    }

    @Test
    void invalidSchemaOverlayRetriesThroughProductionGraphExtractionTypes() {
        GraphExtractionOrchestrator orchestrator = spy(new GraphExtractionOrchestrator());
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-graph-discovery");
        when(orchestrator.llmDispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenThrow(new IllegalArgumentException("invalid abstract schema overlay"));

        String discoveredGraph = """
                {"$schema":"kompile-graph-extraction/v1","entities":[
                  {"id":"jordan","name":"Jordan Lee","type":"PERSON","confidence":0.98},
                  {"id":"helios","name":"Helios Dynamics","type":"COMPANY","confidence":0.98}
                ],"relations":[
                  {"source":"jordan","target":"helios","type":"FOUNDED","confidence":0.97}
                ]}
                """;
        doReturn(discoveredGraph).when(orchestrator).extractViaDecomposedPasses(
                any(String.class),
                any(org.springframework.ai.document.Document.class),
                any(GraphExtractionConfig.class),
                isNull(),
                any(Graph.class),
                same(job),
                isNull(),
                any(ExplicitAssertionSchemaInferencer.Analysis.class));

        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-graph-discovery",
                List.of(new CrawlCorpusPassage(
                        "founding-1", 0,
                        "Jordan Lee is a person. Helios Dynamics is a company. "
                                + "Jordan Lee founded Helios Dynamics.",
                        "hash-founding", Map.of(), true)));

        GraphSchema schema = orchestrator.deriveCorpusSchema(
                job,
                corpus,
                GraphExtractionConfig.builder()
                        .schemaMode(SchemaEnforcementMode.LENIENT)
                        .build(),
                Graph.builder().build());

        assertTrue(schema.getAllNodeLabels().containsAll(List.of("PERSON", "COMPANY")));
        assertTrue(schema.getAllRelationshipTypes().contains("FOUNDED"));
        assertTrue(schema.getPatterns().contains("(PERSON)-[:FOUNDED]->(COMPANY)"));
    }

    @Test
    void failedModelOntologyPathsDoNotReturnConfiguredOrDeterministicSchema() {
        GraphExtractionOrchestrator orchestrator = spy(new GraphExtractionOrchestrator());
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
                .thenThrow(new IllegalArgumentException("invalid abstract schema overlay"));
        doReturn(null).when(orchestrator).extractViaDecomposedPasses(
                any(String.class),
                any(org.springframework.ai.document.Document.class),
                any(GraphExtractionConfig.class),
                isNull(),
                any(Graph.class),
                same(job),
                isNull(),
                any(ExplicitAssertionSchemaInferencer.Analysis.class));

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

        assertTrue(failure.getMessage().contains("submit_graph_delta produced no accepted delta"));
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
        when(orchestrator.llmDispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(new StructuredChatLanguageModel.Response(
                        "<native-tool-call>",
                        "",
                        List.of(new StructuredChatLanguageModel.ToolCall(
                                "schema-call",
                                CorpusSchemaUnifier.SCHEMA_TOOL_NAME,
                                Map.of(
                                        "nodeTypes", List.of(),
                                        "relationshipTypes", List.of(),
                                        "patterns", List.of()))),
                        List.of()));

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

        assertNull(schema);
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(orchestrator.llmDispatcher, atLeast(2))
                .promptStructuredWithCapacityFallback(
                        requests.capture(), eq("llm"), same(job),
                        any(CrawlLlmDispatcher.LlmCallScope.class));
        String prompts = requests.getAllValues().stream()
                .map(request -> request.messages().get(1).content())
                .reduce("", (left, right) -> left + right);
        assertTrue(prompts.contains(tailMarker),
                "corpus schema pre-pass truncated the tail of a long passage");
    }

    private static Entity entity(String id, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setType(type);
        return entity;
    }
}
