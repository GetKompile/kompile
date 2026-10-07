/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Set;

/** Shared MCP wire representation for local {@link ToolResult} values. */
public final class McpToolResultSerializer {

    private static final TypeReference<LinkedHashMap<String, Object>> METADATA_TYPE =
            new TypeReference<>() { };

    // These tools already render their model-facing payload as bounded text.
    // structuredContent makes Claude render the output again as escaped JSON,
    // including bookkeeping that belongs to the client, not the conversation.
    private static final Set<String> TEXT_TOOLS = Set.of(
            "read", "read_batch", "grep", "grep_batch", "glob", "list",
            "fetch_result", "fetch_result_batch");

    private McpToolResultSerializer() {
    }

    /**
     * Read back the {@code structuredContent} of {@link #toMcpCallResult} from an MCP
     * client that hands the model that object serialized as the tool result text, as
     * Claude Code does. Returns null unless {@code text} is exactly such an object, so
     * any other output keeps rendering as received.
     */
    public static ToolResult fromStructuredContentText(ObjectMapper mapper, String text,
                                                       boolean error) {
        if (text == null) return null;
        String json = text.strip();
        if (!json.startsWith("{") || !json.endsWith("}")) return null;
        JsonNode structured;
        try {
            structured = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
        if (structured == null || !structured.isObject()) return null;
        JsonNode title = structured.get("title");
        JsonNode output = structured.get("output");
        JsonNode metadata = structured.get("metadata");
        if (structured.size() != (title == null ? 2 : 3)
                || (title != null && !title.isTextual())
                || output == null || !output.isTextual()
                || metadata == null || !metadata.isObject()) {
            return null;
        }
        LinkedHashMap<String, Object> meta = mapper.convertValue(metadata, METADATA_TYPE);
        // Streamed live to the MCP client, not to whoever reads the result back.
        meta.remove(ToolResult.OUTPUT_STREAMED_METADATA);
        return new ToolResult(title == null ? "" : title.asText(), output.asText(), meta, error);
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
        return toMcpCallResult(mapper, result, usage, null);
    }

    /** Named filesystem/search tools keep bookkeeping in client-only MCP _meta.
     * Domain tools and legacy callers retain their structured payload contract.
     */
    public static ObjectNode toMcpCallResult(ObjectMapper mapper, ToolResult result,
                                             ai.kompile.cli.common.metrics.ToolCallUsage usage,
                                             String toolName) {
        ObjectNode callResult = mapper.createObjectNode();
        var content = callResult.putArray("content");
        var text = content.addObject();
        text.put("type", "text");
        String title = result.getTitle() != null && !result.getTitle().isEmpty()
                ? result.getTitle() + "\n" : "";
        String output = result.getOutput() != null ? result.getOutput() : "";
        text.put("text", title + output);
        if (result.getMetadata() != null && !result.getMetadata().isEmpty()) {
            if (toolName != null && TEXT_TOOLS.contains(toolName)) {
                callResult.putObject("_meta").set("ai.kompile/toolResult", mapper.valueToTree(result.getMetadata()));
            } else {
                ObjectNode structured = mapper.createObjectNode();
                if (result.getTitle() != null && !result.getTitle().isEmpty()) {
                    structured.put("title", result.getTitle());
                }
                structured.put("output", output);
                structured.set("metadata", mapper.valueToTree(result.getMetadata()));
                callResult.set("structuredContent", structured);
            }
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
