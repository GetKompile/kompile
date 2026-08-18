/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRuntimeToolSchemaTest {

    @Test
    void advertisesExplicitOptimizationAndAllOptimizerControls() {
        JsonNode schema = new ModelRuntimeTool(new ObjectMapper()).parameterSchema();
        JsonNode actions = schema.path("properties").path("action").path("enum");
        assertTrue(actions.toString().contains("\"convert\""));
        assertTrue(actions.toString().contains("\"optimize\""));

        JsonNode properties = schema.path("properties");
        assertEquals("array", properties.path("selectedPasses").path("type").asText());
        assertEquals("BASIC", properties.path("profile").path("default").asText());
        assertEquals(3, properties.path("maxIterations").path("default").asInt());
        assertTrue(properties.has("quantizationType"));
        assertTrue(properties.has("force"));
        assertTrue(properties.has("createBackup"));
        assertTrue(properties.has("dryRun"));
    }
}
