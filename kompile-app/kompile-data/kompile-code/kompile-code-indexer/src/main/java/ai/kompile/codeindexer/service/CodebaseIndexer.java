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

package ai.kompile.codeindexer.service;

import ai.kompile.codeindexer.domain.*;
import ai.kompile.codeindexer.domain.IndexedDirectory.IndexStatus;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Walks a directory tree, extracts code entities, stores them in the database
 * and as knowledge graph nodes/edges for semantic search.
 * Manages tracked directories per project.
 */
@Service
public class CodebaseIndexer {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected CodebaseIndexer() {}


    private static final Logger log = LoggerFactory.getLogger(CodebaseIndexer.class);
    private static final int BUFFER_SIZE = 8192; // SHA-256 read buffer

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "__pycache__", ".gradle",
            "target", "build", "dist", "out", ".idea", ".vscode", ".settings",
            "vendor", ".tox", ".mypy_cache", ".pytest_cache", ".angular",
            ".next", ".nuxt", "coverage", ".cache", "bin", "obj"
    );

    private CodeEntityExtractor extractor;
    private CodeEntityRepository entityRepository;
    private CodeRelationRepository relationRepository;
    private IndexedDirectoryRepository directoryRepository;
    private FileFingerprintRepository fingerprintRepository;
    private KnowledgeGraphService knowledgeGraphService;
    private SimpMessagingTemplate messagingTemplate;
    private ObjectMapper objectMapper;

    private final Map<String, IndexingStatus> activeJobs = new ConcurrentHashMap<>();

    public record IndexingStatus(
            String projectId,
            String rootPath,
            Instant startedAt,
            AtomicInteger totalFiles,
            AtomicInteger filesProcessed,
            AtomicInteger filesSkipped,
            AtomicInteger entitiesFound,
            AtomicInteger relationsCreated,
            AtomicInteger errors,
            AtomicBoolean completed,
            String errorMessage,
            boolean incremental
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("projectId", projectId);
            map.put("rootPath", rootPath);
            map.put("startedAt", startedAt.toString());
            map.put("totalFiles", totalFiles.get());
            map.put("filesProcessed", filesProcessed.get());
            map.put("filesSkipped", filesSkipped.get());
            map.put("entitiesFound", entitiesFound.get());
            map.put("relationsCreated", relationsCreated.get());
            map.put("errors", errors.get());
            map.put("completed", completed.get());
            map.put("incremental", incremental);
            if (errorMessage != null) map.put("errorMessage", errorMessage);
            int total = totalFiles.get();
            int processed = filesProcessed.get() + filesSkipped.get();
            map.put("progressPercent", total > 0 ? (int) ((processed * 100L) / total) : 0);
            return map;
        }
    }

    @Autowired
    public CodebaseIndexer(CodeEntityExtractor extractor,
                           CodeEntityRepository entityRepository,
                           CodeRelationRepository relationRepository,
                           IndexedDirectoryRepository directoryRepository,
                           FileFingerprintRepository fingerprintRepository,
                           @Autowired(required = false) KnowledgeGraphService knowledgeGraphService,
                           @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
                           ObjectMapper objectMapper) {
        this.extractor = extractor;
        this.entityRepository = entityRepository;
        this.relationRepository = relationRepository;
        this.directoryRepository = directoryRepository;
        this.fingerprintRepository = fingerprintRepository;
        this.knowledgeGraphService = knowledgeGraphService;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    // ── Directory management ──────────────────────────────────────────

    /**
     * Add a directory for indexing under a project.
     */
    @Transactional
    public IndexedDirectory addDirectory(String projectId, String absolutePath,
                                         String displayName,
                                         String includePatterns,
                                         String excludePatterns,
                                         Map<String, String> languageOverrides) {
        return addDirectory(projectId, absolutePath, displayName,
                includePatterns, excludePatterns, languageOverrides, null, null);
    }

    public IndexedDirectory addDirectory(String projectId, String absolutePath,
                                         String displayName,
                                         String includePatterns,
                                         String excludePatterns,
                                         Map<String, String> languageOverrides,
                                         String description,
                                         String tags) {
        Path path = Paths.get(absolutePath).toAbsolutePath();
        if (!path.toFile().isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + absolutePath);
        }

        Optional<IndexedDirectory> existing = directoryRepository
                .findByProjectIdAndAbsolutePath(projectId, path.toString());
        if (existing.isPresent()) {
            IndexedDirectory dir = existing.get();
            dir.setDisplayName(displayName != null ? displayName : dir.getDisplayName());
            dir.setIncludePatterns(includePatterns);
            dir.setExcludePatterns(excludePatterns);
            if (description != null) dir.setDescription(description);
            if (tags != null) dir.setTags(tags);
            if (languageOverrides != null) {
                try {
                    dir.setLanguageOverridesJson(objectMapper.writeValueAsString(languageOverrides));
                } catch (Exception e) {
                    log.warn("Failed to serialize language overrides", e);
                }
            }
            return directoryRepository.save(dir);
        }

        IndexedDirectory dir = IndexedDirectory.builder()
                .projectId(projectId)
                .absolutePath(path.toString())
                .displayName(displayName != null ? displayName : path.getFileName().toString())
                .status(IndexStatus.PENDING)
                .includePatterns(includePatterns)
                .excludePatterns(excludePatterns)
                .description(description)
                .tags(tags)
                .build();

        if (languageOverrides != null) {
            try {
                dir.setLanguageOverridesJson(objectMapper.writeValueAsString(languageOverrides));
            } catch (Exception e) {
                log.warn("Failed to serialize language overrides", e);
            }
        }

        return directoryRepository.save(dir);
    }

    /**
     * Remove a tracked directory and its indexed entities.
     */
    @Transactional
    public void removeDirectory(String projectId, String absolutePath) {
        Path path = Paths.get(absolutePath).toAbsolutePath();
        // Delete entities whose filePath is relative to this directory
        List<CodeEntity> entities = entityRepository.findByProjectId(projectId).stream()
                .filter(e -> e.getFilePath() != null &&
                        path.resolve(e.getFilePath()).normalize().startsWith(path))
                .collect(Collectors.toList());
        entityRepository.deleteAll(entities);
        directoryRepository.deleteByProjectIdAndAbsolutePath(projectId, path.toString());
    }

    /**
     * List all tracked directories for a project.
     */
    public List<IndexedDirectory> listDirectories(String projectId) {
        return directoryRepository.findByProjectId(projectId);
    }

    // ── Indexing ──────────────────────────────────────────────────────

    /**
     * Index a specific directory (incremental by default).
     * Only files that have changed since last index are re-processed.
     */
    @Transactional
    public IndexingStatus indexDirectory(String projectId, String absolutePath) {
        return indexDirectory(projectId, absolutePath, false);
    }

    /**
     * Index a specific directory.
     * @param forceReindex if true, wipes all data and re-indexes from scratch.
     */
    @Transactional
    public IndexingStatus indexDirectory(String projectId, String absolutePath, boolean forceReindex) {
        Path rootPath = Paths.get(absolutePath).toAbsolutePath();

        // Ensure directory is tracked
        IndexedDirectory dir = directoryRepository
                .findByProjectIdAndAbsolutePath(projectId, rootPath.toString())
                .orElseGet(() -> addDirectory(projectId, rootPath.toString(), null, null, null, null));

        // Apply language overrides from directory config
        applyLanguageOverrides(dir);

        dir.setStatus(IndexStatus.INDEXING);
        dir.setLastIndexedAt(Instant.now());
        directoryRepository.save(dir);

        IndexingStatus status = doIndex(projectId, rootPath, dir, forceReindex);

        dir.setStatus(status.errorMessage() != null ? IndexStatus.FAILED : IndexStatus.INDEXED);
        dir.setFilesIndexed(status.filesProcessed().get());
        dir.setEntitiesFound(status.entitiesFound().get());
        dir.setRelationsCreated(status.relationsCreated().get());
        dir.setErrors(status.errors().get());
        directoryRepository.save(dir);

        return status;
    }

    /**
     * Async index: returns immediately with a CompletableFuture.
     * Progress is reported via WebSocket at /topic/code-index/{projectId}.
     */
    @Async("taskExecutor")
    public CompletableFuture<IndexingStatus> indexDirectoryAsync(String projectId, String absolutePath, boolean forceReindex) {
        IndexingStatus status = indexDirectory(projectId, absolutePath, forceReindex);
        return CompletableFuture.completedFuture(status);
    }

    /**
     * Index all tracked directories for a project.
     */
    @Transactional
    public List<IndexingStatus> indexAllDirectories(String projectId) {
        return indexAllDirectories(projectId, false);
    }

    @Transactional
    public List<IndexingStatus> indexAllDirectories(String projectId, boolean forceReindex) {
        List<IndexedDirectory> dirs = directoryRepository.findByProjectId(projectId);
        List<IndexingStatus> results = new ArrayList<>();
        for (IndexedDirectory dir : dirs) {
            results.add(indexDirectory(projectId, dir.getAbsolutePath(), forceReindex));
        }
        return results;
    }

    /**
     * Legacy: index a codebase by path (auto-registers directory).
     */
    @Transactional
    public IndexingStatus indexCodebase(String projectId, Path rootPath) {
        return indexDirectory(projectId, rootPath.toAbsolutePath().toString());
    }

    public IndexingStatus getStatus(String projectId) {
        return activeJobs.get(projectId);
    }

    /**
     * Check if a project is currently being indexed.
     */
    public boolean isIndexing(String projectId) {
        IndexingStatus status = activeJobs.get(projectId);
        return status != null && !status.completed().get();
    }

    // ── Internal ─────────────────────────────────────────────────────

    private IndexingStatus doIndex(String projectId, Path rootPath, IndexedDirectory dir, boolean forceReindex) {
        boolean incremental = !forceReindex;
        log.info("Indexing: project={}, root={}, mode={}", projectId, rootPath, incremental ? "incremental" : "full");

        IndexingStatus status = new IndexingStatus(
                projectId, rootPath.toString(), Instant.now(),
                new AtomicInteger(0), new AtomicInteger(0), new AtomicInteger(0),
                new AtomicInteger(0), new AtomicInteger(0), new AtomicInteger(0),
                new AtomicBoolean(false), null, incremental
        );
        activeJobs.put(projectId, status);

        if (forceReindex) {
            // Full wipe for force-reindex
            entityRepository.deleteByProjectId(projectId);
            relationRepository.deleteByProjectId(projectId);
            fingerprintRepository.deleteByProjectId(projectId);
        }

        // Load existing fingerprints for incremental comparison
        Map<String, FileFingerprint> existingFingerprints = new HashMap<>();
        if (incremental) {
            fingerprintRepository.findByProjectId(projectId)
                    .forEach(fp -> existingFingerprints.put(fp.getFilePath(), fp));
        }

        // fqnToNodeId persists across ALL files so cross-file edges can be resolved.
        Map<String, String> fqnToNodeId = new HashMap<>();
        // Deferred relation triples whose target was not yet in fqnToNodeId at first pass.
        List<CodeEntityExtractor.RelationTriple> deferredRelations = new ArrayList<>();
        // All relations accumulated across files, for bulk persistence.
        List<CodeRelation> allRelations = new ArrayList<>();

        String sourceNodeId = null;
        if (knowledgeGraphService != null) {
            GraphNode sourceNode = knowledgeGraphService.createOrUpdateSourceNode(
                    projectId,
                    "codebase: " + rootPath.getFileName(),
                    "DIRECTORY",
                    rootPath.toString(),
                    Map.of("projectId", projectId)
            );
            sourceNodeId = sourceNode.getNodeId();
        }

        // Accumulate ALL saved entities for the hierarchy-fixup second pass.
        List<CodeEntity> allSavedEntities = new ArrayList<>();

        try {
            Set<String> excludePatterns = parsePatterns(dir.getExcludePatterns());
            Set<String> includePatterns = parsePatterns(dir.getIncludePatterns());

            List<Path> sourceFiles = collectSourceFiles(rootPath, includePatterns, excludePatterns);
            status.totalFiles().set(sourceFiles.size());
            log.info("Found {} source files in {}", sourceFiles.size(), rootPath);

            // Detect deleted files (in fingerprints but no longer on disk)
            if (incremental) {
                Set<String> currentFilePaths = sourceFiles.stream()
                        .map(f -> rootPath.relativize(f).toString())
                        .collect(Collectors.toSet());
                Set<String> deletedFiles = existingFingerprints.keySet().stream()
                        .filter(fp -> !currentFilePaths.contains(fp))
                        .collect(Collectors.toSet());
                if (!deletedFiles.isEmpty()) {
                    log.info("Removing {} deleted files from index", deletedFiles.size());
                    entityRepository.deleteByProjectIdAndFilePathIn(projectId, deletedFiles);
                    relationRepository.deleteByProjectIdAndFilePathIn(projectId, deletedFiles);
                    fingerprintRepository.deleteByProjectIdAndFilePathIn(projectId, deletedFiles);
                }
            }

            // Batch fingerprints to save at the end
            List<FileFingerprint> fingerprintsToSave = new ArrayList<>();

            for (Path file : sourceFiles) {
                try {
                    Path relativePath = rootPath.relativize(file);
                    String relativePathStr = relativePath.toString();

                    // Compute fingerprint and check if file changed
                    if (incremental) {
                        String hash = computeFileHash(file);
                        long size = Files.size(file);
                        Instant mtime = Files.getLastModifiedTime(file).toInstant();

                        FileFingerprint existing = existingFingerprints.get(relativePathStr);
                        if (existing != null && existing.getContentHash().equals(hash)) {
                            // File unchanged — skip
                            status.filesSkipped().incrementAndGet();
                            broadcastProgress(projectId, status);
                            continue;
                        }

                        // File is new or changed — remove old data for this file
                        if (existing != null) {
                            entityRepository.deleteByProjectIdAndFilePath(projectId, relativePathStr);
                            relationRepository.deleteByProjectIdAndFilePath(projectId, relativePathStr);
                        }

                        // Queue new fingerprint
                        FileFingerprint fp = FileFingerprint.builder()
                                .projectId(projectId)
                                .filePath(relativePathStr)
                                .contentHash(hash)
                                .fileSize(size)
                                .lastModified(mtime)
                                .indexedAt(Instant.now())
                                .build();
                        if (existing != null) {
                            fp.setId(existing.getId());
                        }
                        fingerprintsToSave.add(fp);
                    }

                    CodeEntityExtractor.ExtractionResult result = extractor.extract(file, projectId);

                    for (CodeEntity entity : result.entities()) {
                        entity.setFilePath(relativePathStr);
                        if (entity.getEntityType() == CodeEntityType.FILE) {
                            entity.setFullyQualifiedName(relativePathStr);
                        }
                    }

                    List<CodeEntity> saved = entityRepository.saveAll(result.entities());
                    status.entitiesFound().addAndGet(saved.size());
                    allSavedEntities.addAll(saved);

                    // Persist all relation triples to the code_relations table
                    for (CodeEntityExtractor.RelationTriple rel : result.relations()) {
                        String targetName = rel.targetFqn().contains(".")
                                ? rel.targetFqn().substring(rel.targetFqn().lastIndexOf('.') + 1)
                                : rel.targetFqn();
                        allRelations.add(CodeRelation.builder()
                                .projectId(projectId)
                                .relationType(rel.relationType())
                                .sourceFqn(rel.sourceFqn())
                                .targetName(targetName)
                                .targetFqn(rel.targetFqn())
                                .filePath(relativePathStr)
                                .build());
                    }

                    if (knowledgeGraphService != null) {
                        for (CodeEntity entity : saved) {
                            String nodeId = storeAsGraphNode(entity);
                            if (nodeId != null) {
                                entity.setGraphNodeId(UUID.fromString(nodeId));
                                fqnToNodeId.put(entity.getFullyQualifiedName(), nodeId);
                            }
                        }

                        // First-pass edges — defer those whose target is not yet known.
                        for (CodeEntityExtractor.RelationTriple rel : result.relations()) {
                            String srcId = fqnToNodeId.get(rel.sourceFqn());
                            String tgtId = fqnToNodeId.get(rel.targetFqn());
                            if (srcId != null && tgtId != null) {
                                createGraphEdge(srcId, tgtId, rel.relationType());
                                status.relationsCreated().incrementAndGet();
                            } else {
                                deferredRelations.add(rel);
                            }
                        }
                    }

                    status.filesProcessed().incrementAndGet();
                    broadcastProgress(projectId, status);
                } catch (Exception e) {
                    log.warn("Error indexing {}: {}", file, e.getMessage());
                    status.errors().incrementAndGet();
                }
            }

            // Bulk-save fingerprints
            if (!fingerprintsToSave.isEmpty()) {
                fingerprintRepository.saveAll(fingerprintsToSave);
            }

            // Bulk-save all relation records
            if (!allRelations.isEmpty()) {
                relationRepository.saveAll(allRelations);
                log.info("Persisted {} relation records", allRelations.size());
            }

            // Second pass — resolve deferred cross-file relations.
            if (knowledgeGraphService != null) {
                int deferred = createDeferredEdges(deferredRelations, fqnToNodeId);
                status.relationsCreated().addAndGet(deferred);

                // Hierarchy fixup — parent→child HIERARCHICAL edges + source→FILE edges.
                int hierarchy = connectHierarchy(allSavedEntities, fqnToNodeId, sourceNodeId);
                status.relationsCreated().addAndGet(hierarchy);
            }

        } catch (IOException e) {
            log.error("Error walking {}: {}", rootPath, e.getMessage());
            status.completed().set(true);
            IndexingStatus failed = new IndexingStatus(projectId, rootPath.toString(), status.startedAt(),
                    status.totalFiles(), status.filesProcessed(), status.filesSkipped(),
                    status.entitiesFound(), status.relationsCreated(),
                    status.errors(), new AtomicBoolean(true), e.getMessage(), incremental);
            activeJobs.put(projectId, failed);
            broadcastProgress(projectId, failed);
            return failed;
        }

        status.completed().set(true);
        IndexingStatus completed = new IndexingStatus(projectId, rootPath.toString(), status.startedAt(),
                status.totalFiles(), status.filesProcessed(), status.filesSkipped(),
                status.entitiesFound(), status.relationsCreated(),
                status.errors(), new AtomicBoolean(true), null, incremental);
        activeJobs.put(projectId, completed);
        broadcastProgress(projectId, completed);

        log.info("Indexed: mode={}, processed={}, skipped={}, entities={}, relations={}, errors={}",
                incremental ? "incremental" : "full",
                status.filesProcessed().get(), status.filesSkipped().get(),
                status.entitiesFound().get(), status.relationsCreated().get(), status.errors().get());

        return completed;
    }

    // ── Fingerprinting ──────────────────────────────────────────────

    private String computeFileHash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream is = Files.newInputStream(file)) {
                int read;
                while ((read = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    // ── Progress Broadcasting ────────────────────────────────────────

    private void broadcastProgress(String projectId, IndexingStatus status) {
        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend("/topic/code-index/" + projectId, status.toMap());
            } catch (Exception e) {
                log.debug("Failed to broadcast progress: {}", e.getMessage());
            }
        }
    }

    private void applyLanguageOverrides(IndexedDirectory dir) {
        if (dir.getLanguageOverridesJson() == null) return;
        try {
            Map<String, String> overrides = objectMapper.readValue(
                    dir.getLanguageOverridesJson(), new TypeReference<>() {});
            LanguageRegistry registry = extractor.getLanguageRegistry();
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                registry.setPatternLanguage(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            log.warn("Failed to parse language overrides for {}: {}", dir.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Name of the ignore file (gitignore-compatible syntax).
     * Looked for in the root of the indexed directory and any VCS root above it.
     */
    static final String IGNORE_FILE_NAME = ".kompile-graph-ignore";

    private List<Path> collectSourceFiles(Path root, Set<String> includes, Set<String> excludes)
            throws IOException {
        // Load .kompile-graph-ignore patterns from the root (and any parent up to a VCS boundary)
        List<PathMatcher> ignoreMatchers = loadIgnorePatterns(root);

        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (IGNORED_DIRS.contains(dirName)) return FileVisitResult.SKIP_SUBTREE;
                if (!excludes.isEmpty() && matchesAny(dir.toString(), excludes)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // Check .kompile-graph-ignore patterns
                if (matchesIgnorePatterns(root, dir, ignoreMatchers)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > 1_000_000) return FileVisitResult.CONTINUE; // Skip > 1MB
                if (!extractor.isSupported(file)) return FileVisitResult.CONTINUE;

                String fileName = file.getFileName().toString();
                if (!includes.isEmpty() && !matchesAny(fileName, includes)) {
                    return FileVisitResult.CONTINUE;
                }
                if (!excludes.isEmpty() && matchesAny(fileName, excludes)) {
                    return FileVisitResult.CONTINUE;
                }
                // Check .kompile-graph-ignore patterns
                if (matchesIgnorePatterns(root, file, ignoreMatchers)) {
                    return FileVisitResult.CONTINUE;
                }
                files.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * Loads .kompile-graph-ignore from the root directory and any parent directories
     * up to a VCS boundary (.git, .hg). Uses gitignore-compatible syntax:
     * lines starting with # are comments, trailing / matches directories,
     * * and ** wildcards work as in .gitignore.
     */
    private List<PathMatcher> loadIgnorePatterns(Path root) {
        List<PathMatcher> matchers = new ArrayList<>();
        FileSystem fs = FileSystems.getDefault();

        // Walk up to find ignore files, stopping at VCS boundaries
        Path current = root.toAbsolutePath().normalize();
        while (current != null) {
            Path ignoreFile = current.resolve(IGNORE_FILE_NAME);
            if (Files.isRegularFile(ignoreFile)) {
                try {
                    List<String> lines = Files.readAllLines(ignoreFile);
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                        // Convert gitignore pattern to glob
                        String glob = trimmed;
                        // Leading / means relative to this ignore file's directory
                        if (glob.startsWith("/")) glob = glob.substring(1);
                        // Trailing / means directories only — we match path prefix
                        if (glob.endsWith("/")) glob = glob + "**";
                        // Ensure proper glob syntax
                        if (!glob.startsWith("**/") && !glob.contains("/")) {
                            glob = "**/" + glob; // bare name matches anywhere
                        }
                        try {
                            matchers.add(fs.getPathMatcher("glob:" + glob));
                        } catch (Exception e) {
                            log.debug("Invalid ignore pattern '{}': {}", trimmed, e.getMessage());
                        }
                    }
                    log.info("Loaded {} ignore patterns from {}", matchers.size(), ignoreFile);
                } catch (IOException e) {
                    log.warn("Failed to read {}: {}", ignoreFile, e.getMessage());
                }
            }
            // Stop at VCS boundary
            if (Files.isDirectory(current.resolve(".git")) || Files.isDirectory(current.resolve(".hg"))) {
                break;
            }
            current = current.getParent();
        }
        return matchers;
    }

    private boolean matchesIgnorePatterns(Path root, Path path, List<PathMatcher> matchers) {
        if (matchers.isEmpty()) return false;
        Path relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relative)) return true;
        }
        return false;
    }

    private boolean matchesAny(String value, Set<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.startsWith("*") && value.endsWith(pattern.substring(1))) return true;
            if (pattern.endsWith("*") && value.startsWith(pattern.substring(0, pattern.length() - 1))) return true;
            if (value.contains(pattern)) return true;
        }
        return false;
    }

    private Set<String> parsePatterns(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) return Set.of();
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Map a {@link CodeEntityType} to the appropriate {@link NodeLevel} for graph storage.
     */
    private NodeLevel mapNodeLevel(CodeEntityType type) {
        return switch (type) {
            case FILE -> NodeLevel.DOCUMENT;
            case PACKAGE, MODULE -> NodeLevel.SOURCE;
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION -> NodeLevel.ENTITY;
            case METHOD, CONSTRUCTOR, FUNCTION -> NodeLevel.SNIPPET;
            default -> NodeLevel.CUSTOM;
        };
    }

    /**
     * Create (or upsert) a knowledge graph node for the given code entity.
     *
     * @return the node's String UUID (nodeId), or {@code null} on failure.
     */
    private String storeAsGraphNode(CodeEntity entity) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            if (entity.getLanguage() != null)   meta.put("language", entity.getLanguage());
            if (entity.getStartLine() != null)  meta.put("startLine", entity.getStartLine());
            if (entity.getEndLine() != null)    meta.put("endLine", entity.getEndLine());
            if (entity.getSignature() != null)  meta.put("signature", entity.getSignature());
            if (entity.getVisibility() != null) meta.put("visibility", entity.getVisibility());
            meta.put("entityType", entity.getEntityType().name());

            GraphNode node = knowledgeGraphService.createNode(
                    mapNodeLevel(entity.getEntityType()),
                    entity.getFullyQualifiedName(),
                    entity.getEntityType().name().toLowerCase() + ": " + entity.getName(),
                    buildDescription(entity),
                    meta
            );
            return node.getNodeId();
        } catch (Exception e) {
            log.warn("Failed to create graph node for {}: {}", entity.getFullyQualifiedName(), e.getMessage());
            return null;
        }
    }

    /**
     * Create a directed edge between two graph nodes identified by their String node IDs.
     */
    private void createGraphEdge(String sourceId, String targetId, CodeRelationType relType) {
        EdgeType edgeType = switch (relType) {
            case CONTAINS -> EdgeType.HIERARCHICAL;
            case EXTENDS, IMPLEMENTS, OVERRIDES -> EdgeType.USER_DEFINED;
            case IMPORTS, DEPENDS_ON -> EdgeType.CROSS_SOURCE;
            case CALLS, RETURNS, PARAMETER_TYPE, FIELD_TYPE, ANNOTATED_BY -> EdgeType.SHARED_ENTITY;
        };

        try {
            knowledgeGraphService.createEdge(
                    sourceId,
                    targetId,
                    edgeType,
                    1.0,
                    relType.name().toLowerCase()
            );
        } catch (Exception e) {
            log.warn("Failed to create graph edge {}->{}: {}", sourceId, targetId, e.getMessage());
        }
    }

    /**
     * Second-pass: attempt to resolve and create edges that were deferred because
     * the target node had not been indexed yet during the first pass.
     *
     * @return number of edges successfully created.
     */
    private int createDeferredEdges(List<CodeEntityExtractor.RelationTriple> deferred,
                                    Map<String, String> fqnToNodeId) {
        int created = 0;
        for (CodeEntityExtractor.RelationTriple rel : deferred) {
            String srcId = fqnToNodeId.get(rel.sourceFqn());
            String tgtId = fqnToNodeId.get(rel.targetFqn());
            if (srcId != null && tgtId != null) {
                createGraphEdge(srcId, tgtId, rel.relationType());
                created++;
            } else {
                log.debug("Skipping deferred relation {}->{} ({}): node(s) not in graph",
                        rel.sourceFqn(), rel.targetFqn(), rel.relationType());
            }
        }
        if (created > 0) {
            log.info("Created {} deferred cross-file edges", created);
        }
        return created;
    }

    /**
     * Post-indexing hierarchy fixup.
     * <ul>
     *   <li>For every entity that has a {@code parentFqn}, create a HIERARCHICAL edge
     *       from the parent node to the child node (if both are in the graph).</li>
     *   <li>For every FILE entity, create a HIERARCHICAL edge from the source node
     *       to the file node.</li>
     * </ul>
     *
     * @return total number of edges created.
     */
    private int connectHierarchy(List<CodeEntity> entities,
                                 Map<String, String> fqnToNodeId,
                                 String sourceNodeId) {
        int created = 0;
        for (CodeEntity entity : entities) {
            String childId = fqnToNodeId.get(entity.getFullyQualifiedName());
            if (childId == null) continue;

            // FILE → source node hierarchy
            if (entity.getEntityType() == CodeEntityType.FILE && sourceNodeId != null) {
                try {
                    if (!knowledgeGraphService.edgeExists(sourceNodeId, childId)) {
                        knowledgeGraphService.createEdge(sourceNodeId, childId,
                                EdgeType.HIERARCHICAL, 1.0, "source contains file");
                        created++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to create source->file edge for {}: {}", entity.getFullyQualifiedName(), e.getMessage());
                }
            }

            // parent → child hierarchy based on parentFqn
            if (entity.getParentFqn() != null) {
                String parentId = fqnToNodeId.get(entity.getParentFqn());
                if (parentId != null) {
                    try {
                        if (!knowledgeGraphService.edgeExists(parentId, childId)) {
                            knowledgeGraphService.createEdge(parentId, childId,
                                    EdgeType.HIERARCHICAL, 1.0, "contains");
                            created++;
                        }
                    } catch (Exception e) {
                        log.warn("Failed to create parent->child hierarchy edge {} -> {}: {}",
                                entity.getParentFqn(), entity.getFullyQualifiedName(), e.getMessage());
                    }
                }
            }
        }
        if (created > 0) {
            log.info("Created {} hierarchy fixup edges", created);
        }
        return created;
    }

    private String buildDescription(CodeEntity entity) {
        StringBuilder sb = new StringBuilder();
        sb.append(entity.getEntityType().name().toLowerCase());
        if (entity.getLanguage() != null) sb.append(" [").append(entity.getLanguage()).append("]");
        if (entity.getSignature() != null) sb.append(": ").append(entity.getSignature());
        if (entity.getFilePath() != null) {
            sb.append(" in ").append(entity.getFilePath());
            if (entity.getStartLine() != null) sb.append(":").append(entity.getStartLine());
        }
        return sb.toString();
    }
}
