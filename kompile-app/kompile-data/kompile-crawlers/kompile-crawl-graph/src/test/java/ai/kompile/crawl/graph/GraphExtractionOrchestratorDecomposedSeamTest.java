/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.ExtractionMode;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.GraphConstructor.ExtractionTaskContext;
import ai.kompile.core.graphrag.GraphConstructor.SourceSpan;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusSnapshot;
import ai.kompile.crawl.graph.passes.ToolDrivenExtractionExecutor;
import ai.kompile.graph.reasoning.admission.AdmissionComparison;
import ai.kompile.graph.reasoning.admission.AdmissionDecision;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.impl.ConceptExtractorImpl;
import ai.kompile.knowledgegraph.service.ConceptExtractor;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Production seam tests for DECOMPOSED extraction's single tool-driven model loop.
 *
 * <p>These tests deliberately mock only the crawl's LLM dispatcher. Corpus retrieval, graph
 * reasoning, validation, schema handling, JSON conversion, and task telemetry are production
 * components.</p>
 */
class GraphExtractionOrchestratorDecomposedSeamTest {

    private static final String SOURCE =
            "Acme Corp acquired Initech in 2019. Analysts believe the deal was overpriced.";

    private static final String CORPUS_SEARCH = """
            {"tool":"unified_corpus","args":{
              "action":"SEARCH","query":"Acme Initech acquisition","limit":5}}
            """;

    private static final String GRAPH_SEARCH = """
            {"tool":"graph_reasoning_query","args":{
              "operation":"SEARCH","queryText":"Acme Corporation Initech","topK":5}}
            """;

    private static final String GRAPH_RELATIONS = """
            {"tool":"graph_reasoning_query","args":{
              "operation":"RELATIONS","entityId":"ent-acme","direction":"OUTGOING","topK":10}}
            """;

    private static final String SUBMIT_EXISTING_RELATION = """
            {"tool":"submit_graph_delta","args":{
              "entities":[],
              "relations":[{
                "source":"ent-acme","target":"ent-initech","type":"ACQUIRED",
                "description":"Acme Corp acquired Initech in 2019.",
                "confidence":0.95,"occurredAt":"2019"
              }]
            }}
            """;

    private static Entity entity(String id, String title) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType("ORGANIZATION");
        entity.setConfidence(0.95);
        return entity;
    }

    private static Graph graph() {
        Graph graph = new Graph();
        graph.setId("graph-7");
        graph.setEntities(new ArrayList<>(List.of(
                entity("ent-acme", "Acme Corporation"),
                entity("ent-initech", "Initech"),
                entity("ent-division", "North Division"))));
        Relationship relationship = new Relationship();
        relationship.setSource("ent-acme");
        relationship.setType("OPERATES_DIVISION");
        relationship.setTarget("ent-division");
        relationship.setConfidence(0.9);
        graph.setRelationships(new ArrayList<>(List.of(relationship)));
        return graph;
    }

    private static GraphExtractionConfig decomposedConfig() {
        return GraphExtractionConfig.builder()
                .extractionMode(ExtractionMode.DECOMPOSED)
                .entityTypes(new ArrayList<>(List.of("ORGANIZATION")))
                .relationshipTypes(new ArrayList<>(List.of("ACQUIRED", "PARTNERED_WITH")))
                .validationPolicy(GraphExtractionValidationPolicy.builder()
                        .relationPatterns(new ArrayList<>(
                                List.of("(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)")))
                        .build())
                .build();
    }

    private static Document chunk(String id, String text, String sourcePath) {
        return new Document(id, text,
                sourcePath == null ? Map.of() : Map.of(GraphConstants.META_SOURCE_PATH, sourcePath));
    }

    private static Document chunk() {
        return chunk("chunk-acme", SOURCE, "/corpus/acme.txt");
    }

    private static UnifiedCrawlJob job() {
        return UnifiedCrawlJob.builder().jobId("job-1").build();
    }

    private static UnifiedCrawlJob factSheetJob() {
        return UnifiedCrawlJob.builder()
                .jobId("job-1")
                .request(UnifiedCrawlRequest.builder().factSheetId(42L).build())
                .build();
    }

    private record Harness(
            GraphExtractionOrchestrator orchestrator,
            CrawlLlmDispatcher dispatcher,
            List<String> prompts,
            List<CrawlLlmDispatcher.LlmCallScope> scopes) {
    }

    private static Harness harness(String... scriptedAnswers) {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.pipelineStepTracker = new PipelineStepTracker();
        orchestrator.documentTracker = new CrawlDocumentTracker();
        orchestrator.graphReasoningQueryService = new GraphReasoningQueryService(null);

        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        List<String> prompts = new CopyOnWriteArrayList<>();
        List<CrawlLlmDispatcher.LlmCallScope> scopes = new CopyOnWriteArrayList<>();
        AtomicInteger cursor = new AtomicInteger();
        when(dispatcher.promptWithCapacityFallback(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    prompts.add(invocation.getArgument(0));
                    scopes.add(invocation.getArgument(3));
                    int index = cursor.getAndIncrement();
                    return index < scriptedAnswers.length ? scriptedAnswers[index] : "";
                });
        orchestrator.llmDispatcher = dispatcher;
        return new Harness(orchestrator, dispatcher, prompts, scopes);
    }

    private static Harness successfulHarness() {
        return harness(CORPUS_SEARCH, GRAPH_SEARCH, SUBMIT_EXISTING_RELATION);
    }

    @Test
    void productionSchemaBuilderPreservesDefinitionsPropertiesAliasesAndRelationShapes() {
        GraphSchema standardized = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("ROLE", "A complete role title",
                                List.of(new PropertyType("function", "String")))),
                List.of(new RelationshipType(
                        "HAS_ROLE", "Person has role",
                        List.of(new PropertyType("primary", "Boolean")),
                        List.of("serves_as"))),
                List.of("(PERSON)-[:HAS_ROLE]->(ROLE)"));
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .standardizedSchema(standardized)
                .entityTypes(List.of("ROLE"))
                .relationshipTypes(List.of("HAS_ROLE"))
                .validationPolicy(GraphExtractionValidationPolicy.defaults())
                .build();

        GraphSchema effective = successfulHarness().orchestrator().buildGraphSchema(config);

        assertNotNull(effective);
        assertEquals(List.of("ROLE"), effective.getNodeTypes().stream()
                .map(NodeType::getLabel).toList());
        assertEquals("A complete role title", effective.getNodeTypes().get(0).getDescription());
        assertEquals("function", effective.getNodeTypes().get(0).getProperties().get(0).getName());
        assertEquals(List.of("serves_as"),
                effective.getRelationshipTypes().get(0).getAliases());
        assertEquals("primary",
                effective.getRelationshipTypes().get(0).getProperties().get(0).getName());
        assertEquals(List.of("(PERSON)-[:HAS_ROLE]->(ROLE)"), effective.getPatterns());
    }

    @Test
    void productionSeamUsesCorpusGraphAndSubmitToolsOnTheExistingDispatcher() throws Exception {
        Harness harness = successfulHarness();
        UnifiedCrawlJob job = job();

        String json = harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job);

        assertNotNull(json);
        ExtractionResult result = GraphExtractionValidator.fromJson(json);
        assertEquals(0, result.entities().size());
        assertEquals(1, result.relations().size());
        assertEquals("ACQUIRED", result.relations().get(0).type());
        assertEquals("ent-acme", result.relations().get(0).source());
        assertEquals("ent-initech", result.relations().get(0).target());

        verify(harness.dispatcher(), org.mockito.Mockito.times(3))
                .promptWithCapacityFallback(anyString(), eq("llm"), eq(job), any());
        assertEquals(3, harness.scopes().size());
        assertTrue(harness.scopes().stream().allMatch(scope ->
                ToolDrivenExtractionExecutor.PASS_ID.equals(scope.passId())));
        assertEquals(List.of(1, 2, 3), harness.scopes().stream()
                .map(CrawlLlmDispatcher.LlmCallScope::passInvocation).toList());
        assertTrue(job.getRecentEvents().stream().anyMatch(event ->
                event.getMessage().contains("Tool-guided extraction pass completed")));
    }

    @Test
    void shadowAdmissionObservesAcceptedEntityIdsWithoutChangingReturnedDelta() throws Exception {
        Harness harness = harness(
                """
                {"tool":"submit_graph_delta","args":{"entities":[
                  {"id":"ent-acme","name":"Acme Corporation","type":"ORGANIZATION"},
                  {"id":"ent-new","name":"New Corporation","type":"ORGANIZATION"}
                ],"relations":[]}}
                """);
        GraphExtractionConfig config = decomposedConfig();
        config.setAdmissionMode("SHADOW_COMPARE");
        List<AdmissionComparison> comparisons = new CopyOnWriteArrayList<>();
        harness.orchestrator().admissionComparisonSink = comparisons::add;

        String json = harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), config, graph(), job());

        ExtractionResult result = GraphExtractionValidator.fromJson(json);
        assertEquals(List.of("ent-acme", "ent-new"), result.entities().stream()
                .map(entity -> entity.id()).toList());
        assertEquals(List.of("ent-acme", "ent-new"), comparisons.stream()
                .map(AdmissionComparison::candidateId).toList());
        assertEquals(AdmissionDecision.REUSE, comparisons.get(0).llmDecision());
        assertEquals(AdmissionDecision.CREATE_PROVISIONAL, comparisons.get(1).llmDecision());
        assertTrue(comparisons.stream().allMatch(item ->
                item.authoritativeDecision() == item.llmDecision()));
        assertTrue(comparisons.stream().allMatch(AdmissionComparison::graphAvailable));
        assertTrue(comparisons.stream().allMatch(item ->
                item.snapshotId().startsWith("graph-7:")));

        assertEquals(1, harness.prompts().size());
        assertEquals(2, result.entities().size());
    }

    @Test
    void graphPolicyFiltersTheAcceptedDeltaAtTheProductionCrawlSeam() throws Exception {
        String source = "The Approved workbook has Current status. "
                + "The Blocked workbook has status Do not use.";
        Harness harness = harness(
                """
                {"tool":"submit_graph_delta","args":{
                  "entities":[
                    {"id":"workbook-approved","name":"Approved workbook","type":"WORKBOOK"},
                    {"id":"workbook-blocked","name":"Blocked workbook","type":"WORKBOOK"},
                    {"id":"status-current","name":"Current","type":"STATUS"},
                    {"id":"status-blocked","name":"Do not use","type":"STATUS"}
                  ],
                  "relations":[
                    {"source":"workbook-approved","target":"status-current","type":"HAS_STATUS",
                     "description":"The Approved workbook has Current status.","confidence":1.0},
                    {"source":"workbook-blocked","target":"status-blocked","type":"HAS_STATUS",
                     "description":"The Blocked workbook has status Do not use.","confidence":1.0}
                  ]
                }}
                """);
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .extractionMode(ExtractionMode.DECOMPOSED)
                .entityTypes(new ArrayList<>(List.of("WORKBOOK", "STATUS")))
                .relationshipTypes(new ArrayList<>(List.of("HAS_STATUS")))
                .validationPolicy(GraphExtractionValidationPolicy.builder()
                        .relationPatterns(new ArrayList<>(
                                List.of("(WORKBOOK)-[:HAS_STATUS]->(STATUS)")))
                        .build())
                .admissionMode("GRAPH_POLICY")
                .operationalAdmissionEntityTypes(List.of("WORKBOOK"))
                .operationalAdmissionRules(List.of(
                        new GraphExtractionConfig.OperationalAdmissionRule(
                                "fpna.status.not-usable", "DENY", 100,
                                "The workbook must not enter the crawl graph."),
                        new GraphExtractionConfig.OperationalAdmissionRule(
                                "fpna.status.authoritative", "ALLOW", 10,
                                "The workbook is authoritative.")))
                .build();
        List<AdmissionComparison> comparisons = new CopyOnWriteArrayList<>();
        harness.orchestrator().admissionComparisonSink = comparisons::add;

        String json = harness.orchestrator().extractViaDecomposedPasses(
                source,
                chunk("chunk-policy", source, "/corpus/policy.txt"),
                config,
                graph(),
                job());

        assertNotNull(json);
        ExtractionResult result = GraphExtractionValidator.fromJson(json);
        assertEquals(
                List.of("status-blocked", "status-current", "workbook-approved"),
                result.entities().stream().map(entity -> entity.id()).sorted().toList());
        assertEquals(1, result.relations().size());
        assertEquals("workbook-approved", result.relations().get(0).source());
        assertEquals(List.of("workbook-approved", "workbook-blocked"),
                comparisons.stream().map(AdmissionComparison::candidateId).toList());
        assertEquals(AdmissionDecision.CREATE_PROVISIONAL,
                comparisons.get(0).authoritativeDecision());
        assertEquals(AdmissionDecision.REJECT,
                comparisons.get(1).authoritativeDecision());
    }

    @Test
    void completeUnifiedCorpusIsVisibleFromTheFirstShard() {
        Harness harness = harness(
                """
                {"tool":"unified_corpus","args":{
                  "action":"SEARCH","query":"Orchid approval finance","limit":5}}
                """,
                SUBMIT_EXISTING_RELATION);
        UnifiedCrawlJob job = job();
        Document first = chunk();
        Document second = chunk("chunk-orchid",
                "Orchid approval was recorded by the finance team.", "/corpus/orchid.txt");
        CrawlCorpusSnapshot active = harness.orchestrator().activateExtractionCorpus(
                job, List.of(first, second), "corpus-all-documents");

        try {
            assertNotNull(harness.orchestrator().extractViaDecomposedPasses(
                    first.getText(), first, decomposedConfig(), graph(), job));
        } finally {
            harness.orchestrator().deactivateExtractionCorpus(job, active);
        }

        assertTrue(harness.prompts().get(1).contains(
                "Orchid approval was recorded by the finance team."));
        assertTrue(harness.scopes().stream().allMatch(scope ->
                "corpus-all-documents".equals(scope.corpusSnapshotId())));
        assertEquals(0, harness.orchestrator().activeExtractionCorpusCount());
    }

    @Test
    void persistedCorpusFillsGapsWhileCurrentTextWinsAndOrderIsPreserved() {
        Harness harness = successfulHarness();
        CrawlIndexTrackingCallback corpus = mock(CrawlIndexTrackingCallback.class);
        when(corpus.loadCorpusSnapshot(42L)).thenReturn(Optional.of(
                new CrawlCorpusSnapshot("persisted-v4", List.of(
                        new CrawlCorpusPassage("same", 99, "stale persisted text",
                                "old", Map.of(), true),
                        new CrawlCorpusPassage("persisted", 1, "complete historical text",
                                "history", Map.of(), true),
                        new CrawlCorpusPassage("preview", 2, "truncated preview",
                                "preview", Map.of(), false)))));
        harness.orchestrator().crawlIndexTrackingCallback = corpus;
        UnifiedCrawlJob job = factSheetJob();

        CrawlCorpusSnapshot snapshot = harness.orchestrator().activateExtractionCorpus(
                job,
                List.of(
                        chunk("same", "fresh current text", "/current/same.txt"),
                        chunk("current-two", "second current text", "/current/two.txt")),
                null);
        try {
            assertEquals(List.of("same", "current-two", "persisted", "preview"),
                    snapshot.passages().stream().map(CrawlCorpusPassage::chunkId).toList());
            assertEquals("fresh current text", snapshot.passages().get(0).content());
            assertTrue(snapshot.snapshotId().startsWith("extraction-corpus-v1:"));
        } finally {
            harness.orchestrator().deactivateExtractionCorpus(job, snapshot);
        }
        assertEquals(0, harness.orchestrator().activeExtractionCorpusCount());
    }

    @Test
    void graphToolSeesIncrementalRelationsWithoutFlatteningTheGraphIntoEveryPrompt() {
        Harness harness = harness(GRAPH_RELATIONS, SUBMIT_EXISTING_RELATION);

        String json = harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job());

        assertNotNull(json);
        assertFalse(harness.prompts().get(0).contains("ENTITIES:\n- id="));
        assertTrue(harness.prompts().get(0).contains("graphRevision: graph-7:3:1"));
        assertTrue(harness.prompts().get(1).contains("OPERATES_DIVISION"));
        assertTrue(harness.prompts().get(1).contains("ent-division"));
    }

    @Test
    void persistedEmbeddingsAndLogicRemainAvailableAlongsideTransientGraphState() {
        Harness harness = harness(
                """
                {"tool":"graph_reasoning_query","args":{
                  "operation":"SIMILAR","entityId":"persisted-lead",
                  "topK":5,"structural":"PSL"}}
                """,
                """
                {"tool":"graph_reasoning_query","args":{
                  "operation":"SEARCH","queryText":"Transient Audit","topK":5}}
                """,
                """
                {"tool":"submit_graph_delta","args":{"entities":[],"relations":[]}}
                """);
        UnifiedGraph persisted = new UnifiedGraph().graphId("factsheet_42").factSheetId(42L);
        persisted.addEntity(GraphEntity.builder("persisted-lead")
                .type("PERSON").label("Finance Lead").confidence(0.95)
                .embedding(new double[]{1.0, 0.0}).build());
        persisted.addEntity(GraphEntity.builder("persisted-close")
                .type("PROCESS_STEP").label("Close Review").confidence(0.9)
                .embedding(new double[]{0.9, 0.1}).build());
        persisted.addRelation(GraphRelation.builder(
                        "persisted-approval", "persisted-lead", "persisted-close")
                .type("APPROVED").confidence(0.9).weight(0.9).directed(true).build());
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.export(42L)).thenReturn(persisted);
        harness.orchestrator().unifiedGraphBridge = bridge;

        Graph transientGraph = graph();
        transientGraph.getEntities().add(entity("transient-audit", "Transient Audit"));
        assertNotNull(harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), transientGraph, factSheetJob()));

        assertTrue(harness.prompts().get(1).contains("\"semanticVectorAvailable\":true"));
        assertTrue(harness.prompts().get(1).contains("\"structuralEngine\":\"PSL\""));
        assertTrue(harness.prompts().get(2).contains("transient-audit"));
    }

    @Test
    void promptExposesReasoningFacetsAndColdStartCorpusWithoutDomainWordLists() {
        Harness harness = harness(
                """
                {"tool":"submit_graph_delta","args":{"entities":[],"relations":[]}}
                """);
        Graph fresh = new Graph();
        fresh.setEntities(new ArrayList<>());
        fresh.setRelationships(new ArrayList<>());
        String source = "Aster reviewed the submitted record.";

        assertNotNull(harness.orchestrator().extractViaDecomposedPasses(
                source, chunk("aster", source, "/corpus/aster.txt"),
                decomposedConfig(), fresh, job()));

        String prompt = harness.prompts().get(0);
        assertTrue(prompt.contains("unified_corpus"));
        assertTrue(prompt.contains("graph_reasoning_query"));
        assertTrue(prompt.contains("existing graph structure, embeddings, logic, and schema"));
        assertTrue(prompt.contains("Arrays are unlimited"));
        assertTrue(prompt.contains("every source-supported entity and relation"));
        assertFalse(prompt.contains("Japanese"));
        assertFalse(prompt.contains("VERIFY"),
                "operation discovery remains behind graph tools instead of bloating every prompt");
    }

    @Test
    void rejectedProposalReturnsValidationFeedbackAndDoesNotMutateTheGraph() throws Exception {
        Harness harness = harness(
                """
                {"tool":"submit_graph_delta","args":{
                  "entities":[],"relations":[{
                    "source":"ent-acme","target":"missing","type":"ACQUIRED",
                    "description":"bad endpoint","confidence":0.9}]}}
                """,
                SUBMIT_EXISTING_RELATION);
        Graph graph = graph();
        int entitiesBefore = graph.getEntities().size();
        int relationsBefore = graph.getRelationships().size();

        String json = harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph, job());

        assertNotNull(json);
        assertTrue(harness.prompts().get(1).contains("missing"));
        assertTrue(harness.prompts().get(1).contains("errors"));
        assertEquals(entitiesBefore, graph.getEntities().size());
        assertEquals(relationsBefore, graph.getRelationships().size());
        assertEquals(1, GraphExtractionValidator.fromJson(json).relations().size());
    }

    @Test
    void productionChunkExtractionAcceptsRelationsToExistingGraphEndpoints() {
        Harness harness = successfulHarness();
        GraphConstructor oneShotConstructor = mock(GraphConstructor.class);
        harness.orchestrator().graphConstructor = oneShotConstructor;
        ExtractionTaskContext task = new ExtractionTaskContext(
                "job-1:partition-acme:chunk-acme",
                "partition-acme",
                "partition-corpus-v1:abc123",
                List.of("Acme Corporation", "Initech"),
                chunk().getId(),
                "STRUCTURED_RELATIONSHIP",
                "one-hop graph evidence",
                0.88,
                "graph-7",
                null);

        Graph extracted = harness.orchestrator().extractChunkGraph(
                chunk(), decomposedConfig(), job(), graph(), task);

        assertNotNull(extracted);
        assertTrue(extracted.getEntities().isEmpty());
        assertEquals(1, extracted.getRelationships().size());
        verifyNoInteractions(oneShotConstructor);
        assertTrue(harness.scopes().stream().allMatch(scope ->
                "ENTITY_PARTITIONS".equals(scope.phase())
                        && "partition-acme".equals(scope.partitionId())
                        && "partition-corpus-v1:abc123".equals(scope.corpusSnapshotId())));
    }

    @Test
    void deterministicConceptPrepassRemainsARoutingHintRatherThanEvidence() {
        Harness harness = harness(
                """
                {"tool":"submit_graph_delta","args":{"entities":[],"relations":[]}}
                """);
        ConceptExtractor concepts = mock(ConceptExtractor.class);
        when(concepts.extractConcepts(anyString(), any())).thenReturn(
                new ConceptExtractor.ExtractionResult(
                        List.of(new ConceptExtractor.ExtractedConcept(
                                "acquisition date", "acquisition date", "KEYWORD", 0.9, 1,
                                "acquired Initech in 2019")),
                        List.of(), Map.of()));
        harness.orchestrator().conceptExtractor = concepts;

        assertNotNull(harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job()));

        String prompt = harness.prompts().get(0);
        assertTrue(prompt.contains("HINTS (candidate boundaries and types; not evidence)"));
        assertTrue(prompt.contains("acquisition date [KEYWORD]"));
    }

    @Test
    void productionConceptPrepassPreservesShortSchemaDiscriminators() {
        String source = "The inventory identifies M. Chen as VP, FP&A and J. Park as Sr. Analyst.";
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.conceptExtractor = new ConceptExtractorImpl();
        ExtractionTaskContext task = new ExtractionTaskContext(
                "task-role", "partition-role", "corpus-role", List.of(), "chunk-role",
                "SOURCE_EVENT", "raw retained source event", 1.0, null, null);

        ExtractionTaskContext prepared = orchestrator.prepareDecomposedTaskContext(
                source, new Document("chunk-role", source, Map.of()), graph(), null, task);
        List<String> terms = prepared.conceptHints().stream()
                .map(GraphConstructor.ConceptHint::term)
                .toList();

        assertTrue(terms.stream().anyMatch(term -> "M. Chen".equalsIgnoreCase(term)),
                () -> "production pre-pass split the initialized identity M. Chen: " + terms);
        assertTrue(terms.stream().anyMatch(term -> "J. Park".equalsIgnoreCase(term)),
                () -> "production pre-pass split the initialized identity J. Park: " + terms);
        assertTrue(terms.stream().anyMatch(term -> "VP".equalsIgnoreCase(term)),
                () -> "production pre-pass dropped the short ontology discriminator VP: " + terms);
        assertTrue(terms.stream().anyMatch(term -> "FP&A".equalsIgnoreCase(term)),
                () -> "production pre-pass dropped the punctuated ontology discriminator FP&A: " + terms);
        assertTrue(terms.stream().anyMatch(term -> "VP, FP&A".equalsIgnoreCase(term)),
                () -> "production pre-pass dropped the source compound VP, FP&A: " + terms);
        assertTrue(prepared.conceptHints().stream().allMatch(hint ->
                        !"APPROVAL_ROLE".equals(hint.category())),
                "the deterministic pre-pass must remain domain-neutral; schema semantics resolve types");
    }

    @Test
    void graphProjectionCountsDirectAndArchivedMetadataAsOneEvidenceOccurrence() {
        String quote = "Acme Corp acquired Initech in 2019.";
        Map<String, Object> evidence = Map.of(
                "sourceChunkId", "chunk-acme",
                "sourceDocumentId", "acme.txt",
                "evidenceQuote", quote);
        Map<String, Object> metadata = Map.of(
                "sourceChunkId", "chunk-acme",
                "sourceDocumentId", "acme.txt",
                "evidenceQuote", quote,
                "supportingEvidence", List.of(evidence));

        Entity acme = entity("ent-acme", "Acme Corporation");
        acme.setMetadata(metadata);
        Entity initech = entity("ent-initech", "Initech");
        initech.setMetadata(metadata);
        Relationship acquired = new Relationship();
        acquired.setSource(acme.getId());
        acquired.setTarget(initech.getId());
        acquired.setType("ACQUIRED");
        acquired.setConfidence(0.95);
        acquired.setMetadata(metadata);

        Graph produced = new Graph();
        produced.setEntities(new ArrayList<>(List.of(acme, initech)));
        produced.setRelationships(new ArrayList<>(List.of(acquired)));
        Graph context = new Graph();
        context.setId("graph-evidence");
        context.setEntities(new ArrayList<>());
        context.setRelationships(new ArrayList<>());

        new GraphExtractionOrchestrator().mergeIntoContext(
                produced, context, decomposedConfig());

        assertEquals(2, context.getEntities().size());
        for (Entity entity : context.getEntities()) {
            assertEquals(1,
                    ((List<?>) entity.getMetadata().get("supportingEvidence")).size());
        }
        assertEquals(1, context.getRelationships().size());
        Map<String, Object> relationMetadata = context.getRelationships().get(0).getMetadata();
        assertEquals(1, ((List<?>) relationMetadata.get("supportingEvidence")).size());
        assertEquals(1, relationMetadata.get("supportingCount"));
    }

    @Test
    void emptyValidatedDeltaIsARealAnswerButNoModelAnswerStillRetriesUpstream() throws Exception {
        Harness empty = harness(
                """
                {"tool":"submit_graph_delta","args":{"entities":[],"relations":[]}}
                """);
        String emptyJson = empty.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job());
        assertNotNull(emptyJson);
        assertTrue(GraphExtractionValidator.fromJson(emptyJson).entities().isEmpty());
        assertTrue(GraphExtractionValidator.fromJson(emptyJson).relations().isEmpty());

        Harness noAnswer = harness("");
        assertNull(noAnswer.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job()));
    }

    @Test
    void dispatcherFailureIsNotMaskedByAnAlternateExecutionPath() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        when(dispatcher.promptWithCapacityFallback(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("backend unreachable"));
        orchestrator.llmDispatcher = dispatcher;
        orchestrator.graphReasoningQueryService = new GraphReasoningQueryService(null);

        assertNull(orchestrator.extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job()));
    }

    @Test
    void previewInvocationCanRunWithoutJobOrPersistedConfig() {
        Harness harness = harness(
                """
                {"tool":"submit_graph_delta","args":{"entities":[],"relations":[]}}
                """);

        assertNotNull(harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), null, graph(), null));
    }

    @Test
    void decomposedModeStillRequiresAtomicOrderedDispatch() {
        assertTrue(GraphExtractionOrchestrator.requiresAtomicOrderedDispatch(decomposedConfig()));
        assertFalse(GraphExtractionOrchestrator.requiresAtomicOrderedDispatch(
                GraphExtractionConfig.builder().extractionMode(ExtractionMode.SINGLE_PASS).build()));
    }

    @Test
    void taskContextTracksTheIncrementalGraphRevision() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        Graph context = new Graph();
        context.setId("graph-live");
        context.setEntities(new ArrayList<>());
        context.setRelationships(new ArrayList<>());
        ExtractionTaskContext task = new ExtractionTaskContext(
                "task-1", "partition-acme", "corpus-v1", List.of("Acme"), "chunk-1",
                "SOURCE_EVENT", "bounded source event", 1.0, null, null);

        ExtractionTaskContext empty = orchestrator.prepareDecomposedTaskContext(
                SOURCE, chunk(), context, null, task);
        assertEquals("graph-live:0:0", empty.graphRevision());

        context.getEntities().add(entity("ent-acme", "Acme Corporation"));
        context.getEntities().add(entity("ent-division", "North Division"));
        Relationship operates = new Relationship();
        operates.setSource("ent-acme");
        operates.setType("OPERATES_DIVISION");
        operates.setTarget("ent-division");
        context.getRelationships().add(operates);

        ExtractionTaskContext accumulated = orchestrator.prepareDecomposedTaskContext(
                SOURCE, chunk(), context, null, task);
        assertEquals("graph-live:2:1", accumulated.graphRevision());
    }

    @Test
    void archivedSourceSpansRemainEngineOwnedChunkBoundaries() {
        String source = "First event. Second event.";
        Document document = new Document(source, Map.of(
                GraphConstants.META_SOURCE_EVENT_SPANS, List.of(
                        Map.of("start", 0, "end", 12, "kind", "EMAIL_HEADER"),
                        Map.of("start", "13", "end", String.valueOf(source.length()),
                                "kind", "EMAIL_BODY"),
                        Map.of("start", -1, "end", 2))));

        List<SourceSpan> spans = GraphExtractionOrchestrator.sourceEventSpans(document, source);

        assertEquals(List.of(
                new SourceSpan(0, 12, "EMAIL_HEADER"),
                new SourceSpan(13, source.length(), "EMAIL_BODY")), spans);
    }
}
