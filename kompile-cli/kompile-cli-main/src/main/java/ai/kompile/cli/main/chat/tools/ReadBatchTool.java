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
import ai.kompile.cli.main.codeindex.FileContextService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read MULTIPLE files in ONE tool call, with the same per-file semantics as
 * {@code read} (line numbers, offset/limit, binary detection). Every file read
 * successfully is recorded as freshly read, so a following {@code edit_batch} /
 * {@code edit_patch} / {@code edit} passes the read-before-edit gate — the
 * intended pairing is one read_batch then one edit_batch.
 */
public class ReadBatchTool implements CliTool {

    private static final int MAX_LINES = 2000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final long MAX_FILE_SIZE = 50 * 1024; // 50KB — beyond this, stop after the window
    private static final int MAX_FILES = 100;
    private static final int MAX_CONTEXT_FILES = 5;
    private static final int MAX_BATCH_CONTEXT_CHARS = 24_000;
    private final FileContextService fileContextService;

    public ReadBatchTool() {
        this(new FileContextService());
    }

    public ReadBatchTool(FileContextService fileContextService) {
        this.fileContextService = Objects.requireNonNull(fileContextService, "fileContextService");
    }

    @Override
    public String id() { return "read_batch"; }

    @Override
    public String description() {
        return "Read MULTIPLE files in a single call — use this instead of many sequential read calls, "
                + "especially before edit_batch/edit_patch (each file read here satisfies the "
                + "read-before-edit rule). files accepts plain path strings or "
                + "{file_path, offset, limit} objects for windowed reads. Per-file output matches "
                + "read (line-numbered, 2000-line/2000-char caps, binary files summarized). "
                + "Set include_context=true globally or per object entry to append durable notes plus "
                + "bounded code/KGraph context (maximum 5 contextual files per call). "
                + "A missing file is reported in its section without failing the rest.";
    }

    @Override
    public String compactHint() {
        return "Read MANY files in ONE call (line-numbered per file; counts toward read-before-edit). "
                + "files=[\"a.java\", {file_path:\"b.java\",offset:100,limit:50}, …]; missing files "
                + "report per-section. include_context=true appends bounded notes + code/KGraph context.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode files = props.putObject("files");
        files.put("type", "array");
        files.put("description", "Files to read: path strings, or objects with file_path and "
                + "optional offset (1-based start line) and limit (line count, default/max 2000)");
        ObjectNode item = files.putObject("items");
        item.putArray("anyOf").addObject().put("type", "string");
        ObjectNode objectForm = item.withArray("anyOf").addObject();
        objectForm.put("type", "object");
        ObjectNode objProps = objectForm.putObject("properties");
        objProps.putObject("file_path").put("type", "string");
        objProps.putObject("offset").put("type", "integer");
        objProps.putObject("limit").put("type", "integer");
        objProps.putObject("include_context").put("type", "boolean");
        objectForm.putArray("required").add("file_path");

        ObjectNode includeContext = props.putObject("include_context");
        includeContext.put("type", "boolean");
        includeContext.put("description", "Default context setting for entries (default false; per-entry value overrides it)");

        schema.putArray("required").add("files");
        return schema;
    }

    @Override
    public String permissionKey() { return "read"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Read files");

        JsonNode filesNode = params.path("files");
        if (!filesNode.isArray() || filesNode.isEmpty()) {
            return ToolResult.error("files (non-empty array) is required");
        }
        if (filesNode.size() > MAX_FILES) {
            return ToolResult.error("files has " + filesNode.size() + " entries (max " + MAX_FILES
                    + ") — split into several read_batch calls");
        }

        List<Request> requests = new ArrayList<>();
        boolean defaultIncludeContext = params.path("include_context").asBoolean(false);
        int contextRequests = 0;
        int index = 0;
        for (JsonNode entry : filesNode) {
            index++;
            String filePath;
            int offset = 1;
            int limit = MAX_LINES;
            boolean includeContext = defaultIncludeContext;
            if (entry.isTextual()) {
                filePath = entry.asText("");
            } else if (entry.isObject()) {
                filePath = entry.path("file_path").asText("");
                offset = entry.path("offset").asInt(1);
                limit = entry.path("limit").asInt(MAX_LINES);
                if (entry.has("include_context")) {
                    includeContext = entry.path("include_context").asBoolean(false);
                }
            } else {
                return ToolResult.error("files[" + (index - 1) + "]: expected a path string or "
                        + "{file_path, offset?, limit?} object");
            }
            if (filePath.isEmpty()) {
                return ToolResult.error("files[" + (index - 1) + "]: file_path is required");
            }
            if (offset < 1) offset = 1;
            if (limit < 1) limit = MAX_LINES;
            limit = Math.min(limit, MAX_LINES);
            if (includeContext && ++contextRequests > MAX_CONTEXT_FILES) {
                return ToolResult.error("read_batch context requested for more than "
                        + MAX_CONTEXT_FILES + " files — split the call or disable include_context");
            }
            requests.add(new Request(context.resolvePath(filePath), offset, limit, includeContext));
        }

        StringBuilder out = new StringBuilder();
        int filesRead = 0;
        int filesFailed = 0;
        int remainingContextChars = MAX_BATCH_CONTEXT_CHARS;
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (Request request : requests) {
            String display = display(context, request.path);
            Section section = readFile(context, request);
            if (section.error != null) {
                filesFailed++;
                out.append("== ").append(display).append(" — ERROR: ").append(section.error).append("\n\n");
            } else {
                filesRead++;
                out.append("== ").append(display).append(" (").append(section.note).append(")\n");
                out.append(section.body);
                if (!section.body.endsWith("\n")) out.append('\n');
                if (section.context != null) {
                    Map<String, Object> contextMetadata = new LinkedHashMap<>();
                    contextMetadata.put("filePath", display);
                    contextMetadata.put("context", section.context.summaryMetadata());
                    contexts.add(contextMetadata);
                    if (remainingContextChars >= 512) {
                        String rendered = fileContextService.render(section.context,
                                Math.min(FileContextService.DEFAULT_RENDER_CHARS, remainingContextChars));
                        out.append('\n').append(rendered);
                        remainingContextChars -= rendered.length();
                    } else {
                        out.append("\n--- File context omitted: batch context budget exhausted ---\n");
                    }
                }
                out.append('\n');
            }
        }

        String summary = filesRead + "/" + requests.size() + " files read"
                + (filesFailed > 0 ? ", " + filesFailed + " failed" : "");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("filesRead", filesRead);
        metadata.put("filesFailed", filesFailed);
        if (!contexts.isEmpty()) metadata.put("fileContexts", List.copyOf(contexts));
        String output = summary + "\n\n" + out.toString().stripTrailing();
        if (filesRead == 0 && filesFailed > 0) {
            return ToolResult.error(output);
        }
        return ToolResult.success("read_batch", output, metadata);
    }

    private Section readFile(ToolContext context, Request request) {
        Path path = request.path;
        if (!Files.exists(path)) {
            return Section.error("file not found");
        }
        if (!Files.isRegularFile(path)) {
            return Section.error("not a regular file (use the list tool for directories)");
        }
        try {
            long size = Files.size(path);
            if (SearchExclusions.isLikelyBinaryFile(path)) {
                return Section.error("binary file, " + size + " bytes");
            }

            int startLine = request.offset;
            int endExclusive = startLine + request.limit;
            boolean scanWhole = size <= MAX_FILE_SIZE;
            int totalLines = 0;
            int shown = 0;
            boolean truncated = false;
            StringBuilder body = new StringBuilder();

            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    totalLines++;
                    if (totalLines >= startLine && shown < request.limit) {
                        body.append(String.format("%6d\t%s%n", totalLines, truncateLine(line)));
                        shown++;
                    }
                    if (!scanWhole && totalLines > endExclusive) {
                        truncated = true;
                        break;
                    }
                }
            }

            context.recordFileRead(path);

            FileContextService.ContextSnapshot fileContext = request.includeContext
                    ? fileContextService.lookup(path, context.getWorkingDirectory()) : null;

            String note;
            if (shown == 0) {
                note = "file has " + totalLines + " lines, offset " + request.offset + " is past end";
            } else if (truncated || shown < totalLines || request.offset > 1) {
                note = "lines " + startLine + "-" + (startLine + shown - 1)
                        + (truncated ? ", more follow" : " of " + totalLines);
            } else {
                note = totalLines + " lines";
            }
            return Section.ok(note, body.toString(), fileContext);
        } catch (IOException e) {
            return Section.error("read failed: " + e.getMessage());
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

    private static String truncateLine(String line) {
        if (line == null) return "";
        if (line.length() <= MAX_LINE_LENGTH) return line;
        return line.substring(0, MAX_LINE_LENGTH) + "... (truncated)";
    }

    private record Request(Path path, int offset, int limit, boolean includeContext) {}

    private record Section(String error, String note, String body,
                           FileContextService.ContextSnapshot context) {
        static Section ok(String note, String body, FileContextService.ContextSnapshot context) {
            return new Section(null, note, body, context);
        }
        static Section error(String error) { return new Section(error, null, null, null); }
    }
}
