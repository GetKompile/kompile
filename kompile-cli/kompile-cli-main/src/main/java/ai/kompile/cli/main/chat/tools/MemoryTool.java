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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Persistent memory tool that provides three complementary layers of
 * cross-session memory for the kompile chat CLI:
 *
 * <ol>
 *   <li><b>Flat markdown files</b> — {@code read}/{@code write}/{@code append}/
 *       {@code list}/{@code search} raw markdown files in the memory directory
 *       (the original Claude-Code-style MEMORY.md + topic files).</li>
 *   <li><b>Typed memories</b> — {@code save}/{@code forget}/{@code recall}/
 *       {@code types} store individual memory files with YAML frontmatter
 *       ({@code name}, {@code description}, {@code type}) and automatically
 *       maintain a {@code MEMORY.md} index. Types follow the Claude-Code
 *       auto-memory taxonomy: {@code user}, {@code feedback}, {@code project},
 *       {@code reference}.</li>
 *   <li><b>Knowledge graph</b> — {@code create_entity}, {@code create_relation},
 *       {@code add_observation}, {@code delete_entity}, {@code delete_relation},
 *       {@code delete_observation}, {@code read_graph}, {@code search_nodes},
 *       and {@code open_nodes} implement the official MCP memory server API
 *       against a JSONL-backed graph ({@code graph.jsonl}).</li>
 * </ol>
 *
 * <p>Memory is stored at two scopes:
 * <ul>
 *   <li><b>Global</b>: {@code ~/.kompile/memory/} - user-wide preferences and
 *       cross-project knowledge</li>
 *   <li><b>Project</b>: {@code .kompile/memory/} - project-specific
 *       architecture decisions, conventions, debugging notes</li>
 * </ul>
 */
public class MemoryTool implements CliTool {

    private static final String MEMORY_DIR = "memory";
    private static final String DEFAULT_FILE = "MEMORY.md";
    private static final String INDEX_FILE = "MEMORY.md";
    private static final String GRAPH_FILE = "graph.jsonl";

    private static final int MAX_MEMORY_FILE_SIZE = 50_000; // ~50KB
    private static final int MAX_MAIN_MEMORY_LINES = 200;

    private static final Set<String> MEMORY_TYPES =
            Set.of("user", "feedback", "project", "reference");

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    @Override
    public String id() { return "memory"; }

    @Override
    public String description() {
        return "Persistent memory across chat sessions. Three layers: "
                + "(1) FLAT FILES — 'read', 'write', 'append', 'list', 'search' raw markdown "
                + "files under .kompile/memory/ (project) or ~/.kompile/memory/ (global). "
                + "(2) TYPED MEMORIES — 'save' with memoryType=user|feedback|project|reference, "
                + "name, description, content; 'forget' by name; 'recall' with query and optional "
                + "memoryType filter; 'types' to browse by type. Typed memories are individual "
                + "files with YAML frontmatter and are auto-indexed in MEMORY.md. Use for user "
                + "preferences, workflow feedback, project facts, and external references. "
                + "(3) KNOWLEDGE GRAPH — 'create_entity' with entities[{name,entityType,observations[]}], "
                + "'create_relation' with relations[{from,to,relationType}], 'add_observation' with "
                + "observations[{entityName,contents[]}], 'delete_entity' with names[], "
                + "'delete_relation', 'delete_observation' with deletions[{entityName,observations[]}], "
                + "'read_graph', 'search_nodes' with query, 'open_nodes' with names[]. Backed by "
                + "graph.jsonl; implements the official MCP memory server API. "
                + "Default scope is 'project'; pass scope='global' for cross-project memory.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        addStringProp(props, "action",
                "Action. Flat: read|write|append|list|search. "
                        + "Typed: save|forget|recall|types. "
                        + "Graph: create_entity|create_relation|add_observation|"
                        + "delete_entity|delete_relation|delete_observation|"
                        + "read_graph|search_nodes|open_nodes");
        addStringProp(props, "scope", "Memory scope: 'project' (default) or 'global'");
        addStringProp(props, "file",
                "File name for flat ops (default: MEMORY.md). Use topic files like "
                        + "'debugging.md' for detailed notes.");
        addStringProp(props, "content", "Content to write/append/save");
        addStringProp(props, "query", "Search query for search/recall/search_nodes");
        addStringProp(props, "memoryType",
                "Memory type for save/recall/types: user|feedback|project|reference");
        addStringProp(props, "name",
                "Memory name (for save/forget) or a single entity name (for graph ops)");
        addStringProp(props, "description",
                "One-line description used as the MEMORY.md index hook for typed memories");

        // Array params for batch ops (graph + bulk typed operations)
        addArrayProp(props, "entities",
                "Array for create_entity: [{name, entityType, observations[]}]");
        addArrayProp(props, "relations",
                "Array for create_relation/delete_relation: [{from, to, relationType}]");
        addArrayProp(props, "observations",
                "Array for add_observation: [{entityName, contents[]}]");
        addArrayProp(props, "deletions",
                "Array for delete_observation: [{entityName, observations[]}]");
        addArrayProp(props, "names",
                "Array of names for delete_entity/open_nodes");

        schema.putArray("required").add("action");
        return schema;
    }

    private static void addStringProp(ObjectNode props, String name, String desc) {
        ObjectNode p = props.putObject(name);
        p.put("type", "string");
        p.put("description", desc);
    }

    private static void addArrayProp(ObjectNode props, String name, String desc) {
        ObjectNode p = props.putObject(name);
        p.put("type", "array");
        p.put("description", desc);
        p.putObject("items").put("type", "object");
    }

    @Override
    public String permissionKey() { return "memory"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Access persistent memory");

        String action = params.path("action").asText("");
        String scope = params.path("scope").asText("project");
        Path memDir = resolveMemoryDir(scope, context.getWorkingDirectory());

        try {
            switch (action) {
                // Flat markdown file operations
                case "read":
                    return readMemory(memDir,
                            sanitizeFileName(params.path("file").asText(DEFAULT_FILE)), scope);
                case "write":
                    return writeMemory(memDir,
                            sanitizeFileName(params.path("file").asText(DEFAULT_FILE)),
                            params.path("content").asText(""), scope);
                case "append":
                    return appendMemory(memDir,
                            sanitizeFileName(params.path("file").asText(DEFAULT_FILE)),
                            params.path("content").asText(""), scope);
                case "list":
                    return listMemoryFiles(context.getWorkingDirectory());
                case "search":
                    return searchMemory(params.path("query").asText(""),
                            context.getWorkingDirectory());

                // Typed memory operations
                case "save":
                    return saveTypedMemory(memDir, params, scope);
                case "forget":
                    return forgetTypedMemory(memDir, params, scope);
                case "recall":
                    return recallTypedMemory(memDir, params, scope);
                case "types":
                    return listByType(memDir, params, scope);

                // Knowledge graph operations (MCP memory server API)
                case "create_entity":
                case "create_entities":
                    return createEntities(memDir, params, scope);
                case "create_relation":
                case "create_relations":
                    return createRelations(memDir, params, scope);
                case "add_observation":
                case "add_observations":
                    return addObservations(memDir, params, scope);
                case "delete_entity":
                case "delete_entities":
                    return deleteEntities(memDir, params, scope);
                case "delete_relation":
                case "delete_relations":
                    return deleteRelations(memDir, params, scope);
                case "delete_observation":
                case "delete_observations":
                    return deleteObservations(memDir, params, scope);
                case "read_graph":
                    return readGraph(memDir, scope);
                case "search_nodes":
                    return searchNodes(memDir, params.path("query").asText(""), scope);
                case "open_nodes":
                    return openNodes(memDir, params, scope);

                default:
                    return ToolResult.error("Unknown action: '" + action + "'. Flat: "
                            + "read|write|append|list|search. Typed: save|forget|recall|types. "
                            + "Graph: create_entity|create_relation|add_observation|"
                            + "delete_entity|delete_relation|delete_observation|"
                            + "read_graph|search_nodes|open_nodes.");
            }
        } catch (IOException e) {
            return ToolResult.error("I/O error: " + e.getMessage());
        }
    }

    // ========================================================================
    // Flat markdown file operations (original MemoryTool behavior)
    // ========================================================================

    private ToolResult readMemory(Path memDir, String fileName, String scope) {
        Path file = memDir.resolve(fileName);
        if (!Files.exists(file)) {
            return ToolResult.success("No " + scope + " memory file found at: " + file
                    + "\nUse action='write' to create one.");
        }

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (fileName.equals(DEFAULT_FILE)) {
                String[] lines = content.split("\n");
                if (lines.length > MAX_MAIN_MEMORY_LINES) {
                    return ToolResult.success("memory: " + scope + "/" + fileName, content,
                            Map.of("scope", scope, "file", fileName,
                                    "lines", lines.length,
                                    "warning", "MEMORY.md exceeds " + MAX_MAIN_MEMORY_LINES
                                            + " lines. Consider moving details to topic files."));
                }
            }
            return ToolResult.success("memory: " + scope + "/" + fileName, content,
                    Map.of("scope", scope, "file", fileName));
        } catch (IOException e) {
            return ToolResult.error("Error reading memory file: " + e.getMessage());
        }
    }

    private ToolResult writeMemory(Path memDir, String fileName, String content, String scope) {
        if (content.isEmpty()) {
            return ToolResult.error("content is required for 'write' action");
        }

        if (content.length() > MAX_MEMORY_FILE_SIZE) {
            return ToolResult.error("Content too large (" + content.length()
                    + " chars). Maximum is " + MAX_MEMORY_FILE_SIZE
                    + ". Break into topic files.");
        }

        Path file = memDir.resolve(fileName);
        try {
            Files.createDirectories(memDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            int lines = content.split("\n").length;

            String warning = null;
            if (fileName.equals(DEFAULT_FILE) && lines > MAX_MAIN_MEMORY_LINES) {
                warning = "MEMORY.md has " + lines + " lines (recommended max: "
                        + MAX_MAIN_MEMORY_LINES + "). Consider moving details to topic files.";
            }

            Map<String, Object> meta = warning != null
                    ? Map.of("scope", scope, "file", fileName, "lines", lines, "warning", warning)
                    : Map.of("scope", scope, "file", fileName, "lines", lines);

            return ToolResult.success("memory: wrote " + scope + "/" + fileName
                    + " (" + lines + " lines)", "Saved to " + file, meta);
        } catch (IOException e) {
            return ToolResult.error("Error writing memory file: " + e.getMessage());
        }
    }

    private ToolResult appendMemory(Path memDir, String fileName, String content, String scope) {
        if (content.isEmpty()) {
            return ToolResult.error("content is required for 'append' action");
        }

        Path file = memDir.resolve(fileName);
        try {
            Files.createDirectories(memDir);

            String existing = "";
            if (Files.exists(file)) {
                existing = Files.readString(file, StandardCharsets.UTF_8);
            }

            String timestamped = "\n\n## " + TIMESTAMP_FMT.format(Instant.now()) + "\n" + content;
            String newContent = existing + timestamped;

            if (newContent.length() > MAX_MEMORY_FILE_SIZE) {
                return ToolResult.error("Append would exceed maximum file size ("
                        + MAX_MEMORY_FILE_SIZE
                        + " chars). Use 'write' to replace or create a topic file.");
            }

            Files.writeString(file, newContent, StandardCharsets.UTF_8);
            int lines = newContent.split("\n").length;

            return ToolResult.success("memory: appended to " + scope + "/" + fileName
                            + " (" + lines + " lines)",
                    "Appended to " + file,
                    Map.of("scope", scope, "file", fileName, "lines", lines));
        } catch (IOException e) {
            return ToolResult.error("Error appending to memory file: " + e.getMessage());
        }
    }

    private ToolResult listMemoryFiles(Path workDir) {
        StringBuilder sb = new StringBuilder();

        Path projectDir = workDir.resolve(".kompile").resolve(MEMORY_DIR);
        sb.append("Project memory (").append(projectDir).append("):\n");
        listDir(projectDir, sb);

        Path globalDir = KompileHome.homeDirectory().toPath().resolve(MEMORY_DIR);
        sb.append("\nGlobal memory (").append(globalDir).append("):\n");
        listDir(globalDir, sb);

        return ToolResult.success("memory: list", sb.toString(), Map.of());
    }

    private void listDir(Path dir, StringBuilder sb) {
        if (!Files.exists(dir)) {
            sb.append("  (empty - no memory files yet)\n");
            return;
        }

        try (var files = Files.list(dir)) {
            var list = files.filter(p -> !Files.isDirectory(p))
                    .sorted()
                    .toList();

            if (list.isEmpty()) {
                sb.append("  (empty)\n");
                return;
            }

            for (Path f : list) {
                try {
                    long size = Files.size(f);
                    String[] lines = Files.readString(f, StandardCharsets.UTF_8).split("\n");
                    sb.append(String.format("  %-30s %5d lines  %6d bytes%n",
                            f.getFileName(), lines.length, size));
                } catch (IOException e) {
                    sb.append("  ").append(f.getFileName()).append(" (unreadable)\n");
                }
            }
        } catch (IOException e) {
            sb.append("  (error listing: ").append(e.getMessage()).append(")\n");
        }
    }

    private ToolResult searchMemory(String query, Path workDir) {
        if (query.isEmpty()) {
            return ToolResult.error("query is required for 'search' action");
        }

        String queryLower = query.toLowerCase();
        StringBuilder sb = new StringBuilder();
        int matchCount = 0;

        Path projectDir = workDir.resolve(".kompile").resolve(MEMORY_DIR);
        matchCount += searchDir(projectDir, queryLower, "project", sb);

        Path globalDir = KompileHome.homeDirectory().toPath().resolve(MEMORY_DIR);
        matchCount += searchDir(globalDir, queryLower, "global", sb);

        if (matchCount == 0) {
            return ToolResult.success("No memory matches for: " + query);
        }

        return ToolResult.success("memory: search '" + query + "'", sb.toString(),
                Map.of("query", query, "matchCount", matchCount));
    }

    private int searchDir(Path dir, String queryLower, String scope, StringBuilder sb) {
        if (!Files.exists(dir)) return 0;

        int matches = 0;
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> !Files.isDirectory(p)).toList()) {
                try {
                    String content = Files.readString(f, StandardCharsets.UTF_8);
                    String[] lines = content.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].toLowerCase().contains(queryLower)) {
                            if (matches == 0
                                    || !sb.toString().endsWith(f.getFileName() + ":\n")) {
                                sb.append("\n").append(scope).append("/")
                                        .append(f.getFileName()).append(":\n");
                            }
                            int start = Math.max(0, i - 1);
                            int end = Math.min(lines.length - 1, i + 1);
                            for (int j = start; j <= end; j++) {
                                sb.append(j == i ? ">>> " : "    ")
                                        .append("L").append(j + 1).append(": ")
                                        .append(lines[j]).append("\n");
                            }
                            matches++;
                            if (matches >= 30) return matches;
                        }
                    }
                } catch (IOException e) {
                    // Skip unreadable files
                }
            }
        } catch (IOException e) {
            // Skip
        }
        return matches;
    }

    // ========================================================================
    // Typed memory operations (Claude-Code-style save/forget/recall/types)
    // ========================================================================

    private ToolResult saveTypedMemory(Path memDir, JsonNode params, String scope)
            throws IOException {
        String memoryType = params.path("memoryType").asText("").trim().toLowerCase();
        if (!MEMORY_TYPES.contains(memoryType)) {
            return ToolResult.error("memoryType must be one of " + MEMORY_TYPES
                    + " (got: '" + memoryType + "')");
        }
        String name = params.path("name").asText("").trim();
        if (name.isEmpty()) {
            return ToolResult.error("'name' is required for save");
        }
        String description = params.path("description").asText("").trim();
        String content = params.path("content").asText("").trim();
        if (content.isEmpty()) {
            return ToolResult.error("'content' is required for save");
        }

        String fileName = slug(name) + ".md";
        Path file = memDir.resolve(fileName);
        Files.createDirectories(memDir);

        StringBuilder mem = new StringBuilder();
        mem.append("---\n");
        mem.append("name: ").append(yamlEscape(name)).append("\n");
        mem.append("description: ").append(yamlEscape(description)).append("\n");
        mem.append("type: ").append(memoryType).append("\n");
        mem.append("---\n\n");
        mem.append(content);
        if (!content.endsWith("\n")) mem.append("\n");

        if (mem.length() > MAX_MEMORY_FILE_SIZE) {
            return ToolResult.error("Memory content too large (" + mem.length()
                    + " chars). Max: " + MAX_MEMORY_FILE_SIZE);
        }
        Files.writeString(file, mem.toString(), StandardCharsets.UTF_8);

        updateIndex(memDir, fileName, name, description, memoryType, true);

        return ToolResult.success("memory: saved " + scope + "/" + fileName,
                "Saved typed memory '" + name + "' (type=" + memoryType + ") to " + file,
                Map.of("scope", scope, "file", fileName, "name", name, "type", memoryType));
    }

    private ToolResult forgetTypedMemory(Path memDir, JsonNode params, String scope)
            throws IOException {
        String explicitFile = params.path("file").asText("").trim();
        String name = params.path("name").asText("").trim();

        String fileName;
        if (!explicitFile.isEmpty()) {
            fileName = sanitizeFileName(explicitFile);
        } else if (!name.isEmpty()) {
            fileName = slug(name) + ".md";
        } else {
            return ToolResult.error("'name' or 'file' is required for forget");
        }

        Path file = memDir.resolve(fileName);
        if (!Files.exists(file)) {
            return ToolResult.error("No memory file at: " + file);
        }
        Files.delete(file);

        updateIndex(memDir, fileName, null, null, null, false);

        return ToolResult.success("memory: forgot " + scope + "/" + fileName,
                "Removed memory file: " + file,
                Map.of("scope", scope, "file", fileName));
    }

    private ToolResult recallTypedMemory(Path memDir, JsonNode params, String scope)
            throws IOException {
        String query = params.path("query").asText("").trim().toLowerCase();
        String typeFilter = params.path("memoryType").asText("").trim().toLowerCase();
        if (query.isEmpty() && typeFilter.isEmpty()) {
            return ToolResult.error("'query' or 'memoryType' is required for recall");
        }

        if (!Files.exists(memDir)) {
            return ToolResult.success("No memory directory at: " + memDir);
        }

        List<Map<String, String>> matches = new ArrayList<>();
        try (var files = Files.list(memDir)) {
            for (Path f : files.filter(p -> !Files.isDirectory(p)).toList()) {
                String fn = f.getFileName().toString();
                if (!fn.endsWith(".md") || fn.equals(INDEX_FILE)) continue;
                String content;
                try {
                    content = Files.readString(f, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }

                Frontmatter fm = parseFrontmatter(content);
                if (fm == null) continue;

                if (!typeFilter.isEmpty() && !typeFilter.equals(fm.type)) continue;
                if (!query.isEmpty()) {
                    String hay = (fm.name + " " + fm.description + " " + fm.body).toLowerCase();
                    if (!hay.contains(query)) continue;
                }

                Map<String, String> m = new LinkedHashMap<>();
                m.put("file", fn);
                m.put("name", fm.name);
                m.put("type", fm.type);
                m.put("description", fm.description);
                m.put("body", fm.body);
                matches.add(m);
            }
        }

        if (matches.isEmpty()) {
            return ToolResult.success("No memory matches for query='" + query
                    + "' type='" + typeFilter + "'");
        }

        StringBuilder sb = new StringBuilder();
        for (Map<String, String> m : matches) {
            sb.append("## ").append(m.get("name"))
                    .append(" (").append(m.get("type"))
                    .append(", ").append(m.get("file")).append(")\n");
            if (!m.get("description").isEmpty()) {
                sb.append(m.get("description")).append("\n\n");
            }
            sb.append(m.get("body")).append("\n\n");
        }
        return ToolResult.success("memory: recall " + matches.size() + " matches",
                sb.toString(),
                Map.of("scope", scope, "matches", matches.size()));
    }

    private ToolResult listByType(Path memDir, JsonNode params, String scope) throws IOException {
        String typeFilter = params.path("memoryType").asText("").trim().toLowerCase();

        if (!Files.exists(memDir)) {
            return ToolResult.success("No memory directory at: " + memDir);
        }

        Map<String, List<String>> byType = new TreeMap<>();
        for (String t : MEMORY_TYPES) byType.put(t, new ArrayList<>());

        try (var files = Files.list(memDir)) {
            for (Path f : files.filter(p -> !Files.isDirectory(p)).toList()) {
                String fn = f.getFileName().toString();
                if (!fn.endsWith(".md") || fn.equals(INDEX_FILE)) continue;
                String content;
                try {
                    content = Files.readString(f, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                Frontmatter fm = parseFrontmatter(content);
                if (fm == null) continue;
                List<String> bucket = byType.get(fm.type);
                if (bucket == null) continue;
                String desc = fm.description.isEmpty() ? "" : " — " + fm.description;
                bucket.add(fn + ": " + fm.name + desc);
            }
        }

        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map.Entry<String, List<String>> e : byType.entrySet()) {
            if (!typeFilter.isEmpty() && !typeFilter.equals(e.getKey())) continue;
            sb.append("\n[").append(e.getKey()).append("] (")
                    .append(e.getValue().size()).append(")\n");
            if (e.getValue().isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (String line : e.getValue()) {
                    sb.append("  ").append(line).append("\n");
                    total++;
                }
            }
        }
        return ToolResult.success("memory: types (" + total + ")", sb.toString(),
                Map.of("scope", scope, "total", total));
    }

    /**
     * Maintain MEMORY.md as an index of typed memory files. Each typed memory
     * has a line like: {@code - [name](file.md) — [type] description}.
     */
    private void updateIndex(Path memDir, String fileName, String name,
                             String description, String type, boolean add) throws IOException {
        Path indexFile = memDir.resolve(INDEX_FILE);
        List<String> lines;
        if (Files.exists(indexFile)) {
            lines = new ArrayList<>(Files.readAllLines(indexFile, StandardCharsets.UTF_8));
        } else {
            lines = new ArrayList<>();
        }

        // Remove any existing pointer at this file
        String pointer = "](" + fileName + ")";
        lines.removeIf(l -> l.trim().startsWith("- [") && l.contains(pointer));

        if (add) {
            if (lines.isEmpty()) {
                lines.add("# Kompile Memory Index");
                lines.add("");
            }
            String hook = description == null || description.isEmpty() ? name : description;
            if (hook.length() > 120) hook = hook.substring(0, 117) + "...";
            String entry = "- [" + escapeMarkdown(name) + "](" + fileName + ") — ["
                    + type + "] " + hook;
            lines.add(entry);
        }

        Files.createDirectories(memDir);
        Files.writeString(indexFile, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    // ========================================================================
    // Knowledge graph operations (MCP memory server API)
    // ========================================================================

    private static class Entity {
        String name;
        String entityType;
        List<String> observations = new ArrayList<>();
    }

    private static class Relation {
        String from;
        String to;
        String relationType;
    }

    private static class Graph {
        Map<String, Entity> entities = new LinkedHashMap<>();
        List<Relation> relations = new ArrayList<>();
    }

    private Graph loadGraph(Path memDir) throws IOException {
        Graph g = new Graph();
        Path gf = memDir.resolve(GRAPH_FILE);
        if (!Files.exists(gf)) return g;

        List<String> lines = Files.readAllLines(gf, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                String type = node.path("type").asText("");
                if ("entity".equals(type)) {
                    Entity e = new Entity();
                    e.name = node.path("name").asText("");
                    e.entityType = node.path("entityType").asText("");
                    JsonNode obsNode = node.path("observations");
                    if (obsNode.isArray()) {
                        for (JsonNode o : obsNode) e.observations.add(o.asText(""));
                    }
                    if (!e.name.isEmpty()) g.entities.put(e.name, e);
                } else if ("relation".equals(type)) {
                    Relation r = new Relation();
                    r.from = node.path("from").asText("");
                    r.to = node.path("to").asText("");
                    r.relationType = node.path("relationType").asText("");
                    if (!r.from.isEmpty() && !r.to.isEmpty() && !r.relationType.isEmpty()) {
                        g.relations.add(r);
                    }
                }
            } catch (JsonProcessingException ex) {
                // Skip corrupted lines
            }
        }
        return g;
    }

    private void saveGraph(Path memDir, Graph g) throws IOException {
        Files.createDirectories(memDir);
        StringBuilder sb = new StringBuilder();
        for (Entity e : g.entities.values()) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("type", "entity");
            n.put("name", e.name);
            n.put("entityType", e.entityType);
            ArrayNode arr = n.putArray("observations");
            for (String o : e.observations) arr.add(o);
            sb.append(MAPPER.writeValueAsString(n)).append("\n");
        }
        for (Relation r : g.relations) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("type", "relation");
            n.put("from", r.from);
            n.put("to", r.to);
            n.put("relationType", r.relationType);
            sb.append(MAPPER.writeValueAsString(n)).append("\n");
        }
        Files.writeString(memDir.resolve(GRAPH_FILE), sb.toString(), StandardCharsets.UTF_8);
    }

    private ToolResult createEntities(Path memDir, JsonNode params, String scope)
            throws IOException {
        JsonNode entitiesNode = params.path("entities");
        // Allow single entity via top-level name/entityType for convenience
        if ((!entitiesNode.isArray() || entitiesNode.isEmpty())
                && !params.path("name").asText("").isEmpty()) {
            ArrayNode fallback = MAPPER.createArrayNode();
            ObjectNode single = fallback.addObject();
            single.put("name", params.path("name").asText(""));
            single.put("entityType", params.path("description").asText(""));
            entitiesNode = fallback;
        }
        if (!entitiesNode.isArray() || entitiesNode.isEmpty()) {
            return ToolResult.error("'entities' array is required for create_entity");
        }
        Graph g = loadGraph(memDir);
        int added = 0, skipped = 0;
        for (JsonNode en : entitiesNode) {
            String name = en.path("name").asText("").trim();
            if (name.isEmpty()) continue;
            if (g.entities.containsKey(name)) {
                skipped++;
                continue;
            }
            Entity e = new Entity();
            e.name = name;
            e.entityType = en.path("entityType").asText("");
            JsonNode obs = en.path("observations");
            if (obs.isArray()) {
                for (JsonNode o : obs) {
                    String v = o.asText("");
                    if (!v.isEmpty()) e.observations.add(v);
                }
            }
            g.entities.put(name, e);
            added++;
        }
        saveGraph(memDir, g);
        return ToolResult.success(
                "memory: created " + added + " entities"
                        + (skipped > 0 ? " (" + skipped + " already existed)" : ""),
                "Entities: " + added + " added, " + skipped + " skipped (duplicates). "
                        + "Total now: " + g.entities.size(),
                Map.of("scope", scope, "added", added, "skipped", skipped,
                        "total", g.entities.size()));
    }

    private ToolResult createRelations(Path memDir, JsonNode params, String scope)
            throws IOException {
        JsonNode relsNode = params.path("relations");
        if (!relsNode.isArray() || relsNode.isEmpty()) {
            return ToolResult.error("'relations' array is required for create_relation");
        }
        Graph g = loadGraph(memDir);
        int added = 0, skipped = 0;
        for (JsonNode rn : relsNode) {
            String from = rn.path("from").asText("").trim();
            String to = rn.path("to").asText("").trim();
            String relType = rn.path("relationType").asText("").trim();
            if (from.isEmpty() || to.isEmpty() || relType.isEmpty()) continue;
            boolean dup = g.relations.stream().anyMatch(r ->
                    r.from.equals(from) && r.to.equals(to) && r.relationType.equals(relType));
            if (dup) {
                skipped++;
                continue;
            }
            Relation r = new Relation();
            r.from = from;
            r.to = to;
            r.relationType = relType;
            g.relations.add(r);
            added++;
        }
        saveGraph(memDir, g);
        return ToolResult.success(
                "memory: created " + added + " relations"
                        + (skipped > 0 ? " (" + skipped + " duplicates)" : ""),
                "Relations: " + added + " added. Total now: " + g.relations.size(),
                Map.of("scope", scope, "added", added, "skipped", skipped,
                        "total", g.relations.size()));
    }

    private ToolResult addObservations(Path memDir, JsonNode params, String scope)
            throws IOException {
        JsonNode obsNode = params.path("observations");
        if (!obsNode.isArray() || obsNode.isEmpty()) {
            return ToolResult.error("'observations' array is required for add_observation");
        }
        Graph g = loadGraph(memDir);
        int added = 0;
        List<String> missing = new ArrayList<>();
        for (JsonNode on : obsNode) {
            String entityName = on.path("entityName").asText("").trim();
            if (entityName.isEmpty()) continue;
            Entity e = g.entities.get(entityName);
            if (e == null) {
                missing.add(entityName);
                continue;
            }
            JsonNode contents = on.path("contents");
            if (contents.isArray()) {
                for (JsonNode c : contents) {
                    String v = c.asText("");
                    if (!v.isEmpty() && !e.observations.contains(v)) {
                        e.observations.add(v);
                        added++;
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            return ToolResult.error("Entity not found: " + missing
                    + ". Create the entity first with create_entity.");
        }
        saveGraph(memDir, g);
        return ToolResult.success("memory: added " + added + " observations",
                "Observations added: " + added,
                Map.of("scope", scope, "added", added));
    }

    private ToolResult deleteEntities(Path memDir, JsonNode params, String scope)
            throws IOException {
        Set<String> toDelete = collectNames(params);
        if (toDelete.isEmpty()) {
            return ToolResult.error("'names' array or 'name' is required for delete_entity");
        }
        Graph g = loadGraph(memDir);
        int deleted = 0;
        for (String name : toDelete) {
            if (g.entities.remove(name) != null) deleted++;
        }
        int relsBefore = g.relations.size();
        g.relations.removeIf(r -> toDelete.contains(r.from) || toDelete.contains(r.to));
        int cascaded = relsBefore - g.relations.size();
        saveGraph(memDir, g);
        return ToolResult.success(
                "memory: deleted " + deleted + " entities"
                        + (cascaded > 0 ? " (+ " + cascaded + " cascaded relations)" : ""),
                "Deleted " + deleted + " entities and cascaded " + cascaded + " relations",
                Map.of("scope", scope, "deleted", deleted, "cascadedRelations", cascaded));
    }

    private ToolResult deleteRelations(Path memDir, JsonNode params, String scope)
            throws IOException {
        JsonNode relsNode = params.path("relations");
        if (!relsNode.isArray() || relsNode.isEmpty()) {
            return ToolResult.error("'relations' array is required for delete_relation");
        }
        Graph g = loadGraph(memDir);
        int deleted = 0;
        for (JsonNode rn : relsNode) {
            String from = rn.path("from").asText("");
            String to = rn.path("to").asText("");
            String relType = rn.path("relationType").asText("");
            boolean removed = g.relations.removeIf(r ->
                    r.from.equals(from) && r.to.equals(to) && r.relationType.equals(relType));
            if (removed) deleted++;
        }
        saveGraph(memDir, g);
        return ToolResult.success("memory: deleted " + deleted + " relations",
                "Relations removed: " + deleted,
                Map.of("scope", scope, "deleted", deleted));
    }

    private ToolResult deleteObservations(Path memDir, JsonNode params, String scope)
            throws IOException {
        JsonNode delsNode = params.path("deletions");
        if (!delsNode.isArray() || delsNode.isEmpty()) {
            // Tolerate the same shape under 'observations'
            delsNode = params.path("observations");
        }
        if (!delsNode.isArray() || delsNode.isEmpty()) {
            return ToolResult.error(
                    "'deletions' array is required for delete_observation: "
                            + "[{entityName, observations[]}]");
        }
        Graph g = loadGraph(memDir);
        int removed = 0;
        for (JsonNode dn : delsNode) {
            String entityName = dn.path("entityName").asText("");
            Entity e = g.entities.get(entityName);
            if (e == null) continue;
            JsonNode obsList = dn.path("observations");
            if (obsList.isArray()) {
                for (JsonNode o : obsList) {
                    String v = o.asText("");
                    if (!v.isEmpty() && e.observations.remove(v)) removed++;
                }
            }
        }
        saveGraph(memDir, g);
        return ToolResult.success("memory: deleted " + removed + " observations",
                "Observations removed: " + removed,
                Map.of("scope", scope, "removed", removed));
    }

    private ToolResult readGraph(Path memDir, String scope) throws IOException {
        Graph g = loadGraph(memDir);
        String json = graphToJson(g, g.entities.values(), g.relations);
        return ToolResult.success(
                "memory: graph (" + g.entities.size() + " entities, "
                        + g.relations.size() + " relations)",
                json,
                Map.of("scope", scope,
                        "entities", g.entities.size(),
                        "relations", g.relations.size()));
    }

    private ToolResult searchNodes(Path memDir, String query, String scope) throws IOException {
        if (query.isEmpty()) return ToolResult.error("'query' is required for search_nodes");
        String q = query.toLowerCase();
        Graph g = loadGraph(memDir);
        List<Entity> matches = new ArrayList<>();
        for (Entity e : g.entities.values()) {
            boolean hit = e.name.toLowerCase().contains(q)
                    || e.entityType.toLowerCase().contains(q)
                    || e.observations.stream().anyMatch(o -> o.toLowerCase().contains(q));
            if (hit) matches.add(e);
        }
        Set<String> matchNames = new LinkedHashSet<>();
        for (Entity e : matches) matchNames.add(e.name);
        List<Relation> subRels = g.relations.stream()
                .filter(r -> matchNames.contains(r.from) && matchNames.contains(r.to))
                .toList();
        String json = graphToJson(g, matches, subRels);
        return ToolResult.success(
                "memory: search_nodes '" + query + "' → " + matches.size() + " matches",
                json,
                Map.of("scope", scope, "query", query,
                        "entities", matches.size(),
                        "relations", subRels.size()));
    }

    private ToolResult openNodes(Path memDir, JsonNode params, String scope) throws IOException {
        Set<String> wanted = collectNames(params);
        if (wanted.isEmpty()) {
            return ToolResult.error("'names' array or 'name' is required for open_nodes");
        }
        Graph g = loadGraph(memDir);
        List<Entity> picked = new ArrayList<>();
        for (String n : wanted) {
            Entity e = g.entities.get(n);
            if (e != null) picked.add(e);
        }
        List<Relation> rels = g.relations.stream()
                .filter(r -> wanted.contains(r.from) && wanted.contains(r.to))
                .toList();
        String json = graphToJson(g, picked, rels);
        return ToolResult.success(
                "memory: open_nodes (" + picked.size() + "/" + wanted.size() + ")",
                json,
                Map.of("scope", scope, "requested", wanted.size(), "found", picked.size()));
    }

    private String graphToJson(Graph g, Iterable<Entity> entities,
                               Iterable<Relation> relations) throws JsonProcessingException {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode entArr = root.putArray("entities");
        for (Entity e : entities) {
            ObjectNode en = entArr.addObject();
            en.put("name", e.name);
            en.put("entityType", e.entityType);
            ArrayNode obs = en.putArray("observations");
            for (String o : e.observations) obs.add(o);
        }
        ArrayNode relArr = root.putArray("relations");
        for (Relation r : relations) {
            ObjectNode rn = relArr.addObject();
            rn.put("from", r.from);
            rn.put("to", r.to);
            rn.put("relationType", r.relationType);
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private Set<String> collectNames(JsonNode params) {
        Set<String> wanted = new LinkedHashSet<>();
        JsonNode namesNode = params.path("names");
        if (namesNode.isArray()) {
            for (JsonNode n : namesNode) {
                String v = n.asText("");
                if (!v.isEmpty()) wanted.add(v);
            }
        }
        String singleName = params.path("name").asText("");
        if (!singleName.isEmpty()) wanted.add(singleName);
        return wanted;
    }

    // ========================================================================
    // Static helpers for loading memory content at session start
    // ========================================================================

    /**
     * Loads all MEMORY.md content for injection into the system prompt.
     * Reads both global and project-level MEMORY.md files.
     */
    public static String loadMemoryForContext(Path workDir) {
        StringBuilder sb = new StringBuilder();

        Path globalFile = KompileHome.homeDirectory().toPath()
                .resolve(MEMORY_DIR).resolve(DEFAULT_FILE);
        String globalContent = readFileQuietly(globalFile, MAX_MAIN_MEMORY_LINES);
        if (globalContent != null) {
            sb.append("[Global memory - ~/.kompile/memory/MEMORY.md]\n");
            sb.append(globalContent);
            sb.append("\n\n");
        }

        Path projectFile = workDir.resolve(".kompile")
                .resolve(MEMORY_DIR).resolve(DEFAULT_FILE);
        String projectContent = readFileQuietly(projectFile, MAX_MAIN_MEMORY_LINES);
        if (projectContent != null) {
            sb.append("[Project memory - .kompile/memory/MEMORY.md]\n");
            sb.append(projectContent);
            sb.append("\n\n");
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Lists all memory files with their sizes for status display.
     */
    public static String getMemoryStatus(Path workDir) {
        StringBuilder sb = new StringBuilder();

        Path globalDir = KompileHome.homeDirectory().toPath().resolve(MEMORY_DIR);
        Path projectDir = workDir.resolve(".kompile").resolve(MEMORY_DIR);

        int globalCount = countFiles(globalDir);
        int projectCount = countFiles(projectDir);

        sb.append("  Persistent memory: ");
        if (globalCount == 0 && projectCount == 0) {
            sb.append("(none)\n");
        } else {
            sb.append(globalCount).append(" global, ").append(projectCount).append(" project files\n");
        }

        Path globalMem = globalDir.resolve(DEFAULT_FILE);
        Path projectMem = projectDir.resolve(DEFAULT_FILE);
        if (Files.exists(globalMem)) {
            sb.append("  Global MEMORY.md:  ").append(lineCount(globalMem)).append(" lines\n");
        }
        if (Files.exists(projectMem)) {
            sb.append("  Project MEMORY.md: ").append(lineCount(projectMem)).append(" lines\n");
        }

        return sb.toString();
    }

    // ========================================================================
    // Private helpers
    // ========================================================================

    private Path resolveMemoryDir(String scope, Path workDir) {
        if ("global".equalsIgnoreCase(scope)) {
            return KompileHome.homeDirectory().toPath().resolve(MEMORY_DIR);
        }
        return workDir.resolve(".kompile").resolve(MEMORY_DIR);
    }

    private String sanitizeFileName(String name) {
        Path fileName = Paths.get(name).getFileName();
        name = fileName != null ? fileName.toString() : name;
        if (!name.endsWith(".md")) {
            name = name + ".md";
        }
        return name;
    }

    private static String readFileQuietly(Path file, int maxLines) {
        if (!Files.exists(file)) return null;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) return null;
            String[] lines = content.split("\n");
            if (lines.length > maxLines) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < maxLines; i++) {
                    sb.append(lines[i]).append("\n");
                }
                sb.append("\n... (").append(lines.length - maxLines)
                        .append(" more lines, use memory tool to read full file)");
                return sb.toString();
            }
            return content;
        } catch (IOException e) {
            return null;
        }
    }

    private static int countFiles(Path dir) {
        if (!Files.exists(dir)) return 0;
        try (var files = Files.list(dir)) {
            return (int) files.filter(p -> !Files.isDirectory(p)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static int lineCount(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).split("\n").length;
        } catch (IOException e) {
            return 0;
        }
    }

    // ---- Frontmatter parsing (simple YAML subset) -------------------------

    private static class Frontmatter {
        String name = "";
        String description = "";
        String type = "";
        String body = "";
    }

    private Frontmatter parseFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) return null;
        int firstNewline = content.indexOf('\n');
        if (firstNewline < 0) return null;
        int endMarker = content.indexOf("\n---", firstNewline);
        if (endMarker < 0) return null;
        String fmBlock = content.substring(firstNewline + 1, endMarker);
        int bodyStart = endMarker + 4;
        while (bodyStart < content.length()
                && (content.charAt(bodyStart) == '\n' || content.charAt(bodyStart) == '\r')) {
            bodyStart++;
        }
        Frontmatter fm = new Frontmatter();
        for (String line : fmBlock.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();
            if (val.length() >= 2
                    && ((val.charAt(0) == '"' && val.charAt(val.length() - 1) == '"')
                    || (val.charAt(0) == '\'' && val.charAt(val.length() - 1) == '\''))) {
                val = val.substring(1, val.length() - 1);
            }
            switch (key) {
                case "name":
                    fm.name = val;
                    break;
                case "description":
                    fm.description = val;
                    break;
                case "type":
                    fm.type = val.toLowerCase();
                    break;
                default:
                    break;
            }
        }
        fm.body = content.substring(bodyStart);
        return fm;
    }

    private String yamlEscape(String v) {
        if (v == null) return "";
        if (v.contains(":") || v.contains("#") || v.contains("\n")
                || v.startsWith(" ") || v.endsWith(" ")
                || v.startsWith("\"") || v.startsWith("'")) {
            return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return v;
    }

    private String escapeMarkdown(String v) {
        return v.replace("[", "\\[").replace("]", "\\]");
    }

    private String slug(String name) {
        String s = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "memory";
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }
}
