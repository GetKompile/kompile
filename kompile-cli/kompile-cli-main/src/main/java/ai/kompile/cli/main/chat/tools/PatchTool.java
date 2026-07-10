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
 * Apply unified diff patches using the system's patch command.
 * Comparable to OpenCode's PatchTool, with parameter aliases used by other CLIs.
 */
public class PatchTool implements CliTool {
    private static final Pattern DIFF_GIT = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern OLD_FILE = Pattern.compile("^---\\s+(.+)$");
    private static final Pattern NEW_FILE = Pattern.compile("^\\+\\+\\+\\s+(.+)$");

    @Override
    public String id() { return "patch"; }

    @Override
    public String description() {
        return "Apply a unified diff patch. Provide file_path/path/file plus patch/diff/unified_diff " +
                "for a single-file patch, or provide a full unified diff with file headers and omit " +
                "file_path. The patch is applied using the system's patch command. Use this for " +
                "multi-hunk changes that are easier to express as diffs.";
    }

    @Override
    public String compactHint() {
        return "Apply a unified diff via patch -u — accepts file_path+patch or full diff headers. "
                + "Prefer edit for most changes. The @@ line numbers and context must match exactly; "
                + "on 'Hunk FAILED' switch to edit, don't re-send the diff.";
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
        patch.put("description", "The unified diff patch content");

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
        String patch = firstText(params, "patch", "diff", "unified_diff");

        if (patch.isEmpty()) return ToolResult.error("patch, diff, or unified_diff is required");

        try {
            if (!filePath.isEmpty()) {
                return applySingleFilePatch(context, context.resolvePath(filePath), patch);
            }
            return applyHeaderPatch(context, patch);
        } catch (Exception e) {
            return ToolResult.error("Error applying patch: " + e.getMessage());
        }
    }

    private ToolResult applySingleFilePatch(ToolContext context, Path path, String patch) throws Exception {
        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }

        context.checkPermission(permissionKey(), "Patch file: " + path);
        if (!context.hasFreshFileRead(path)) {
            return ToolResult.error(context.staleReadMessage(path, "patch"));
        }

        String original = Files.readString(path);
        PatchRunResult result = runPatch(context.getWorkingDirectory(), patch,
                List.of("--force", "-u", path.toString()));
        String relativePath = relativePath(context, path);

        if (result.timedOut) {
            Files.writeString(path, original);
            return ToolResult.error("Patch command timed out after 30s — " + relativePath
                    + " left unchanged. Use the `edit` tool for this change instead.");
        }
        if (result.exitCode == 0) {
            context.recordFileRead(path);
            ai.kompile.cli.main.codeindex.BackgroundIndexService.getInstance()
                    .noteFileWritten(path);
            return ToolResult.success(relativePath,
                    "Patch applied successfully\n" + result.output.trim(),
                    Map.of("path", relativePath));
        }

        Files.writeString(path, original);
        return patchFailure(relativePath, result);
    }

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

        PatchRunResult result = runPatch(context.getWorkingDirectory(), patch,
                List.of("--force", "-u", usesGitPathPrefixes(patch) ? "-p1" : "-p0"));
        if (result.timedOut || result.exitCode != 0) {
            restoreTouchedFiles(originals, newFiles);
            if (result.timedOut) {
                return ToolResult.error("Patch command timed out after 30s — files left unchanged. "
                        + "Use the `edit` tool for this change instead.");
            }
            return patchFailure(joinRelativePaths(context, touched), result);
        }

        for (Path path : touched) {
            if (Files.exists(path)) {
                context.recordFileRead(path);
            }
        }
        return ToolResult.success("patch",
                "Patch applied successfully to " + touched.size() + " file(s)\n" + result.output.trim(),
                Map.of("paths", joinRelativePaths(context, touched), "files", touched.size()));
    }

    private PatchRunResult runPatch(Path workingDirectory, String patch, List<String> args) throws Exception {
        Path patchFile = Files.createTempFile("kompile-patch-", ".diff");
        Path rejectFile = Files.createTempFile("kompile-patch-", ".rej");
        try {
            Files.writeString(patchFile, patch, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>();
            command.add("patch");
            command.addAll(args);
            command.add("--reject-file=" + rejectFile);
            command.add("-i");
            command.add(patchFile.toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDirectory.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean done = process.waitFor(30, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return new PatchRunResult(-1, true, output.toString());
            }
            return new PatchRunResult(process.exitValue(), false, output.toString());
        } finally {
            Files.deleteIfExists(patchFile);
            Files.deleteIfExists(rejectFile);
        }
    }

    private ToolResult patchFailure(String pathDescription, PatchRunResult result) {
        return ToolResult.error(
                "Patch failed (exit " + result.exitCode + ") — " + pathDescription + " left unchanged. "
                        + "The diff's @@ line numbers or context lines did not match the file. Prefer "
                        + "the `edit` tool (exact string replacement): re-`read` the file and copy the "
                        + "target text verbatim into old_string, instead of re-sending the diff.\n"
                        + "patch output:\n" + result.output.trim());
    }

    private List<Path> pathsFromUnifiedDiff(ToolContext context, String patch) throws ToolExecutionException {
        Set<Path> paths = new LinkedHashSet<>();
        String pendingOldPath = null;
        for (String rawLine : patch.split("\\R")) {
            Matcher git = DIFF_GIT.matcher(rawLine);
            if (git.matches()) {
                paths.add(context.resolvePath(git.group(2)));
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
                    paths.add(context.resolvePath(stripDiffPrefix(target)));
                }
                pendingOldPath = null;
            }
        }
        return new ArrayList<>(paths);
    }

    private boolean usesGitPathPrefixes(String patch) {
        return patch.contains("diff --git a/") || patch.contains("--- a/") || patch.contains("+++ b/");
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

    private record PatchRunResult(int exitCode, boolean timedOut, String output) {
    }
}
