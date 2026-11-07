package ai.kompile.cli.main.extensions.tools;

import ai.kompile.cli.main.chat.tools.CliTool;

import java.util.Objects;

/** Chat retains its existing per-tool guard; MCP always uses its own registration wrapper. */
public record ToolContribution(CliTool tool, boolean chatHighMemoryGuard) {
    public ToolContribution {
        Objects.requireNonNull(tool, "tool");
    }
}
