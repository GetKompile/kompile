package ai.kompile.cli.main.extensions.tools;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.plugin.api.CliExtensionCatalog;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import ai.kompile.cli.plugin.api.CliToolDescriptor;
import ai.kompile.cli.plugin.api.tools.CliToolProvider;
import ai.kompile.cli.plugin.api.tools.ExtensionTool;
import ai.kompile.cli.plugin.api.tools.ProvidedTool;
import ai.kompile.cli.plugin.api.tools.ToolInvocationContext;
import ai.kompile.cli.plugin.api.tools.ToolProviderContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PortableToolProviderTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void catalogIsSharedAndEachSurfaceConstructsOnlyRequestedFamiliesOnce() {
        var first = new PortableFixtureProvider("first", "first_tool");
        var second = new PortableFixtureProvider("second", "second_tool");
        var catalog = new CliExtensionCatalog(List.of(first, second), Set.of());
        for (var surface : ToolExtensionContext.Surface.values()) {
            int previousCalls = first.factoryCalls;
            int previousSecondCalls = second.factoryCalls;
            var loader = new ToolExtensionLoader(catalog, factoryContext(surface));
            assertSame(catalog, loader.catalog());
            assertEquals(previousCalls, first.factoryCalls);
            var contribution = loader.contribution("first_tool");
            assertSame(contribution, loader.contribution("first_tool"));
            assertEquals(previousCalls + 1, first.factoryCalls);
            assertEquals(previousSecondCalls, second.factoryCalls);
            assertSame(mapper, first.factoryContext.objectMapper());
            assertEquals("http://base", first.factoryContext.baseUrl());
            assertEquals("http://graph", first.factoryContext.graphBaseUrl());
            assertEquals("http://crawl", first.factoryContext.crawlBaseUrl());
            assertEquals(directory, first.factoryContext.workingDirectory());
            assertEquals(surface.name(), first.factoryContext.surface().name());
            assertEquals(List.of("second_tool"), loader.additionalContributions(Set.of("first_tool")).stream()
                    .map(tool -> tool.tool().id()).toList());
            assertTrue(loader.additionalContributions(Set.of("first_tool", "second_tool")).isEmpty());
            assertEquals(previousSecondCalls + 1, second.factoryCalls);
        }
        assertEquals(1, first.metadataReads);
        assertEquals(1, second.metadataReads);
    }

    @Test
    void guardMetadataResultsAndInvocationContextPropagateOnBothSurfaces() throws Exception {
        for (var surface : ToolExtensionContext.Surface.values()) {
            var provider = new PortableFixtureProvider("portable", "portable_tool");
            var contribution = loader(provider, surface).contribution("portable_tool");
            CliTool tool = contribution.tool();
            assertTrue(contribution.chatHighMemoryGuard());
            assertEquals(provider.body.schema, tool.parameterSchema());
            assertEquals(provider.body.annotations.toJsonNode(), tool.mcpAnnotations().toJsonNode());
            assertEquals("Portable fixture", tool.description());
            assertEquals("Fixture compact hint", tool.compactHint());
            assertTrue(tool.isBackgroundable());
            var context = invocationContext(new PermissionService());
            JsonNode params = mapper.createObjectNode().put("value", "input");
            ToolResult result = tool.execute(params, context);
            assertSame(provider.body.result, result);
            assertEquals("payload", result.getOutput());
            assertEquals(Map.of("fixture", true), result.getMetadata());
            assertTrue(result.isError());
            assertSame(params, provider.body.lastParams);
            assertSame(context, provider.body.lastContext);
            assertEquals("portable-session", provider.body.sessionId);
            assertEquals(directory.resolve("inside.txt"), provider.body.resolvedPath);
        }
    }

    @Test
    void mutableMetadataCannotRetargetIdentityPermissionOrPublishedSchema() {
        var provider = new PortableFixtureProvider("portable", "portable_tool");
        provider.body.permission = "write";
        var loader = loader(provider, ToolExtensionContext.Surface.CHAT);
        CliTool tool = loader.contribution("portable_tool").tool();
        JsonNode originalSchema = tool.parameterSchema();
        provider.body.id = "read";
        provider.body.permission = "read";
        provider.body.description = "changed";
        provider.body.hint = "changed";
        provider.body.backgroundable = false;
        provider.body.annotations = McpToolAnnotations.DESTRUCTIVE;
        ((ObjectNode) provider.body.schema).put("changed", true);
        ((ObjectNode) tool.parameterSchema()).put("consumerChanged", true);
        assertEquals("portable_tool", tool.id());
        assertEquals("write", tool.permissionKey());
        assertEquals("Portable fixture", tool.description());
        assertEquals("Fixture compact hint", tool.compactHint());
        assertTrue(tool.isBackgroundable());
        assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        assertEquals(originalSchema, tool.parameterSchema());
        assertTrue(loader.additionalContributions(Set.of("read", "portable_tool")).isEmpty());
        var permissions = new PermissionService();
        permissions.setUserOverride("write", PermissionService.PermissionLevel.DENY);
        var denied = assertThrows(ToolExecutionException.class, () -> tool.execute(
                mapper.createObjectNode().put("id", "read").put("permissionKey", "read"),
                invocationContext(permissions)));
        assertTrue(denied.isPermissionDenied());
        assertEquals(0, provider.body.bodyCalls);
    }

    @Test
    void malformedPortableMetadataIsRejected() {
        for (String id : new String[]{null, "", " "}) {
            var body = new PortableFixtureProvider.FixtureTool("valid");
            body.id = id;
            assertThrows(IllegalArgumentException.class, () -> new PortableExtensionToolAdapter(body));
        }
        for (String key : new String[]{null, "", " "}) {
            var body = new PortableFixtureProvider.FixtureTool("valid");
            body.permission = key;
            assertThrows(IllegalArgumentException.class, () -> new PortableExtensionToolAdapter(body));
        }
        for (JsonNode schema : new JsonNode[]{null, mapper.createArrayNode(), mapper.getNodeFactory().textNode("bad")}) {
            var body = new PortableFixtureProvider.FixtureTool("valid");
            body.schema = schema;
            assertThrows(IllegalArgumentException.class, () -> new PortableExtensionToolAdapter(body));
        }
        assertThrows(NullPointerException.class, () -> new ProvidedTool(null, false));
    }

    @Test
    void invalidCatalogAndHostConflictsNeverConstructAnyPortableTool() {
        var valid = new PortableFixtureProvider("valid", "valid_tool");
        var duplicate = new PortableFixtureProvider("duplicate", "valid_tool");
        assertThrows(IllegalArgumentException.class, () -> new ToolExtensionLoader(
                List.of(valid, duplicate), Set.of(), factoryContext(ToolExtensionContext.Surface.CHAT)));
        var missing = new PortableFixtureProvider("missing", "missing_tool");
        missing.requiredCapabilities = Set.of("not.installed");
        assertThrows(IllegalArgumentException.class, () -> new ToolExtensionLoader(
                List.of(valid, missing), Set.of(), factoryContext(ToolExtensionContext.Surface.CHAT)));
        var unsupported = new PortableFixtureProvider("future", "future_tool");
        unsupported.apiVersion = 2;
        assertThrows(IllegalArgumentException.class, () -> new ToolExtensionLoader(
                List.of(valid, unsupported), Set.of(), factoryContext(ToolExtensionContext.Surface.CHAT)));
        assertThrows(IllegalArgumentException.class, () -> loader(valid, ToolExtensionContext.Surface.CHAT)
                .additionalContributions(Set.of("valid_tool")));
        assertEquals(0, valid.factoryCalls);
        assertEquals(0, duplicate.factoryCalls);
        assertEquals(0, missing.factoryCalls);
        assertEquals(0, unsupported.factoryCalls);
    }

    @Test
    void factoriesMustSupplyExactlyTheirDeclaredNonNullTools() {
        var missing = new PortableFixtureProvider("missing", "declared");
        missing.provided = List.of();
        assertInvalidFactory(missing, IllegalArgumentException.class);
        var duplicate = new PortableFixtureProvider("duplicate", "declared");
        duplicate.provided = List.of(new ProvidedTool(duplicate.body, false), new ProvidedTool(duplicate.body, true));
        assertInvalidFactory(duplicate, IllegalArgumentException.class);
        var undeclared = new PortableFixtureProvider("undeclared", "declared");
        undeclared.body.id = "surprise";
        assertInvalidFactory(undeclared, IllegalArgumentException.class);
        var nullList = new PortableFixtureProvider("null-list", "declared");
        nullList.provided = null;
        assertInvalidFactory(nullList, NullPointerException.class);
        var nullTool = new PortableFixtureProvider("null-tool", "declared");
        nullTool.provided = Arrays.asList((ProvidedTool) null);
        assertInvalidFactory(nullTool, NullPointerException.class);
        var stealing = new PortableFixtureProvider("stealing", "declared");
        stealing.body.id = "other";
        var owner = new PortableFixtureProvider("owner", "other");
        var loader = new ToolExtensionLoader(List.of(stealing, owner), Set.of(),
                factoryContext(ToolExtensionContext.Surface.MCP));
        assertThrows(IllegalArgumentException.class, () -> loader.contribution("declared"));
        assertEquals(0, owner.factoryCalls);
    }

    @Test
    void factoryOrderAndDeliveredStateArePreserved() {
        var provider = new PortableFixtureProvider("ordered", "first_tool", "second_tool");
        provider.provided = List.of(new ProvidedTool(new PortableFixtureProvider.FixtureTool("second_tool"), false),
                new ProvidedTool(provider.body, true));
        var loader = loader(provider, ToolExtensionContext.Surface.MCP);
        assertEquals(List.of("second_tool", "first_tool"), loader.additionalContributions(Set.of()).stream()
                .map(contribution -> contribution.tool().id()).toList());
        assertTrue(loader.additionalContributions(Set.of("first_tool", "second_tool")).isEmpty());
        assertEquals(1, provider.factoryCalls);
    }

    @Test
    void explicitSurfaceOmissionsAreValidatedAndDoNotReconstructTheFactory() {
        var provider = new PortableFixtureProvider("omitted", "portable_tool");
        provider.omitOnChat = true;
        var catalog = new CliExtensionCatalog(List.of(provider), Set.of());
        var chat = new ToolExtensionLoader(catalog, factoryContext(ToolExtensionContext.Surface.CHAT));
        assertThrows(IllegalArgumentException.class, () -> chat.contribution("portable_tool"));
        assertTrue(chat.additionalContributions(Set.of()).isEmpty());
        assertEquals(1, provider.factoryCalls);
        var mcp = new ToolExtensionLoader(catalog, factoryContext(ToolExtensionContext.Surface.MCP));
        assertFalse(mcp.contribution("portable_tool").chatHighMemoryGuard());
        assertEquals(2, provider.factoryCalls);
        var invalid = new PortableFixtureProvider("invalid-omission", "declared");
        invalid.omitted = Set.of("undeclared");
        assertThrows(IllegalArgumentException.class, () -> loader(invalid, ToolExtensionContext.Surface.CHAT)
                .contribution("declared"));
        assertEquals(0, invalid.factoryCalls);
        var stillSupplied = new PortableFixtureProvider("still-supplied", "declared");
        stillSupplied.omitted = Set.of("declared");
        assertInvalidFactory(stillSupplied, IllegalArgumentException.class);
    }

    @Test
    void ambiguousFactoryInterfacesAreRejectedBeforeAnyFactoryRuns() {
        var valid = new PortableFixtureProvider("valid", "valid_tool");
        var ambiguous = new AmbiguousProvider();
        assertThrows(IllegalArgumentException.class, () -> new ToolExtensionLoader(List.of(valid, ambiguous),
                Set.of(), factoryContext(ToolExtensionContext.Surface.CHAT)));
        assertEquals(0, valid.factoryCalls);
        assertEquals(0, ambiguous.calls);
    }

    @Test
    void permissionDenialMandateAndAbortNeverEnterPortableBodyOnEitherSurface() {
        for (var surface : ToolExtensionContext.Surface.values()) {
            var denied = new PortableFixtureProvider("denied", "bash");
            var permissions = new PermissionService();
            permissions.setUserOverride("read", PermissionService.PermissionLevel.DENY);
            var failure = assertThrows(ToolExecutionException.class, () -> loader(denied, surface)
                    .contribution("bash").tool().execute(mapper.createObjectNode().put("command", "cat forbidden.txt"),
                            invocationContext(permissions)));
            assertTrue(failure.isPermissionDenied());
            assertEquals(0, denied.body.bodyCalls);
            var shell = new PortableFixtureProvider("shell", "bash");
            CliTool shellTool = loader(shell, surface).contribution("bash").tool();
            shell.body.id = "not_shell";
            ToolResult blocked = assertDoesNotThrow(() -> shellTool.execute(
                    mapper.createObjectNode().put("command", "cat forbidden.txt"),
                    invocationContext(new PermissionService())));
            assertTrue(blocked.isError());
            assertTrue(blocked.getOutput().contains("read"));
            assertEquals(0, shell.body.bodyCalls);
            var aborted = new PortableFixtureProvider("aborted", "aborted_tool");
            var context = invocationContext(new PermissionService());
            context.abort();
            assertThrows(ToolExecutionException.class, () -> loader(aborted, surface).contribution("aborted_tool")
                    .tool().execute(mapper.createObjectNode(), context));
            assertEquals(0, aborted.body.bodyCalls);
        }
    }

    @Test
    void additionalPermissionsAndExternalPathsUseTheRealHostContext() throws Exception {
        for (var surface : ToolExtensionContext.Surface.values()) {
            var provider = new PortableFixtureProvider("additional", "additional_tool");
            provider.body.additionalPermission = "write";
            var permissions = new PermissionService();
            permissions.setUserOverride("write", PermissionService.PermissionLevel.DENY);
            CliTool tool = loader(provider, surface).contribution("additional_tool").tool();
            var context = invocationContext(permissions);
            var denied = assertThrows(ToolExecutionException.class, () -> tool.execute(mapper.createObjectNode(), context));
            assertTrue(denied.isPermissionDenied());
            assertTrue(denied.getMessage().contains("write"));
            assertSame(context, provider.body.lastContext);
            provider.body.additionalPermission = null;
            provider.body.path = "../outside.txt";
            permissions.setUserOverride("external_directory", PermissionService.PermissionLevel.DENY);
            var external = assertThrows(ToolExecutionException.class, () -> tool.execute(mapper.createObjectNode(), context));
            assertTrue(external.isPermissionDenied());
            assertTrue(external.getMessage().contains("external_directory"));
        }
    }

    @Test
    void legacyExecuteDeclarationRemainsOnTheHostInterface() throws Exception {
        assertEquals(ToolResult.class, CliTool.class.getDeclaredMethod("execute", JsonNode.class, ToolContext.class)
                .getReturnType());
        assertTrue(ExtensionTool.class.isAssignableFrom(CliTool.class));
        assertTrue(ToolInvocationContext.class.isAssignableFrom(ToolContext.class));
    }

    private <T extends Throwable> void assertInvalidFactory(PortableFixtureProvider provider, Class<T> failure) {
        assertThrows(failure, () -> loader(provider, ToolExtensionContext.Surface.CHAT)
                .additionalContributions(Set.of()));
        assertEquals(1, provider.factoryCalls);
        assertEquals(0, provider.body.bodyCalls);
    }

    private ToolExtensionLoader loader(PortableFixtureProvider provider, ToolExtensionContext.Surface surface) {
        return new ToolExtensionLoader(List.of(provider), Set.of(), factoryContext(surface));
    }

    private ToolExtensionContext factoryContext(ToolExtensionContext.Surface surface) {
        return new ToolExtensionContext(mapper, "http://base", "http://graph", "http://crawl", directory, surface);
    }

    private ToolContext invocationContext(PermissionService permissions) {
        return new ToolContext("portable-session", null, permissions, directory, null);
    }

    private static final class AmbiguousProvider implements CliToolProvider, CliToolExtension {
        int calls;
        @Override public CliExtensionDescriptor descriptor() {
            return new CliExtensionDescriptor("ambiguous", "1", 1, Set.of(), Set.of(),
                    List.of(new CliToolDescriptor("ambiguous_tool", Set.of(), Map.of())), Set.of());
        }
        @Override public List<ProvidedTool> createTools(ToolProviderContext context) { calls++; return List.of(); }
        @Override public List<ToolContribution> createTools(ToolExtensionContext context) { calls++; return List.of(); }
    }
}

/** Independent fixture: only tool-api/plugin-api values, Jackson, and JDK types; no host implementation types. */
final class PortableFixtureProvider implements CliToolProvider {
    private final String extensionId;
    private final List<String> declaredIds;
    final FixtureTool body;
    List<ProvidedTool> provided;
    Set<String> requiredCapabilities = Set.of();
    Set<String> omitted = Set.of();
    int apiVersion = 1;
    int metadataReads;
    int factoryCalls;
    boolean omitOnChat;
    ToolProviderContext factoryContext;

    PortableFixtureProvider(String extensionId, String... declaredIds) {
        this.extensionId = extensionId;
        this.declaredIds = List.of(declaredIds);
        this.body = new FixtureTool(declaredIds[0]);
        this.provided = List.of(new ProvidedTool(body, true));
    }

    @Override public CliExtensionDescriptor descriptor() {
        metadataReads++;
        return new CliExtensionDescriptor(extensionId, "1", apiVersion, Set.of(), Set.of(),
                declaredIds.stream().map(id -> new CliToolDescriptor(id, requiredCapabilities, Map.of())).toList(), Set.of());
    }

    @Override public Set<String> omittedTools(ToolProviderContext context) {
        return omitOnChat && context.surface() == ToolProviderContext.Surface.CHAT ? Set.copyOf(declaredIds) : omitted;
    }

    @Override public List<ProvidedTool> createTools(ToolProviderContext context) {
        factoryCalls++;
        factoryContext = context;
        if (omitOnChat) {
            return context.surface() == ToolProviderContext.Surface.CHAT ? List.of() : List.of(new ProvidedTool(body, false));
        }
        return provided;
    }

    static final class FixtureTool implements ExtensionTool<ToolInvocationContext> {
        String id;
        String permission = "read";
        String description = "Portable fixture";
        String hint = "Fixture compact hint";
        boolean backgroundable = true;
        JsonNode schema = new ObjectMapper().createObjectNode().put("type", "object");
        McpToolAnnotations annotations = McpToolAnnotations.READ_ONLY;
        final ToolResult result = new ToolResult("portable", "payload", Map.of("fixture", true), true);
        int bodyCalls;
        JsonNode lastParams;
        ToolInvocationContext lastContext;
        String sessionId;
        Path resolvedPath;
        String additionalPermission;
        String path = "inside.txt";

        FixtureTool(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public String description() { return description; }
        @Override public JsonNode parameterSchema() { return schema; }
        @Override public String permissionKey() { return permission; }
        @Override public McpToolAnnotations mcpAnnotations() { return annotations; }
        @Override public String compactHint() { return hint; }
        @Override public boolean isBackgroundable() { return backgroundable; }
        @Override public ToolResult execute(JsonNode params, ToolInvocationContext context) throws ToolExecutionException {
            bodyCalls++;
            lastParams = params;
            lastContext = context;
            sessionId = context.getSessionId();
            if (additionalPermission != null) context.checkPermission(additionalPermission, "Fixture additional action");
            resolvedPath = context.resolvePath(path);
            return result;
        }
    }
}
