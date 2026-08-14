/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.mcp;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.KnowledgeSearchCliTool;
import ai.kompile.cli.main.chat.tools.KnowledgeStatusCliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphAssertTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphRetractTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlDocumentsTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlResultTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphReasoningQueryTool;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

    @TempDir
    Path project;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private JvmToolHarness harness;
    private String previousLocalCrawlExecution;

    @BeforeEach
    void setUp() {
        previousLocalCrawlExecution = System.getProperty("kompile.local.crawl.execution");
        System.setProperty("kompile.local.crawl.execution", "inline");

        AgentConfig agent = AgentConfig.builder("jvm-mcp-business-it")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService permissions = new PermissionService();
        for (String tool : Set.of(
                "crawl_documents", "crawl_result", "knowledge_status", "knowledge_search",
                "graph_reasoning_query", "ask_graph_assert", "ask_graph_retract",
                "external_directory")) {
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        }
        ToolRegistry registry = new ToolRegistry(mapper);
        registry.register(new CrawlDocumentsTool((String) null, mapper));
        registry.register(new CrawlResultTool((String) null, mapper));
        registry.register(new KnowledgeStatusCliTool((String) null, mapper));
        registry.register(new KnowledgeSearchCliTool((String) null, mapper));
        registry.register(new GraphReasoningQueryTool((String) null, mapper));
        registry.register(new AskGraphAssertTool((String) null, mapper));
        registry.register(new AskGraphRetractTool((String) null, mapper));
        ToolContext context = new ToolContext(
                "jvm-mcp-business-it", agent, permissions, project, registry);
        harness = new JvmToolHarness(registry, context);
    }

    @AfterEach
    void restoreExecutionMode() {
        if (previousLocalCrawlExecution == null) {
            System.clearProperty("kompile.local.crawl.execution");
        } else {
            System.setProperty("kompile.local.crawl.execution", previousLocalCrawlExecution);
        }
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
            assertFalse(Arrays.equals(beforeAssertion, afterAssertion),
                    "ask_graph_assert did not persist a graph mutation");
            assertTrue(UnifiedGraph.load(graphPath).relations().stream()
                            .anyMatch(relation -> "jvmMcpBusinessFact".equals(relation.type())),
                    "ask_graph_assert did not add the asserted relation");

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
            assertTrue(UnifiedGraph.load(graphPath).relations().stream()
                            .noneMatch(relation -> "jvmMcpBusinessFact".equals(relation.type())),
                    "ask_graph_retract did not remove the asserted relation");
    }

    private ObjectNode crawlRequest() {
        ObjectNode request = mapper.createObjectNode();
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
