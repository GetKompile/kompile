package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.ProductChatPolicy;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.graph.GraphServiceRouting;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Source-only regression fixtures: no servers, live inference or crawl/model workers. */
@TemporaryUserHome
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ProductContentReadersTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @TempDir Path root;

    @Test void allFourteenEnabledNativeIdsAreGatewaysOrScopedAuthority() {
        Set<String> enabled = Set.of("mcp_tool_search", "mcp_tool_call", "pipeline", "model_runtime",
                "crawl_discover", "crawl_documents", "crawl_control", "crawl_result", "knowledge_graph",
                "knowledge_status", "knowledge_search", "rag_search", "graph_search", "graph_reasoning_query");
        Set<String> mediated = new HashSet<>(ProductContentSession.TOOLS);
        mediated.addAll(Set.of("mcp_tool_search", "mcp_tool_call"));
        assertEquals(14, enabled.size());
        assertEquals(enabled, mediated, "No enabled native ID may silently use an ordinary delegate");
        assertTrue(ProductContentSession.TOOLS.containsAll(ProductContentSession.READERS));
    }

    @Test void futureUnmappedNativeIdsFailClosedAtFreeze() throws Exception {
        new ProductContentSessionTest.Fixture(root);
        ObjectNode manifest = (ObjectNode) JSON.readTree(root.resolve("policy.json").toFile());
        manifest.withArray("nativeTools").add("unmapped_reader");
        JSON.writeValue(root.resolve("future-policy.json").toFile(), manifest);
        ProductChatPolicy policy = ProductChatPolicy.load(root.resolve("future-policy.json"), root);
        ToolRegistry registry = registry();
        registry.register(fake("unmapped_reader", new AtomicInteger(), null));
        assertThrows(SecurityException.class, () -> registry.freezeProductPolicy(policy, Set.of("*")));
        assertFalse(registry.isProductMode(), "A rejected freeze must not publish a partial policy");
    }

    @Test void allFiveRetainedWrappersValidateAndForceScopeBeforeTheirLocalOverride() throws Exception {
        var fixture = new ProductContentSessionTest.Fixture(root);
        writeCorpus(); writeGraph();
        ToolRegistry registry = registry();
        AtomicInteger ordinaryCalls = new AtomicInteger(), scopedCalls = new AtomicInteger();
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        Map<String, CliTool> retained = new LinkedHashMap<>();
        for (String id : ProductContentSession.READERS) {
            registry.register(fake(id, ordinaryCalls, null));
            retained.put(id, registry.get(id));
            registry.registerProductContentDelegate(fake(id, scopedCalls, seen));
        }
        fixture.bind(registry);
        var context = fixture.context(registry);
        for (String id : ProductContentSession.READERS) {
            ToolResult result = retained.get(id).execute(request(id), context);
            assertFalse(result.isError(), id + ": " + result.getOutput());
            assertEquals("elthoria-knowledge", seen.get().path("knowledgeBase").asText());
        }
        assertEquals(5, scopedCalls.get()); assertEquals(0, ordinaryCalls.get());
        assertTrue(registry.productContentSession().pendingApprovals().isEmpty());

        for (String id : ProductContentSession.READERS) {
            List<ObjectNode> attacks = new ArrayList<>();
            for (String key : List.of("factSheetId", "fact_sheet_id", "graph_id", "graphId", "code_project_id",
                    "documentId", "document_id", "node_id", "nodeId", "source_id", "provider", "model", "modelId",
                    "baseUrl", "url", "processingRoute", "approvalId", "confirmed", "action", "codeProjects")) {
                attacks.add(request(id).put(key, "untrusted"));
            }
            attacks.add(request(id).put("knowledgeBase", "other-kb"));
            attacks.add(request(id).put("knowledgeBase", "*"));
            attacks.add(request(id).putNull("knowledgeBase"));
            attacks.add(request(id).set("chatModel", JSON.createObjectNode().put("dryRun", true)));
            attacks.add(request(id).set("chatModel", JSON.createObjectNode().put("provider", "codex").put("modelId", "fake-approved")));
            attacks.add(request(id).putNull("chatModel"));
            for (ObjectNode attack : attacks) {
                ToolResult result = retained.get(id).execute(attack, context);
                assertTrue(result.isError(), id + ": " + attack);
                assertEquals(5, scopedCalls.get(), "Denied before delegate: " + attack);
            }
        }
        assertEquals(0, ordinaryCalls.get());
        assertTrue(registry.productContentSession().pendingApprovals().isEmpty(), "Tool-authored approval is never queued");
        assertEquals("REJECTED", registry.productContentSession().resolveApproval("fake-approved", true).path("status").asText());
    }

    @Test void foreignReferencesAndNestedQuantitativeOverridesCannotResolveSiblingGraphs() throws Exception {
        var fixture = new ProductContentSessionTest.Fixture(root); writeGraph();
        Path foreign = Files.createDirectories(root.resolve("data/crawls/other-kb"));
        new UnifiedGraph().addEntity("private", "CELL", "Foreign Total").save(foreign.resolve("graph.kgraph"));
        AtomicInteger calls = new AtomicInteger(); ToolRegistry registry = registry();
        registry.register(fake("graph_reasoning_query", new AtomicInteger(), null));
        registry.registerProductContentDelegate(fake("graph_reasoning_query", calls, null)); fixture.bind(registry);
        var context = fixture.context(registry); CliTool reader = registry.get("graph_reasoning_query");
        for (String key : List.of("entityId", "targetId")) {
            assertTrue(reader.execute(request("graph_reasoning_query").put(key, "private"), context).isError());
        }
        ObjectNode foreignMeasure = JSON.createObjectNode().put("operation", "CALCULATE");
        foreignMeasure.putObject("quantitative").putObject("target").put("entityId", "private");
        assertTrue(reader.execute(foreignMeasure, context).isError());
        ObjectNode nested = JSON.createObjectNode().put("operation", "CALCULATE");
        nested.putObject("quantitative").putObject("target").put("entityId", "total").put("knowledgeBase", "other-kb");
        assertTrue(reader.execute(nested, context).isError());
        assertEquals(0, calls.get());
        ObjectNode valid = JSON.createObjectNode().put("operation", "CALCULATE");
        valid.putObject("quantitative").putObject("target").put("entityId", "total");
        assertFalse(reader.execute(valid, context).isError()); assertEquals(1, calls.get());
    }

    @Test void missingIndexesReturnTypedNotReadyBeforeAnyDelegateOrFilesystemMutation() throws Exception {
        var fixture = new ProductContentSessionTest.Fixture(root);
        AtomicInteger calls = new AtomicInteger(); ToolRegistry registry = registry();
        for (String id : ProductContentSession.READERS) {
            registry.register(fake(id, calls, null)); registry.registerProductContentDelegate(fake(id, calls, null));
        }
        fixture.bind(registry); var context = fixture.context(registry);
        Map<String, List<Byte>> before = snapshot();
        for (String id : ProductContentSession.READERS) {
            ToolResult result = registry.get(id).execute(request(id), context);
            assertTrue(result.isError(), id); assertEquals("NOT_READY", result.getMetadata().get("status"), result.getOutput());
        }
        assertEquals(0, calls.get()); assertEquals(before, snapshot());
        assertFalse(Files.exists(root.resolve("data/crawls")));
        assertFalse(Files.exists(root.resolve("kompile.project.json")));
    }

    @Test void configuredGlobalRoutesAreNeverUsedByPinnedExistingNativeReaders() throws Exception {
        var fixture = new ProductContentSessionTest.Fixture(root); writeCorpus(); writeGraph();
        Path foreign = Files.createDirectories(root.resolve("data/crawls/other-kb"));
        new UnifiedGraph().addEntity("secret", "RULE", "approved private sibling").save(foreign.resolve("graph.kgraph"));
        AtomicInteger remoteCalls = new AtomicInteger(); ToolRegistry registry = registry();
        Map<String, CliTool> retained = new LinkedHashMap<>();
        String previous = System.getProperty(GraphServiceRouting.URL_PROPERTY);
        try {
            System.setProperty(GraphServiceRouting.URL_PROPERTY, "https://never-contact.invalid/graph");
            for (String id : ProductContentSession.READERS) {
                registry.register(fake(id, remoteCalls, null)); retained.put(id, registry.get(id));
            }
            ToolRegistryFactory.registerProductContentReaders(registry, JSON);
            fixture.bind(registry); var context = fixture.context(registry); Map<String, List<Byte>> before = snapshot();
            for (String id : ProductContentSession.READERS) {
                ToolResult result = retained.get(id).execute(request(id), context);
                assertFalse(result.isError(), id + ": " + result.getOutput());
                assertFalse(result.getOutput().contains("private sibling"), result.getOutput());
                if (Set.of("knowledge_search", "rag_search").contains(id)) {
                    assertEquals("lexical-only", result.getMetadata().get("retrievalMode"));
                    assertTrue(result.getOutput().contains("approved public reference"), result.getOutput());
                }
            }
            ObjectNode calculation = JSON.createObjectNode().put("operation", "CALCULATE");
            calculation.putObject("quantitative").putObject("target").put("entityId", "total");
            ToolResult calculated = retained.get("graph_reasoning_query").execute(calculation, context);
            assertFalse(calculated.isError(), calculated.getOutput());
            assertTrue(calculated.getOutput().contains("30"), calculated.getOutput());
            assertEquals(0, remoteCalls.get()); assertEquals(before, snapshot());
            assertFalse(Files.exists(root.resolve("data/crawls/elthoria-knowledge/semantic-index.jsonl")));
        } finally {
            if (previous == null) System.clearProperty(GraphServiceRouting.URL_PROPERTY);
            else System.setProperty(GraphServiceRouting.URL_PROPERTY, previous);
        }
    }

    @Test void missingOverrideFailsClosedWhileOtherProductsAndOrdinaryModeStayUnchanged() throws Exception {
        var fixture = new ProductContentSessionTest.Fixture(root); writeCorpus();
        AtomicInteger calls = new AtomicInteger(); ToolRegistry guarded = registry();
        CliTool original = fake("knowledge_search", calls, null); guarded.register(original);
        CliTool retained = guarded.get(original.id()); fixture.bind(guarded);
        assertTrue(retained.execute(request(original.id()), fixture.context(guarded)).isError()); assertEquals(0, calls.get());

        ObjectNode manifest = (ObjectNode) JSON.readTree(root.resolve("policy.json").toFile()); manifest.remove("nativeActionPolicy");
        JSON.writeValue(root.resolve("other-policy.json").toFile(), manifest);
        ProductChatPolicy otherPolicy = ProductChatPolicy.load(root.resolve("other-policy.json"), root);
        ToolRegistry other = registry(); other.register(original);
        other.registerProductContentDelegate(fake("knowledge_search", new AtomicInteger(), null));
        other.freezeProductPolicy(otherPolicy, Set.of("*")); other.openProductTurn();
        ObjectNode ordinaryArgs = request(original.id()); ordinaryArgs.putObject("chatModel").put("dryRun", true);
        assertFalse(other.get(original.id()).execute(ordinaryArgs, fixture.context(other)).isError()); assertEquals(1, calls.get());
        ToolRegistry ordinary = new ToolRegistry(JSON); ordinary.register(original); assertSame(original, ordinary.get(original.id()));
    }

    private static ToolRegistry registry() { return ToolRegistry.createProduct(() -> new ToolRegistry(JSON)); }
    private static ObjectNode request(String id) {
        ObjectNode request = JSON.createObjectNode();
        if (!"knowledge_status".equals(id)) request.put("query", "approved");
        if ("graph_reasoning_query".equals(id)) { request.remove("query"); request.put("operation", "SEARCH").put("question", "approved"); }
        return request;
    }
    private static CliTool fake(String id, AtomicInteger calls, AtomicReference<JsonNode> seen) {
        return new CliTool() {
            public String id() { return id; }
            public String description() { return id; }
            public JsonNode parameterSchema() { return JSON.createObjectNode(); }
            public String permissionKey() { return id; }
            public ToolResult execute(JsonNode params, ToolContext context) {
                calls.incrementAndGet(); if (seen != null) { seen.set(params.deepCopy()); assertNotNull(context.getProductManagedWork()); }
                return ToolResult.success("delegate");
            }
        };
    }
    private void writeCorpus() throws Exception {
        Path directory = Files.createDirectories(root.resolve("data/crawls/elthoria-knowledge"));
        JSON.writeValue(directory.resolve("crawl-result.json").toFile(), Map.of("status", "COMPLETED", "documentCount", 1, "chunkCount", 1));
        Files.writeString(directory.resolve("chunks.jsonl"), "{\"documentId\":\"doc\",\"chunkId\":\"chunk\",\"text\":\"approved public reference\"}\n");
    }
    private void writeGraph() throws Exception {
        Path directory = Files.createDirectories(root.resolve("data/crawls/elthoria-knowledge"));
        new UnifiedGraph().graphId("local:product:elthoria-knowledge").addEntity("approved-rule", "RULE", "approved public reference")
                .addEntity(GraphEntity.builder("a1").type("CELL").label("Input A").attribute("cell_reference", "Sheet1!A1").attribute("value", 10.0).build())
                .addEntity(GraphEntity.builder("a2").type("CELL").label("Input B").attribute("cell_reference", "Sheet1!A2").attribute("value", 20.0).build())
                .addEntity(GraphEntity.builder("total").type("FORMULA_CELL").label("Total").attribute("cell_reference", "Sheet1!A3")
                        .attribute("formula", "SUM(Sheet1!A1:Sheet1!A2)").attribute("displayValue", "30").attribute("validated", true).build())
                .addRelation("d1", "total", "a1", "DEPENDS_ON", 1.0).addRelation("d2", "total", "a2", "DEPENDS_ON", 1.0)
                .save(directory.resolve("graph.kgraph"));
    }
    private Map<String, List<Byte>> snapshot() throws Exception {
        Map<String, List<Byte>> files = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                List<Byte> bytes = new ArrayList<>(); for (byte value : Files.readAllBytes(path)) bytes.add(value);
                files.put(root.relativize(path).toString(), bytes);
            }
        }
        return files;
    }
}
