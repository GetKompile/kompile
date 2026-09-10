/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.codeindex.FileContextService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Read durable notes and bounded code/KGraph context for one source file. */
public final class FileContextTool implements CliTool {

    private final FileContextService service;

    public FileContextTool() {
        this(new FileContextService());
    }

    public FileContextTool(FileContextService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String id() {
        return "file_context";
    }

    @Override
    public String description() {
        return "Look up context for one file without rereading its contents: durable per-file notes, "
                + "indexed declarations, structural relations, and an exact bounded neighborhood from "
                + "the projected local KGraph. Does not build an index or launch a model.";
    }

    @Override
    public String compactHint() {
        return "Get notes + bounded code/KGraph context for file_path. Read-only; missing/stale indexes "
                + "are reported explicitly and no index/model work is started.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("file_path")
                .put("type", "string")
                .put("description", "Absolute path or path relative to the working directory");
        properties.putObject("max_chars")
                .put("type", "integer")
                .put("minimum", 512)
                .put("maximum", FileContextService.MAX_RENDER_CHARS)
                .put("description", "Maximum rendered context characters (default 6000)");
        schema.putArray("required").add("file_path");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "read";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Read file context");
        String requested = params.path("file_path").asText("").trim();
        if (requested.isEmpty()) return ToolResult.error("file_path is required");
        Path path = context.resolvePath(requested);
        if (!Files.isRegularFile(path)) return ToolResult.error("Not a regular file: " + path);

        FileContextService.ContextSnapshot snapshot = service.lookup(path, context.getWorkingDirectory());
        int maxChars = params.path("max_chars").asInt(FileContextService.DEFAULT_RENDER_CHARS);
        Map<String, Object> metadata = snapshot.metadata();
        return ToolResult.success("file_context: " + display(context, path),
                service.render(snapshot, maxChars), metadata);
    }

    private static String display(ToolContext context, Path path) {
        try {
            return context.getWorkingDirectory().toAbsolutePath().normalize()
                    .relativize(path.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException ignored) {
            return path.toString();
        }
    }
}
