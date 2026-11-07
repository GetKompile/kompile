package ai.kompile.cli.plugin.api.tools.fixture;

import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import ai.kompile.cli.plugin.api.CliToolDescriptor;
import ai.kompile.cli.plugin.api.tools.CliToolProvider;
import ai.kompile.cli.plugin.api.tools.ExtensionTool;
import ai.kompile.cli.plugin.api.tools.ProvidedTool;
import ai.kompile.cli.plugin.api.tools.ToolInvocationContext;
import ai.kompile.cli.plugin.api.tools.ToolProviderContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A provider implemented with only the public API and its declared dependencies. */
public final class StandaloneToolProvider implements CliToolProvider {
    private int factoryCalls;

    public int factoryCalls() { return factoryCalls; }

    @Override
    public CliExtensionDescriptor descriptor() {
        return new CliExtensionDescriptor("standalone", "1", CliExtensionDescriptor.API_VERSION,
                Set.of(), Set.of(), List.of(new CliToolDescriptor("echo", Set.of(), Map.of())), Set.of());
    }

    @Override
    public List<ProvidedTool> createTools(ToolProviderContext context) {
        factoryCalls++;
        ExtensionTool<ToolInvocationContext> tool = new ExtensionTool<>() {
            public String id() { return "echo"; }
            public String description() { return "Echo a message"; }
            public JsonNode parameterSchema() { return context.objectMapper().createObjectNode(); }
            public String permissionKey() { return "read"; }
            public ToolResult execute(JsonNode params, ToolInvocationContext invocation) throws ToolExecutionException {
                invocation.checkPermission(permissionKey(), description());
                return ToolResult.success(invocation.getSessionId(), params.asText(),
                        Map.of("path", invocation.resolvePath("echo.txt").toString()));
            }
        };
        return List.of(new ProvidedTool(tool, false));
    }

    public static ToolInvocationContext invocationContext(Path workingDirectory) {
        return new ToolInvocationContext() {
            public String getSessionId() { return "standalone-session"; }
            public Path getWorkingDirectory() { return workingDirectory; }
            public boolean isAborted() { return false; }
            public void checkPermission(String permissionKey, String description) { }
            public Path resolvePath(String path) { return workingDirectory.resolve(path); }
        };
    }
}
