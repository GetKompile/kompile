/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.GraphBayesTool;
import ai.kompile.cli.main.chat.tools.GraphEmbeddingsTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
import ai.kompile.core.crawl.graph.NativeChatCompletion;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.crawl.graph.CrawlOntology;
import ai.kompile.crawl.graph.CanonicalGraphSchemaArtifact;
import ai.kompile.crawl.graph.CrawlStepPlan;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryArtifactCodec;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic tiny corpora exercise graph behavior here; crawl admission is covered separately and
 * is disabled for this fixture so host load cannot make these graph assertions nondeterministic.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class LocalProjectGraphBackendTest {
    @TempDir
    Path projectRoot;

    private ObjectMapper mapper;
    private ToolContext context;
    private String previousAdmissionMode;

    @BeforeEach
    void setUp() {
        previousAdmissionMode = System.getProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "off");
        mapper = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("local-graph-worker")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService permissions = new PermissionService();
        for (String tool : Set.of(
                "crawl_documents", "crawl_control", "graph_reasoning_query", "graph_reason",
                "graph_bayes", "graph_embeddings", "graph_export", "graph_import", "external_directory")) {
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        }
        context = new ToolContext("local-graph-test", agent, permissions, projectRoot,
                new ToolRegistry(mapper));
    }

    @AfterEach
    void tearDown() {
        if (previousAdmissionMode == null) {
            System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        } else {
            System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, previousAdmissionMode);
        }
    }

    @Test
    void localReasoningQueryEnforcesTheTransportContractAndEnumAliases() throws Exception {
        LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.put("operation", "CAPABILITIES");

        JsonNode result = backend.reasoningQuery(capabilities, context);
        List<String> operationNames = new java.util.ArrayList<>();
        result.path("capabilities").forEach(capability ->
                operationNames.add(capability.path("intent").asText()));
        assertEquals(List.of(
                "CAPABILITIES", "OVERVIEW", "SCHEMA", "SEARCH", "RELATIONS",
                "DESCRIBE", "NEIGHBORS", "PATH", "TIMELINE", "FACTS", "SIMILAR",
                "VERIFY", "WHY", "WHY_NOT", "RANK", "ASSETS", "ARTIFACT"),
                operationNames);

        for (String blocked : List.of("MODELS", "CALCULATE", "SCENARIO", "SOLVE_TARGET")) {
            ObjectNode request = mapper.createObjectNode().put("operation", blocked);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> backend.reasoningQuery(request, context));
            assertTrue(failure.getMessage().contains("not supported"), failure.getMessage());
        }

        ObjectNode normalizedIntent = mapper.createObjectNode()
                .put("operation", "why-not")
                .put("entityId", "missing-source")
                .put("targetId", "missing-target");
        normalizedIntent.putArray("relationTypes").add("RELATED_TO");
        assertEquals("WHY_NOT",
                backend.reasoningQuery(normalizedIntent, context).path("intent").asText());

        ObjectNode directionAlias = mapper.createObjectNode()
                .put("operation", "neighbors")
                .put("entityId", "missing-source")
                .put("direction", "forward");
        assertEquals("NEIGHBORS",
                backend.reasoningQuery(directionAlias, context).path("intent").asText());
    }

    @Test
    void localCrawlStageAliasesTranslateAtTheSharedLifecycleBoundary() {
        assertEquals("LOADING", LocalProjectGraphBackend.sharedLifecycleStepId(" loading "));
        assertEquals("CONVERTING", LocalProjectGraphBackend.sharedLifecycleStepId("MARKDOWN_EXTRACTION"));
        assertEquals("SURFACING", LocalProjectGraphBackend.sharedLifecycleStepId("LEXICAL_INDEX"));
        assertEquals("ENRICHMENT", LocalProjectGraphBackend.sharedLifecycleStepId("LEARNING"));
        assertEquals("GRAPH_EXTRACTION", LocalProjectGraphBackend.sharedLifecycleStepId("GRAPH_EXTRACTION"));
        assertEquals("TYPO", LocalProjectGraphBackend.sharedLifecycleStepId("TYPO"));
    }

    @Test
    void localAliasesRespectStrictAndLegacyPlanningAtTheSharedBoundary() {
        for (boolean strict : List.of(true, false)) {
            for (String localId : List.of("MARKDOWN_EXTRACTION", "LEXICAL_INDEX", "LEARNING")) {
                ObjectNode request = mapper.createObjectNode().put("strictSteps", strict);
                request.putArray("steps").add(localId);

                CrawlStepPlan plan = LocalProjectGraphBackend.localStepPlan(request);

                CrawlStepPlan.Action expectedGraphExtraction = localId.equals("LEARNING")
                        ? CrawlStepPlan.Action.RUN
                        : strict ? CrawlStepPlan.Action.SKIP : CrawlStepPlan.Action.RUN;
                assertEquals(expectedGraphExtraction, plan.forStep("GRAPH_EXTRACTION"),
                        localId + " strict=" + strict);
                assertEquals(CrawlStepPlan.Action.RUN, plan.forStep(
                        LocalProjectGraphBackend.sharedLifecycleStepId(localId)),
                        localId + " must map to a shared crawl step");
                assertEquals(localId.equals("LEARNING") ? CrawlStepPlan.Action.RUN : CrawlStepPlan.Action.SKIP,
                        plan.forStep("ENRICHMENT"),
                        localId + " enrichment selection");
                assertDoesNotThrow(plan::validate);
            }
        }
    }

    @Test
    void explicitLearningAliasRunsReasoningForLegacyAndStrictPlans() throws Exception {
        Path docs = projectRoot.resolve("learning-alias-docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("retained.md"),
                "# Retained\nThe aurora compass points toward the northern archive.\n",
                StandardCharsets.UTF_8);
        Files.writeString(docs.resolve("related.md"),
                "# Related\nThe northern archive catalogs the silver beacon.\n",
                StandardCharsets.UTF_8);

        for (boolean strict : List.of(false, true)) {
            int factSheetId = strict ? 43 : 42;
            ObjectNode request = mapper.createObjectNode()
                    .put("async", false)
                    .put("strictSteps", strict);
            request.putArray("documents").addObject().put("path", "learning-alias-docs");
            request.putObject("knowledgeBase").put("id", factSheetId);
            request.putArray("steps").add("LEARNING");
            request.putObject("embeddingTraining").put("enabled", false);
            request.putObject("reasoningLearning")
                    .put("enabled", true)
                    .put("pslSteps", 1)
                    .put("mebnEpochs", 1)
                    .put("consensusRounds", 1)
                    .put("maxRelationTypes", 8);

            ToolResult result = new CrawlDocumentsTool((String) null, mapper)
                    .execute(request, context);

            assertFalse(result.isError(), "strict=" + strict + ": " + result.getOutput());
            assertEquals(true, result.getMetadata().get("enrichmentRequested"),
                    "strict=" + strict + ": " + result.getMetadata());
            assertEquals(true, result.getMetadata().get("reasoningLearningEnabled"),
                    "strict=" + strict + ": " + result.getMetadata());
            assertEquals(true, result.getMetadata().get("folPslLearned"),
                    "strict=" + strict + ": " + result.getMetadata());
            assertEquals(true, result.getMetadata().get("mebnLearned"),
                    "strict=" + strict + ": " + result.getMetadata());
        }
    }

    @Test
    void explicitLearningAliasPreservesManualReasoningOptOut() throws Exception {
        Path docs = projectRoot.resolve("learning-opt-out-docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("note.md"),
                "# Note\nThe silver compass points toward the northern archive.\n",
                StandardCharsets.UTF_8);

        ObjectNode request = mapper.createObjectNode()
                .put("async", false)
                .put("strictSteps", true);
        request.putArray("documents").addObject().put("path", "learning-opt-out-docs");
        request.putObject("knowledgeBase").put("id", 44);
        request.putArray("steps").add("LEARNING");
        request.putObject("embeddingTraining").put("enabled", false);
        request.putObject("runtimeConfig").put("runReasoningLearning", true);
        request.putObject("reasoningLearning").put("enabled", false);

        ToolResult result = new CrawlDocumentsTool((String) null, mapper)
                .execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals(true, result.getMetadata().get("enrichmentRequested"), result.getMetadata().toString());
        assertEquals(false, result.getMetadata().get("reasoningLearningEnabled"), result.getMetadata().toString());
        assertEquals(false, result.getMetadata().get("folPslLearned"), result.getMetadata().toString());
        assertEquals(false, result.getMetadata().get("mebnLearned"), result.getMetadata().toString());
        UnifiedGraph graph = UnifiedGraph.load(
                projectRoot.resolve("data/crawls/kb-44/graph.kgraph"));
        assertEquals("SKIPPED_BY_CONFIGURATION", graph.meta().get("enrichment.status"));
    }

    @Test
    void localAliasesRespectArchivedStepMappingWithoutArchivingSharedSteps() {
        for (String localId : List.of("MARKDOWN_EXTRACTION", "LEXICAL_INDEX", "LEARNING")) {
            ObjectNode request = mapper.createObjectNode();
            request.putArray("archivedSteps").add(localId);

            CrawlStepPlan plan = LocalProjectGraphBackend.localStepPlan(request);

            assertEquals(CrawlStepPlan.Action.RUN, plan.forStep(
                    LocalProjectGraphBackend.sharedLifecycleStepId(localId)), localId);
            assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("ENRICHMENT"),
                    "Archiving a local completion alias must not disable enrichment by aliasing it");
            assertDoesNotThrow(plan::validate);
        }

        ObjectNode vectorArchive = mapper.createObjectNode();
        vectorArchive.putArray("archivedSteps").add("VECTOR_INDEXING");
        assertEquals(CrawlStepPlan.Action.ARCHIVE,
                LocalProjectGraphBackend.localStepPlan(vectorArchive).forStep("VECTOR_INDEXING"));
    }

    @Test
    void localStepPlanPreservesBlankAndNullIdsForSharedValidation() {
        ObjectNode malformed = mapper.createObjectNode();
        malformed.putArray("steps").addNull().add(" ");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LocalProjectGraphBackend.localStepPlan(malformed));

        assertTrue(error.getMessage().contains("<null>"), error.getMessage());
        assertTrue(error.getMessage().contains("<blank>"), error.getMessage());
    }

    @Test
    void localStepPlanRejectsUnknownEnabledAndArchivedIds() {
        ObjectNode enabled = mapper.createObjectNode();
        enabled.putArray("steps").add("BOGUS_STEP");
        IllegalArgumentException enabledError = assertThrows(IllegalArgumentException.class,
                () -> LocalProjectGraphBackend.localStepPlan(enabled));
        assertTrue(enabledError.getMessage().contains("Unknown enabledSteps"), enabledError.getMessage());
        assertTrue(enabledError.getMessage().contains("BOGUS_STEP"), enabledError.getMessage());

        ObjectNode archived = mapper.createObjectNode();
        archived.putArray("archivedSteps").add("BOGUS_STEP");
        IllegalArgumentException archivedError = assertThrows(IllegalArgumentException.class,
                () -> LocalProjectGraphBackend.localStepPlan(archived));
        assertTrue(archivedError.getMessage().contains("Unknown archivedSteps"), archivedError.getMessage());
        assertTrue(archivedError.getMessage().contains("BOGUS_STEP"), archivedError.getMessage());
    }

    @Test
    void schemaArtifactSurvivesUnifiedGraphSaveReload() throws Exception {
        GraphSchema schema = new GraphSchema(
                List.of(new NodeType("PERSON", "A named person.", null)),
                List.of(),
                List.of());
        Path graphPath = projectRoot.resolve("data/crawls/schema-round-trip/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        new UnifiedGraph()
                .graphId("local:test:schema-round-trip")
                .addEntity("person", "PERSON", "Ada")
                .putArtifact(CanonicalGraphSchemaArtifact.ARTIFACT_NAME,
                        CanonicalGraphSchemaArtifact.encode(mapper, schema))
                .save(graphPath);

        UnifiedGraph reloaded = UnifiedGraph.load(graphPath);
        CanonicalGraphSchemaArtifact.Value decoded = CanonicalGraphSchemaArtifact.decode(
                mapper, reloaded.artifact(CanonicalGraphSchemaArtifact.ARTIFACT_NAME));
        assertEquals(schema.getNodeTypes().size(), decoded.schema().getNodeTypes().size());
        assertEquals(List.of(), decoded.schema().getRelationshipTypes());
        assertEquals(List.of(), decoded.schema().getPatterns());
    }

    @Test
    void legacyGraphWithoutSchemaArtifactIsAllowedAsColdStart() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/legacy-schema/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        new UnifiedGraph().graphId("local:test:legacy-schema").save(graphPath);

        assertNull(new LocalProjectGraphBackend(mapper).loadPersistedSchemaSeed(graphPath));
    }

    @Test
    void corruptSchemaArtifactFailsBeforeSemanticExtraction() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/corrupt-schema/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        new UnifiedGraph()
                .graphId("local:test:corrupt-schema")
                .putArtifactText(CanonicalGraphSchemaArtifact.ARTIFACT_NAME, "not-json")
                .save(graphPath);

        IOException failure = assertThrows(IOException.class,
                () -> new LocalProjectGraphBackend(mapper).loadPersistedSchemaSeed(graphPath));
        assertTrue(failure.getMessage().contains("Invalid canonical graph schema artifact"),
                failure.getMessage());
    }

    @Test
    void nativeCrawlPublishesAndReusesCanonicalSchemaSeedAcrossJobs() throws Exception {
        saveNativeChatSettings();
        Path firstDocuments = projectRoot.resolve("schema-first");
        Path secondDocuments = projectRoot.resolve("schema-second");
        Files.createDirectories(firstDocuments);
        Files.createDirectories(secondDocuments);
        Files.writeString(firstDocuments.resolve("first.md"),
                "An old entity anchors the first corpus vocabulary; old rel connects the entities.\n",
                StandardCharsets.UTF_8);
        Files.writeString(secondDocuments.resolve("second.md"),
                "A new entity extends the retained corpus vocabulary.\n", StandardCharsets.UTF_8);

        List<String> structuredPrompts = new CopyOnWriteArrayList<>();
        List<String> textPrompts = new CopyOnWriteArrayList<>();
        AtomicInteger capabilityChecks = new AtomicInteger();
        NativeChatCompletion bridge = new NativeChatCompletion() {
            @Override
            public boolean supportsStructuredChat(String provider, String model, String thinking) {
                assertEquals("custom", provider);
                assertEquals("fixture-model", model);
                capabilityChecks.incrementAndGet();
                return true;
            }

            @Override
            public String completeStructuredJson(
                    String provider, String model, String thinking, String prompt,
                    String system, Map<String, Object> schema, Duration timeout) throws Exception {
                structuredPrompts.add(prompt);
                return nativeSchemaResponse(schema, prompt);
            }

            @Override
            public String complete(String provider, String model, String prompt,
                                   String system, Duration timeout) {
                assertFalse(prompt.contains("schema type design")
                                || prompt.contains("schema type consolidation"),
                        "schema prepass must stay on the structured native bridge");
                textPrompts.add(prompt);
                String entityType = prompt.contains("NEW_ENTITY") ? "NEW_ENTITY" : "OLD_ENTITY";
                return nativeFacts(entityType);
            }
        };

        LocalProjectGraphBackend graphBackend = new LocalProjectGraphBackend(
                mapper, new ProjectLocalLearningSubprocessExecutor(mapper), bridge);
        CrawlDocumentsTool crawl = new CrawlDocumentsTool(
                (String) null, mapper, new LocalProjectCrawlBackend(mapper, graphBackend));

        ToolResult first = crawl.execute(
                nativeSchemaCrawlRequest("schema-first", "schema-reuse"), context);
        assertFalse(first.isError(), first.getOutput());
        assertEquals("COMPLETED", first.getMetadata().get("status"), first.getMetadata().toString());
        assertTrue(((Number) first.getMetadata().get("semanticEntityCount")).intValue() > 0,
                first.getMetadata().toString());
        assertTrue(structuredPrompts.size() > 0, "first crawl must derive its schema through JSON bridge");
        assertTrue(textPrompts.size() > 0, "first crawl must perform semantic extraction");

        Path graphPath = projectRoot.resolve("data/crawls/schema-reuse/graph.kgraph");
        CanonicalGraphSchemaArtifact.Value firstArtifact = readSchemaArtifact(graphPath);
        assertTrue(firstArtifact.schema().getAllNodeLabels().contains("OLD_ENTITY"));
        assertEquals("CONCEPT", firstArtifact.schema().getNodeParentTypes().get("OLD_ENTITY"));
        assertEquals("REFERENCE",
                firstArtifact.schema().getRelationshipConnectionFamilies().get("OLD_REL"));
        assertEquals(CrawlOntology.contentFingerprint(firstArtifact.schema()), firstArtifact.fingerprint());
        assertMatchingEntityFingerprint(graphPath, "OLD_ENTITY", firstArtifact.fingerprint());

        int structuredBeforeSecond = structuredPrompts.size();
        int textBeforeSecond = textPrompts.size();
        ToolResult second = crawl.execute(
                nativeSchemaCrawlRequest("schema-second", "schema-reuse"), context);
        assertFalse(second.isError(), second.getOutput());
        assertEquals("COMPLETED", second.getMetadata().get("status"), second.getMetadata().toString());
        assertTrue(structuredPrompts.size() > structuredBeforeSecond,
                "second crawl must run a new schema prepass rather than silently reusing facts");
        assertTrue(textPrompts.size() > textBeforeSecond,
                "second crawl must perform semantic extraction through the text bridge");
        assertTrue(capabilityChecks.get() >= 2, "each crawl must prove the selected native capability");

        List<String> secondPrompts = structuredPrompts.subList(
                structuredBeforeSecond, structuredPrompts.size());
        assertTrue(secondPrompts.stream().anyMatch(prompt -> prompt.contains("OLD_ENTITY")),
                "persisted node seed must be visible to the second schema prepass");
        assertTrue(secondPrompts.stream().anyMatch(prompt -> prompt.contains("OLD_REL")),
                "persisted relationship seed must be visible to the second schema prepass");

        CanonicalGraphSchemaArtifact.Value secondArtifact = readSchemaArtifact(graphPath);
        assertTrue(secondArtifact.schema().getAllNodeLabels().containsAll(
                List.of("OLD_ENTITY", "NEW_ENTITY")));
        assertEquals("CONCEPT", secondArtifact.schema().getNodeParentTypes().get("OLD_ENTITY"),
                "seeded type parent must not be redefined by the additive prepass");
        assertEquals("REFERENCE",
                secondArtifact.schema().getRelationshipConnectionFamilies().get("OLD_REL"),
                "seeded relationship family must not be redefined by the additive prepass");
        assertEquals(CrawlOntology.contentFingerprint(secondArtifact.schema()),
                secondArtifact.fingerprint());
        assertMatchingEntityFingerprint(graphPath, "NEW_ENTITY", secondArtifact.fingerprint());
    }

    @Test
    void exactLocalQueryUsesBoundedCompactArchiveNeighborhood() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/kb-55/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        new UnifiedGraph()
                .graphId("local:test:kb-55")
                .factSheetId(55L)
                .addEntity("a", "CODE_SYMBOL", "A")
                .addEntity("b", "CODE_SYMBOL", "B")
                .addEntity("isolated", "CODE_SYMBOL", "Unused")
                .addRelation("a-b", "a", "b", "CALLS", 1.0)
                .saveCompact(graphPath);
        ObjectNode request = mapper.createObjectNode()
                .put("operation", "NEIGHBORS")
                .put("entityId", "a")
                .put("factSheetId", 55);

        JsonNode result = new LocalProjectGraphBackend(mapper).reasoningQuery(request, context);

        assertEquals("OK", result.path("status").asText());
        assertEquals(1, result.path("entities").size());
        assertEquals("b", result.path("entities").get(0).path("id").asText());
        assertEquals(1, result.path("relations").size());
        assertFalse(result.toString().contains("isolated"));
    }

    @Test
    void knowledgeBaseSelectorScopesPosteriorAndRejectsUnknownOrAmbiguousSelectors() throws Exception {
        LocalProjectCrawlBackend crawlBackend = new LocalProjectCrawlBackend(mapper);
        String defaultKnowledgeBase = crawlBackend.defaultKnowledgeBaseId(projectRoot);
        writeConfidenceGraph(defaultKnowledgeBase, 0.5, "Default fact");
        writeConfidenceGraph("scope-a", 0.2, "Alpha fact");
        writeConfidenceGraph("scope-b", 0.8, "Beta fact");

        GraphBayesTool tool = new GraphBayesTool(null, mapper);
        ObjectNode scopedA = mapper.createObjectNode()
                .put("action", "query")
                .put("node_id", "shared")
                .put("knowledgeBase", "scope-a");
        ObjectNode scopedB = scopedA.deepCopy().put("knowledgeBase", "scope-b");
        ObjectNode defaultRequest = scopedA.deepCopy();
        defaultRequest.remove("knowledgeBase");

        JsonNode posteriorA = mapper.readTree(tool.execute(scopedA, context).getOutput());
        JsonNode posteriorB = mapper.readTree(tool.execute(scopedB, context).getOutput());
        JsonNode defaultPosterior = mapper.readTree(tool.execute(defaultRequest, context).getOutput());
        assertEquals(0.2, posteriorA.path("posterior").asDouble(), 1.0e-12);
        assertEquals(0.8, posteriorB.path("posterior").asDouble(), 1.0e-12);
        assertEquals(0.5, defaultPosterior.path("posterior").asDouble(), 1.0e-12);

        ObjectNode unknown = scopedA.deepCopy().put("knowledgeBase", "missing-scope");
        ToolResult unknownResult = tool.execute(unknown, context);
        assertTrue(unknownResult.isError(), unknownResult.getOutput());
        assertTrue(unknownResult.getOutput().contains("No project-local graph"), unknownResult.getOutput());

        ObjectNode ambiguous = scopedA.deepCopy().put("factSheetId", 7);
        ToolResult ambiguousResult = tool.execute(ambiguous, context);
        assertTrue(ambiguousResult.isError(), ambiguousResult.getOutput());
        assertTrue(ambiguousResult.getOutput().contains("mutually exclusive"), ambiguousResult.getOutput());
    }

    private void writeConfidenceGraph(String knowledgeBase, double confidence, String fact) throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls").resolve(knowledgeBase).resolve("graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        UnifiedGraph graph = new UnifiedGraph().graphId("local:test:" + knowledgeBase);
        graph.addEntity(GraphEntity.builder("shared").type("FACT").label("Shared").confidence(confidence)
                .attribute("fact", fact).build());
        graph.save(graphPath);
    }

    private void saveNativeChatSettings() throws Exception {
        Path settings = Files.createDirectories(projectRoot.resolve(".kompile"))
                .resolve("chat-config.json");
        mapper.writeValue(settings.toFile(), Map.of(
                "provider", "custom",
                "apiKey", "fixture-only-secret",
                "model", "fixture-model",
                "baseUrl", "http://127.0.0.1:1/v1"));
    }

    private ObjectNode nativeSchemaCrawlRequest(String documents, String knowledgeBase) {
        ObjectNode request = mapper.createObjectNode()
                .put("async", false)
                .put("strictSteps", true)
                .put("deriveOntology", false);
        request.putArray("documents").addObject().put("path", documents);
        request.putObject("knowledgeBase").put("name", knowledgeBase);
        request.putArray("steps").add("LOADING").add("MARKDOWN_EXTRACTION")
                .add("CHUNKING").add("LEXICAL_INDEX").add("GRAPH_EXTRACTION");
        request.putObject("embeddingTraining").put("enabled", false);
        request.putObject("reasoningLearning").put("enabled", false);
        request.putObject("graphExtraction")
                .put("llmProvider", "chat:custom")
                .put("modelName", "fixture-model")
                .put("extractionMode", "SINGLE_PASS")
                .put("schemaMode", "LENIENT")
                .put("entityResolution", false)
                .put("minConfidence", 0.0);
        return request;
    }

    private String nativeSchemaResponse(Map<String, Object> schema, String prompt) throws Exception {
        if (schemaProperty(schema, "s")) {
            int marker = prompt.lastIndexOf("BINDING_OPTION_IDS_JSON=");
            if (marker < 0) return mapper.writeValueAsString(Map.of("s", "0"));
            try {
                Map<?, ?> options = mapper.readValue(
                        prompt.substring(marker + "BINDING_OPTION_IDS_JSON=".length()).trim(), Map.class);
                List<?> relationships = (List<?>) options.get("relationshipIds");
                List<?> endpoints = (List<?>) options.get("endpointIds");
                List<?> evidence = (List<?>) options.get("evidenceIds");
                if (relationships == null || relationships.isEmpty()
                        || endpoints == null || endpoints.isEmpty()
                        || evidence == null || evidence.isEmpty()) {
                    return mapper.writeValueAsString(Map.of("s", "0"));
                }
                // The endpoint-signature contract uses 1-based positions, not literal labels.
                String packed = "1|1|1|1";
                return mapper.writeValueAsString(Map.of("s", packed));
            } catch (Exception invalidOptions) {
                throw new AssertionError("native fixture could not parse endpoint options", invalidOptions);
            }
        }
        if (schemaProperty(schema, "nodeTypes")) {
            boolean persisted = prompt.contains(
                    "Only add missing node types; never repeat or redefine these frozen node types")
                    && prompt.contains("OLD_ENTITY");
            String label = persisted ? "NEW_ENTITY" : "OLD_ENTITY";
            return mapper.writeValueAsString(Map.of("nodeTypes", List.of(
                    Map.of("label", label, "parentType", "CONCEPT"))));
        }
        if (schemaProperty(schema, "relationshipTypes")) {
            boolean persisted = prompt.contains(
                    "Only add missing relationship types; never repeat or redefine these frozen relationship types")
                    && prompt.contains("OLD_REL");
            List<Map<String, String>> values = persisted ? List.of() : List.of(
                    Map.of("type", "OLD_REL", "connectionFamily", "REFERENCE"));
            return mapper.writeValueAsString(Map.of("relationshipTypes", values));
        }
        throw new AssertionError("unexpected native schema bridge contract: " + schema);
    }

    private static boolean schemaProperty(Map<String, Object> schema, String property) {
        Object properties = schema == null ? null : schema.get("properties");
        return properties instanceof Map<?, ?> values && values.containsKey(property);
    }

    private static String nativeFacts(String entityType) {
        return "{\"$schema\":\"kompile-graph-extraction/v1\",\"entities\":["
                + "{\"id\":\"left\",\"name\":\"Left\",\"type\":\"" + entityType
                + "\",\"confidence\":0.95},"
                + "{\"id\":\"right\",\"name\":\"Right\",\"type\":\"" + entityType
                + "\",\"confidence\":0.94}],\"relations\":[]}";
    }

    private CanonicalGraphSchemaArtifact.Value readSchemaArtifact(Path graphPath) throws Exception {
        UnifiedGraph graph = UnifiedGraph.load(graphPath);
        return CanonicalGraphSchemaArtifact.decode(
                mapper, graph.artifact(CanonicalGraphSchemaArtifact.ARTIFACT_NAME));
    }

    private static void assertMatchingEntityFingerprint(
            Path graphPath, String entityType, String expectedFingerprint) throws Exception {
        UnifiedGraph graph = UnifiedGraph.load(graphPath);
        GraphEntity entity = graph.entities().stream()
                .filter(candidate -> entityType.equals(candidate.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing semantic fact type " + entityType));
        assertEquals(expectedFingerprint, entity.attributes().get("schema.fingerprint"));
    }

    @Test
    void localBayesianQueryUsesEvidenceAndWhatIfDoesNotMutateGraph() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/bayes-analytic/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        UnifiedGraph graph = new UnifiedGraph().graphId("local:test:bayes-analytic")
                .addEntity(GraphEntity.builder("cause").type("FACT").label("Cause").confidence(0.8).build())
                .addEntity(GraphEntity.builder("effect").type("FACT").label("Effect").confidence(0.2).build())
                .addRelation(GraphRelation.builder("cause-effect", "cause", "effect")
                        .type("CAUSES").weight(0.9).confidence(1.0).directed(true).build());
        graph.saveCompact(graphPath);

        GraphBayesTool tool = new GraphBayesTool(null, mapper);
        ObjectNode query = mapper.createObjectNode().put("action", "query")
                .put("node_id", "effect").put("knowledgeBase", "bayes-analytic");
        query.putArray("seed_node_ids").add("cause").add("effect");
        query.put("max_depth", 1).put("max_nodes", 2);
        ToolResult priorResult = tool.execute(query, context);
        assertFalse(priorResult.isError(), priorResult.getOutput());
        JsonNode priorJson = mapper.readTree(priorResult.getOutput());
        double prior = priorJson.path("prior").asDouble();

        ObjectNode evidenceQuery = query.deepCopy();
        evidenceQuery.putObject("evidence").put("cause", 1);
        ToolResult evidenceResult = tool.execute(evidenceQuery, context);
        assertFalse(evidenceResult.isError(), evidenceResult.getOutput());
        JsonNode evidenceJson = mapper.readTree(evidenceResult.getOutput());
        assertTrue(evidenceJson.path("posterior").asDouble() > prior, evidenceJson.toString());
        assertEquals("VariableElimination", evidenceJson.path("inferenceAlgorithm").asText());

        ObjectNode whatIf = evidenceQuery.deepCopy().put("action", "whatif");
        whatIf.remove("evidence");
        whatIf.putObject("hypothetical_evidence").put("cause", 1);
        ToolResult whatIfResult = tool.execute(whatIf, context);
        assertFalse(whatIfResult.isError(), whatIfResult.getOutput());
        JsonNode whatIfJson = mapper.readTree(whatIfResult.getOutput());
        assertTrue(whatIfJson.path("posteriors").path("v1").asDouble() > prior, whatIfJson.toString());
        assertEquals(1, UnifiedGraph.load(graphPath).relationCount(), "what-if must not mutate topology");
    }

    @Test
    void localBayesianMpeReportsJointMaxProductResult() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/bayes-mpe/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        new UnifiedGraph().graphId("local:test:bayes-mpe")
                .addEntity(GraphEntity.builder("a").type("FACT").label("A").confidence(0.7).build())
                .addEntity(GraphEntity.builder("b").type("FACT").label("B").confidence(0.6).build())
                .addRelation(GraphRelation.builder("a-b", "a", "b").type("CAUSES")
                        .weight(0.8).confidence(1.0).directed(true).build())
                .saveCompact(graphPath);

        ObjectNode request = mapper.createObjectNode().put("action", "mpe")
                .put("knowledgeBase", "bayes-mpe").put("max_depth", 1).put("max_nodes", 2);
        ToolResult result = new GraphBayesTool(null, mapper).execute(request, context);
        assertFalse(result.isError(), result.getOutput());
        JsonNode json = mapper.readTree(result.getOutput());
        assertTrue(json.path("mpeStates").isObject(), json.toString());
        assertTrue(json.path("assignment").isObject(), json.toString());
        assertTrue(json.has("jointProbability"), json.toString());
        assertTrue(json.has("probabilityGivenEvidence"), json.toString());
        assertFalse(json.path("method").asText().contains("confidence propagation"));
    }

    @Test
    void owlRlVerificationSupportsTransitiveTwoHopAndInvalidatesAfterRetraction() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/owl-verify/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        String ontology = "@prefix ex: <urn:test#> .\n"
                + "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "ex:PART_OF a owl:ObjectProperty , owl:TransitiveProperty .\n";
        new UnifiedGraph().graphId("local:test:owl-verify")
                .putArtifactText("ontology.ttl", ontology)
                .addEntity("a", "THING", "A").addEntity("b", "THING", "B")
                .addEntity("c", "THING", "C")
                .addRelation(GraphRelation.builder("a-b", "a", "b").type("PART_OF")
                        .confidence(1.0).directed(true).build())
                .addRelation(GraphRelation.builder("b-c", "b", "c").type("PART_OF")
                        .confidence(1.0).directed(true).build())
                .saveCompact(graphPath);

        LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);
        JsonNode supported = mapper.readTree(backend.executeOfflineTool("ask_graph_verify",
                mapper.createObjectNode().put("knowledgeBase", "owl-verify")
                        .put("atom", "PART_OF(a, c)"), context).getOutput());
        assertEquals("SUPPORTED", supported.path("verdict").asText(), supported.toString());
        assertTrue(supported.path("supportingFactKeys").size() >= 2, supported.toString());
        boolean hasTransitiveRule = false;
        for (JsonNode rule : supported.path("activatedRules")) {
            hasTransitiveRule |= rule.asText().contains("prp-trp");
        }
        assertTrue(hasTransitiveRule, supported.toString());

        JsonNode reverse = mapper.readTree(backend.executeOfflineTool("ask_graph_verify",
                mapper.createObjectNode().put("knowledgeBase", "owl-verify")
                        .put("atom", "PART_OF(c, a)"), context).getOutput());
        assertEquals("UNKNOWN", reverse.path("verdict").asText(), reverse.toString());

        ToolResult explanation = backend.executeOfflineTool("ask_graph_explain",
                mapper.createObjectNode().put("knowledgeBase", "owl-verify")
                        .put("atom", "PART_OF(a, c)"), context);
        assertFalse(explanation.isError(), explanation.getOutput());
        assertTrue(explanation.getOutput().contains("Supporting fact keys"), explanation.getOutput());

        ToolResult retracted = backend.executeOfflineTool("ask_graph_retract",
                mapper.createObjectNode().put("knowledgeBase", "owl-verify")
                        .put("atomKey", "PART_OF(b, c)"), context);
        assertFalse(retracted.isError(), retracted.getOutput());
        JsonNode afterRetraction = mapper.readTree(backend.executeOfflineTool("ask_graph_verify",
                mapper.createObjectNode().put("knowledgeBase", "owl-verify")
                        .put("atom", "PART_OF(a, c)"), context).getOutput());
        assertEquals("UNKNOWN", afterRetraction.path("verdict").asText(), afterRetraction.toString());
    }

    @Test
    void knowledgeBaseSelectorScopesEmbeddingTrainingAndScore() throws Exception {
        writeEmbeddingGraph("embed-a", "tail");
        writeEmbeddingGraph("embed-b", "other");

        GraphEmbeddingsTool tool = new GraphEmbeddingsTool(null, mapper);
        ObjectNode trainA = mapper.createObjectNode()
                .put("action", "train")
                .put("knowledgeBase", "embed-a")
                .put("algorithm", "TRANSE")
                .put("embedding_dim", 2)
                .put("epochs", 1);
        ToolResult trainedA = tool.execute(trainA, context);
        assertFalse(trainedA.isError(), trainedA.getOutput());
        assertNotNull(UnifiedGraph.load(projectRoot.resolve("data/crawls/embed-a/graph.kgraph"))
                .artifact(LocalProjectGraphBackend.MODEL_ARTIFACT));
        assertTrue(UnifiedGraph.load(projectRoot.resolve("data/crawls/embed-b/graph.kgraph"))
                .artifact(LocalProjectGraphBackend.MODEL_ARTIFACT) == null);

        ObjectNode scoreA = mapper.createObjectNode()
                .put("action", "score")
                .put("knowledgeBase", "embed-a")
                .put("head", "shared")
                .put("relation", "LINKS")
                .put("tail", "tail");
        ToolResult scoredA = tool.execute(scoreA, context);
        assertFalse(scoredA.isError(), scoredA.getOutput());

        ObjectNode scoreB = scoreA.deepCopy().put("knowledgeBase", "embed-b");
        ToolResult untrainedB = tool.execute(scoreB, context);
        assertTrue(untrainedB.isError(), untrainedB.getOutput());
        assertTrue(untrainedB.getOutput().contains("No trained embeddings"), untrainedB.getOutput());

        ObjectNode trainB = trainA.deepCopy().put("knowledgeBase", "embed-b");
        ToolResult trainedB = tool.execute(trainB, context);
        assertFalse(trainedB.isError(), trainedB.getOutput());
        ToolResult scoredB = tool.execute(scoreB, context);
        assertFalse(scoredB.isError(), scoredB.getOutput());
    }

    private void writeEmbeddingGraph(String knowledgeBase, String relationTarget) throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls").resolve(knowledgeBase).resolve("graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        UnifiedGraph graph = new UnifiedGraph().graphId("local:test:" + knowledgeBase)
                .addEntity("shared", "FACT", "Shared")
                .addEntity("tail", "FACT", "Tail")
                .addEntity("other", "FACT", "Other")
                .addRelation("link", "shared", relationTarget, "LINKS", 1.0);
        graph.save(graphPath);
    }

    @Test
    void localGraphSearchRanksNaturalLanguageAndReturnsLabeledRelations() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/code-search/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        UnifiedGraph graph = new UnifiedGraph().graphId("local:test:code-search");
        graph.addEntity(GraphEntity.builder("service")
                .type("CLASS").label("CodeGraphService")
                .attribute("description", "Builds and searches the local code index")
                .attribute("codeProjectId", "app").build());
        graph.addEntity(GraphEntity.builder("method")
                .type("METHOD").label("buildIndex")
                .attribute("codeProjectId", "app").build());
        graph.addEntity(GraphEntity.builder("unrelated")
                .type("CLASS").label("PathfinderRules")
                .attribute("codeProjectId", "app").build());
        graph.addRelation("contains", "service", "method", "CONTAINS", 1.0);
        graph.save(graphPath);

        ObjectNode request = mapper.createObjectNode()
                .put("query", "local code index")
                .put("search_type", "local")
                .put("knowledgeBase", "code-search")
                .put("max_results", 5);
        ToolResult toolResult = new LocalProjectGraphBackend(mapper)
                .executeOfflineTool("graph_search", request, context);
        assertFalse(toolResult.isError(), toolResult.getOutput());
        JsonNode result = mapper.readTree(toolResult.getOutput());

        assertEquals("LOCAL", result.path("searchType").asText());
        assertEquals("lexical + stored entity prior", result.path("ranking").asText());
        assertFalse(result.path("inferenceInvoked").asBoolean(true));
        assertEquals("lexical + stored entity prior",
                result.path("data").path("scoreBasis").asText());
        assertEquals("clamp01(weight * confidence)",
                result.path("data").path("storedPrior").asText());
        assertFalse(result.path("data").path("inferenceInvoked").asBoolean(true));
        assertEquals("service", result.path("entities").get(0).path("id").asText());
        assertTrue(result.path("entities").get(0).path("score").asDouble() > 0.0);
        assertEquals("CodeGraphService",
                result.path("relationships").get(0).path("sourceName").asText());
        assertEquals("buildIndex",
                result.path("relationships").get(0).path("targetName").asText());
        assertFalse(result.toString().contains("PathfinderRules"));
    }

    @Test
    void localFusedExplanationReportsRetrievalWithoutInventingConfidence() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/fused-evidence/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        new UnifiedGraph().graphId("local:test:fused-evidence")
                .addEntity("service", "CLASS", "LocalCodeIndex")
                .save(graphPath);
        LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);

        for (String target : List.of("LocalCodeIndex", "unknown-no-evidence")) {
            ObjectNode request = mapper.createObjectNode()
                    .put("target", target)
                    .put("knowledgeBase", "fused-evidence");
            ToolResult result = backend.executeOfflineTool("ask_graph_explain_fused", request, context);
            assertFalse(result.isError(), result.getOutput());
            JsonNode json = mapper.readTree(result.getOutput());
            assertEquals("RETRIEVAL_ONLY", json.path("status").asText());
            assertTrue(json.path("fusedConfidence").isNull(), json.toString());
            assertEquals(1, json.path("modalityCount").asInt());
            assertTrue(json.path("caveat").asText().contains("no concurrent reasoning-engine fusion"));
            JsonNode evidence = mapper.readTree(json.path("naturalLanguageAnswer").asText());
            assertEquals("local:test:fused-evidence", evidence.path("graphId").asText());
            assertEquals(target.equals("LocalCodeIndex") ? 1 : 0, evidence.path("entities").size());
        }

        assertTrue(backend.executeOfflineTool("ask_graph_explain_fused",
                mapper.createObjectNode(), context).isError());
    }

    @Test
    void hybridGraphSearchExpandsNeighborsAndHonorsCodeProjectFilter() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/code-hybrid/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        UnifiedGraph graph = new UnifiedGraph().graphId("local:test:code-hybrid");
        graph.addEntity(GraphEntity.builder("service")
                .type("CLASS").label("LocalCodeIndex")
                .attribute("codeProjectId", "app").build());
        graph.addEntity(GraphEntity.builder("helper")
                .type("METHOD").label("refreshProjection")
                .attribute("codeProjectId", "app").build());
        graph.addEntity(GraphEntity.builder("duplicate")
                .type("CLASS").label("LocalCodeIndex")
                .attribute("codeProjectId", "nested").build());
        graph.addRelation("calls", "service", "helper", "CALLS", 1.0);
        graph.save(graphPath);

        ObjectNode request = mapper.createObjectNode()
                .put("query", "local code index")
                .put("search_type", "hybrid")
                .put("knowledgeBase", "code-hybrid")
                .put("code_project_id", "app")
                .put("max_results", 4);
        ToolResult toolResult = new LocalProjectGraphBackend(mapper)
                .executeOfflineTool("graph_search", request, context);
        assertFalse(toolResult.isError(), toolResult.getOutput());
        JsonNode result = mapper.readTree(toolResult.getOutput());

        assertEquals("HYBRID", result.path("searchType").asText());
        assertEquals("lexical + stored entity prior", result.path("ranking").asText());
        assertFalse(result.path("inferenceInvoked").asBoolean(true));
        assertFalse(result.path("data").path("inferenceInvoked").asBoolean(true));
        assertEquals(1, result.path("expansionDepth").asInt());
        assertTrue(result.path("entities").findValuesAsText("id").contains("helper"), result.toString());
        assertFalse(result.path("entities").findValuesAsText("id").contains("duplicate"), result.toString());
    }

    @Test
    void globalGraphSearchExpandsTwoHopsWithoutInference() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/code-global/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        UnifiedGraph graph = new UnifiedGraph().graphId("local:test:code-global");
        graph.addEntity(GraphEntity.builder("root")
                .type("CLASS").label("LocalCodeIndex")
                .attribute("codeProjectId", "app").build());
        graph.addEntity(GraphEntity.builder("helper")
                .type("METHOD").label("refreshProjection")
                .attribute("codeProjectId", "app").build());
        graph.addEntity(GraphEntity.builder("leaf")
                .type("FIELD").label("projectionLeaf")
                .attribute("codeProjectId", "app").build());
        graph.addEntity(GraphEntity.builder("unrelated")
                .type("CLASS").label("UnrelatedNode")
                .attribute("codeProjectId", "app").build());
        graph.addRelation("root-helper", "root", "helper", "CALLS", 1.0);
        graph.addRelation("helper-leaf", "helper", "leaf", "USES", 1.0);
        graph.save(graphPath);

        ObjectNode request = mapper.createObjectNode()
                .put("query", "local code index")
                .put("search_type", "global")
                .put("knowledgeBase", "code-global")
                .put("code_project_id", "app")
                .put("max_results", 4);
        ToolResult toolResult = new LocalProjectGraphBackend(mapper)
                .executeOfflineTool("graph_search", request, context);
        assertFalse(toolResult.isError(), toolResult.getOutput());
        JsonNode result = mapper.readTree(toolResult.getOutput());

        assertEquals("GLOBAL", result.path("searchType").asText());
        assertEquals(2, result.path("expansionDepth").asInt());
        assertEquals("lexical + stored entity prior", result.path("ranking").asText());
        assertFalse(result.path("inferenceInvoked").asBoolean(true));
        assertFalse(result.path("data").path("inferenceInvoked").asBoolean(true));
        assertTrue(result.path("entities").findValuesAsText("id").containsAll(
                List.of("root", "helper", "leaf")), result.toString());
        assertFalse(result.path("entities").findValuesAsText("id").contains("unrelated"), result.toString());
        assertEquals(2, result.path("relationships").size());
    }

    @Test
    void localListEdgesHonorsManagedNodeIdContract() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/edge-filter/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        new UnifiedGraph().graphId("local:test:edge-filter")
                .addEntity("a", "CLASS", "A")
                .addEntity("b", "CLASS", "B")
                .addEntity("c", "CLASS", "C")
                .addEntity("d", "CLASS", "D")
                .addRelation("a-b", "a", "b", "CALLS", 1.0)
                .addRelation("c-d", "c", "d", "CALLS", 1.0)
                .save(graphPath);

        ObjectNode request = mapper.createObjectNode()
                .put("action", "list_edges")
                .put("node_id", "a")
                .put("knowledgeBase", "edge-filter");
        ToolResult result = new LocalProjectGraphBackend(mapper).knowledgeGraph(request, context);

        assertFalse(result.isError(), result.getOutput());
        JsonNode json = mapper.readTree(result.getOutput());
        assertEquals(1, json.path("count").asInt());
        assertEquals("a-b", json.path("edges").get(0).path("id").asText());
    }

    @Test
    void embeddingSimilarityReportsMissingSourceVectorInsteadOfZeroScoreNoise() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/missing-vector/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        UnifiedGraph graph = new UnifiedGraph().graphId("local:test:missing-vector");
        graph.addEntity(GraphEntity.builder("source")
                .type("CLASS").label("UntrainedCodeNode")
                .attribute("codeProjectId", "app").build());
        graph.addEntity(GraphEntity.builder("other")
                .type("CHUNK").label("Unrelated document").build());
        graph.putVectorLayer(new VectorLayer("kge", VectorLayer.Target.ENTITY, 2, Dtype.F64)
                .put("other", new double[]{0.2, 0.8}));
        graph.putVectorLayer(new VectorLayer("kge-relations", VectorLayer.Target.RELATION, 2, Dtype.F64)
                .put("CALLS", new double[]{0.1, 0.9}));
        graph.putArtifactText("models/kge.json",
                "{\"algorithm\":\"TRANSE\",\"embeddingDim\":2}");
        graph.meta("phase.codeLearning.app", "STALE");
        graph.save(graphPath);

        ObjectNode request = mapper.createObjectNode()
                .put("action", "similar")
                .put("entity_name", "UntrainedCodeNode")
                .put("knowledgeBase", "missing-vector");
        ToolResult result = new LocalProjectGraphBackend(mapper).embeddings(request, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("No learned KGE vector"), result.getOutput());
        assertEquals(false, result.getMetadata().get("vectorAvailable"));
        assertEquals(0, result.getMetadata().get("count"));
        assertEquals("STALE", result.getMetadata().get("codeLearningStatus"));
    }

    @Test
    void embeddingSimilarityReportsMissingVectorWhenGraphHasNoKgeModel() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls/no-kge-model/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        UnifiedGraph graph = new UnifiedGraph().graphId("local:test:no-kge-model");
        graph.addEntity(GraphEntity.builder("source")
                .type("CLASS").label("UntrainedCodeNode")
                .attribute("codeProjectId", "app").build());
        graph.meta("phase.codeLearning.app", "STALE");
        graph.save(graphPath);

        ObjectNode request = mapper.createObjectNode()
                .put("action", "similar")
                .put("entity_name", "UntrainedCodeNode")
                .put("knowledgeBase", "no-kge-model");
        ToolResult result = new LocalProjectGraphBackend(mapper).embeddings(request, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("No learned KGE vector"), result.getOutput());
        assertEquals(false, result.getMetadata().get("vectorAvailable"));
        assertEquals(0, result.getMetadata().get("count"));
        assertEquals("STALE", result.getMetadata().get("codeLearningStatus"));
    }

    @Test
    void stripsOnlyGeneratedProjectCrawlFrontMatterFromSemanticText() {
        String generated = """
                ---
                title: "astronomy-en.txt"
                converter: kompile-project-crawl
                crawl_profile: "topic-smoke"
                collection: "topic-smoke"
                ---

                # Astronomy
                Astronomers observed a distant galaxy.
                """;
        assertEquals("# Astronomy\nAstronomers observed a distant galaxy.\n",
                LocalProjectGraphBackend.stripGeneratedCrawlFrontMatter(generated));

        String authored = """
                ---
                title: User-authored note
                domain: astronomy
                ---
                The front matter is part of the source document.
                """;
        assertEquals(authored, LocalProjectGraphBackend.stripGeneratedCrawlFrontMatter(authored));
        assertEquals("plain text", LocalProjectGraphBackend.stripGeneratedCrawlFrontMatter("plain text"));
    }

    @Test
    void crawlsReasonsTrainsExportsAndIncrementallyRemovesDocumentsLocally() throws Exception {
        Path docs = projectRoot.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("retained.md"),
                "# Retained\nThe aurora compass points toward the northern archive.\n",
                StandardCharsets.UTF_8);
        Files.writeString(docs.resolve("removed.md"),
                "# Removed\nThe obsolete scarlet beacon is scheduled for deletion.\n",
                StandardCharsets.UTF_8);

        ObjectNode request = mapper.createObjectNode();
        request.put("async", false);
        request.putArray("documents").addObject().put("path", "docs");
        request.putObject("knowledgeBase").put("id", 41);
        request.putArray("steps").add("ENRICHMENT");
        request.put("strictSteps", true);
        request.putObject("embeddingTraining")
                .put("enabled", true)
                .put("algorithm", "TRANSE")
                .put("embeddingDim", 8)
                .put("epochs", 2);

        CrawlDocumentsTool crawlTool = new CrawlDocumentsTool((String) null, mapper);
        ToolResult first = crawlTool.execute(request, context);
        assertFalse(first.isError(), first.getOutput());
        assertEquals("project-local", first.getMetadata().get("backend"));
        assertTrue(((Number) first.getMetadata().get("graphEntityCount")).intValue() >= 6);
        assertTrue(((Number) first.getMetadata().get("graphRelationCount")).intValue() >= 5);
        assertTrue(((Number) first.getMetadata().get("embeddingVectorCount")).intValue() > 0);
        assertEquals(true, first.getMetadata().get("enrichmentRequested"));
        assertEquals(true, first.getMetadata().get("reasoningLearningEnabled"));

        Path graphPath = projectRoot.resolve("data/crawls/kb-41/graph.kgraph");
        assertTrue(Files.isRegularFile(graphPath));
        UnifiedGraph firstGraph = UnifiedGraph.load(graphPath);
        assertEquals(41L, firstGraph.factSheetId());
        assertTrue(firstGraph.entityCount() >= 6);
        assertTrue(firstGraph.relationCount() >= 5);
        assertNotNull(firstGraph.artifact(LocalProjectGraphBackend.MODEL_ARTIFACT));
        assertTrue(firstGraph.vectorLayers().containsKey(LocalProjectGraphBackend.ENTITY_LAYER));
        MTheory learnedTheory = UnifiedGraphReasoningLifecycle.learnedMTheory(firstGraph);
        assertNotNull(learnedTheory);
        String scopedEntityId = learnedTheory.getEntityTypes().stream()
                .flatMap(type -> type.getEntityIds().stream())
                .findFirst().orElseThrow();
        MTheory scopedTheory = RelationalMTheoryArtifactCodec.restrictToEntityIds(
                learnedTheory, Set.of(scopedEntityId));
        Set<String> scopedIds = scopedTheory.getEntityTypes().stream()
                .flatMap(type -> type.getEntityIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(scopedEntityId), scopedIds);
        assertTrue(learnedTheory.getEntityTypes().stream()
                .flatMap(type -> type.getEntityIds().stream()).distinct().count() > 1);

        ObjectNode boundedMebn = mapper.createObjectNode();
        boundedMebn.put("nodeId", scopedEntityId);
        boundedMebn.put("factSheetId", 41);
        boundedMebn.put("maxDepth", 0);
        boundedMebn.put("maxNodes", 1);
        ToolResult boundedMebnResult = new LocalProjectGraphBackend(mapper)
                .executeOfflineTool("ask_graph_mebn", boundedMebn, context);
        assertFalse(boundedMebnResult.isError(), boundedMebnResult.getOutput());
        assertEquals(1, ((Number) boundedMebnResult.getMetadata().get("scopedEntityCount")).intValue());
        assertTrue(((Number) boundedMebnResult.getMetadata().get("totalVariables")).intValue() <= 1);

        ObjectNode query = mapper.createObjectNode();
        query.put("operation", "SEARCH");
        query.put("queryText", "aurora compass");
        query.put("factSheetId", 41);
        ToolResult queryResult = new GraphReasoningQueryTool((String) null, mapper)
                .execute(query, context);
        assertFalse(queryResult.isError(), queryResult.getOutput());
        assertTrue(queryResult.getOutput().toLowerCase().contains("aurora"), queryResult.getOutput());
        assertTrue(new LocalProjectGraphBackend(mapper).reasoningQuery(query, context)
                .path("archiveNative").asBoolean());

        ObjectNode aliases = mapper.createObjectNode();
        aliases.put("queryText", "aurora compass");
        aliases.put("question", "scarlet beacon");
        aliases.put("factSheetId", 41);
        ToolResult aliasResult = new GraphReasoningQueryTool((String) null, mapper)
                .execute(aliases, context);
        assertFalse(aliasResult.isError(), aliasResult.getOutput());
        assertTrue(aliasResult.getOutput().toLowerCase().contains("aurora"), aliasResult.getOutput());
        assertFalse(aliasResult.getOutput().toLowerCase().contains("scarlet"), aliasResult.getOutput());

        ObjectNode reason = mapper.createObjectNode();
        reason.put("target", "retained.md");
        reason.put("factSheetId", 41);
        ToolResult reasonResult = new GraphReasonTool((String) null, mapper)
                .execute(reason, context);
        assertFalse(reasonResult.isError(), reasonResult.getOutput());
        assertEquals("project-local", reasonResult.getMetadata().get("backend"));

        ObjectNode jobs = mapper.createObjectNode();
        jobs.put("action", "jobs");
        jobs.put("fact_sheet_id", 41);
        ToolResult jobsResult = new GraphEmbeddingsTool(null, mapper).execute(jobs, context);
        assertFalse(jobsResult.isError(), jobsResult.getOutput());
        assertEquals("project-local", jobsResult.getMetadata().get("backend"));

        ObjectNode export = mapper.createObjectNode();
        export.put("path", "backup.kgraph");
        export.put("format", "kgraph");
        export.put("factSheetId", 41);
        ToolResult exportResult = new GraphExportTool((String) null, mapper)
                .execute(export, context);
        assertFalse(exportResult.isError(), exportResult.getOutput());
        assertTrue(Files.isRegularFile(projectRoot.resolve("backup.kgraph")));

        Files.delete(docs.resolve("removed.md"));
        ToolResult second = crawlTool.execute(request, context);
        assertFalse(second.isError(), second.getOutput());
        UnifiedGraph updated = UnifiedGraph.load(graphPath);
        assertTrue(updated.entities().stream().noneMatch(entity ->
                entity.label() != null && entity.label().contains("removed.md")));
        assertTrue(updated.entityCount() < firstGraph.entityCount());

        ObjectNode graphStats = mapper.createObjectNode();
        graphStats.put("operation", "graph_stats");
        graphStats.put("jobId", "kb-41");
        ToolResult stats = new CrawlControlTool((String) null, mapper)
                .execute(graphStats, context);
        assertFalse(stats.isError(), stats.getOutput());
        assertTrue(stats.getOutput().contains("\"available\" : true"), stats.getOutput());

        ObjectNode importRequest = mapper.createObjectNode();
        importRequest.put("path", "backup.kgraph");
        importRequest.put("factSheetId", 42);
        ToolResult importResult = new GraphImportTool((String) null, mapper)
                .execute(importRequest, context);
        assertFalse(importResult.isError(), importResult.getOutput());
        assertTrue(Files.isRegularFile(projectRoot.resolve("data/crawls/kb-42/graph.kgraph")));
    }

    @Test
    void openAiProviderAliasesResolveToTheCodexCliBackend() {
        for (String provider : List.of("openai", "openai-codex", "codex", "codex-cli")) {
            assertEquals("codex-cli", LocalProjectGraphBackend.cliAgentForProvider(provider), provider);
        }
    }

    @Test
    void localCrawlUsesProductionSemanticExtractorWithRequestScopedApiRoute() throws Exception {
        Path docs = projectRoot.resolve("semantic-docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("deal.md"),
                "Acme acquired Initech in a strategic transaction.\n", StandardCharsets.UTF_8);

        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            String extracted = """
                    {"tool":"submit_graph_delta","args":{"entities":[
                      {"id":"acme","name":"Acme","type":"ORGANIZATION","description":"Buyer","confidence":0.95},
                      {"id":"initech","name":"Initech","type":"ORGANIZATION","description":"Target","confidence":0.92}
                    ],"relations":[
                      {"source":"acme","target":"initech","type":"ACQUIRED","description":"Acquisition","confidence":0.9}
                    ]}}
                    """;
            ObjectNode response = mapper.createObjectNode();
            response.putArray("choices").addObject().putObject("message").put("content", extracted);
            byte[] body = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            ObjectNode request = mapper.createObjectNode();
        request.put("async", false);
            request.putArray("documents").addObject().put("path", "semantic-docs");
            request.putObject("knowledgeBase").put("id", 118);
            request.putObject("embeddingTraining").put("enabled", false);
            ObjectNode extraction = request.putObject("graphExtraction")
                    .put("llmProvider", "request-scoped-api")
                    .put("extractionMode", "SINGLE_PASS")
                    .put("entityResolution", false)
                    .put("minConfidence", 0.0)
                    .put("schemaMode", "STRICT");
            extraction.putArray("entityTypes").add("ORGANIZATION");
            extraction.putArray("relationshipTypes").add("ACQUIRED");
            ObjectNode route = request.putObject("processingRoute");
            route.put("fallbackEnabled", true);
            route.put("servingLaneEnabled", false);
            route.putArray("backends").addObject()
                    .put("id", "test-api")
                    .put("type", "API_AGENT")
                    .put("priority", 1)
                    .put("endpointUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .put("modelName", "test-model")
                    .putArray("capabilities").add("llm");

            ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);

            assertFalse(result.isError(), result.getOutput());
            assertEquals(2, ((Number) result.getMetadata().get("semanticEntityCount")).intValue(),
                    result.getMetadata().toString());
            assertEquals(1, ((Number) result.getMetadata().get("semanticRelationCount")).intValue(),
                    result.getMetadata().toString());
            assertEquals(List.of(), result.getMetadata().get("semanticExtractionErrors"));
            assertFalse(requests.isEmpty(), "semantic extraction must call the configured API backend");
            assertTrue(requests.stream().allMatch(capturedRequest ->
                    "test-model".equals(capturedRequest.path("model").asText())), requests.toString());
            UnifiedGraph graph = UnifiedGraph.load(
                    projectRoot.resolve("data/crawls/kb-118/graph.kgraph"));
            assertTrue(graph.entities().stream().anyMatch(entity ->
                    "ORGANIZATION".equals(entity.type()) && "Acme".equals(entity.label())));
            assertTrue(graph.relations().stream().anyMatch(relation ->
                    "ACQUIRED".equals(relation.type())));
            assertEquals("GraphExtractionOrchestrator",
                    graph.meta().get("semanticExtractionEngine"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void localCrawlRunsSharedFinalEntityResolutionLifecycleWithoutInventingChanges() throws Exception {
        Path docs = projectRoot.resolve("identity-docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("people.md"),
                "Alice Example knows Bob Example.\n",
                StandardCharsets.UTF_8);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String extracted = """
                    {"tool":"submit_graph_delta","args":{"entities":[
                      {"id":"alice","name":"Alice Example","type":"PERSON",
                       "description":"Full name stated in the source","confidence":0.92},
                      {"id":"bob","name":"Bob Example","type":"PERSON",
                       "description":"Second person stated in the source","confidence":0.94}
                    ],"relations":[
                      {"source":"alice","target":"bob","type":"KNOWS",
                       "description":"Alice Example knows Bob Example","confidence":0.98}
                    ]}}
                    """;
            ObjectNode response = mapper.createObjectNode();
            response.putArray("choices").addObject().putObject("message").put("content", extracted);
            byte[] body = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            ObjectNode request = mapper.createObjectNode();
        request.put("async", false);
            request.putArray("documents").addObject().put("path", "identity-docs");
            request.putObject("knowledgeBase").put("id", 119);
            request.putObject("embeddingTraining").put("enabled", false);
            ObjectNode extraction = request.putObject("graphExtraction")
                    .put("llmProvider", "request-scoped-api")
                    .put("extractionMode", "SINGLE_PASS")
                    .put("entityResolution", true)
                    .put("minConfidence", 0.0)
                    .put("schemaMode", "STRICT");
            extraction.putArray("entityTypes").add("PERSON");
            extraction.putArray("relationshipTypes").add("KNOWS");
            ObjectNode route = request.putObject("processingRoute");
            route.put("fallbackEnabled", true);
            route.put("servingLaneEnabled", false);
            route.putArray("backends").addObject()
                    .put("id", "test-api")
                    .put("type", "API_AGENT")
                    .put("priority", 1)
                    .put("endpointUrl",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .put("modelName", "test-model")
                    .putArray("capabilities").add("llm");

            ToolResult result =
                    new CrawlDocumentsTool((String) null, mapper).execute(request, context);

            assertFalse(result.isError(), result.getOutput());
            assertEquals(0,
                    ((Number) result.getMetadata().get("entityResolutionMergedCount")).intValue(),
                    result.getMetadata().toString());
            assertEquals(0,
                    ((Number) result.getMetadata()
                            .get("entityResolutionTypeCorrectionCount")).intValue());
            assertEquals(0,
                    ((Number) result.getMetadata()
                            .get("entityResolutionIdentifierLinkCount")).intValue());
            assertTrue(result.getMetadata().get("crawlResult").toString()
                    .contains("kompile-crawl-result/v1"));

            ToolResult crawlResult = new CrawlResultTool((String) null, mapper).execute(
                    mapper.createObjectNode().put("jobId", "kb-119"), context);
            assertFalse(crawlResult.isError(), crawlResult.getOutput());
            assertTrue(crawlResult.getMetadata().get("nextActions").toString()
                    .contains("graph_reasoning_query"));
            assertTrue(crawlResult.getMetadata().get("nextActions").toString()
                    .contains("knowledge_search"));

            UnifiedGraph graph = UnifiedGraph.load(
                    projectRoot.resolve("data/crawls/kb-119/graph.kgraph"));
            assertEquals(2, graph.entities().stream()
                    .filter(entity -> "PERSON".equals(entity.type()))
                    .count());
            assertTrue(graph.relations().stream().anyMatch(relation ->
                    "KNOWS".equals(relation.type())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void projectsTheIncrementalCodeIndexIntoTheLocalKnowledgeGraph() throws Exception {
        Path source = projectRoot.resolve("src/main/java/example/LocalService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                final class LocalService {
                    String answer() { return "local graph"; }
                }
                """, StandardCharsets.UTF_8);

        ObjectNode request = mapper.createObjectNode();
        request.put("async", false);
        request.putArray("codeProjects").add("*");
        request.putObject("knowledgeBase").put("id", 73);
        request.putObject("embeddingTraining").put("enabled", false);

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);
        assertFalse(result.isError(), result.getOutput());
        assertTrue(((Number) result.getMetadata().get("codeEntityCount")).intValue() > 0);

        UnifiedGraph graph = UnifiedGraph.load(
                projectRoot.resolve("data/crawls/kb-73/graph.kgraph"));
        assertEquals(73L, graph.factSheetId());
        assertTrue(graph.entities().stream().anyMatch(entity -> "CODE_PROJECT".equals(entity.type())));
        assertTrue(graph.entities().stream().anyMatch(entity ->
                entity.label() != null && entity.label().contains("LocalService")));
        assertTrue(graph.relations().stream().anyMatch(relation ->
                "HAS_CODE_PROJECT".equals(relation.type())));
    }

    @Test
    void assertsAndRetractsExactFactsThroughMutationJournal() throws Exception {
        Path directory = projectRoot.resolve("data/crawls/kb-55");
        Files.createDirectories(directory);
        UnifiedGraph graph = new UnifiedGraph().factSheetId(55L)
                .addEntity(ai.kompile.graph.reasoning.model.SimpleGraphEntity.of(
                        "alice", "PERSON", "Alice"))
                .addEntity(ai.kompile.graph.reasoning.model.SimpleGraphEntity.of(
                        "acme", "ORGANIZATION", "Acme"));
        Path graphPath = directory.resolve("graph.kgraph");
        graph.saveCompact(graphPath);

        LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);
        ObjectNode assertion = mapper.createObjectNode();
        assertion.put("atom", "worksFor(Alice, Acme)");
        assertion.put("value", 0.9);
        assertion.put("factSheetId", 55);
        long started = System.nanoTime();
        ToolResult asserted = backend.executeOfflineTool("ask_graph_assert", assertion, context);
        long assertionMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);
        assertFalse(asserted.isError(), asserted.getOutput());
        assertTrue(assertionMillis < 2_000, "small assertion unexpectedly took " + assertionMillis + " ms");
        assertTrue(asserted.getOutput().contains("mutationJournalRecords"), asserted.getOutput());
        assertTrue(Files.isRegularFile(ai.kompile.graph.reasoning.unified
                .UnifiedGraphMutationJournal.pathFor(graphPath)));
        UnifiedGraph afterAssert = UnifiedGraph.load(graphPath);
        assertTrue(afterAssert.relations().stream().anyMatch(relation ->
                "worksFor".equals(relation.type()) && "alice".equals(relation.sourceId())
                        && "acme".equals(relation.targetId())));
        try (var archive = ai.kompile.graph.reasoning.unified.UnifiedGraphArchive.open(graphPath)) {
            assertTrue(archive.hasAdjacencyIndex());
            assertTrue(archive.hasJournalMutations());
            assertEquals(1, archive.linkCount());
        }

        ObjectNode retraction = mapper.createObjectNode();
        retraction.put("atomKey", "worksFor(Alice, Acme)");
        retraction.put("factSheetId", 55);
        ToolResult retracted = backend.executeOfflineTool("ask_graph_retract", retraction, context);
        assertFalse(retracted.isError(), retracted.getOutput());
        assertTrue(UnifiedGraph.load(graphPath).relations().stream()
                .noneMatch(relation -> "worksFor".equals(relation.type())));
    }

    @Test
    void persistsCodeProjectFactSheetAndGraphProfileInKompileProjectManifest() throws Exception {
        Path source = projectRoot.resolve("src/main/java/example/BoundService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; final class BoundService {}\n",
                StandardCharsets.UTF_8);

        KompileCodingProject codingProject = new KompileCodingProject();
        codingProject.setId("app");
        codingProject.setCodeProjectId("app-code");
        codingProject.setName("Application");
        codingProject.setRootPath(projectRoot.toString());
        KompileProjectInitRequest init = new KompileProjectInitRequest();
        init.setName("bound-project");
        init.setIncludeStandardComponents(false);
        init.setCodingProjects(List.of(codingProject));
        KompileProjectStore store = new KompileProjectStore();
        store.init(projectRoot, init);

        ObjectNode request = mapper.createObjectNode();
        request.put("async", false);
        request.putArray("codeProjects").add("app");
        request.putObject("knowledgeBase").put("id", 91);
        request.putObject("embeddingTraining").put("enabled", false);

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);
        assertFalse(result.isError(), result.getOutput());

        KompileProjectManifest restored = store.load(projectRoot);
        KompileCodingProject restoredCodeProject = restored.getCodingProjects().stream()
                .filter(candidate -> "app".equals(candidate.getId()))
                .findFirst().orElseThrow();
        assertEquals(91L, restoredCodeProject.getFactSheetId());
        KompileProjectCrawlProfile graphProfile = restored.getCrawlProfiles().stream()
                .filter(candidate -> "kb-91".equals(candidate.getId()))
                .findFirst().orElseThrow();
        assertTrue(graphProfile.isGraphLocal());
        assertTrue(graphProfile.isGraphAutoStart());
        assertEquals("91", graphProfile.getMetadata().get("factSheetId"));
        assertEquals("data/crawls/kb-91/graph.kgraph",
                graphProfile.getMetadata().get("graphPath").replace('\\', '/'));
        assertTrue(Files.isRegularFile(projectRoot.resolve(graphProfile.getMetadata().get("graphPath"))));
    }
}
