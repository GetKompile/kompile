/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRagSearchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void localProvenanceIsVisibleAndRetainedInMetadata() throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.putArray("entities").addObject().put("name", "service").put("score", 0.9);
        result.put("ranking", "lexical + stored entity prior");
        result.put("inferenceInvoked", false);
        result.putObject("data")
                .put("scoreBasis", "lexical + stored entity prior")
                .put("storedPrior", "clamp01(weight * confidence)")
                .put("inferenceInvoked", false);

        ToolResult formatted = formatResults("service", result, "LOCAL");

        assertFalse(formatted.isError());
        assertTrue(formatted.getOutput().contains("Score basis: lexical + stored entity prior"));
        assertTrue(formatted.getOutput().contains("Stored prior: clamp01(weight * confidence)"));
        assertTrue(formatted.getOutput().contains("Inference invoked: false"));
        assertEquals("lexical + stored entity prior", formatted.getMetadata().get("ranking"));
        assertEquals(false, formatted.getMetadata().get("inferenceInvoked"));
        assertEquals("service", formatted.getMetadata().get("query"));
        assertEquals("LOCAL", formatted.getMetadata().get("searchType"));

        Map<?, ?> data = (Map<?, ?>) formatted.getMetadata().get("data");
        assertEquals("lexical + stored entity prior", data.get("scoreBasis"));
        assertEquals("clamp01(weight * confidence)", data.get("storedPrior"));
        assertEquals(false, data.get("inferenceInvoked"));
    }

    @Test
    void emptyLocalResultKeepsNoResultsMessageAndProvenance() throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("ranking", "lexical + stored entity prior");
        result.put("inferenceInvoked", false);
        result.putObject("data")
                .put("scoreBasis", "lexical + stored entity prior")
                .put("storedPrior", "clamp01(weight * confidence)")
                .put("inferenceInvoked", false);

        ToolResult formatted = formatResults("missing", result, "LOCAL");

        assertTrue(formatted.getOutput().contains("No graph results found for: missing"));
        assertTrue(formatted.getOutput().contains("Score basis: lexical + stored entity prior"));
        assertTrue(formatted.getOutput().contains("Inference invoked: false"));
        assertEquals(0, formatted.getMetadata().get("entityCount"));
        assertEquals(0, formatted.getMetadata().get("relationshipCount"));
        assertEquals(0, formatted.getMetadata().get("sourceChunkCount"));
        assertEquals(false, formatted.getMetadata().get("inferenceInvoked"));
    }

    @Test
    void managedPayloadWithoutProvenanceDoesNotInventRetrievalOnlyClaims() throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.putArray("entities").addObject().put("name", "remote-service");

        ToolResult formatted = formatResults("remote", result, "GLOBAL");

        assertFalse(formatted.getOutput().contains("Score basis:"));
        assertFalse(formatted.getOutput().contains("Inference invoked:"));
        assertFalse(formatted.getMetadata().containsKey("ranking"));
        assertFalse(formatted.getMetadata().containsKey("inferenceInvoked"));
        assertEquals("remote", formatted.getMetadata().get("query"));
        assertEquals("GLOBAL", formatted.getMetadata().get("searchType"));
    }

    @Test
    void explicitManagedInferenceTrueIsPreserved() throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.putArray("entities").addObject().put("name", "remote-service");
        result.put("ranking", "managed ranking");
        result.put("inferenceInvoked", true);

        ToolResult formatted = formatResults("remote", result, "GLOBAL");

        assertTrue(formatted.getOutput().contains("Score basis: managed ranking"));
        assertTrue(formatted.getOutput().contains("Inference invoked: true"));
        assertEquals("managed ranking", formatted.getMetadata().get("ranking"));
        assertEquals(true, formatted.getMetadata().get("inferenceInvoked"));
    }

    private ToolResult formatResults(String query, JsonNode result, String searchType) throws Exception {
        GraphRagSearchTool tool = new GraphRagSearchTool(null, mapper);
        Method formatter = GraphRagSearchTool.class
                .getDeclaredMethod("formatResults", String.class, JsonNode.class, String.class);
        formatter.setAccessible(true);
        return (ToolResult) formatter.invoke(tool, query, result, searchType);
    }
}
