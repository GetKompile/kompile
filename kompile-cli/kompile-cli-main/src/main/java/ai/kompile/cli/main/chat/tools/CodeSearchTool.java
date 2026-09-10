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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.tools.grounding.CodeGraphLearningRunner;
import ai.kompile.cli.main.codeindex.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.ConnectException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CLI tool for searching and indexing codebases via the kompile-app
 * code indexer backend. Supports searching for code entities (classes,
 * methods, functions, etc.), indexing new codebases, and getting
 * codebase statistics.
 * <p>
 * Uses the folder-local index unless an explicit server URL is supplied.
 * Explicit remote requests never fall back to a different local dataset.
 */
public class CodeSearchTool implements CliTool {

    // Actions unavailable on the remote code-index API.
    private static final Set<String> LOCAL_ALWAYS_ACTIONS = Set.of(
            "ranked_search", "blended_search", "signatures", "health", "routing");

    // Read actions preceded by a throttled incremental refresh of the local index
    // ('health'/'stats' intentionally unrefreshed — they report staleness).
    private static final Set<String> REFRESHABLE_ACTIONS = Set.of(
            "search", "ranked_search", "blended_search", "signatures", "routing");

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;
    private final boolean remoteConfigured;

    public CodeSearchTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        this.remoteConfigured = baseUrl != null && !baseUrl.isBlank();
        if (remoteConfigured) {
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
                "index, stats, entities. No explicit URL uses the folder-local index; " +
                "explicit remote requests never fall back locally. Analysis actions are local-only.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
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
        action.putArray("enum")
                .add("search").add("ranked_search").add("blended_search")
                .add("signatures").add("health").add("routing")
                .add("index").add("stats").add("entities");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Search query — name, signature fragment, or description keyword");

        ObjectNode rootPath = props.putObject("root_path");
        rootPath.put("type", "string");
        rootPath.put("description", "Absolute path to codebase root directory (for action='index')");

        ObjectNode projectId = props.putObject("project_id");
        projectId.put("type", "string");
        projectId.put("description", "Project identifier for the index (default: auto-resolved " +
                "from kompile.project.json, registration.json, or indexed roots containing the cwd)");

        ObjectNode autoRefresh = props.putObject("auto_refresh");
        autoRefresh.put("type", "boolean");
        autoRefresh.put("description", "Incrementally re-index changed files before local read " +
                "actions (default: true; throttled)");

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

        // Default root_path to current working directory if not specified
        String cwd = context.getWorkingDirectory().toAbsolutePath().toString();

        if (remoteConfigured && LOCAL_ALWAYS_ACTIONS.contains(action)) {
            return ToolResult.error("Action '" + action + "' is local-only and cannot analyze the " +
                    "explicitly configured remote dataset. Remove --url to use the folder-local index " +
                    "(a separate dataset), or use local_code_index explicitly.");
        }
        String projectId = resolveProjectId(action, params, context);

        if (!remoteConfigured) {
            String refreshNote = null;
            if (REFRESHABLE_ACTIONS.contains(action) && params.path("auto_refresh").asBoolean(true)) {
                refreshNote = BackgroundIndexService.getInstance()
                        .prepareForRead(new LocalCodeIndexer(), projectId);
            }
            ToolResult result = executeLocal(action, params, projectId, cwd, context);
            return withBackend(result, "folder-local", refreshNote);
        }

        try {
            if (!backend.isAvailable("/api/code-indexer/search")) {
                return ToolResult.error("The explicitly configured remote code index service is unavailable. " +
                        "No local fallback was attempted. Remove --url to use the separate folder-local index.");
            }
            ToolResult result = switch (action) {
                case "search" -> doSearch(params, projectId, cwd, context);
                case "index" -> doIndex(params, projectId, cwd, context);
                case "stats" -> doStats(projectId, cwd, context);
                case "entities" -> doEntities(params, projectId, cwd, context);
                default -> ToolResult.error("Unknown action: " + action +
                        ". Use 'search', 'ranked_search', 'blended_search', 'signatures', " +
                        "'health', 'routing', 'index', 'stats', or 'entities'.");
            };
            return withBackend(result, "remote", null);
        } catch (ConnectException e) {
            return ToolResult.error("Explicit remote code index connection failed; no local fallback: " + e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolResult.error("Explicit remote code search timed out; no local fallback. Try a more specific query.");
        } catch (Exception e) {
            return ToolResult.error("Explicit remote code search error; no local fallback: " + e.getMessage());
        }
    }

    private ToolResult withBackend(ToolResult result, String backendName, String refreshNote) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.getMetadata());
        metadata.put("backend", backendName);
        String output = "[backend: " + backendName + "]\n" + result.getOutput();
        if (refreshNote != null && !refreshNote.isBlank()) {
            output += "\n" + refreshNote;
        }
        return new ToolResult(result.getTitle(), output, metadata, result.isError());
    }

    /**
     * Resolve the effective project id. Search-type actions use
     * {@link ProjectIdResolver} (project manifest → registration → indexed root
     * → cwd name). Indexing and searching use the same resolution rules so the
     * selected backend cannot silently create or query a different bucket.
     */
    private String resolveProjectId(String action, JsonNode params, ToolContext context) {
        String raw = params.path("project_id").asText("");
        if ("index".equals(action)) {
            if (!raw.isEmpty()) return raw;
            String rootPath = params.path("root_path").asText("");
            Path dir = rootPath.isEmpty()
                    ? context.getWorkingDirectory().toAbsolutePath()
                    : Path.of(rootPath).toAbsolutePath();
            return ProjectIdResolver.resolve("", dir).projectId();
        }
        ProjectIdResolver.Resolution resolution =
                ProjectIdResolver.resolve(raw, context.getWorkingDirectory());
        return resolution.projectId();
    }

    private ToolResult doSearch(JsonNode params, String projectId, String cwd,
                                ToolContext context) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("query is required for search");

        String entityType = params.path("entity_type").asText("");
        int maxResults = params.path("max_results").asInt(10);

        StringBuilder path = new StringBuilder("/api/code-indexer/search?")
                .append("projectId=").append(urlEncode(projectId))
                .append("&query=").append(urlEncode(query))
                .append("&maxResults=").append(maxResults);
        if (!entityType.isEmpty()) {
            path.append("&type=").append(urlEncode(entityType));
        }

        HttpResponse<String> response = backend.get(path.toString(), Duration.ofSeconds(30));

        if (response.statusCode() != 200) {
            return ToolResult.error("Search failed (HTTP " + response.statusCode() + "): " +
                    response.body());
        }

        JsonNode results = objectMapper.readTree(response.body());
        return formatSearchResults(query, results);
    }

    private ToolResult doIndex(JsonNode params, String projectId, String cwd,
                               ToolContext context) throws Exception {
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
        sb.append("- **Folder-local graph**: not updated by the managed code-index endpoint; ")
                .append("use local_code_index action='index' when folder-local graph_search is required\n");
        if (result.path("errors").asInt() > 0) {
            sb.append("- **Errors**: ").append(result.path("errors").asInt()).append("\n");
        }

        return ToolResult.success("code_index: " + rootPath, sb.toString(),
                Map.of("projectId", projectId, "filesProcessed", result.path("filesProcessed").asInt()));
    }

    private ToolResult doStats(String projectId, String cwd, ToolContext context) throws Exception {
        HttpResponse<String> response = backend.get(
                "/api/code-indexer/statistics?projectId=" + urlEncode(projectId),
                Duration.ofSeconds(10));

        if (response.statusCode() != 200) {
            return ToolResult.error("Stats failed (HTTP " + response.statusCode() + "): " + response.body());
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

    private ToolResult doEntities(JsonNode params, String projectId, String cwd,
                                  ToolContext context) throws Exception {
        String filePath = params.path("file_path").asText("");
        String parentFqn = params.path("parent_fqn").asText("");

        StringBuilder apiPath = new StringBuilder("/api/code-indexer/entities?projectId=" + urlEncode(projectId));
        if (!filePath.isEmpty()) {
            apiPath.append("&file=").append(urlEncode(filePath));
        } else if (!parentFqn.isEmpty()) {
            apiPath.append("&parentFqn=").append(urlEncode(parentFqn));
        } else {
            return ToolResult.error("Provide file_path or parent_fqn for entities action");
        }

        HttpResponse<String> response = backend.get(apiPath.toString(), Duration.ofSeconds(30));

        if (response.statusCode() != 200) {
            return ToolResult.error("Entities failed (HTTP " + response.statusCode() + "): " + response.body());
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

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
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
                    LocalCodeKGraphPublisher.ProjectionResult projection =
                            LocalCodeKGraphPublisher.publish(Path.of(rootPath), projectId, null, null);
                    CodeGraphLearningRunner.ConfiguredResult learning =
                            new CodeGraphLearningRunner().runConfigured(
                                    Path.of(rootPath), projection.graphPath(),
                                    CodeGraphReasoningConfig.TRIGGER_BUILD);
                    StringBuilder sb = new StringBuilder();
                    sb.append("Codebase indexed locally\n\n");
                    sb.append("- **Project**: ").append(result.projectId()).append("\n");
                    sb.append("- **Root**: ").append(result.rootPath()).append("\n");
                    sb.append("- **Files processed**: ").append(result.filesProcessed()).append("\n");
                    sb.append("- **Entities found**: ").append(result.entitiesFound()).append("\n");
                    sb.append("- **Knowledge base**: ").append(projection.knowledgeBaseId()).append("\n");
                    sb.append("- **KGraph**: ").append(projection.graphPath()).append("\n");
                    sb.append("- **Code graph learning**: ").append(learning.status()).append("\n");
                    if (learning.error() != null) {
                        sb.append("  - Learning failed without invalidating the structural index: ")
                                .append(learning.error()).append("\n");
                    }
                    if (result.errors() > 0) sb.append("- **Errors**: ").append(result.errors()).append("\n");
                    sb.append("\nGraph search with: graph_search query='...' knowledgeBase='")
                            .append(projection.knowledgeBaseId()).append("' code_project_id='")
                            .append(projectId).append("'\n");
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("projectId", projectId);
                    metadata.put("filesProcessed", result.filesProcessed());
                    metadata.put("knowledgeBase", projection.knowledgeBaseId());
                    metadata.put("graphPath", projection.graphPath().toString());
                    metadata.put("learningStatus", learning.status());
                    if (learning.error() != null) metadata.put("learningError", learning.error());
                    yield ToolResult.success("code_index: " + rootPath, sb.toString(), metadata);
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
                case "entities" -> {
                    String filePath = params.path("file_path").asText("");
                    if (filePath.isEmpty()) {
                        String parentFqn = params.path("parent_fqn").asText("");
                        String detail = parentFqn.isEmpty()
                                ? "Provide file_path for local entities action"
                                : "Local entities lookup currently requires file_path, not parent_fqn";
                        yield ToolResult.error(detail);
                    }
                    int maxResults = params.path("max_results").asInt(10);
                    java.util.List<Map<String, Object>> entities =
                            localIndexer.entitiesForFile(projectId, filePath, maxResults);
                    yield formatSearchResults("entities", objectMapper.valueToTree(entities));
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
