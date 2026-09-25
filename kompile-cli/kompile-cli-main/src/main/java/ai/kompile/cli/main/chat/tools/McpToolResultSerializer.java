/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared MCP wire representation for local {@link ToolResult} values. */
public final class McpToolResultSerializer {

    private McpToolResultSerializer() {
    }

    public static ObjectNode toMcpCallResult(ObjectMapper mapper, ToolResult result) {
        return toMcpCallResult(mapper, result, null);
    }

    /**
     * Serialize a tool result, optionally attaching per-invocation usage metrics at
     * {@code _meta["ai.kompile/usage"]}. Pure serialization never increments totals or
     * creates a new invocation — it only projects the already-computed usage.
     *
     * @param usage usage snapshot for THIS invocation; null = legacy shape, no _meta
     */
    public static ObjectNode toMcpCallResult(ObjectMapper mapper, ToolResult result,
                                             ai.kompile.cli.common.metrics.ToolCallUsage usage) {
        ObjectNode callResult = mapper.createObjectNode();
        var content = callResult.putArray("content");
        var text = content.addObject();
        text.put("type", "text");
        String title = result.getTitle() != null && !result.getTitle().isEmpty()
                ? result.getTitle() + "\n" : "";
        String output = result.getOutput() != null ? result.getOutput() : "";
        text.put("text", title + output);
        if (result.getMetadata() != null && !result.getMetadata().isEmpty()) {
            ObjectNode structured = mapper.createObjectNode();
            if (result.getTitle() != null && !result.getTitle().isEmpty()) {
                structured.put("title", result.getTitle());
            }
            structured.put("output", output);
            structured.set("metadata", mapper.valueToTree(result.getMetadata()));
            callResult.set("structuredContent", structured);
        }
        callResult.put("isError", result.isError());
        if (usage != null) {
            // merge into any unrelated _meta rather than replacing it
            com.fasterxml.jackson.databind.JsonNode existingMeta = callResult.get("_meta");
            ObjectNode meta = existingMeta != null && existingMeta.isObject()
                    ? (ObjectNode) existingMeta
                    : callResult.putObject("_meta");
            meta.set("ai.kompile/usage", usage.toWireMetaNode(mapper));
        }
        return callResult;
    }
}
