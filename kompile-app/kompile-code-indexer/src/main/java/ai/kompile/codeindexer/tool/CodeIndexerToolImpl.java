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

package ai.kompile.codeindexer.tool;

import ai.kompile.codeindexer.domain.*;
import ai.kompile.codeindexer.service.CodeGraphBuilder;
import ai.kompile.codeindexer.service.CodeSearchService;
import ai.kompile.codeindexer.service.CodebaseIndexer;
import ai.kompile.codeindexer.service.LanguageRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tools for code search, indexing, graph building, and directory management.
 */
@Component
public class CodeIndexerToolImpl {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected CodeIndexerToolImpl() {}


    private CodebaseIndexer indexer;
    private CodeSearchService searchService;
    private LanguageRegistry languageRegistry;
    private CodeGraphBuilder graphBuilder;
    private CodeEntityRepository entityRepository;
    private CodeRelationRepository relationRepository;
    private IndexedDirectoryRepository directoryRepository;

    @Autowired
    public CodeIndexerToolImpl(CodebaseIndexer indexer,
                               CodeSearchService searchService,
                               LanguageRegistry languageRegistry,
                               @Autowired(required = false) CodeGraphBuilder graphBuilder,
                               CodeEntityRepository entityRepository,
                               CodeRelationRepository relationRepository,
                               IndexedDirectoryRepository directoryRepository) {
        this.indexer = indexer;
        this.searchService = searchService;
        this.languageRegistry = languageRegistry;
        this.graphBuilder = graphBuilder;
        this.entityRepository = entityRepository;
        this.relationRepository = relationRepository;
        this.directoryRepository = directoryRepository;
    }

    @Tool(name = "code_search", description = "Search an indexed codebase for classes, methods, " +
            "functions, interfaces, and other code entities. Returns matching entities with file " +
            "locations, signatures, and documentation.")
    public Map<String, Object> codeSearch(
            @ToolParam(description = "Search query — name, signature fragment, or keyword") String query,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Filter: CLASS, METHOD, FUNCTION, INTERFACE, FILE, IMPORT, FIELD, ENUM, RECORD") String entityType,
            @ToolParam(description = "Max results (default: 10)") Integer maxResults) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (maxResults == null || maxResults <= 0) maxResults = 10;

        List<CodeEntity> results;
        if (entityType != null && !entityType.isEmpty()) {
            try {
                CodeEntityType type = CodeEntityType.valueOf(entityType.toUpperCase());
                results = searchService.searchByType(projectId, query, type, maxResults);
            } catch (IllegalArgumentException e) {
                return Map.of("error", "Invalid entity type: " + entityType);
            }
        } else {
            results = searchService.search(projectId, query, maxResults);
        }

        return Map.of(
                "query", query,
                "resultCount", results.size(),
                "results", results.stream().map(this::entityToMap).collect(Collectors.toList()),
                "formatted", searchService.formatResults(results)
        );
    }

    @Tool(name = "code_index", description = "Index a codebase directory. Walks the tree, extracts " +
            "code entities, and stores them in the knowledge graph. Call before code_search.")
    public Map<String, Object> codeIndex(
            @ToolParam(description = "Absolute path to the codebase root directory") String rootPath,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (rootPath == null || rootPath.isEmpty()) return Map.of("error", "rootPath is required");

        try {
            CodebaseIndexer.IndexingStatus status = indexer.indexDirectory(projectId, rootPath);
            return status.toMap();
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "code_add_directory", description = "Add a directory to the project's tracked " +
            "directories for indexing. Supports include/exclude patterns, language overrides, " +
            "description, and tags for categorization.")
    public Map<String, Object> codeAddDirectory(
            @ToolParam(description = "Absolute path to the directory") String path,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Display name for this directory") String displayName,
            @ToolParam(description = "Comma-separated include glob patterns (e.g. '*.java,*.py')") String includePatterns,
            @ToolParam(description = "Comma-separated exclude glob patterns (e.g. '*.test.js,*_test.go')") String excludePatterns,
            @ToolParam(description = "Human-readable description of what this directory contains") String description,
            @ToolParam(description = "Comma-separated tags for categorization (e.g. 'backend,java,microservice')") String tags) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (path == null || path.isEmpty()) return Map.of("error", "path is required");

        try {
            IndexedDirectory dir = indexer.addDirectory(projectId, path, displayName,
                    includePatterns, excludePatterns, null, description, tags);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("added", dir.getAbsolutePath());
            result.put("projectId", dir.getProjectId());
            result.put("status", dir.getStatus().name());
            if (dir.getDescription() != null) result.put("description", dir.getDescription());
            if (dir.getTags() != null) result.put("tags", dir.getTags());
            return result;
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "code_remove_directory", description = "Remove a tracked directory and its indexed entities.")
    public Map<String, Object> codeRemoveDirectory(
            @ToolParam(description = "Absolute path to the directory to remove") String path,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (path == null || path.isEmpty()) return Map.of("error", "path is required");
        indexer.removeDirectory(projectId, path);
        return Map.of("removed", path);
    }

    @Tool(name = "code_list_directories", description = "List all tracked directories for a project.")
    public Map<String, Object> codeListDirectories(
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        List<IndexedDirectory> dirs = indexer.listDirectories(projectId);
        return Map.of("directories", dirs.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", d.getAbsolutePath());
            m.put("displayName", d.getDisplayName());
            m.put("status", d.getStatus().name());
            m.put("filesIndexed", d.getFilesIndexed());
            m.put("entitiesFound", d.getEntitiesFound());
            m.put("relationsCreated", d.getRelationsCreated());
            if (d.getLastIndexedAt() != null) m.put("lastIndexedAt", d.getLastIndexedAt().toString());
            if (d.getIncludePatterns() != null) m.put("includePatterns", d.getIncludePatterns());
            if (d.getExcludePatterns() != null) m.put("excludePatterns", d.getExcludePatterns());
            if (d.getDescription() != null) m.put("description", d.getDescription());
            if (d.getTags() != null) m.put("tags", d.getTags());
            if (d.getLanguageOverridesJson() != null) m.put("languageOverrides", d.getLanguageOverridesJson());
            return m;
        }).collect(Collectors.toList()));
    }

    @Tool(name = "code_entities", description = "Get code entities in a file or children of a parent " +
            "entity (e.g. methods of a class).")
    public Map<String, Object> codeEntities(
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "File path to list entities for") String filePath,
            @ToolParam(description = "Parent FQN to get children of") String parentFqn) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";

        List<CodeEntity> results;
        if (filePath != null && !filePath.isEmpty()) {
            results = searchService.getEntitiesInFile(projectId, filePath);
        } else if (parentFqn != null && !parentFqn.isEmpty()) {
            results = searchService.getChildren(projectId, parentFqn);
        } else {
            return Map.of("error", "Provide filePath or parentFqn");
        }

        return Map.of(
                "resultCount", results.size(),
                "results", results.stream().map(this::entityToMap).collect(Collectors.toList()),
                "formatted", searchService.formatResults(results)
        );
    }

    @Tool(name = "code_related", description = "Find entities related via the knowledge graph — " +
            "inheritance, callers, dependencies.")
    public Map<String, Object> codeRelated(
            @ToolParam(description = "Fully-qualified name of the entity") String fqn,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Max traversal depth (default: 2)") Integer maxDepth) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (maxDepth == null || maxDepth <= 0) maxDepth = 2;

        List<CodeEntity> results = searchService.findRelated(projectId, fqn, maxDepth);
        return Map.of(
                "fqn", fqn,
                "resultCount", results.size(),
                "results", results.stream().map(this::entityToMap).collect(Collectors.toList()),
                "formatted", searchService.formatResults(results)
        );
    }

    @Tool(name = "code_statistics", description = "Get statistics about an indexed codebase.")
    public Map<String, Object> codeStatistics(
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        return searchService.getStatistics(projectId);
    }

    @Tool(name = "code_set_language", description = "Override the inferred language for a file " +
            "or pattern. For example, force '*.jsx' files to parse as 'typescript'.")
    public Map<String, Object> codeSetLanguage(
            @ToolParam(description = "Glob pattern (e.g. '*.jsx') or absolute file path") String patternOrFile,
            @ToolParam(description = "Language ID (e.g. 'typescript', 'python', 'rust')") String language) {

        if (patternOrFile == null || language == null) {
            return Map.of("error", "patternOrFile and language are required");
        }

        if (patternOrFile.contains("*") || patternOrFile.contains("?")) {
            languageRegistry.setPatternLanguage(patternOrFile, language);
            return Map.of("patternOverride", patternOrFile, "language", language);
        } else {
            languageRegistry.setFileLanguage(patternOrFile, language);
            return Map.of("fileOverride", patternOrFile, "language", language);
        }
    }

    // ── Graph builder tools ──────────────────────────────────────────

    @Tool(name = "code_graph_build", description = "Build a fully-connected code knowledge graph " +
            "from a directory. Indexes all files, extracts symbols, and creates edges for " +
            "inheritance, imports, containment, and cross-references.")
    public Map<String, Object> codeGraphBuild(
            @ToolParam(description = "Absolute path to the directory to index") String directoryPath,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (graphBuilder == null) return Map.of("error", "Knowledge graph not available");
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (directoryPath == null || directoryPath.isEmpty()) return Map.of("error", "directoryPath is required");
        return graphBuilder.buildGraph(projectId, directoryPath);
    }

    @Tool(name = "code_graph_search", description = "Search the code knowledge graph for symbols, " +
            "returning both code entities and graph nodes with their relationships.")
    public Map<String, Object> codeGraphSearch(
            @ToolParam(description = "Search query") String query,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Max results (default: 20)") Integer maxResults) {

        if (graphBuilder == null) return Map.of("error", "Knowledge graph not available");
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (maxResults == null || maxResults <= 0) maxResults = 20;
        return graphBuilder.searchGraph(projectId, query, maxResults);
    }

    @Tool(name = "code_graph_symbol", description = "Get the subgraph centered on a specific symbol " +
            "(class, method, etc.) showing all connected nodes up to N hops.")
    public Map<String, Object> codeGraphSymbol(
            @ToolParam(description = "Fully-qualified name of the symbol") String fqn,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Traversal depth (default: 2)") Integer depth) {

        if (graphBuilder == null) return Map.of("error", "Knowledge graph not available");
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (depth == null || depth <= 0) depth = 2;
        return graphBuilder.getSymbolGraph(projectId, fqn, depth);
    }

    @Tool(name = "code_graph_file", description = "Get all symbols in a file and their graph " +
            "connections (classes, methods, fields, imports, inheritance).")
    public Map<String, Object> codeGraphFile(
            @ToolParam(description = "File path (relative to indexed directory)") String filePath,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (graphBuilder == null) return Map.of("error", "Knowledge graph not available");
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        return graphBuilder.getFileGraph(projectId, filePath);
    }

    @Tool(name = "code_graph_stats", description = "Get combined statistics from the code indexer " +
            "and knowledge graph, including entity counts, edge counts, and directory breakdowns.")
    public Map<String, Object> codeGraphStats(
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (graphBuilder == null) return Map.of("error", "Knowledge graph not available");
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        return graphBuilder.getStatistics(projectId);
    }

    @Tool(name = "code_graph_visualization", description = "Get graph visualization data for " +
            "rendering the code knowledge graph in a UI.")
    public Map<String, Object> codeGraphVisualization(
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Maximum nodes to include (default: 200)") Integer maxNodes) {

        if (graphBuilder == null) return Map.of("error", "Knowledge graph not available");
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (maxNodes == null || maxNodes <= 0) maxNodes = 200;
        return graphBuilder.getGraphVisualization(projectId, maxNodes);
    }

    // ── Unused function detection ─────────────────────────────────────

    @Tool(name = "code_unused_functions", description = "Detect potentially unused functions and " +
            "methods in an indexed codebase. Analyzes call relationships extracted during indexing " +
            "to find callable entities whose name never appears at a call site. Filters out likely " +
            "entry points (main, test methods, framework callbacks, abstract methods). " +
            "Requires indexing (code_index) to have been run first.")
    public Map<String, Object> codeUnusedFunctions(
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Filter by visibility: 'private', 'protected', 'public', or empty for all") String visibility,
            @ToolParam(description = "Filter by language: 'java', 'python', 'typescript', etc.") String language,
            @ToolParam(description = "Include functions in test files (default: false)") Boolean includeTests,
            @ToolParam(description = "Max results (default: 50)") Integer maxResults) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (maxResults == null || maxResults <= 0) maxResults = 50;
        boolean tests = includeTests != null && includeTests;

        List<CodeEntity> unused = searchService.findUnusedFunctions(
                projectId, visibility, language, tests, maxResults);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unusedCount", unused.size());
        result.put("filters", Map.of(
                "visibility", visibility != null ? visibility : "all",
                "language", language != null ? language : "all",
                "includeTests", tests
        ));
        result.put("results", unused.stream().map(this::entityToMap).collect(Collectors.toList()));
        result.put("formatted", searchService.formatResults(unused));
        result.put("note", "These are heuristic results. Functions called via reflection, " +
                "dependency injection, or from un-indexed code may appear here as false positives.");
        return result;
    }

    // ── Callers ──────────────────────────────────────────────────────

    @Tool(name = "code_callers", description = "Find all call sites for a given function or method name " +
            "in the indexed codebase. Returns the calling entity's FQN, file path, and line number.")
    public Map<String, Object> codeCallers(
            @ToolParam(description = "Function or method name to find callers of") String name,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (name == null || name.isEmpty()) return Map.of("error", "name is required");

        List<CodeRelation> callers = searchService.findCallersOf(projectId, name);
        return Map.of(
                "functionName", name,
                "callerCount", callers.size(),
                "callers", callers.stream().map(this::relationToMap).collect(Collectors.toList())
        );
    }

    // ── Relations ───────────────────────────────────────────────────

    @Tool(name = "code_relations", description = "Get all outgoing relations from a code entity " +
            "(EXTENDS, IMPLEMENTS, CALLS, CONTAINS, IMPORTS, etc). Optionally filter by relation type.")
    public Map<String, Object> codeRelations(
            @ToolParam(description = "Fully-qualified name of the source entity") String fqn,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Filter by relation type: EXTENDS, IMPLEMENTS, CALLS, CONTAINS, IMPORTS, ANNOTATED_BY, OVERRIDES") String relationType) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (fqn == null || fqn.isEmpty()) return Map.of("error", "fqn is required");

        List<CodeRelation> relations;
        if (relationType != null && !relationType.isEmpty()) {
            try {
                CodeRelationType type = CodeRelationType.valueOf(relationType.toUpperCase());
                relations = relationRepository.findByProjectIdAndSourceFqnAndRelationType(projectId, fqn, type);
            } catch (IllegalArgumentException e) {
                return Map.of("error", "Invalid relation type: " + relationType);
            }
        } else {
            relations = relationRepository.findByProjectIdAndSourceFqn(projectId, fqn);
        }

        return Map.of(
                "fqn", fqn,
                "direction", "outgoing",
                "relationType", relationType != null ? relationType : "ALL",
                "count", relations.size(),
                "relations", relations.stream().map(this::relationToMap).collect(Collectors.toList())
        );
    }

    @Tool(name = "code_reverse_relations", description = "Get all incoming relations to a code entity " +
            "- find what extends, implements, calls, or imports this entity.")
    public Map<String, Object> codeReverseRelations(
            @ToolParam(description = "Fully-qualified name of the target entity") String fqn,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Filter by relation type: EXTENDS, IMPLEMENTS, CALLS, CONTAINS, IMPORTS, ANNOTATED_BY, OVERRIDES") String relationType) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (fqn == null || fqn.isEmpty()) return Map.of("error", "fqn is required");

        List<CodeRelation> relations;
        if (relationType != null && !relationType.isEmpty()) {
            try {
                CodeRelationType type = CodeRelationType.valueOf(relationType.toUpperCase());
                relations = relationRepository.findByProjectIdAndTargetFqnAndRelationType(projectId, fqn, type);
            } catch (IllegalArgumentException e) {
                return Map.of("error", "Invalid relation type: " + relationType);
            }
        } else {
            relations = relationRepository.findByProjectIdAndTargetFqn(projectId, fqn);
        }

        return Map.of(
                "targetFqn", fqn,
                "direction", "incoming",
                "relationType", relationType != null ? relationType : "ALL",
                "count", relations.size(),
                "relations", relations.stream().map(this::relationToMap).collect(Collectors.toList())
        );
    }

    // ── Entity lookup ───────────────────────────────────────────────

    @Tool(name = "code_entity_lookup", description = "Look up a specific code entity by its " +
            "fully-qualified name. Returns full details including signature, doc comment, and location.")
    public Map<String, Object> codeEntityLookup(
            @ToolParam(description = "Fully-qualified name of the entity") String fqn,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (fqn == null || fqn.isEmpty()) return Map.of("error", "fqn is required");

        return entityRepository.findByProjectIdAndFullyQualifiedName(projectId, fqn)
                .map(e -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("found", true);
                    result.putAll(entityToMap(e));
                    return result;
                })
                .orElse(Map.of("found", false, "fqn", fqn));
    }

    @Tool(name = "code_entities_by_language", description = "List all code entities indexed for a " +
            "specific programming language.")
    public Map<String, Object> codeEntitiesByLanguage(
            @ToolParam(description = "Language name (e.g. 'java', 'python', 'typescript')") String language,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (language == null || language.isEmpty()) return Map.of("error", "language is required");

        List<CodeEntity> entities = entityRepository.findByProjectIdAndLanguage(projectId, language);
        return Map.of(
                "language", language,
                "count", entities.size(),
                "entities", entities.stream().map(this::entityToMap).collect(Collectors.toList())
        );
    }

    @Tool(name = "code_project_languages", description = "List all programming languages detected " +
            "in the indexed project.")
    public Map<String, Object> codeProjectLanguages(
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        List<String> languages = entityRepository.findDistinctLanguagesByProjectId(projectId);
        return Map.of(
                "projectId", projectId,
                "languages", languages
        );
    }

    // ── Graph connectivity & export ─────────────────────────────────

    @Tool(name = "code_graph_ensure_connectivity", description = "Ensure the code knowledge graph is " +
            "fully connected by adding edges between disconnected components. Run after building " +
            "the graph to link isolated clusters.")
    public Map<String, Object> codeGraphEnsureConnectivity(
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (graphBuilder == null) return Map.of("error", "Knowledge graph not available");
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        int added = graphBuilder.ensureConnectivity(projectId);
        return Map.of("edgesAdded", added, "message", added > 0
                ? added + " edges added to connect isolated components"
                : "Graph is already fully connected");
    }

    @Tool(name = "code_graph_export", description = "Export the code knowledge graph in SVG, HTML " +
            "(interactive D3.js viewer), or JSON format.")
    public Map<String, Object> codeGraphExport(
            @ToolParam(description = "Export format: 'svg', 'html', or 'json'") String format,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Maximum nodes to include (default: 500)") Integer maxNodes) {

        if (graphBuilder == null) return Map.of("error", "Knowledge graph not available");
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (format == null || format.isEmpty()) return Map.of("error", "format is required (svg, html, or json)");
        if (maxNodes == null || maxNodes <= 0) maxNodes = 500;

        try {
            Map<String, Object> result = graphBuilder.exportGraph(projectId, format, maxNodes);
            byte[] data = (byte[]) result.get("data");
            return Map.of(
                    "format", format,
                    "filename", result.get("filename"),
                    "contentType", result.get("contentType"),
                    "sizeBytes", data.length,
                    "content", new String(data, java.nio.charset.StandardCharsets.UTF_8)
            );
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }
    }

    // ── Multi-directory build ───────────────────────────────────────

    @Tool(name = "code_graph_build_multi", description = "Build a code knowledge graph from multiple " +
            "directories at once. Indexes all directories and creates cross-directory edges.")
    public Map<String, Object> codeGraphBuildMulti(
            @ToolParam(description = "Comma-separated list of absolute directory paths") String paths,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (graphBuilder == null) return Map.of("error", "Knowledge graph not available");
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (paths == null || paths.isEmpty()) return Map.of("error", "paths is required");

        List<String> pathList = Arrays.stream(paths.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (pathList.isEmpty()) return Map.of("error", "No valid paths provided");

        return graphBuilder.buildGraphFromMultipleDirectories(projectId, pathList);
    }

    // ── Shortest path ──────────────────────────────────────��─────────

    @Tool(name = "code_graph_shortest_path", description = "Find the shortest path between two code " +
            "symbols (by fully-qualified name) through the knowledge graph. Returns the ordered " +
            "sequence of nodes connecting source to target with hop count. Useful for understanding " +
            "how two classes or methods are related through inheritance, calls, or imports.")
    public Map<String, Object> codeGraphShortestPath(
            @ToolParam(description = "FQN of the starting symbol (e.g. com.example.UserService)") String from,
            @ToolParam(description = "FQN of the target symbol (e.g. com.example.DatabaseConfig)") String to,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Optional edge type filter: HIERARCHICAL, EMBEDDING_SIMILARITY, SHARED_ENTITY, USER_DEFINED, CROSS_SOURCE, CITATION, TEMPORAL") String edgeType) {

        if (graphBuilder == null) return Map.of("error", "Knowledge graph not available");
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (from == null || from.isEmpty()) return Map.of("error", "from is required");
        if (to == null || to.isEmpty()) return Map.of("error", "to is required");

        return graphBuilder.getShortestPath(projectId, from, to, edgeType);
    }

    // ── Composite: agent-actionable graph queries ─────────────────────

    @Tool(name = "code_impact_analysis", description = "Blast-radius analysis: find everything affected " +
            "by changing a symbol. Returns callers, subclasses, implementors, importers, and all other " +
            "incoming relations. Answers: 'if I change X, what breaks?'")
    public Map<String, Object> codeImpactAnalysis(
            @ToolParam(description = "Fully-qualified name of the symbol to analyze") String fqn,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (fqn == null || fqn.isEmpty()) return Map.of("error", "fqn is required");
        return graphBuilder != null
                ? graphBuilder.getImpactAnalysis(projectId, fqn)
                : Map.of("error", "Knowledge graph not available");
    }

    @Tool(name = "code_dependency_tree", description = "Dependency tree: what does this symbol depend on? " +
            "Follows outgoing CALLS, EXTENDS, IMPLEMENTS, IMPORTS, and other relations recursively. " +
            "Returns layers of dependencies by depth. Answers: 'what does X need?'")
    public Map<String, Object> codeDependencyTree(
            @ToolParam(description = "Fully-qualified name of the root symbol") String fqn,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Maximum depth to traverse (default: 3)") Integer maxDepth) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (fqn == null || fqn.isEmpty()) return Map.of("error", "fqn is required");
        if (maxDepth == null || maxDepth <= 0) maxDepth = 3;
        return graphBuilder != null
                ? graphBuilder.getDependencyTree(projectId, fqn, maxDepth)
                : Map.of("error", "Knowledge graph not available");
    }

    @Tool(name = "code_component_map", description = "Module/package boundary view: all types in a file " +
            "or package, their inheritance hierarchies, and cross-module dependencies. " +
            "Answers: 'what belongs here?' Pass a file path (contains '/') or a parent FQN (package).")
    public Map<String, Object> codeComponentMap(
            @ToolParam(description = "File path or parent FQN (package) to map") String scope,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (scope == null || scope.isEmpty()) return Map.of("error", "scope is required");
        return graphBuilder != null
                ? graphBuilder.getComponentMap(projectId, scope)
                : Map.of("error", "Knowledge graph not available");
    }

    @Tool(name = "code_symbol_dossier", description = "Complete dossier for a symbol: entity details, " +
            "parent, children, all incoming/outgoing relations grouped by type, callers, and source " +
            "location. Answers: 'tell me everything about X.'")
    public Map<String, Object> codeSymbolDossier(
            @ToolParam(description = "Fully-qualified name of the symbol") String fqn,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (fqn == null || fqn.isEmpty()) return Map.of("error", "fqn is required");
        return graphBuilder != null
                ? graphBuilder.getSymbolDossier(projectId, fqn)
                : Map.of("error", "Knowledge graph not available");
    }

    @Tool(name = "code_export_local_graph", description = "Generate a localized SVG, HTML, or JSON " +
            "visualization centered on a specific symbol or file, rather than the entire project graph. " +
            "Produces a focused, readable artifact for understanding a code neighborhood.")
    public Map<String, Object> codeExportLocalGraph(
            @ToolParam(description = "FQN of a symbol or file path to center the export on") String focus,
            @ToolParam(description = "Export format: 'svg', 'html', or 'json'") String format,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Traversal depth for symbol graphs (default: 2, ignored for file graphs)") Integer depth) {

        if (graphBuilder == null) return Map.of("error", "Knowledge graph not available");
        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (focus == null || focus.isEmpty()) return Map.of("error", "focus is required");
        if (format == null || format.isEmpty()) format = "svg";
        if (depth == null || depth <= 0) depth = 2;

        try {
            Map<String, Object> result = graphBuilder.exportLocalizedGraph(
                    projectId, focus, format, depth);
            byte[] data = (byte[]) result.get("data");
            return Map.of(
                    "format", format,
                    "focus", focus,
                    "filename", result.get("filename"),
                    "contentType", result.get("contentType"),
                    "sizeBytes", data.length,
                    "content", new String(data, java.nio.charset.StandardCharsets.UTF_8)
            );
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }
    }

    // ── Test coverage & code paths ─────────────────────────────────

    @Tool(name = "code_test_frameworks", description = "Detect test frameworks in an indexed project. " +
            "Scans annotations (@Test, @Fact, #[test], etc.), file paths (/test/, _test.go), and naming " +
            "conventions across Java, Python, JS/TS, Go, Rust, C#, Kotlin, Scala, and more.")
    public Map<String, Object> codeTestFrameworks(
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        return graphBuilder != null
                ? graphBuilder.getTestFrameworks(projectId)
                : Map.of("error", "Code graph not available");
    }

    @Tool(name = "code_test_coverage", description = "Get a test coverage report for an indexed project: " +
            "test/production ratio, coverage percentage, untested methods, tested methods, and framework " +
            "breakdown per language. Answers: 'how well tested is this codebase?'")
    public Map<String, Object> codeTestCoverage(
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        return graphBuilder != null
                ? graphBuilder.getTestCoverage(projectId)
                : Map.of("error", "Code graph not available");
    }

    @Tool(name = "code_tests_for_symbol", description = "Find tests that exercise a given production " +
            "symbol. Traces reverse CALLS from the symbol to test entities (annotated with @Test, in " +
            "test files, etc.). Returns both direct test callers and indirect (2-hop) tests. " +
            "Answers: 'what tests cover this method?'")
    public Map<String, Object> codeTestsForSymbol(
            @ToolParam(description = "Fully-qualified name of the production symbol") String fqn,
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (fqn == null || fqn.isEmpty()) return Map.of("error", "fqn is required");
        return graphBuilder != null
                ? graphBuilder.getTestsForSymbol(projectId, fqn)
                : Map.of("error", "Code graph not available");
    }

    @Tool(name = "code_paths", description = "Trace execution paths from an entry point through CALLS " +
            "relations. Collects all reachable symbols layer by layer up to maxDepth. " +
            "Answers: 'what gets called when X runs?'")
    public Map<String, Object> codeCodePaths(
            @ToolParam(description = "FQN of the entry point to trace from") String fqn,
            @ToolParam(description = "Project ID (default: 'default')") String projectId,
            @ToolParam(description = "Maximum depth to trace (default: 5)") Integer maxDepth) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        if (fqn == null || fqn.isEmpty()) return Map.of("error", "fqn is required");
        if (maxDepth == null || maxDepth <= 0) maxDepth = 5;
        return graphBuilder != null
                ? graphBuilder.getCodePaths(projectId, fqn, maxDepth)
                : Map.of("error", "Code graph not available");
    }

    // ── Index all & delete project ──────────────────────────────────

    @Tool(name = "code_index_all", description = "Index all tracked directories for a project. " +
            "Equivalent to running code_index on every directory previously added via code_add_directory.")
    public Map<String, Object> codeIndexAll(
            @ToolParam(description = "Project ID (default: 'default')") String projectId) {

        if (projectId == null || projectId.isEmpty()) projectId = "default";
        List<CodebaseIndexer.IndexingStatus> results = indexer.indexAllDirectories(projectId);
        return Map.of(
                "directoriesIndexed", results.size(),
                "results", results.stream().map(CodebaseIndexer.IndexingStatus::toMap).collect(Collectors.toList())
        );
    }

    @Transactional
    @Tool(name = "code_delete_project", description = "Delete all indexed data for a project " +
            "including entities, relations, and directory records. This is irreversible.")
    public Map<String, Object> codeDeleteProject(
            @ToolParam(description = "Project ID to delete") String projectId) {

        if (projectId == null || projectId.isEmpty()) return Map.of("error", "projectId is required");

        long entityCount = entityRepository.countByProjectId(projectId);
        long relationCount = relationRepository.countByProjectId(projectId);
        entityRepository.deleteByProjectId(projectId);
        relationRepository.deleteByProjectId(projectId);
        directoryRepository.deleteByProjectId(projectId);
        return Map.of(
                "projectId", projectId,
                "entitiesDeleted", entityCount,
                "relationsDeleted", relationCount
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Map<String, Object> entityToMap(CodeEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", e.getName());
        map.put("type", e.getEntityType().name().toLowerCase());
        map.put("fqn", e.getFullyQualifiedName());
        map.put("file", e.getFilePath());
        if (e.getStartLine() != null) map.put("startLine", e.getStartLine());
        if (e.getEndLine() != null) map.put("endLine", e.getEndLine());
        if (e.getSignature() != null) map.put("signature", e.getSignature());
        if (e.getLanguage() != null) map.put("language", e.getLanguage());
        if (e.getDocComment() != null) map.put("docComment", truncate(e.getDocComment(), 300));
        if (e.getVisibility() != null) map.put("visibility", e.getVisibility());
        if (e.getParentFqn() != null) map.put("parentFqn", e.getParentFqn());
        return map;
    }

    private Map<String, Object> relationToMap(CodeRelation r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("relationType", r.getRelationType().name());
        map.put("sourceFqn", r.getSourceFqn());
        map.put("targetName", r.getTargetName());
        if (r.getTargetFqn() != null) map.put("targetFqn", r.getTargetFqn());
        if (r.getFilePath() != null) map.put("file", r.getFilePath());
        if (r.getLine() != null) map.put("line", r.getLine());
        return map;
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
