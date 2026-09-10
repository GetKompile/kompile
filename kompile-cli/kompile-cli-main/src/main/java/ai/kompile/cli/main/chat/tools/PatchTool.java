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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apply patches in either standard unified-diff format (via the system's patch
 * command, escalating to whitespace-tolerant matching and git apply --recount)
 * or the apply_patch "*** Begin Patch" format (applied in-process, see
 * {@link ApplyPatchFormat}).
 */
public class PatchTool implements CliTool {
    private static final Pattern DIFF_GIT = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern OLD_FILE = Pattern.compile("^---\\s+(.+)$");
    private static final Pattern NEW_FILE = Pattern.compile("^\\+\\+\\+\\s+(.+)$");
    private static final Pattern CREATION_HEADER = Pattern.compile("(?m)^---\\s+/dev/null(\\s.*)?$");
    private static final Pattern FIRST_HUNK = Pattern.compile("(?m)^@@ -");

    @Override
    public String id() { return "patch"; }

    @Override
    public String description() {
        return "Apply a patch to one or more files. Accepts standard unified diffs (---/+++/@@, git or " +
                "plain style) AND the apply_patch '*** Begin Patch' format (Add/Update/Delete File). " +
                "Single file: pass file_path plus patch. Multi-file: pass a patch with file headers and " +
                "omit file_path. Creates new files from '--- /dev/null' or '*** Add File:' patches. " +
                "Wrong @@ line numbers and whitespace drift are tolerated (automatic retry with " +
                "whitespace-insensitive matching and git apply --recount). Use this for multi-hunk or " +
                "multi-file changes; edit is still simplest for a single small replacement. Managed " +
                "memory paths are rejected; use the memory tool instead.";
    }

    @Override
    public String compactHint() {
        return "Apply unified diff OR '*** Begin Patch' apply_patch input — file_path+patch for one "
                + "file, diff headers for multi-file. Wrong @@ numbers and whitespace drift are "
                + "auto-recovered; new files via /dev/null or Add File. Managed memory → memory tool.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "The file to patch. Optional when patch/diff contains full unified diff file headers.");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "Alias for file_path used by some CLI providers");

        ObjectNode file = props.putObject("file");
        file.put("type", "string");
        file.put("description", "Alias for file_path used by some CLI providers");

        ObjectNode patch = props.putObject("patch");
        patch.put("type", "string");
        patch.put("description", "The patch content: unified diff or '*** Begin Patch' apply_patch format");

        ObjectNode diff = props.putObject("diff");
        diff.put("type", "string");
        diff.put("description", "Alias for patch used by apply_diff-style tools");

        ObjectNode unifiedDiff = props.putObject("unified_diff");
        unifiedDiff.put("type", "string");
        unifiedDiff.put("description", "Alias for patch used by transcript/imported CLI tools");

        schema.putArray("required");
        schema.putArray("anyOf").addObject().putArray("required").add("patch");
        schema.withArray("anyOf").addObject().putArray("required").add("diff");
        schema.withArray("anyOf").addObject().putArray("required").add("unified_diff");
        return schema;
    }

    @Override
    public String permissionKey() { return "patch"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String filePath = firstText(params, "file_path", "path", "file");
        String rawPatch = firstRawText(params, "patch", "diff", "unified_diff");

        if (rawPatch.isBlank()) return ToolResult.error("patch, diff, or unified_diff is required");
        String patch = normalizePatchText(rawPatch);

        try {
            if (ApplyPatchFormat.isApplyPatchFormat(patch)) {
                return applyApplyPatchFormat(context, patch, filePath);
            }
            if (!filePath.isEmpty()) {
                return applySingleFilePatch(context, context.resolveMutationPath(filePath), patch);
            }
            return applyHeaderPatch(context, patch);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            return ToolResult.error("Error applying patch: " + e.getMessage());
        }
    }

    // ── apply_patch ("*** Begin Patch") format ─────────────────────────────

    private ToolResult applyApplyPatchFormat(ToolContext context, String patch, String filePathParam)
            throws Exception {
        List<ApplyPatchFormat.FileOp> ops;
        try {
            ops = ApplyPatchFormat.parse(patch);
        } catch (ApplyPatchFormat.FormatException e) {
            return ToolResult.error("Invalid apply_patch ('*** Begin Patch') input: " + e.getMessage());
        }

        Map<Path, String> pendingWrites = new LinkedHashMap<>();
        Set<Path> deletions = new LinkedHashSet<>();
        List<String> summary = new ArrayList<>();

        for (ApplyPatchFormat.FileOp op : ops) {
            Path path = context.resolveMutationPath(op.path);
            if (op.type != ApplyPatchFormat.OpType.ADD && !Files.exists(path)
                    && ops.size() == 1 && !filePathParam.isEmpty()) {
                // Rescue a mislabeled section path when the explicit file_path resolves.
                Path fallback = context.resolveMutationPath(filePathParam);
                if (Files.exists(fallback)) path = fallback;
            }
            switch (op.type) {
                case ADD -> {
                    if (Files.exists(path)) {
                        return ToolResult.error("Cannot Add File " + relativePath(context, path)
                                + ": it already exists. Use '*** Update File:' instead.");
                    }
                    context.checkPermission(permissionKey(), "Create file via patch: " + path);
                    pendingWrites.put(path, ApplyPatchFormat.joinAddLines(op.addLines));
                    summary.add("created " + relativePath(context, path));
                }
                case DELETE -> {
                    if (!Files.exists(path)) {
                        return ToolResult.error("Cannot Delete File " + relativePath(context, path) + ": not found");
                    }
                    context.checkPermission(permissionKey(), "Delete file via patch: " + path);
                    if (!context.hasFreshFileRead(path)) {
                        return ToolResult.error(context.staleReadMessage(path, "patch"));
                    }
                    deletions.add(path);
                    summary.add("deleted " + relativePath(context, path));
                }
                case UPDATE -> {
                    if (!Files.exists(path)) {
                        return ToolResult.error("Cannot Update File " + relativePath(context, path) + ": not found");
                    }
                    context.checkPermission(permissionKey(), "Patch file: " + path);
                    if (!context.hasFreshFileRead(path)) {
                        return ToolResult.error(context.staleReadMessage(path, "patch"));
                    }
                    String updated;
                    try {
                        updated = ApplyPatchFormat.applyHunks(op.path, Files.readString(path), op.hunks);
                    } catch (ApplyPatchFormat.FormatException e) {
                        return ToolResult.error("Patch failed — no files changed. " + e.getMessage()
                                + " Re-`read` the file and regenerate the patch against its current content, "
                                + "or use the `edit` tool for a targeted replacement.");
                    }
                    if (op.moveTo != null && !op.moveTo.isBlank()) {
                        Path target = context.resolveMutationPath(op.moveTo);
                        context.checkPermission(permissionKey(), "Create file via patch (move target): " + target);
                        pendingWrites.put(target, updated);
                        deletions.add(path);
                        summary.add("moved " + relativePath(context, path) + " -> " + relativePath(context, target));
                    } else {
                        pendingWrites.put(path, updated);
                        summary.add("updated " + relativePath(context, path));
                    }
                }
            }
        }

        // All operations validated and applied in memory — flush atomically-ish.
        for (Map.Entry<Path, String> entry : pendingWrites.entrySet()) {
            Path p = entry.getKey();
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, entry.getValue());
            context.recordFileRead(p);
            ai.kompile.cli.main.codeindex.BackgroundIndexService.getInstance().noteFileWritten(p);
        }
        for (Path p : deletions) {
            if (pendingWrites.containsKey(p)) continue;
            Files.deleteIfExists(p);
            ai.kompile.cli.main.codeindex.BackgroundIndexService.getInstance().noteFileWritten(p);
        }

        return ToolResult.success("patch",
                "Patch applied successfully (apply_patch format): " + String.join(", ", summary),
                Map.of("files", pendingWrites.size() + deletions.size(), "strategy", "apply_patch"));
    }

    // ── unified diff: single file ──────────────────────────────────────────

    private ToolResult applySingleFilePatch(ToolContext context, Path path, String patch) throws Exception {
        boolean creation = !Files.exists(path) && CREATION_HEADER.matcher(patch).find();
        if (!Files.exists(path) && !creation) {
            return ToolResult.error("File not found: " + path + ". For a brand-new file use the write tool, "
                    + "or send a creation diff with '--- /dev/null' headers.");
        }

        String original;
        if (creation) {
            context.checkPermission(permissionKey(), "Create file via patch: " + path);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            original = null;
        } else {
            context.checkPermission(permissionKey(), "Patch file: " + path);
            if (!context.hasFreshFileRead(path)) {
                return ToolResult.error(context.staleReadMessage(path, "patch"));
            }
            original = Files.readString(path);
        }
        Restore restore = () -> {
            if (original == null) Files.deleteIfExists(path);
            else Files.writeString(path, original);
        };

        List<PatchAttempt> attempts = new ArrayList<>();
        attempts.add(new PatchAttempt("patch (strict)",
                List.of("patch", "--force", "-u", path.toString()), patch, true));
        attempts.add(new PatchAttempt("patch -l --fuzz=3 (whitespace-tolerant)",
                List.of("patch", "--force", "-u", "-l", "--fuzz=3", path.toString()), patch, true));
        PatchAttempt git = gitApplySingleFileAttempt(context, path, patch, creation);
        if (git != null) attempts.add(git);

        String relativePath = relativePath(context, path);
        Ladder ladder = runLadder(context.getWorkingDirectory(), attempts, restore);
        if (ladder.timedOut) {
            return ToolResult.error("Patch command timed out after 30s — " + relativePath
                    + " left unchanged. Use the `edit` tool for this change instead.");
        }
        if (ladder.success) {
            context.recordFileRead(path);
            ai.kompile.cli.main.codeindex.BackgroundIndexService.getInstance().noteFileWritten(path);
            return ToolResult.success(relativePath,
                    "Patch applied successfully via " + ladder.attempt.strategy + "\n" + ladder.result.output.trim(),
                    Map.of("path", relativePath, "strategy", ladder.attempt.strategy));
        }
        return patchFailure(relativePath, ladder);
    }

    // ── unified diff: multi-file via headers ───────────────────────────────

    private ToolResult applyHeaderPatch(ToolContext context, String patch) throws Exception {
        List<Path> touched = pathsFromUnifiedDiff(context, patch);
        if (touched.isEmpty()) {
            return ToolResult.error("file_path is required unless patch/diff contains unified diff file headers");
        }

        Map<Path, String> originals = new LinkedHashMap<>();
        Set<Path> newFiles = new LinkedHashSet<>();
        for (Path path : touched) {
            if (Files.exists(path)) {
                context.checkPermission(permissionKey(), "Patch file: " + path);
                if (!context.hasFreshFileRead(path)) {
                    return ToolResult.error(context.staleReadMessage(path, "patch"));
                }
                originals.put(path, Files.readString(path));
            } else {
                context.checkPermission(permissionKey(), "Create file via patch: " + path);
                newFiles.add(path);
            }
        }
        Restore restore = () -> restoreTouchedFiles(originals, newFiles);

        String strip = usesGitPathPrefixes(patch) ? "-p1" : "-p0";
        List<PatchAttempt> attempts = new ArrayList<>();
        attempts.add(new PatchAttempt("patch (strict)",
                List.of("patch", "--force", "-u", strip), patch, true));
        attempts.add(new PatchAttempt("patch -l --fuzz=3 (whitespace-tolerant)",
                List.of("patch", "--force", "-u", "-l", "--fuzz=3", strip), patch, true));
        if (FIRST_HUNK.matcher(patch).find()) {
            attempts.add(new PatchAttempt("git apply --recount",
                    List.of("git", "apply", "--recount", "-C1", "--ignore-whitespace",
                            "--whitespace=nowarn", strip), patch, false));
        }

        Ladder ladder = runLadder(context.getWorkingDirectory(), attempts, restore);
        if (ladder.timedOut) {
            return ToolResult.error("Patch command timed out after 30s — files left unchanged. "
                    + "Use the `edit` tool for this change instead.");
        }
        if (!ladder.success) {
            return patchFailure(joinRelativePaths(context, touched), ladder);
        }

        for (Path path : touched) {
            if (Files.exists(path)) {
                context.recordFileRead(path);
                ai.kompile.cli.main.codeindex.BackgroundIndexService.getInstance().noteFileWritten(path);
            }
        }
        return ToolResult.success("patch",
                "Patch applied successfully to " + touched.size() + " file(s) via " + ladder.attempt.strategy
                        + "\n" + ladder.result.output.trim(),
                Map.of("paths", joinRelativePaths(context, touched), "files", touched.size(),
                        "strategy", ladder.attempt.strategy));
    }

    // ── escalation ladder ───────────────────────────────────────────────────

    private interface Restore {
        void run() throws Exception;
    }

    private record PatchAttempt(String strategy, List<String> command, String patchText,
                                boolean viaPatchBinary) {
    }

    private static final class Ladder {
        PatchRunResult result;
        PatchAttempt attempt;
        final List<String> tried = new ArrayList<>();
        boolean success;
        boolean timedOut;
    }

    private Ladder runLadder(Path workingDirectory, List<PatchAttempt> attempts, Restore restore)
            throws Exception {
        Ladder ladder = new Ladder();
        boolean dirty = false;
        for (PatchAttempt attempt : attempts) {
            if (dirty) restore.run();
            PatchRunResult result = runExternal(workingDirectory, attempt);
            dirty = true;
            ladder.tried.add(attempt.strategy);
            ladder.result = result;
            ladder.attempt = attempt;
            if (result.timedOut) {
                restore.run();
                ladder.timedOut = true;
                return ladder;
            }
            if (result.exitCode == 0) {
                ladder.success = true;
                return ladder;
            }
        }
        restore.run();
        return ladder;
    }

    /** Builds the git-apply rung for single-file mode with synthesized headers pinned to the target file. */
    private PatchAttempt gitApplySingleFileAttempt(ToolContext context, Path path, String patch, boolean creation) {
        Matcher hunk = FIRST_HUNK.matcher(patch);
        if (!hunk.find()) return null;
        Path wd = context.getWorkingDirectory().toAbsolutePath().normalize();
        Path abs = path.toAbsolutePath().normalize();
        if (!abs.startsWith(wd)) return null; // git apply refuses paths outside its working tree
        String rel = wd.relativize(abs).toString();
        String headers = (creation ? "--- /dev/null" : "--- a/" + rel) + "\n+++ b/" + rel + "\n";
        return new PatchAttempt("git apply --recount",
                List.of("git", "apply", "--recount", "-C1", "--ignore-whitespace",
                        "--whitespace=nowarn", "-p1"),
                headers + patch.substring(hunk.start()), false);
    }

    private PatchRunResult runExternal(Path workingDirectory, PatchAttempt attempt) throws Exception {
        Path patchFile = Files.createTempFile("kompile-patch-", ".diff");
        Path rejectFile = Files.createTempFile("kompile-patch-", ".rej");
        try {
            Files.writeString(patchFile, attempt.patchText, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(attempt.command);
            if (attempt.viaPatchBinary) {
                command.add("--reject-file=" + rejectFile);
                command.add("-i");
                command.add(patchFile.toString());
            } else {
                command.add(patchFile.toString());
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDirectory.toFile());
            pb.redirectErrorStream(true);
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                // Binary not available (e.g. no git) — report as a failed rung, not a tool crash.
                return new PatchRunResult(127, false, "could not run '" + command.get(0) + "': " + e.getMessage(), "");
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean done = process.waitFor(30, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return new PatchRunResult(-1, true, output.toString(), "");
            }
            String rejects = "";
            if (attempt.viaPatchBinary) {
                try {
                    if (Files.exists(rejectFile)) rejects = Files.readString(rejectFile);
                } catch (IOException ignored) {
                }
            }
            return new PatchRunResult(process.exitValue(), false, output.toString(), rejects);
        } finally {
            Files.deleteIfExists(patchFile);
            Files.deleteIfExists(rejectFile);
        }
    }

    private ToolResult patchFailure(String pathDescription, Ladder ladder) {
        int exit = ladder.result == null ? -1 : ladder.result.exitCode;
        String reason = (exit == 2 || exit == 127 || exit == 128)
                ? "The diff could not be parsed or its target could not be opened — typical causes: "
                + "hunk @@ counts that don't match the hunk body, a truncated diff, or wrong paths."
                : "The hunk context could not be found in the target file even with whitespace-tolerant "
                + "and --recount matching — the file content differs from what the diff expects.";
        StringBuilder message = new StringBuilder();
        message.append("Patch failed — ").append(pathDescription).append(" left unchanged. Tried: ")
                .append(String.join(", ", ladder.tried)).append(".\n").append(reason).append("\n")
                .append("patch output:\n")
                .append(ladder.result == null ? "(none)" : ladder.result.output.trim());
        if (ladder.result != null && !ladder.result.rejects.isBlank()) {
            message.append("\nrejected hunks:\n").append(truncate(ladder.result.rejects, 1500));
        }
        message.append("\nRe-`read` the file and regenerate the diff against its current content, ")
                .append("or use the `edit` tool for a targeted replacement.");
        return ToolResult.error(message.toString());
    }

    // ── shared helpers ──────────────────────────────────────────────────────

    /**
     * Normalizes model-supplied patch text: unescapes payloads that arrived as a
     * single line of literal backslash-n sequences, strips carriage returns, and
     * guarantees a trailing newline. Never trims — leading/trailing whitespace is
     * significant in diffs (blank context lines).
     */
    static String normalizePatchText(String raw) {
        String patch = raw;
        if (!patch.contains("\n") && patch.contains("\\n")) {
            patch = patch.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", "\t");
        }
        patch = patch.replace("\r\n", "\n").replace("\r", "\n");
        if (!patch.endsWith("\n")) patch = patch + "\n";
        return patch;
    }

    private List<Path> pathsFromUnifiedDiff(ToolContext context, String patch) throws ToolExecutionException {
        Set<Path> paths = new LinkedHashSet<>();
        String pendingOldPath = null;
        for (String rawLine : patch.split("\\R")) {
            Matcher git = DIFF_GIT.matcher(rawLine);
            if (git.matches()) {
                paths.add(context.resolveMutationPath(git.group(2)));
                pendingOldPath = null;
                continue;
            }

            Matcher oldFile = OLD_FILE.matcher(rawLine);
            if (oldFile.matches()) {
                pendingOldPath = normalizePatchPath(oldFile.group(1));
                continue;
            }

            Matcher newFile = NEW_FILE.matcher(rawLine);
            if (newFile.matches()) {
                String newPath = normalizePatchPath(newFile.group(1));
                String target = "/dev/null".equals(newPath) ? pendingOldPath : newPath;
                if (target != null && !target.isBlank() && !"/dev/null".equals(target)) {
                    paths.add(context.resolveMutationPath(stripDiffPrefix(target)));
                }
                pendingOldPath = null;
            }
        }
        return new ArrayList<>(paths);
    }

    private boolean usesGitPathPrefixes(String patch) {
        return patch.lines().anyMatch(line -> line.startsWith("diff --git a/")
                || line.startsWith("--- a/") || line.startsWith("+++ b/"));
    }

    private String normalizePatchPath(String path) {
        String normalized = path.trim();
        int tab = normalized.indexOf('\t');
        if (tab >= 0) normalized = normalized.substring(0, tab);
        int space = normalized.indexOf(' ');
        if (space >= 0) normalized = normalized.substring(0, space);
        return normalized;
    }

    private String stripDiffPrefix(String path) {
        if (path.startsWith("a/") || path.startsWith("b/")) {
            return path.substring(2);
        }
        return path;
    }

    private void restoreTouchedFiles(Map<Path, String> originals, Set<Path> newFiles) throws Exception {
        for (Map.Entry<Path, String> entry : originals.entrySet()) {
            Files.writeString(entry.getKey(), entry.getValue());
        }
        for (Path path : newFiles) {
            Files.deleteIfExists(path);
        }
    }

    private String relativePath(ToolContext context, Path path) {
        try {
            return context.getWorkingDirectory().toAbsolutePath().normalize()
                    .relativize(path.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException ex) {
            return path.toString();
        }
    }

    private String joinRelativePaths(ToolContext context, List<Path> paths) {
        List<String> relative = new ArrayList<>();
        for (Path path : paths) {
            relative.add(relativePath(context, path));
        }
        return String.join(", ", relative);
    }

    private String firstText(JsonNode params, String... names) {
        for (String name : names) {
            String value = params.path(name).asText("").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    /** Like {@link #firstText} but preserves whitespace — diffs are whitespace-sensitive. */
    private String firstRawText(JsonNode params, String... names) {
        for (String name : names) {
            String value = params.path(name).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max) + "\n… (" + (text.length() - max) + " more chars)";
    }

    private record PatchRunResult(int exitCode, boolean timedOut, String output, String rejects) {
    }
}
