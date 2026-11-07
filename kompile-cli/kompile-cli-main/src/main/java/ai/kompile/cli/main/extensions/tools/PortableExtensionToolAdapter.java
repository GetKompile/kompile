package ai.kompile.cli.main.extensions.tools;

import ai.kompile.cli.main.chat.enforcer.ShellMandatePolicy;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.plugin.api.tools.ExtensionTool;
import ai.kompile.cli.plugin.api.tools.ToolInvocationContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Captures portable metadata once and retains the host's permission and mandate boundary. */
public final class PortableExtensionToolAdapter implements CliTool {
    private final ExtensionTool<ToolInvocationContext> delegate;
    private final String id;
    private final String description;
    private final JsonNode schema;
    private final String permissionKey;
    private final McpToolAnnotations annotations;
    private final String compactHint;
    private final boolean backgroundable;

    public PortableExtensionToolAdapter(ExtensionTool<ToolInvocationContext> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "tool");
        this.id = requireNonBlank(delegate.id(), "tool id");
        this.description = delegate.description();
        JsonNode parameters = delegate.parameterSchema();
        if (parameters == null || !parameters.isObject()) {
            throw new IllegalArgumentException("Tool parameter schema must be an object: " + id);
        }
        this.schema = parameters.deepCopy();
        this.permissionKey = requireNonBlank(delegate.permissionKey(), "permission key");
        this.annotations = delegate.mcpAnnotations();
        this.compactHint = delegate.compactHint();
        this.backgroundable = delegate.isBackgroundable();
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Blank " + label);
        return value;
    }

    @Override public String id() { return id; }
    @Override public String description() { return description; }
    @Override public JsonNode parameterSchema() { return schema.deepCopy(); }
    @Override public String permissionKey() { return permissionKey; }
    @Override public McpToolAnnotations mcpAnnotations() { return annotations; }
    @Override public String compactHint() { return compactHint; }
    @Override public boolean isBackgroundable() { return backgroundable; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey, "Call extension tool " + id);
        var mandate = ShellMandatePolicy.evaluateFromSerializedArgs(
                id, params == null ? "{}" : params.toString(), context.getWorkingDirectory());
        if (mandate != null) return ToolResult.error(mandate.getCorrectionPrompt());
        if (context.isAborted()) throw new ToolExecutionException("Extension tool aborted: " + id);
        return delegate.execute(params, context);
    }
}
