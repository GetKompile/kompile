package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.ProductChatPolicy;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryProductPolicyTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private ProductChatPolicy policy() throws Exception {
        Files.writeString(root.resolve("role.md"), "Frozen role");
        Files.writeString(root.resolve("mcp.json"), "{}");
        Files.writeString(root.resolve("policy.json"), """
                {"schemaVersion":"kompile.product-chat-policy.v1","id":"play","title":"Play","role":"gm",
                "roleFile":"role.md","mcpConfigFile":"mcp.json","assetsDirectory":"assets","nativeTools":["read","grep"],
                "mcpServers":["elthoria"],"readCapabilities":{},"idleTimeoutSeconds":1800,"turnTimeoutSeconds":300}
                """);
        return ProductChatPolicy.load(root.resolve("policy.json"), root);
    }

    private CliTool tool(String id, AtomicInteger calls) {
        return new CliTool() {
            public String id() { return id; }
            public String description() { return id; }
            public JsonNode parameterSchema() { return mapper.createObjectNode(); }
            public String permissionKey() { return "read"; }
            public ToolResult execute(JsonNode p, ToolContext c) { calls.incrementAndGet(); return ToolResult.success("ok"); }
        };
    }

    @Test void deniesUnadvertisedExemptAndRetainedToolExecutionAndLateRegistration() throws Exception {
        ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        AtomicInteger calls = new AtomicInteger();
        registry.register(tool("read", calls));
        registry.register(tool("grep", calls));
        registry.register(tool("bash", calls));
        registry.register(tool("crawl_documents", calls));
        registry.register(tool("graph_search", calls));
        CliTool retained = registry.get("bash");
        ProductChatPolicy policy = policy();
        registry.freezeProductPolicy(policy, Set.of("read"));
        registry.openProductTurn();
        assertTrue(retained.execute(mapper.createObjectNode(), null).isError());
        assertEquals(0, calls.get());
        assertNull(registry.get("bash"));
        assertNull(registry.get("grep")); // manifest alone cannot widen the frozen role.
        var agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        registry.getDynamicToolManager().activateGroup("crawl");
        registry.getDynamicToolManager().activateGroup("graph_query");
        assertEquals(Set.of("read"), registry.ids());
        assertEquals(1, registry.buildDirectToolDefinitions(agent).size());
        assertEquals(1, registry.buildProgressiveDirectToolDefinitions(agent).size());
        assertFalse(registry.get("read").execute(null, null).isError());
        assertThrows(SecurityException.class, () -> registry.register(tool("bash", calls)));
        assertThrows(SecurityException.class, () -> registry.registerProductMcp(tool("mcp__foreign__read", calls), policy));
        registry.registerProductMcp(tool("mcp__elthoria__get_play_connection", calls), policy);
        assertThrows(SecurityException.class, () -> registry.registerProductMcp(tool("mcp__elthoria__get_play_connection", calls), policy));
        registry.stopProductAdmission();
        assertTrue(registry.get("read").execute(null, null).isError());
        registry.awaitProductQuiescence();
    }

    @Test void quiescenceDrainsRealDelegatesRatherThanFutureCancellation() throws Exception {
        ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), drained = new CountDownLatch(1);
        registry.register(new CliTool() {
            public String id() { return "read"; }
            public String description() { return "blocked read"; }
            public JsonNode parameterSchema() { return mapper.createObjectNode(); }
            public String permissionKey() { return "read"; }
            public ToolResult execute(JsonNode p, ToolContext c) {
                entered.countDown();
                boolean interrupted = false;
                for (;;) { try { release.await(); break; } catch (InterruptedException e) { interrupted = true; } }
                if (interrupted) Thread.currentThread().interrupt();
                return ToolResult.success("done");
            }
        });
        registry.freezeProductPolicy(policy(), Set.of("read"));
        registry.openProductTurn();
        Thread delegate = new Thread(() -> { try { registry.get("read").execute(null, null); } catch (Exception e) { throw new AssertionError(e); } });
        Thread waiter = new Thread(() -> { registry.awaitProductQuiescence(); drained.countDown(); });
        delegate.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            registry.stopProductAdmission();
            delegate.interrupt();
            waiter.start();
            assertFalse(drained.await(100, TimeUnit.MILLISECONDS));
            assertTrue(registry.get("read").execute(null, null).isError());
        } finally { release.countDown(); delegate.join(2000); waiter.join(2000); }
        assertEquals(0, drained.getCount());
        registry.openProductTurn();
        ToolContext stale = new ToolContext("old-turn", null,
                new ai.kompile.cli.main.chat.permission.PermissionService(), root, registry);
        stale.abort();
        assertTrue(registry.get("read").execute(null, stale).isError(),
                "a late cancelled tool worker must stay denied even after next-turn admission opens");
        registry.awaitProductQuiescence();
    }

    @Test void unknownOutcomePermanentlyDeniesEvenRetainedApprovedWrappers() throws Exception {
        ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        AtomicInteger calls = new AtomicInteger();
        registry.register(tool("read", calls));
        CliTool retained = registry.get("read");
        registry.freezeProductPolicy(policy(), Set.of("read"));
        assertTrue(registry.isProductMode());
        registry.openProductTurn();
        ToolContext foreign = new ToolContext("foreign", null,
                new ai.kompile.cli.main.chat.permission.PermissionService(), root, new ToolRegistry(mapper));
        assertTrue(retained.execute(null, foreign).isError(), "transport outcome must route back to this product registry");
        assertFalse(retained.execute(null, null).isError());
        registry.markProductUnknownOutcome();
        registry.awaitProductQuiescence(); // local return/drain must not clear remote uncertainty
        assertTrue(registry.hasProductUnknownOutcome());
        assertTrue(retained.execute(null, null).isError());
        assertTrue(registry.get("read").execute(null, null).isError());
        assertThrows(IllegalStateException.class, registry::openProductTurn);
        registry.stopProductAdmission();
        registry.awaitProductQuiescence();
        assertThrows(IllegalStateException.class, registry::openProductTurn);
        assertEquals(1, calls.get());
    }

    @Test void contentModeSelectsPinnedLocalDelegateEvenThroughRetainedWrapper() throws Exception {
        ProductChatPolicy legacy = policy();
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(legacy.manifestPath()));
        manifest.put("nativeActionPolicy", ProductChatPolicy.ELTHORIA_CONTENT);
        manifest.putArray("nativeTools").add("pipeline");
        Files.writeString(legacy.manifestPath(), manifest.toString());
        ProductChatPolicy content = ProductChatPolicy.load(legacy.manifestPath(), root);
        ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        AtomicInteger inheritedRemoteCalls = new AtomicInteger(), pinnedLocalCalls = new AtomicInteger();
        registry.register(tool("pipeline", inheritedRemoteCalls));
        CliTool retained = registry.get("pipeline");
        registry.registerProductContentDelegate(tool("pipeline", pinnedLocalCalls));
        registry.freezeProductPolicy(content, Set.of("pipeline"));
        Path sources = Files.createDirectories(root.resolve("data/input_documents/elthoria"));
        registry.productContentSession().bindKnowledgeScope(mapper.valueToTree(java.util.Map.of(
                "mode", "folder-local", "workspace", root.toString(), "sources_directory", sources.toString(),
                "knowledge_base", "content-knowledge", "crawl_directory", root.resolve("data/crawls/content-knowledge").toString(),
                "graph_path", root.resolve("data/crawls/content-knowledge/graph.kgraph").toString())));
        registry.openProductTurn();
        ToolContext context = new ToolContext("content", null, new ai.kompile.cli.main.chat.permission.PermissionService(), root, registry);
        context.setAutoApproveAll(true);
        assertFalse(retained.execute(mapper.createObjectNode().put("action", "list"), context).isError());
        assertEquals(0, inheritedRemoteCalls.get()); assertEquals(1, pinnedLocalCalls.get());
        assertNull(context.getProductManagedWork());
        assertThrows(IllegalStateException.class, () -> registry.registerProductContentDelegate(tool("pipeline", inheritedRemoteCalls)));
    }

    @Test void absentNativeActionFlagDoesNotSelectContentAlternatives() throws Exception {
        ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        AtomicInteger originalCalls = new AtomicInteger(), alternativeCalls = new AtomicInteger();
        registry.register(tool("read", originalCalls)); registry.registerProductContentDelegate(tool("read", alternativeCalls));
        registry.freezeProductPolicy(policy(), Set.of("read")); registry.openProductTurn();
        assertNull(registry.productContentSession());
        assertFalse(registry.get("read").execute(null, null).isError());
        assertEquals(1, originalCalls.get()); assertEquals(0, alternativeCalls.get());
    }

    @Test void ordinaryRegistrationsKeepTheirConcreteIdentityAndBehavior() {
        ToolRegistry registry = new ToolRegistry(mapper);
        CliTool ordinary = tool("bash", new AtomicInteger());
        registry.register(ordinary);
        assertSame(ordinary, registry.get("bash"));
        registry.markProductUnknownOutcome();
        assertFalse(registry.isProductMode());
        assertFalse(registry.hasProductUnknownOutcome());
    }
}
