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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared MCP wire representation for local {@link ToolResult} values. */
public final class McpToolResultSerializer {

    private static final TypeReference<LinkedHashMap<String, Object>> METADATA_TYPE =
            new TypeReference<>() { };

    private static final int MAX_SAVED_RESULT_BYTES = 8 * 1024 * 1024;
    private static final Pattern SAVED_RESULT_NOTICE = Pattern.compile(
            "\\AError: result \\([0-9,]+ characters\\) exceeds maximum allowed tokens\\. "
                    + "Output has been saved to ([^\\r\\n]+)\\.(?:\\R|\\z)");

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
     * any other output keeps rendering as received. Large Claude Code results arrive
     * as a spill-file notice instead; restore their structured payload for display,
     * without changing the bounded text Claude's model received.
     */
    public static ToolResult fromStructuredContentText(ObjectMapper mapper, String text,
                                                       boolean error) {
        if (text == null) return null;
        String json = text.strip();
        var saved = SAVED_RESULT_NOTICE.matcher(json);
        if (saved.lookingAt()) {
            json = savedStructuredContent(saved.group(1));
            if (json == null) return null;
            json = json.strip();
        }
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

    /** Only read Claude's own MCP spill files, never an arbitrary path in tool output. */
    private static String savedStructuredContent(String filename) {
        try {
            String configured = System.getenv("CLAUDE_CONFIG_DIR");
            Path config = configured == null || configured.isBlank()
                    ? Path.of(System.getProperty("user.home"), ".claude") : Path.of(configured);
            Path projects = config.resolve("projects").toAbsolutePath().normalize();
            Path file = Path.of(filename).toAbsolutePath().normalize();
            if (!file.startsWith(projects)) return null;
            Path relative = projects.relativize(file);
            // projects/<project>/<session>/tool-results/mcp-<server>-<tool>-<stamp>.txt
            if (relative.getNameCount() != 4 || !relative.getName(2).toString().equals("tool-results")
                    || !file.getFileName().toString().startsWith("mcp-")
                    || !file.getFileName().toString().endsWith(".txt")) return null;
            Path realProjects = projects.toRealPath();
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(realProjects) || !Files.isRegularFile(realFile)
                    || Files.size(realFile) > MAX_SAVED_RESULT_BYTES) return null;
            try (InputStream input = Files.newInputStream(realFile)) {
                byte[] bytes = input.readNBytes(MAX_SAVED_RESULT_BYTES + 1);
                return bytes.length > MAX_SAVED_RESULT_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return null; // Keep the provider's original notice visible when recovery is impossible.
        }
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
