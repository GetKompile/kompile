package ai.kompile.cli.plugin.api.tools.nativefixture;

import ai.kompile.cli.plugin.api.CliExtension;
import ai.kompile.cli.plugin.api.CliExtensionCatalog;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import ai.kompile.cli.plugin.api.CliToolDescriptor;
import ai.kompile.cli.plugin.api.tools.CliToolProvider;
import ai.kompile.cli.plugin.api.tools.ExtensionTool;
import ai.kompile.cli.plugin.api.tools.ProvidedTool;
import ai.kompile.cli.plugin.api.tools.ToolInvocationContext;
import ai.kompile.cli.plugin.api.tools.ToolProviderContext;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/** An independently compiled provider: only tool-api and its runtime dependencies are visible. */
public final class NativeToolProviderSmoke {
    public static void main(String[] args) throws Exception {
        for (String unavailable : List.of("ai.kompile.cli.main.MainCommand",
                "ai.kompile.cli.main.chat.tools.ToolContext", "ai.kompile.cli.common.util.JsonUtils",
                "ai.kompile.core.llm.StructuredChatLanguageModel")) {
            try {
                Class.forName(unavailable, false, NativeToolProviderSmoke.class.getClassLoader());
                throw new AssertionError("Host/local runtime leaked into provider: " + unavailable);
            } catch (ClassNotFoundException expected) { }
        }
        List<CliExtension> extensions = ServiceLoader.load(CliExtension.class).stream()
                .map(ServiceLoader.Provider::get).toList();
        var catalog = new CliExtensionCatalog(extensions, Set.of());
        if (catalog.extensions().size() != 1 || FixtureProvider.factories != 0
                || !catalog.toolIds().contains("portable_echo")) {
            throw new AssertionError("Discovery must validate metadata without constructing tools");
        }
        var provider = (CliToolProvider) catalog.extensions().get(0);
        var mapper = new ObjectMapper();
        for (var surface : ToolProviderContext.Surface.values()) {
            var environment = new ToolProviderContext(mapper, "http://localhost:8080", null, null,
                    Path.of(".").toAbsolutePath().normalize(), surface);
            var contribution = provider.createTools(environment).get(0);
            var tool = contribution.tool();
            var invocation = new FixtureInvocation(environment.workingDirectory());
            var result = tool.execute(mapper.createObjectNode().put("message", "independent"), invocation);
            if (result.isError() || !"echo:independent".equals(result.getOutput())
                    || !surface.name().equals(result.getMetadata().get("surface"))
                    || invocation.permissions != 1 || contribution.chatHighMemoryGuard()
                    || !tool.mcpAnnotations().toJsonNode().path("readOnlyHint").asBoolean()) {
                throw new AssertionError("Contributed tool contract failed on " + surface);
            }
        }
        if (FixtureProvider.factories != 2) throw new AssertionError("Unexpected factory count");
        System.out.println("PORTABLE_TOOL_PROVIDER_NATIVE_OK");
    }

    public static final class FixtureProvider implements CliToolProvider {
        static int factories;
        public CliExtensionDescriptor descriptor() {
            return new CliExtensionDescriptor("fixture.portable", "1", CliExtensionDescriptor.API_VERSION,
                    Set.of("fixture.portable"), Set.of(),
                    List.of(new CliToolDescriptor("portable_echo", Set.of(), Map.of())), Set.of());
        }
        public List<ProvidedTool> createTools(ToolProviderContext environment) {
            factories++;
            ExtensionTool<ToolInvocationContext> tool = new ExtensionTool<>() {
                public String id() { return "portable_echo"; }
                public String description() { return "Echo through the portable tool API"; }
                public String permissionKey() { return "fixture.echo"; }
                public JsonNode parameterSchema() {
                    return environment.objectMapper().createObjectNode().put("type", "object");
                }
                public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }
                public ToolResult execute(JsonNode params, ToolInvocationContext context) throws ToolExecutionException {
                    context.checkPermission(permissionKey(), description());
                    if (context.isAborted()) throw new ToolExecutionException("Cancelled");
                    return ToolResult.success("Portable echo", "echo:" + params.path("message").asText(),
                            Map.of("surface", environment.surface().name()));
                }
            };
            return List.of(new ProvidedTool(tool, false));
        }
    }

    private static final class FixtureInvocation implements ToolInvocationContext {
        private final Path directory;
        int permissions;
        FixtureInvocation(Path directory) { this.directory = directory; }
        public String getSessionId() { return "native-provider-fixture"; }
        public Path getWorkingDirectory() { return directory; }
        public boolean isAborted() { return false; }
        public void checkPermission(String key, String description) throws ToolExecutionException {
            if (!"fixture.echo".equals(key)) throw new ToolExecutionException("Unexpected permission", true);
            permissions++;
        }
        public Path resolvePath(String path) { return directory.resolve(path).normalize(); }
    }
}
