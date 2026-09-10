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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Add or delete durable notes attached to an indexed source file. */
public final class FileNoteTool implements CliTool {

    private final FileContextService service;

    public FileNoteTool() {
        this(new FileContextService());
    }

    public FileNoteTool(FileContextService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String id() {
        return "file_note";
    }

    @Override
    public String description() {
        return "Manage durable notes keyed to an indexed source file. action=add requires content; "
                + "action=delete requires note_id returned by file_context/read context. Notes survive "
                + "code reindexing and KGraph replacement.";
    }

    @Override
    public String compactHint() {
        return "Manage per-file notes: action=add + file_path + content, or action=delete + file_path + "
                + "note_id. Use file_context or read(include_context=true) to list note IDs.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("action")
                .put("type", "string")
                .putArray("enum").add("add").add("delete");
        properties.putObject("file_path")
                .put("type", "string")
                .put("description", "Absolute path or path relative to the working directory");
        properties.putObject("content")
                .put("type", "string")
                .put("maxLength", 4_000)
                .put("description", "Note text for action=add");
        properties.putObject("note_id")
                .put("type", "string")
                .put("description", "Exact note id for action=delete");
        schema.putArray("required").add("action").add("file_path");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "memory";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return new McpToolAnnotations(false, true, false, false);
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Manage persistent file notes");
        String action = params.path("action").asText("").trim().toLowerCase();
        String requested = params.path("file_path").asText("").trim();
        if (requested.isEmpty()) return ToolResult.error("file_path is required");
        Path path = context.resolvePath(requested);
        if (!Files.isRegularFile(path)) return ToolResult.error("Not a regular file: " + path);

        try {
            FileContextService.NoteMutation mutation = switch (action) {
                case "add" -> service.addNote(path, context.getWorkingDirectory(),
                        params.path("content").asText(""), author(context), context.getSessionId());
                case "delete" -> service.deleteNote(path, context.getWorkingDirectory(),
                        params.path("note_id").asText(""));
                default -> null;
            };
            if (mutation == null) return ToolResult.error("action must be add or delete");

            Map<String, Object> metadata = new LinkedHashMap<>(mutation.context().metadata());
            metadata.put("action", action);
            metadata.put("noteId", mutation.noteId());
            String verb = "add".equals(action) ? "Added" : "Deleted";
            String output = verb + " file note " + mutation.noteId() + ".\n\n"
                    + service.render(mutation.context(), FileContextService.DEFAULT_RENDER_CHARS);
            return ToolResult.success("file_note: " + action, output, metadata);
        } catch (IOException failure) {
            return ToolResult.error("File note " + action + " failed: " + failure.getMessage());
        }
    }

    private static String author(ToolContext context) {
        if (context.getAgent() == null) return "agent";
        String role = context.getAgent().getRoleName();
        if (role != null && !role.isBlank()) return role;
        String name = context.getAgent().getName();
        return name == null || name.isBlank() ? "agent" : name;
    }
}
