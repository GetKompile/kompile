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
import ai.kompile.cli.main.codeindex.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP-compatible CLI tool for local code indexing. Works entirely offline —
 * no kompile-app instance required. Indexes source files in a directory,
 * stores entities as JSON under {@code ~/.kompile/code-index/<project>/},
 * and supports searching the local index.
 *
 * <p>This tool is exposed to passthrough agents (Claude, Codex, Gemini, etc.)
 * via the embedded MCP stdio server, giving them code understanding capabilities
 * without any server dependency.</p>
 */
public class LocalCodeIndexTool implements CliTool {

    // ── DB caching ─────────────────────────────────────────────────────────
    // Cache IndexDatabase per project_id across calls to avoid open/close overhead.
    private static final ConcurrentHashMap<String, IndexDatabase> DB_CACHE = new ConcurrentHashMap<>();

    /**
     * Get or open a cached IndexDatabase for the given project.
     */
    static IndexDatabase getCachedDb(String projectId) throws java.sql.SQLException {
        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
        if (!Files.exists(indexDir.resolve("index.db"))) return null;
        return DB_CACHE.computeIfAbsent(projectId, k -> {
            try {
                return IndexDatabase.open(indexDir);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Close all cached database connections. Called on shutdown.
     */
    public static void closeAll() {
        DB_CACHE.values().forEach(IndexDatabase::close);
        DB_CACHE.clear();
    }

    // ── Token budget ───────────────────────────────────────────────────────
    private static final int DEFAULT_MAX_TOKENS = 0; // 0 = unlimited

    /**
     * Truncate output text to a token budget (1 token ≈ 4 chars).
     * When truncated, appends a summary line.
     */
    static String truncateToTokenBudget(String text, int maxTokens) {
        if (maxTokens <= 0 || text == null) return text;
        int maxChars = maxTokens * 4;
        if (text.length() <= maxChars) return text;
        // Find a line boundary near the budget
        int cutoff = text.lastIndexOf('\n', maxChars);
        if (cutoff < maxChars / 2) cutoff = maxChars;
        String truncated = text.substring(0, cutoff);
        int remaining = text.length() - cutoff;
        int remainingLines = (int) text.substring(cutoff).chars().filter(c -> c == '\n').count();
        return truncated + "\n\n... (" + remainingLines + " more lines, ~"
                + (remaining / 4) + " tokens truncated. Use max_tokens=0 for full output)";
    }

    @Override
    public String id() { return "local_code_index"; }

    @Override
    public String description() {
        return "Index and search a codebase locally. Extracts code entities (classes, methods, functions) " +
                "with incremental indexing. Actions: index, search, ranked_search (multi-signal relevance), " +
                "blended_search (auto-selects strategy: spath for symbols, ranked for names, compressed for broad), " +
                "signatures (token-compressed file views), impact (blast radius analysis), " +
                "health (index quality score), routing (file complexity tiers), " +
                "spath (semantic path query), find, replace, usages, " +
                "pagerank (compute/query file importance via PageRank), " +
                "clones (detect near-duplicate functions via MinHash), " +
                "cochanges (analyze git co-change coupling), " +
                "unused_exports (find dead code — unused public symbols), " +
                "stats, list, " +
                "callers (find all callers of a method/function), " +
                "implementors (find all implementations of an interface), " +
                "trace (BFS call chain traversal — outgoing or incoming), " +
                "spring_resolve (resolve Spring DI bean wiring for an interface), " +
                "debug_trace (composite: search + callers + trace + impact in ONE call — best for debugging), " +
                "changed_context (git-aware: entities in uncommitted changed files + impact analysis), " +
                "modules (parse Maven pom.xml to show module dependency graph).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: 'index' (index a directory), 'search' (find entities), " +
                "'ranked_search' (multi-signal relevance ranking with intent detection and graph boost), " +
                "'signatures' (extract compact signatures per file — 70-95% token reduction), " +
                "'impact' (BFS blast radius — who is affected if this file changes), " +
                "'health' (index quality score 0-100), " +
                "'routing' (classify files into fast/balanced/powerful complexity tiers), " +
                "'spath' (semantic path query — address code by meaning not filesystem), " +
                "'find' (find text/regex in files), 'replace' (find and replace in files), " +
                "'usages' (find all usages of a symbol), " +
                "'pagerank' (compute PageRank file importance scores — top_files or file rank), " +
                "'clones' (detect near-duplicate functions via MinHash — finds structural clones), " +
                "'cochanges' (analyze git history for files that change together), " +
                "'unused_exports' (detect dead code — exports never referenced externally), " +
                "'stats' (show statistics), 'list' (list indexed projects), " +
                "'callers' (find all callers of a method/function), " +
                "'implementors' (find all implementations of an interface), " +
                "'trace' (BFS call chain traversal — outgoing or incoming), " +
                "'spring_resolve' (resolve Spring DI bean wiring for an interface)");

        ObjectNode directory = props.putObject("directory");
        directory.put("type", "string");
        directory.put("description", "Directory to index (default: current working directory). Used with action='index'.");

        ObjectNode projectId = props.putObject("project_id");
        projectId.put("type", "string");
        projectId.put("description", "Project identifier (default: directory name)");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Search query — name, signature, or keyword. Used with action='search'. " +
                "For action='spath', use semantic path syntax: 'pkg.Class.method', 'pkg.*', 'pkg.**', " +
                "'pkg.*Handler', 'pkg[File.java].method', 'pkg.Class/imports'.");

        ObjectNode entityType = props.putObject("entity_type");
        entityType.put("type", "string");
        entityType.put("description", "Filter by entity type: CLASS, METHOD, FUNCTION, INTERFACE, " +
                "FILE, IMPORT, ENUM, RECORD, PACKAGE. Used with action='search'.");

        ObjectNode maxResults = props.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results to return (default: 20)");

        ObjectNode include = props.putObject("include_patterns");
        include.put("type", "string");
        include.put("description", "Comma-separated include glob patterns (e.g. '*.java,*.py'). Used with action='index'.");

        ObjectNode exclude = props.putObject("exclude_patterns");
        exclude.put("type", "string");
        exclude.put("description", "Comma-separated exclude glob patterns (e.g. '*Test.java'). Used with action='index'.");

        ObjectNode forceReindex = props.putObject("force_reindex");
        forceReindex.put("type", "boolean");
        forceReindex.put("description", "Force full re-index, ignoring cached fingerprints. Used with action='index'.");

        ObjectNode regex = props.putObject("regex");
        regex.put("type", "boolean");
        regex.put("description", "Treat query as regex pattern. Used with action='find' and 'replace'.");

        ObjectNode caseSensitive = props.putObject("case_sensitive");
        caseSensitive.put("type", "boolean");
        caseSensitive.put("description", "Case-sensitive search (default: true). Used with action='find' and 'replace'.");

        ObjectNode wholeWord = props.putObject("whole_word");
        wholeWord.put("type", "boolean");
        wholeWord.put("description", "Match whole words only. Used with action='find', 'replace', and 'usages'.");

        ObjectNode filePattern = props.putObject("file_pattern");
        filePattern.put("type", "string");
        filePattern.put("description", "Glob pattern to filter files (e.g. '*.java'). Used with action='find' and 'replace'.");

        ObjectNode contextLines = props.putObject("context_lines");
        contextLines.put("type", "integer");
        contextLines.put("description", "Lines of context around each match (default: 2). Used with action='find'.");

        ObjectNode replacement = props.putObject("replacement");
        replacement.put("type", "string");
        replacement.put("description", "Replacement text. Required with action='replace'.");

        ObjectNode dryRun = props.putObject("dry_run");
        dryRun.put("type", "boolean");
        dryRun.put("description", "Preview changes without modifying files (default: true). Used with action='replace'.");

        ObjectNode symbolName = props.putObject("symbol_name");
        symbolName.put("type", "string");
        symbolName.put("description", "Symbol name to find usages of. Used with action='usages'.");

        ObjectNode filePaths = props.putObject("file_paths");
        filePaths.put("type", "string");
        filePaths.put("description", "Comma-separated file paths (relative to project root). " +
                "Used with action='impact' (files to analyze blast radius for) and " +
                "action='signatures' (specific file to extract signatures from).");

        ObjectNode topK = props.putObject("top_k");
        topK.put("type", "integer");
        topK.put("description", "Number of top results to return (default: 10). Used with action='ranked_search'.");

        ObjectNode maxDepth = props.putObject("max_depth");
        maxDepth.put("type", "integer");
        maxDepth.put("description", "Maximum BFS depth for impact analysis (default: 0 = unlimited). Used with action='impact'. Also used with action='trace' (default: 5).");

        ObjectNode direction = props.putObject("direction");
        direction.put("type", "string");
        direction.put("description", "Direction for trace: 'outgoing' (what does this call?) or 'incoming' (who calls this?). Default: incoming. Used with action='trace'.");

        ObjectNode maxTokens = props.putObject("max_tokens");
        maxTokens.put("type", "integer");
        maxTokens.put("description", "Token budget for output (default: 0 = unlimited). Truncates results with summary when exceeded. " +
                "Use ~2000 for focused results, ~4000 for broader context.");

        ObjectNode gitRef = props.putObject("git_ref");
        gitRef.put("type", "string");
        gitRef.put("description", "Git ref for changed_context (default: HEAD). Can be a branch, tag, or commit hash.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "local_code_index"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Index/search code locally");

        String action = params.path("action").asText("index");
        String cwd = context.getWorkingDirectory().toAbsolutePath().toString();
        int maxTokens = params.path("max_tokens").asInt(DEFAULT_MAX_TOKENS);
        java.io.PrintStream progress = ProgressPrintStream.from(context);

        try {
            LocalCodeIndexer indexer = new LocalCodeIndexer();

            ai.kompile.cli.main.codeindex.CodeSearchEngine engine =
                    new ai.kompile.cli.main.codeindex.CodeSearchEngine(indexer);

            ToolResult result = switch (action) {
                case "index" -> doIndex(indexer, params, cwd, progress);
                case "search" -> doSearch(indexer, params, cwd);
                case "ranked_search" -> doRankedSearch(params, cwd);
                case "blended_search" -> doBlendedSearch(params, cwd);
                case "signatures" -> doSignatures(params, cwd);
                case "impact" -> doImpact(params, cwd);
                case "health" -> doHealth(params, cwd);
                case "routing" -> doRouting(params, cwd);
                case "spath" -> doSpath(params, cwd);
                case "find" -> doFind(engine, params, cwd);
                case "replace" -> doReplace(engine, params, cwd, progress);
                case "usages" -> doUsages(engine, params, cwd);
                case "pagerank" -> doPageRank(params, cwd);
                case "clones" -> doClones(params, cwd);
                case "cochanges" -> doCoChanges(params, cwd);
                case "unused_exports" -> doUnusedExports(params, cwd);
                case "stats" -> doStats(indexer, params, cwd);
                case "list" -> doList(indexer);
                case "callers" -> doCallers(params, cwd);
                case "implementors" -> doImplementors(params, cwd);
                case "trace" -> doTrace(params, cwd);
                case "spring_resolve" -> doSpringResolve(params, cwd);
                case "debug_trace" -> doDebugTrace(params, cwd);
                case "changed_context" -> doChangedContext(params, cwd);
                case "modules" -> doModules(params, cwd);
                default -> ToolResult.error("Unknown action: " + action +
                        ". Use 'index', 'search', 'ranked_search', 'blended_search', 'signatures', " +
                        "'impact', 'health', 'routing', 'spath', 'find', 'replace', 'usages', " +
                        "'pagerank', 'clones', 'cochanges', 'unused_exports', 'stats', 'list', " +
                        "'callers', 'implementors', 'trace', 'spring_resolve', " +
                        "'debug_trace', 'changed_context', or 'modules'.");
            };

            // Apply token budget truncation if requested
            if (maxTokens > 0 && result != null && !result.isError()) {
                String text = result.getOutput();
                String truncated = truncateToTokenBudget(text, maxTokens);
                if (!truncated.equals(text)) {
                    return ToolResult.success(result.getTitle(), truncated, result.getMetadata());
                }
            }
            return result;
        } catch (Exception e) {
            return ToolResult.error("Local code index error: " + e.getMessage());
        }
    }

    private ToolResult doIndex(LocalCodeIndexer indexer, JsonNode params, String cwd, java.io.PrintStream progress) throws Exception {
        String dir = params.path("directory").asText("");
        if (dir.isEmpty()) dir = cwd;
        Path dirPath = Path.of(dir).toAbsolutePath();

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = dirPath.getFileName().toString();

        String includes = params.path("include_patterns").asText(null);
        String excludes = params.path("exclude_patterns").asText(null);
        boolean forceReindex = params.path("force_reindex").asBoolean(false);

        LocalCodeIndexer.IndexResult result = indexer.index(dirPath, projectId,
                includes, excludes, forceReindex, progress);

        StringBuilder sb = new StringBuilder();
        sb.append("Codebase indexed locally (incremental)\n\n");
        sb.append("- **Project**: ").append(result.projectId()).append("\n");
        sb.append("- **Root**: ").append(result.rootPath()).append("\n");
        sb.append("- **Files (total)**: ").append(result.filesProcessed()).append("\n");
        sb.append("- **Files (skipped)**: ").append(result.filesSkipped()).append("\n");
        sb.append("- **Files (deleted)**: ").append(result.filesDeleted()).append("\n");
        sb.append("- **Entities found**: ").append(result.entitiesFound()).append("\n");
        if (result.errors() > 0) {
            sb.append("- **Errors**: ").append(result.errors()).append("\n");
        }
        if (!result.languageCounts().isEmpty()) {
            sb.append("\nLanguages:\n");
            result.languageCounts().forEach((lang, count) ->
                    sb.append("  - ").append(lang).append(": ").append(count).append("\n"));
        }
        sb.append("\nSearch with: local_code_index action='search' query='...' project_id='")
                .append(result.projectId()).append("'");

        return ToolResult.success("code_index: " + dir, sb.toString(),
                Map.of("projectId", result.projectId(), "filesProcessed", result.filesProcessed(),
                        "entitiesFound", result.entitiesFound(), "filesSkipped", result.filesSkipped()));
    }

    private ToolResult doSearch(LocalCodeIndexer indexer, JsonNode params, String cwd) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("'query' is required for search");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) {
            projectId = Path.of(cwd).getFileName().toString();
        }

        String entityType = params.path("entity_type").asText("");
        int maxResults = params.path("max_results").asInt(20);

        List<Map<String, Object>> results = indexer.search(projectId, query,
                entityType.isEmpty() ? null : entityType, maxResults);

        if (results.isEmpty()) {
            return ToolResult.success("No results found for: " + query +
                    " (project: " + projectId + "). Run action='index' first if you haven't indexed.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Search results for \"").append(query).append("\" in project '")
                .append(projectId).append("' (").append(results.size()).append(" results)\n\n");

        int idx = 0;
        for (Map<String, Object> entity : results) {
            idx++;
            String type = ((String) entity.getOrDefault("entityType", "?")).toLowerCase();
            String name = (String) entity.getOrDefault("name", "?");
            String file = (String) entity.getOrDefault("filePath", "");
            Object startLine = entity.get("startLine");
            String sig = (String) entity.get("signature");

            sb.append(idx).append(". [").append(type).append("] **").append(name).append("**");
            if (sig != null) sb.append(" — `").append(sig).append("`");
            sb.append("\n   ").append(file);
            if (startLine != null) sb.append(":").append(startLine);
            sb.append("\n");

            String fqn = (String) entity.get("fullyQualifiedName");
            if (fqn != null && !fqn.equals(name)) {
                sb.append("   FQN: ").append(fqn).append("\n");
            }

            String inheritedFrom = (String) entity.get("inheritedFrom");
            String implementsList = (String) entity.get("implementsList");
            if (inheritedFrom != null || implementsList != null) {
                sb.append("   ");
                if (inheritedFrom != null) sb.append("extends `").append(inheritedFrom).append("`");
                if (implementsList != null) {
                    if (inheritedFrom != null) sb.append(", ");
                    sb.append("implements `").append(implementsList).append("`");
                }
                sb.append("\n");
            }

            String doc = (String) entity.get("docComment");
            if (doc != null) {
                String truncated = doc.length() > 150 ?
                        doc.substring(0, 150).replaceAll("\\n", " ") + "..." :
                        doc.replaceAll("\\n", " ");
                sb.append("   Doc: ").append(truncated).append("\n");
            }
            sb.append("\n");
        }

        // Staleness detection: check if any result files changed since indexing
        String stalenessWarning = checkStaleness(projectId, results, cwd);
        if (stalenessWarning != null) {
            sb.insert(0, stalenessWarning);
        }

        return ToolResult.success("code_search: " + query, sb.toString(),
                Map.of("query", query, "resultCount", results.size()));
    }

    // -----------------------------------------------------------------------
    // Spath semantic path search
    // -----------------------------------------------------------------------

    private ToolResult doSpath(JsonNode params, String cwd) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("'query' is required for spath (semantic path query)");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        int maxResults = params.path("max_results").asInt(50);

        ai.kompile.cli.main.codeindex.SpathResolver resolver =
                new ai.kompile.cli.main.codeindex.SpathResolver(projectId);
        ai.kompile.cli.main.codeindex.SpathResolver.SpathResult result =
                resolver.resolve(query, maxResults);

        if (result.matches().isEmpty()) {
            return ToolResult.success("No matches for spath: " + query +
                    " (project: " + projectId + "). Ensure the project is indexed first.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("spath: **").append(query).append("**");
        if (result.resolvedPackage() != null && !result.resolvedPackage().isEmpty()) {
            sb.append(" (package: ").append(result.resolvedPackage());
            if (result.resolvedSymbol() != null) {
                sb.append(", symbol: ").append(result.resolvedSymbol());
            }
            sb.append(")");
        }
        sb.append("\n").append(result.totalMatches()).append(" match(es)\n\n");

        int idx = 0;
        for (ai.kompile.cli.main.codeindex.SpathResolver.SpathMatch match : result.matches()) {
            idx++;
            String type = match.entityType().toLowerCase();
            sb.append(idx).append(". [").append(type).append("] **").append(match.name()).append("**");
            if (match.startLine() > 0) {
                sb.append(":").append(match.startLine());
            }
            sb.append("\n");
            sb.append("   ").append(match.filePath());
            if (match.startLine() > 0) sb.append(":").append(match.startLine());
            sb.append("\n");
            if (match.fullyQualifiedName() != null && !match.fullyQualifiedName().equals(match.name())) {
                sb.append("   fqn: ").append(match.fullyQualifiedName()).append("\n");
            }
            if (match.signature() != null) {
                sb.append("   sig: `").append(match.signature()).append("`\n");
            }
            if (match.inheritedFrom() != null || match.implementsList() != null) {
                sb.append("   ");
                if (match.inheritedFrom() != null)
                    sb.append("extends `").append(match.inheritedFrom()).append("`");
                if (match.implementsList() != null) {
                    if (match.inheritedFrom() != null) sb.append(", ");
                    sb.append("implements `").append(match.implementsList()).append("`");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return ToolResult.success("spath: " + query, sb.toString(),
                Map.of("query", query, "totalMatches", result.totalMatches(),
                        "resolvedPackage", result.resolvedPackage() != null ? result.resolvedPackage() : "",
                        "resolvedSymbol", result.resolvedSymbol() != null ? result.resolvedSymbol() : ""));
    }

    private ToolResult doStats(LocalCodeIndexer indexer, JsonNode params, String cwd) throws Exception {
        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) {
            projectId = Path.of(cwd).getFileName().toString();
        }

        Map<String, Object> stats = indexer.getStats(projectId);

        StringBuilder sb = new StringBuilder();
        sb.append("Local index statistics for: ").append(projectId).append("\n\n");
        sb.append("- **Root path**: ").append(stats.getOrDefault("rootPath", "?")).append("\n");
        sb.append("- **Files processed**: ").append(stats.getOrDefault("filesProcessed", "?")).append("\n");
        sb.append("- **Entities found**: ").append(stats.getOrDefault("entitiesFound", "?")).append("\n");
        sb.append("- **Errors**: ").append(stats.getOrDefault("errors", 0)).append("\n");
        sb.append("- **Indexed at**: ").append(stats.getOrDefault("indexedAt", "?")).append("\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> langCounts = (Map<String, Object>) stats.get("languageCounts");
        if (langCounts != null && !langCounts.isEmpty()) {
            sb.append("\nLanguages:\n");
            langCounts.forEach((lang, count) ->
                    sb.append("  - ").append(lang).append(": ").append(count).append("\n"));
        }

        return ToolResult.success("code_stats: " + projectId, sb.toString());
    }

    private ToolResult doList(LocalCodeIndexer indexer) throws Exception {
        List<Map<String, Object>> projects = indexer.listProjects();

        if (projects.isEmpty()) {
            return ToolResult.success("No locally indexed projects found. " +
                    "Use action='index' to index a directory first.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Locally indexed projects (").append(projects.size()).append("):\n\n");

        for (Map<String, Object> meta : projects) {
            sb.append("- **").append(meta.getOrDefault("projectId", "?")).append("**\n");
            sb.append("  Path: ").append(meta.getOrDefault("rootPath", "?")).append("\n");
            sb.append("  Files: ").append(meta.getOrDefault("filesProcessed", "?"))
                    .append(" | Entities: ").append(meta.getOrDefault("entitiesFound", "?")).append("\n");
            sb.append("  Indexed: ").append(meta.getOrDefault("indexedAt", "?")).append("\n\n");
        }

        return ToolResult.success("local_projects", sb.toString(),
                Map.of("projectCount", projects.size()));
    }

    // -----------------------------------------------------------------------
    // Ranked search (multi-signal relevance ranking)
    // -----------------------------------------------------------------------

    private ToolResult doRankedSearch(JsonNode params, String cwd) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("'query' is required for ranked_search");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        int topK = params.path("top_k").asInt(10);
        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);

        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId +
                    "'. Run action='index' first.");
        }

        CodeRelevanceRanker.RankedResults results =
                CodeRelevanceRanker.rankedSearch(projectId, query, indexDir,
                        Path.of(cwd), topK);

        String formatted = CodeRelevanceRanker.formatResults(results);

        return ToolResult.success("ranked_search: " + query, formatted,
                Map.of("query", query, "intent", results.intent().name(),
                        "resultCount", results.results().size(),
                        "candidates", results.totalCandidates(),
                        "elapsedMs", results.elapsedMs()));
    }

    // -----------------------------------------------------------------------
    // Blended search (auto-selects optimal strategy)
    // -----------------------------------------------------------------------

    private ToolResult doBlendedSearch(JsonNode params, String cwd) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("'query' is required for blended_search");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        int topK = params.path("top_k").asInt(params.path("max_results").asInt(10));
        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);

        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId +
                    "'. Run action='index' first.");
        }

        BlendedCodeSearch.BlendedResult results =
                BlendedCodeSearch.search(projectId, query, indexDir, Path.of(cwd), topK);

        String formatted = BlendedCodeSearch.formatResults(results);

        return ToolResult.success("blended_search: " + query, formatted,
                Map.of("query", query,
                        "queryType", results.queryType().name(),
                        "intent", results.intent().name(),
                        "resultCount", results.results().size(),
                        "hasCompressedContext", results.compressedContext() != null));
    }

    // -----------------------------------------------------------------------
    // Signature extraction (token-compressed file views)
    // -----------------------------------------------------------------------

    private ToolResult doSignatures(JsonNode params, String cwd) throws Exception {
        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId +
                    "'. Run action='index' first.");
        }

        String filePaths = params.path("file_paths").asText("");

        if (!filePaths.isEmpty()) {
            // Extract signatures for specific file(s)
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

        // Extract signatures for entire project
        SignatureExtractor.ProjectSignatures project =
                SignatureExtractor.extractProject(projectId, indexDir, Path.of(cwd));
        String formatted = SignatureExtractor.formatAsContext(project);

        return ToolResult.success("signatures: " + projectId, formatted,
                Map.of("projectId", projectId,
                        "totalFiles", project.totalFiles(),
                        "totalSignatures", project.totalSignatures(),
                        "reductionPercent", String.format("%.1f", project.overallReductionPercent())));
    }

    // -----------------------------------------------------------------------
    // Impact analysis (blast radius)
    // -----------------------------------------------------------------------

    private ToolResult doImpact(JsonNode params, String cwd) throws Exception {
        String filePaths = params.path("file_paths").asText("");
        if (filePaths.isEmpty()) {
            filePaths = params.path("query").asText("");
        }
        if (filePaths.isEmpty()) {
            return ToolResult.error("'file_paths' is required for impact analysis. " +
                    "Provide comma-separated relative paths of changed files.");
        }

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        int maxDepth = params.path("max_depth").asInt(0);

        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId +
                    "'. Run action='index' first.");
        }

        List<String> paths = Arrays.stream(filePaths.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (paths.size() == 1) {
            ImpactAnalyzer.FileImpact impact =
                    ImpactAnalyzer.analyzeFile(paths.get(0), indexDir, maxDepth);
            String formatted = ImpactAnalyzer.formatFileImpact(impact);
            return ToolResult.success("impact: " + paths.get(0), formatted,
                    Map.of("changedFile", impact.changedFile(),
                            "totalImpact", impact.totalImpact(),
                            "directDependents", impact.directDependents().size(),
                            "transitiveDependents", impact.transitiveDependents().size(),
                            "affectedTests", impact.affectedTests().size()));
        }

        ImpactAnalyzer.ImpactReport report =
                ImpactAnalyzer.analyzeFiles(paths, indexDir, maxDepth);
        String formatted = ImpactAnalyzer.formatReport(report);
        return ToolResult.success("impact: " + paths.size() + " files", formatted,
                Map.of("changedFiles", paths.size(),
                        "totalUniqueImpact", report.totalUniqueImpact(),
                        "affectedTests", report.allAffectedTests().size()));
    }

    // -----------------------------------------------------------------------
    // Index health scoring
    // -----------------------------------------------------------------------

    private ToolResult doHealth(JsonNode params, String cwd) throws Exception {
        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
        if (!Files.isDirectory(indexDir)) {
            return ToolResult.error("No index found for project '" + projectId +
                    "'. Run action='index' first.");
        }

        IndexHealthScorer.HealthScore hs = IndexHealthScorer.score(projectId, indexDir);
        String formatted = IndexHealthScorer.formatHealth(hs);

        return ToolResult.success("health: " + projectId, formatted,
                Map.of("score", hs.score(), "grade", hs.grade(),
                        "totalFiles", hs.totalFiles(),
                        "totalEntities", hs.totalEntities()));
    }

    // -----------------------------------------------------------------------
    // Complexity routing (file tier classification)
    // -----------------------------------------------------------------------

    private ToolResult doRouting(JsonNode params, String cwd) throws Exception {
        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId +
                    "'. Run action='index' first.");
        }

        ComplexityClassifier.ProjectClassification pc =
                ComplexityClassifier.classifyProject(projectId, indexDir);
        String formatted = ComplexityClassifier.formatClassification(pc);

        return ToolResult.success("routing: " + projectId, formatted,
                Map.of("projectId", projectId,
                        "fast", pc.fastCount(),
                        "balanced", pc.balancedCount(),
                        "powerful", pc.powerfulCount()));
    }

    // -----------------------------------------------------------------------
    // Find in files
    // -----------------------------------------------------------------------

    private ToolResult doFind(ai.kompile.cli.main.codeindex.CodeSearchEngine engine,
                              JsonNode params, String cwd) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("'query' is required for find");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        ai.kompile.cli.main.codeindex.CodeSearchEngine.FindOptions opts =
                ai.kompile.cli.main.codeindex.CodeSearchEngine.FindOptions.defaults()
                        .withRegex(params.path("regex").asBoolean(false))
                        .withCaseSensitive(params.path("case_sensitive").asBoolean(true))
                        .withWholeWord(params.path("whole_word").asBoolean(false))
                        .withFilePattern(params.path("file_pattern").asText(null))
                        .withContextLines(params.path("context_lines").asInt(2))
                        .withMaxResults(params.path("max_results").asInt(200));

        ai.kompile.cli.main.codeindex.CodeSearchEngine.FindResult result =
                engine.findInFiles(projectId, query, opts);

        if (result.matches().isEmpty()) {
            return ToolResult.success("No matches found for: " + query);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(result.totalMatches()).append(" match(es) in ")
                .append(result.filesWithMatches()).append(" file(s)");
        if (result.truncated()) sb.append(" (results truncated)");
        sb.append("\n\n");

        String lastFile = null;
        for (ai.kompile.cli.main.codeindex.CodeSearchEngine.FileMatch match : result.matches()) {
            if (!match.filePath().equals(lastFile)) {
                if (lastFile != null) sb.append("\n");
                sb.append("**").append(match.filePath()).append("**\n");
                lastFile = match.filePath();
            }
            for (String ctx : match.contextBefore()) {
                sb.append("  ").append(ctx).append("\n");
            }
            sb.append("  ").append(match.lineNumber()).append(": ").append(match.lineContent()).append("\n");
            for (String ctx : match.contextAfter()) {
                sb.append("  ").append(ctx).append("\n");
            }
        }

        return ToolResult.success("find: " + query, sb.toString(),
                Map.of("query", query, "totalMatches", result.totalMatches(),
                        "filesWithMatches", result.filesWithMatches()));
    }

    // -----------------------------------------------------------------------
    // Find and replace
    // -----------------------------------------------------------------------

    private ToolResult doReplace(ai.kompile.cli.main.codeindex.CodeSearchEngine engine,
                                  JsonNode params, String cwd, java.io.PrintStream progress) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("'query' is required for replace");

        String replacement = params.path("replacement").asText("");
        if (replacement.isEmpty()) return ToolResult.error("'replacement' is required for replace");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        boolean dryRun = params.path("dry_run").asBoolean(true);

        ai.kompile.cli.main.codeindex.CodeSearchEngine.FindOptions opts =
                ai.kompile.cli.main.codeindex.CodeSearchEngine.FindOptions.defaults()
                        .withRegex(params.path("regex").asBoolean(false))
                        .withCaseSensitive(params.path("case_sensitive").asBoolean(true))
                        .withWholeWord(params.path("whole_word").asBoolean(false))
                        .withFilePattern(params.path("file_pattern").asText(null));

        ai.kompile.cli.main.codeindex.CodeSearchEngine.ReplaceResult result =
                engine.findAndReplace(projectId, query, replacement, opts, dryRun, progress);

        if (result.replacements().isEmpty()) {
            return ToolResult.success("No matches found for: " + query);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(dryRun ? "**[DRY RUN]** " : "");
        sb.append(result.totalReplacements()).append(" replacement(s) in ")
                .append(result.filesModified()).append(" file(s)\n\n");

        String lastFile = null;
        for (ai.kompile.cli.main.codeindex.CodeSearchEngine.Replacement r : result.replacements()) {
            if (!r.filePath().equals(lastFile)) {
                if (lastFile != null) sb.append("\n");
                sb.append("**").append(r.filePath()).append("**\n");
                lastFile = r.filePath();
            }
            sb.append("  - ").append(r.lineNumber()).append(": `").append(r.originalLine().trim()).append("`\n");
            sb.append("  + ").append(r.lineNumber()).append(": `").append(r.replacedLine().trim()).append("`\n");
        }

        if (!dryRun && result.indexResult() != null) {
            sb.append("\nIndex updated: ").append(result.indexResult().entitiesFound()).append(" entities");
        }

        return ToolResult.success("replace: " + query + " -> " + replacement, sb.toString(),
                Map.of("query", query, "replacement", replacement,
                        "totalReplacements", result.totalReplacements(),
                        "dryRun", dryRun));
    }

    // -----------------------------------------------------------------------
    // Find usages
    // -----------------------------------------------------------------------

    private ToolResult doUsages(ai.kompile.cli.main.codeindex.CodeSearchEngine engine,
                                 JsonNode params, String cwd) throws Exception {
        String symbolName = params.path("symbol_name").asText(params.path("query").asText(""));
        if (symbolName.isEmpty()) return ToolResult.error("'symbol_name' (or 'query') is required for usages");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        String entityType = params.path("entity_type").asText("");
        int maxResults = params.path("max_results").asInt(100);

        ai.kompile.cli.main.codeindex.CodeSearchEngine.UsagesResult result =
                engine.findUsages(projectId, symbolName,
                        entityType.isEmpty() ? null : entityType, maxResults);

        if (result.usages().isEmpty()) {
            return ToolResult.success("No usages found for: " + symbolName);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Usages of ").append(result.symbolType().toLowerCase()).append(" '")
                .append(result.symbolName()).append("' (")
                .append(result.totalUsages()).append(" total)\n\n");

        if (result.definitionFile() != null) {
            sb.append("Defined at: ").append(result.definitionFile())
                    .append(":").append(result.definitionLine()).append("\n\n");
        }

        if (!result.usagesByKind().isEmpty()) {
            sb.append("Breakdown:\n");
            result.usagesByKind().forEach((kind, count) ->
                    sb.append("  - ").append(kind.name().toLowerCase().replace('_', ' '))
                            .append(": ").append(count).append("\n"));
            sb.append("\n");
        }

        String lastFile = null;
        for (ai.kompile.cli.main.codeindex.CodeSearchEngine.Usage usage : result.usages()) {
            if (!usage.filePath().equals(lastFile)) {
                if (lastFile != null) sb.append("\n");
                sb.append("**").append(usage.filePath()).append("**\n");
                lastFile = usage.filePath();
            }
            String kindLabel = usage.kind().name().toLowerCase().replace('_', ' ');
            sb.append("  ").append(usage.lineNumber()).append(": [").append(kindLabel).append("] ")
                    .append(usage.lineContent());
            if (usage.context() != null) sb.append(" (in ").append(usage.context()).append(")");
            sb.append("\n");
        }

        return ToolResult.success("usages: " + symbolName, sb.toString(),
                Map.of("symbolName", symbolName, "totalUsages", result.totalUsages(),
                        "symbolType", result.symbolType()));
    }

    // -----------------------------------------------------------------------
    // PageRank (file importance scoring)
    // -----------------------------------------------------------------------

    private ToolResult doPageRank(JsonNode params, String cwd) throws Exception {
        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId +
                    "'. Run action='index' first.");
        }

        String subAction = params.path("query").asText("top_files");
        int limit = params.path("max_results").asInt(20);

        // If no PageRank data exists, compute it first
        if (!PageRankComputer.isComputed(indexDir)) {
            int ranked = PageRankComputer.compute(indexDir, Path.of(cwd));
            if (ranked == 0) {
                return ToolResult.success("PageRank: no graph edges found. " +
                        "Index the project first and ensure import/call relations are extracted.");
            }
        }

        if (subAction.equals("compute") || subAction.equals("recompute")) {
            int ranked = PageRankComputer.compute(indexDir, Path.of(cwd));
            return ToolResult.success("pagerank: computed",
                    "PageRank computed for " + ranked + " files.",
                    Map.of("filesRanked", ranked));
        }

        // Query mode: get top files or specific file rank
        if (subAction.startsWith("file:")) {
            String filePath = subAction.substring(5).trim();
            double score = PageRankComputer.getFileRank(indexDir, filePath);
            return ToolResult.success("pagerank: " + filePath,
                    String.format("PageRank for %s: %.6f", filePath, score),
                    Map.of("filePath", filePath, "score", score));
        }

        // Default: top files
        List<Map<String, Object>> topFiles = PageRankComputer.getTopFiles(indexDir, limit);
        String formatted = PageRankComputer.formatTopFiles(topFiles);
        return ToolResult.success("pagerank: top_files", formatted,
                Map.of("resultCount", topFiles.size()));
    }

    // -----------------------------------------------------------------------
    // Clone detection (MinHash near-duplicate functions)
    // -----------------------------------------------------------------------

    private ToolResult doClones(JsonNode params, String cwd) throws Exception {
        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId +
                    "'. Run action='index' first.");
        }

        int limit = params.path("max_results").asInt(15);
        String filePath = params.path("file_paths").asText("");
        String query = params.path("query").asText("");

        // If a specific file is requested, get clones for that file
        if (!filePath.isEmpty()) {
            List<CloneDetector.ClonePair> clones = CloneDetector.getClonesForFile(indexDir, filePath.trim());
            if (clones.isEmpty()) {
                // Run detection first, then retry
                CloneDetector.detect(indexDir, Path.of(cwd), 0.8);
                clones = CloneDetector.getClonesForFile(indexDir, filePath.trim());
            }
            if (clones.isEmpty()) {
                return ToolResult.success("No clones found for: " + filePath);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Clones for ").append(filePath).append(":\n\n");
            for (CloneDetector.ClonePair pair : clones) {
                sb.append(String.format("  %d%% — %s:%d %s ↔ %s:%d %s\n",
                        Math.round(pair.similarity() * 100),
                        pair.fileA(), pair.lineA(), pair.nameA(),
                        pair.fileB(), pair.lineB(), pair.nameB()));
            }
            return ToolResult.success("clones: " + filePath, sb.toString());
        }

        // Run full detection (or query=detect to force rerun)
        if (query.equals("detect") || query.equals("recompute")) {
            double threshold = params.path("top_k").asInt(80) / 100.0;
            if (threshold <= 0 || threshold > 1.0) threshold = 0.8;
            CloneDetector.CloneReport report = CloneDetector.detect(indexDir, Path.of(cwd), threshold);
            List<CloneDetector.ClonePair> topClones = CloneDetector.getClones(indexDir, limit);
            List<CloneDetector.FragmentCluster> topFragments = CloneDetector.getFragments(indexDir, limit);
            String formatted = CloneDetector.formatReport(report, topClones, topFragments);
            return ToolResult.success("clones: detected", formatted,
                    Map.of("functionsAnalyzed", report.functionsAnalyzed(),
                            "clonePairs", report.clonePairsFound(),
                            "fragmentClusters", report.fragmentClustersFound()));
        }

        // Default: show existing results (run detection if none exist)
        List<CloneDetector.ClonePair> clones = CloneDetector.getClones(indexDir, limit);
        if (clones.isEmpty()) {
            CloneDetector.CloneReport report = CloneDetector.detect(indexDir, Path.of(cwd), 0.8);
            clones = CloneDetector.getClones(indexDir, limit);
            List<CloneDetector.FragmentCluster> fragments = CloneDetector.getFragments(indexDir, limit);
            String formatted = CloneDetector.formatReport(report, clones, fragments);
            return ToolResult.success("clones: detected", formatted,
                    Map.of("functionsAnalyzed", report.functionsAnalyzed(),
                            "clonePairs", report.clonePairsFound(),
                            "fragmentClusters", report.fragmentClustersFound()));
        }

        List<CloneDetector.FragmentCluster> fragments = CloneDetector.getFragments(indexDir, limit);
        CloneDetector.CloneReport summaryReport = new CloneDetector.CloneReport(0, clones.size(), fragments.size(), 0);
        String formatted = CloneDetector.formatReport(summaryReport, clones, fragments);
        return ToolResult.success("clones", formatted);
    }

    // -----------------------------------------------------------------------
    // Co-change analysis (git history coupling)
    // -----------------------------------------------------------------------

    private ToolResult doCoChanges(JsonNode params, String cwd) throws Exception {
        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId +
                    "'. Run action='index' first.");
        }

        int limit = params.path("max_results").asInt(20);
        String filePath = params.path("file_paths").asText("");
        String query = params.path("query").asText("");

        // Query co-changes for a specific file
        if (!filePath.isEmpty()) {
            List<CoChangeAnalyzer.CoChangePair> pairs = CoChangeAnalyzer.getCoChanges(indexDir, filePath.trim());
            if (pairs.isEmpty() && !CoChangeAnalyzer.hasData(indexDir)) {
                // Run analysis first
                CoChangeAnalyzer.analyze(indexDir, Path.of(cwd), 300);
                pairs = CoChangeAnalyzer.getCoChanges(indexDir, filePath.trim());
            }
            if (pairs.isEmpty()) {
                return ToolResult.success("No co-change partners found for: " + filePath);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Files that change together with ").append(filePath).append(":\n\n");
            for (CoChangeAnalyzer.CoChangePair pair : pairs) {
                String partner = pair.fileA().equals(filePath.trim()) ? pair.fileB() : pair.fileA();
                sb.append(String.format("  %3d co-commits: %s\n", pair.cochangeCount(), partner));
            }
            return ToolResult.success("cochanges: " + filePath, sb.toString());
        }

        // Run analysis (or query=analyze to force rerun)
        if (query.equals("analyze") || query.equals("recompute") || !CoChangeAnalyzer.hasData(indexDir)) {
            int commitLimit = params.path("top_k").asInt(300);
            CoChangeAnalyzer.CoChangeReport report = CoChangeAnalyzer.analyze(indexDir, Path.of(cwd), commitLimit);
            List<CoChangeAnalyzer.CoChangePair> topPairs = CoChangeAnalyzer.getTopCoChanges(indexDir, limit);
            String formatted = CoChangeAnalyzer.formatReport(report, topPairs);
            return ToolResult.success("cochanges: analyzed", formatted,
                    Map.of("commitsAnalyzed", report.commitsAnalyzed(),
                            "pairsFound", report.pairsFound(),
                            "filesInvolved", report.filesInvolved()));
        }

        // Default: show existing results
        List<CoChangeAnalyzer.CoChangePair> topPairs = CoChangeAnalyzer.getTopCoChanges(indexDir, limit);
        if (topPairs.isEmpty()) {
            return ToolResult.success("No co-change data found. Run with query='analyze' to scan git history.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Top co-change pairs (files that change together most often):\n\n");
        for (CoChangeAnalyzer.CoChangePair pair : topPairs) {
            sb.append(String.format("  %3d co-commits: %s ↔ %s\n",
                    pair.cochangeCount(), pair.fileA(), pair.fileB()));
        }
        return ToolResult.success("cochanges", sb.toString(),
                Map.of("pairCount", topPairs.size()));
    }

    // -----------------------------------------------------------------------
    // Unused export detection (dead code finder)
    // -----------------------------------------------------------------------

    private ToolResult doUnusedExports(JsonNode params, String cwd) throws Exception {
        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId +
                    "'. Run action='index' first.");
        }

        UnusedExportDetector.UnusedExportReport report =
                UnusedExportDetector.detect(indexDir, Path.of(cwd));

        String formatted = UnusedExportDetector.formatReport(report);
        return ToolResult.success("unused_exports", formatted,
                Map.of("deadFiles", report.deadFiles().size(),
                        "deadBarrels", report.deadBarrels().size(),
                        "deadExports", report.deadExports().size(),
                        "testOnlyExports", report.testOnlyExports().size(),
                        "internalOnlyExports", report.internalOnlyExports().size(),
                        "totalAnalyzed", report.totalExportsAnalyzed()));
    }

    // -----------------------------------------------------------------------
    // Callers — reverse call lookup
    // -----------------------------------------------------------------------

    private ToolResult doCallers(JsonNode params, String cwd) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) query = params.path("symbol_name").asText("");
        if (query.isEmpty()) return ToolResult.error("'query' or 'symbol_name' is required for callers");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        int maxResults = params.path("max_results").asInt(30);
        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);

        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId + "'. Run action='index' first.");
        }

        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            List<Map<String, Object>> callers = db.getCallers(query, maxResults);

            if (callers.isEmpty()) {
                return ToolResult.success("No callers found for: " + query +
                        ". The method may not be called, or the index may need rebuilding with action='index'.");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Callers of **").append(query).append("** (").append(callers.size()).append(" call sites)\n\n");

            for (int i = 0; i < callers.size(); i++) {
                Map<String, Object> c = callers.get(i);
                sb.append(i + 1).append(". ");
                String callerName = (String) c.get("callerName");
                String callerFqn = (String) c.get("callerFqn");
                String callerType = (String) c.get("callerType");
                String file = (String) c.get("callSiteFile");
                Object line = c.get("callSiteLine");

                sb.append("[").append(callerType != null ? callerType.toLowerCase() : "?").append("] ");
                sb.append("**").append(callerName != null ? callerName : callerFqn).append("**");
                sb.append("\n   ").append(file);
                if (line != null) sb.append(":").append(line);
                if (callerFqn != null && !callerFqn.equals(callerName)) {
                    sb.append("\n   FQN: ").append(callerFqn);
                }
                String sig = (String) c.get("callerSignature");
                if (sig != null) sb.append("\n   Sig: `").append(sig).append("`");
                sb.append("\n\n");
            }

            return ToolResult.success("callers: " + query, sb.toString(),
                    Map.of("query", query, "callerCount", callers.size()));
        } catch (java.sql.SQLException e) {
            return ToolResult.error("Database error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Implementors — find all implementations of an interface
    // -----------------------------------------------------------------------

    private ToolResult doImplementors(JsonNode params, String cwd) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("'query' is required for implementors (interface FQN or name)");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        int maxResults = params.path("max_results").asInt(30);
        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);

        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId + "'. Run action='index' first.");
        }

        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            List<Map<String, Object>> impls = db.getImplementors(query, maxResults);

            if (impls.isEmpty()) {
                return ToolResult.success("No implementors found for: " + query);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Implementations of **").append(query).append("** (").append(impls.size()).append(")\n\n");

            for (int i = 0; i < impls.size(); i++) {
                Map<String, Object> impl = impls.get(i);
                String name = (String) impl.get("name");
                String fqn = (String) impl.get("fullyQualifiedName");
                String type = (String) impl.get("entityType");
                String file = (String) impl.get("filePath");
                Object startLine = impl.get("startLine");

                sb.append(i + 1).append(". [").append(type != null ? type.toLowerCase() : "?").append("] ");
                sb.append("**").append(name).append("**\n");
                sb.append("   ").append(file);
                if (startLine != null) sb.append(":").append(startLine);
                sb.append("\n");
                if (fqn != null && !fqn.equals(name)) {
                    sb.append("   FQN: ").append(fqn).append("\n");
                }
                String inherited = (String) impl.get("inheritedFrom");
                String implements_ = (String) impl.get("implementsList");
                if (inherited != null) sb.append("   extends `").append(inherited).append("`\n");
                if (implements_ != null) sb.append("   implements `").append(implements_).append("`\n");
                sb.append("\n");
            }

            return ToolResult.success("implementors: " + query, sb.toString(),
                    Map.of("query", query, "count", impls.size()));
        } catch (java.sql.SQLException e) {
            return ToolResult.error("Database error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Trace — BFS call chain traversal
    // -----------------------------------------------------------------------

    private ToolResult doTrace(JsonNode params, String cwd) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("'query' is required for trace (method FQN or name)");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        int maxDepth = params.path("max_depth").asInt(5);
        String direction = params.path("direction").asText("incoming");
        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);

        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId + "'. Run action='index' first.");
        }

        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            Map<String, Object> chain = db.getCallChain(query, maxDepth, direction);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edges = (List<Map<String, Object>>) chain.get("edges");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) chain.get("nodes");

            if (edges.isEmpty()) {
                return ToolResult.success("No call chain found for: " + query +
                        " (direction: " + direction + ")");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Call chain: **").append(query).append("** (")
                    .append(direction).append(", depth ").append(maxDepth).append(")\n");
            sb.append("Nodes: ").append(chain.get("totalNodes"))
                    .append(" | Edges: ").append(chain.get("totalEdges")).append("\n\n");

            // Group edges by depth
            Map<Integer, List<Map<String, Object>>> byDepth = new LinkedHashMap<>();
            for (Map<String, Object> edge : edges) {
                int d = edge.get("depth") instanceof Number n ? n.intValue() : 0;
                byDepth.computeIfAbsent(d, k -> new ArrayList<>()).add(edge);
            }

            for (var entry : byDepth.entrySet()) {
                sb.append("**Depth ").append(entry.getKey()).append("**:\n");
                for (Map<String, Object> edge : entry.getValue()) {
                    String source = (String) edge.get("sourceFqn");
                    String target = (String) edge.get("targetFqn");
                    if (target == null) target = (String) edge.get("targetName");
                    String file = (String) edge.get("filePath");
                    Object line = edge.get("line");

                    if ("incoming".equals(direction)) {
                        sb.append("  \u2190 ").append(source);
                    } else {
                        sb.append("  \u2192 ").append(target);
                    }
                    if (file != null) {
                        sb.append(" (").append(file);
                        if (line != null) sb.append(":").append(line);
                        sb.append(")");
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            return ToolResult.success("trace: " + query, sb.toString(),
                    Map.of("query", query, "direction", direction,
                            "totalNodes", chain.get("totalNodes"),
                            "totalEdges", chain.get("totalEdges")));
        } catch (java.sql.SQLException e) {
            return ToolResult.error("Database error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Spring DI resolution
    // -----------------------------------------------------------------------

    private ToolResult doSpringResolve(JsonNode params, String cwd) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("'query' is required for spring_resolve (interface FQN or name)");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);

        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId + "'. Run action='index' first.");
        }

        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            Map<String, Object> resolution = db.resolveSpringBean(query);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> implementors = (List<Map<String, Object>>) resolution.get("implementors");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> springComponents = (List<Map<String, Object>>) resolution.get("springComponents");

            StringBuilder sb = new StringBuilder();
            sb.append("Spring DI Resolution: **").append(query).append("**\n\n");

            // Resolution strategy
            String strategy = (String) resolution.get("resolutionStrategy");
            sb.append("**Resolution**: ").append(strategy).append("\n");

            @SuppressWarnings("unchecked")
            Map<String, Object> resolved = (Map<String, Object>) resolution.get("resolvedBean");
            if (resolved != null) {
                sb.append("**Resolved Bean**: ").append(resolved.get("fullyQualifiedName"));
                sb.append(" (").append(resolved.get("filePath")).append(")\n");
            }
            sb.append("\n");

            // All implementors
            sb.append("### Implementors (").append(implementors.size()).append(")\n\n");
            for (Map<String, Object> impl : implementors) {
                String name = (String) impl.get("name");
                String fqn = (String) impl.get("fullyQualifiedName");
                String file = (String) impl.get("filePath");
                boolean isComponent = springComponents.stream()
                        .anyMatch(c -> fqn != null && fqn.equals(c.get("fullyQualifiedName")));
                boolean isPrimary = impl.equals(resolution.get("primaryBean"));

                sb.append("- **").append(name).append("**");
                if (isPrimary) sb.append(" `@Primary`");
                if (isComponent) sb.append(" `@Component`");
                sb.append("\n  ").append(file).append("\n");
                if (fqn != null) sb.append("  FQN: ").append(fqn).append("\n");
            }

            // Conditionals
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conditionals = (List<Map<String, Object>>) resolution.get("conditionalBeans");
            if (conditionals != null && !conditionals.isEmpty()) {
                sb.append("\n### Conditional Beans\n\n");
                for (Map<String, Object> cond : conditionals) {
                    sb.append("- **").append(cond.get("name")).append("**: ");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> condRels = (List<Map<String, Object>>) cond.get("conditionals");
                    if (condRels != null) {
                        for (Map<String, Object> rel : condRels) {
                            sb.append("`").append(rel.get("targetName")).append("` ");
                        }
                    }
                    sb.append("\n");
                }
            }

            // Who injects this interface?
            List<Map<String, Object>> injectors = db.getSpringInjectors(query, 20);
            if (!injectors.isEmpty()) {
                sb.append("\n### Injected By (").append(injectors.size()).append(" classes)\n\n");
                for (Map<String, Object> inj : injectors) {
                    sb.append("- ").append(inj.get("injectorName"));
                    sb.append(" (").append(inj.get("filePath"));
                    if (inj.get("line") != null) sb.append(":").append(inj.get("line"));
                    sb.append(")\n");
                }
            }

            return ToolResult.success("spring_resolve: " + query, sb.toString(),
                    Map.of("query", query, "strategy", strategy,
                            "implementorCount", implementors.size(),
                            "componentCount", springComponents.size()));
        } catch (java.sql.SQLException e) {
            return ToolResult.error("Database error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Staleness detection helper
    // -----------------------------------------------------------------------

    /**
     * Check if any files in search results have been modified since indexing.
     * Returns a warning string to prepend, or null if everything is fresh.
     */
    private String checkStaleness(String projectId, List<Map<String, Object>> results, String cwd) {
        try {
            IndexDatabase db = getCachedDb(projectId);
            if (db == null) return null;

            Set<String> resultFiles = new LinkedHashSet<>();
            for (Map<String, Object> r : results) {
                String fp = (String) r.get("filePath");
                if (fp != null) resultFiles.add(fp);
            }
            if (resultFiles.isEmpty()) return null;

            Set<String> stale = db.getStaleFilesInSet(Path.of(cwd), resultFiles);
            if (stale.isEmpty()) return null;

            StringBuilder warn = new StringBuilder();
            warn.append("**WARNING**: ").append(stale.size()).append(" file(s) in results modified since last index:");
            int shown = 0;
            for (String f : stale) {
                if (shown++ >= 5) {
                    warn.append("\n  ... and ").append(stale.size() - 5).append(" more");
                    break;
                }
                warn.append("\n  - ").append(f);
            }
            warn.append("\nRun `action='index'` to update.\n\n");
            return warn.toString();
        } catch (Exception e) {
            return null; // Don't fail the search over staleness check errors
        }
    }

    // -----------------------------------------------------------------------
    // debug_trace — composite debugging action
    // -----------------------------------------------------------------------

    private ToolResult doDebugTrace(JsonNode params, String cwd) throws Exception {
        String query = params.path("query").asText("");
        if (query.isEmpty()) return ToolResult.error("'query' is required for debug_trace (symbol name or FQN)");

        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        int maxDepth = params.path("max_depth").asInt(3);
        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);

        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId + "'. Run action='index' first.");
        }

        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            StringBuilder sb = new StringBuilder();
            sb.append("# Debug Trace: **").append(query).append("**\n\n");

            // ── Step 1: Find the symbol ──
            Map<String, Object> entity = db.findEntityByFqn(query);
            if (entity == null) entity = db.findEntityBySuffix(query);

            if (entity != null) {
                sb.append("## Symbol Found\n");
                sb.append("- **Name**: ").append(entity.get("name")).append("\n");
                sb.append("- **Type**: ").append(entity.get("entityType")).append("\n");
                sb.append("- **File**: ").append(entity.get("filePath"));
                if (entity.get("startLine") != null) sb.append(":").append(entity.get("startLine"));
                sb.append("\n");
                if (entity.get("fullyQualifiedName") != null) {
                    sb.append("- **FQN**: ").append(entity.get("fullyQualifiedName")).append("\n");
                }
                if (entity.get("signature") != null) {
                    sb.append("- **Sig**: `").append(entity.get("signature")).append("`\n");
                }
                sb.append("\n");
            } else {
                // Try FTS search as fallback
                List<Map<String, Object>> searchResults = db.search(query, null, 5);
                if (searchResults.isEmpty()) {
                    return ToolResult.success("debug_trace: " + query,
                            "No symbol found matching '" + query + "'. Try a different name or FQN.");
                }
                entity = searchResults.get(0);
                sb.append("## Closest Match\n");
                sb.append("- **Name**: ").append(entity.get("name")).append("\n");
                sb.append("- **Type**: ").append(entity.get("entityType")).append("\n");
                sb.append("- **File**: ").append(entity.get("filePath")).append("\n\n");
                if (searchResults.size() > 1) {
                    sb.append("Other candidates: ");
                    for (int i = 1; i < searchResults.size(); i++) {
                        sb.append(searchResults.get(i).get("name"));
                        if (i < searchResults.size() - 1) sb.append(", ");
                    }
                    sb.append("\n\n");
                }
            }

            String resolvedFqn = entity.get("fullyQualifiedName") != null
                    ? (String) entity.get("fullyQualifiedName") : query;

            // ── Step 2: Callers ──
            List<Map<String, Object>> callers = db.getCallers(resolvedFqn, 15);
            sb.append("## Callers (").append(callers.size()).append(")\n\n");
            if (callers.isEmpty()) {
                sb.append("_No callers found_\n\n");
            } else {
                for (Map<String, Object> c : callers) {
                    sb.append("- ");
                    if (c.get("callerType") != null)
                        sb.append("[").append(((String) c.get("callerType")).toLowerCase()).append("] ");
                    sb.append("**").append(c.get("callerName") != null ? c.get("callerName") : c.get("callerFqn"));
                    sb.append("** (").append(c.get("callSiteFile"));
                    if (c.get("callSiteLine") != null) sb.append(":").append(c.get("callSiteLine"));
                    sb.append(")\n");
                }
                sb.append("\n");
            }

            // ── Step 3: Call chain trace (incoming) ──
            Map<String, Object> chain = db.getCallChain(resolvedFqn, maxDepth, "incoming");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edges = (List<Map<String, Object>>) chain.get("edges");
            sb.append("## Incoming Call Chain (depth ").append(maxDepth).append(")\n");
            sb.append("Nodes: ").append(chain.get("totalNodes"))
                    .append(" | Edges: ").append(chain.get("totalEdges")).append("\n\n");

            if (edges.isEmpty()) {
                sb.append("_No incoming call chain_\n\n");
            } else {
                Map<Integer, List<Map<String, Object>>> byDepth = new LinkedHashMap<>();
                for (Map<String, Object> edge : edges) {
                    int d = edge.get("depth") instanceof Number n ? n.intValue() : 0;
                    byDepth.computeIfAbsent(d, k -> new ArrayList<>()).add(edge);
                }
                for (var entry : byDepth.entrySet()) {
                    sb.append("**Depth ").append(entry.getKey()).append("**: ");
                    List<String> sources = new ArrayList<>();
                    for (Map<String, Object> edge : entry.getValue()) {
                        String src = (String) edge.get("sourceFqn");
                        if (src != null) sources.add(src);
                    }
                    sb.append(String.join(", ", sources)).append("\n");
                }
                sb.append("\n");
            }

            // ── Step 4: Impact analysis ──
            String filePath = (String) entity.get("filePath");
            if (filePath != null) {
                try {
                    ImpactAnalyzer.FileImpact impact = ImpactAnalyzer.analyzeFile(filePath, indexDir, 0);
                    sb.append("## Impact Analysis (").append(filePath).append(")\n");
                    sb.append("- **Total impacted**: ").append(impact.totalImpact()).append(" files\n");
                    if (!impact.directDependents().isEmpty()) {
                        sb.append("- **Direct dependents**: ")
                                .append(String.join(", ", impact.directDependents().subList(0,
                                        Math.min(10, impact.directDependents().size()))));
                        if (impact.directDependents().size() > 10)
                            sb.append(" ... +").append(impact.directDependents().size() - 10);
                        sb.append("\n");
                    }
                    if (!impact.affectedTests().isEmpty()) {
                        sb.append("- **Affected tests**: ")
                                .append(String.join(", ", impact.affectedTests().subList(0,
                                        Math.min(5, impact.affectedTests().size()))));
                        if (impact.affectedTests().size() > 5)
                            sb.append(" ... +").append(impact.affectedTests().size() - 5);
                        sb.append("\n");
                    }
                } catch (Exception e) {
                    sb.append("## Impact Analysis\n_Error: ").append(e.getMessage()).append("_\n");
                }
            }

            return ToolResult.success("debug_trace: " + query, sb.toString(),
                    Map.of("query", query, "callerCount", callers.size(),
                            "chainNodes", chain.get("totalNodes"),
                            "chainEdges", chain.get("totalEdges")));
        } catch (java.sql.SQLException e) {
            return ToolResult.error("Database error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // changed_context — git-aware entity analysis
    // -----------------------------------------------------------------------

    private ToolResult doChangedContext(JsonNode params, String cwd) throws Exception {
        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        String gitRef = params.path("git_ref").asText("HEAD");
        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);

        if (!Files.exists(indexDir.resolve("index.db"))) {
            return ToolResult.error("No index found for project '" + projectId + "'. Run action='index' first.");
        }

        // Get changed files from git
        Set<String> changedFiles = getGitChangedFiles(cwd, gitRef);
        if (changedFiles.isEmpty()) {
            return ToolResult.success("changed_context",
                    "No uncommitted changes detected (compared to " + gitRef + ").");
        }

        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            StringBuilder sb = new StringBuilder();
            sb.append("# Changed Context (").append(changedFiles.size()).append(" files changed vs ")
                    .append(gitRef).append(")\n\n");

            // Get entities in changed files
            Map<String, List<Map<String, Object>>> entitiesByFile =
                    db.getEntitiesForFiles(changedFiles, 20);

            int totalEntities = 0;
            sb.append("## Changed Files & Entities\n\n");
            for (String file : changedFiles) {
                List<Map<String, Object>> entities = entitiesByFile.getOrDefault(file, List.of());
                totalEntities += entities.size();
                sb.append("### ").append(file);
                if (entities.isEmpty()) {
                    sb.append(" _(not in index)_\n");
                } else {
                    sb.append(" (").append(entities.size()).append(" entities)\n");
                    for (Map<String, Object> e : entities) {
                        sb.append("  - [").append(((String) e.getOrDefault("entityType", "?")).toLowerCase())
                                .append("] **").append(e.get("name")).append("**");
                        if (e.get("signature") != null) sb.append(" `").append(e.get("signature")).append("`");
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }

            // Impact analysis across all changed files
            List<String> indexedChanged = new ArrayList<>(entitiesByFile.keySet());
            if (!indexedChanged.isEmpty()) {
                try {
                    ImpactAnalyzer.ImpactReport impact =
                            ImpactAnalyzer.analyzeFiles(indexedChanged, indexDir, 0);

                    sb.append("## Blast Radius\n");
                    sb.append("- **Total unique files impacted**: ").append(impact.totalUniqueImpact()).append("\n");
                    if (!impact.allAffectedTests().isEmpty()) {
                        sb.append("- **Affected tests** (").append(impact.allAffectedTests().size()).append("): ");
                        int shown = 0;
                        for (String test : impact.allAffectedTests()) {
                            if (shown++ >= 10) {
                                sb.append("... +").append(impact.allAffectedTests().size() - 10);
                                break;
                            }
                            if (shown > 1) sb.append(", ");
                            sb.append(test);
                        }
                        sb.append("\n");
                    }
                    if (!impact.allAffectedRoutes().isEmpty()) {
                        sb.append("- **Affected routes**: ")
                                .append(String.join(", ", impact.allAffectedRoutes())).append("\n");
                    }

                    // Top impacted files (not in the change set themselves)
                    Set<String> nonSelfImpacted = new LinkedHashSet<>(impact.allImpacted());
                    nonSelfImpacted.removeAll(changedFiles);
                    if (!nonSelfImpacted.isEmpty()) {
                        sb.append("- **Other impacted files** (").append(nonSelfImpacted.size()).append("):\n");
                        int shown = 0;
                        for (String f : nonSelfImpacted) {
                            if (shown++ >= 15) {
                                sb.append("  ... +").append(nonSelfImpacted.size() - 15).append(" more\n");
                                break;
                            }
                            sb.append("  - ").append(f).append("\n");
                        }
                    }
                } catch (Exception e) {
                    sb.append("## Blast Radius\n_Error: ").append(e.getMessage()).append("_\n");
                }
            }

            // Staleness check
            Set<String> stale = db.getStaleFilesInSet(Path.of(cwd), changedFiles);
            if (!stale.isEmpty()) {
                sb.append("\n**Note**: ").append(stale.size())
                        .append(" changed file(s) are stale in the index. Run `action='index'` to update.\n");
            }

            return ToolResult.success("changed_context: " + changedFiles.size() + " files", sb.toString(),
                    Map.of("changedFiles", changedFiles.size(),
                            "totalEntities", totalEntities,
                            "gitRef", gitRef));
        } catch (java.sql.SQLException e) {
            return ToolResult.error("Database error: " + e.getMessage());
        }
    }

    /**
     * Get files changed relative to a git ref (uncommitted changes).
     */
    private Set<String> getGitChangedFiles(String cwd, String gitRef) {
        Set<String> changed = new LinkedHashSet<>();
        try {
            // Staged + unstaged changes
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", gitRef);
            pb.directory(new java.io.File(cwd));
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) changed.add(line);
                }
            }
            if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }

            // Also include untracked files
            ProcessBuilder pb2 = new ProcessBuilder("git", "diff", "--name-only", "--cached", gitRef);
            pb2.directory(new java.io.File(cwd));
            pb2.redirectErrorStream(true);
            Process proc2 = pb2.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc2.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) changed.add(line);
                }
            }
            if (!proc2.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                proc2.destroyForcibly();
            }

            // Unstaged modifications (not yet added)
            ProcessBuilder pb3 = new ProcessBuilder("git", "diff", "--name-only");
            pb3.directory(new java.io.File(cwd));
            pb3.redirectErrorStream(true);
            Process proc3 = pb3.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc3.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) changed.add(line);
                }
            }
            if (!proc3.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                proc3.destroyForcibly();
            }
        } catch (Exception e) {
            // Git not available or not a git repo — return empty
        }
        return changed;
    }

    // -----------------------------------------------------------------------
    // modules — Maven module boundary analysis
    // -----------------------------------------------------------------------

    private ToolResult doModules(JsonNode params, String cwd) throws Exception {
        String projectId = params.path("project_id").asText("");
        if (projectId.isEmpty()) projectId = Path.of(cwd).getFileName().toString();

        String query = params.path("query").asText("");
        Path projectRoot = Path.of(cwd);

        // Find all pom.xml files
        List<Path> pomFiles = new ArrayList<>();
        try (java.util.stream.Stream<Path> walkStream = Files.walk(projectRoot, 10)) {
            walkStream
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> {
                        String rel = projectRoot.relativize(p).toString();
                        return !rel.contains("target/") && !rel.contains("node_modules/")
                                && !rel.contains(".git/");
                    })
                    .forEach(pomFiles::add);
        }

        if (pomFiles.isEmpty()) {
            return ToolResult.success("modules",
                    "No pom.xml files found in " + cwd + ". This action only works for Maven projects.");
        }

        // Parse module structure
        Map<String, ModuleInfo> modules = new LinkedHashMap<>();
        for (Path pom : pomFiles) {
            try {
                ModuleInfo info = parsePomModule(pom, projectRoot);
                if (info != null) modules.put(info.artifactId, info);
            } catch (Exception e) {
                // Skip unparseable poms
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Maven Module Structure (").append(modules.size()).append(" modules)\n\n");

        // If query specified, filter to modules matching the query
        if (!query.isEmpty()) {
            String q = query.toLowerCase();
            Map<String, ModuleInfo> filtered = new LinkedHashMap<>();
            for (var entry : modules.entrySet()) {
                if (entry.getKey().toLowerCase().contains(q)
                        || entry.getValue().path.toLowerCase().contains(q)) {
                    filtered.put(entry.getKey(), entry.getValue());
                }
            }

            if (filtered.isEmpty()) {
                return ToolResult.success("modules: " + query,
                        "No modules matching '" + query + "'. " + modules.size() + " total modules.");
            }

            sb.append("Filtered by: **").append(query).append("** (")
                    .append(filtered.size()).append(" matches)\n\n");

            for (var entry : filtered.entrySet()) {
                formatModuleInfo(sb, entry.getValue(), modules);
            }
        } else {
            // Show parent modules first, then leaf modules
            List<ModuleInfo> parents = new ArrayList<>();
            List<ModuleInfo> leaves = new ArrayList<>();
            for (ModuleInfo m : modules.values()) {
                if (!m.childModules.isEmpty()) parents.add(m);
                else leaves.add(m);
            }

            if (!parents.isEmpty()) {
                sb.append("## Parent Modules (").append(parents.size()).append(")\n\n");
                for (ModuleInfo m : parents) {
                    sb.append("### ").append(m.artifactId).append(" (").append(m.path).append(")\n");
                    sb.append("Children: ").append(String.join(", ", m.childModules)).append("\n\n");
                }
            }

            sb.append("## All Modules (").append(leaves.size()).append(" leaf, ")
                    .append(parents.size()).append(" parent)\n\n");
            for (ModuleInfo m : modules.values()) {
                sb.append("- **").append(m.artifactId).append("** — `").append(m.path).append("`");
                if (!m.dependencies.isEmpty()) {
                    long internalDeps = m.dependencies.stream()
                            .filter(modules::containsKey).count();
                    if (internalDeps > 0) {
                        sb.append(" (").append(internalDeps).append(" internal deps)");
                    }
                }
                sb.append("\n");
            }

            // Cross-module dependency graph (internal deps only)
            sb.append("\n## Internal Dependency Graph\n\n");
            boolean hasDeps = false;
            for (ModuleInfo m : modules.values()) {
                List<String> internal = m.dependencies.stream()
                        .filter(modules::containsKey)
                        .toList();
                if (!internal.isEmpty()) {
                    hasDeps = true;
                    sb.append("**").append(m.artifactId).append("** depends on: ")
                            .append(String.join(", ", internal)).append("\n");
                }
            }
            if (!hasDeps) {
                sb.append("_No cross-module dependencies detected_\n");
            }
        }

        // If code index exists, show entity summary
        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
        if (Files.exists(indexDir.resolve("index.db"))) {
            try (IndexDatabase db = IndexDatabase.open(indexDir)) {
                int totalEntities = db.getEntityCount();
                int totalFiles = db.getFileCount();
                Map<String, Integer> langCounts = db.getLanguageCounts();
                sb.append("\n## Index Overlay\n");
                sb.append("- **Total indexed entities**: ").append(totalEntities).append("\n");
                sb.append("- **Total indexed files**: ").append(totalFiles).append("\n");
                if (!langCounts.isEmpty()) {
                    sb.append("- **Languages**: ");
                    int shown = 0;
                    for (var lc : langCounts.entrySet()) {
                        if (shown++ > 0) sb.append(", ");
                        sb.append(lc.getKey()).append("(").append(lc.getValue()).append(")");
                        if (shown >= 8) { sb.append("..."); break; }
                    }
                    sb.append("\n");
                }
            } catch (Exception e) {
                // Skip overlay on error
            }
        }

        return ToolResult.success("modules: " + modules.size() + " modules", sb.toString(),
                Map.of("moduleCount", modules.size()));
    }

    /**
     * Parsed Maven module info.
     */
    private static class ModuleInfo {
        String groupId;
        String artifactId;
        String version;
        String packaging;
        String path; // relative path from project root
        List<String> childModules = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();
    }

    /**
     * Parse a pom.xml to extract module info.
     * Simple XML parsing using string matching (no DOM parser needed).
     */
    private ModuleInfo parsePomModule(Path pomFile, Path projectRoot) throws Exception {
        String content = Files.readString(pomFile);
        ModuleInfo info = new ModuleInfo();

        info.path = projectRoot.relativize(pomFile.getParent()).toString();
        if (info.path.isEmpty()) info.path = ".";

        // Extract artifactId (first occurrence, before dependencies section)
        info.artifactId = extractFirstXmlTag(content, "artifactId");
        if (info.artifactId == null) return null;

        info.groupId = extractFirstXmlTag(content, "groupId");
        info.version = extractFirstXmlTag(content, "version");
        info.packaging = extractFirstXmlTag(content, "packaging");
        if (info.packaging == null) info.packaging = "jar";

        // Extract child modules
        int modulesStart = content.indexOf("<modules>");
        int modulesEnd = content.indexOf("</modules>");
        if (modulesStart >= 0 && modulesEnd > modulesStart) {
            String modulesBlock = content.substring(modulesStart, modulesEnd);
            int idx = 0;
            while (true) {
                int start = modulesBlock.indexOf("<module>", idx);
                if (start < 0) break;
                int end = modulesBlock.indexOf("</module>", start);
                if (end < 0) break;
                String module = modulesBlock.substring(start + 8, end).trim();
                info.childModules.add(module);
                idx = end + 9;
            }
        }

        // Extract dependencies (artifactId only)
        int depsStart = content.indexOf("<dependencies>");
        int depsEnd = content.indexOf("</dependencies>");
        if (depsStart >= 0 && depsEnd > depsStart) {
            String depsBlock = content.substring(depsStart, depsEnd);
            int idx = 0;
            while (true) {
                int start = depsBlock.indexOf("<dependency>", idx);
                if (start < 0) break;
                int end = depsBlock.indexOf("</dependency>", start);
                if (end < 0) break;
                String dep = depsBlock.substring(start, end);
                String depArtifact = extractFirstXmlTag(dep, "artifactId");
                if (depArtifact != null) info.dependencies.add(depArtifact);
                idx = end + 13;
            }
        }

        return info;
    }

    private String extractFirstXmlTag(String xml, String tag) {
        // Only match the first occurrence before <dependencies> to get the project's own values
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        if (start < 0) return null;
        int end = xml.indexOf(close, start);
        if (end < 0) return null;
        return xml.substring(start + open.length(), end).trim();
    }

    private void formatModuleInfo(StringBuilder sb, ModuleInfo m, Map<String, ModuleInfo> allModules) {
        sb.append("### ").append(m.artifactId).append("\n");
        sb.append("- **Path**: `").append(m.path).append("`\n");
        if (m.groupId != null) sb.append("- **Group**: ").append(m.groupId).append("\n");
        if (m.packaging != null) sb.append("- **Packaging**: ").append(m.packaging).append("\n");
        if (!m.childModules.isEmpty()) {
            sb.append("- **Child modules**: ").append(String.join(", ", m.childModules)).append("\n");
        }
        List<String> internalDeps = m.dependencies.stream()
                .filter(allModules::containsKey).toList();
        if (!internalDeps.isEmpty()) {
            sb.append("- **Internal deps**: ").append(String.join(", ", internalDeps)).append("\n");
        }
        List<String> externalDeps = m.dependencies.stream()
                .filter(d -> !allModules.containsKey(d)).toList();
        if (!externalDeps.isEmpty()) {
            sb.append("- **External deps**: ").append(String.join(", ",
                    externalDeps.subList(0, Math.min(10, externalDeps.size()))));
            if (externalDeps.size() > 10) sb.append(" ... +").append(externalDeps.size() - 10);
            sb.append("\n");
        }
        sb.append("\n");
    }
}
