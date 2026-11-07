package ai.kompile.cli.plugin.api.tools;

import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.plugin.api.tools.fixture.StandaloneToolProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolApiContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path workingDirectory = Path.of("relative", "workspace");

    @Test
    void toolDefaultsAndTypedContextMatchTheCliContract() throws Exception {
        ExtensionTool<TestContext> tool = new ExtensionTool<>() {
            public String id() { return "echo"; }
            public String description() { return "Echo a message"; }
            public JsonNode parameterSchema() { return mapper.createObjectNode(); }
            public String permissionKey() { return "read"; }
            public ToolResult execute(JsonNode params, TestContext context) throws ToolExecutionException {
                context.checkPermission(permissionKey(), description());
                return ToolResult.success(context.getSessionId(), context.resolvePath(params.asText()).toString());
            }
        };
        var context = new TestContext("session", workingDirectory, false);

        assertEquals("echo", tool.id());
        assertEquals("Echo a message", tool.description());
        assertEquals("read", tool.permissionKey());
        assertTrue(tool.parameterSchema().isObject());
        assertSame(McpToolAnnotations.WRITE, tool.mcpAnnotations());
        assertNull(tool.compactHint());
        assertFalse(tool.isBackgroundable());
        assertFalse(context.isAborted());
        var result = tool.execute(mapper.getNodeFactory().textNode("file.txt"), context);
        assertEquals("session", result.getTitle());
        assertEquals(workingDirectory.resolve("file.txt").toString(), result.getOutput());
        var denied = assertThrows(ToolExecutionException.class,
                () -> tool.execute(mapper.getNodeFactory().textNode("file.txt"),
                        new TestContext("session", workingDirectory, true)));
        assertTrue(denied.isPermissionDenied());
    }

    @Test
    void providerContextRetainsInputsAndOnlyRequiresMapperDirectoryAndSurface() {
        for (var surface : ToolProviderContext.Surface.values()) {
            var context = new ToolProviderContext(mapper, "base", "graph", "crawl", workingDirectory, surface);
            assertSame(mapper, context.objectMapper());
            assertEquals("base", context.baseUrl());
            assertEquals("graph", context.graphBaseUrl());
            assertEquals("crawl", context.crawlBaseUrl());
            assertSame(workingDirectory, context.workingDirectory());
            assertSame(surface, context.surface());
            assertDoesNotThrow(() -> new ToolProviderContext(mapper, null, null, null, workingDirectory, surface));
        }
        assertEquals("objectMapper", assertThrows(NullPointerException.class,
                () -> new ToolProviderContext(null, null, null, null, workingDirectory,
                        ToolProviderContext.Surface.CHAT)).getMessage());
        assertEquals("workingDirectory", assertThrows(NullPointerException.class,
                () -> new ToolProviderContext(mapper, null, null, null, null,
                        ToolProviderContext.Surface.CHAT)).getMessage());
        assertEquals("surface", assertThrows(NullPointerException.class,
                () -> new ToolProviderContext(mapper, null, null, null, workingDirectory, null)).getMessage());
    }

    @Test
    void providersHaveNoImplicitConstructionAndOmitNothingByDefault() throws Exception {
        var provider = new StandaloneToolProvider();
        assertEquals("standalone", provider.descriptor().id());
        assertEquals(0, provider.factoryCalls());
        for (var surface : ToolProviderContext.Surface.values()) {
            var context = new ToolProviderContext(mapper, null, null, null, workingDirectory, surface);
            assertEquals(Set.of(), provider.omittedTools(context));
            assertThrows(UnsupportedOperationException.class, () -> provider.omittedTools(context).add("echo"));
            assertEquals(0, provider.factoryCalls());
        }
        var context = new ToolProviderContext(mapper, null, null, null, workingDirectory,
                ToolProviderContext.Surface.CHAT);
        ProvidedTool contribution = provider.createTools(context).get(0);
        assertEquals(1, provider.factoryCalls());
        assertFalse(contribution.chatHighMemoryGuard());
        assertSame(contribution.tool(), new ProvidedTool(contribution.tool(), true).tool());
        assertTrue(new ProvidedTool(contribution.tool(), true).chatHighMemoryGuard());
        assertEquals("tool", assertThrows(NullPointerException.class, () -> new ProvidedTool(null, false)).getMessage());
        var result = contribution.tool().execute(mapper.getNodeFactory().textNode("hello"),
                StandaloneToolProvider.invocationContext(workingDirectory));
        assertEquals("hello", result.getOutput());
    }

    private record TestContext(String sessionId, Path workingDirectory, boolean aborted)
            implements ToolInvocationContext {
        public String getSessionId() { return sessionId; }
        public Path getWorkingDirectory() { return workingDirectory; }
        public boolean isAborted() { return aborted; }
        public void checkPermission(String permissionKey, String description) throws ToolExecutionException {
            if (aborted) {
                throw new ToolExecutionException("denied", true);
            }
        }
        public Path resolvePath(String path) { return workingDirectory.resolve(path); }
    }
}
