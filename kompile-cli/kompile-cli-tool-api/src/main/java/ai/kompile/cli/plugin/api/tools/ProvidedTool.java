/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api.tools;

import java.util.Objects;

/** Chat retains its existing per-tool guard; MCP always uses its own registration wrapper. */
public record ProvidedTool(ExtensionTool<ToolInvocationContext> tool, boolean chatHighMemoryGuard) {
    public ProvidedTool {
        Objects.requireNonNull(tool, "tool");
    }
}
