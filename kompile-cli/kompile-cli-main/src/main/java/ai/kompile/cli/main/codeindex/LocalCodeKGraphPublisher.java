/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.graph.reasoning.unified.UnifiedGraphArchive;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import ai.kompile.cli.main.project.ProjectAutoDetection;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectLifecycleState;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Publishes the committed local code index as a materialized slice of the owning
 * directory project's portable KGraph. SQLite remains the code-search store;
 * {@code graph.kgraph} is the reasoning/export view.
 */
public final class LocalCodeKGraphPublisher {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final ConcurrentHashMap<Path, ReentrantLock> PROJECT_LOCKS = new ConcurrentHashMap<>();
    private static final String META_PROJECTED_GENERATION = "codeIndexGeneration";
    private static final String META_PROJECTION_VERSION = "codeProjectionVersion";
    private static final String META_GRAPH_ENTITIES = "codeProjectionEntities";
    private static final String META_GRAPH_RELATIONS = "codeProjectionRelations";
    private static final String META_CODE_ENTITIES = "codeProjectionCodeEntities";
    private static final String META_FOLDER_PROJECT = "codeProjectionFolderProjectId";
    private static final String META_FACT_SHEET = "codeProjectionFactSheetId";
    private static final String META_INCLUDES = "codeProjectionIncludes";
    private static final String META_EXCLUDES = "codeProjectionExcludes";

    private LocalCodeKGraphPublisher() {
    }

    public static ProjectionResult publish(Path indexedRoot, String indexProjectId,
                                           String includes, String excludes) throws Exception {
        Objects.requireNonNull(indexedRoot, "indexedRoot");
        if (!LocalCodeIndexer.isSafeProjectId(indexProjectId)) {
            throw new IllegalArgumentException("Invalid code-index project id: " + indexProjectId);
        }
        Path normalizedRoot = indexedRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IllegalArgumentException("Not a directory: " + normalizedRoot);
        }
        normalizedRoot = normalizedRoot.toRealPath();

        KompileProjectStore store = new KompileProjectStore();
        Path projectRoot = store.findProjectRoot(normalizedRoot).orElse(normalizedRoot);
        Path projectState = projectRoot.resolve(".kompile");
        Files.createDirectories(projectState);
        Path lockKey = projectRoot.toRealPath();
        ReentrantLock processLock = PROJECT_LOCKS.computeIfAbsent(lockKey,
                ignored -> new ReentrantLock());
        processLock.lock();
        try {
        try (FileChannel manifestChannel = FileChannel.open(
                projectState.resolve("code-kgraph-publisher.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = manifestChannel.lock()) {
        KompileProjectManifest manifest;
        if (Files.isRegularFile(projectRoot.resolve(KompileProjectStore.MANIFEST_FILE))) {
            manifest = store.load(projectRoot);
        } else {
            KompileProjectInitRequest request = new KompileProjectInitRequest();
            String name = projectRoot.getFileName() != null
                    ? projectRoot.getFileName().toString() : indexProjectId;
            request.setName(name);
            request.setIncludeStandardComponents(false);
            request.setTags(List.of("auto-detected", "code", "directory-project", "local-stdio"));
            KompileCodingProject detected = ProjectAutoDetection.buildDirectoryCodingProject(normalizedRoot);
            detected.setId(indexProjectId);
            detected.setCodeProjectId(indexProjectId);
            request.getCodingProjects().add(detected);
            manifest = store.init(projectRoot, request);
        }

        KompileCodingProject codingProject = findExactRoot(manifest, projectRoot, normalizedRoot);
        if (codingProject == null) {
            codingProject = ProjectAutoDetection.buildDirectoryCodingProject(normalizedRoot);
            codingProject.setId(indexProjectId);
            codingProject.setCodeProjectId(indexProjectId);
            manifest = store.registerCodingProject(projectRoot, codingProject);
            codingProject = findExactRoot(manifest, projectRoot, normalizedRoot);
        }
        String canonicalId = firstNonBlank(codingProject.getCodeProjectId(), codingProject.getId());
        if (!indexProjectId.equals(canonicalId)) {
            throw new IllegalStateException("Directory is registered as code project '" + canonicalId
                    + "' but was indexed as '" + indexProjectId + "'. Re-index with the canonical id.");
        }

        KompileCodingProject folderProject = findExactRoot(manifest, projectRoot, projectRoot);
        String folderProjectId = folderProject == null ? canonicalId
                : firstNonBlank(folderProject.getCodeProjectId(), folderProject.getId(), canonicalId);
        Long factSheetId = codingProject.getFactSheetId() != null
                ? codingProject.getFactSheetId()
                : folderProject == null ? null : folderProject.getFactSheetId();
        String knowledgeBaseId = factSheetId != null
                ? "kb-" + factSheetId
                : safeKnowledgeBaseId(firstNonBlank(codingProject.getMetadata().get("knowledgeBaseId"),
                folderProject == null ? null : folderProject.getMetadata().get("knowledgeBaseId"),
                folderProjectId.toLowerCase(Locale.ROOT) + "-knowledge"));
        String knowledgeBaseName = firstNonBlank(manifest.getName(), codingProject.getName(), knowledgeBaseId);
        List<String> includePatterns = csv(includes != null ? includes : codingProject.getIncludePatterns());
        List<String> excludePatterns = csv(excludes != null ? excludes : codingProject.getExcludePatterns());

        LocalProjectGraphBackend.CodeProjectSource source =
                new LocalProjectGraphBackend.CodeProjectSource(normalizedRoot, canonicalId,
                        firstNonBlank(codingProject.getName(), canonicalId),
                        includePatterns, excludePatterns);
        LocalProjectGraphBackend.CodeProjectionUpdate update;
        String indexGeneration;
        try (IndexLockManager.LockToken ignoredIndex =
                     IndexLockManager.acquireReadLock(canonicalId)) {
            indexGeneration = currentIndexGeneration(canonicalId);
            ProjectionResult existing = existingProjection(projectRoot, codingProject,
                    knowledgeBaseId, factSheetId, folderProjectId,
                    includePatterns, excludePatterns, indexGeneration);
            if (existing != null) return existing;
            update = new LocalProjectGraphBackend(MAPPER).projectIndexedCodeProject(
                    projectRoot, knowledgeBaseId, knowledgeBaseName, factSheetId,
                    folderProjectId, source, indexGeneration);
            String confirmedGeneration = currentIndexGeneration(canonicalId);
            if (!Objects.equals(indexGeneration, confirmedGeneration)) {
                throw new IllegalStateException("Code index changed during graph projection; retrying "
                        + "against the newer committed generation");
            }
        }

        codingProject.getMetadata().put("knowledgeBaseId", knowledgeBaseId);
        codingProject.getMetadata().put("graphPath",
                projectRoot.relativize(update.graphPath()).toString().replace('\\', '/'));
        codingProject.getMetadata().put(META_PROJECTION_VERSION,
                String.valueOf(LocalProjectGraphBackend.CODE_PROJECTION_VERSION));
        codingProject.getMetadata().put("codeProjectedAt", Instant.now().toString());
        if (indexGeneration != null) {
            codingProject.getMetadata().put(META_PROJECTED_GENERATION, indexGeneration);
        }
        codingProject.getMetadata().put(META_GRAPH_ENTITIES, String.valueOf(update.entities()));
        codingProject.getMetadata().put(META_GRAPH_RELATIONS, String.valueOf(update.relations()));
        codingProject.getMetadata().put(META_CODE_ENTITIES, String.valueOf(update.codeEntities()));
        codingProject.getMetadata().put(META_FOLDER_PROJECT, folderProjectId);
        codingProject.getMetadata().put(META_FACT_SHEET,
                factSheetId == null ? "" : factSheetId.toString());
        codingProject.getMetadata().put(META_INCLUDES, String.join(",", includePatterns));
        codingProject.getMetadata().put(META_EXCLUDES, String.join(",", excludePatterns));
        if (includes != null) codingProject.setIncludePatterns(includes);
        if (excludes != null) codingProject.setExcludePatterns(excludes);
        store.registerCodingProject(projectRoot, codingProject);

        return new ProjectionResult(update.graphPath(), update.knowledgeBaseId(), update.factSheetId(),
                update.entities(), update.relations(), update.codeEntities());
        }
        } finally {
            processLock.unlock();
        }
    }

    private static ProjectionResult existingProjection(Path projectRoot,
                                                       KompileCodingProject codingProject,
                                                       String knowledgeBaseId,
                                                       Long factSheetId,
                                                       String folderProjectId,
                                                       List<String> includePatterns,
                                                       List<String> excludePatterns,
                                                       String indexGeneration) {
        if (indexGeneration == null || indexGeneration.isBlank()) return null;
        Map<String, String> metadata = codingProject.getMetadata();
        if (!indexGeneration.equals(metadata.get(META_PROJECTED_GENERATION))
                || !String.valueOf(LocalProjectGraphBackend.CODE_PROJECTION_VERSION)
                .equals(metadata.get(META_PROJECTION_VERSION))) {
            return null;
        }
        if (!knowledgeBaseId.equals(metadata.get("knowledgeBaseId"))
                || !folderProjectId.equals(metadata.get(META_FOLDER_PROJECT))
                || !(factSheetId == null ? "" : factSheetId.toString())
                .equals(metadata.get(META_FACT_SHEET))
                || !String.join(",", includePatterns).equals(metadata.get(META_INCLUDES))
                || !String.join(",", excludePatterns).equals(metadata.get(META_EXCLUDES))) {
            return null;
        }
        Integer graphEntities = nonNegativeInt(metadata.get(META_GRAPH_ENTITIES));
        Integer graphRelations = nonNegativeInt(metadata.get(META_GRAPH_RELATIONS));
        Integer codeEntities = nonNegativeInt(metadata.get(META_CODE_ENTITIES));
        if (graphEntities == null || graphRelations == null || codeEntities == null) return null;

        String configuredGraph = metadata.get("graphPath");
        if (configuredGraph == null || configuredGraph.isBlank()) return null;
        try {
            Path graphPath = Path.of(configuredGraph);
            if (!graphPath.isAbsolute()) graphPath = projectRoot.resolve(graphPath);
            Path expected = projectRoot.resolve("data/crawls").resolve(knowledgeBaseId)
                    .resolve("graph.kgraph").toAbsolutePath().normalize();
            graphPath = graphPath.toAbsolutePath().normalize();
            if (!graphPath.equals(expected) || !Files.isRegularFile(graphPath)) return null;
            Path realRoot = projectRoot.toRealPath();
            Path realGraph = graphPath.toRealPath();
            if (!realGraph.startsWith(realRoot)) return null;
            // The manifest is only a receipt: imports/restores/crawls can replace the archive
            // at this path without changing the code index or that receipt.
            try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(realGraph)) {
                Object raw = archive.manifest().get("meta");
                String codeProjectId = firstNonBlank(codingProject.getCodeProjectId(), codingProject.getId());
                if (!(raw instanceof Map<?, ?> graphMeta)
                        || !indexGeneration.equals(graphMeta.get("codeIndexGeneration." + codeProjectId))
                        || !String.valueOf(LocalProjectGraphBackend.CODE_PROJECTION_VERSION)
                            .equals(String.valueOf(graphMeta.get("codeProjectionVersion")))
                        || !"COMPLETED".equals(graphMeta.get("phase.codeProjection." + codeProjectId))) {
                    return null;
                }
            }
            return new ProjectionResult(realGraph, knowledgeBaseId, factSheetId,
                    graphEntities, graphRelations, codeEntities);
        } catch (Exception invalidPath) {
            return null;
        }
    }

    private static Integer nonNegativeInt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String currentIndexGeneration(String projectId) throws Exception {
        try (IndexDatabase database = IndexDatabase.openReadOnly(LocalCodeIndexer.getIndexDir(projectId))) {
            return database.getIndexGeneration();
        }
    }

    private static KompileCodingProject findExactRoot(KompileProjectManifest manifest,
                                                       Path projectRoot, Path indexedRoot) {
        if (manifest == null || manifest.getCodingProjects() == null) return null;
        for (KompileCodingProject candidate : manifest.getCodingProjects()) {
            if (candidate == null || candidate.getLifecycle() != null
                    && candidate.getLifecycle() != KompileProjectLifecycleState.ACTIVE) continue;
            String configured = firstNonBlank(candidate.getRootPath());
            if (configured == null) continue;
            try {
                Path root = Path.of(configured);
                if (!root.isAbsolute()) root = projectRoot.resolve(root);
                if (sameCanonicalPath(indexedRoot, root)) return candidate;
            } catch (RuntimeException ignored) {
                // Invalid registrations are ignored; the valid directory registration wins.
            }
        }
        return null;
    }

    private static boolean sameCanonicalPath(Path first, Path second) {
        try {
            return first.toRealPath().equals(second.toRealPath());
        } catch (Exception unavailable) {
            return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
        }
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static String safeKnowledgeBaseId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("knowledge-base id is required");
        }
        String raw = value.trim();
        Path rawPath = Path.of(raw);
        if (rawPath.isAbsolute() || rawPath.getNameCount() != 1
                || raw.contains("/") || raw.contains("\\")
                || ".".equals(raw) || "..".equals(raw)) {
            throw new IllegalArgumentException("Invalid project-local knowledge-base id: " + raw);
        }
        String id = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        Path path = Path.of(id);
        if (path.isAbsolute() || path.getNameCount() != 1 || id.contains("/") || id.contains("\\")
                || ".".equals(id) || "..".equals(id)) {
            throw new IllegalArgumentException("Invalid project-local knowledge-base id: " + id);
        }
        return id;
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return null;
    }

    public record ProjectionResult(Path graphPath,
                                   String knowledgeBaseId,
                                   Long factSheetId,
                                   int graphEntities,
                                   int graphRelations,
                                   int codeEntities) {
    }
}
