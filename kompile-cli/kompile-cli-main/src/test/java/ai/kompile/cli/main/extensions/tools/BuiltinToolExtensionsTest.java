package ai.kompile.cli.main.extensions.tools;

import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.KompileBackendClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinToolExtensionsTest {
    private static final Set<String> GUARDED = Set.of("code_search", "code_graph", "local_code_index",
            "crawl_source", "crawl_documents", "crawl_control", "crawl_result", "model_runtime", "pipeline",
            "graph_embeddings");
    private static final Map<String, Set<String>> OWNERSHIP = Map.of(
            "builtin.code", Set.of("code_search", "code_graph", "local_code_index"),
            "builtin.knowledge", Set.of("knowledge_search", "knowledge_status", "rag_search", "graph_search"),
            "builtin.graph", Set.of("graph_aggregate", "graph_forecast", "graph_centrality", "knowledge_graph",
                    "ask_graph_query", "ask_graph_verify", "ask_graph_assert", "ask_graph_retract", "ask_graph_mebn",
                    "ask_graph_explain", "ask_graph_explain_fused", "ask_graph_synthesize", "ask_graph_subscribe",
                    "graph_reason", "graph_import", "graph_export", "process_mining", "ask_graph_claim",
                    "graph_reasoning_query", "graph_bayes", "graph_embeddings", "graph_simulate"),
            "builtin.crawl", Set.of("crawl_source", "crawl_documents", "crawl_discover", "crawl_control", "crawl_result"),
            "builtin.model", Set.of("model_runtime", "vlm_model_definition", "subprocess_watchdog"),
            "builtin.pipeline", Set.of("pipeline"));

    @Test
    void descriptorsExactlyOwnConstructedIdsAndRetainExistingChatGuards() {
        var context = context(ToolExtensionContext.Surface.CHAT, null, null, null);
        var loader = new ToolExtensionLoader(extensions(), Set.of(), context);
        Set<String> ids = new HashSet<>();
        for (var extension : extensions()) {
            Set<String> constructed = new HashSet<>();
            for (var contribution : extension.createTools(context)) {
                String id = contribution.tool().id();
                assertTrue(ids.add(id), "duplicate built-in id: " + id);
                assertTrue(constructed.add(id));
                assertEquals(extension.descriptor().id(), loader.catalog().ownerOfTool(id).orElseThrow().id());
                assertEquals(GUARDED.contains(id), contribution.chatHighMemoryGuard(), id);
                assertEquals(id, loader.contribution(id).tool().id());
            }
            assertEquals(OWNERSHIP.get(extension.descriptor().id()), constructed);
            assertEquals(constructed, extension.descriptor().tools().stream()
                    .map(ai.kompile.cli.plugin.api.CliToolDescriptor::id).collect(java.util.stream.Collectors.toSet()));
        }
        assertEquals(loader.catalog().toolIds(), ids);
    }

    @Test
    void chatAndMcpHaveIdenticalFamilyToolsExceptHistoricalChatOnlyVlmDefinition() {
        Map<String, Class<?>> chat = constructedTypes(ToolExtensionContext.Surface.CHAT);
        Map<String, Class<?>> mcp = constructedTypes(ToolExtensionContext.Surface.MCP);
        assertNotNull(chat.remove("vlm_model_definition"));
        assertEquals(chat, mcp);
    }

    @Test
    void suppliedAppGraphAndCrawlRoutesArePreservedOnBothHostsWithoutNetworkCalls() throws Exception {
        // Existing tools share this singleton. Restore all fields touched by their constructors.
        var backend = KompileBackendClient.getInstance();
        AtomicReference<String> pinned = field(backend, "pinnedBaseUrl");
        AtomicReference<String> cached = field(backend, "cachedBaseUrl");
        AtomicLong lastSuccess = field(backend, "lastSuccessTimeMs");
        String previousPin = pinned.get();
        String previousCached = cached.get();
        long previousSuccess = lastSuccess.get();
        try {
            for (var surface : ToolExtensionContext.Surface.values()) {
                var loader = new ToolExtensionLoader(extensions(), Set.of(), context(surface,
                        "https://app.example.invalid", "https://graph.example.invalid", "https://crawl.example.invalid"));
                assertEquals("https://app.example.invalid", field(tool(loader, "knowledge_search"), "baseUrl"));
                assertEquals("https://app.example.invalid", field(tool(loader, "knowledge_status"), "baseUrl"));
                assertTrue((Boolean) field(tool(loader, "code_search"), "remoteConfigured"));
                assertEquals("https://app.example.invalid", pinned.get());
                assertRoute(loader, "graph_import", "client", "https://graph.example.invalid");
                assertRoute(loader, "graph_export", "client", "https://graph.example.invalid");
                assertRoute(loader, "graph_reasoning_query", "groundingClient", "https://graph.example.invalid");
                for (String id : OWNERSHIP.get("builtin.crawl")) {
                    assertRoute(loader, id, "client", "https://crawl.example.invalid");
                }
            }
        } finally {
            pinned.set(previousPin);
            cached.set(previousCached);
            lastSuccess.set(previousSuccess);
        }
    }

    @Test
    void explicitLocalRoutesStayLocalAndCodeLearningIsNotAdvertisedAsModelFree() throws Exception {
        var code = new ToolExtensionLoader(List.of(new BuiltinToolExtensions.Code()), Set.of(),
                context(ToolExtensionContext.Surface.CHAT, null, null, null));
        for (String id : Set.of("code_search", "local_code_index")) {
            assertEquals(Set.of(), code.catalog().missingCapabilities(id, "index"));
            assertEquals(Set.of(), code.catalog().missingCapabilities(id, "search"));
            var descriptor = code.catalog().ownerOfTool(id).orElseThrow().tools().stream()
                    .filter(tool -> tool.id().equals(id)).findFirst().orElseThrow();
            assertEquals(Set.of("graph.learning"), descriptor.conditionalCapabilities().get("index"));
            assertEquals(Set.of("graph.learning"), descriptor.conditionalCapabilities().get("search"));
            assertEquals(Set.of(), code.catalog().missingCapabilities(id, "stats"));
        }
        for (String action : Set.of("build", "add_directory", "learning_config_get", "learning_config_update", "search")) {
            assertEquals(Set.of(), code.catalog().missingCapabilities("code_graph", action));
        }
        assertEquals(Set.of("graph.learning"), code.catalog().missingCapabilities("code_graph", "learn"));
        var loader = new ToolExtensionLoader(extensions(), Set.of(),
                context(ToolExtensionContext.Surface.CHAT, null, null, null));
        assertRoute(loader, "graph_import", "client", null);
        assertRoute(loader, "graph_reasoning_query", "groundingClient", null);
        assertRoute(loader, "crawl_documents", "client", null);
        assertFalse((Boolean) field(tool(loader, "code_search"), "remoteConfigured"));
        assertEquals(Set.of(), loader.catalog().missingCapabilities("code_graph", "learn"));
    }

    private static List<CliToolExtension> extensions() {
        return List.of(new BuiltinToolExtensions.Code(), new BuiltinToolExtensions.Knowledge(),
                new BuiltinToolExtensions.Graph(), new BuiltinToolExtensions.Crawl(),
                new BuiltinToolExtensions.Model(), new BuiltinToolExtensions.Pipeline());
    }

    private static ToolExtensionContext context(ToolExtensionContext.Surface surface, String app,
                                                 String graph, String crawl) {
        return new ToolExtensionContext(new ObjectMapper(), app, graph, crawl, Path.of("."), surface);
    }

    private static Map<String, Class<?>> constructedTypes(ToolExtensionContext.Surface surface) {
        Map<String, Class<?>> result = new LinkedHashMap<>();
        var context = context(surface, null, null, null);
        for (var extension : extensions()) {
            for (var contribution : extension.createTools(context)) {
                assertNull(result.put(contribution.tool().id(), contribution.tool().getClass()));
            }
        }
        return result;
    }

    private static CliTool tool(ToolExtensionLoader loader, String id) {
        return loader.contribution(id).tool();
    }

    private static void assertRoute(ToolExtensionLoader loader, String id, String clientField, String route)
            throws Exception {
        Object client = field(tool(loader, id), clientField);
        assertEquals(route, field(client, "baseUrl"), id);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(instance);
    }
}
