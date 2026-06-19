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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Unified code intelligence tool that aggregates {@link CodeSearchTool},
 * {@link LocalCodeIndexTool}, and {@link CodeGraphTool} behind a single
 * MCP entry point.
 *
 * <p>Routing logic:
 * <ul>
 *   <li>Graph-specific actions (build, symbol, file, connectivity, add_directory,
 *       remove_directory, list_directories) → {@link CodeGraphTool}</li>
 *   <li>Local-only actions (find, replace, usages, spath, pagerank, clones,
 *       cochanges, unused_exports, list) → {@link LocalCodeIndexTool}</li>
 *   <li>Common actions (search, ranked_search, blended_search, signatures,
 *       impact, health, routing, index, stats, entities) → tries {@link CodeSearchTool}
 *       first (which itself falls back to local), then {@link LocalCodeIndexTool}</li>
 * </ul>
 *
 * <p>The underlying tool implementations are preserved unchanged — this class
 * is a pure dispatcher that eliminates the need for callers to know which
 * backend to target.
 */
public class UnifiedCodeSearchTool implements CliTool {

    private final CodeSearchTool codeSearchTool;
    private final LocalCodeIndexTool localCodeIndexTool;
    private final CodeGraphTool codeGraphTool;

    public UnifiedCodeSearchTool(String baseUrl, ObjectMapper objectMapper) {
        this.codeSearchTool = new CodeSearchTool(baseUrl, objectMapper);
        this.localCodeIndexTool = new LocalCodeIndexTool();
        this.codeGraphTool = new CodeGraphTool(baseUrl, objectMapper);
    }

    @Override
    public String id() { return "code_search"; }

    @Override
    public String description() {
        return "Unified code intelligence tool. Search, index, and navigate codebases with "
                + "structural understanding. Actions: "
                + "search (find code entities by name/keyword), "
                + "ranked_search (multi-signal relevance with intent detection), "
                + "blended_search (auto-detect query type and blend strategies), "
                + "index (index a codebase directory), "
                + "signatures (token-compressed file views — 70-95% reduction), "
                + "impact (BFS blast radius — what breaks if a file changes), "
                + "health (index quality score 0-100), "
                + "routing (file complexity tiers: fast/balanced/powerful), "
                + "spath (semantic path query — address code by meaning), "
                + "symbol (graph traversal from a fully-qualified name), "
                + "file (graph view of a single file's entities and relations), "
                + "find (text/regex search in source files), "
                + "replace (find and replace across files), "
                + "usages (find all usages of a symbol), "
                + "pagerank (file importance scoring), "
                + "clones (detect near-duplicate functions via MinHash), "
                + "cochanges (git co-change coupling analysis), "
                + "unused_exports (dead code detection), "
                + "stats (index statistics), "
                + "entities (list entities in a file), "
                + "build (build/rebuild code graph), "
                + "connectivity (resolve cross-file references), "
                + "add_directory/remove_directory/list_directories (manage indexed dirs), "
                + "list (list all indexed projects).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action to perform: search, ranked_search, blended_search, "
                + "index, signatures, impact, health, routing, spath, symbol, file, "
                + "find, replace, usages, pagerank, clones, cochanges, unused_exports, "
                + "stats, entities, build, connectivity, "
                + "add_directory, remove_directory, list_directories, list");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Search query — name, signature fragment, keyword, or semantic path. "
                + "For spath: use 'pkg.Class.method', 'pkg.*', 'pkg.**' syntax.");

        ObjectNode rootPath = props.putObject("root_path");
        rootPath.put("type", "string");
        rootPath.put("description", "Absolute path to codebase root (for index/build). Defaults to cwd.");

        ObjectNode directoryPath = props.putObject("directory_path");
        directoryPath.put("type", "string");
        directoryPath.put("description", "Directory path (alias for root_path, also used for add/remove directory).");

        ObjectNode projectId = props.putObject("project_id");
        projectId.put("type", "string");
        projectId.put("description", "Project identifier (default: directory name or 'default')");

        ObjectNode entityType = props.putObject("entity_type");
        entityType.put("type", "string");
        entityType.put("description", "Filter by entity type: CLASS, METHOD, FUNCTION, INTERFACE, "
                + "FILE, IMPORT, FIELD, ENUM, RECORD, PACKAGE");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "File path for file-level operations (entities, file graph, find)");

        ObjectNode filePaths = props.putObject("file_paths");
        filePaths.put("type", "string");
        filePaths.put("description", "Comma-separated file paths for multi-file operations "
                + "(impact, signatures, clones)");

        ObjectNode fqn = props.putObject("fqn");
        fqn.put("type", "string");
        fqn.put("description", "Fully-qualified name for symbol graph traversal");

        ObjectNode maxResults = props.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results to return (default: 10-20 depending on action)");

        ObjectNode topK = props.putObject("top_k");
        topK.put("type", "integer");
        topK.put("description", "Alias for max_results (used with ranked_search, pagerank)");

        ObjectNode depth = props.putObject("depth");
        depth.put("type", "integer");
        depth.put("description", "Graph traversal depth (default: 2). Used with symbol action.");

        ObjectNode maxDepth = props.putObject("max_depth");
        maxDepth.put("type", "integer");
        maxDepth.put("description", "Max BFS depth for impact analysis (default: 0 = unlimited)");

        ObjectNode parentFqn = props.putObject("parent_fqn");
        parentFqn.put("type", "string");
        parentFqn.put("description", "Parent FQN for entities action");

        ObjectNode includePatterns = props.putObject("include_patterns");
        includePatterns.put("type", "string");
        includePatterns.put("description", "Comma-separated include glob patterns (e.g. '*.java,*.py')");

        ObjectNode excludePatterns = props.putObject("exclude_patterns");
        excludePatterns.put("type", "string");
        excludePatterns.put("description", "Comma-separated exclude glob patterns (e.g. '*Test.java')");

        ObjectNode forceReindex = props.putObject("force_reindex");
        forceReindex.put("type", "boolean");
        forceReindex.put("description", "Force full re-index, ignoring cached fingerprints");

        ObjectNode regex = props.putObject("regex");
        regex.put("type", "boolean");
        regex.put("description", "Treat query as regex (for find/replace)");

        ObjectNode caseSensitive = props.putObject("case_sensitive");
        caseSensitive.put("type", "boolean");
        caseSensitive.put("description", "Case-sensitive search (default: true). For find/replace.");

        ObjectNode wholeWord = props.putObject("whole_word");
        wholeWord.put("type", "boolean");
        wholeWord.put("description", "Match whole words only. For find/replace/usages.");

        ObjectNode filePattern = props.putObject("file_pattern");
        filePattern.put("type", "string");
        filePattern.put("description", "Glob pattern to filter files (e.g. '*.java'). For find/replace.");

        ObjectNode contextLines = props.putObject("context_lines");
        contextLines.put("type", "integer");
        contextLines.put("description", "Lines of context around matches (default: 2). For find.");

        ObjectNode replacement = props.putObject("replacement");
        replacement.put("type", "string");
        replacement.put("description", "Replacement text. Required for replace action.");

        ObjectNode dryRun = props.putObject("dry_run");
        dryRun.put("type", "boolean");
        dryRun.put("description", "Preview changes without modifying files (default: true). For replace.");

        ObjectNode symbolName = props.putObject("symbol_name");
        symbolName.put("type", "string");
        symbolName.put("description", "Symbol name for usages action");

        ObjectNode displayName = props.putObject("display_name");
        displayName.put("type", "string");
        displayName.put("description", "Display name for a directory (for add_directory)");

        ObjectNode dirDescription = props.putObject("description");
        dirDescription.put("type", "string");
        dirDescription.put("description", "Description of directory contents (for add_directory)");

        ObjectNode tags = props.putObject("tags");
        tags.put("type", "string");
        tags.put("description", "Comma-separated tags (e.g. 'backend,java') for add_directory");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "code_search"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("search");

        // Route to the appropriate underlying tool based on action type
        return switch (action) {
            // Graph-specific actions → CodeGraphTool
            case "build", "symbol", "file", "connectivity",
                 "add_directory", "remove_directory", "list_directories" ->
                    codeGraphTool.execute(params, context);

            // Local-only actions → LocalCodeIndexTool
            case "find", "replace", "usages", "spath",
                 "pagerank", "clones", "cochanges", "unused_exports", "list" ->
                    localCodeIndexTool.execute(params, context);

            // Common actions: CodeSearchTool handles with its own local fallback
            case "search", "ranked_search", "blended_search", "signatures",
                 "impact", "health", "routing", "index", "stats", "entities" ->
                    codeSearchTool.execute(params, context);

            default -> ToolResult.error("Unknown action: " + action + ". Available actions: "
                    + "search, ranked_search, blended_search, index, signatures, impact, health, "
                    + "routing, spath, symbol, file, find, replace, usages, pagerank, clones, "
                    + "cochanges, unused_exports, stats, entities, build, connectivity, "
                    + "add_directory, remove_directory, list_directories, list");
        };
    }
}
