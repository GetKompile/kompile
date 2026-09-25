/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.mcp;

import ai.kompile.cli.common.metrics.ToolCallUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 4 acceptance: usage wire context, session provenance, and transport
 * substitution against the real SDK 0.10.0 types.
 */
class McpToolUsageAppWiringTest {

    private static final ObjectMapper M = new ObjectMapper();

    @AfterEach
    void cleanThreadState() {
        McpUsageWireContext.clear();
    }

    private static ToolCallUsage usage(String invocationId) {
        return new ToolCallUsage(invocationId, "sess-1", "echo", "echo",
                1000L, 1100L, 100L,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false,
                null,
                ai.kompile.cli.common.metrics.TokenMeasurement.measured(7, "utf8-text",
                        PayloadTokenCounterMethod(), "est", "1"),
                null, List.of(), false, null);
    }

    private static String PayloadTokenCounterMethod() {
        return ai.kompile.cli.common.metrics.PayloadTokenCounter.TEXT_METHOD;
    }

    @Test
    @DisplayName("wire context: publish → take consumes exactly once; clear leaves nothing")
    void wireContextConsumeOnce() {
        McpUsageWireContext.publish(usage("inv-1"));
        assertTrue(McpUsageWireContext.pending());
        ToolCallUsage taken = McpUsageWireContext.take();
        assertNotNull(taken);
        assertEquals("inv-1", taken.invocationId());
        assertFalse(McpUsageWireContext.pending(), "take must consume");
        assertNull(McpUsageWireContext.take(), "second take is empty — no cross-response leak");
    }

    @Test
    @DisplayName("session provenance: client-info id labeled exchange-client; generated labeled service-generated")
    void sessionProvenance() {
        McpSchema.Implementation clientInfo =
                new McpSchema.Implementation("claude-code", "1.2.3");
        McpSyncServerExchange exchange = Mockito.mock(McpSyncServerExchange.class);
        Mockito.when(exchange.getClientInfo()).thenReturn(clientInfo);
        String resolved = McpSessionContext.logicalSessionId(exchange);
        assertEquals("mcp-client-claude-code", resolved);
        assertEquals(McpSessionContext.PROVENANCE_EXCHANGE_CLIENT,
                McpSessionContext.provenanceOf(resolved));

        McpSyncServerExchange anonymous = Mockito.mock(McpSyncServerExchange.class);
        Mockito.when(anonymous.getClientInfo()).thenReturn(null);
        String generated = McpSessionContext.logicalSessionId(anonymous);
        assertTrue(generated.startsWith("mcp-app-"));
        assertEquals(McpSessionContext.PROVENANCE_SERVICE_GENERATED,
                McpSessionContext.provenanceOf(generated),
                "generated fallback must carry explicit provenance, never silently merge sessions");
    }

    @Test
    @DisplayName("transport seam: CallToolResult + published usage → wire node with _meta; result fields preserved")
    void transportSubstitution() {
        McpSchema.CallToolResult callResult = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("compressed payload")), false);

        McpUsageWireContext.publish(usage("inv-wire"));
        SpringMvcSseServerTransport transport = newTransport();
        // build the wire node the same way sendMessage does (unit-level seam check)
        Object wire = transport.usageWireNodeForTest(callResult, McpUsageWireContext.take());
        assertNotNull(wire);
        com.fasterxml.jackson.databind.JsonNode node = M.valueToTree(wire);
        assertEquals("compressed payload",
                node.path("content").get(0).path("text").asText());
        assertFalse(node.path("isError").asBoolean());
        assertEquals(7L, node.path("_meta").path("ai.kompile/usage")
                .path("payload").path("tokens").asLong());
    }

    @Test
    @DisplayName("transport seam: no published usage → no substitution (SDK path untouched)")
    void transportNoUsageNoSubstitution() {
        McpUsageWireContext.clear();
        SpringMvcSseServerTransport transport = newTransport();
        McpSchema.CallToolResult callResult = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("plain")), false);
        Object wire = transport.usageWireNodeForTest(callResult, McpUsageWireContext.take());
        assertNull(wire, "no usage → handler must send the original CallToolResult");
    }

    private SpringMvcSseServerTransport newTransport() {
        return new SpringMvcSseServerTransport(M, () -> "http://localhost:8080",
                "/mcp/message", "/sse");
    }
}
