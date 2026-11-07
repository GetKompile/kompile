/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api.tools;

import ai.kompile.cli.plugin.api.CliExtension;

import java.util.List;
import java.util.Set;

/** Tool factory invoked explicitly by the host after extension metadata validation. */
public interface CliToolProvider extends CliExtension {
    List<ProvidedTool> createTools(ToolProviderContext context);

    /** Explicit, side-effect-free surface omissions; all other declared tools are mandatory. */
    default Set<String> omittedTools(ToolProviderContext context) {
        return Set.of();
    }
}
