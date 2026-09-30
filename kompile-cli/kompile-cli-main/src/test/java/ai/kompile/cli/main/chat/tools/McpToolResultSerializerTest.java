/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Claude Code hands its model a Kompile tool's {@code structuredContent} as JSON text.
 * Reading that text back must give the tool result that was sent, and must leave any
 * other text alone.
 */
class McpToolResultSerializerTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void structuredContentSentAsJsonTextReadsBackAsTheToolResult() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("totalLines", 1);
        metadata.put("truncated", false);
        metadata.put(ToolResult.OUTPUT_STREAMED_METADATA, true);
        ObjectNode wire = McpToolResultSerializer.toMcpCallResult(M,
                new ToolResult("NOTES.md", "     1\tbody", metadata, false));
        String text = wire.path("structuredContent").toString();

        ToolResult read = McpToolResultSerializer.fromStructuredContentText(M, text, false);

        assertNotNull(read, text);
        assertEquals("NOTES.md", read.getTitle());
        assertEquals("     1\tbody", read.getOutput());
        assertEquals(Map.of("totalLines", 1, "truncated", false), read.getMetadata());
        assertFalse(read.isError());
        assertFalse(read.isOutputStreamed(),
                "the output streamed live to the MCP client, not to whoever reads it back");
    }

    @Test
    void untitledFailedResultReadsBack() {
        ObjectNode wire = McpToolResultSerializer.toMcpCallResult(M,
                new ToolResult("", "boom", Map.of("exitCode", 2), true));
        String text = wire.path("structuredContent").toString();

        ToolResult read = McpToolResultSerializer.fromStructuredContentText(M, text, true);

        assertNotNull(read, text);
        assertEquals("", read.getTitle());
        assertEquals("boom", read.getOutput());
        assertEquals(Map.of("exitCode", 2), read.getMetadata());
        assertTrue(read.isError());
    }

    @Test
    void anyOtherTextIsLeftAsReceived() {
        for (String text : List.of(
                "plain output",
                "{\"output\":\"no metadata\"}",
                "{\"output\":\"x\",\"metadata\":{},\"extra\":1}",
                "{\"title\":1,\"output\":\"x\",\"metadata\":{}}",
                "{\"output\":[\"x\"],\"metadata\":{}}",
                "{\"output\":\"x\",\"metadata\":\"m\"}",
                "{\"title\":\"NOTES.md\",\"output\":\"cut sho",
                "[1,2]")) {
            assertNull(McpToolResultSerializer.fromStructuredContentText(M, text, false), text);
        }
        assertNull(McpToolResultSerializer.fromStructuredContentText(M, null, false));
    }
}
