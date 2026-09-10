/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.EditLockEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Perform exact string replacement edits in a file.
 * Matching and failure diagnostics are shared with {@code edit_batch} via
 * {@link EditEngine}.
 */
public class EditTool implements CliTool {

    /**
     * Optional coordination manager for multi-agent edit tracking. May be null.
     * When present, edits to files locked by ANOTHER session carry a conflict
     * warning in the result (locks are advisory — the edit still applies).
     */
    private final CoordinationStateManager coordinationManager;

    public EditTool() {
        this.coordinationManager = null;
    }

    public EditTool(CoordinationStateManager coordinationManager) {
        this.coordinationManager = coordinationManager;
    }

    @Override
    public String id() { return "edit"; }

    @Override
    public String description() {
        return "Perform exact string replacement in a file. Provide the old_string to find " +
                "and new_string to replace it with. The old_string must be unique in the file " +
                "(provide more context if needed). Set replace_all to true to replace all " +
                "occurrences. Always read the file first before editing. Managed memory paths " +
                "are rejected; use the memory tool instead.";
    }

    @Override
    public String compactHint() {
        return "Exact string replace — READ the file first. old_string must match byte-for-byte "
                + "and be UNIQUE (add surrounding context, or set replace_all). Don't paste read's "
                + "line-number prefix. Managed memory → memory tool.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "The path to the file to edit");

        ObjectNode oldString = props.putObject("old_string");
        oldString.put("type", "string");
        oldString.put("description", "The exact text to find and replace");

        ObjectNode newString = props.putObject("new_string");
        newString.put("type", "string");
        newString.put("description", "The replacement text");

        ObjectNode replaceAll = props.putObject("replace_all");
        replaceAll.put("type", "boolean");
        replaceAll.put("description", "Replace all occurrences (default: false)");

        schema.putArray("required").add("file_path").add("old_string").add("new_string");
        return schema;
    }

    @Override
    public String permissionKey() { return "edit"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String filePath = params.path("file_path").asText("");
        String oldString = params.path("old_string").asText("");
        String newString = params.path("new_string").asText("");
        boolean replaceAll = params.path("replace_all").asBoolean(false);

        if (filePath.isEmpty()) {
            return ToolResult.error("file_path is required");
        }
        if (oldString.isEmpty()) {
            return ToolResult.error("old_string is required");
        }
        if (oldString.equals(newString)) {
            return ToolResult.error("old_string and new_string must be different");
        }

        Path path = context.resolveMutationPath(filePath);

        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }

        context.checkPermission(permissionKey(), "Edit file: " + path);
        if (!context.hasFreshFileRead(path)) {
            return ToolResult.error(context.staleReadMessage(path, "edit"));
        }

        try {
            String content = Files.readString(path);

            EditEngine.Applied applied;
            try {
                applied = EditEngine.apply(content, oldString, newString, replaceAll);
            } catch (EditEngine.NoMatchException e) {
                return ToolResult.error(e.getMessage());
            }

            Files.writeString(path, applied.newContent());
            context.recordFileRead(path);
            ai.kompile.cli.main.codeindex.BackgroundIndexService.getInstance()
                    .noteFileWritten(path);

            String relativePath;
            try {
                relativePath = context.getWorkingDirectory().toAbsolutePath()
                        .relativize(path.toAbsolutePath()).toString();
            } catch (IllegalArgumentException ex) {
                relativePath = path.toString();
            }
            String message = "Applied edit";
            if (!"exact".equals(applied.matchType())) {
                message += " (" + applied.matchType() + " match)";
            }
            if (applied.replacements() > 1) {
                message += " (" + applied.replacements() + " replacements)";
            }
            String conflictWarning = conflictWarning(path);
            if (conflictWarning != null) {
                message += "\n" + conflictWarning;
            }
            return ToolResult.success(relativePath, message,
                    Map.of("path", relativePath,
                            "matchType", applied.matchType(),
                            "replacements", applied.replacements()));

        } catch (IOException e) {
            return ToolResult.error("Error editing file: " + e.getMessage());
        }
    }

    /** Advisory multi-agent warning when another session holds an edit lock on this file. */
    private String conflictWarning(Path path) {
        if (coordinationManager == null) return null;
        EditLockEntry conflict = coordinationManager.findConflictingLock(
                path.toAbsolutePath().toString());
        if (conflict == null) return null;
        return "WARNING: this file is locked by " + conflict.getAgentName()
                + " (session " + conflict.getSessionId() + ") via edit_coordinator — "
                + "coordinate before making further edits.";
    }
}
