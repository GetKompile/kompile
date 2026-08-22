/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagSearchToolTest {
    @Test
    void localSchemaExposesFolderScopedKnowledgeBaseAndLimit() {
        RagSearchTool tool = new RagSearchTool(null, JsonUtils.standardMapper());

        JsonNode schema = tool.parameterSchema();

        assertTrue(schema.path("properties").has("knowledgeBase"));
        assertEquals(50, schema.path("properties").path("limit").path("maximum").asInt());
        assertTrue(tool.description().contains("current folder"));
    }
}
