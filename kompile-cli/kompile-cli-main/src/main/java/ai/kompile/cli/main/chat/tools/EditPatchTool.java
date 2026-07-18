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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apply per-file patches to MULTIPLE files in ONE tool call, entirely in-process.
 *
 * <p>Each entry names its file explicitly and carries just that file's hunks —
 * V4A-style context hunks or a unified diff (whose {@code @@} line numbers are
 * ignored; hunks are located by content with whitespace-tolerant fallbacks, so
 * miscounted numbers and whitespace drift don't fail the patch). Files are
 * independent and atomic: a file is written only when all of its hunks locate.
 * Diffs are more token-efficient than edit's old/new pairs for large changes;
 * for whole-tree diffs with Add/Delete File operations use the {@code patch} tool.
 */
public class EditPatchTool implements CliTool {

    /**
     * Optional coordination manager for multi-agent edit tracking. May be null.
     * When present, files locked by ANOTHER session fail fast with the holder
     * identified.
     */
    private final CoordinationStateManager coordinationManager;

    public EditPatchTool() {
        this.coordinationManager = null;
    }

    public EditPatchTool(CoordinationStateManager coordinationManager) {
        this.coordinationManager = coordinationManager;
    }

    @Override
    public String id() { return "edit_patch"; }

    @Override
    public String description() {
        return "Apply patches to MULTIPLE files in one call. Each patches[] entry has file_path plus "
                + "that file's hunks (' ' context / '-' removed / '+' added lines, hunks separated by "
                + "@@ markers — V4A or unified-diff style; @@ line numbers are ignored and hunks are "
                + "located by content with whitespace-tolerant matching). More token-efficient than "
                + "many edit calls for larger changes. Every file must have been read first. Files are "
                + "independent and atomic (written only when all hunks locate); a failing file does not "
                + "stop the rest unless stop_on_error=true. Existing files only — for Add/Delete File "
                + "or full multi-file diff blobs use the patch tool.";
    }

    @Override
    public String compactHint() {
        return "Patch MANY files in one call: patches=[{file_path,patch}] where patch = that file's "
                + "hunks (' '/'-'/'+' lines, @@ separators; V4A or unified — @@ numbers ignored, "
                + "content-located). READ files first. Per-file atomic; Add/Delete File → patch tool.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode patches = props.putObject("patches");
        patches.put("type", "array");
        patches.put("description", "Per-file patches to apply");
        ObjectNode item = patches.putObject("items");
        item.put("type", "object");
        ObjectNode itemProps = item.putObject("properties");
        itemProps.putObject("file_path").put("type", "string")
                .put("description", "The existing file to patch");
        itemProps.putObject("patch").put("type", "string")
                .put("description", "This file's hunks: ' ' context, '-' removed, '+' added lines; "
                        + "separate hunks with @@ lines (unified-diff @@ headers accepted, numbers ignored)");
        item.putArray("required").add("file_path").add("patch");

        ObjectNode stopOnError = props.putObject("stop_on_error");
        stopOnError.put("type", "boolean");
        stopOnError.put("description",
                "Stop at the first failing FILE instead of continuing with the rest (default: false). "
                        + "Files already written stay written.");

        schema.putArray("required").add("patches");
        return schema;
    }

    /** Reuses the patch permission: an agent allowed to patch may batch-patch. */
    @Override
    public String permissionKey() { return "patch"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        JsonNode patchesNode = params.path("patches");
        if (!patchesNode.isArray() || patchesNode.isEmpty()) {
            return ToolResult.error("patches (non-empty array) is required");
        }
        boolean stopOnError = params.path("stop_on_error").asBoolean(false);

        // Parse + resolve everything up front so malformed input fails before any write.
        Map<Path, List<ApplyPatchFormat.Hunk>> perFile = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode entry : patchesNode) {
            index++;
            String filePath = entry.path("file_path").asText("");
            String patch = entry.path("patch").asText("");
            if (filePath.isEmpty()) {
                return ToolResult.error("patches[" + (index - 1) + "]: file_path is required");
            }
            if (patch.isBlank()) {
                return ToolResult.error("patches[" + (index - 1) + "]: patch is required");
            }
            Path path = context.resolvePath(filePath);
            List<ApplyPatchFormat.Hunk> hunks;
            try {
                hunks = ApplyPatchFormat.parseHunksBody(patch);
            } catch (ApplyPatchFormat.FormatException e) {
                return ToolResult.error("patches[" + (index - 1) + "] (" + filePath + "): " + e.getMessage());
            }
            // Two entries for the same file chain in order.
            perFile.merge(path, hunks, (a, b) -> {
                a.addAll(b);
                return a;
            });
        }

        context.checkPermission(permissionKey(),
                "Batch patch " + perFile.size() + " file(s)");

        StringBuilder out = new StringBuilder();
        int filesPatched = 0;
        int filesFailed = 0;
        boolean halted = false;

        for (Map.Entry<Path, List<ApplyPatchFormat.Hunk>> entry : perFile.entrySet()) {
            if (halted) {
                out.append("- ").append(display(context, entry.getKey())).append(": skipped (stop_on_error)\n");
                continue;
            }
            String error = patchFile(context, entry.getKey(), entry.getValue());
            if (error == null) {
                filesPatched++;
                out.append("+ ").append(display(context, entry.getKey()))
                        .append(" (").append(entry.getValue().size())
                        .append(entry.getValue().size() == 1 ? " hunk" : " hunks").append(")\n");
            } else {
                filesFailed++;
                out.append("! ").append(display(context, entry.getKey()))
                        .append(" FAILED (file unchanged) — ").append(error).append('\n');
                if (stopOnError) halted = true;
            }
        }

        String summary = filesPatched + "/" + perFile.size() + " files patched"
                + (filesFailed > 0 ? ", " + filesFailed + " files failed" : "");
        String output = summary + "\n" + out.toString().stripTrailing();
        Map<String, Object> metadata = Map.of(
                "filesPatched", filesPatched,
                "filesFailed", filesFailed);

        if (filesPatched == 0 && filesFailed > 0) {
            return ToolResult.error(output);
        }
        return ToolResult.success("edit_patch", output, metadata);
    }

    /** Apply one file's hunks; returns null on success, else the failure reason. */
    private String patchFile(ToolContext context, Path path, List<ApplyPatchFormat.Hunk> hunks) {
        if (!Files.exists(path)) {
            return "file not found (edit_patch updates existing files; use write or patch to create)";
        }
        if (!context.hasFreshFileRead(path)) {
            return context.staleReadMessage(path, "patch");
        }
        if (coordinationManager != null) {
            EditLockEntry conflict = coordinationManager.findConflictingLock(
                    path.toAbsolutePath().toString());
            if (conflict != null) {
                return "locked by " + conflict.getAgentName()
                        + " (session " + conflict.getSessionId() + ") via edit_coordinator — "
                        + "wait, coordinate, or lock first with register_edits";
            }
        }
        try {
            String content = Files.readString(path);
            String updated;
            try {
                updated = ApplyPatchFormat.applyHunks(path.toString(), content, hunks);
            } catch (ApplyPatchFormat.FormatException e) {
                return e.getMessage() + " Re-read the file and regenerate this entry, "
                        + "or use edit/edit_batch for a targeted replacement.";
            }
            Files.writeString(path, updated);
            context.recordFileRead(path);
            ai.kompile.cli.main.codeindex.BackgroundIndexService.getInstance()
                    .noteFileWritten(path);
            return null;
        } catch (IOException e) {
            return "I/O error: " + e.getMessage();
        }
    }

    private String display(ToolContext context, Path path) {
        try {
            return context.getWorkingDirectory().toAbsolutePath()
                    .relativize(path.toAbsolutePath()).toString();
        } catch (IllegalArgumentException ex) {
            return path.toString();
        }
    }
}
