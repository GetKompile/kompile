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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apply many exact-string edits across many files in ONE tool call.
 *
 * <p>Files are processed independently and atomically: a file is written only
 * when ALL of its edits apply (edits to one file run sequentially, each seeing
 * the previous edit's output). A failed file never blocks the others unless
 * {@code stop_on_error} is set. Matching semantics are identical to {@code edit}
 * (shared {@link EditEngine}); the same read-before-edit rule applies per file.
 */
public class EditBatchTool implements CliTool {

    /**
     * Optional coordination manager for multi-agent edit tracking. May be null.
     * When present, files locked by ANOTHER session fail fast with the holder
     * identified, instead of racing its edits.
     */
    private final CoordinationStateManager coordinationManager;

    public EditBatchTool() {
        this.coordinationManager = null;
    }

    public EditBatchTool(CoordinationStateManager coordinationManager) {
        this.coordinationManager = coordinationManager;
    }

    @Override
    public String id() { return "edit_batch"; }

    @Override
    public String description() {
        return "Apply MULTIPLE exact string replacements across one or more files in a single call — "
                + "use this instead of many sequential edit calls. Each entry in edits has file_path, "
                + "old_string, new_string and optional replace_all, with the same matching rules as edit. "
                + "Every touched file must have been read first. Edits are grouped per file and applied "
                + "in order (later edits see earlier results); a file is only written when ALL of its "
                + "edits apply, and a failing file does not stop the rest (set stop_on_error=true to "
                + "halt at the first failing file). Per-file results are returned. Managed memory "
                + "paths are rejected; use the memory tool instead.";
    }

    @Override
    public String compactHint() {
        return "Many exact string replaces in ONE call (multi-file) — same rules as edit: read files "
                + "first, old_string unique per file. Per-file atomic; failures don't stop other files. "
                + "edits=[{file_path,old_string,new_string,replace_all?}]. Managed memory → memory tool.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode edits = props.putObject("edits");
        edits.put("type", "array");
        edits.put("description", "The edits to apply, in order. Entries for the same file chain "
                + "(each sees the previous result).");
        ObjectNode item = edits.putObject("items");
        item.put("type", "object");
        ObjectNode itemProps = item.putObject("properties");
        itemProps.putObject("file_path").put("type", "string")
                .put("description", "The file to edit");
        itemProps.putObject("old_string").put("type", "string")
                .put("description", "The exact text to find (must be unique in the file unless replace_all)");
        itemProps.putObject("new_string").put("type", "string")
                .put("description", "The replacement text");
        itemProps.putObject("replace_all").put("type", "boolean")
                .put("description", "Replace all occurrences (default: false)");
        item.putArray("required").add("file_path").add("old_string").add("new_string");

        ObjectNode stopOnError = props.putObject("stop_on_error");
        stopOnError.put("type", "boolean");
        stopOnError.put("description",
                "Stop at the first failing FILE instead of continuing with the rest (default: false). "
                        + "Files already written stay written.");

        schema.putArray("required").add("edits");
        return schema;
    }

    /** Reuses the edit permission: an agent allowed to edit may batch-edit. */
    @Override
    public String permissionKey() { return "edit"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        JsonNode editsNode = params.path("edits");
        if (!editsNode.isArray() || editsNode.isEmpty()) {
            return ToolResult.error("edits (non-empty array) is required");
        }
        boolean stopOnError = params.path("stop_on_error").asBoolean(false);

        // Group edits per resolved file, preserving both file order and edit order.
        Map<Path, List<Edit>> perFile = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode e : editsNode) {
            index++;
            String filePath = e.path("file_path").asText("");
            String oldString = e.path("old_string").asText("");
            String newString = e.path("new_string").asText("");
            boolean replaceAll = e.path("replace_all").asBoolean(false);
            if (filePath.isEmpty()) {
                return ToolResult.error("edits[" + (index - 1) + "]: file_path is required");
            }
            if (oldString.isEmpty()) {
                return ToolResult.error("edits[" + (index - 1) + "]: old_string is required");
            }
            if (oldString.equals(newString)) {
                return ToolResult.error("edits[" + (index - 1) + "]: old_string and new_string must be different");
            }
            Path path = context.resolveMutationPath(filePath);
            perFile.computeIfAbsent(path, p -> new ArrayList<>())
                    .add(new Edit(index, oldString, newString, replaceAll));
        }

        context.checkPermission(permissionKey(),
                "Batch edit " + perFile.size() + " file(s): " + fileListSummary(perFile));

        StringBuilder out = new StringBuilder();
        int filesEdited = 0;
        int filesFailed = 0;
        int editsApplied = 0;
        boolean halted = false;

        for (Map.Entry<Path, List<Edit>> entry : perFile.entrySet()) {
            if (halted) {
                out.append("- ").append(display(context, entry.getKey())).append(": skipped (stop_on_error)\n");
                continue;
            }
            FileOutcome outcome = applyFile(context, entry.getKey(), entry.getValue());
            if (outcome.error == null) {
                filesEdited++;
                editsApplied += entry.getValue().size();
                out.append("+ ").append(display(context, entry.getKey()))
                        .append(" (").append(entry.getValue().size())
                        .append(entry.getValue().size() == 1 ? " edit" : " edits").append(")");
                if (outcome.note != null) out.append(" — ").append(outcome.note);
                out.append('\n');
            } else {
                filesFailed++;
                out.append("! ").append(display(context, entry.getKey()))
                        .append(" FAILED (file unchanged) — ").append(outcome.error).append('\n');
                if (stopOnError) halted = true;
            }
        }

        String summary = filesEdited + "/" + perFile.size() + " files edited, "
                + editsApplied + " edits applied"
                + (filesFailed > 0 ? ", " + filesFailed + " files failed" : "");
        String output = summary + "\n" + out.toString().stripTrailing();
        Map<String, Object> metadata = Map.of(
                "filesEdited", filesEdited,
                "filesFailed", filesFailed,
                "editsApplied", editsApplied);

        // Only an all-files failure is a tool error; partial success reports per-file.
        if (filesEdited == 0 && filesFailed > 0) {
            return ToolResult.error(output);
        }
        return ToolResult.success("edit_batch", output, metadata);
    }

    /** Apply all edits for one file; write only when every edit matched. */
    private FileOutcome applyFile(ToolContext context, Path path, List<Edit> edits) {
        if (!Files.exists(path)) {
            return FileOutcome.fail("file not found");
        }
        if (!context.hasFreshFileRead(path)) {
            return FileOutcome.fail(context.staleReadMessage(path, "edit"));
        }
        if (coordinationManager != null) {
            EditLockEntry conflict = coordinationManager.findConflictingLock(
                    path.toAbsolutePath().toString());
            if (conflict != null) {
                return FileOutcome.fail("locked by " + conflict.getAgentName()
                        + " (session " + conflict.getSessionId() + ") via edit_coordinator — "
                        + "wait, coordinate, or lock first with register_edits");
            }
        }
        try {
            String content = Files.readString(path);
            boolean fuzzy = false;
            for (Edit edit : edits) {
                try {
                    EditEngine.Applied applied = EditEngine.apply(
                            content, edit.oldString, edit.newString, edit.replaceAll);
                    content = applied.newContent();
                    fuzzy |= !"exact".equals(applied.matchType());
                } catch (EditEngine.NoMatchException e) {
                    return FileOutcome.fail("edit #" + edit.index + ": " + e.getMessage());
                }
            }
            Files.writeString(path, content);
            context.recordFileRead(path);
            ai.kompile.cli.main.codeindex.BackgroundIndexService.getInstance()
                    .noteFileWritten(path);
            return FileOutcome.ok(fuzzy ? "whitespace-tolerant match used" : null);
        } catch (IOException e) {
            return FileOutcome.fail("I/O error: " + e.getMessage());
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

    private String fileListSummary(Map<Path, List<Edit>> perFile) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Path p : perFile.keySet()) {
            if (shown > 0) sb.append(", ");
            if (shown == 5) {
                sb.append("… (").append(perFile.size() - shown).append(" more)");
                break;
            }
            sb.append(p.getFileName());
            shown++;
        }
        return sb.toString();
    }

    private record Edit(int index, String oldString, String newString, boolean replaceAll) {}

    private record FileOutcome(String error, String note) {
        static FileOutcome ok(String note) { return new FileOutcome(null, note); }
        static FileOutcome fail(String error) { return new FileOutcome(error, null); }
    }
}
