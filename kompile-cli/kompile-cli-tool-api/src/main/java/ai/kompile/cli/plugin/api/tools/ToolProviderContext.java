/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Objects;

/** Only construction dependencies shared by tool families. No execution/session state. */
public record ToolProviderContext(ObjectMapper objectMapper, String baseUrl, String graphBaseUrl,
                                  String crawlBaseUrl, Path workingDirectory, Surface surface) {
    public enum Surface { CHAT, MCP }

    public ToolProviderContext {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(surface, "surface");
    }
}
