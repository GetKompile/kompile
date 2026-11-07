package ai.kompile.cli.main.extensions.tools;

import ai.kompile.cli.plugin.api.CliExtension;

import java.util.List;

/** Host-side tool factory; metadata is validated before this method is called. */
public interface CliToolExtension extends CliExtension {
    List<ToolContribution> createTools(ToolExtensionContext context);

    /** Explicit, side-effect-free surface omissions; all other declared tools are mandatory. */
    default java.util.Set<String> omittedTools(ToolExtensionContext context) {
        return java.util.Set.of();
    }
}
