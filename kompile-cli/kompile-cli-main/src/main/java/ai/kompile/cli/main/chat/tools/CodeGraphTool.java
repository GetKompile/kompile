/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.codeindex.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.ConnectException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * CLI tool for building, searching, and navigating the code knowledge graph.
 * Wraps the /api/code-indexer/graph/* REST endpoints.
 * <p>
 * Uses {@link KompileBackendClient} for auto-detection, reconnection,
 * and configurable timeouts. Falls back to local index when no backend
 * is available.
 */
public class CodeGraphTool implements CliTool {

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;

    public CodeGraphTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() { return "code_graph"; }

    @Override
    public String description() {
        return "Build and navigate a code knowledge graph. Actions: build, search, symbol, file, " +
                "impact (BFS blast radius — who is affected if a file changes), " +
                "ranked_search (multi-signal relevance ranking with intent detection and graph boost), " +
                "signatures (token-compressed file views — 70-95% reduction), " +
                "health (index quality score 0-100 with grade), " +
                "routing (classify files into fast/balanced/powerful complexity tiers), " +
                "stats, connectivity, add_directory, remove_directory, list_directories.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: 'build', 'search', 'symbol', 'file', 'impact', " +
                "'ranked_search', 'signatures', 'health', 'routing', 'stats', 'connectivity', " +
                "'add_directory', 'remove_directory', 'list_directories'");

        ObjectNode dirPath = props.putObject("directory_path");
        dirPath.put("type", "string");
        dirPath.put("description", "Absolute path to directory (for action='build')");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Search query (for action='search')");

        ObjectNode fqn = props.putObject("fqn");
        fqn.put("type", "string");
        fqn.put("description", "Fully-qualified name of symbol (for action='symbol')");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "File path (for action='file')");

        ObjectNode projectId = props.putObject("project_id");
        projectId.put("type", "string");
        projectId.put("description", "Project identifier (default: 'default')");

        ObjectNode depth = props.putObject("depth");
        depth.put("type", "integer");
        depth.put("description", "Graph traversal depth (default: 2)");

        ObjectNode maxResults = props.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results to return (default: 20)");

        ObjectNode displayName = props.putObject("display_name");
        displayName.put("type", "string");
        displayName.put("description", "Display name for a directory (for add_directory)");

        ObjectNode includePatterns = props.putObject("include_patterns");
        includePatterns.put("type", "string");
        includePatterns.put("description", "Comma-separated include glob patterns, e.g. '*.java,*.py' (for add_directory)");

        ObjectNode excludePatterns = props.putObject("exclude_patterns");
        excludePatterns.put("type", "string");
        excludePatterns.put("description", "Comma-separated exclude glob patterns, e.g. '*.test.js,node_modules/**' (for add_directory)");

        ObjectNode dirDescription = props.putObject("description");
        dirDescription.put("type", "string");
        dirDescription.put("description", "Human-readable description of the directory contents (for add_directory)");

        ObjectNode tags = props.putObject("tags");
        tags.put("type", "string");
        tags.put("description", "Comma-separated tags for categorization, e.g. 'backend,java' (for add_directory)");

        ObjectNode filePaths = props.putObject("file_paths");
        filePaths.put("type", "string");
        filePaths.put("description", "Comma-separated file paths (relative to project root). " +
                "Used with action='impact' and 'signatures'.");

        ObjectNode topK = props.putObject("top_k");
        topK.put("type", "integer");
        topK.put("description", "Number of top results (default: 10). Used with action='ranked_search'.");

        ObjectNode maxDepthParam = props.putObject("max_depth");
        maxDepthParam.put("type", "integer");
        maxDepthParam.put("description", "Max BFS depth for impact analysis (default: 0 = unlimited).");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "code_graph"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Build/search code graph");

        String action = params.path("action").asText("build");
        String projectId = params.path("project_id").asText("default");

        // Default directory_path to current working directory if not specified
        String cwd = context.getWorkingDirectory().toAbsolutePath().toString();

        if (!backend.isAvailable()) {
            // No backend reachable — fall back to local index
            return executeLocal(action, params, projectId, cwd, context);
        }

        try {
            return switch (action) {
                case "build" -> doBuild(params, projectId, cwd);
                case "search" -> doSearch(params, projectId);
                case "symbol" -> doSymbol(params, projectId);
                case "file" -> doFile(params, projectId);
                case "impact" -> doImpactLocal(params, projectId, cwd);
                case "ranked_search" -> doRankedSearchLocal(params, projectId, cwd);
                case "blended_search" -> doBlendedSearchLocal(params, projectId, cwd);
                case "signatures" -> doSignaturesLocal(params, projectId, cwd);
                case "health" -> doHealthLocal(params, projectId, cwd);
                case "routing" -> doRoutingLocal(params, projectId, cwd);
                case "stats" -> doStats(projectId);
                case "connectivity" -> doConnectivity(projectId);
                case "add_directory" -> doAddDirectory(params, projectId, cwd);
                case "remove_directory" -> doRemoveDirectory(params, projectId);
                case "list_directories" -> doListDirectories(projectId);
                default -> ToolResult.error("Unknown action: " + action +
                        ". Use 'build', 'search', 'symbol', 'file', 'impact', 'ranked_search', " +
                        "'signatures', 'health', 'routing', 'stats', 'connectivity', " +
                        "'add_directory', 'remove_directory', or 'list_directories'.");
            };
        } catch (ConnectException e) {
            // Backend went down — KompileBackendClient already tried reconnection, fall back to local
            return executeLocal(action, params, projectId, cwd, context);
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolResult.error("Code graph request timed out. Try a more specific query.");
        } catch (Exception e) {
            return ToolResult.error("Code graph error: " + e.getMessage());
        }
    }

    private ToolResult doBuild(JsonNode params, String projectId, String cwd) throws Exception {
        String dirPath = params.path("directory_path").asText("");
        if (dirPath.isEmpty()) dirPath = cwd;

        ObjectNode request = objectMapper.createObjectNode();
        request.put("projectId", projectId);
        request.put("directoryPath", dirPath);

        HttpResponse<String> response = backend.post(
                "/api/code-indexer/graph/build",
                objectMapper.writeValueAsString(request),
                Duration.ofSeconds(600));

        if (response.statusCode() != 200) {
            return ToolResult.error("Graph build failed (HTTP " + response.statusCode() + "): " + response.body());
        }

        JsonNode result = objectMapper.readTree(response.body());
        StringBuilder sb = new StringBuilder();
        sb.append("Code graph built successfully\n\n");
        sb.append("- **Project**: ").append(result.path("projectId").asText()).append("\n");
        sb.append("- **Root**: ").append(result.path("rootPath").asText()).append("\n");
        sb.append("- **Files processed**: ").append(result.path("filesProcessed").asInt()).append("\n");
        sb.append("- **Entities found**: ").append(result.path("entitiesFound").asInt()).append("\n");
        sb.append("- **Relations created**: ").append(result.path("relationsCreated").asInt()).append("\n");
        if (result.has("connectivityEdgesAdded")) {
            sb.append("- **Connectivity edges added**: ").append(result.path("connectivityEdgesAdded").asInt()).append("\n");
        }
        if (result.path("errors").asInt() > 0) {
            sb.append("- **Errors**: ").append(result.path("errors").asInt()).append("\n");
        }

        return ToolResult.success("graph_build: " + dirPath, sb.toString());
    }

    private ToolResult doSearch(JsonNode params, String projectId) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("query is required for search");
        int maxResults = params.path("max_results").asInt(20);

        String apiPath = "/api/code-indexer/graph/search?projectId=" + enc(projectId) +
                "&query=" + enc(query) + "&maxResults=" + maxResults;

        HttpResponse<String> response = backend.get(apiPath, Duration.ofSeconds(30));

        if (response.statusCode() != 200) {
            return ToolResult.error("Graph search failed: " + response.body());
        }

        JsonNode result = objectMapper.readTree(response.body());
        StringBuilder sb = new StringBuilder();
        sb.append("Graph search: \"").append(query).append("\"\n\n");

        JsonNode codeEntities = result.path("codeEntities");
        if (codeEntities.isArray() && !codeEntities.isEmpty()) {
            sb.append("### Code Entities\n");
            int i = 0;
            for (JsonNode e : codeEntities) {
                i++;
                sb.append(i).append(". [").append(e.path("entityType").asText().toLowerCase())
                        .append("] **").append(e.path("name").asText()).append("**");
                String sig = e.path("signature").asText("");
                if (!sig.isEmpty()) sb.append(" — `").append(sig).append("`");
                sb.append("\n   ").append(e.path("filePath").asText(""));
                int line = e.path("startLine").asInt(0);
                if (line > 0) sb.append(":").append(line);
                sb.append("\n\n");
            }
        }

        JsonNode graphNodes = result.path("graphNodes");
        if (graphNodes.isArray() && !graphNodes.isEmpty()) {
            sb.append("### Graph Nodes\n");
            int i = 0;
            for (JsonNode n : graphNodes) {
                i++;
                sb.append(i).append(". [").append(n.path("nodeType").asText().toLowerCase())
                        .append("] **").append(n.path("title").asText()).append("**\n");
                String desc = n.path("description").asText("");
                if (!desc.isEmpty()) sb.append("   ").append(desc).append("\n");
                sb.append("\n");
            }
        }

        return ToolResult.success("graph_search: " + query, sb.toString());
    }

    private ToolResult doSymbol(JsonNode params, String projectId) throws Exception {
        String fqn = params.path("fqn").asText("");
        if (fqn.isEmpty()) return ToolResult.error("fqn is required for symbol action");
        int depth = params.path("depth").asInt(2);

        String apiPath = "/api/code-indexer/graph/symbol?projectId=" + enc(projectId) +
                "&fqn=" + enc(fqn) + "&depth=" + depth;

        HttpResponse<String> response = backend.get(apiPath, Duration.ofSeconds(30));

        if (response.statusCode() != 200) {
            return ToolResult.error("Symbol graph failed: " + response.body());
        }

        JsonNode result = objectMapper.readTree(response.body());
        return formatVisualization("Symbol: " + fqn, result);
    }

    private ToolResult doFile(JsonNode params, String projectId) throws Exception {
        String filePath = params.path("file_path").asText("");
        if (filePath.isEmpty()) return ToolResult.error("file_path is required for file action");

        String apiPath = "/api/code-indexer/graph/file?projectId=" + enc(projectId) +
                "&filePath=" + enc(filePath);

        HttpResponse<String> response = backend.get(apiPath, Duration.ofSeconds(30));

        if (response.statusCode() != 200) {
            return ToolResult.error("File graph failed: " + response.body());
        }

        JsonNode result = objectMapper.readTree(response.body());
        return formatVisualization("File: " + filePath, result);
    }

    private ToolResult doStats(String projectId) throws Exception {
        HttpResponse<String> response = backend.get(
                "/api/code-indexer/graph/statistics?projectId=" + enc(projectId),
                Duration.ofSeconds(10));

        if (response.statusCode() != 200) {
            return ToolResult.error("Graph stats failed: " + response.body());
        }

        JsonNode result = objectMapper.readTree(response.body());
        StringBuilder sb = new StringBuilder();
        sb.append("Code Graph Statistics (project: ").append(projectId).append(")\n\n");

        JsonNode codeStats = result.path("codeIndexer");
        if (codeStats.isObject()) {
            sb.append("### Code Entities\n");
            sb.append("- Total: ").append(codeStats.path("totalEntities").asLong()).append("\n");
            JsonNode byType = codeStats.path("byType");
            if (byType.isObject()) {
                byType.fields().forEachRemaining(e ->
                        sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue().asLong()).append("\n"));
            }
            sb.append("\n");
        }

        JsonNode graphStats = result.path("knowledgeGraph");
        if (graphStats.isObject()) {
            sb.append("### Knowledge Graph\n");
            sb.append("- Nodes: ").append(graphStats.path("totalNodes").asLong()).append("\n");
            sb.append("- Edges: ").append(graphStats.path("totalEdges").asLong()).append("\n");
            sb.append("  - Hierarchical: ").append(graphStats.path("edges_hierarchical").asLong()).append("\n");
            sb.append("  - Cross-source: ").append(graphStats.path("edges_cross_source").asLong()).append("\n");
            sb.append("  - User-defined: ").append(graphStats.path("edges_user_defined").asLong()).append("\n");
            sb.append("  - Shared-entity: ").append(graphStats.path("edges_shared_entity").asLong()).append("\n");
            sb.append("\n");
        }

        JsonNode dirs = result.path("directories");
        if (dirs.isArray() && !dirs.isEmpty()) {
            sb.append("### Indexed Directories\n");
            for (JsonNode d : dirs) {
                sb.append("- ").append(d.path("path").asText())
                        .append(" (").append(d.path("filesIndexed").asInt()).append(" files, ")
                        .append(d.path("entitiesFound").asInt()).append(" entities)\n");
            }
        }

        return ToolResult.success("graph_stats: " + projectId, sb.toString());
    }

    private ToolResult doConnectivity(String projectId) throws Exception {
        HttpResponse<String> response = backend.post(
                "/api/code-indexer/graph/ensure-connectivity?projectId=" + enc(projectId),
                null,
                Duration.ofSeconds(120));

        if (response.statusCode() != 200) {
            return ToolResult.error("Connectivity check failed: " + response.body());
        }

        JsonNode result = objectMapper.readTree(response.body());
        int added = result.path("edgesAdded").asInt(0);
        return ToolResult.success("connectivity: " + projectId,
                "Connectivity check complete. Edges added: " + added);
    }

    private ToolResult doAddDirectory(JsonNode params, String projectId, String cwd) throws Exception {
        String dirPath = params.path("directory_path").asText("");
        if (dirPath.isEmpty()) dirPath = cwd;

        ObjectNode request = objectMapper.createObjectNode();
        request.put("projectId", projectId);
        request.put("path", dirPath);

        String displayName = params.path("display_name").asText("");
        if (!displayName.isEmpty()) request.put("displayName", displayName);

        String includePatterns = params.path("include_patterns").asText("");
        if (!includePatterns.isEmpty()) request.put("includePatterns", includePatterns);

        String excludePatterns = params.path("exclude_patterns").asText("");
        if (!excludePatterns.isEmpty()) request.put("excludePatterns", excludePatterns);

        String description = params.path("description").asText("");
        if (!description.isEmpty()) request.put("description", description);

        String tags = params.path("tags").asText("");
        if (!tags.isEmpty()) request.put("tags", tags);

        HttpResponse<String> response = backend.post(
                "/api/code-indexer/directories",
                objectMapper.writeValueAsString(request),
                Duration.ofSeconds(30));

        if (response.statusCode() != 200) {
            return ToolResult.error("Add directory failed (HTTP " + response.statusCode() + "): " + response.body());
        }

        JsonNode result = objectMapper.readTree(response.body());
        StringBuilder sb = new StringBuilder();
        sb.append("Directory added for indexing\n\n");
        sb.append("- **Path**: ").append(result.path("absolutePath").asText()).append("\n");
        sb.append("- **Display Name**: ").append(result.path("displayName").asText()).append("\n");
        sb.append("- **Status**: ").append(result.path("status").asText()).append("\n");
        String desc = result.path("description").asText("");
        if (!desc.isEmpty()) sb.append("- **Description**: ").append(desc).append("\n");
        String t = result.path("tags").asText("");
        if (!t.isEmpty()) sb.append("- **Tags**: ").append(t).append("\n");
        String incl = result.path("includePatterns").asText("");
        if (!incl.isEmpty()) sb.append("- **Include**: ").append(incl).append("\n");
        String excl = result.path("excludePatterns").asText("");
        if (!excl.isEmpty()) sb.append("- **Exclude**: ").append(excl).append("\n");

        return ToolResult.success("add_directory: " + dirPath, sb.toString());
    }

    private ToolResult doRemoveDirectory(JsonNode params, String projectId) throws Exception {
        String dirPath = params.path("directory_path").asText("");
        if (dirPath.isEmpty()) return ToolResult.error("directory_path is required for remove_directory");

        HttpResponse<String> response = backend.delete(
                "/api/code-indexer/directories?projectId=" + enc(projectId) + "&path=" + enc(dirPath),
                Duration.ofSeconds(30));

        if (response.statusCode() != 200) {
            return ToolResult.error("Remove directory failed (HTTP " + response.statusCode() + "): " + response.body());
        }

        return ToolResult.success("remove_directory: " + dirPath, "Directory removed: " + dirPath);
    }

    private ToolResult doListDirectories(String projectId) throws Exception {
        HttpResponse<String> response = backend.get(
                "/api/code-indexer/directories?projectId=" + enc(projectId),
                Duration.ofSeconds(10));

        if (response.statusCode() != 200) {
            return ToolResult.error("List directories failed: " + response.body());
        }

        JsonNode dirs = objectMapper.readTree(response.body());
        if (!dirs.isArray() || dirs.isEmpty()) {
            return ToolResult.success("list_directories: " + projectId,
                    "No directories tracked for project \"" + projectId + "\".");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Tracked directories for project \"").append(projectId).append("\" (")
                .append(dirs.size()).append(")\n\n");

        for (JsonNode d : dirs) {
            String name = d.path("displayName").asText(d.path("absolutePath").asText("?"));
            String path = d.path("absolutePath").asText("");
            String status = d.path("status").asText("UNKNOWN");
            int files = d.path("filesIndexed").asInt(0);
            int entities = d.path("entitiesFound").asInt(0);
            int relations = d.path("relationsCreated").asInt(0);

            sb.append("### ").append(name).append("\n");
            sb.append("- **Path**: ").append(path).append("\n");
            sb.append("- **Status**: ").append(status).append("\n");
            sb.append("- **Files**: ").append(files)
                    .append(" | **Entities**: ").append(entities)
                    .append(" | **Relations**: ").append(relations).append("\n");

            String lastIndexed = d.path("lastIndexedAt").asText("");
            if (!lastIndexed.isEmpty()) sb.append("- **Last indexed**: ").append(lastIndexed).append("\n");

            String desc = d.path("description").asText("");
            if (!desc.isEmpty()) sb.append("- **Description**: ").append(desc).append("\n");

            String tags = d.path("tags").asText("");
            if (!tags.isEmpty()) sb.append("- **Tags**: ").append(tags).append("\n");

            String incl = d.path("includePatterns").asText("");
            if (!incl.isEmpty()) sb.append("- **Include**: ").append(incl).append("\n");

            String excl = d.path("excludePatterns").asText("");
            if (!excl.isEmpty()) sb.append("- **Exclude**: ").append(excl).append("\n");

            sb.append("\n");
        }

        return ToolResult.success("list_directories: " + projectId, sb.toString());
    }

    private ToolResult formatVisualization(String title, JsonNode data) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n");

        JsonNode nodes = data.path("nodes");
        JsonNode edges = data.path("edges");
        JsonNode metadata = data.path("metadata");

        if (metadata.isObject()) {
            sb.append("Nodes: ").append(metadata.path("nodeCount").asInt())
                    .append(", Edges: ").append(metadata.path("edgeCount").asInt()).append("\n\n");
        }

        if (nodes.isArray() && !nodes.isEmpty()) {
            sb.append("### Nodes\n");
            for (JsonNode n : nodes) {
                sb.append("- [").append(n.path("type").asText().toLowerCase()).append("] ")
                        .append(n.path("title").asText());
                String desc = n.path("description").asText("");
                if (!desc.isEmpty() && desc.length() < 100) {
                    sb.append(" — ").append(desc);
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (edges.isArray() && !edges.isEmpty()) {
            sb.append("### Edges\n");
            for (JsonNode e : edges) {
                sb.append("- ").append(e.path("source").asText())
                        .append(" —[").append(e.path("type").asText().toLowerCase());
                String label = e.path("label").asText("");
                if (!label.isEmpty()) sb.append(": ").append(label);
                sb.append("]→ ").append(e.path("target").asText()).append("\n");
            }
        }

        return ToolResult.success(title, sb.toString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Execute against the local code index (no server required).
     * Supports build, search, and stats actions locally.
     */
    private ToolResult executeLocal(String action, JsonNode params, String projectId, String cwd, ToolContext context) {
        try {
            LocalCodeIndexer localIndexer =
                    new LocalCodeIndexer();

            return switch (action) {
                case "build", "add_directory" -> {
                    String dirPath = params.path("directory_path").asText("");
                    if (dirPath.isEmpty()) dirPath = cwd;
                    LocalCodeIndexer.IndexResult result =
                            localIndexer.index(Path.of(dirPath), projectId,
                                    params.path("include_patterns").asText(null),
                                    params.path("exclude_patterns").asText(null),
                                    ProgressPrintStream.from(context));
                    StringBuilder sb = new StringBuilder();
                    sb.append("Codebase indexed locally with graph\n\n");
                    sb.append("- **Project**: ").append(result.projectId()).append("\n");
                    sb.append("- **Root**: ").append(result.rootPath()).append("\n");
                    sb.append("- **Files processed**: ").append(result.filesProcessed()).append("\n");
                    sb.append("- **Entities found**: ").append(result.entitiesFound()).append("\n");
                    if (result.errors() > 0) sb.append("- **Errors**: ").append(result.errors()).append("\n");

                    // Report relation counts from the graph
                    try (IndexDatabase gdb =
                             IndexDatabase.open(
                                     LocalCodeIndexer.getIndexDir(projectId))) {
                        Map<String, Object> gStats = gdb.getGraphStats();
                        sb.append("- **Relations**: ").append(gStats.getOrDefault("totalRelations", 0)).append("\n");
                        @SuppressWarnings("unchecked")
                        Map<String, Integer> byType = (Map<String, Integer>) gStats.get("relationsByType");
                        if (byType != null && !byType.isEmpty()) {
                            byType.forEach((type, count) ->
                                    sb.append("  - ").append(type).append(": ").append(count).append("\n"));
                        }
                    } catch (Exception ignored) {}

                    if (!result.languageCounts().isEmpty()) {
                        sb.append("\nLanguages:\n");
                        result.languageCounts().forEach((lang, count) ->
                                sb.append("  - ").append(lang).append(": ").append(count).append("\n"));
                    }
                    yield ToolResult.success("code_index: " + dirPath, sb.toString(),
                            Map.of("projectId", projectId, "filesProcessed", result.filesProcessed()));
                }
                case "search" -> {
                    String query = params.path("query").asText("");
                    if (query.isEmpty()) yield ToolResult.error("query is required");
                    String entityType = params.path("entity_type").asText("");
                    int maxResults = params.path("max_results").asInt(10);
                    List<Map<String, Object>> results =
                            localIndexer.search(projectId, query,
                                    entityType.isEmpty() ? null : entityType, maxResults);
                    if (results.isEmpty()) {
                        yield ToolResult.success("No results for: " + query);
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("Local search results for \"").append(query).append("\" (")
                            .append(results.size()).append(" results)\n\n");
                    int idx = 0;
                    for (Map<String, Object> entity : results) {
                        idx++;
                        sb.append(idx).append(". [").append(entity.getOrDefault("entityType", "?"))
                                .append("] **").append(entity.getOrDefault("name", "?")).append("**");
                        Object sig = entity.get("signature");
                        if (sig != null) sb.append(" — `").append(sig).append("`");
                        sb.append("\n   ").append(entity.getOrDefault("filePath", ""));
                        Object startLine = entity.get("startLine");
                        if (startLine != null) sb.append(":").append(startLine);
                        sb.append("\n\n");
                    }
                    yield ToolResult.success("code_search: " + query, sb.toString(),
                            Map.of("query", query, "resultCount", results.size()));
                }
                case "stats" -> {
                    Map<String, Object> stats = localIndexer.getStats(projectId);
                    StringBuilder sb = new StringBuilder();
                    sb.append("Local index stats for: ").append(projectId).append("\n\n");
                    sb.append("- **Root**: ").append(stats.getOrDefault("rootPath", "?")).append("\n");
                    sb.append("- **Files**: ").append(stats.getOrDefault("filesProcessed", "?")).append("\n");
                    sb.append("- **Entities**: ").append(stats.getOrDefault("entitiesFound", "?")).append("\n");
                    sb.append("- **Indexed at**: ").append(stats.getOrDefault("indexedAt", "?")).append("\n");

                    // Graph stats
                    @SuppressWarnings("unchecked")
                    Map<String, Object> graphStats = (Map<String, Object>) stats.get("graphStats");
                    if (graphStats != null) {
                        sb.append("\n### Graph Relations\n");
                        sb.append("- **Total relations**: ").append(graphStats.getOrDefault("totalRelations", 0)).append("\n");
                        @SuppressWarnings("unchecked")
                        Map<String, Integer> byType = (Map<String, Integer>) graphStats.get("relationsByType");
                        if (byType != null && !byType.isEmpty()) {
                            byType.forEach((type, count) ->
                                    sb.append("  - ").append(type).append(": ").append(count).append("\n"));
                        }
                    }

                    yield ToolResult.success("code_stats: " + projectId, sb.toString());
                }
                case "list_directories" -> {
                    List<Map<String, Object>> projects = localIndexer.listProjects();
                    if (projects.isEmpty()) {
                        yield ToolResult.success("No locally indexed projects. Use action='build' to create one.");
                    }
                    StringBuilder sb = new StringBuilder("Locally indexed projects:\n\n");
                    for (Map<String, Object> meta : projects) {
                        sb.append("- **").append(meta.getOrDefault("projectId", "?")).append("**")
                                .append(" — ").append(meta.getOrDefault("rootPath", "?"))
                                .append(" (").append(meta.getOrDefault("entitiesFound", "?")).append(" entities)\n");
                    }
                    yield ToolResult.success("local_projects", sb.toString());
                }
                case "symbol" -> {
                    String fqn = params.path("fqn").asText("");
                    if (fqn.isEmpty()) yield ToolResult.error("fqn is required for symbol action");
                    int depth = params.path("depth").asInt(2);

                    Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
                    if (!Files.exists(indexDir.resolve("index.db"))) {
                        yield ToolResult.error("No index found for project '" + projectId +
                                "'. Run action='build' first.");
                    }

                    try (IndexDatabase db =
                             IndexDatabase.open(indexDir)) {
                        Map<String, Object> graph = db.getSymbolGraph(fqn, depth);

                        @SuppressWarnings("unchecked")
                        Map<String, Object> entity = (Map<String, Object>) graph.get("entity");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> nodes =
                                (List<Map<String, Object>>) graph.get("nodes");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> edges =
                                (List<Map<String, Object>>) graph.get("edges");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> metadata = (Map<String, Object>) graph.get("metadata");

                        StringBuilder sb = new StringBuilder();
                        sb.append("Symbol graph: ").append(fqn).append("\n\n");

                        if (entity != null) {
                            sb.append("### Focal Entity\n");
                            sb.append("- **Name**: ").append(entity.getOrDefault("name", "?")).append("\n");
                            sb.append("- **Type**: ").append(entity.getOrDefault("entityType", "?")).append("\n");
                            sb.append("- **FQN**: ").append(entity.getOrDefault("fullyQualifiedName", "?")).append("\n");
                            sb.append("- **File**: ").append(entity.getOrDefault("filePath", "?"));
                            Object sl = entity.get("startLine");
                            if (sl != null) sb.append(":").append(sl);
                            sb.append("\n");
                            Object sig = entity.get("signature");
                            if (sig != null) sb.append("- **Signature**: `").append(sig).append("`\n");
                            sb.append("\n");
                        } else {
                            sb.append("Entity not found for FQN: ").append(fqn).append("\n\n");
                        }

                        if (metadata != null) {
                            sb.append("Nodes: ").append(metadata.getOrDefault("nodeCount", 0))
                                    .append(", Edges: ").append(metadata.getOrDefault("edgeCount", 0)).append("\n\n");
                        }

                        if (nodes != null && !nodes.isEmpty()) {
                            sb.append("### Connected Nodes\n");
                            int count = 0;
                            for (Map<String, Object> n : nodes) {
                                if (++count > 50) {
                                    sb.append("... and ").append(nodes.size() - 50).append(" more\n");
                                    break;
                                }
                                sb.append("- [").append(n.getOrDefault("entityType", "?"))
                                        .append("] **").append(n.getOrDefault("name", "?")).append("**");
                                Object nSig = n.get("signature");
                                if (nSig != null) sb.append(" — `").append(nSig).append("`");
                                sb.append("\n  ").append(n.getOrDefault("filePath", ""));
                                Object nLine = n.get("startLine");
                                if (nLine != null) sb.append(":").append(nLine);
                                sb.append("\n");
                            }
                            sb.append("\n");
                        }

                        if (edges != null && !edges.isEmpty()) {
                            sb.append("### Edges\n");
                            int count = 0;
                            for (Map<String, Object> e : edges) {
                                if (++count > 50) {
                                    sb.append("... and ").append(edges.size() - 50).append(" more\n");
                                    break;
                                }
                                sb.append("- ").append(e.getOrDefault("sourceFqn", "?"))
                                        .append(" —[").append(e.getOrDefault("relationType", "?"))
                                        .append("]→ ").append(e.getOrDefault("targetFqn",
                                                e.getOrDefault("targetName", "?"))).append("\n");
                            }
                        }

                        yield ToolResult.success("symbol_graph: " + fqn, sb.toString());
                    } catch (java.sql.SQLException e) {
                        yield ToolResult.error("Local symbol graph error: " + e.getMessage());
                    }
                }
                case "file" -> {
                    String filePath = params.path("file_path").asText("");
                    if (filePath.isEmpty()) yield ToolResult.error("file_path is required for file action");

                    Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
                    if (!Files.exists(indexDir.resolve("index.db"))) {
                        yield ToolResult.error("No index found for project '" + projectId +
                                "'. Run action='build' first.");
                    }

                    try (IndexDatabase db =
                             IndexDatabase.open(indexDir)) {
                        Map<String, Object> graph = db.getFileGraph(filePath);

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> entities =
                                (List<Map<String, Object>>) graph.get("entities");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> outRels =
                                (List<Map<String, Object>>) graph.get("outgoingRelations");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> inRels =
                                (List<Map<String, Object>>) graph.get("incomingRelations");

                        StringBuilder sb = new StringBuilder();
                        sb.append("File graph: ").append(filePath).append("\n\n");
                        sb.append("Entities: ").append(graph.getOrDefault("entityCount", 0)).append("\n\n");

                        if (entities != null && !entities.isEmpty()) {
                            sb.append("### Entities\n");
                            for (Map<String, Object> ent : entities) {
                                String type = (String) ent.getOrDefault("entityType", "?");
                                if ("FILE".equals(type) || "IMPORT".equals(type) || "PACKAGE".equals(type)) continue;
                                sb.append("- [").append(type).append("] **")
                                        .append(ent.getOrDefault("name", "?")).append("**");
                                Object sig = ent.get("signature");
                                if (sig != null) sb.append(" — `").append(sig).append("`");
                                Object sl = ent.get("startLine");
                                if (sl != null) sb.append(" (line ").append(sl).append(")");
                                sb.append("\n");
                            }
                            sb.append("\n");
                        }

                        if (outRels != null && !outRels.isEmpty()) {
                            sb.append("### Outgoing Relations (").append(outRels.size()).append(")\n");
                            int count = 0;
                            for (Map<String, Object> r : outRels) {
                                String type = (String) r.getOrDefault("relationType", "?");
                                if ("CONTAINS".equals(type) || "IMPORTS".equals(type)) continue;
                                if (++count > 30) {
                                    sb.append("... and more\n");
                                    break;
                                }
                                sb.append("- ").append(r.getOrDefault("sourceFqn", "?"))
                                        .append(" —[").append(type)
                                        .append("]→ ").append(r.getOrDefault("targetFqn",
                                                r.getOrDefault("targetName", "?"))).append("\n");
                            }
                            sb.append("\n");
                        }

                        if (inRels != null && !inRels.isEmpty()) {
                            sb.append("### Incoming Relations (").append(inRels.size()).append(")\n");
                            int count = 0;
                            for (Map<String, Object> r : inRels) {
                                if (++count > 30) {
                                    sb.append("... and more\n");
                                    break;
                                }
                                sb.append("- ").append(r.getOrDefault("sourceFqn", "?"))
                                        .append(" —[").append(r.getOrDefault("relationType", "?"))
                                        .append("]→ ").append(r.getOrDefault("targetFqn",
                                                r.getOrDefault("targetName", "?"))).append("\n");
                            }
                        }

                        yield ToolResult.success("file_graph: " + filePath, sb.toString());
                    } catch (java.sql.SQLException e) {
                        yield ToolResult.error("Local file graph error: " + e.getMessage());
                    }
                }
                case "connectivity" -> {
                    Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
                    if (!Files.exists(indexDir.resolve("index.db"))) {
                        yield ToolResult.error("No index found for project '" + projectId +
                                "'. Run action='build' first.");
                    }

                    try (IndexDatabase db =
                             IndexDatabase.open(indexDir)) {
                        db.beginTransaction();
                        int resolved = db.ensureConnectivity();
                        db.commit();
                        yield ToolResult.success("connectivity: " + projectId,
                                "Local connectivity pass complete. " + resolved + " target FQNs resolved.");
                    } catch (java.sql.SQLException e) {
                        yield ToolResult.error("Local connectivity error: " + e.getMessage());
                    }
                }
                case "impact" -> doImpactLocal(params, projectId, cwd);
                case "ranked_search" -> doRankedSearchLocal(params, projectId, cwd);
                case "blended_search" -> doBlendedSearchLocal(params, projectId, cwd);
                case "signatures" -> doSignaturesLocal(params, projectId, cwd);
                case "health" -> doHealthLocal(params, projectId, cwd);
                case "routing" -> doRoutingLocal(params, projectId, cwd);
                case "remove_directory" -> ToolResult.error(
                        "Action 'remove_directory' requires a running kompile-app instance.");
                default -> ToolResult.error("Unknown action: '" + action + "'. " +
                        "Supported local actions: build, search, ranked_search, blended_search, signatures, " +
                        "impact, health, routing, stats, list_directories, symbol, file, connectivity.");
            };
        } catch (Exception e) {
            return ToolResult.error("Local code index error: " + e.getMessage());
        }
    }

    /**
     * BFS blast-radius analysis over the reverse dependency graph.
     */
    private ToolResult doImpactLocal(JsonNode params, String projectId, String cwd) {
        try {
            String filePaths = params.path("file_paths").asText("");
            if (filePaths.isEmpty()) filePaths = params.path("file_path").asText("");
            if (filePaths.isEmpty()) filePaths = params.path("query").asText("");
            if (filePaths.isEmpty()) {
                return ToolResult.error("'file_path' is required for impact analysis. " +
                        "Provide comma-separated relative paths of changed files.");
            }

            int maxDepth = params.path("max_depth").asInt(params.path("depth").asInt(0));
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
            if (!Files.exists(indexDir.resolve("index.db"))) {
                return ToolResult.error("No index found for project '" + projectId +
                        "'. Run action='build' first.");
            }

            List<String> paths = Arrays.stream(filePaths.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            if (paths.size() == 1) {
                ImpactAnalyzer.FileImpact impact =
                        ImpactAnalyzer.analyzeFile(paths.get(0), indexDir, maxDepth);
                return ToolResult.success("impact: " + paths.get(0),
                        ImpactAnalyzer.formatFileImpact(impact),
                        Map.of("changedFile", impact.changedFile(),
                                "totalImpact", impact.totalImpact(),
                                "directDependents", impact.directDependents().size(),
                                "affectedTests", impact.affectedTests().size()));
            }

            ImpactAnalyzer.ImpactReport report =
                    ImpactAnalyzer.analyzeFiles(paths, indexDir, maxDepth);
            return ToolResult.success("impact: " + paths.size() + " files",
                    ImpactAnalyzer.formatReport(report),
                    Map.of("changedFiles", paths.size(),
                            "totalUniqueImpact", report.totalUniqueImpact()));
        } catch (Exception e) {
            return ToolResult.error("Impact analysis error: " + e.getMessage());
        }
    }

    /**
     * Multi-signal ranked search using the local index.
     */
    private ToolResult doRankedSearchLocal(JsonNode params, String projectId, String cwd) {
        try {
            String query = params.path("query").asText("");
            if (query.isEmpty()) return ToolResult.error("'query' is required for ranked_search");

            int topK = params.path("top_k").asInt(params.path("max_results").asInt(10));
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);

            if (!Files.exists(indexDir.resolve("index.db"))) {
                return ToolResult.error("No index found for project '" + projectId +
                        "'. Run action='build' first.");
            }

            CodeRelevanceRanker.RankedResults results =
                    CodeRelevanceRanker.rankedSearch(projectId, query, indexDir,
                            Path.of(cwd), topK);

            return ToolResult.success("ranked_search: " + query,
                    CodeRelevanceRanker.formatResults(results),
                    Map.of("query", query, "intent", results.intent().name(),
                            "resultCount", results.results().size()));
        } catch (Exception e) {
            return ToolResult.error("Ranked search error: " + e.getMessage());
        }
    }

    /**
     * Extract token-compressed signatures from indexed files.
     */
    private ToolResult doSignaturesLocal(JsonNode params, String projectId, String cwd) {
        try {
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
            if (!Files.exists(indexDir.resolve("index.db"))) {
                return ToolResult.error("No index found for project '" + projectId +
                        "'. Run action='build' first.");
            }

            String filePaths = params.path("file_paths").asText(
                    params.path("file_path").asText(""));

            if (!filePaths.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String fp : filePaths.split(",")) {
                    String relPath = fp.trim();
                    SignatureExtractor.FileSignatures fs =
                            SignatureExtractor.extractFile(projectId, relPath, indexDir, Path.of(cwd));
                    if (fs != null) {
                        sb.append(SignatureExtractor.formatFileContext(fs)).append("\n");
                    } else {
                        sb.append("No signatures found for: ").append(relPath).append("\n");
                    }
                }
                return ToolResult.success("signatures", sb.toString());
            }

            SignatureExtractor.ProjectSignatures project =
                    SignatureExtractor.extractProject(projectId, indexDir, Path.of(cwd));
            String formatted = SignatureExtractor.formatAsContext(project);

            return ToolResult.success("signatures: " + projectId, formatted,
                    Map.of("projectId", projectId,
                            "totalFiles", project.totalFiles(),
                            "totalSignatures", project.totalSignatures(),
                            "reductionPercent", String.format("%.1f", project.overallReductionPercent())));
        } catch (Exception e) {
            return ToolResult.error("Signature extraction error: " + e.getMessage());
        }
    }

    /**
     * Score index quality 0-100 with grade.
     */
    private ToolResult doHealthLocal(JsonNode params, String projectId, String cwd) {
        try {
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
            if (!Files.isDirectory(indexDir)) {
                return ToolResult.error("No index found for project '" + projectId +
                        "'. Run action='build' first.");
            }

            IndexHealthScorer.HealthScore hs = IndexHealthScorer.score(projectId, indexDir);
            String formatted = IndexHealthScorer.formatHealth(hs);

            return ToolResult.success("health: " + projectId, formatted,
                    Map.of("score", hs.score(), "grade", hs.grade(),
                            "totalFiles", hs.totalFiles(),
                            "totalEntities", hs.totalEntities()));
        } catch (Exception e) {
            return ToolResult.error("Health scoring error: " + e.getMessage());
        }
    }

    /**
     * Classify files into fast/balanced/powerful complexity tiers.
     */
    private ToolResult doRoutingLocal(JsonNode params, String projectId, String cwd) {
        try {
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
            if (!Files.exists(indexDir.resolve("index.db"))) {
                return ToolResult.error("No index found for project '" + projectId +
                        "'. Run action='build' first.");
            }

            ComplexityClassifier.ProjectClassification pc =
                    ComplexityClassifier.classifyProject(projectId, indexDir);
            String formatted = ComplexityClassifier.formatClassification(pc);

            return ToolResult.success("routing: " + projectId, formatted,
                    Map.of("projectId", projectId,
                            "fast", pc.fastCount(),
                            "balanced", pc.balancedCount(),
                            "powerful", pc.powerfulCount()));
        } catch (Exception e) {
            return ToolResult.error("Complexity routing error: " + e.getMessage());
        }
    }

    /**
     * Blended search: auto-detects query type and routes to optimal strategy combination.
     */
    private ToolResult doBlendedSearchLocal(JsonNode params, String projectId, String cwd) {
        try {
            String query = params.path("query").asText("");
            if (query.isEmpty()) return ToolResult.error("'query' is required for blended_search");

            int topK = params.path("top_k").asInt(params.path("max_results").asInt(10));
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
            if (!Files.exists(indexDir.resolve("index.db"))) {
                return ToolResult.error("No index found for project '" + projectId +
                        "'. Run action='build' first.");
            }

            BlendedCodeSearch.BlendedResult results =
                    BlendedCodeSearch.search(projectId, query, indexDir, Path.of(cwd), topK);
            String formatted = BlendedCodeSearch.formatResults(results);

            return ToolResult.success("blended_search: " + query, formatted,
                    Map.of("query", query, "queryType", results.queryType().name(),
                            "intent", results.intent().name(),
                            "resultCount", results.results().size(),
                            "hasCompressedContext", results.compressedContext() != null));
        } catch (Exception e) {
            return ToolResult.error("Blended search error: " + e.getMessage());
        }
    }
}
