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

import java.io.IOException;
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
import java.util.Optional;
import java.util.concurrent.Callable;
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
        Path projectRoot = store.findProjectRoot(normalizedRoot).orElse(null);
        if (projectRoot == null) {
            // Decided before anything is written: a refused directory must not gain a .kompile dir.
            String refusal = autoInitRefusal(normalizedRoot);
            if (refusal != null) return ProjectionResult.skipped(refusal);
            projectRoot = normalizedRoot;
        }
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
        if (LocalCodeIndexer.isRemoved(indexProjectId)) {
            throw new IllegalStateException("Code index was removed; refusing stale graph publication: " + indexProjectId);
        }
        KompileProjectManifest manifest;
        if (Files.isRegularFile(projectRoot.resolve(KompileProjectStore.MANIFEST_FILE))) {
            manifest = store.load(projectRoot);
        } else {
            String refusal = autoInitRefusal(projectRoot);
            if (refusal != null) return ProjectionResult.skipped(refusal);
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
            KompileCodingProject enclosing = findEnclosingRoot(manifest, projectRoot, normalizedRoot);
            if (enclosing != null) {
                // Registering the sub-directory would claim every file under it for a second id.
                return ProjectionResult.skipped(normalizedRoot + " is part of code project '"
                        + firstNonBlank(enclosing.getCodeProjectId(), enclosing.getId()) + "' rooted at "
                        + registeredRoot(enclosing, projectRoot) + "; index that root to publish its code graph");
            }
            codingProject = ProjectAutoDetection.buildDirectoryCodingProject(normalizedRoot);
            codingProject.setId(indexProjectId);
            codingProject.setCodeProjectId(indexProjectId);
            try {
                manifest = store.registerCodingProject(projectRoot, codingProject);
            } catch (IllegalArgumentException refused) {
                // The store refuses roots it cannot own (home, a root another id owns); the index stays searchable.
                return ProjectionResult.skipped(refused.getMessage());
            }
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

        List<String> includePatterns;
        List<String> excludePatterns;
        LocalProjectGraphBackend.CodeProjectionUpdate update;
        String indexGeneration;
        try (IndexLockManager.LockToken ignoredIndex =
                     IndexLockManager.acquireReadLock(canonicalId)) {
            indexGeneration = currentIndexGeneration(canonicalId);
            // A call that names no scope publishes the scope the committed index was built with;
            // the registration's patterns are only the fallback when that metadata is unreadable.
            Map<String, Object> indexMetadata = indexMetadata(canonicalId);
            includePatterns = csv(includes != null ? includes : indexMetadata != null
                    ? metadataString(indexMetadata.get("includePatterns")) : codingProject.getIncludePatterns());
            excludePatterns = csv(excludes != null ? excludes : indexMetadata != null
                    ? metadataString(indexMetadata.get("excludePatterns")) : codingProject.getExcludePatterns());
            ProjectionResult existing = existingProjection(projectRoot, codingProject,
                    knowledgeBaseId, factSheetId, folderProjectId,
                    includePatterns, excludePatterns, indexGeneration);
            if (existing != null) return existing;
            LocalProjectGraphBackend.CodeProjectSource source =
                    new LocalProjectGraphBackend.CodeProjectSource(normalizedRoot, canonicalId,
                            firstNonBlank(codingProject.getName(), canonicalId),
                            includePatterns, excludePatterns);
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
        // A call's scope stays in the index metadata and this receipt. Saved as the registration's
        // patterns, it narrowed every later refresh of the project to that one call's scope.
        store.registerCodingProject(projectRoot, codingProject);

        return new ProjectionResult(update.graphPath(), update.knowledgeBaseId(), update.factSheetId(),
                update.entities(), update.relations(), update.codeEntities());
        }
        } finally {
            processLock.unlock();
        }
    }

    private static <T> T withPublicationLock(Path root, Callable<T> operation) throws Exception {
        Path state = root.resolve(".kompile");
        Files.createDirectories(state);
        ReentrantLock lock = PROJECT_LOCKS.computeIfAbsent(root.toRealPath(), ignored -> new ReentrantLock());
        lock.lock();
        try (FileChannel channel = FileChannel.open(state.resolve("code-kgraph-publisher.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return operation.call();
        } finally {
            lock.unlock();
        }
    }

    /** Only a new explicit tool request may lift the persistent removal fence. */
    public static void prepareExplicitIndex(Path directory, String projectId) throws Exception {
        Optional<Path> manifestRoot = new KompileProjectStore().findProjectRoot(directory);
        if (!LocalCodeIndexer.isSafeProjectId(projectId)) throw new IllegalArgumentException("Invalid project id");
        if (manifestRoot.isEmpty() && autoInitRefusal(directory.toRealPath()) != null) {
            // publish() refuses this directory: there is no registration to re-activate and no
            // publication to serialize with, so no .kompile directory is created in it either.
            liftRemovalFence(directory, projectId, null);
            return;
        }
        Path root = manifestRoot.orElse(directory).toRealPath();
        withPublicationLock(root, () -> {
            liftRemovalFence(directory, projectId, root);
            return null;
        });
    }

    private static void liftRemovalFence(Path directory, String projectId, Path root) throws Exception {
        Path marker = LocalCodeIndexer.getIndexDir(projectId).resolve(LocalCodeIndexer.REMOVAL_MARKER);
        try (var ignored = IndexLockManager.acquireWriteLock(projectId, LocalCodeIndexer.getIndexDir(projectId))) {
            if (Files.isRegularFile(marker) && !directory.toRealPath().equals(Path.of(Files.readString(marker)).toRealPath())) {
                throw new IllegalArgumentException("Removed project belongs to a different directory");
            }
            KompileProjectStore store = new KompileProjectStore();
            if (root != null && Files.isRegularFile(root.resolve(KompileProjectStore.MANIFEST_FILE))) {
                KompileProjectManifest manifest = store.load(root);
                for (KompileCodingProject cp : manifest.getCodingProjects()) {
                    if (projectId.equals(firstNonBlank(cp.getCodeProjectId(), cp.getId()))) {
                        cp.setLifecycle(KompileProjectLifecycleState.ACTIVE);
                        cp.getMetadata().remove("codeProjectionState");
                        store.registerCodingProject(root, cp);
                        break;
                    }
                }
            }
            Files.deleteIfExists(marker);
        }
    }

    /** Fenced, retryable removal. The marker and lock inode deliberately outlive the index. */
    public static int remove(Path directory, String projectId) throws Exception {
        if (!LocalCodeIndexer.isSafeProjectId(projectId)) throw new IllegalArgumentException("Invalid project id");
        Path requested = directory.toRealPath();
        KompileProjectStore store = new KompileProjectStore();
        Optional<Path> manifestRoot = store.findProjectRoot(requested);
        Path root = manifestRoot.orElse(requested).toRealPath();
        Path index = LocalCodeIndexer.getIndexDir(projectId).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(index) || !index.startsWith(LocalCodeIndexer.getBaseIndexDir().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Unsafe code-index directory: " + index);
        }
        Callable<Integer> removal = () -> {
            KompileProjectManifest manifest = Files.isRegularFile(root.resolve(KompileProjectStore.MANIFEST_FILE))
                    ? store.load(root) : null;
            KompileCodingProject registration = manifest == null ? null : manifest.getCodingProjects().stream()
                    .filter(cp -> projectId.equals(firstNonBlank(cp.getCodeProjectId(), cp.getId())))
                    .findFirst().orElse(null);
            Path marker = index.resolve(LocalCodeIndexer.REMOVAL_MARKER);
            try (var ignored = IndexLockManager.acquireWriteLock(projectId, index)) {
                String indexedRoot;
                if (Files.isRegularFile(marker)) indexedRoot = Files.readString(marker);
                else if (Files.isRegularFile(index.resolve("index.db"))) {
                    indexedRoot = String.valueOf(new LocalCodeIndexer().getStats(projectId).get("rootPath"));
                } else if (registration != null) indexedRoot = root.resolve(registration.getRootPath()).toString();
                else throw new IllegalArgumentException("No index or registration found for project '" + projectId + "'");
                if (!requested.equals(Path.of(indexedRoot).toRealPath())) {
                    throw new IllegalArgumentException("Directory is not tracked by project '" + projectId + "'");
                }
                if (registration != null && !requested.equals(root.resolve(registration.getRootPath()).toRealPath())) {
                    throw new IllegalArgumentException("Project registration root does not match index root");
                }
                Files.writeString(marker, requested.toString());
            }
            // Never hold the index lock while acquiring a graph lock: crawls take them in the reverse order.
            BackgroundIndexService.getInstance().retireRemovedProject(projectId);
            int updatedGraphs = 0;
            Path crawls = root.resolve("data/crawls");
            if (Files.isSymbolicLink(crawls)) throw new IllegalArgumentException("Unsafe crawl directory");
            if (Files.isDirectory(crawls)) {
                try (var bases = Files.list(crawls)) {
                    for (Path base : bases.toList()) {
                        if (Files.isSymbolicLink(base)) throw new IllegalArgumentException("Unsafe knowledge-base directory: " + base);
                        Path graph = base.resolve("graph.kgraph");
                        if (Files.isSymbolicLink(graph)) throw new IllegalArgumentException("Unsafe graph path: " + graph);
                        if (Files.isRegularFile(graph) && new LocalProjectGraphBackend(MAPPER).removeCodeProjection(graph, projectId)) {
                            updatedGraphs++;
                        }
                    }
                }
            }
            if (registration != null) {
                registration.setLifecycle(KompileProjectLifecycleState.ARCHIVED);
                registration.getMetadata().put("codeProjectionState", "REMOVED");
                registration.getMetadata().remove(META_PROJECTED_GENERATION);
                registration.getMetadata().remove(META_PROJECTION_VERSION);
                store.registerCodingProject(root, registration);
            }
            try (var ignored = IndexLockManager.acquireWriteLock(projectId, index);
                 var paths = Files.walk(index)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    if (!path.equals(index) && !path.equals(marker) && !path.equals(index.resolve("project.lock"))) {
                        Files.deleteIfExists(path);
                    }
                }
            }
            return updatedGraphs;
        };
        // publish() never writes into a directory it refuses, so there is no publication to
        // serialize with, and taking the lock would leave a .kompile directory behind in it.
        return manifestRoot.isEmpty() && autoInitRefusal(requested) != null
                ? removal.call() : withPublicationLock(root, removal);
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

    /** The deepest ACTIVE registration whose root strictly contains {@code directory}, or null. */
    private static KompileCodingProject findEnclosingRoot(KompileProjectManifest manifest,
                                                          Path projectRoot, Path directory) {
        if (manifest == null || manifest.getCodingProjects() == null) return null;
        KompileCodingProject deepest = null;
        int deepestDepth = -1;
        for (KompileCodingProject candidate : manifest.getCodingProjects()) {
            if (candidate == null || candidate.getLifecycle() != null
                    && candidate.getLifecycle() != KompileProjectLifecycleState.ACTIVE) continue;
            Path root = registeredRoot(candidate, projectRoot);
            if (root == null || root.equals(directory) || !directory.startsWith(root)) continue;
            if (root.getNameCount() > deepestDepth) {
                deepest = candidate;
                deepestDepth = root.getNameCount();
            }
        }
        return deepest;
    }

    /** A registration's root as a canonical path, or null when it is blank or unparseable. */
    private static Path registeredRoot(KompileCodingProject project, Path projectRoot) {
        String configured = firstNonBlank(project.getRootPath());
        if (configured == null) return null;
        try {
            Path root = Path.of(configured);
            if (!root.isAbsolute()) root = projectRoot.resolve(root);
            try {
                return root.toRealPath();
            } catch (IOException unavailable) {
                return root.toAbsolutePath().normalize();
            }
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * Why publication may not create a project manifest at {@code root}, or null when it may:
     * only a checkout's top level, or a directory outside any checkout, is initialised implicitly.
     */
    private static String autoInitRefusal(Path root) {
        Optional<Path> initRoot = ProjectAutoDetection.autoInitRoot(root);
        if (initRoot.isEmpty()) {
            return "no kompile project covers " + root + "; run `kompile project init` in the "
                    + "project's top-level directory to publish its code graph";
        }
        if (!initRoot.get().equals(root)) {
            return root + " is inside the checkout " + initRoot.get() + ", which has no kompile project; "
                    + "index " + initRoot.get() + " or run `kompile project init` there to publish its code graph";
        }
        return null;
    }

    /** The committed index's metadata, or null when it is missing or unreadable. */
    private static Map<String, Object> indexMetadata(String projectId) {
        try {
            Map<String, Object> metadata =
                    new IndexFileStore(LocalCodeIndexer.getIndexDir(projectId), MAPPER).loadMetadata();
            return metadata.isEmpty() ? null : metadata;
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static String metadataString(Object value) {
        return value == null ? null : value.toString();
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

    /**
     * Outcome of one publication. A skipped publication carries its reason and no graph: the code
     * index itself is committed and searchable, only its KGraph slice was not written.
     */
    public record ProjectionResult(Path graphPath,
                                   String knowledgeBaseId,
                                   Long factSheetId,
                                   int graphEntities,
                                   int graphRelations,
                                   int codeEntities,
                                   String skippedReason) {

        public ProjectionResult(Path graphPath, String knowledgeBaseId, Long factSheetId,
                                int graphEntities, int graphRelations, int codeEntities) {
            this(graphPath, knowledgeBaseId, factSheetId, graphEntities, graphRelations, codeEntities, null);
        }

        static ProjectionResult skipped(String reason) {
            return new ProjectionResult(null, null, null, 0, 0, 0, reason);
        }

        /** True when a graph was written, or an up-to-date one was confirmed. */
        public boolean published() {
            return skippedReason == null;
        }
    }
}
