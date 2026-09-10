/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.NativeChatModels;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.lifecycle.IncrementalGraphPslInference;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphArchive;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live proof of the native Codex text-crawl path, including the asynchronous crawl,
 * semantic graph extraction, selected FOL/PSL/MEBN learning, persisted opinions, and graph mutation.
 * This is intentionally scoped to the native text/graph lifecycle; it does not claim coverage of
 * unsupported model modalities such as embeddings, VLM, or native tool choice.
 *
 * <p>This test deliberately writes a request-scoped chat config below {@link #projectRoot}; the
 * configured host credential is read, but no global/project config, credential, or model setting is
 * modified. Normal test runs never contact Codex.</p>
 */
class ChatModelTextCrawlGraphLiveIT {
    private static final String ENABLE_PROPERTY = "kompile.remote.chat.text.live";
    private static final String CONFIG_ROOT_PROPERTY = "kompile.remote.chat.configRoot";
    private static final String MODEL = "gpt-5.6-luna";
    private static final String THINKING = "xhigh";
    private static final String KNOWLEDGE_BASE = "codex-luna-text-graph-live-it";
    private static final String SOURCE_ENTITY = "Orchid Systems";
    private static final String TARGET_ENTITY = "Cedar Labs";
    private static final String MUTATION_ATOM = "partnersWith(Orchid Systems, Cedar Labs)";

    @TempDir
    Path projectRoot;

    @Test
    void nativeCodexTextCrawlCompletesLearnsPersistsSearchesAndMutatesGraph() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY),
                "Native Codex text crawl is opt-in; pass -D" + ENABLE_PROPERTY + "=true");

        ChatConfig configured = requireCodexConfiguration();
        configureRequestScopedCodex(configured);

        Path source = projectRoot.resolve("orchid-cedar.md");
        Files.writeString(source, "# Acquisition\n\n"
                + SOURCE_ENTITY + " acquired " + TARGET_ENTITY
                + " in a strategic transaction.\n", StandardCharsets.UTF_8);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ToolContext context = context(mapper);
        CrawlDocumentsTool crawlDocuments = new CrawlDocumentsTool((String) null, mapper);
        CrawlControlTool crawlControl = new CrawlControlTool((String) null, mapper);
        CrawlResultTool crawlResult = new CrawlResultTool((String) null, mapper);
        String jobId = null;

        try {
            ToolResult accepted = crawlDocuments.execute(crawlRequest(mapper, source), context);
            assertFalse(accepted.isError(), accepted.getOutput());
            assertEquals("QUEUED", accepted.getMetadata().get("status"), accepted.getOutput());
            jobId = String.valueOf(accepted.getMetadata().get("jobId"));
            assertFalse(jobId.isBlank(), accepted.getOutput());

            ToolResult terminal = awaitTerminal(crawlControl, context, mapper, jobId,
                    Duration.ofMinutes(10));
            assertFalse(terminal.isError(), terminal.getOutput());
            assertEquals(Boolean.TRUE, terminal.getMetadata().get("terminal"), terminal.getOutput());
            assertEquals("COMPLETED", terminal.getMetadata().get("status"), terminal.getOutput());

            ToolResult result = crawlResult.execute(
                    mapper.createObjectNode().put("jobId", jobId), context);
            assertFalse(result.isError(), result.getOutput());
            assertEquals("COMPLETED", result.getMetadata().get("status"), result.getOutput());
            assertEquals(1, ((Number) result.getMetadata().get("documentCount")).intValue());
            assertTrue(((Number) result.getMetadata().get("chunkCount")).intValue() > 0);
            assertTrue(((Number) result.getMetadata().get("semanticEntityCount")).intValue() >= 2,
                    result.getMetadata().toString());
            assertTrue(((Number) result.getMetadata().get("semanticRelationCount")).intValue() >= 1,
                    result.getMetadata().toString());
            assertEquals(Boolean.TRUE, result.getMetadata().get("reasoningLearningEnabled"),
                    result.getMetadata().toString());
            assertEquals(Boolean.TRUE, result.getMetadata().get("folPslLearned"),
                    result.getMetadata().toString());
            assertEquals(Boolean.TRUE, result.getMetadata().get("mebnLearned"),
                    result.getMetadata().toString());
            assertEquals(Boolean.TRUE, result.getMetadata().get("enrichmentRequested"),
                    result.getMetadata().toString());
            assertEquals(Boolean.TRUE, result.getMetadata().get("entityResolutionEnabled"),
                    result.getMetadata().toString());
            assertEquals(0, ((Number) result.getMetadata().get("embeddingVectorCount")).intValue(),
                    result.getMetadata().toString());
            assertTrue(((List<?>) result.getMetadata().get("semanticExtractionErrors")).isEmpty(),
                    result.getMetadata().toString());

            Path graphPath = projectRoot.resolve("data/crawls").resolve(KNOWLEDGE_BASE)
                    .resolve(LocalProjectGraphBackend.GRAPH_FILE);
            assertTrue(Files.isRegularFile(graphPath), graphPath.toString());
            UnifiedGraph graph = UnifiedGraph.load(graphPath);
            assertTrue(graph.entities().stream().anyMatch(entity ->
                    entity.label() != null && entity.label().contains(SOURCE_ENTITY)),
                    graph.entities().toString());
            assertTrue(graph.entities().stream().anyMatch(entity ->
                    entity.label() != null && entity.label().contains(TARGET_ENTITY)),
                    graph.entities().toString());
            assertTrue(graph.types().stream().anyMatch(type -> "ORGANIZATION".equalsIgnoreCase(type)),
                    "the standardized schema must reach the persisted ontology types: " + graph.types());
            assertTrue(graph.relations().stream().anyMatch(relation ->
                    "ACQUIRED".equalsIgnoreCase(relation.type())
                            && relationConnects(graph, relation, SOURCE_ENTITY, TARGET_ENTITY)),
                    graph.relations().toString());

            assertEquals("COMPLETED", String.valueOf(
                    graph.meta().get("reasoningLearning.status")), graph.meta().toString());
            assertNotNull(graph.artifactText(UnifiedGraphReasoningLifecycle.PSL_WEIGHTS_ARTIFACT));
            assertNotNull(graph.artifactText(UnifiedGraphReasoningLifecycle.MEBN_THEORY_JSON_ARTIFACT));
            assertFalse(graph.entityOpinions().isEmpty(),
                    "real reasoning learning must persist entity opinions");

            List<HybridReasoner.ScoredEntity> coldRanking = new HybridReasoner().rank(graph);
            assertFalse(coldRanking.isEmpty(), "HybridReasoner must return real PSL scores");
            assertNotNull(graph.artifact(IncrementalGraphPslInference.CACHE_ARTIFACT),
                    "HybridReasoner must persist the incremental PSL cache artifact");
            graph.saveCompact(graphPath);

            UnifiedGraph reloaded = UnifiedGraph.load(graphPath);
            assertEquals(graph.entityOpinions(), reloaded.entityOpinions());
            assertEquals(graph.artifactText(UnifiedGraphReasoningLifecycle.PSL_WEIGHTS_ARTIFACT),
                    reloaded.artifactText(UnifiedGraphReasoningLifecycle.PSL_WEIGHTS_ARTIFACT));
            assertEquals(graph.artifactText(UnifiedGraphReasoningLifecycle.MEBN_THEORY_JSON_ARTIFACT),
                    reloaded.artifactText(UnifiedGraphReasoningLifecycle.MEBN_THEORY_JSON_ARTIFACT));
            assertNotNull(reloaded.artifact(IncrementalGraphPslInference.CACHE_ARTIFACT),
                    "incremental PSL cache must survive graph reload");
            List<HybridReasoner.ScoredEntity> warmRanking = new HybridReasoner().rank(reloaded);
            assertEquals(coldRanking.size(), warmRanking.size());
            assertEquals(Boolean.TRUE,
                    reloaded.meta().get(IncrementalGraphPslInference.META_CACHE_LOADED),
                    reloaded.meta().toString());
            assertTrue(((Number) reloaded.meta()
                    .get(IncrementalGraphPslInference.META_REUSED_COMPONENT_COUNT)).intValue() > 0,
                    reloaded.meta().toString());
            try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
                assertTrue(archive.hasCompactTopology(), "reload must retain the compact graph cache");
                assertTrue(archive.hasAdjacencyIndex(), "reload must retain adjacency cache");
            }

            LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);
            ToolResult graphSearch = backend.executeOfflineTool("graph_search",
                    mapper.createObjectNode().put("query", SOURCE_ENTITY)
                            .put("search_type", "local")
                            .put("knowledgeBase", KNOWLEDGE_BASE)
                            .put("max_results", 10), context);
            assertFalse(graphSearch.isError(), graphSearch.getOutput());
            assertTrue(graphSearch.getOutput().toLowerCase(Locale.ROOT)
                    .contains(SOURCE_ENTITY.toLowerCase(Locale.ROOT)), graphSearch.getOutput());

            ToolResult asserted = backend.executeOfflineTool("ask_graph_assert",
                    mapper.createObjectNode().put("atom", MUTATION_ATOM)
                            .put("value", 1.0)
                            .put("knowledgeBase", KNOWLEDGE_BASE)
                            .put("source", "ChatModelTextCrawlGraphLiveIT"), context);
            assertFalse(asserted.isError(), asserted.getOutput());
            try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
                assertTrue(archive.hasJournalMutations(),
                        "assertions must be persisted through the mutation journal");
            }
            UnifiedGraph afterAssert = UnifiedGraph.load(graphPath);
            assertTrue(afterAssert.relations().stream().anyMatch(relation ->
                    "partnersWith".equals(relation.type())
                            && relationConnects(afterAssert, relation, SOURCE_ENTITY, TARGET_ENTITY)),
                    afterAssert.relations().toString());

            ToolResult retracted = backend.executeOfflineTool("ask_graph_retract",
                    mapper.createObjectNode().put("atomKey", MUTATION_ATOM)
                            .put("knowledgeBase", KNOWLEDGE_BASE)
                            .put("mode", "revise"), context);
            assertFalse(retracted.isError(), retracted.getOutput());
            UnifiedGraph afterRetraction = UnifiedGraph.load(graphPath);
            assertTrue(afterRetraction.relations().stream().noneMatch(relation ->
                    "partnersWith".equals(relation.type())), afterRetraction.relations().toString());
            assertTrue(afterRetraction.entities().stream().anyMatch(entity ->
                    entity.label() != null && entity.label().contains(SOURCE_ENTITY)),
                    "retracting the small mutation must not remove the extracted entity");
            try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
                assertTrue(archive.hasJournalMutations(),
                        "retractions must remain readable through the mutation journal");
            }
        } catch (Throwable failure) {
            try {
                cancelIfActive(crawlControl, context, mapper, jobId);
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new AssertionError(failure);
        }
        cancelIfActive(crawlControl, context, mapper, jobId);
    }

    private ChatConfig requireCodexConfiguration() {
        Path configRoot = Path.of(System.getProperty(
                CONFIG_ROOT_PROPERTY, System.getProperty("user.dir")))
                .toAbsolutePath().normalize();
        ChatConfig configured = ChatConfig.loadOrFromEnv(configRoot);
        assertNotNull(configured,
                "Native Codex chat configuration is missing; configure a native Codex provider "
                        + "(openai-codex or codex) and run `kompile auth login openai-codex` before enabling this test");
        assertEquals("openai-codex", NativeChatModels.normalizeProvider(configured.getProvider()),
                "This live test requires the configured native Codex provider (codex aliases are normalized), not a CLI alias");
        assertEquals("standard", configured.getChatMode(),
                "Native CHAT_MODEL requires standard direct chat mode");
        assertFalse(configured.isKompileLocalServing(),
                "Native CHAT_MODEL must not use Kompile local serving");
        assertFalse(configured.isKompileServer(),
                "Native CHAT_MODEL must not use a managed Kompile server");
        assertTrue(configured.isValid(),
                "Native Codex authentication/configuration is unavailable; run "
                        + "`kompile auth login openai-codex` (or use the configured codex alias) and retry");
        return configured;
    }

    private void configureRequestScopedCodex(ChatConfig configured) throws Exception {
        ChatConfig scoped = new ChatConfig(configured.getProvider(), null, MODEL, configured.getBaseUrl());
        scoped.setAuthenticationMethod(configured.getAuthenticationMethod());
        scoped.setThinking(THINKING);
        scoped.setChatMode("standard");
        scoped.setApiKey(null);
        scoped.saveProject(projectRoot);

        ChatConfig saved = ChatConfig.loadProject(projectRoot);
        assertNotNull(saved);
        assertEquals("openai-codex", NativeChatModels.normalizeProvider(saved.getProvider()));
        assertEquals(MODEL, saved.getModel());
        assertEquals(THINKING, saved.getThinking());
        String persisted = Files.readString(ChatConfig.projectConfigPath(projectRoot), StandardCharsets.UTF_8);
        assertFalse(persisted.contains("apiKey"), persisted);
    }

    private ObjectNode crawlRequest(ObjectMapper mapper, Path source) {
        ObjectNode request = mapper.createObjectNode();
        request.put("name", "Native Codex text graph lifecycle");
        request.put("async", true);
        request.put("deriveOntology", true);
        request.putObject("knowledgeBase").put("name", KNOWLEDGE_BASE);
        request.putArray("documents").addObject().put("path", source.toString());
        // Select only the supported graph lifecycle stages. Dependency closure supplies the
        // foundational text stages and keeps optional VECTOR_INDEXING out of this remote-text test;
        // reasoning LEARNING is an internal ENRICHMENT lifecycle, not a step ID.
        request.putArray("steps")
                .add("GRAPH_EXTRACTION")
                .add("ENTITY_RESOLUTION")
                .add("ENRICHMENT");
        request.putObject("embeddingTraining").put("enabled", false);
        request.putObject("reasoningLearning")
                .put("enabled", true)
                .put("pslSteps", 1)
                .put("mebnEpochs", 1)
                .put("consensusRounds", 1)
                .put("consensusWeight", 0.35)
                .put("maxRelationTypes", 8);
        request.putObject("runtimeConfig")
                .put("runReasoningLearning", true)
                .put("graphExtractionParallelism", 1)
                .put("graphExtractionRemoteParallelism", 1)
                .put("llmCallTimeoutSeconds", 180);

        ObjectNode extraction = request.putObject("graphExtraction")
                .put("llmProvider", "chat:codex")
                .put("modelName", MODEL)
                .put("extractionMode", "SINGLE_PASS")
                .put("schemaMode", "STRICT")
                .put("entityResolution", true)
                .put("minConfidence", 0.0)
                .put("customPrompt", "Extract only the two organizations and one ACQUIRED relation from the source. "
                        + "Use the exact entity names and relation type from the standardized schema; return no prose.");
        extraction.putArray("entityTypes").add("ORGANIZATION");
        extraction.putArray("relationshipTypes").add("ACQUIRED");
        ObjectNode schema = extraction.putObject("standardizedSchema");
        schema.putArray("nodeTypes").addObject()
                .put("label", "ORGANIZATION")
                .put("description", "A business organization named in the source.");
        schema.putArray("relationshipTypes").addObject()
                .put("type", "ACQUIRED")
                .put("description", "The source organization acquired the target organization.");
        schema.putArray("patterns").add("(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)");
        return request;
    }

    private ToolContext context(ObjectMapper mapper) {
        PermissionService permissions = new PermissionService();
        for (String tool : Set.of(
                "crawl_documents", "crawl_control", "crawl_result", "knowledge_search",
                "knowledge_graph", "graph_search", "graph_reasoning_query", "graph_export", "graph_import",
                "ask_graph_assert", "ask_graph_retract", "ask_graph_verify")) {
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        }
        return new ToolContext(
                "codex-luna-text-graph-live-it",
                AgentConfig.builder("codex-luna-text-graph-live-it").enabledTools(Set.of("*")).build(),
                permissions,
                projectRoot,
                new ToolRegistry(mapper));
    }

    private static boolean relationConnects(UnifiedGraph graph, GraphRelation relation,
                                             String source, String target) {
        return entityLabelMatches(graph, relation.sourceId(), source)
                && entityLabelMatches(graph, relation.targetId(), target);
    }

    private static boolean entityLabelMatches(UnifiedGraph graph, String id, String expected) {
        return graph.entity(id).map(entity -> entity.label() != null
                && entity.label().toLowerCase(Locale.ROOT)
                .contains(expected.toLowerCase(Locale.ROOT))).orElse(false);
    }

    private static ToolResult awaitTerminal(CrawlControlTool crawlControl,
                                            ToolContext context,
                                            ObjectMapper mapper,
                                            String jobId,
                                            Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        ToolResult latest = null;
        while (Instant.now().isBefore(deadline)) {
            latest = crawlControl.execute(
                    mapper.createObjectNode().put("operation", "status").put("jobId", jobId),
                    context);
            if (latest.isError() || Boolean.TRUE.equals(latest.getMetadata().get("terminal"))) {
                return latest;
            }
            Thread.sleep(Math.min(1_000L, Math.max(50L,
                    ((Number) latest.getMetadata().getOrDefault("pollAfterMs", 1_000L)).longValue())));
        }
        assertNotNull(latest, "crawl_control returned no status before timeout");
        throw new AssertionError("Native Codex text crawl did not reach terminal state within "
                + timeout + ": " + latest.getOutput());
    }

    private static void cancelIfActive(CrawlControlTool crawlControl,
                                       ToolContext context,
                                       ObjectMapper mapper,
                                       String jobId) throws Exception {
        if (jobId == null || jobId.isBlank()) return;
        ToolResult status = crawlControl.execute(
                mapper.createObjectNode().put("operation", "status").put("jobId", jobId), context);
        if (status.isError()) {
            throw new AssertionError("cleanup status failed: " + status.getOutput());
        }
        if (Boolean.TRUE.equals(status.getMetadata().get("terminal"))) return;
        ToolResult cancelled = crawlControl.execute(
                mapper.createObjectNode().put("operation", "cancel").put("jobId", jobId), context);
        if (cancelled.isError()) {
            throw new AssertionError("cleanup cancellation failed: " + cancelled.getOutput());
        }
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            ToolResult finalStatus = crawlControl.execute(
                    mapper.createObjectNode().put("operation", "status").put("jobId", jobId), context);
            if (finalStatus.isError()) {
                throw new AssertionError("cleanup final status failed: " + finalStatus.getOutput());
            }
            if (Boolean.TRUE.equals(finalStatus.getMetadata().get("terminal"))) return;
            Thread.sleep(250L);
        }
        throw new AssertionError("cleanup cancellation did not reach terminal state for job " + jobId);
    }
}
