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
import ai.kompile.codeindexer.service.CodeSearchService;
import ai.kompile.codeindexer.service.CodebaseIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for managing code projects as fact sheets.
 * Provides CRUD operations, indexing triggers, and project summaries.
 */
@RestController
@RequestMapping("/api/code-projects")
public class CodeProjectController {

    private static final Logger log = LoggerFactory.getLogger(CodeProjectController.class);

    private final CodeProjectRepository projectRepository;
    private final IndexedDirectoryRepository directoryRepository;
    private final CodeEntityRepository entityRepository;
    private final CodeRelationRepository relationRepository;
    private final FileFingerprintRepository fingerprintRepository;
    private final CodebaseIndexer indexer;
    private final CodeSearchService searchService;

    @Autowired
    public CodeProjectController(CodeProjectRepository projectRepository,
                                 IndexedDirectoryRepository directoryRepository,
                                 CodeEntityRepository entityRepository,
                                 CodeRelationRepository relationRepository,
                                 FileFingerprintRepository fingerprintRepository,
                                 CodebaseIndexer indexer,
                                 CodeSearchService searchService) {
        this.projectRepository = projectRepository;
        this.directoryRepository = directoryRepository;
        this.entityRepository = entityRepository;
        this.relationRepository = relationRepository;
        this.fingerprintRepository = fingerprintRepository;
        this.indexer = indexer;
        this.searchService = searchService;
    }

    // ── CRUD ─────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listProjects() {
        List<CodeProject> projects = projectRepository.findAll();
        List<Map<String, Object>> result = projects.stream()
                .map(this::projectToSummary)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<?> getProject(@PathVariable String projectId) {
        return projectRepository.findByProjectId(projectId)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(projectToDetail(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createProject(@RequestBody Map<String, Object> request) {
        String projectId = (String) request.get("projectId");
        String name = (String) request.get("name");
        if (projectId == null || projectId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectId is required"));
        }
        if (name == null || name.isBlank()) {
            name = projectId;
        }

        if (projectRepository.existsByProjectId(projectId)) {
            return ResponseEntity.status(409).body(Map.of("error", "Project already exists: " + projectId));
        }

        CodeProject project = CodeProject.builder()
                .projectId(projectId)
                .name(name)
                .description((String) request.get("description"))
                .color((String) request.getOrDefault("color", "#4caf50"))
                .icon((String) request.getOrDefault("icon", "code"))
                .tags((String) request.get("tags"))
                .includePatterns((String) request.get("includePatterns"))
                .excludePatterns((String) request.get("excludePatterns"))
                .autoIndex(Boolean.TRUE.equals(request.get("autoIndex")))
                .build();

        project = projectRepository.save(project);

        // If directories are provided, add them
        @SuppressWarnings("unchecked")
        List<Map<String, String>> directories = (List<Map<String, String>>) request.get("directories");
        if (directories != null) {
            for (Map<String, String> dirEntry : directories) {
                String path = dirEntry.get("path");
                if (path != null && !path.isBlank()) {
                    try {
                        indexer.addDirectory(projectId, path,
                                dirEntry.get("displayName"),
                                dirEntry.get("includePatterns"),
                                dirEntry.get("excludePatterns"),
                                null,
                                dirEntry.get("description"),
                                dirEntry.get("tags"));
                    } catch (Exception e) {
                        log.warn("Failed to add directory {}: {}", path, e.getMessage());
                    }
                }
            }
        }

        return ResponseEntity.ok(projectToDetail(project));
    }

    @PutMapping("/{projectId}")
    @Transactional
    public ResponseEntity<?> updateProject(@PathVariable String projectId, @RequestBody Map<String, Object> request) {
        return projectRepository.findByProjectId(projectId)
                .<ResponseEntity<?>>map(project -> {
                    if (request.containsKey("name")) project.setName((String) request.get("name"));
                    if (request.containsKey("description")) project.setDescription((String) request.get("description"));
                    if (request.containsKey("color")) project.setColor((String) request.get("color"));
                    if (request.containsKey("icon")) project.setIcon((String) request.get("icon"));
                    if (request.containsKey("tags")) project.setTags((String) request.get("tags"));
                    if (request.containsKey("autoIndex")) project.setAutoIndex(Boolean.TRUE.equals(request.get("autoIndex")));
                    if (request.containsKey("includePatterns")) project.setIncludePatterns((String) request.get("includePatterns"));
                    if (request.containsKey("excludePatterns")) project.setExcludePatterns((String) request.get("excludePatterns"));
                    projectRepository.save(project);
                    return ResponseEntity.ok(projectToDetail(project));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{projectId}")
    @Transactional
    public ResponseEntity<?> deleteProject(@PathVariable String projectId) {
        return projectRepository.findByProjectId(projectId)
                .<ResponseEntity<?>>map(project -> {
                    entityRepository.deleteByProjectId(projectId);
                    relationRepository.deleteByProjectId(projectId);
                    fingerprintRepository.deleteByProjectId(projectId);
                    directoryRepository.deleteByProjectId(projectId);
                    projectRepository.delete(project);
                    return ResponseEntity.ok(Map.of("deleted", projectId));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Auto-registration (idempotent) ────────────────────────────

    /**
     * Idempotent endpoint for CLI auto-registration.
     * Creates or updates a code project for the given directory.
     * If a project already exists for the directory, it is returned as-is.
     */
    @PutMapping("/auto-register")
    @Transactional
    public ResponseEntity<?> autoRegister(@RequestBody Map<String, Object> request) {
        String directory = (String) request.get("directory");
        if (directory == null || directory.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "directory is required"));
        }

        String projectId = (String) request.get("projectId");
        if (projectId == null || projectId.isBlank()) {
            // Derive projectId from directory name
            projectId = java.nio.file.Paths.get(directory).getFileName().toString();
        }

        String name = (String) request.get("name");
        if (name == null || name.isBlank()) {
            name = projectId;
        }

        // Check if a project with this projectId already exists
        Optional<CodeProject> existing = projectRepository.findByProjectId(projectId);
        if (existing.isPresent()) {
            CodeProject project = existing.get();
            // Ensure the directory is registered
            ensureDirectoryRegistered(project.getProjectId(), directory);
            return ResponseEntity.ok(projectToDetail(project));
        }

        // Create new project
        CodeProject project = CodeProject.builder()
                .projectId(projectId)
                .name(name)
                .description((String) request.get("description"))
                .color("#4caf50")
                .icon("code")
                .autoIndex(Boolean.TRUE.equals(request.get("autoIndex")))
                .build();
        project = projectRepository.save(project);

        // Register directory
        ensureDirectoryRegistered(projectId, directory);

        log.info("Auto-registered code project '{}' for directory: {}", projectId, directory);
        return ResponseEntity.ok(projectToDetail(project));
    }

    private void ensureDirectoryRegistered(String projectId, String directory) {
        List<IndexedDirectory> dirs = directoryRepository.findByProjectId(projectId);
        boolean alreadyHasDir = dirs.stream()
                .anyMatch(d -> directory.equals(d.getAbsolutePath()));
        if (!alreadyHasDir) {
            try {
                indexer.addDirectory(projectId, directory, null, null, null, null, null, null);
            } catch (Exception e) {
                log.warn("Failed to add directory {} to project {}: {}", directory, projectId, e.getMessage());
            }
        }
    }

    // ── Activation ──────────────────────────────────────────────────

    @PostMapping("/{projectId}/activate")
    @Transactional
    public ResponseEntity<?> activateProject(@PathVariable String projectId) {
        return projectRepository.findByProjectId(projectId)
                .<ResponseEntity<?>>map(project -> {
                    // Deactivate all others
                    projectRepository.findByIsActiveTrue().forEach(p -> {
                        p.setIsActive(false);
                        projectRepository.save(p);
                    });
                    project.setIsActive(true);
                    projectRepository.save(project);
                    return ResponseEntity.ok(projectToSummary(project));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/active")
    public ResponseEntity<?> getActiveProject() {
        List<CodeProject> active = projectRepository.findByIsActiveTrue();
        if (active.isEmpty()) {
            return ResponseEntity.ok(Map.of("active", false));
        }
        return ResponseEntity.ok(projectToDetail(active.get(0)));
    }

    // ── Indexing ─────────────────────────────────────────────────────

    @PostMapping("/{projectId}/index")
    public ResponseEntity<?> indexProject(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean forceReindex) {
        Optional<CodeProject> projectOpt = projectRepository.findByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        if (indexer.isIndexing(projectId)) {
            return ResponseEntity.status(409).body(Map.of("error", "Indexing already in progress"));
        }

        CodeProject project = projectOpt.get();
        project.setIndexState(CodeProject.ProjectIndexState.INDEXING);
        projectRepository.save(project);

        // Trigger async index
        List<IndexedDirectory> dirs = directoryRepository.findByProjectId(projectId);
        if (dirs.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No directories configured for project"));
        }

        // Index first directory async, then update project stats
        indexer.indexDirectoryAsync(projectId, dirs.get(0).getAbsolutePath(), forceReindex)
                .thenAccept(status -> updateProjectStats(projectId, status));

        return ResponseEntity.accepted().body(Map.of(
                "projectId", projectId,
                "mode", forceReindex ? "full" : "incremental",
                "directories", dirs.size(),
                "message", "Indexing started. Subscribe to /topic/code-index/" + projectId + " for progress."
        ));
    }

    @GetMapping("/{projectId}/index-status")
    public ResponseEntity<?> getIndexStatus(@PathVariable String projectId) {
        CodebaseIndexer.IndexingStatus status = indexer.getStatus(projectId);
        if (status == null) {
            return projectRepository.findByProjectId(projectId)
                    .<ResponseEntity<?>>map(p -> ResponseEntity.ok(Map.of(
                            "projectId", projectId,
                            "indexState", p.getIndexState().name(),
                            "lastIndexedAt", p.getLastIndexedAt() != null ? p.getLastIndexedAt().toString() : "never"
                    )))
                    .orElse(ResponseEntity.notFound().build());
        }
        return ResponseEntity.ok(status.toMap());
    }

    // ── Fact Sheet Summary ──────────────────────────────────────────

    @GetMapping("/{projectId}/fact-sheet")
    public ResponseEntity<?> getFactSheet(@PathVariable String projectId) {
        return projectRepository.findByProjectId(projectId)
                .<ResponseEntity<?>>map(project -> {
                    Map<String, Object> factSheet = new LinkedHashMap<>();
                    factSheet.put("projectId", projectId);
                    factSheet.put("name", project.getName());
                    factSheet.put("description", project.getDescription());
                    factSheet.put("indexState", project.getIndexState().name());
                    factSheet.put("lastIndexedAt", project.getLastIndexedAt() != null ? project.getLastIndexedAt().toString() : null);

                    // Stats
                    Map<String, Object> stats = searchService.getStatistics(projectId);
                    factSheet.put("statistics", stats);

                    // Directories
                    List<IndexedDirectory> dirs = directoryRepository.findByProjectId(projectId);
                    factSheet.put("directories", dirs.stream().map(d -> Map.of(
                            "path", d.getAbsolutePath(),
                            "displayName", d.getDisplayName() != null ? d.getDisplayName() : d.getAbsolutePath(),
                            "status", d.getStatus().name(),
                            "filesIndexed", d.getFilesIndexed(),
                            "entitiesFound", d.getEntitiesFound()
                    )).collect(Collectors.toList()));

                    // Languages breakdown
                    List<String> languages = entityRepository.findDistinctLanguagesByProjectId(projectId);
                    factSheet.put("languages", languages);

                    // Entity type breakdown
                    Map<String, Long> entityBreakdown = new LinkedHashMap<>();
                    for (CodeEntityType type : CodeEntityType.values()) {
                        long count = entityRepository.countByProjectIdAndEntityType(projectId, type);
                        if (count > 0) entityBreakdown.put(type.name(), count);
                    }
                    factSheet.put("entityBreakdown", entityBreakdown);

                    return ResponseEntity.ok(factSheet);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void updateProjectStats(String projectId, CodebaseIndexer.IndexingStatus status) {
        projectRepository.findByProjectId(projectId).ifPresent(project -> {
            project.setTotalFiles(status.filesProcessed().get());
            project.setTotalEntities(status.entitiesFound().get());
            project.setTotalRelations(status.relationsCreated().get());
            project.setTotalErrors(status.errors().get());
            project.setLastIndexedAt(Instant.now());
            project.setIndexState(status.errorMessage() != null
                    ? CodeProject.ProjectIndexState.FAILED
                    : CodeProject.ProjectIndexState.INDEXED);

            // Update languages
            List<String> languages = entityRepository.findDistinctLanguagesByProjectId(projectId);
            project.setLanguages(String.join(",", languages));

            projectRepository.save(project);
        });
    }

    private Map<String, Object> projectToSummary(CodeProject p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId().toString());
        map.put("projectId", p.getProjectId());
        map.put("name", p.getName());
        map.put("description", p.getDescription());
        map.put("isActive", p.getIsActive());
        map.put("color", p.getColor());
        map.put("icon", p.getIcon());
        map.put("indexState", p.getIndexState().name());
        map.put("totalFiles", p.getTotalFiles());
        map.put("totalEntities", p.getTotalEntities());
        map.put("totalRelations", p.getTotalRelations());
        map.put("languages", p.getLanguages());
        map.put("lastIndexedAt", p.getLastIndexedAt() != null ? p.getLastIndexedAt().toString() : null);
        map.put("tags", p.getTags());
        map.put("autoIndex", p.getAutoIndex());

        // Directory count
        long dirCount = directoryRepository.countByProjectId(p.getProjectId());
        map.put("directoryCount", dirCount);

        return map;
    }

    private Map<String, Object> projectToDetail(CodeProject p) {
        Map<String, Object> map = projectToSummary(p);

        // Add directories
        List<IndexedDirectory> dirs = directoryRepository.findByProjectId(p.getProjectId());
        map.put("directories", dirs.stream().map(d -> {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", d.getId().toString());
            dm.put("absolutePath", d.getAbsolutePath());
            dm.put("displayName", d.getDisplayName());
            dm.put("status", d.getStatus().name());
            dm.put("filesIndexed", d.getFilesIndexed());
            dm.put("entitiesFound", d.getEntitiesFound());
            dm.put("relationsCreated", d.getRelationsCreated());
            dm.put("errors", d.getErrors());
            dm.put("lastIndexedAt", d.getLastIndexedAt() != null ? d.getLastIndexedAt().toString() : null);
            dm.put("includePatterns", d.getIncludePatterns());
            dm.put("excludePatterns", d.getExcludePatterns());
            dm.put("description", d.getDescription());
            dm.put("tags", d.getTags());
            return dm;
        }).collect(Collectors.toList()));

        // Indexing status if active
        CodebaseIndexer.IndexingStatus status = indexer.getStatus(p.getProjectId());
        if (status != null) {
            map.put("currentIndexingStatus", status.toMap());
        }

        return map;
    }
}
