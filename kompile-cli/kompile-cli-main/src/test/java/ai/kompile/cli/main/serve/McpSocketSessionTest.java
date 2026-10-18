/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.serve;

import ai.kompile.cli.main.chat.tools.McpToolResultSerializer;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolResultReferenceCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSocketSessionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void socketDeliveryCachesLargeDiagnosticsAndPreservesFailure() {
        ToolResultReferenceCache cache = new ToolResultReferenceCache(16000, 60000);
        McpSocketSession session = new McpSocketSession(null, null, "session", () -> {}, cache);
        String diagnostic = "compile failed\n" + "stack frame\n".repeat(4000);
        ToolResult raw = new ToolResult("failed build", diagnostic, Map.of("exitCode", 1), true);

        ObjectNode wire = session.toolCallResult(mapper, "bash", raw, true);

        assertTrue(wire.path("isError").asBoolean());
        String text = wire.path("content").get(0).path("text").asText();
        assertTrue(text.contains("Tool failed"));
        assertTrue(text.length() < diagnostic.length());
        String handle = wire.path("structuredContent").path("metadata").path("result_id").asText();
        assertEquals(diagnostic, cache.get(handle).orElseThrow().output());
    }

    @Test
    void socketDeliveryDoesNotCreateUnreachableHandlesOrRecacheFetchPages() {
        ToolResultReferenceCache cache = new ToolResultReferenceCache(16000, 60000);
        McpSocketSession session = new McpSocketSession(null, null, "session", () -> {}, cache);
        String output = "x".repeat(20000);
        ToolResult raw = ToolResult.success(output);

        for (String name : Set.of("fetch_result", "fetch_result_batch")) {
            assertEquals(output, session.toolCallResult(mapper, name, raw, true)
                    .path("content").get(0).path("text").asText());
        }
        assertEquals(output, session.toolCallResult(mapper, "read", raw, false)
                .path("content").get(0).path("text").asText());
        assertEquals(0, cache.size());
    }

    @Test
    void daemonToolFilterEnforcesResolvedProfileAllowlist() {
        ArrayNode values = mapper.createArrayNode()
                .add("file_context").add("local_code_index")
                .add("file_context").add(" ").add(42);
        Set<String> allowed = McpSocketSession.parseAllowedToolIds(values);
        Map<String, String> tools = new LinkedHashMap<>();
        tools.put("file_context", "read-only");
        tools.put("file_note", "mutable");
        tools.put("local_code_index", "read-only");

        McpSocketSession.retainAllowedTools(tools, allowed);

        assertEquals(Set.of("file_context", "local_code_index"), tools.keySet());
        assertFalse(tools.containsKey("file_note"));
    }

    @Test
    void absentAllowlistRetainsFullCompatibilitySurface() {
        Map<String, String> tools = new LinkedHashMap<>();
        tools.put("file_context", "read-only");
        tools.put("file_note", "mutable");

        McpSocketSession.retainAllowedTools(tools, null);

        assertEquals(Set.of("file_context", "file_note"), tools.keySet());
    }

    @Test
    void sharedSerializerPreservesStructuredFileContextMetadata() {
        ToolResult result = ToolResult.success("file_context: A.java", "context",
                Map.of("graphStatus", "AVAILABLE", "notes", java.util.List.of("note")));

        ObjectNode wire = McpToolResultSerializer.toMcpCallResult(mapper, result, null, "file_context");

        assertFalse(wire.path("isError").asBoolean());
        assertTrue(wire.path("content").get(0).path("text").asText()
                .contains("file_context: A.java"));
        assertEquals("AVAILABLE", wire.path("structuredContent")
                .path("metadata").path("graphStatus").asText());
        assertEquals("note", wire.path("structuredContent")
                .path("metadata").path("notes").get(0).asText());
    }
}
