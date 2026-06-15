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
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * CLI tool for searching and indexing codebases via the kompile-app
 * code indexer backend. Supports searching for code entities (classes,
 * methods, functions, etc.), indexing new codebases, and getting
 * codebase statistics.
 * <p>
 * Uses {@link KompileBackendClient} for auto-detection, reconnection,
 * and configurable timeouts. Falls back to local index when no backend
 * is available.
 */
public class CodeSearchTool implements CliTool {

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;

    public CodeSearchTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() { return "code_search"; }

    @Override
    public String description() {
        return "Search an indexed codebase for classes, methods, functions, interfaces, " +
                "and other code entities. Actions: search, ranked_search (multi-signal relevance " +
                "with intent detection and graph boost), blended_search (auto-detects query type " +
                "and blends spath, ranked, and signature strategies), signatures (token-compressed " +
                "file views), health (index quality score 0-100), routing (file complexity tiers), " +
                "index, stats, entities.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action to perform: 'search' (find code entities), " +
                "'ranked_search' (multi-signal relevance ranking), " +
                "'blended_search' (auto-detect query type and blend strategies), " +
                "'signatures' (token-compressed file views), " +
                "'health' (index quality score), 'routing' (file complexity tiers), " +
                "'index' (index a codebase directory), 'stats' (get codebase statistics), " +
                "'entities' (list entities in a file or children of a parent)");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Search query — name, signature fragment, or description keyword");

        ObjectNode rootPath = props.putObject("root_path");
        rootPath.put("type", "string");
        rootPath.put("description", "Absolute path to codebase root directory (for action='index')");

        ObjectNode projectId = props.putObject("project_id");
        projectId.put("type", "string");
        projectId.put("description", "Project identifier for the index (default: 'default')");

        ObjectNode entityType = props.putObject("entity_type");
        entityType.put("type", "string");
        entityType.put("description", "Filter by entity type: CLASS, METHOD, FUNCTION, INTERFACE, " +
                "FILE, IMPORT, FIELD, ENUM, RECORD, PACKAGE");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "File path for action='entities'");

        ObjectNode parentFqn = props.putObject("parent_fqn");
        parentFqn.put("type", "string");
        parentFqn.put("description", "Parent fully-qualified name for action='entities'");

        ObjectNode maxResults = props.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results to return (default: 10)");

        ObjectNode filePaths = props.putObject("file_paths");
        filePaths.put("type", "string");
        filePaths.put("description", "Comma-separated file paths (relative to project root). " +
                "Used with action='signatures'.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "code_search"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Search/index codebase");

        String action = params.path("action").asText("search");
        String projectId = params.path("project_id").asText("default");

        // Default root_path to current working directory if not specified
        String cwd = context.getWorkingDirectory().toAbsolutePath().toString();

        if (!backend.isAvailable()) {
            // No backend reachable — fall back to local index
            return executeLocal(action, params, projectId, cwd, context);
        }

        try {
            return switch (action) {
                case "search" -> doSearch(params, projectId);
                case "ranked_search" -> doRankedSearchLocal(params, projectId, cwd);
                case "blended_search" -> doBlendedSearchLocal(params, projectId, cwd);
                case "signatures" -> doSignaturesLocal(params, projectId, cwd);
                case "health" -> doHealthLocal(params, projectId, cwd);
                case "routing" -> doRoutingLocal(params, projectId, cwd);
                case "index" -> doIndex(params, projectId, cwd);
                case "stats" -> doStats(projectId);
                case "entities" -> doEntities(params, projectId);
                default -> ToolResult.error("Unknown action: " + action +
                        ". Use 'search', 'ranked_search', 'blended_search', 'signatures', " +
                        "'health', 'routing', 'index', 'stats', or 'entities'.");
            };
        } catch (ConnectException e) {
            // Backend went down — KompileBackendClient already tried reconnection, fall back to local
            return executeLocal(action, params, projectId, cwd, context);
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolResult.error("Code search timed out. Try a more specific query.");
        } catch (Exception e) {
            return ToolResult.error("Code search error: " + e.getMessage());
        }
    }

    private ToolResult doSearch(JsonNode params, String projectId) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("query is required for search");

        String entityType = params.path("entity_type").asText("");
        int maxResults = params.path("max_results").asInt(10);

        StringBuilder path = new StringBuilder("/api/code-indexer/search?")
                .append("projectId=").append(projectId)
                .append("&query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                .append("&maxResults=").append(maxResults);
        if (!entityType.isEmpty()) {
            path.append("&type=").append(entityType);
        }

        HttpResponse<String> response = backend.get(path.toString(), Duration.ofSeconds(30));

        if (response.statusCode() != 200) {
            return ToolResult.error("Search failed (HTTP " + response.statusCode() + "): " +
                    response.body());
        }

        JsonNode results = objectMapper.readTree(response.body());
        return formatSearchResults(query, results);
    }

    private ToolResult doIndex(JsonNode params, String projectId, String cwd) throws Exception {
        String rootPath = params.path("root_path").asText("");
        if (rootPath.isEmpty()) rootPath = cwd;

        ObjectNode request = objectMapper.createObjectNode();
        request.put("projectId", projectId);
        request.put("rootPath", rootPath);

        HttpResponse<String> response = backend.post(
                "/api/code-indexer/index",
                objectMapper.writeValueAsString(request),
                Duration.ofSeconds(300));

        if (response.statusCode() != 200) {
            return ToolResult.error("Indexing failed (HTTP " + response.statusCode() + "): " +
                    response.body());
        }

        JsonNode result = objectMapper.readTree(response.body());
        StringBuilder sb = new StringBuilder();
        sb.append("Codebase indexed successfully\n\n");
        sb.append("- **Project**: ").append(result.path("projectId").asText()).append("\n");
        sb.append("- **Root**: ").append(result.path("rootPath").asText()).append("\n");
        sb.append("- **Files processed**: ").append(result.path("filesProcessed").asInt()).append("\n");
        sb.append("- **Entities found**: ").append(result.path("entitiesFound").asInt()).append("\n");
        sb.append("- **Relations created**: ").append(result.path("relationsCreated").asInt()).append("\n");
        if (result.path("errors").asInt() > 0) {
            sb.append("- **Errors**: ").append(result.path("errors").asInt()).append("\n");
        }

        return ToolResult.success("code_index: " + rootPath, sb.toString(),
                Map.of("projectId", projectId, "filesProcessed", result.path("filesProcessed").asInt()));
    }

    private ToolResult doStats(String projectId) throws Exception {
        HttpResponse<String> response = backend.get(
                "/api/code-indexer/statistics?projectId=" + projectId,
                Duration.ofSeconds(10));

        if (response.statusCode() != 200) {
            return ToolResult.error("Stats failed: " + response.body());
        }

        JsonNode result = objectMapper.readTree(response.body());
        StringBuilder sb = new StringBuilder();
        sb.append("Codebase statistics for project: ").append(projectId).append("\n\n");
        sb.append("- **Total entities**: ").append(result.path("totalEntities").asLong()).append("\n");

        JsonNode byType = result.path("byType");
        if (byType.isObject()) {
            sb.append("\nBy type:\n");
            byType.fields().forEachRemaining(e ->
                    sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue().asLong()).append("\n"));
        }

        return ToolResult.success("code_stats: " + projectId, sb.toString());
    }

    private ToolResult doEntities(JsonNode params, String projectId) throws Exception {
        String filePath = params.path("file_path").asText("");
        String parentFqn = params.path("parent_fqn").asText("");

        StringBuilder apiPath = new StringBuilder("/api/code-indexer/entities?projectId=" + projectId);
        if (!filePath.isEmpty()) {
            apiPath.append("&file=").append(java.net.URLEncoder.encode(filePath, "UTF-8"));
        } else if (!parentFqn.isEmpty()) {
            apiPath.append("&parentFqn=").append(java.net.URLEncoder.encode(parentFqn, "UTF-8"));
        } else {
            return ToolResult.error("Provide file_path or parent_fqn for entities action");
        }

        HttpResponse<String> response = backend.get(apiPath.toString(), Duration.ofSeconds(30));

        if (response.statusCode() != 200) {
            return ToolResult.error("Entities failed: " + response.body());
        }

        JsonNode results = objectMapper.readTree(response.body());
        return formatSearchResults("entities", results);
    }

    private ToolResult formatSearchResults(String query, JsonNode results) {
        if (!results.isArray() || results.isEmpty()) {
            return ToolResult.success("No code entities found for: " + query);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Code search results for: \"").append(query).append("\" (")
                .append(results.size()).append(" results)\n\n");

        int i = 0;
        for (JsonNode entity : results) {
            i++;
            String type = entity.path("entityType").asText(entity.path("type").asText("unknown")).toLowerCase();
            String name = entity.path("name").asText("unnamed");
            String fqn = entity.path("fullyQualifiedName").asText(entity.path("fqn").asText(""));
            String file = entity.path("filePath").asText(entity.path("file").asText(""));
            int startLine = entity.path("startLine").asInt(0);
            String sig = entity.path("signature").asText("");
            String doc = entity.path("docComment").asText("");

            sb.append(i).append(". [").append(type).append("] **").append(name).append("**");
            if (!sig.isEmpty()) sb.append(" — `").append(sig).append("`");
            sb.append("\n");
            sb.append("   ").append(file);
            if (startLine > 0) sb.append(":").append(startLine);
            sb.append("\n");
            if (!fqn.isEmpty() && !fqn.equals(name)) {
                sb.append("   FQN: ").append(fqn).append("\n");
            }
            if (!doc.isEmpty()) {
                String truncated = doc.length() > 150 ? doc.substring(0, 150) + "..." : doc;
                sb.append("   Doc: ").append(truncated.replaceAll("\\n", " ")).append("\n");
            }
            sb.append("\n");
        }

        return ToolResult.success("code_search: " + query, sb.toString(),
                Map.of("query", query, "resultCount", results.size()));
    }

    /**
     * Execute against the local code index (no server required).
     */
    private ToolResult executeLocal(String action, JsonNode params, String projectId, String cwd, ToolContext context) {
        try {
            ai.kompile.cli.main.codeindex.LocalCodeIndexer localIndexer =
                    new ai.kompile.cli.main.codeindex.LocalCodeIndexer();

            return switch (action) {
                case "index" -> {
                    String rootPath = params.path("root_path").asText("");
                    if (rootPath.isEmpty()) rootPath = cwd;
                    ai.kompile.cli.main.codeindex.LocalCodeIndexer.IndexResult result =
                            localIndexer.index(java.nio.file.Path.of(rootPath), projectId,
                                    null, null, ProgressPrintStream.from(context));
                    StringBuilder sb = new StringBuilder();
                    sb.append("Codebase indexed locally\n\n");
                    sb.append("- **Project**: ").append(result.projectId()).append("\n");
                    sb.append("- **Root**: ").append(result.rootPath()).append("\n");
                    sb.append("- **Files processed**: ").append(result.filesProcessed()).append("\n");
                    sb.append("- **Entities found**: ").append(result.entitiesFound()).append("\n");
                    if (result.errors() > 0) sb.append("- **Errors**: ").append(result.errors()).append("\n");
                    yield ToolResult.success("code_index: " + rootPath, sb.toString(),
                            Map.of("projectId", projectId, "filesProcessed", result.filesProcessed()));
                }
                case "search" -> {
                    String query = params.path("query").asText("");
                    if (query.isEmpty()) yield ToolResult.error("query is required for search");
                    String entityType = params.path("entity_type").asText("");
                    int maxResults = params.path("max_results").asInt(10);
                    java.util.List<Map<String, Object>> results =
                            localIndexer.search(projectId, query,
                                    entityType.isEmpty() ? null : entityType, maxResults);
                    if (results.isEmpty()) {
                        yield ToolResult.success("No results found for: " + query +
                                " (project: " + projectId + "). Try 'kompile code-index' to index first.");
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
                    yield ToolResult.success("code_stats: " + projectId, sb.toString());
                }
                case "ranked_search" -> doRankedSearchLocal(params, projectId, cwd);
                case "blended_search" -> doBlendedSearchLocal(params, projectId, cwd);
                case "signatures" -> doSignaturesLocal(params, projectId, cwd);
                case "health" -> doHealthLocal(params, projectId, cwd);
                case "routing" -> doRoutingLocal(params, projectId, cwd);
                default -> ToolResult.error("Unknown action: " + action +
                        ". Use 'search', 'ranked_search', 'blended_search', 'signatures', " +
                        "'health', 'routing', 'index', 'stats', or 'entities'.");
            };
        } catch (Exception e) {
            return ToolResult.error("Local code index error: " + e.getMessage());
        }
    }

    /**
     * Multi-signal ranked search using the local index.
     */
    private ToolResult doRankedSearchLocal(JsonNode params, String projectId, String cwd) {
        try {
            String query = params.path("query").asText("");
            if (query.isEmpty()) return ToolResult.error("query is required for ranked_search");

            int topK = params.path("max_results").asInt(10);
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);

            if (!Files.exists(indexDir.resolve("index.db"))) {
                return ToolResult.error("No index found for project '" + projectId +
                        "'. Run action='index' first.");
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
                        "'. Run action='index' first.");
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
                            "totalSignatures", project.totalSignatures()));
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
                        "'. Run action='index' first.");
            }

            IndexHealthScorer.HealthScore hs = IndexHealthScorer.score(projectId, indexDir);
            return ToolResult.success("health: " + projectId,
                    IndexHealthScorer.formatHealth(hs),
                    Map.of("score", hs.score(), "grade", hs.grade()));
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
                        "'. Run action='index' first.");
            }

            ComplexityClassifier.ProjectClassification pc =
                    ComplexityClassifier.classifyProject(projectId, indexDir);
            return ToolResult.success("routing: " + projectId,
                    ComplexityClassifier.formatClassification(pc),
                    Map.of("fast", pc.fastCount(),
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

            int topK = params.path("max_results").asInt(10);
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
            if (!Files.exists(indexDir.resolve("index.db"))) {
                return ToolResult.error("No index found for project '" + projectId +
                        "'. Run action='index' first.");
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
