/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;

/** One tool call requested by the model in a provider stream. */
public class ToolCallOutput {
    public String id;
    public String name;
    public JsonNode arguments;
}
