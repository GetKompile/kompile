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

package ai.kompile.codeindexer.controller;

import ai.kompile.codeindexer.domain.*;
import ai.kompile.codeindexer.service.CodeGraphBuilder;
import ai.kompile.codeindexer.service.CodeSearchService;
import ai.kompile.codeindexer.service.CodebaseIndexer;
import ai.kompile.codeindexer.service.LanguageRegistry;
import ai.kompile.codeindexer.domain.FileFingerprintRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/code-indexer")
public class CodeIndexerController {

    private final CodebaseIndexer indexer;
    private final CodeSearchService searchService;
    private final LanguageRegistry languageRegistry;
    private final CodeGraphBuilder graphBuilder;
    private final CodeEntityRepository entityRepository;
    private final CodeRelationRepository relationRepository;
    private final IndexedDirectoryRepository directoryRepository;
    private final FileFingerprintRepository fingerprintRepository;

    @Autowired
    public CodeIndexerController(CodebaseIndexer indexer,
                                 CodeSearchService searchService,
                                 LanguageRegistry languageRegistry,
                                 @Autowired(required = false) CodeGraphBuilder graphBuilder,
                                 CodeEntityRepository entityRepository,
                                 CodeRelationRepository relationRepository,
                                 IndexedDirectoryRepository directoryRepository,
                                 FileFingerprintRepository fingerprintRepository) {
        this.indexer = indexer;
        this.searchService = searchService;
        this.languageRegistry = languageRegistry;
        this.graphBuilder = graphBuilder;
        this.entityRepository = entityRepository;
        this.relationRepository = relationRepository;
        this.directoryRepository = directoryRepository;
        this.fingerprintRepository = fingerprintRepository;
    }

    // ── Directory management ─────────────────────────────────────────

    @PostMapping("/directories")
    public ResponseEntity<?> addDirectory(@RequestBody Map<String, Object> request) {
        String projectId = (String) request.getOrDefault("projectId", "default");
        String path = (String) request.get("path");
        if (path == null || path.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "path is required"));
        }

        String displayName = (String) request.get("displayName");
        String includePatterns = (String) request.get("includePatterns");
        String excludePatterns = (String) request.get("excludePatterns");
        String description = (String) request.get("description");
        String tags = (String) request.get("tags");
        @SuppressWarnings("unchecked")
        Map<String, String> languageOverrides = (Map<String, String>) request.get("languageOverrides");

        try {
            IndexedDirectory dir = indexer.addDirectory(projectId, path, displayName,
                    includePatterns, excludePatterns, languageOverrides, description, tags);
            return ResponseEntity.ok(dir);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/directories")
    public ResponseEntity<?> removeDirectory(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String path) {
        indexer.removeDirectory(projectId, path);
        return ResponseEntity.ok(Map.of("removed", path));
    }

    @GetMapping("/directories")
    public ResponseEntity<List<IndexedDirectory>> listDirectories(
            @RequestParam(defaultValue = "default") String projectId) {
        return ResponseEntity.ok(indexer.listDirectories(projectId));
    }

    // ── Indexing ─────────────────────────────────────────────────────

    /**
     * Synchronous index (incremental by default).
     * Pass "forceReindex": true in the body to wipe and rebuild.
     */
    @PostMapping("/index")
    public ResponseEntity<?> indexCodebase(@RequestBody Map<String, Object> request) {
        String projectId = (String) request.getOrDefault("projectId", "default");
        String rootPath = (String) request.get("rootPath");
        boolean forceReindex = Boolean.TRUE.equals(request.get("forceReindex"));
        if (rootPath == null || rootPath.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "rootPath is required"));
        }

        if (indexer.isIndexing(projectId)) {
            return ResponseEntity.status(409).body(Map.of("error", "Indexing already in progress for project: " + projectId));
        }

        try {
            CodebaseIndexer.IndexingStatus status = indexer.indexDirectory(projectId, rootPath, forceReindex);
            return ResponseEntity.ok(status.toMap());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Async index: returns immediately. Listen on WebSocket /topic/code-index/{projectId} for progress.
     */
    @PostMapping("/index-async")
    public ResponseEntity<?> indexCodebaseAsync(@RequestBody Map<String, Object> request) {
        String projectId = (String) request.getOrDefault("projectId", "default");
        String rootPath = (String) request.get("rootPath");
        boolean forceReindex = Boolean.TRUE.equals(request.get("forceReindex"));
        if (rootPath == null || rootPath.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "rootPath is required"));
        }

        if (indexer.isIndexing(projectId)) {
            return ResponseEntity.status(409).body(Map.of("error", "Indexing already in progress for project: " + projectId));
        }

        indexer.indexDirectoryAsync(projectId, rootPath, forceReindex);
        return ResponseEntity.accepted().body(Map.of(
                "projectId", projectId,
                "rootPath", rootPath,
                "mode", forceReindex ? "full" : "incremental",
                "message", "Indexing started. Subscribe to /topic/code-index/" + projectId + " for progress."
        ));
    }

    @PostMapping("/index-all")
    public ResponseEntity<?> indexAll(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam(defaultValue = "false") boolean forceReindex) {
        List<CodebaseIndexer.IndexingStatus> results = indexer.indexAllDirectories(projectId, forceReindex);
        return ResponseEntity.ok(results.stream().map(CodebaseIndexer.IndexingStatus::toMap).toList());
    }

    // ── Search ───────────────────────────────────────────────────────

    @GetMapping("/search")
    public ResponseEntity<List<CodeEntity>> search(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String query,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "20") int maxResults) {

        List<CodeEntity> results;
        if (type != null && !type.isEmpty()) {
            CodeEntityType entityType = CodeEntityType.valueOf(type.toUpperCase());
            results = searchService.searchByType(projectId, query, entityType, maxResults);
        } else {
            results = searchService.search(projectId, query, maxResults);
        }
        return ResponseEntity.ok(results);
    }

    @GetMapping("/entities")
    public ResponseEntity<?> getEntities(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam(required = false) String file,
            @RequestParam(required = false) String parentFqn) {

        if (file != null) {
            return ResponseEntity.ok(searchService.getEntitiesInFile(projectId, file));
        }
        if (parentFqn != null) {
            return ResponseEntity.ok(searchService.getChildren(projectId, parentFqn));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "file or parentFqn required"));
    }

    @GetMapping("/related")
    public ResponseEntity<List<CodeEntity>> findRelated(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String fqn,
            @RequestParam(defaultValue = "2") int maxDepth) {
        return ResponseEntity.ok(searchService.findRelated(projectId, fqn, maxDepth));
    }

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @RequestParam(defaultValue = "default") String projectId) {
        return ResponseEntity.ok(searchService.getStatistics(projectId));
    }

    @GetMapping("/status/{projectId}")
    public ResponseEntity<?> getStatus(@PathVariable String projectId) {
        CodebaseIndexer.IndexingStatus status = indexer.getStatus(projectId);
        if (status == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(status.toMap());
    }

    // ── Unused function detection ──────────────────────────────────────

    @GetMapping("/unused-functions")
    public ResponseEntity<?> findUnusedFunctions(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) String language,
            @RequestParam(defaultValue = "false") boolean includeTests,
            @RequestParam(defaultValue = "50") int maxResults) {
        List<CodeEntity> unused = searchService.findUnusedFunctions(
                projectId, visibility, language, includeTests, maxResults);
        return ResponseEntity.ok(Map.of(
                "unusedCount", unused.size(),
                "results", unused
        ));
    }

    // ── Callers and relations ──────────────────────────────────────

    @GetMapping("/callers")
    public ResponseEntity<?> findCallers(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String name) {
        List<CodeRelation> callers = searchService.findCallersOf(projectId, name);
        return ResponseEntity.ok(Map.of(
                "functionName", name,
                "callerCount", callers.size(),
                "callers", callers
        ));
    }

    @GetMapping("/relations")
    public ResponseEntity<?> findRelations(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String fqn,
            @RequestParam(required = false) String type) {
        List<CodeRelation> relations;
        if (type != null && !type.isEmpty()) {
            try {
                CodeRelationType relType = CodeRelationType.valueOf(type.toUpperCase());
                relations = relationRepository.findByProjectIdAndSourceFqnAndRelationType(projectId, fqn, relType);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid relation type: " + type));
            }
        } else {
            relations = relationRepository.findByProjectIdAndSourceFqn(projectId, fqn);
        }
        return ResponseEntity.ok(Map.of(
                "fqn", fqn,
                "relationType", type != null ? type : "ALL",
                "count", relations.size(),
                "relations", relations
        ));
    }

    // ── Entity lookup ────────────────────────────────────────────────

    @GetMapping("/entity")
    public ResponseEntity<?> getEntityByFqn(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String fqn) {
        return entityRepository.findByProjectIdAndFullyQualifiedName(projectId, fqn)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/entities-by-language")
    public ResponseEntity<?> getEntitiesByLanguage(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String language) {
        List<CodeEntity> entities = entityRepository.findByProjectIdAndLanguage(projectId, language);
        return ResponseEntity.ok(Map.of(
                "language", language,
                "count", entities.size(),
                "entities", entities
        ));
    }

    @GetMapping("/project-languages")
    public ResponseEntity<?> getProjectLanguages(
            @RequestParam(defaultValue = "default") String projectId) {
        List<String> languages = entityRepository.findDistinctLanguagesByProjectId(projectId);
        return ResponseEntity.ok(Map.of(
                "projectId", projectId,
                "languages", languages
        ));
    }

    // ── Reverse relation lookup ─────────────────────────────────────

    @GetMapping("/reverse-relations")
    public ResponseEntity<?> findReverseRelations(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String fqn,
            @RequestParam(required = false) String type) {
        List<CodeRelation> relations;
        if (type != null && !type.isEmpty()) {
            try {
                CodeRelationType relType = CodeRelationType.valueOf(type.toUpperCase());
                relations = relationRepository.findByProjectIdAndTargetFqnAndRelationType(projectId, fqn, relType);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid relation type: " + type));
            }
        } else {
            relations = relationRepository.findByProjectIdAndTargetFqn(projectId, fqn);
        }
        return ResponseEntity.ok(Map.of(
                "targetFqn", fqn,
                "relationType", type != null ? type : "ALL",
                "count", relations.size(),
                "relations", relations
        ));
    }

    // ── Project cleanup ─────────────────────────────────────────────

    @DeleteMapping("/project")
    @Transactional
    public ResponseEntity<?> deleteProject(
            @RequestParam(defaultValue = "default") String projectId) {
        long entityCount = entityRepository.countByProjectId(projectId);
        long relationCount = relationRepository.countByProjectId(projectId);
        long fingerprintCount = fingerprintRepository.countByProjectId(projectId);
        entityRepository.deleteByProjectId(projectId);
        relationRepository.deleteByProjectId(projectId);
        fingerprintRepository.deleteByProjectId(projectId);
        directoryRepository.deleteByProjectId(projectId);
        return ResponseEntity.ok(Map.of(
                "projectId", projectId,
                "entitiesDeleted", entityCount,
                "relationsDeleted", relationCount,
                "fingerprintsDeleted", fingerprintCount
        ));
    }

    // ── Language configuration ───────────────────────────────────────

    @GetMapping("/languages")
    public ResponseEntity<?> getLanguages() {
        return ResponseEntity.ok(Map.of(
                "supported", languageRegistry.getSupportedLanguages(),
                "extensions", languageRegistry.getDefaultExtensions(),
                "fileOverrides", languageRegistry.getFileOverrides(),
                "patternOverrides", languageRegistry.getPatternOverrides()
        ));
    }

    @PostMapping("/languages/override")
    public ResponseEntity<?> setLanguageOverride(@RequestBody Map<String, String> request) {
        String pattern = request.get("pattern");
        String file = request.get("file");
        String language = request.get("language");

        if (language == null || language.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "language is required"));
        }

        if (pattern != null && !pattern.isEmpty()) {
            languageRegistry.setPatternLanguage(pattern, language);
            return ResponseEntity.ok(Map.of("pattern", pattern, "language", language));
        }
        if (file != null && !file.isEmpty()) {
            languageRegistry.setFileLanguage(file, language);
            return ResponseEntity.ok(Map.of("file", file, "language", language));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "pattern or file is required"));
    }

    // ── Graph building ──────────────────────────────────────────────

    @PostMapping("/graph/build")
    public ResponseEntity<?> buildGraph(@RequestBody Map<String, Object> request) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        String projectId = (String) request.getOrDefault("projectId", "default");
        String directoryPath = (String) request.get("directoryPath");
        if (directoryPath == null || directoryPath.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "directoryPath is required"));
        }
        return ResponseEntity.ok(graphBuilder.buildGraph(projectId, directoryPath));
    }

    @PostMapping("/graph/build-multi")
    public ResponseEntity<?> buildGraphMulti(@RequestBody Map<String, Object> request) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        String projectId = (String) request.getOrDefault("projectId", "default");
        @SuppressWarnings("unchecked")
        List<String> paths = (List<String>) request.get("paths");
        if (paths == null || paths.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "paths is required"));
        }
        return ResponseEntity.ok(graphBuilder.buildGraphFromMultipleDirectories(projectId, paths));
    }

    @PostMapping("/graph/ensure-connectivity")
    public ResponseEntity<?> ensureConnectivity(
            @RequestParam(defaultValue = "default") String projectId) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        int added = graphBuilder.ensureConnectivity(projectId);
        return ResponseEntity.ok(Map.of("edgesAdded", added));
    }

    @GetMapping("/graph/visualization")
    public ResponseEntity<?> getGraphVisualization(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam(defaultValue = "200") int maxNodes) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        return ResponseEntity.ok(graphBuilder.getGraphVisualization(projectId, maxNodes));
    }

    @GetMapping("/graph/search")
    public ResponseEntity<?> searchGraph(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String query,
            @RequestParam(defaultValue = "20") int maxResults) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        return ResponseEntity.ok(graphBuilder.searchGraph(projectId, query, maxResults));
    }

    @GetMapping("/graph/symbol")
    public ResponseEntity<?> getSymbolGraph(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String fqn,
            @RequestParam(defaultValue = "2") int depth) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        return ResponseEntity.ok(graphBuilder.getSymbolGraph(projectId, fqn, depth));
    }

    @GetMapping("/graph/file")
    public ResponseEntity<?> getFileGraph(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String filePath) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        return ResponseEntity.ok(graphBuilder.getFileGraph(projectId, filePath));
    }

    @GetMapping("/graph/statistics")
    public ResponseEntity<?> getGraphStatistics(
            @RequestParam(defaultValue = "default") String projectId) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        return ResponseEntity.ok(graphBuilder.getStatistics(projectId));
    }

    @GetMapping("/graph/shortest-path")
    public ResponseEntity<?> getShortestPath(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String edgeType) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        Map<String, Object> result = graphBuilder.getShortestPath(projectId, from, to, edgeType);
        if (result.containsKey("error")) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    // ── Composite graph queries ────────────────────────────────────

    @GetMapping("/graph/impact-analysis")
    public ResponseEntity<?> getImpactAnalysis(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String fqn) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        return ResponseEntity.ok(graphBuilder.getImpactAnalysis(projectId, fqn));
    }

    @GetMapping("/graph/dependency-tree")
    public ResponseEntity<?> getDependencyTree(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String fqn,
            @RequestParam(defaultValue = "3") int maxDepth) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        return ResponseEntity.ok(graphBuilder.getDependencyTree(projectId, fqn, maxDepth));
    }

    @GetMapping("/graph/component-map")
    public ResponseEntity<?> getComponentMap(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String scope) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        return ResponseEntity.ok(graphBuilder.getComponentMap(projectId, scope));
    }

    @GetMapping("/graph/symbol-dossier")
    public ResponseEntity<?> getSymbolDossier(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String fqn) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        Map<String, Object> result = graphBuilder.getSymbolDossier(projectId, fqn);
        if (Boolean.FALSE.equals(result.get("found"))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/graph/export-local")
    public ResponseEntity<?> exportLocalizedGraph(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String focus,
            @RequestParam(defaultValue = "svg") String format,
            @RequestParam(defaultValue = "2") int depth) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        try {
            Map<String, Object> result = graphBuilder.exportLocalizedGraph(
                    projectId, focus, format, depth);
            byte[] data = (byte[]) result.get("data");
            String contentType = (String) result.get("contentType");
            String filename = (String) result.get("filename");

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
            return new ResponseEntity<>(data, headers, 200);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Test coverage & code paths ──────────────────────────────────

    @GetMapping("/graph/test-frameworks")
    public ResponseEntity<?> getTestFrameworks(
            @RequestParam(defaultValue = "default") String projectId) {
        return ResponseEntity.ok(graphBuilder != null
                ? graphBuilder.getTestFrameworks(projectId)
                : Map.of("error", "Code graph not available"));
    }

    @GetMapping("/graph/test-coverage")
    public ResponseEntity<?> getTestCoverage(
            @RequestParam(defaultValue = "default") String projectId) {
        return ResponseEntity.ok(graphBuilder != null
                ? graphBuilder.getTestCoverage(projectId)
                : Map.of("error", "Code graph not available"));
    }

    @GetMapping("/graph/tests-for-symbol")
    public ResponseEntity<?> getTestsForSymbol(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String fqn) {
        return ResponseEntity.ok(graphBuilder != null
                ? graphBuilder.getTestsForSymbol(projectId, fqn)
                : Map.of("error", "Code graph not available"));
    }

    @GetMapping("/graph/code-paths")
    public ResponseEntity<?> getCodePaths(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String fqn,
            @RequestParam(defaultValue = "5") int maxDepth) {
        return ResponseEntity.ok(graphBuilder != null
                ? graphBuilder.getCodePaths(projectId, fqn, maxDepth)
                : Map.of("error", "Code graph not available"));
    }

    @GetMapping("/graph/export")
    public ResponseEntity<?> exportGraph(
            @RequestParam(defaultValue = "default") String projectId,
            @RequestParam String format,
            @RequestParam(defaultValue = "500") int maxNodes) {
        if (graphBuilder == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Knowledge graph not available"));
        }
        try {
            Map<String, Object> result = graphBuilder.exportGraph(projectId, format, maxNodes);
            byte[] data = (byte[]) result.get("data");
            String contentType = (String) result.get("contentType");
            String filename = (String) result.get("filename");

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
            return new ResponseEntity<>(data, headers, 200);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
