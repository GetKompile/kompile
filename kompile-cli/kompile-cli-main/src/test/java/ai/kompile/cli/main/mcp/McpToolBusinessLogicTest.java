/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.mcp;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolRegistryFactory;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphArchive;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast JVM business-logic gate for the local MCP tools.
 *
 * <p>This deliberately calls each real {@code CliTool.execute} method. It does not mock the tool,
 * backend, crawl worker, graph store, or knowledge index, and it does not introduce a transport or
 * HTTP fixture. The production local loader, chunker, enrichment, persistence, search, reasoning,
 * and mutation components execute in the ordinary JVM.</p>
 */
class McpToolBusinessLogicTest {
    private static final String KNOWLEDGE_BASE = "jvm-business-it";
    private static final String EVIDENCE = "JVM_MCP_BUSINESS_EVIDENCE_6A42";
    private static final String ASSERTED_ATOM = "jvmMcpBusinessFact(acme,initech)";
    private static final Set<String> PROJECT_LOCAL_COMPONENT_TOOLS = Set.of(
            "knowledge_search", "knowledge_status", "rag_search", "graph_search",
            "graph_aggregate", "graph_forecast", "graph_centrality", "knowledge_graph",
            "ask_graph_query", "ask_graph_verify", "ask_graph_assert", "ask_graph_retract",
            "ask_graph_mebn", "ask_graph_explain", "ask_graph_explain_fused",
            "ask_graph_synthesize", "ask_graph_subscribe", "graph_reason", "graph_import",
            "graph_export", "crawl_source", "crawl_documents", "crawl_discover",
            "model_runtime", "crawl_control", "crawl_result", "process_mining",
            "ask_graph_claim", "graph_reasoning_query", "graph_bayes", "graph_embeddings",
            "graph_simulate");

    @TempDir
    Path project;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private JvmToolHarness harness;

    @BeforeEach
    void setUp() {
        AgentConfig agent = AgentConfig.builder("jvm-mcp-business-it")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService permissions = new PermissionService();
        ToolRegistry registry = ToolRegistryFactory.create(
                mapper,
                null,
                new AgentRegistry(),
                permissions,
                null,
                null,
                null,
                null);
        for (String tool : registry.ids()) {
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        }
        permissions.setUserOverride("external_directory", PermissionService.PermissionLevel.ALLOW);
        ToolContext context = new ToolContext(
                "jvm-mcp-business-it", agent, permissions, project, registry);
        harness = new JvmToolHarness(registry, context);
    }

    @Test
    void realToolsExecuteCrawlPersistSearchQueryAssertAndRetractBusinessLogic() throws Exception {
        Files.writeString(project.resolve("deal.md"),
                EVIDENCE + ": Acme acquired Initech in a strategic transaction.\n",
                StandardCharsets.UTF_8);

        ToolResult crawl = harness.call("crawl_documents", crawlRequest());
            assertSucceeded(crawl, "crawl_documents");
            assertEquals("COMPLETED", crawl.getMetadata().get("status"), crawl.getOutput());
            assertEquals(1, number(crawl, "documentCount"));
            assertEquals(1, number(crawl, "chunkCount"));
            assertTrue(number(crawl, "graphEntityCount") > 0,
                    "crawl_documents did not execute graph enrichment");
            assertTrue(number(crawl, "graphRelationCount") > 0,
                    "crawl_documents did not persist enriched graph relations");
            assertEquals(true, crawl.getMetadata().get("enrichmentRequested"));

            Path crawlDirectory = project.resolve("data/crawls").resolve(KNOWLEDGE_BASE);
            Path documents = crawlDirectory.resolve("documents.jsonl");
            Path chunks = crawlDirectory.resolve("chunks.jsonl");
            Path graphPath = crawlDirectory.resolve(LocalProjectGraphBackend.GRAPH_FILE);
            assertTrue(Files.isRegularFile(documents),
                    "crawl_documents did not persist documents.jsonl");
            assertTrue(Files.isRegularFile(chunks),
                    "crawl_documents did not persist chunks.jsonl");
            assertTrue(Files.readString(chunks).contains(EVIDENCE),
                    "crawl_documents did not load and chunk the fixture");
            assertTrue(Files.isRegularFile(graphPath),
                    "crawl_documents did not persist graph.kgraph");

            UnifiedGraph extracted = UnifiedGraph.load(graphPath);
            assertTrue(extracted.entityCount() > 0,
                    "crawl_documents did not persist enriched graph entities");
            assertTrue(extracted.relationCount() > 0,
                    "crawl_documents did not persist enriched graph relations");

            ToolResult crawlResult = harness.call("crawl_result",
                    mapper.createObjectNode().put("jobId", KNOWLEDGE_BASE));
            assertSucceeded(crawlResult, "crawl_result");
            assertTrue(crawlResult.getOutput().contains("\"status\" : \"COMPLETED\""),
                    "crawl_result did not retrieve the completed crawl");

            ToolResult status = harness.call("knowledge_status",
                    mapper.createObjectNode().put("knowledgeBase", KNOWLEDGE_BASE));
            assertSucceeded(status, "knowledge_status");
            assertTrue(status.getOutput().contains(KNOWLEDGE_BASE),
                    "knowledge_status did not inspect the persisted knowledge base");

            ToolResult search = harness.call("knowledge_search",
                    mapper.createObjectNode()
                            .put("knowledgeBase", KNOWLEDGE_BASE)
                            .put("query", EVIDENCE)
                            .put("limit", 5));
            assertSucceeded(search, "knowledge_search");
            assertTrue(search.getOutput().contains(EVIDENCE),
                    "knowledge_search did not retrieve the crawled fixture");

            ToolResult graphSearch = harness.call("graph_reasoning_query",
                    mapper.createObjectNode()
                            .put("knowledgeBase", KNOWLEDGE_BASE)
                            .put("operation", "SEARCH")
                            .put("queryText", EVIDENCE)
                            .put("topK", 10));
            assertSucceeded(graphSearch, "graph_reasoning_query");
            assertTrue(graphSearch.getOutput().contains(EVIDENCE),
                    "graph_reasoning_query did not query the produced graph");

            byte[] beforeAssertion = Files.readAllBytes(graphPath);
            ToolResult asserted = harness.call("ask_graph_assert",
                    mapper.createObjectNode()
                            .put("knowledgeBase", KNOWLEDGE_BASE)
                            .put("atom", ASSERTED_ATOM)
                            .put("value", 1.0)
                            .put("source", "McpToolBusinessLogicTest"));
            assertSucceeded(asserted, "ask_graph_assert");
            byte[] afterAssertion = Files.readAllBytes(graphPath);
            // Compact-archive contract: an assert may durably land in the base
            // .kgraph (legacy) OR in the mutation-journal sidecar (base stays
            // immutable between compactions; readers replay the journal on open).
            boolean baseChanged = !Arrays.equals(beforeAssertion, afterAssertion);
            boolean journaled;
            try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
                journaled = archive.hasJournalMutations()
                        && archive.journalSnapshot().relationStates().values().stream()
                                .anyMatch(state -> state.current() != null
                                        && "jvmMcpBusinessFact".equals(state.current().type()));
            }
            assertTrue(baseChanged || journaled,
                    "ask_graph_assert persisted neither base archive nor journal");
            assertTrue(journaled || UnifiedGraph.load(graphPath).relations().stream()
                            .anyMatch(relation -> "jvmMcpBusinessFact".equals(relation.type())),
                    "ask_graph_assert did not make the asserted relation visible");

            ToolResult facts = harness.call("graph_reasoning_query",
                    mapper.createObjectNode()
                            .put("knowledgeBase", KNOWLEDGE_BASE)
                            .put("operation", "FACTS")
                            .put("queryText", "jvmMcpBusinessFact"));
            assertSucceeded(facts, "graph_reasoning_query facts");
            assertTrue(facts.getOutput().toUpperCase().contains("JVMMCPBUSINESSFACT("),
                    "the asserted fact was not queryable: " + facts.getOutput());

            ToolResult retracted = harness.call("ask_graph_retract",
                    mapper.createObjectNode()
                            .put("knowledgeBase", KNOWLEDGE_BASE)
                            .put("atomKey", ASSERTED_ATOM)
                            .put("mode", "revise"));
            assertSucceeded(retracted, "ask_graph_retract");
            assertEquals(1, number(retracted, "removed"));
            // Mirror of the assert contract: the removal is durable in the base
            // archive or the journal sidecar, and no replaying reader sees the
            // jvmMcpBusinessFact relation anymore.
            boolean baseCleared;
            try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
                boolean baseStillHasRelation = archive.journalSnapshot().relationStates().values().stream()
                        .anyMatch(state -> state.basePresent()
                                && state.baseLink() != null
                                && "jvmMcpBusinessFact".equals(state.baseLink().type())
                                && state.current() == null);
                boolean journalHasRelation = archive.journalSnapshot().relationStates().values().stream()
                        .anyMatch(state -> state.current() != null
                                && "jvmMcpBusinessFact".equals(state.current().type()));
                baseCleared = !baseStillHasRelation && !journalHasRelation;
            }
            assertTrue(baseCleared || UnifiedGraph.load(graphPath).relations().stream()
                            .noneMatch(relation -> "jvmMcpBusinessFact".equals(relation.type())),
                    "ask_graph_retract did not remove the asserted relation");
    }

    @Test
    void everyProjectLocalComponentToolExecutesRealBusinessLogic() throws Exception {
        Files.writeString(project.resolve("deal.md"),
                EVIDENCE + ": Acme acquired Initech in a strategic transaction.\n",
                StandardCharsets.UTF_8);
        ObjectNode crawlRequest = crawlRequest();
        ((ObjectNode) crawlRequest.get("reasoningLearning"))
                .put("enabled", true)
                .put("pslSteps", 1)
                .put("mebnEpochs", 1)
                .put("consensusRounds", 1);

        Set<String> called = new LinkedHashSet<>();
        callSucceeded(called, "crawl_documents", crawlRequest);
        Path graphPath = project.resolve("data/crawls").resolve(KNOWLEDGE_BASE)
                .resolve(LocalProjectGraphBackend.GRAPH_FILE);
        UnifiedGraph graph = UnifiedGraph.load(graphPath);
        assertFalse(graph.entities().isEmpty(), "component crawl produced no graph entities");
        assertFalse(graph.relations().isEmpty(), "component crawl produced no graph relations");
        var entity = graph.entities().iterator().next();
        var relation = graph.relations().iterator().next();
        String atom = relation.type() + "(" + relation.sourceId() + "," + relation.targetId() + ")";

        callSucceeded(called, "crawl_result", object("jobId", KNOWLEDGE_BASE));
        callSucceeded(called, "crawl_discover", mapper.createObjectNode().put("section", "all"));
        callSucceeded(called, "crawl_control", mapper.createObjectNode()
                .put("operation", "status").put("jobId", KNOWLEDGE_BASE));
        ObjectNode source = mapper.createObjectNode()
                .put("text", "Standalone source for the JVM local crawl component.")
                .put("title", "component-source")
                .put("dryRun", true);
        source.putArray("steps").add("PREPROCESSING");
        callSucceeded(called, "crawl_source", source);
        callSucceeded(called, "model_runtime", mapper.createObjectNode().put("action", "status"));

        callSucceeded(called, "knowledge_status", selector());
        callSucceeded(called, "knowledge_search", selector()
                .put("query", EVIDENCE).put("limit", 5));
        callSucceeded(called, "rag_search", selector().put("query", EVIDENCE));
        callSucceeded(called, "graph_search", selector().put("query", "Acme").put("max_results", 10));
        callSucceeded(called, "graph_aggregate", selector()
                .put("root_type", entity.type()).put("aggregation", "COUNT"));
        callSucceeded(called, "graph_forecast", selector()
                .put("root_type", entity.type()).put("aggregation", "COUNT")
                .put("bucket_size", "QUARTER").put("horizon_buckets", 2));
        callSucceeded(called, "graph_centrality", selector()
                .put("algorithm", "degree").put("top_k", 10));
        callSucceeded(called, "knowledge_graph", selector().put("action", "overview"));

        ObjectNode query = selector();
        query.putArray("conjuncts").addObject()
                .put("predicate", relation.type())
                .putArray("args").add("?subject").add("?object");
        callSucceeded(called, "ask_graph_query", query);
        callSucceeded(called, "ask_graph_verify", selector().put("atom", atom));
        callSucceeded(called, "ask_graph_explain", selector().put("atom", atom));
        callSucceeded(called, "ask_graph_explain_fused", selector().put("target", entity.id()));
        callSucceeded(called, "ask_graph_synthesize", selector()
                .put("query", entity.label() == null ? entity.id() : entity.label()));
        ObjectNode subscribe = selector();
        subscribe.putArray("predicates").add(relation.type());
        callSucceeded(called, "ask_graph_subscribe", subscribe);
        callSucceeded(called, "ask_graph_claim", selector()
                .put("subject", relation.sourceId())
                .put("predicate", relation.type())
                .put("object", relation.targetId()));
        callSucceeded(called, "graph_reason", selector().put("target", atom).put("depth", 2));
        callSucceeded(called, "graph_reasoning_query", selector()
                .put("operation", "OVERVIEW"));
        callSucceeded(called, "ask_graph_mebn", selector()
                .put("nodeId", entity.id()).put("maxDepth", 2).put("maxNodes", 25));
        callSucceeded(called, "graph_bayes", selector()
                .put("action", "stats").put("node_id", entity.id()));
        callSucceeded(called, "graph_simulate", selector().put("action", "scenarios"));
        callSucceeded(called, "process_mining", selector().put("action", "discover"));

        callSucceeded(called, "graph_embeddings", selector()
                .put("action", "train").put("algorithm", "TRANSE")
                .put("embedding_dim", 8).put("epochs", 1));

        Path exported = project.resolve("component-export.kgraph");
        callSucceeded(called, "graph_export", selector().put("path", exported.toString()));
        assertTrue(Files.isRegularFile(exported), "graph_export did not create its artifact");
        callSucceeded(called, "graph_import", mapper.createObjectNode().put("path", exported.toString()));

        ObjectNode assertion = selector()
                .put("atom", ASSERTED_ATOM)
                .put("value", 1.0)
                .put("source", "McpToolBusinessLogicTest component matrix");
        callSucceeded(called, "ask_graph_assert", assertion);
        callSucceeded(called, "ask_graph_retract", selector()
                .put("atomKey", ASSERTED_ATOM).put("mode", "revise"));

        assertEquals(PROJECT_LOCAL_COMPONENT_TOOLS, called,
                () -> "Component matrix did not execute every scoped MCP tool; called=" + called);
    }

    private ObjectNode crawlRequest() {
        ObjectNode request = mapper.createObjectNode();
        request.put("async", false);
        request.put("name", "JVM MCP business logic test");
        request.putObject("knowledgeBase").put("name", KNOWLEDGE_BASE);
        request.putArray("documents").addObject()
                .put("path", project.resolve("deal.md").toString())
                .put("sourceType", "FILE")
                .put("pipelineId", "standard-text")
                .put("loaderName", "markdown")
                .put("chunkerName", "no-op");
        request.putArray("steps").add("LOADING").add("CHUNKING").add("ENRICHMENT");
        request.put("strictSteps", true);
        request.putObject("embeddingTraining").put("enabled", false);
        request.putObject("reasoningLearning").put("enabled", false);
        return request;
    }

    private ObjectNode selector() {
        return mapper.createObjectNode().put("knowledgeBase", KNOWLEDGE_BASE);
    }

    private ObjectNode object(String key, String value) {
        return mapper.createObjectNode().put(key, value);
    }

    private ToolResult callSucceeded(Set<String> called, String toolId, ObjectNode arguments)
            throws Exception {
        ToolResult result = harness.call(toolId, arguments);
        assertSucceeded(result, toolId);
        called.add(toolId);
        return result;
    }

    /**
     * In-memory integration transport: resolves the production tool from the real registry and
     * invokes it with the same context shared by the local MCP surface. No executable, socket, or
     * external service is involved; every result comes from the underlying JVM components.
     */
    private record JvmToolHarness(ToolRegistry registry, ToolContext context) {
        ToolResult call(String toolId, ObjectNode arguments) throws Exception {
            var tool = registry.get(toolId);
            assertTrue(tool != null, () -> "JVM harness has no registered tool " + toolId);
            return tool.execute(arguments, context);
        }
    }

    private static void assertSucceeded(ToolResult result, String tool) {
        assertFalse(result.isError(), tool + " failed: " + result.getOutput());
    }

    private static int number(ToolResult result, String key) {
        Object value = result.getMetadata().get(key);
        assertTrue(value instanceof Number,
                () -> "Missing numeric metadata " + key + " in " + result.getMetadata());
        return ((Number) value).intValue();
    }
}
