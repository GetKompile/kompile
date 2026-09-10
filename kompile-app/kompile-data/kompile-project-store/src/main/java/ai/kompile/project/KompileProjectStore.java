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
package ai.kompile.project;

import ai.kompile.cli.common.util.GitRunner;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class KompileProjectStore {
    public static final String MANIFEST_FILE = "kompile.project.json";
    public static final String METADATA_DIR = ".kompile";
    public static final String PROJECT_METADATA_DIR = ".kompile/project";
    public static final String OPEN_STATE_FILE = ".kompile/project/open.json";
    private static final Map<Path, ReentrantLock> MANIFEST_LOCKS = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;

    public KompileProjectStore() {
        this.mapper = JsonUtils.newStandardMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public KompileProjectManifest init(Path root, KompileProjectInitRequest request) {
        Path normalizedRoot = normalizeRoot(root);
        KompileProjectInitRequest req = request == null ? new KompileProjectInitRequest() : request;
        try {
            Files.createDirectories(normalizedRoot);
            ensureStandardDirectories(normalizedRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create project directories under " + normalizedRoot + ": " + e.getMessage(), e);
        }
        // Write the canonical .gitignore unconditionally so the project has a
        // consistent ignore policy even before (or without) a git repository.
        // ensureGitRepository's later call is a no-op once the file exists.
        writeGitignore(normalizedRoot);

        KompileProjectManifest manifest = manifestPath(normalizedRoot).toFile().isFile()
                ? load(normalizedRoot)
                : new KompileProjectManifest();
        Instant now = Instant.now();
        if (manifest.getProjectId() == null || manifest.getProjectId().isBlank()) {
            manifest.setProjectId(UUID.randomUUID().toString());
        }
        if (manifest.getCreatedAt() == null) {
            manifest.setCreatedAt(now);
        }
        manifest.setUpdatedAt(now);
        manifest.setName(firstNonBlank(req.getName(), normalizedRoot.getFileName() != null ? normalizedRoot.getFileName().toString() : "kompile-project"));
        manifest.setDescription(firstNonBlank(req.getDescription(), manifest.getDescription()));
        manifest.setLifecycle(KompileProjectLifecycleState.ACTIVE);
        manifest.setTags(normalizeTags(req.getTags()));
        manifest.setModules(normalizeTags(req.getModules()));

        KompileProjectRepository repository = manifest.getRepository();
        repository.setBackend(req.getBackend());
        repository.setBranch(req.getBranch());
        repository.setRemoteUrl(trimToNull(req.getRemoteUrl()));
        repository.setGitXetEnabled(req.getBackend() == KompileProjectStorageBackend.GIT_XET);
        manifest.setRepository(repository);

        if (req.isIncludeStandardComponents()) {
            upsertStandardComponents(manifest);
            upsertStandardScripts(manifest);
        }
        for (KompileProjectComponent component : req.getComponents()) {
            upsertComponent(manifest, component);
        }
        for (KompileProjectModel model : req.getModels()) {
            upsertModel(manifest, model);
        }
        for (KompileProjectPipeline pipeline : req.getPipelines()) {
            upsertPipeline(manifest, pipeline);
        }
        for (KompileProjectScript script : req.getScripts()) {
            upsertScript(manifest, script);
        }
        for (KompileProjectCrawlProfile crawlProfile : req.getCrawlProfiles()) {
            upsertCrawlProfile(manifest, crawlProfile);
        }
        if (req.isIncludeStandardComponents()) {
            upsertStandardWorkflows(manifest);
        }
        for (KompileProjectWorkflow workflow : req.getWorkflows()) {
            upsertWorkflow(manifest, workflow);
        }
        for (KompileCodingProject cp : req.getCodingProjects()) {
            upsertCodingProject(normalizedRoot, manifest, cp);
        }

        save(normalizedRoot, manifest);

        boolean wantsGit = req.isInitializeGit()
                || req.getBackend() == KompileProjectStorageBackend.GIT
                || req.getBackend() == KompileProjectStorageBackend.GIT_XET
                || trimToNull(req.getRemoteUrl()) != null;
        if (wantsGit) {
            ensureGitRepository(normalizedRoot, repository, req.isInstallGitXet());
        }
        openProject(normalizedRoot);
        return manifest;
    }

    public Optional<Path> findProjectRoot(Path start) {
        if (start == null) {
            return Optional.empty();
        }
        Path current = start.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (Files.isRegularFile(manifestPath(current))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    public KompileProjectManifest load(Path root) {
        Path manifestPath = manifestPath(normalizeRoot(root));
        try {
            return mapper.readValue(manifestPath.toFile(), KompileProjectManifest.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + manifestPath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Bring generated project lifecycle files forward after application personas are split into
     * separate processes. Only generator-owned manifest entries and byte-for-byte legacy generated
     * scripts are changed; custom workflows and edited scripts remain untouched.
     */
    public KompileProjectManifest ensureStandardServiceLifecycle(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        KompileProjectManifest manifest = load(normalizedRoot);
        boolean hasGeneratedLifecycle = manifest.getScripts().stream()
                .anyMatch(script -> script.isGenerated() && isStandardServiceScript(script.getId()))
                || findWorkflow(manifest, "start-services")
                .map(KompileProjectWorkflow::isGenerated)
                .orElse(false);
        if (!hasGeneratedLifecycle) {
            return manifest;
        }

        try {
            writeStandardLifecycleScriptFiles(normalizedRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to upgrade service lifecycle scripts under "
                    + normalizedRoot + ": " + e.getMessage(), e);
        }

        boolean changed = false;
        KompileProjectManifest standards = new KompileProjectManifest();
        upsertStandardScripts(standards);
        for (KompileProjectScript standard : standards.getScripts()) {
            if (findScript(manifest, standard.getId()).isEmpty()) {
                upsertScript(manifest, standard);
                changed = true;
            }
        }

        KompileProjectWorkflow standardStart = standardStartWorkflow();
        Optional<KompileProjectWorkflow> existingStart = findWorkflow(manifest, "start-services");
        if (existingStart.isEmpty()) {
            upsertWorkflow(manifest, standardStart);
            changed = true;
        } else if (existingStart.get().isGenerated()
                && !isCurrentStandardStartWorkflow(existingStart.get())) {
            upsertWorkflow(manifest, standardStart);
            changed = true;
        }

        if (changed) {
            save(normalizedRoot, manifest);
        }
        return manifest;
    }

    private boolean isStandardServiceScript(String id) {
        return Set.of("start-all", "stop-all", "start-staging", "start-serving", "start-app",
                "start-chat", "start-crawl-manager").contains(id);
    }

    private boolean isCurrentStandardStartWorkflow(KompileProjectWorkflow workflow) {
        List<String> refs = workflow.getSteps().stream()
                .filter(step -> "SCRIPT".equalsIgnoreCase(step.getType()))
                .map(KompileProjectWorkflowStep::getRef)
                .toList();
        return refs.equals(List.of("start-staging", "start-serving", "start-app", "start-chat",
                "start-crawl-manager"));
    }

    public void save(Path root, KompileProjectManifest manifest) {
        Path normalizedRoot = normalizeRoot(root);
        withManifestLock(normalizedRoot, () -> {
            saveManifestLocked(normalizedRoot, manifest);
            return null;
        });
        autoCommitIfEnabled(normalizedRoot, manifest);
    }

    public KompileProjectManifest updateManifest(Path root, Consumer<KompileProjectManifest> update) {
        Path normalizedRoot = normalizeRoot(root);
        KompileProjectManifest manifest = withManifestLock(normalizedRoot, () -> {
            KompileProjectManifest current = load(normalizedRoot);
            update.accept(current);
            saveManifestLocked(normalizedRoot, current);
            return current;
        });
        autoCommitIfEnabled(normalizedRoot, manifest);
        return manifest;
    }

    private void saveManifestLocked(Path normalizedRoot, KompileProjectManifest manifest) {
        Path manifestPath = manifestPath(normalizedRoot);
        try {
            Files.createDirectories(normalizedRoot);
            if (Files.isRegularFile(manifestPath)) {
                mergeConcurrentGraphState(load(normalizedRoot), manifest);
            }
            manifest.setUpdatedAt(Instant.now());
            Path temporary = Files.createTempFile(normalizedRoot, ".kompile-project-", ".tmp");
            try {
                mapper.writeValue(temporary.toFile(), manifest);
                try {
                    Files.move(temporary, manifestPath, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, manifestPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            syncProjectRegistries(normalizedRoot, manifest);
            syncMarkdownCatalogQuietly(normalizedRoot);
            syncCrawlCatalogQuietly(normalizedRoot);
            syncSourcesCatalogQuietly(normalizedRoot);
            syncPromptTemplateCatalogQuietly(normalizedRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write " + manifestPath + ": " + e.getMessage(), e);
        }
    }

    private void mergeConcurrentGraphState(KompileProjectManifest current,
                                           KompileProjectManifest update) {
        if (current == null || update == null) return;
        for (KompileCodingProject existing : current.getCodingProjects()) {
            KompileCodingProject incoming = update.getCodingProjects().stream()
                    .filter(candidate -> Objects.equals(candidate.getId(), existing.getId())
                            || Objects.equals(candidate.getCodeProjectId(), existing.getCodeProjectId()))
                    .findFirst().orElse(null);
            if (incoming == null) {
                update.getCodingProjects().add(existing);
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>(existing.getMetadata());
            metadata.putAll(incoming.getMetadata());
            incoming.setMetadata(metadata);
            if (incoming.getFactSheetId() == null) incoming.setFactSheetId(existing.getFactSheetId());
        }
        for (KompileProjectCrawlProfile existing : current.getCrawlProfiles()) {
            KompileProjectCrawlProfile incoming = update.getCrawlProfiles().stream()
                    .filter(candidate -> Objects.equals(candidate.getId(), existing.getId()))
                    .findFirst().orElse(null);
            if (incoming == null) {
                update.getCrawlProfiles().add(existing);
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>(existing.getMetadata());
            metadata.putAll(incoming.getMetadata());
            incoming.setMetadata(metadata);
        }
    }

    private <T> T withManifestLock(Path root, ManifestOperation<T> operation) {
        Path manifestPath = manifestPath(root).toAbsolutePath().normalize();
        ReentrantLock processLock = MANIFEST_LOCKS.computeIfAbsent(manifestPath,
                ignored -> new ReentrantLock());
        processLock.lock();
        try {
            Files.createDirectories(root.resolve(METADATA_DIR));
            Path lockPath = root.resolve(METADATA_DIR).resolve("project-manifest.lock");
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.run();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to lock project manifest: " + e.getMessage(), e);
        } finally {
            processLock.unlock();
        }
    }

    @FunctionalInterface
    private interface ManifestOperation<T> {
        T run();
    }

    /**
     * If the project has a git repo and autoCommit is enabled, commit all changes.
     * This runs after every save so that manifest, registry, and catalog changes
     * are automatically committed without requiring an explicit {@code project commit}.
     */
    private void autoCommitIfEnabled(Path root, KompileProjectManifest manifest) {
        KompileProjectRepository repo = manifest.getRepository();
        if (!repo.isAutoCommit()) return;
        if (!Files.isDirectory(root.resolve(".git"))) return;
        try {
            KompileProjectGitResult status = runProcess(root, true, "git", "status", "--porcelain");
            if (status.getOutput() == null || status.getOutput().isBlank()) return;
            gitCommitAll(root, "Auto-commit: update project state");
        } catch (Exception e) {
            // Auto-commit is best-effort — don't fail the save
        }
    }

    private void syncMarkdownCatalogQuietly(Path root) {
        try {
            if (Files.isDirectory(root.resolve("data/markdown"))) {
                syncMarkdownCatalog(root);
            }
        } catch (RuntimeException e) {
            // catalog sync is best-effort during save
        }
    }

    public KompileProjectStatus status(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        KompileProjectStatus status = new KompileProjectStatus();
        status.setRoot(normalizedRoot.toString());
        status.setManifestPath(manifestPath(normalizedRoot).toString());
        status.setManifestPresent(Files.isRegularFile(manifestPath(normalizedRoot)));
        status.setMetadataPath(projectMetadataDirectory(normalizedRoot).toString());
        status.setMetadataPresent(Files.isDirectory(projectMetadataDirectory(normalizedRoot)));
        status.setOpenStatePath(openStatePath(normalizedRoot).toString());
        status.setGitRepository(Files.isDirectory(normalizedRoot.resolve(".git")));
        status.setGitXetAvailable(isGitXetAvailable());

        KompileProjectManifest manifest = null;
        if (status.isManifestPresent()) {
            manifest = load(normalizedRoot);
            status.setComponentCount(manifest.getComponents().size());
            status.setCodingProjectCount(manifest.getCodingProjects().size());
            status.setModelCount(manifest.getModels().size());
            status.setPipelineCount(manifest.getPipelines().size());
            status.setScriptCount(manifest.getScripts().size());
            status.setCrawlProfileCount(manifest.getCrawlProfiles().size());
            status.setWorkflowCount(manifest.getWorkflows().size());
            status.setGitXetEnabled(manifest.getRepository().isGitXetEnabled()
                    || manifest.getRepository().getBackend() == KompileProjectStorageBackend.GIT_XET);
        }
        try {
            status.setMarkdownCount(listMarkdown(normalizedRoot).size());
        } catch (RuntimeException e) {
            status.setMarkdownCount(0);
        }
        try {
            status.setCrawlResultCount(listCrawlResults(normalizedRoot).size());
        } catch (RuntimeException e) {
            status.setCrawlResultCount(0);
        }
        try {
            status.setSourceDocumentCount(listSourceDocuments(normalizedRoot).size());
        } catch (RuntimeException e) {
            status.setSourceDocumentCount(0);
        }
        try {
            status.setPromptTemplateCount(listPromptTemplates(normalizedRoot).size());
        } catch (RuntimeException e) {
            status.setPromptTemplateCount(0);
        }
        try {
            status.setFactSheetCount(listFactSheets(normalizedRoot).size());
        } catch (RuntimeException e) {
            status.setFactSheetCount(0);
        }
        try {
            status.setChatSessionCount(listChatSessions(normalizedRoot).size());
        } catch (RuntimeException e) {
            status.setChatSessionCount(0);
        }
        try {
            status.setNoteSyncConnectionCount(listNoteSyncConnections(normalizedRoot).size());
        } catch (RuntimeException e) {
            status.setNoteSyncConnectionCount(0);
        }
        try {
            status.setIndexedDocumentCount(listIndexedDocuments(normalizedRoot).size());
        } catch (RuntimeException e) {
            status.setIndexedDocumentCount(0);
        }
        Optional<KompileProjectOpenState> openState = readOpenStateQuietly(normalizedRoot);
        if (openState.isPresent()) {
            KompileProjectOpenState state = openState.get();
            status.setOpenProjectId(state.getProjectId());
            status.setOpenedAt(state.getOpenedAt());
            status.setOpenStateUpdatedAt(state.getUpdatedAt());
            status.setOpen(manifest != null && manifest.getProjectId() != null
                    && manifest.getProjectId().equals(state.getProjectId()));
        }
        if (status.isGitRepository()) {
            KompileProjectGitResult dirty = runProcess(normalizedRoot, true, "git", "status", "--porcelain");
            status.setGitDirty(dirty.getExitCode() == 0 && !dirty.getOutput().isBlank());
            status.setBranch(trimToNull(runProcess(normalizedRoot, true, "git", "branch", "--show-current").getOutput()));
            KompileProjectGitResult remote = runProcess(normalizedRoot, true, "git", "remote", "get-url", "origin");
            status.setRemoteUrl(remote.getExitCode() == 0 ? trimToNull(remote.getOutput()) : null);
        }
        return status;
    }

    public KompileProjectManifest addComponent(Path root, KompileProjectComponent component) {
        Path normalizedRoot = normalizeRoot(root);
        KompileProjectManifest manifest = updateManifest(normalizedRoot,
                current -> upsertComponent(current, component));
        createComponentDirectory(normalizedRoot, component);
        return manifest;
    }

    public KompileProjectManifest registerCodingProject(Path root, KompileCodingProject codingProject) {
        Path normalizedRoot = normalizeRoot(root);
        return updateManifest(normalizedRoot,
                manifest -> upsertCodingProject(normalizedRoot, manifest, codingProject));
    }

    public KompileProjectManifest registerModel(Path root, KompileProjectModel model) {
        Path normalizedRoot = normalizeRoot(root);
        return updateManifest(normalizedRoot, manifest -> upsertModel(manifest, model));
    }

    public KompileProjectManifest registerPipeline(Path root, KompileProjectPipeline pipeline) {
        Path normalizedRoot = normalizeRoot(root);
        return updateManifest(normalizedRoot, manifest -> upsertPipeline(manifest, pipeline));
    }

    public KompileProjectManifest syncProjectRegistries(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        KompileProjectManifest manifest = load(normalizedRoot);
        syncProjectRegistries(normalizedRoot, manifest);
        return manifest;
    }

    /**
     * Lists all markdown files under the project's {@code data/markdown/} directory.
     * Returns entries sorted by relative path, each with title, tags, and timestamps
     * extracted from YAML frontmatter when present.
     */
    public List<KompileProjectMarkdownEntry> listMarkdown(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        Path markdownDir = normalizedRoot.resolve("data/markdown");
        if (!Files.isDirectory(markdownDir)) {
            return List.of();
        }
        List<KompileProjectMarkdownEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(markdownDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".md") || name.endsWith(".markdown");
                    })
                    .sorted(Comparator.comparing(p -> markdownDir.relativize(p).toString()))
                    .forEach(p -> {
                        try {
                            String relativePath = markdownDir.relativize(p).toString().replace('\\', '/');
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            entries.add(parseMarkdownEntry(relativePath, content));
                        } catch (IOException e) {
                            // skip unreadable files
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list markdown files under " + markdownDir + ": " + e.getMessage(), e);
        }
        return entries;
    }

    /**
     * Reads a single markdown file from the project's {@code data/markdown/} directory.
     *
     * @param relativePath path relative to {@code data/markdown/}, e.g. "notes/intro.md"
     */
    public Optional<KompileProjectMarkdownEntry> readMarkdown(Path root, String relativePath) {
        Path normalizedRoot = normalizeRoot(root);
        Path markdownDir = normalizedRoot.resolve("data/markdown");
        String sanitized = sanitizeRelativePath(relativePath);
        Path file = markdownDir.resolve(sanitized).normalize();
        if (!file.startsWith(markdownDir)) {
            throw new IllegalArgumentException("Markdown path escapes project: " + relativePath);
        }
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(parseMarkdownEntry(sanitized, content));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read markdown file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Searches markdown files under the project's {@code data/markdown/} directory
     * for entries whose title, tags, or body contain the given query (case-insensitive).
     */
    public List<KompileProjectMarkdownEntry> searchMarkdown(Path root, String query) {
        if (trimToNull(query) == null) {
            return listMarkdown(root);
        }
        String lowerQuery = query.trim().toLowerCase(Locale.ROOT);
        return listMarkdown(root).stream()
                .filter(entry -> {
                    String title = entry.getTitle() == null ? "" : entry.getTitle().toLowerCase(Locale.ROOT);
                    String tags = entry.getTags() == null ? "" : entry.getTags().toLowerCase(Locale.ROOT);
                    String body = entry.getBody() == null ? "" : entry.getBody().toLowerCase(Locale.ROOT);
                    return title.contains(lowerQuery) || tags.contains(lowerQuery) || body.contains(lowerQuery);
                })
                .collect(Collectors.toList());
    }

    /**
     * Writes or replaces a markdown file under the project's {@code data/markdown/} directory.
     */
    public void writeMarkdown(Path root, String relativePath, String content) {
        Path normalizedRoot = normalizeRoot(root);
        Path markdownDir = normalizedRoot.resolve("data/markdown");
        String sanitized = sanitizeRelativePath(relativePath);
        Path file = markdownDir.resolve(sanitized).normalize();
        if (!file.startsWith(markdownDir)) {
            throw new IllegalArgumentException("Markdown path escapes project: " + relativePath);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write markdown file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Syncs the markdown catalog to {@code data/markdown/project-markdown.json}.
     * Called automatically during {@link #save(Path, KompileProjectManifest)}.
     */
    public void syncMarkdownCatalog(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        List<KompileProjectMarkdownEntry> entries = listMarkdown(normalizedRoot);
        Path catalogPath = normalizedRoot.resolve("data/markdown/project-markdown.json");
        try {
            Files.createDirectories(catalogPath.getParent());
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("updatedAt", Instant.now().toString());
            catalog.put("count", entries.size());
            List<Map<String, Object>> items = new ArrayList<>();
            for (KompileProjectMarkdownEntry entry : entries) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", entry.getPath());
                item.put("title", entry.getTitle());
                if (trimToNull(entry.getTags()) != null) {
                    item.put("tags", entry.getTags());
                }
                if (entry.getCreatedAt() != null) {
                    item.put("createdAt", entry.getCreatedAt());
                }
                if (entry.getUpdatedAt() != null) {
                    item.put("updatedAt", entry.getUpdatedAt());
                }
                if (trimToNull(entry.getSource()) != null) {
                    item.put("source", entry.getSource());
                }
                if (trimToNull(entry.getSourcePath()) != null) {
                    item.put("sourcePath", entry.getSourcePath());
                }
                if (trimToNull(entry.getContentType()) != null) {
                    item.put("contentType", entry.getContentType());
                }
                if (trimToNull(entry.getConverter()) != null) {
                    item.put("converter", entry.getConverter());
                }
                if (trimToNull(entry.getCrawlProfile()) != null) {
                    item.put("crawlProfile", entry.getCrawlProfile());
                }
                if (trimToNull(entry.getFactSheet()) != null) {
                    item.put("factSheet", entry.getFactSheet());
                }
                if (trimToNull(entry.getCollection()) != null) {
                    item.put("collection", entry.getCollection());
                }
                if (trimToNull(entry.getProject()) != null) {
                    item.put("project", entry.getProject());
                }
                items.add(item);
            }
            catalog.put("files", items);
            mapper.writeValue(catalogPath.toFile(), catalog);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to sync markdown catalog: " + e.getMessage(), e);
        }
    }

    /**
     * Lists crawl results by reading {@code crawl-result.json} files under {@code data/crawls/}.
     */
    public List<KompileProjectCrawlResult> listCrawlResults(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        Path crawlsDir = normalizedRoot.resolve("data/crawls");
        if (!Files.isDirectory(crawlsDir)) {
            return List.of();
        }
        List<KompileProjectCrawlResult> results = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(crawlsDir)) {
            dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(dir -> {
                        Path resultFile = dir.resolve("crawl-result.json");
                        if (Files.isRegularFile(resultFile)) {
                            try {
                                results.add(mapper.readValue(resultFile.toFile(), KompileProjectCrawlResult.class));
                            } catch (IOException e) {
                                // skip unreadable crawl results
                            }
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list crawl results under " + crawlsDir + ": " + e.getMessage(), e);
        }
        return results;
    }

    /**
     * Writes {@code data/crawls/project-crawls.json} — a catalog of all completed crawl results.
     * Analogous to {@link #syncMarkdownCatalog(Path)} for markdown files.
     */
    public void syncCrawlCatalog(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        List<KompileProjectCrawlResult> results = listCrawlResults(normalizedRoot);
        Path catalogPath = normalizedRoot.resolve("data/crawls/project-crawls.json");
        try {
            Files.createDirectories(catalogPath.getParent());
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("updatedAt", Instant.now().toString());
            catalog.put("count", results.size());
            catalog.put("crawls", results);
            mapper.writeValue(catalogPath.toFile(), catalog);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to sync crawl catalog: " + e.getMessage(), e);
        }
    }

    private void syncCrawlCatalogQuietly(Path root) {
        try {
            if (Files.isDirectory(root.resolve("data/crawls"))) {
                syncCrawlCatalog(root);
            }
        } catch (RuntimeException e) {
            // catalog sync is best-effort during save
        }
    }

    // ── Source document catalog ──────────────────────────────────────────────

    /**
     * Lists source documents under {@code data/input_documents/}.
     */
    public List<KompileProjectSourceDocument> listSourceDocuments(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        Path sourcesDir = normalizedRoot.resolve("data/input_documents");
        if (!Files.isDirectory(sourcesDir)) {
            return List.of();
        }
        List<KompileProjectSourceDocument> docs = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sourcesDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("project-sources.json"))
                    .sorted(Comparator.comparing(p -> sourcesDir.relativize(p).toString()))
                    .forEach(p -> {
                        try {
                            KompileProjectSourceDocument doc = new KompileProjectSourceDocument();
                            doc.setPath(sourcesDir.relativize(p).toString().replace('\\', '/'));
                            doc.setFileName(p.getFileName().toString());
                            doc.setSizeBytes(Files.size(p));
                            doc.setContentType(guessContentType(p.getFileName().toString()));
                            doc.setLastModified(Files.getLastModifiedTime(p).toInstant().toString());
                            docs.add(doc);
                        } catch (IOException ignored) { }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list source documents under " + sourcesDir + ": " + e.getMessage(), e);
        }
        return docs;
    }

    /**
     * Writes {@code data/input_documents/project-sources.json}.
     */
    public void syncSourcesCatalog(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        List<KompileProjectSourceDocument> docs = listSourceDocuments(normalizedRoot);
        Path catalogPath = normalizedRoot.resolve("data/input_documents/project-sources.json");
        try {
            Files.createDirectories(catalogPath.getParent());
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("updatedAt", Instant.now().toString());
            catalog.put("count", docs.size());
            long totalSize = docs.stream().mapToLong(KompileProjectSourceDocument::getSizeBytes).sum();
            catalog.put("totalSizeBytes", totalSize);
            catalog.put("files", docs);
            mapper.writeValue(catalogPath.toFile(), catalog);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to sync sources catalog: " + e.getMessage(), e);
        }
    }

    private void syncSourcesCatalogQuietly(Path root) {
        try {
            if (Files.isDirectory(root.resolve("data/input_documents"))) {
                syncSourcesCatalog(root);
            }
        } catch (RuntimeException ignored) { }
    }

    // ── Prompt template catalog ───────────────────────────────────────────

    /**
     * Lists prompt template summaries from {@code data/prompt-templates/*.json}.
     */
    public List<KompileProjectPromptTemplate> listPromptTemplates(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        Path templatesDir = normalizedRoot.resolve("data/prompt-templates");
        if (!Files.isDirectory(templatesDir)) {
            return List.of();
        }
        List<KompileProjectPromptTemplate> templates = new ArrayList<>();
        try (Stream<Path> stream = Files.list(templatesDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json")
                            && !p.getFileName().toString().equals("project-prompts.json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            templates.add(mapper.readValue(p.toFile(), KompileProjectPromptTemplate.class));
                        } catch (IOException ignored) { }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list prompt templates under " + templatesDir + ": " + e.getMessage(), e);
        }
        return templates;
    }

    /**
     * Writes {@code data/prompt-templates/project-prompts.json}.
     */
    public void syncPromptTemplateCatalog(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        List<KompileProjectPromptTemplate> templates = listPromptTemplates(normalizedRoot);
        Path catalogPath = normalizedRoot.resolve("data/prompt-templates/project-prompts.json");
        try {
            Files.createDirectories(catalogPath.getParent());
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("updatedAt", Instant.now().toString());
            catalog.put("count", templates.size());
            catalog.put("templates", templates);
            mapper.writeValue(catalogPath.toFile(), catalog);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to sync prompt template catalog: " + e.getMessage(), e);
        }
    }

    private void syncPromptTemplateCatalogQuietly(Path root) {
        try {
            if (Files.isDirectory(root.resolve("data/prompt-templates"))) {
                syncPromptTemplateCatalog(root);
            }
        } catch (RuntimeException ignored) { }
    }

    // ── Chat session catalog ──────────────────────────────────────────────

    /**
     * Lists chat session summaries exported to {@code data/chats/}.
     * Reads individual session JSON files or a combined catalog.
     */
    public List<KompileProjectChatSession> listChatSessions(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        Path chatsDir = normalizedRoot.resolve("data/chats");
        if (!Files.isDirectory(chatsDir)) {
            return List.of();
        }
        // Try reading from catalog first
        Path catalogPath = chatsDir.resolve("project-chats.json");
        if (Files.isRegularFile(catalogPath)) {
            try {
                Map<?, ?> catalog = mapper.readValue(catalogPath.toFile(), Map.class);
                Object sessions = catalog.get("sessions");
                if (sessions instanceof List) {
                    return mapper.convertValue(sessions,
                            mapper.getTypeFactory().constructCollectionType(List.class, KompileProjectChatSession.class));
                }
            } catch (IOException ignored) { }
        }
        // Fall back to scanning individual session JSON files
        List<KompileProjectChatSession> sessions = new ArrayList<>();
        try (Stream<Path> stream = Files.list(chatsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json")
                            && !p.getFileName().toString().equals("project-chats.json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            sessions.add(mapper.readValue(p.toFile(), KompileProjectChatSession.class));
                        } catch (IOException ignored) { }
                    });
        } catch (IOException ignored) { }
        return sessions;
    }

    /**
     * Writes a chat session catalog to {@code data/chats/project-chats.json}.
     * Called by the backend when exporting sessions.
     */
    public void writeChatCatalog(Path root, List<KompileProjectChatSession> sessions) {
        Path normalizedRoot = normalizeRoot(root);
        Path catalogPath = normalizedRoot.resolve("data/chats/project-chats.json");
        try {
            Files.createDirectories(catalogPath.getParent());
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("updatedAt", Instant.now().toString());
            catalog.put("count", sessions.size());
            catalog.put("sessions", sessions);
            mapper.writeValue(catalogPath.toFile(), catalog);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write chat catalog: " + e.getMessage(), e);
        }
    }

    // ── Fact sheet catalog ────────────────────────────────────────────────

    /**
     * Lists fact sheet snapshots from {@code data/fact-sheets/project-fact-sheets.json}.
     * This file is written by the backend (which has access to the JPA entities).
     */
    public List<KompileProjectFactSheet> listFactSheets(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        Path catalogPath = normalizedRoot.resolve("data/fact-sheets/project-fact-sheets.json");
        if (!Files.isRegularFile(catalogPath)) {
            return List.of();
        }
        try {
            Map<?, ?> catalog = mapper.readValue(catalogPath.toFile(), Map.class);
            Object sheets = catalog.get("factSheets");
            if (sheets instanceof List) {
                return mapper.convertValue(sheets,
                        mapper.getTypeFactory().constructCollectionType(List.class, KompileProjectFactSheet.class));
            }
            return List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Writes fact sheet catalog to {@code data/fact-sheets/project-fact-sheets.json}.
     * Called by the backend when exporting fact sheets.
     */
    public void writeFactSheetCatalog(Path root, List<KompileProjectFactSheet> factSheets) {
        Path normalizedRoot = normalizeRoot(root);
        Path catalogPath = normalizedRoot.resolve("data/fact-sheets/project-fact-sheets.json");
        try {
            Files.createDirectories(catalogPath.getParent());
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("updatedAt", Instant.now().toString());
            catalog.put("count", factSheets.size());
            catalog.put("factSheets", factSheets);
            mapper.writeValue(catalogPath.toFile(), catalog);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write fact sheet catalog: " + e.getMessage(), e);
        }
    }

    // ── Note sync catalog ─────────────────────────────────────────────────

    /**
     * Lists note sync connections from {@code data/note-sync/project-note-sync.json}.
     * Written by the backend.
     */
    public List<KompileProjectNoteSyncConnection> listNoteSyncConnections(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        Path catalogPath = normalizedRoot.resolve("data/note-sync/project-note-sync.json");
        if (!Files.isRegularFile(catalogPath)) {
            return List.of();
        }
        try {
            Map<?, ?> catalog = mapper.readValue(catalogPath.toFile(), Map.class);
            Object connections = catalog.get("connections");
            if (connections instanceof List) {
                return mapper.convertValue(connections,
                        mapper.getTypeFactory().constructCollectionType(List.class, KompileProjectNoteSyncConnection.class));
            }
            return List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Writes note sync connection catalog to {@code data/note-sync/project-note-sync.json}.
     * Called by the backend.
     */
    public void writeNoteSyncCatalog(Path root, List<KompileProjectNoteSyncConnection> connections) {
        Path normalizedRoot = normalizeRoot(root);
        Path catalogPath = normalizedRoot.resolve("data/note-sync/project-note-sync.json");
        try {
            Files.createDirectories(catalogPath.getParent());
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("updatedAt", Instant.now().toString());
            catalog.put("count", connections.size());
            catalog.put("connections", connections);
            mapper.writeValue(catalogPath.toFile(), catalog);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write note sync catalog: " + e.getMessage(), e);
        }
    }

    public List<KompileProjectIndexedDocument> listIndexedDocuments(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        Path catalogPath = normalizedRoot.resolve("data/indexed-documents/project-indexed-documents.json");
        if (!Files.isRegularFile(catalogPath)) return new ArrayList<>();
        try {
            Map<?, ?> catalog = mapper.readValue(catalogPath.toFile(), Map.class);
            Object docs = catalog.get("documents");
            if (docs == null) return new ArrayList<>();
            return mapper.convertValue(docs, mapper.getTypeFactory()
                    .constructCollectionType(List.class, KompileProjectIndexedDocument.class));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public void writeIndexedDocumentCatalog(Path root, List<KompileProjectIndexedDocument> documents) {
        Path normalizedRoot = normalizeRoot(root);
        Path catalogPath = normalizedRoot.resolve("data/indexed-documents/project-indexed-documents.json");
        try {
            Files.createDirectories(catalogPath.getParent());
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("updatedAt", Instant.now().toString());
            catalog.put("count", documents.size());
            catalog.put("documents", documents);
            mapper.writeValue(catalogPath.toFile(), catalog);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write indexed document catalog: " + e.getMessage(), e);
        }
    }

    public KompileProjectManifest registerScript(Path root, KompileProjectScript script) {
        Path normalizedRoot = normalizeRoot(root);
        return updateManifest(normalizedRoot, manifest -> upsertScript(manifest, script));
    }

    public KompileProjectManifest registerCrawlProfile(Path root, KompileProjectCrawlProfile crawlProfile) {
        Path normalizedRoot = normalizeRoot(root);
        return updateManifest(normalizedRoot, manifest -> upsertCrawlProfile(manifest, crawlProfile));
    }

    public KompileProjectManifest registerWorkflow(Path root, KompileProjectWorkflow workflow) {
        Path normalizedRoot = normalizeRoot(root);
        return updateManifest(normalizedRoot, manifest -> upsertWorkflow(manifest, workflow));
    }

    public KompileProjectManifest setProjectTags(Path root, Collection<String> tags) {
        Path normalizedRoot = normalizeRoot(root);
        return updateManifest(normalizedRoot, manifest -> manifest.setTags(normalizeTags(tags)));
    }

    public KompileProjectManifest setComponentTags(Path root, String componentId, Collection<String> tags) {
        Path normalizedRoot = normalizeRoot(root);
        return updateManifest(normalizedRoot, manifest -> {
            KompileProjectComponent component = findComponent(manifest, componentId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown project component: " + componentId));
            component.setTags(normalizeTags(tags));
            component.setUpdatedAt(Instant.now());
        });
    }

    public KompileProjectManifest setLifecycle(Path root, KompileProjectLifecycleState lifecycle) {
        Path normalizedRoot = normalizeRoot(root);
        return updateManifest(normalizedRoot, manifest -> manifest.setLifecycle(lifecycle));
    }

    public Path cloneRepository(String remoteUrl, Path targetDir, String branch, boolean gitXetEnabled) {
        if (trimToNull(remoteUrl) == null) {
            throw new IllegalArgumentException("remoteUrl is required");
        }
        Path target = normalizeRoot(targetDir);
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Target directory must have a parent: " + target);
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create clone parent " + parent + ": " + e.getMessage(), e);
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("clone");
        if (trimToNull(branch) != null) {
            command.add("--branch");
            command.add(branch.trim());
        }
        command.add(remoteUrl.trim());
        command.add(target.getFileName().toString());
        runProcess(parent, false, command.toArray(String[]::new));
        if (gitXetEnabled) {
            runProcess(target, true, "git", "xet", "install");
        }
        if (!Files.isRegularFile(manifestPath(target))) {
            KompileProjectInitRequest request = new KompileProjectInitRequest();
            request.setName(target.getFileName().toString());
            request.setBackend(gitXetEnabled ? KompileProjectStorageBackend.GIT_XET : KompileProjectStorageBackend.GIT);
            request.setRemoteUrl(remoteUrl);
            request.setBranch(branch);
            init(target, request);
        }
        openProject(target);
        return target;
    }

    public KompileProjectGitResult gitPull(Path root) {
        KompileProjectManifest manifest = load(root);
        String branch = manifest.getRepository().getBranch();
        return runProcess(normalizeRoot(root), false, "git", "pull", "--ff-only", "origin", branch);
    }

    public KompileProjectGitResult gitPush(Path root) {
        KompileProjectManifest manifest = load(root);
        String branch = manifest.getRepository().getBranch();
        return runProcess(normalizeRoot(root), false, "git", "push", "-u", "origin", branch);
    }

    public KompileProjectGitResult gitCommitAll(Path root, String message) {
        Path normalizedRoot = normalizeRoot(root);
        runProcess(normalizedRoot, false, "git", "add", "-A");
        KompileProjectGitResult status = runProcess(normalizedRoot, false, "git", "status", "--porcelain");
        if (status.getOutput().isBlank()) {
            return new KompileProjectGitResult(0, "No changes to commit.");
        }
        return runProcess(normalizedRoot, false,
                "git",
                "-c", "user.name=Kompile",
                "-c", "user.email=kompile@local",
                "commit", "-m", firstNonBlank(message, "Update Kompile project"));
    }

    public boolean isGitXetAvailable() {
        return GitRunner.isGitXetAvailable();
    }

    public Path manifestPath(Path root) {
        return normalizeRoot(root).resolve(MANIFEST_FILE);
    }

    public Path projectMetadataDirectory(Path root) {
        return normalizeRoot(root).resolve(PROJECT_METADATA_DIR);
    }

    public Path openStatePath(Path root) {
        return normalizeRoot(root).resolve(OPEN_STATE_FILE);
    }

    public void ensureProjectMetadataDirectory(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        try {
            ensureProjectMetadataDirectoryInternal(normalizedRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create project metadata directory under "
                    + normalizedRoot.resolve(METADATA_DIR) + ": " + e.getMessage(), e);
        }
    }

    public KompileProjectOpenState openProject(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        KompileProjectManifest manifest = load(normalizedRoot);
        ensureProjectMetadataDirectory(normalizedRoot);
        Optional<KompileProjectOpenState> existing = readOpenStateQuietly(normalizedRoot)
                .filter(state -> manifest.getProjectId() != null && manifest.getProjectId().equals(state.getProjectId()));
        Instant now = Instant.now();
        KompileProjectOpenState state = new KompileProjectOpenState();
        state.setProjectId(manifest.getProjectId());
        state.setName(manifest.getName());
        state.setRoot(normalizedRoot.toString());
        state.setManifestPath(manifestPath(normalizedRoot).toString());
        state.setLifecycle(manifest.getLifecycle());
        state.setOpenedAt(existing.map(KompileProjectOpenState::getOpenedAt).orElse(now));
        state.setUpdatedAt(now);
        try {
            state.setMarkdownCount(listMarkdown(normalizedRoot).size());
        } catch (RuntimeException e) {
            state.setMarkdownCount(0);
        }
        try {
            List<KompileProjectCrawlResult> crawlResults = listCrawlResults(normalizedRoot);
            state.setCrawlResultCount(crawlResults.size());
            crawlResults.stream()
                    .map(KompileProjectCrawlResult::getFinishedAt)
                    .filter(f -> f != null && !f.isBlank())
                    .max(Comparator.naturalOrder())
                    .ifPresent(latest -> {
                        try {
                            state.setLastCrawlAt(Instant.parse(latest));
                        } catch (Exception ignored) { }
                    });
        } catch (RuntimeException e) {
            state.setCrawlResultCount(0);
        }
        try { state.setSourceDocumentCount(listSourceDocuments(normalizedRoot).size()); }
        catch (RuntimeException ignored) { }
        try { state.setPromptTemplateCount(listPromptTemplates(normalizedRoot).size()); }
        catch (RuntimeException ignored) { }
        try { state.setFactSheetCount(listFactSheets(normalizedRoot).size()); }
        catch (RuntimeException ignored) { }
        try { state.setChatSessionCount(listChatSessions(normalizedRoot).size()); }
        catch (RuntimeException ignored) { }
        try { state.setNoteSyncConnectionCount(listNoteSyncConnections(normalizedRoot).size()); }
        catch (RuntimeException ignored) { }
        try { state.setIndexedDocumentCount(listIndexedDocuments(normalizedRoot).size()); }
        catch (RuntimeException ignored) { }
        Map<String, String> metadata = existing.map(KompileProjectOpenState::getMetadata).orElseGet(LinkedHashMap::new);
        metadata.put("source", "kompile-project");
        state.setMetadata(metadata);
        try {
            mapper.writeValue(openStatePath(normalizedRoot).toFile(), state);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write project open state: " + e.getMessage(), e);
        }
        return state;
    }

    public Optional<KompileProjectOpenState> readOpenState(Path root) {
        Path statePath = openStatePath(root);
        if (!Files.isRegularFile(statePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(statePath.toFile(), KompileProjectOpenState.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read project open state " + statePath + ": " + e.getMessage(), e);
        }
    }

    private Optional<KompileProjectOpenState> readOpenStateQuietly(Path root) {
        try {
            return readOpenState(root);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void ensureGitRepository(Path root, KompileProjectRepository repository, boolean installGitXet) {
        if (!Files.isDirectory(root.resolve(".git"))) {
            runProcess(root, false, "git", "init");
        }
        runProcess(root, true, "git", "checkout", "-B", repository.getBranch());
        if (trimToNull(repository.getRemoteUrl()) != null) {
            KompileProjectGitResult existingRemote = runProcess(root, true, "git", "remote", "get-url", "origin");
            if (existingRemote.getExitCode() == 0) {
                runProcess(root, false, "git", "remote", "set-url", "origin", repository.getRemoteUrl());
            } else {
                runProcess(root, false, "git", "remote", "add", "origin", repository.getRemoteUrl());
            }
        }
        writeGitignore(root);
        boolean xetRequested = repository.isGitXetEnabled() || repository.getBackend() == KompileProjectStorageBackend.GIT_XET;
        boolean xetAvailable = xetRequested && isGitXetAvailable();
        if (xetRequested && !xetAvailable) {
            System.err.println("Warning: git-xet backend requested but git-xet is not installed.");
            System.err.println("  Install with: kompile install git-xet");
            System.err.println("  Writing .gitattributes with binary markers instead of xet filters.");
        }
        writeGitattributes(root, xetAvailable);
        if (xetRequested) {
            writeModelStorageReadme(root);
            if (xetAvailable && installGitXet) {
                KompileProjectGitResult result = runProcess(root, true, "git", "xet", "install");
                if (result.getExitCode() != 0) {
                    System.err.println("Warning: 'git xet install' failed: " + result.getOutput().trim());
                }
            }
        }
    }

    private void writeGitignore(Path root) {
        Path gitignore = root.resolve(".gitignore");
        if (Files.exists(gitignore)) {
            ensureGitignoreRule(gitignore, "config/channel-admin.token");
            return;
        }
        String content = """
                # Build output
                target/
                *.class

                # Runtime databases (not portable)
                data/*.db
                data/*.db.trace.db
                data/*.db.mv.db
                data/orchestrator-db*
                data/chat-history*

                # Runtime state
                data/logs/
                data/pids/

                # Staging server binaries
                staging/

                # Per-project config is versioned, EXCEPT files that hold secrets.
                # Keep API keys / tokens out of git; reference them via env placeholders.
                config/secrets/
                config/*.secret.json
                config/oauth-settings.json
                config/oauth-encryption.key
                config/channel-admin.token
                .env*
                !.env.example

                # Crash dumps
                hs_err_pid*.log
                hotspot_pid*.log
                *.hprof
                *.heapdump
                compute_sanitizer_*.log
                replay_pid*.log

                # Lucene write locks (created at runtime)
                **/write.lock

                # IDE
                .idea/
                *.iml
                .vscode/
                .settings/
                .classpath
                .project

                # OS
                .DS_Store
                Thumbs.db

                # Node
                node_modules/
                """;
        try {
            Files.writeString(gitignore, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write .gitignore: " + e.getMessage(), e);
        }
    }

    private static void ensureGitignoreRule(Path gitignore, String rule) {
        try {
            String existing = Files.readString(gitignore, StandardCharsets.UTF_8);
            boolean present = existing.lines().map(String::trim).anyMatch(rule::equals);
            if (present) {
                return;
            }
            String prefix = existing.isEmpty() || existing.endsWith("\n") ? "" : System.lineSeparator();
            Files.writeString(
                    gitignore,
                    prefix + "# Kompile runtime credentials\n" + rule + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update .gitignore: " + e.getMessage(), e);
        }
    }

    private void writeGitattributes(Path root, boolean xetEnabled) {
        Path gitattributes = root.resolve(".gitattributes");
        if (Files.exists(gitattributes)) return;
        StringBuilder sb = new StringBuilder();
        sb.append("# Kompile project git attributes\n\n");

        if (xetEnabled) {
            // Git Xet handles large files transparently — these patterns tell xet
            // which files to deduplicate/stream rather than store inline.
            sb.append("# Model files (tracked via git-xet)\n");
            sb.append("*.onnx filter=xet diff=xet merge=xet -text\n");
            sb.append("*.safetensors filter=xet diff=xet merge=xet -text\n");
            sb.append("*.bin filter=xet diff=xet merge=xet -text\n");
            sb.append("*.pt filter=xet diff=xet merge=xet -text\n");
            sb.append("*.pth filter=xet diff=xet merge=xet -text\n");
            sb.append("*.gguf filter=xet diff=xet merge=xet -text\n");
            sb.append("*.fb filter=xet diff=xet merge=xet -text\n");
            sb.append("*.zip filter=xet diff=xet merge=xet -text\n");
            sb.append("*.tar.gz filter=xet diff=xet merge=xet -text\n");
            sb.append("*.tgz filter=xet diff=xet merge=xet -text\n");
            sb.append("\n");
            sb.append("# Index files (tracked via git-xet)\n");
            sb.append("data/indices/** filter=xet diff=xet merge=xet -text\n");
            sb.append("\n");
            sb.append("# Knowledge-graph embedding sidecars (binary; tracked via git-xet)\n");
            sb.append("data/graph/embeddings/** filter=xet diff=xet merge=xet -text\n");
            sb.append("\n");
            sb.append("# Model store, built artifacts, and native-image distributions\n");
            sb.append("# (large binaries — including kompile-app-main native dists bundling\n");
            sb.append("# vendor backend dependencies — are streamed/deduplicated via git-xet)\n");
            sb.append("data/models/** filter=xet diff=xet merge=xet -text\n");
            sb.append("data/artifacts/** filter=xet diff=xet merge=xet -text\n");
            sb.append("data/distributions/** filter=xet diff=xet merge=xet -text\n");
        } else {
            // Without xet, mark large binary files so git doesn't try to diff them
            sb.append("# Model files (binary, no diff)\n");
            sb.append("*.onnx binary\n");
            sb.append("*.safetensors binary\n");
            sb.append("*.bin binary\n");
            sb.append("*.pt binary\n");
            sb.append("*.pth binary\n");
            sb.append("*.gguf binary\n");
            sb.append("*.fb binary\n");
            sb.append("*.tar.gz binary\n");
            sb.append("*.tgz binary\n");
            sb.append("\n");
            sb.append("# Index files (binary, no diff)\n");
            sb.append("data/indices/** binary\n");
            sb.append("\n");
            sb.append("# Knowledge-graph embedding sidecars (binary, no diff)\n");
            sb.append("data/graph/embeddings/** binary\n");
            sb.append("\n");
            sb.append("# Model store, built artifacts, and native-image distributions\n");
            sb.append("data/models/** binary\n");
            sb.append("data/artifacts/** binary\n");
            sb.append("data/distributions/** binary\n");
        }
        try {
            Files.writeString(gitattributes, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write .gitattributes: " + e.getMessage(), e);
        }
    }

    /**
     * The complete, authoritative set of project directories. This is the single
     * source of truth for the project layout: every directory that is registered as
     * a component in {@link #upsertStandardComponents} or read/written by a catalog
     * method below must appear here so that a freshly initialized project has a
     * consistent, complete on-disk structure regardless of which init entry point
     * created it (CLI {@code project init}, {@code init-project}, clone, or the API).
     *
     * <p>Paths are relative to the project root. Durable (versioned) directories and
     * the app-runtime working directories are both created here; the latter are kept
     * out of version control by {@link #writeGitignore}.</p>
     */
    static final List<String> STANDARD_DIRECTORIES = List.of(
            // ── Durable, versioned knowledge & metadata ──
            "data/markdown",
            "data/models",
            "data/models/.staging",
            "data/pipelines",
            "data/input_documents",
            "data/input_documents/uploads",
            "data/sources",
            "data/chats",
            "data/code-projects",
            "data/crawls",
            "data/workflows",
            "data/prompt-templates",
            "data/fact-sheets",
            "data/note-sync",
            "data/indexed-documents",
            "data/graph",
            "data/graph/embeddings",
            "data/artifacts",
            "data/distributions",
            "data/indices",
            "data/shared_files",
            "config",
            "scripts",
            // ── App-runtime working directories (gitignored, created for parity) ──
            "data/tool-definitions",
            "data/folders",
            "data/mcp-bridges",
            "data/mcp-servers",
            "data/logs",
            "data/pids"
    );

    private void ensureStandardDirectories(Path root) throws IOException {
        for (String dir : STANDARD_DIRECTORIES) {
            Files.createDirectories(root.resolve(dir));
        }
        ensureProjectMetadataDirectoryInternal(root);
        writeStandardLifecycleScriptFiles(root);
    }

    private void upsertStandardComponents(KompileProjectManifest manifest) {
        upsertComponent(manifest, component("metadata", KompileProjectComponentType.CONFIG, "Kompile metadata",
                METADATA_DIR, "Project-local Kompile metadata, open state, cache, and runtime state.",
                KompileProjectStorageBackend.LOCAL, List.of("metadata", "open", "local-state")));
        upsertComponent(manifest, component("markdown", KompileProjectComponentType.MARKDOWN, "Markdown notes",
                "data/markdown", "Markdown files synchronized from local or git-backed note stores.",
                KompileProjectStorageBackend.GIT, List.of("markdown", "notes", "git")));
        upsertComponent(manifest, component("models", KompileProjectComponentType.MODEL, "Models",
                "data/models", "Model artifacts and manifests. Use the GIT_XET backend for large model binaries.",
                KompileProjectStorageBackend.GIT_XET, List.of("models", "git-xet", "artifacts")));
        upsertComponent(manifest, component("model-registry", KompileProjectComponentType.MODEL, "Project model registry",
                "data/models/project-models.json", "Project-level model role, version, source, and staging registry references.",
                KompileProjectStorageBackend.GIT, List.of("models", "registry", "versioning", "staging")));
        upsertComponent(manifest, component("pipelines", KompileProjectComponentType.PIPELINE, "Pipelines",
                "data/pipelines", "Pipeline definitions and versioned project pipeline registry snapshots.",
                KompileProjectStorageBackend.GIT, List.of("pipelines", "versioning")));
        upsertComponent(manifest, component("pipeline-registry", KompileProjectComponentType.PIPELINE, "Project pipeline registry",
                "data/pipelines/project-pipelines.json", "Project-level pipeline versions and model bindings.",
                KompileProjectStorageBackend.GIT, List.of("pipelines", "registry", "versioning")));
        upsertComponent(manifest, component("sources", KompileProjectComponentType.SOURCE, "Sources",
                "data/input_documents", "Documents and source material used by ingestion and crawling.",
                KompileProjectStorageBackend.GIT, List.of("sources", "ingestion", "rag")));
        upsertComponent(manifest, component("chats", KompileProjectComponentType.CHAT, "Chats",
                "data/chats", "Project-scoped chat exports and conversation artifacts.",
                KompileProjectStorageBackend.GIT, List.of("chats", "sessions")));
        upsertComponent(manifest, component("code-projects", KompileProjectComponentType.CODE_PROJECT, "Coding projects",
                "data/code-projects", "Project-local context, AGENTS.md files, and chat links for external code repositories.",
                KompileProjectStorageBackend.GIT, List.of("code", "indexing", "context")));
        upsertComponent(manifest, component("scripts", KompileProjectComponentType.SCRIPT, "Lifecycle scripts",
                "scripts", "Managed project lifecycle scripts, runbooks, and generated command wrappers.",
                KompileProjectStorageBackend.GIT, List.of("scripts", "lifecycle", "init")));
        upsertComponent(manifest, component("crawls", KompileProjectComponentType.CRAWL, "Crawl profiles",
                "data/crawls", "Crawl profile definitions, crawl state exports, and ingestion initialization metadata.",
                KompileProjectStorageBackend.GIT, List.of("crawl", "ingestion", "sources")));
        upsertComponent(manifest, component("workflows", KompileProjectComponentType.WORKFLOW, "Workflows",
                "data/workflows", "Managed project lifecycle workflows, run metadata, and workflow outputs.",
                KompileProjectStorageBackend.GIT, List.of("workflow", "lifecycle", "automation")));
        upsertComponent(manifest, component("prompts", KompileProjectComponentType.PROMPT, "Prompt templates",
                "data/prompt-templates", "Prompt template JSON definitions used by the application.",
                KompileProjectStorageBackend.GIT, List.of("prompts", "templates")));
        upsertComponent(manifest, component("fact-sheets", KompileProjectComponentType.CONFIG, "Fact sheets",
                "data/fact-sheets", "Exported fact sheet definitions, index references, and document registrations.",
                KompileProjectStorageBackend.GIT, List.of("fact-sheets", "indices", "documents")));
        upsertComponent(manifest, component("note-sync", KompileProjectComponentType.CONFIG, "Note sync connections",
                "data/note-sync", "Note synchronization connection definitions for Notion, Obsidian, Git, and local folders.",
                KompileProjectStorageBackend.GIT, List.of("notes", "sync", "connections")));
        upsertComponent(manifest, component("indices", KompileProjectComponentType.CONFIG, "Search indices",
                "data/indices", "Lucene keyword and vector (HNSW) indices. Tracked via git-xet for large binary storage.",
                KompileProjectStorageBackend.GIT_XET, List.of("indices", "vector", "search", "lucene")));
        upsertComponent(manifest, component("indexed-documents", KompileProjectComponentType.DATASET, "Indexed documents",
                "data/indexed-documents", "Registry of documents that have been parsed, embedded, and indexed.",
                KompileProjectStorageBackend.GIT, List.of("documents", "indexing", "rag")));
        upsertComponent(manifest, component("graph", KompileProjectComponentType.DATASET, "Knowledge graphs",
                "data/graph", "Serialized knowledge graph nodes/edges (portable JSON, one file per fact sheet) plus "
                        + "embedding sidecars under data/graph/embeddings. Rehydrated into the runtime store on "
                        + "project open so the graph survives a git clone.",
                KompileProjectStorageBackend.GIT, List.of("graph", "knowledge-graph", "portability", "versioning")));
        upsertComponent(manifest, component("config", KompileProjectComponentType.CONFIG, "Project configuration",
                "config", "Per-project Kompile JSON configuration (index, pipeline, LLM, graph extraction). Secrets are gitignored.",
                KompileProjectStorageBackend.GIT, List.of("config", "settings", "versioning")));
        upsertComponent(manifest, component("artifacts", KompileProjectComponentType.ARTIFACT, "Build artifacts",
                "data/artifacts", "Built JARs and other large build outputs. Tracked via git-xet for large binary storage.",
                KompileProjectStorageBackend.GIT_XET, List.of("artifacts", "build", "git-xet")));
        upsertComponent(manifest, component("distributions", KompileProjectComponentType.DISTRIBUTION, "Native distributions",
                "data/distributions", "Native-image distributions of kompile-app-main (bundling vendor backend dependencies). "
                        + "Tracked via git-xet so large binaries are versioned without bloating git history.",
                KompileProjectStorageBackend.GIT_XET, List.of("distributions", "native-image", "release", "git-xet")));
    }

    private void ensureProjectMetadataDirectoryInternal(Path root) throws IOException {
        Path metadataRoot = root.resolve(METADATA_DIR);
        Files.createDirectories(root.resolve(PROJECT_METADATA_DIR));
        Files.createDirectories(metadataRoot.resolve("cache"));
        Files.createDirectories(metadataRoot.resolve("sessions"));
        Files.createDirectories(metadataRoot.resolve("state"));

        Path readme = metadataRoot.resolve("README.md");
        if (!Files.exists(readme)) {
            Files.writeString(readme, "# Kompile Metadata\n\n"
                    + "This directory stores Kompile project metadata that is adjacent to `kompile.project.json`.\n\n"
                    + "- `project/open.json` records the currently opened project on this machine.\n"
                    + "- `cache/`, `sessions/`, and `state/` are local runtime areas.\n"
                    + "- Durable project definitions live in `kompile.project.json` and the `data/` registries.\n",
                    StandardCharsets.UTF_8);
        }

        Path gitignore = metadataRoot.resolve(".gitignore");
        if (!Files.exists(gitignore)) {
            Files.writeString(gitignore, "project/open.json\n"
                    + "cache/\n"
                    + "sessions/\n"
                    + "state/\n"
                    + "*.lock\n"
                    + "*.pid\n",
                    StandardCharsets.UTF_8);
        }
    }

    private void writeStandardLifecycleScriptFiles(Path root) throws IOException {
        String startAll = """
                #!/usr/bin/env bash
                set -euo pipefail
                ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
                "$ROOT/scripts/start-staging.sh"
                "$ROOT/scripts/start-serving.sh"
                "$ROOT/scripts/start-app.sh"
                "$ROOT/scripts/start-chat.sh"
                "$ROOT/scripts/start-crawl-manager.sh"
                """;
        Path startAllPath = root.resolve("scripts/start-all.sh");
        writeExecutableIfMissing(startAllPath, startAll);
        upgradeExecutableIfContentMatches(startAllPath, """
                #!/usr/bin/env bash
                set -euo pipefail
                ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
                "$ROOT/scripts/start-staging.sh"
                "$ROOT/scripts/start-serving.sh"
                "$ROOT/scripts/start-app.sh"
                """, startAll);

        String stopAll = """
                #!/usr/bin/env bash
                set -euo pipefail
                ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
                PID_DIR="$ROOT/.kompile/state/pids"
                for service in chat crawl-manager app serving staging; do
                  pid_file="$PID_DIR/$service.pid"
                  if [ -f "$pid_file" ]; then
                    pid="$(cat "$pid_file")"
                    if kill -0 "$pid" >/dev/null 2>&1; then
                      kill "$pid"
                    fi
                    rm -f "$pid_file"
                  fi
                done
                """;
        Path stopAllPath = root.resolve("scripts/stop-all.sh");
        writeExecutableIfMissing(stopAllPath, stopAll);
        upgradeExecutableIfContentMatches(stopAllPath, """
                #!/usr/bin/env bash
                set -euo pipefail
                ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
                PID_DIR="$ROOT/.kompile/state/pids"
                for service in app serving staging; do
                  pid_file="$PID_DIR/$service.pid"
                  if [ -f "$pid_file" ]; then
                    pid="$(cat "$pid_file")"
                    if kill -0 "$pid" >/dev/null 2>&1; then
                      kill "$pid"
                    fi
                    rm -f "$pid_file"
                  fi
                done
                """, stopAll);

        writeServiceScript(root, "staging", "KOMPILE_STAGING_COMMAND",
                "Set KOMPILE_STAGING_COMMAND to start model staging for this project.",
                "kompile project serve --staging-only", "kompile manage start staging");
        writeServiceScript(root, "serving", "KOMPILE_SERVING_COMMAND",
                "Set KOMPILE_SERVING_COMMAND to start model serving for this project.",
                "kompile project serve --serving-only", "kompile manage start serving");
        writeServiceScript(root, "app", "KOMPILE_APP_COMMAND",
                "Set KOMPILE_APP_COMMAND to start the Kompile app for this project.",
                "kompile project serve --app-only", "kompile project service start");
        // The end-user personas are separate processes: the admin console does not mount
        // /api/agents/chat or /api/unified-crawl, so a project that starts only start-app.sh can
        // serve its admin API but cannot chat or ingest.
        writeServiceScript(root, "chat", "KOMPILE_CHAT_COMMAND",
                "Set KOMPILE_CHAT_COMMAND to start the Kompile chat app for this project.",
                "kompile project serve --chat-only", null);
        writeServiceScript(root, "crawl-manager", "KOMPILE_CRAWL_MANAGER_COMMAND",
                "Set KOMPILE_CRAWL_MANAGER_COMMAND to start the Kompile crawl manager for this project.",
                "kompile project serve --crawl-manager-only", null);
    }

    private void writeServiceScript(Path root, String service, String commandVariable,
                                    String missingMessage, String cliFallback,
                                    String legacyCliFallback) throws IOException {
        Path path = root.resolve("scripts/start-" + service + ".sh");
        String content = serviceScript(service, commandVariable, missingMessage, cliFallback);
        writeExecutableIfMissing(path, content);
        if (legacyCliFallback != null) {
            upgradeExecutableIfContentMatches(path,
                    serviceScript(service, commandVariable, missingMessage, legacyCliFallback), content);
        }
    }

    /**
     * Build a lifecycle start script. Prefers an explicit {@code commandVariable} env override; when
     * unset it falls back to {@code cliFallback} (a kompile CLI command run from the project root,
     * which discovers the installed component) so the script works out of the box instead of erroring
     * on a missing env var. Only if neither the override nor the {@code kompile} CLI is available does
     * it print {@code missingMessage} and exit.
     */
    private String serviceScript(String service, String commandVariable, String missingMessage, String cliFallback) {
        return """
                #!/usr/bin/env bash
                set -euo pipefail
                ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
                LOG_DIR="$ROOT/.kompile/state/logs"
                PID_DIR="$ROOT/.kompile/state/pids"
                mkdir -p "$LOG_DIR" "$PID_DIR"
                COMMAND="${%s:-}"
                if [ -z "$COMMAND" ]; then
                  # No explicit command set — fall back to the kompile CLI (discovers the installed component).
                  if command -v kompile >/dev/null 2>&1; then
                    cd "$ROOT"
                    COMMAND="%s"
                  else
                    echo "%s" >&2
                    echo "  (or install the kompile CLI so this script can auto-discover and start it)" >&2
                    exit 2
                  fi
                fi
                nohup bash -lc "$COMMAND" > "$LOG_DIR/%s.log" 2>&1 &
                echo $! > "$PID_DIR/%s.pid"
                echo "Started %s with PID $(cat "$PID_DIR/%s.pid")"
                """.formatted(commandVariable, cliFallback, missingMessage, service, service, service, service);
    }

    private void writeExecutableIfMissing(Path script, String content) throws IOException {
        if (Files.exists(script)) {
            return;
        }
        Files.createDirectories(script.getParent());
        Files.writeString(script, content, StandardCharsets.UTF_8);
        script.toFile().setExecutable(true, false);
    }

    private void upgradeExecutableIfContentMatches(Path script, String legacyContent,
                                                   String currentContent) throws IOException {
        if (!Files.isRegularFile(script)
                || !Files.readString(script, StandardCharsets.UTF_8).equals(legacyContent)) {
            return;
        }
        Files.writeString(script, currentContent, StandardCharsets.UTF_8);
        script.toFile().setExecutable(true, false);
    }

    private void upsertStandardScripts(KompileProjectManifest manifest) {
        upsertScript(manifest, script("start-all", "Start all services", "scripts/start-all.sh",
                "./scripts/start-all.sh", ".", "start",
                "Start staging, serving, the admin console, chat, and the crawl manager.",
                List.of("lifecycle", "start", "services")));
        upsertScript(manifest, script("stop-all", "Stop all services", "scripts/stop-all.sh",
                "./scripts/stop-all.sh", ".", "stop", "Stop project services and clean service PIDs.",
                List.of("lifecycle", "stop", "services")));
        upsertScript(manifest, script("start-app", "Start app", "scripts/start-app.sh",
                "./scripts/start-app.sh", ".", "start", "Start the Kompile admin console.",
                List.of("lifecycle", "app", "admin")));
        upsertScript(manifest, script("start-chat", "Start chat", "scripts/start-chat.sh",
                "./scripts/start-chat.sh", ".", "start", "Start the Kompile chat app.",
                List.of("lifecycle", "chat")));
        upsertScript(manifest, script("start-crawl-manager", "Start crawl manager",
                "scripts/start-crawl-manager.sh", "./scripts/start-crawl-manager.sh", ".", "start",
                "Start the Kompile crawl manager (crawl, ingest, indexing).",
                List.of("lifecycle", "crawl", "ingest")));
        upsertScript(manifest, script("start-staging", "Start staging", "scripts/start-staging.sh",
                "./scripts/start-staging.sh", ".", "start", "Start the model staging service.",
                List.of("lifecycle", "staging", "models")));
        upsertScript(manifest, script("start-serving", "Start serving", "scripts/start-serving.sh",
                "./scripts/start-serving.sh", ".", "start", "Start the model serving subprocess.",
                List.of("lifecycle", "serving", "models")));
    }

    private KompileProjectScript script(String id, String name, String path, String command,
                                        String workingDirectory, String phase, String description,
                                        List<String> tags) {
        KompileProjectScript script = new KompileProjectScript();
        script.setId(id);
        script.setName(name);
        script.setPath(path);
        script.setCommand(command);
        script.setWorkingDirectory(workingDirectory);
        script.setPhase(phase);
        script.setDescription(description);
        script.setGenerated(true);
        script.setPlatform("unix");
        script.setTags(tags);
        return script;
    }

    private KompileProjectWorkflow standardStartWorkflow() {
        KompileProjectWorkflow start = workflow("start-services", "Start services", "start",
                "Start staging, serving, the admin console, chat, and the crawl manager.",
                List.of("workflow", "lifecycle", "start"));
        // Chat and the crawl manager come after the admin console because they are separate
        // processes on their own ports — the crawl manager is what serves /api/unified-crawl, so
        // the auto-ingest workflow below has nothing to talk to without it.
        start.setSteps(List.of(
                workflowStep("start-staging", "Start staging", "SCRIPT", "start-staging"),
                workflowStep("start-serving", "Start serving", "SCRIPT", "start-serving"),
                workflowStep("start-app", "Start app", "SCRIPT", "start-app"),
                workflowStep("start-chat", "Start chat", "SCRIPT", "start-chat"),
                workflowStep("start-crawl-manager", "Start crawl manager", "SCRIPT", "start-crawl-manager")
        ));
        return start;
    }

    private void upsertStandardWorkflows(KompileProjectManifest manifest) {
        upsertWorkflow(manifest, standardStartWorkflow());

        KompileProjectWorkflow stop = workflow("stop-services", "Stop services", "stop",
                "Stop all project services.", List.of("workflow", "lifecycle", "stop"));
        stop.setSteps(List.of(workflowStep("stop-all", "Stop all", "SCRIPT", "stop-all")));
        upsertWorkflow(manifest, stop);

        KompileProjectWorkflow autoIngest = workflow("auto-ingest", "Auto ingest", "crawl",
                "Wait for services, crawl all profiles, and optionally commit results.",
                List.of("workflow", "automation", "crawl", "ingest"));
        KompileProjectWorkflowStep healthStep = workflowStep(
                "wait-for-crawl-manager", "Wait for crawl manager", "HEALTH_CHECK", null);
        // Wait for the process that serves the NEXT step, not for "any kompile service".
        // /api/unified-crawl lives on the crawl manager, so ${appUrl} resolves to :8082 here;
        // the untargeted probe would pass as soon as the admin console answered on :8080 and the
        // crawl would then POST into a connection refused. A pinned --url/--port still wins, so
        // an all-in-one deployment behaves as before.
        //
        // Do NOT use "${appUrl}/actuator/health": generated kompile apps do not ship Spring Boot
        // Actuator, so it 404s. /api/unified-crawl/jobs/active is the crawl manager's own
        // always-present read endpoint — the same one the CLI polls while a crawl runs.
        healthStep.setUrl("${appUrl}/api/unified-crawl/jobs/active");
        healthStep.setExpectedStatus(200);
        healthStep.setTimeoutSeconds(120);
        String crawlRef = manifest.getCrawlProfiles().stream()
                .map(KompileProjectCrawlProfile::getId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse("auto-ingest");
        KompileProjectWorkflowStep crawlStep = workflowStep(
                "run-crawl", "Run crawl", "CRAWL", crawlRef);
        autoIngest.setSteps(List.of(healthStep, crawlStep));
        upsertWorkflow(manifest, autoIngest);
    }

    private KompileProjectWorkflow workflow(String id, String name, String phase,
                                            String description, List<String> tags) {
        KompileProjectWorkflow workflow = new KompileProjectWorkflow();
        workflow.setId(id);
        workflow.setName(name);
        workflow.setPhase(phase);
        workflow.setDescription(description);
        workflow.setGenerated(true);
        workflow.setTags(tags);
        return workflow;
    }

    private KompileProjectWorkflowStep workflowStep(String id, String name, String type, String ref) {
        KompileProjectWorkflowStep step = new KompileProjectWorkflowStep();
        step.setId(id);
        step.setName(name);
        step.setType(type);
        step.setRef(ref);
        return step;
    }

    private KompileProjectComponent component(String id, KompileProjectComponentType type, String name,
                                              String path, String description,
                                              KompileProjectStorageBackend backend, List<String> tags) {
        KompileProjectComponent component = new KompileProjectComponent();
        component.setId(id);
        component.setType(type);
        component.setName(name);
        component.setPath(path);
        component.setDescription(description);
        component.setStorageBackend(backend);
        component.setTags(tags);
        return component;
    }

    private void upsertComponent(KompileProjectManifest manifest, KompileProjectComponent component) {
        if (component == null) {
            return;
        }
        Instant now = Instant.now();
        if (trimToNull(component.getId()) == null) {
            component.setId(slug(firstNonBlank(component.getName(), component.getPath(), component.getType().name())));
        }
        if (trimToNull(component.getName()) == null) {
            component.setName(component.getId());
        }
        if (component.getCreatedAt() == null) {
            component.setCreatedAt(now);
        }
        component.setUpdatedAt(now);
        component.setTags(normalizeTags(component.getTags()));

        Optional<KompileProjectComponent> existing = findComponent(manifest, component.getId());
        if (existing.isPresent()) {
            int index = manifest.getComponents().indexOf(existing.get());
            manifest.getComponents().set(index, component);
        } else {
            manifest.getComponents().add(component);
        }
        manifest.getComponents().sort(Comparator.comparing(KompileProjectComponent::getId));
    }

    private void upsertScript(KompileProjectManifest manifest, KompileProjectScript script) {
        if (script == null) {
            return;
        }
        String id = slug(firstNonBlank(script.getId(), script.getName(), script.getPath(), script.getCommand()));
        script.setId(id);
        script.setName(firstNonBlank(script.getName(), id));
        script.setPath(trimToNull(script.getPath()));
        script.setCommand(firstNonBlank(script.getCommand(), script.getPath()));
        script.setWorkingDirectory(firstNonBlank(script.getWorkingDirectory(), "."));
        script.setPhase(firstNonBlank(script.getPhase(), "run"));
        script.setPlatform(firstNonBlank(script.getPlatform(), "any"));
        script.setTags(normalizeTags(script.getTags()));

        Instant now = Instant.now();
        if (script.getCreatedAt() == null) {
            script.setCreatedAt(now);
        }
        script.setUpdatedAt(now);

        Optional<KompileProjectScript> existing = findScript(manifest, id);
        if (existing.isPresent()) {
            int index = manifest.getScripts().indexOf(existing.get());
            manifest.getScripts().set(index, script);
        } else {
            manifest.getScripts().add(script);
        }
        manifest.getScripts().sort(Comparator.comparing(KompileProjectScript::getId));
    }

    private void upsertCrawlProfile(KompileProjectManifest manifest, KompileProjectCrawlProfile crawlProfile) {
        if (crawlProfile == null) {
            return;
        }
        if (crawlProfile.getSources() == null || crawlProfile.getSources().isEmpty()) {
            throw new IllegalArgumentException("Crawl profile sources are required");
        }
        String id = slug(firstNonBlank(crawlProfile.getId(), crawlProfile.getName(), crawlProfile.getSources().get(0)));
        crawlProfile.setId(id);
        crawlProfile.setName(firstNonBlank(crawlProfile.getName(), id));
        crawlProfile.setSources(normalizeTags(crawlProfile.getSources()));
        crawlProfile.setIncludePatterns(normalizeTags(crawlProfile.getIncludePatterns()));
        crawlProfile.setExcludePatterns(normalizeTags(crawlProfile.getExcludePatterns()));
        crawlProfile.setContentTypes(normalizeTags(crawlProfile.getContentTypes()));
        crawlProfile.setGraphEntityTypes(normalizeTags(crawlProfile.getGraphEntityTypes()));
        crawlProfile.setGraphRelationTypes(normalizeTags(crawlProfile.getGraphRelationTypes()));
        crawlProfile.setTags(normalizeTags(crawlProfile.getTags()));
        if (trimToNull(crawlProfile.getSchemaPresetId()) != null) {
            crawlProfile.setGraphExtraction(true);
        }
        if (crawlProfile.isGraphAutoStart()) {
            crawlProfile.setGraphLocal(true);
        }

        Instant now = Instant.now();
        if (crawlProfile.getCreatedAt() == null) {
            crawlProfile.setCreatedAt(now);
        }
        crawlProfile.setUpdatedAt(now);

        Optional<KompileProjectCrawlProfile> existing = findCrawlProfile(manifest, id);
        if (existing.isPresent()) {
            int index = manifest.getCrawlProfiles().indexOf(existing.get());
            manifest.getCrawlProfiles().set(index, crawlProfile);
        } else {
            manifest.getCrawlProfiles().add(crawlProfile);
        }
        manifest.getCrawlProfiles().sort(Comparator.comparing(KompileProjectCrawlProfile::getId));
    }

    private void upsertModel(KompileProjectManifest manifest, KompileProjectModel model) {
        if (model == null) {
            return;
        }
        String id = slug(firstNonBlank(model.getId(), model.getModelId(), model.getRole(), model.getPath(), "model"));
        model.setId(id);
        model.setModelId(firstNonBlank(model.getModelId(), id));
        model.setRole(normalizeEnumLike(firstNonBlank(model.getRole(), "MODEL")));
        model.setRegistryModelId(firstNonBlank(model.getRegistryModelId(), model.getModelId()));
        model.setStagingRegistryPath(firstNonBlank(model.getStagingRegistryPath(), "data/models/registry.json"));
        model.setTags(normalizeTags(model.getTags()));

        Instant now = Instant.now();
        if (model.getCreatedAt() == null) {
            model.setCreatedAt(now);
        }
        model.setUpdatedAt(now);

        Optional<KompileProjectModel> existing = findModel(manifest, id);
        if (existing.isPresent()) {
            int index = manifest.getModels().indexOf(existing.get());
            manifest.getModels().set(index, model);
        } else {
            manifest.getModels().add(model);
        }
        manifest.getModels().sort(Comparator.comparing(KompileProjectModel::getId));
    }

    private void upsertPipeline(KompileProjectManifest manifest, KompileProjectPipeline pipeline) {
        if (pipeline == null) {
            return;
        }
        String id = slug(firstNonBlank(pipeline.getId(), pipeline.getPipelineId(), pipeline.getName(), pipeline.getRole(), "pipeline"));
        pipeline.setId(id);
        pipeline.setPipelineId(firstNonBlank(pipeline.getPipelineId(), id));
        pipeline.setName(firstNonBlank(pipeline.getName(), pipeline.getPipelineId()));
        pipeline.setRole(normalizeEnumLike(firstNonBlank(pipeline.getRole(), "PIPELINE")));
        pipeline.setRegistryPath(firstNonBlank(pipeline.getRegistryPath(), "data/pipelines/project-pipelines.json"));
        pipeline.setDefinitionPath(trimToNull(pipeline.getDefinitionPath()));
        pipeline.setModelRefs(normalizeTags(pipeline.getModelRefs()));
        pipeline.setTags(normalizeTags(pipeline.getTags()));

        Instant now = Instant.now();
        if (pipeline.getCreatedAt() == null) {
            pipeline.setCreatedAt(now);
        }
        pipeline.setUpdatedAt(now);

        Optional<KompileProjectPipeline> existing = findPipeline(manifest, id);
        if (existing.isPresent()) {
            int index = manifest.getPipelines().indexOf(existing.get());
            manifest.getPipelines().set(index, pipeline);
        } else {
            manifest.getPipelines().add(pipeline);
        }
        manifest.getPipelines().sort(Comparator.comparing(KompileProjectPipeline::getId));
    }

    private void upsertWorkflow(KompileProjectManifest manifest, KompileProjectWorkflow workflow) {
        if (workflow == null) {
            return;
        }
        if (workflow.getSteps() == null || workflow.getSteps().isEmpty()) {
            throw new IllegalArgumentException("Workflow steps are required");
        }
        String id = slug(firstNonBlank(workflow.getId(), workflow.getName(), workflow.getPhase()));
        workflow.setId(id);
        workflow.setName(firstNonBlank(workflow.getName(), id));
        workflow.setPhase(firstNonBlank(workflow.getPhase(), "run"));
        workflow.setTags(normalizeTags(workflow.getTags()));
        normalizeWorkflowSteps(workflow);

        Instant now = Instant.now();
        if (workflow.getCreatedAt() == null) {
            workflow.setCreatedAt(now);
        }
        workflow.setUpdatedAt(now);

        Optional<KompileProjectWorkflow> existing = findWorkflow(manifest, id);
        if (existing.isPresent()) {
            int index = manifest.getWorkflows().indexOf(existing.get());
            manifest.getWorkflows().set(index, workflow);
        } else {
            manifest.getWorkflows().add(workflow);
        }
        manifest.getWorkflows().sort(Comparator.comparing(KompileProjectWorkflow::getId));
    }

    private void normalizeWorkflowSteps(KompileProjectWorkflow workflow) {
        int index = 1;
        for (KompileProjectWorkflowStep step : workflow.getSteps()) {
            String fallback = workflow.getId() + "-step-" + index;
            step.setId(slug(firstNonBlank(step.getId(), step.getName(), step.getRef(), step.getCommand(), fallback)));
            step.setName(firstNonBlank(step.getName(), step.getId()));
            step.setType(normalizeEnumLike(firstNonBlank(step.getType(), "COMMAND")));
            step.setWorkingDirectory(firstNonBlank(step.getWorkingDirectory(), "."));
            step.setMethod(firstNonBlank(step.getMethod(), "GET").toUpperCase(Locale.ROOT));
            index++;
        }
    }

    private void upsertCodingProject(Path root, KompileProjectManifest manifest, KompileCodingProject codingProject) {
        if (codingProject == null) {
            return;
        }
        if (trimToNull(codingProject.getRootPath()) == null) {
            throw new IllegalArgumentException("Coding project rootPath is required");
        }

        Path sourceRoot = Path.of(codingProject.getRootPath()).toAbsolutePath().normalize();
        String id = firstNonBlank(codingProject.getId(), codingProject.getCodeProjectId(), codingProject.getName(),
                sourceRoot.getFileName() != null ? sourceRoot.getFileName().toString() : null);
        id = slug(id);
        codingProject.setId(id);
        codingProject.setCodeProjectId(firstNonBlank(codingProject.getCodeProjectId(), id));
        codingProject.setName(firstNonBlank(codingProject.getName(), id));
        codingProject.setRootPath(sourceRoot.toString());
        codingProject.setContextPath(firstNonBlank(codingProject.getContextPath(), "data/code-projects/" + id));
        codingProject.setAgentsMdPath(firstNonBlank(codingProject.getAgentsMdPath(), codingProject.getContextPath() + "/AGENTS.md"));
        codingProject.setChatsPath(firstNonBlank(codingProject.getChatsPath(), codingProject.getContextPath() + "/chats"));
        codingProject.setMetadataPath(firstNonBlank(codingProject.getMetadataPath(), codingProject.getContextPath() + "/metadata"));
        codingProject.setIndexPath(firstNonBlank(codingProject.getIndexPath(), codingProject.getContextPath() + "/indexes"));
        codingProject.setTags(normalizeTags(codingProject.getTags()));

        Instant now = Instant.now();
        if (codingProject.getCreatedAt() == null) {
            codingProject.setCreatedAt(now);
        }
        codingProject.setUpdatedAt(now);

        ensureCodingProjectContext(root, codingProject);

        Optional<KompileCodingProject> existing = findCodingProject(manifest, id);
        if (existing.isPresent()) {
            int index = manifest.getCodingProjects().indexOf(existing.get());
            manifest.getCodingProjects().set(index, codingProject);
        } else {
            manifest.getCodingProjects().add(codingProject);
        }
        manifest.getCodingProjects().sort(Comparator.comparing(KompileCodingProject::getId));
    }

    private Optional<KompileCodingProject> findCodingProject(KompileProjectManifest manifest, String codingProjectId) {
        if (trimToNull(codingProjectId) == null) {
            return Optional.empty();
        }
        return manifest.getCodingProjects().stream()
                .filter(project -> codingProjectId.equals(project.getId())
                        || codingProjectId.equals(project.getCodeProjectId()))
                .findFirst();
    }

    public Optional<KompileProjectModel> findModel(KompileProjectManifest manifest, String modelId) {
        if (trimToNull(modelId) == null) {
            return Optional.empty();
        }
        return manifest.getModels().stream()
                .filter(model -> modelId.equals(model.getId())
                        || modelId.equals(model.getModelId())
                        || modelId.equals(model.getRegistryModelId()))
                .findFirst();
    }

    public Optional<KompileProjectPipeline> findPipeline(KompileProjectManifest manifest, String pipelineId) {
        if (trimToNull(pipelineId) == null) {
            return Optional.empty();
        }
        return manifest.getPipelines().stream()
                .filter(pipeline -> pipelineId.equals(pipeline.getId())
                        || pipelineId.equals(pipeline.getPipelineId())
                        || pipelineId.equals(pipeline.getName()))
                .findFirst();
    }

    public Optional<KompileProjectCrawlProfile> findCrawlProfile(KompileProjectManifest manifest, String crawlProfileId) {
        if (trimToNull(crawlProfileId) == null) {
            return Optional.empty();
        }
        return manifest.getCrawlProfiles().stream()
                .filter(profile -> crawlProfileId.equals(profile.getId()) || crawlProfileId.equals(profile.getName()))
                .findFirst();
    }

    public Optional<KompileProjectWorkflow> findWorkflow(KompileProjectManifest manifest, String workflowId) {
        if (trimToNull(workflowId) == null) {
            return Optional.empty();
        }
        return manifest.getWorkflows().stream()
                .filter(workflow -> workflowId.equals(workflow.getId()) || workflowId.equals(workflow.getName()))
                .findFirst();
    }

    public Optional<KompileProjectScript> findScript(KompileProjectManifest manifest, String scriptId) {
        if (trimToNull(scriptId) == null) {
            return Optional.empty();
        }
        return manifest.getScripts().stream()
                .filter(script -> scriptId.equals(script.getId()) || scriptId.equals(script.getName()))
                .findFirst();
    }

    private Optional<KompileProjectComponent> findComponent(KompileProjectManifest manifest, String componentId) {
        if (trimToNull(componentId) == null) {
            return Optional.empty();
        }
        return manifest.getComponents().stream()
                .filter(component -> componentId.equals(component.getId()))
                .findFirst();
    }

    private void createComponentDirectory(Path root, KompileProjectComponent component) {
        if (trimToNull(component.getPath()) == null) {
            return;
        }
        Path path = root.resolve(component.getPath()).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Component path escapes project root: " + component.getPath());
        }
        if (component.getPath().endsWith("/") || !component.getPath().contains(".")) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create component path " + path + ": " + e.getMessage(), e);
            }
        }
    }

    private void ensureCodingProjectContext(Path root, KompileCodingProject codingProject) {
        Path contextPath = resolveProjectRelativePath(root, codingProject.getContextPath(), "Coding project context path");
        Path chatsPath = resolveProjectRelativePath(root, codingProject.getChatsPath(), "Coding project chats path");
        Path agentsMdPath = resolveProjectRelativePath(root, codingProject.getAgentsMdPath(), "Coding project AGENTS.md path");
        Path metadataPath = resolveProjectRelativePath(root, codingProject.getMetadataPath(), "Coding project metadata path");
        Path indexPath = resolveProjectRelativePath(root, codingProject.getIndexPath(), "Coding project index path");
        try {
            Files.createDirectories(contextPath);
            Files.createDirectories(chatsPath);
            Files.createDirectories(metadataPath);
            Files.createDirectories(indexPath);
            Files.createDirectories(contextPath.resolve("notes"));
            Files.createDirectories(contextPath.resolve("prompts"));
            if (!Files.exists(agentsMdPath)) {
                Files.createDirectories(agentsMdPath.getParent());
                Files.writeString(agentsMdPath, defaultCodingProjectAgentsMd(codingProject), StandardCharsets.UTF_8);
            }
            writeCodingProjectMetadata(metadataPath, codingProject);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize coding project context for "
                    + codingProject.getId() + ": " + e.getMessage(), e);
        }
    }

    private Path resolveProjectRelativePath(Path root, String relativePath, String label) {
        if (trimToNull(relativePath) == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException(label + " escapes project root: " + relativePath);
        }
        return path;
    }

    private String defaultCodingProjectAgentsMd(KompileCodingProject codingProject) {
        return "# " + codingProject.getName() + "\n\n"
                + "This context file belongs to the Kompile coding project `" + codingProject.getCodeProjectId() + "`.\n\n"
                + "- External source root: `" + codingProject.getRootPath() + "`\n"
                + "- Code index project ID: `" + codingProject.getCodeProjectId() + "`\n"
                + "- Project metadata: `" + codingProject.getMetadataPath() + "`\n"
                + "- Project index snapshots: `" + codingProject.getIndexPath() + "`\n"
                + "- Project-local chats: `" + codingProject.getChatsPath() + "`\n\n"
                + "Run `kompile project code-index --id " + codingProject.getId() + "` to refresh the project-owned file index.\n\n"
                + "Keep source files in the external repository. Store only coding context, notes, prompts, and chat artifacts in this folder.\n";
    }

    private void writeCodingProjectMetadata(Path metadataPath, KompileCodingProject codingProject) throws IOException {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("id", codingProject.getId());
        project.put("codeProjectId", codingProject.getCodeProjectId());
        project.put("name", codingProject.getName());
        project.put("rootPath", codingProject.getRootPath());
        project.put("contextPath", codingProject.getContextPath());
        project.put("agentsMdPath", codingProject.getAgentsMdPath());
        project.put("chatsPath", codingProject.getChatsPath());
        project.put("metadataPath", codingProject.getMetadataPath());
        project.put("indexPath", codingProject.getIndexPath());
        project.put("includePatterns", splitCsv(codingProject.getIncludePatterns()));
        project.put("excludePatterns", splitCsv(codingProject.getExcludePatterns()));
        project.put("autoIndex", codingProject.isAutoIndex());
        project.put("tags", codingProject.getTags());
        project.put("updatedAt", codingProject.getUpdatedAt());
        mapper.writeValue(metadataPath.resolve("project.json").toFile(), project);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("codeProjectId", codingProject.getCodeProjectId());
        plan.put("sourceRoot", codingProject.getRootPath());
        plan.put("actualIndexer", "LocalCodeIndexer");
        plan.put("actualIndexPath", Path.of(System.getProperty("user.home"), ".kompile", "code-index",
                codingProject.getCodeProjectId()).toString());
        plan.put("includePatterns", splitCsv(codingProject.getIncludePatterns()));
        plan.put("requestedExcludePatterns", splitCsv(codingProject.getExcludePatterns()));
        plan.put("defaultExcludePatterns", defaultCodeIndexExcludes());
        plan.put("effectiveExcludePatterns", effectiveCodeIndexExcludes(codingProject.getExcludePatterns()));
        plan.put("indexPath", codingProject.getIndexPath());
        plan.put("latestIndexPath", codingProject.getIndexPath() + "/latest");
        plan.put("autoIndex", codingProject.isAutoIndex());
        mapper.writeValue(metadataPath.resolve("index-plan.json").toFile(), plan);
    }

    private void writeModelStorageReadme(Path root) {
        Path readme = root.resolve("data/models/README.md");
        if (Files.exists(readme)) {
            return;
        }
        String content = "# Models\n\n"
                + "This project component is intended for model files, descriptors, tokenizer assets, and staged artifacts.\n\n"
                + "When the repository backend is `GIT_XET`, Kompile uses normal Git clone/add/commit/push flows and expects Git Xet to be installed on machines that push or pull large model files.\n\n"
                + "- `registry.json` is the model-staging registry consumed by kompile-model-staging.\n"
                + "- `project-models.json` is the project manifest snapshot of desired model roles, versions, and sources.\n";
        try {
            Files.createDirectories(readme.getParent());
            Files.writeString(readme, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write model storage README: " + e.getMessage(), e);
        }
    }

    private void syncProjectRegistries(Path root, KompileProjectManifest manifest) {
        try {
            Path modelsRegistry = root.resolve("data/models/project-models.json");
            Path stagingRegistry = root.resolve("data/models/registry.json");
            Path pipelinesRegistry = root.resolve("data/pipelines/project-pipelines.json");
            Files.createDirectories(modelsRegistry.getParent());
            Files.createDirectories(pipelinesRegistry.getParent());
            Map<String, Object> modelSnapshot = new LinkedHashMap<>();
            modelSnapshot.put("schemaVersion", manifest.getSchemaVersion());
            modelSnapshot.put("projectId", manifest.getProjectId());
            modelSnapshot.put("updatedAt", manifest.getUpdatedAt());
            modelSnapshot.put("stagingRegistryPath", "data/models/registry.json");
            modelSnapshot.put("models", manifest.getModels());
            mapper.writeValue(modelsRegistry.toFile(), modelSnapshot);
            writeStagingRegistryAtomically(
                    stagingRegistry, stagingRegistrySnapshot(root, manifest));

            Map<String, Object> pipelineSnapshot = new LinkedHashMap<>();
            pipelineSnapshot.put("schemaVersion", manifest.getSchemaVersion());
            pipelineSnapshot.put("projectId", manifest.getProjectId());
            pipelineSnapshot.put("updatedAt", manifest.getUpdatedAt());
            pipelineSnapshot.put("pipelines", manifest.getPipelines());
            mapper.writeValue(pipelinesRegistry.toFile(), pipelineSnapshot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to sync project model/pipeline registries: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void writeStagingRegistryAtomically(
            Path registryPath, Map<String, Object> snapshot) throws IOException {
        Path lockPath = registryPath.getParent().resolve(".registry.lock");
        try (FileChannel channel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Map<String, Object> merged = new LinkedHashMap<>(snapshot);
            Map<String, Object> projectedModels = new LinkedHashMap<>(
                    (Map<String, Object>) snapshot.getOrDefault("models", Map.of()));
            if (Files.isRegularFile(registryPath)) {
                Map<String, Object> current = mapper.readValue(
                        registryPath.toFile(), new TypeReference<Map<String, Object>>() { });
                Object currentModels = current.get("models");
                if (currentModels instanceof Map<?, ?> existing) {
                    Map<String, Object> preserved = new LinkedHashMap<>();
                    existing.forEach((key, value) -> preserved.put(String.valueOf(key), value));
                    preserved.putAll(projectedModels);
                    projectedModels = preserved;
                }
            }
            merged.put("models", projectedModels);
            Path temp = Files.createTempFile(registryPath.getParent(), "registry-project-", ".tmp");
            try {
                mapper.writeValue(temp.toFile(), merged);
                try {
                    Files.move(temp, registryPath, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temp, registryPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private Map<String, Object> stagingRegistrySnapshot(Path root, KompileProjectManifest manifest) {
        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("version", "1.0");
        registry.put("updated_at", registryTimestamp(manifest));
        Map<String, Object> models = new LinkedHashMap<>();
        for (KompileProjectModel model : manifest.getModels()) {
            String modelId = firstNonBlank(model.getRegistryModelId(), model.getModelId(), model.getId());
            if (modelId != null) {
                models.put(modelId, stagingRegistryEntry(root, manifest, model, modelId));
            }
        }
        registry.put("models", models);
        registry.put("installed_archives", new LinkedHashMap<>());
        return registry;
    }

    private Map<String, Object> stagingRegistryEntry(Path root,
                                                    KompileProjectManifest manifest,
                                                    KompileProjectModel model,
                                                    String modelId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        String modelFile = stagingModelFile(model);
        entry.put("model_id", modelId);
        entry.put("type", stagingModelType(model));
        entry.put("path", stagingModelPath(model, modelId, modelFile));
        entry.put("model_file", modelFile);
        entry.put("vocab_file", firstNonBlank(metadataValue(model, "registry.vocabFile"),
                metadataValue(model, "vocab.file"), "vocab.txt"));
        putIfNotBlank(entry, "checksum", metadataValue(model, "registry.checksum"));
        entry.put("status", stagingRegistryStatus(root, model, modelId, modelFile));
        entry.put("promoted_at", model.getUpdatedAt() == null
                ? registryTimestamp(manifest)
                : model.getUpdatedAt().toString());
        entry.put("metadata", stagingModelMetadata(model));
        Map<String, Object> tokenizer = stagingTokenizerConfig(model);
        if (!tokenizer.isEmpty()) {
            entry.put("tokenizer", tokenizer);
        }
        return entry;
    }

    private Map<String, Object> stagingTokenizerConfig(KompileProjectModel model) {
        Map<String, Object> tokenizer = new LinkedHashMap<>();
        putBooleanMetadata(tokenizer, "do_lower_case", model, "registry.tokenizerDoLowerCase");
        putBooleanMetadata(tokenizer, "strip_accents", model, "registry.tokenizerStripAccents");
        putBooleanMetadata(tokenizer, "add_special_tokens", model, "registry.tokenizerAddSpecialTokens");
        putBooleanMetadata(tokenizer, "truncation", model, "registry.tokenizerTruncation");
        putIfNotBlank(tokenizer, "padding", metadataValue(model, "registry.tokenizerPadding"));
        String maxLength = metadataValue(model, "registry.tokenizerMaxLength");
        if (maxLength != null) {
            try {
                tokenizer.put("max_length", Integer.parseInt(maxLength));
            } catch (NumberFormatException ignored) {
                // Omit malformed optional metadata instead of publishing a bad runtime contract.
            }
        }
        return tokenizer;
    }

    private void putBooleanMetadata(
            Map<String, Object> target, String outputKey,
            KompileProjectModel model, String metadataKey) {
        String value = metadataValue(model, metadataKey);
        if (value != null && ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
            target.put(outputKey, Boolean.parseBoolean(value));
        }
    }

    private String stagingRegistryStatus(Path root, KompileProjectModel model,
                                         String modelId, String modelFile) {
        boolean materialized = hasMaterializedModelArtifact(root, model, modelId, modelFile);
        return stagingRegistryStatus(model.getLifecycle(), materialized);
    }

    /**
     * Maps project lifecycle and local artifact state onto the staging registry's supported status
     * vocabulary without advertising paused/draft models as usable.
     */
    public static String stagingRegistryStatus(KompileProjectLifecycleState lifecycle,
                                               boolean materialized) {
        KompileProjectLifecycleState effectiveLifecycle = lifecycle == null
                ? KompileProjectLifecycleState.ACTIVE
                : lifecycle;
        return switch (effectiveLifecycle) {
            case ACTIVE -> materialized ? "active" : "staged";
            case DRAFT, PAUSED -> "staged";
            case ARCHIVED, DEPRECATED -> "deprecated";
        };
    }

    private boolean hasMaterializedModelArtifact(Path root, KompileProjectModel model,
                                                 String modelId, String modelFile) {
        String artifactStage = metadataValue(model, "artifact.stage");
        String runtimeReady = metadataValue(model, "runtime.ready");
        if ("SOURCE".equalsIgnoreCase(artifactStage)
                || "false".equalsIgnoreCase(runtimeReady)) {
            return false;
        }
        Path modelsRoot = root.resolve("data/models").toAbsolutePath().normalize();
        Path modelDir = modelsRoot.resolve(stagingModelPath(model, modelId, modelFile)).normalize();
        if (!modelDir.startsWith(modelsRoot)) {
            return false;
        }
        return hasCompleteModelArtifact(modelDir, modelFile);
    }

    /**
     * Returns whether the configured model file, or its complete zero-based SDNB shard set, has
     * materialized beneath the supplied model directory. Shards must share the configured file's
     * basename; unrelated or stale shard groups never activate a registry entry.
     */
    public static boolean hasCompleteModelArtifact(Path modelDirectory, String modelFile) {
        if (modelDirectory == null || modelFile == null || modelFile.isBlank()) {
            return false;
        }
        Path modelDir = modelDirectory.toAbsolutePath().normalize();
        Path artifact = modelDir.resolve(modelFile).normalize();
        if (!artifact.startsWith(modelDir)) {
            return false;
        }
        if (isNonEmptyRegularFile(artifact)) {
            return true;
        }
        Path shardDirectory = artifact.getParent();
        if (shardDirectory == null || !Files.isDirectory(shardDirectory)) {
            return false;
        }
        String artifactName = artifact.getFileName().toString();
        int extensionIndex = artifactName.lastIndexOf('.');
        String expectedBase = extensionIndex > 0
                ? artifactName.substring(0, extensionIndex)
                : artifactName;
        Pattern shardPattern = Pattern.compile(Pattern.quote(expectedBase)
                + "\\.shard([0-9]+)-of-([0-9]+)\\.sdnb");
        Set<Integer> indices = new LinkedHashSet<>();
        Integer expectedTotal = null;
        try (Stream<Path> files = Files.list(shardDirectory)) {
            for (Path file : files.toList()) {
                if (!isNonEmptyRegularFile(file)) {
                    continue;
                }
                Matcher matcher = shardPattern.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                int index;
                int total;
                try {
                    index = Integer.parseInt(matcher.group(1));
                    total = Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException ignored) {
                    return false;
                }
                if (total <= 0 || index < 0 || index >= total) {
                    return false;
                }
                if (expectedTotal != null && expectedTotal != total) {
                    return false;
                }
                expectedTotal = total;
                indices.add(index);
            }
        } catch (IOException ignored) {
            return false;
        }
        if (expectedTotal == null || indices.size() != expectedTotal) {
            return false;
        }
        for (int index = 0; index < expectedTotal; index++) {
            if (!indices.contains(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNonEmptyRegularFile(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    private Map<String, Object> stagingModelMetadata(KompileProjectModel model) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String modelType = stagingModelType(model);
        putIfNotBlank(metadata, "model_type", modelType);
        putIfNotBlank(metadata, "framework", firstNonBlank(metadataValue(model, "registry.framework"),
                metadataValue(model, "registry.backend")));
        putIfNotBlank(metadata, "rag_role", model.getRole());
        putIfNotBlank(metadata, "source_origin", model.getSource());
        putIfNotBlank(metadata, "source_repository", model.getSourceRepository());
        putIfNotBlank(metadata, "version", model.getVersion());
        putIfNotBlank(metadata, "description", metadataValue(model, "description"));
        putIfNotBlank(metadata, "original_format", metadataValue(model, "registry.originalFormat"));
        putIfNotBlank(metadata, "embedding_dim", firstNonBlank(
                metadataValue(model, "registry.embeddingDim"),
                metadataValue(model, "embedding_dim")));
        putIfNotBlank(metadata, "max_sequence_length", firstNonBlank(
                metadataValue(model, "registry.maxSequenceLength"),
                metadataValue(model, "max_sequence_length")));
        putIfNotBlank(metadata, "encoder_type", firstNonBlank(
                metadataValue(model, "registry.encoderType"),
                metadataValue(model, "encoder_type")));
        putIfNotBlank(metadata, "pooling_strategy", firstNonBlank(
                metadataValue(model, "registry.poolingStrategy"),
                metadataValue(model, "pooling_strategy")));
        String inputPrefix = rawMetadataValue(model, "registry.inputPrefix");
        if (inputPrefix == null) {
            inputPrefix = rawMetadataValue(model, "input_prefix");
        }
        if (inputPrefix != null && !inputPrefix.isEmpty()) {
            metadata.put("input_prefix", inputPrefix);
        }
        String normalizeOutput = firstNonBlank(
                metadataValue(model, "registry.normalizeOutput"),
                metadataValue(model, "normalize_output"));
        if (normalizeOutput != null) {
            metadata.put("normalize_output", Boolean.parseBoolean(normalizeOutput));
        }
        List<String> supportedLanguages = splitCsv(firstNonBlank(
                metadataValue(model, "registry.supportedLanguages"),
                metadataValue(model, "supported_languages")));
        if (!supportedLanguages.isEmpty()) {
            metadata.put("supported_languages", supportedLanguages);
        }
        putIfNotBlank(metadata, "installed_from", "project");
        putIfNotBlank(metadata, "staging_registry_version", "1.0");
        List<String> components = splitCsv(firstNonBlank(metadataValue(model, "registry.components"),
                metadataValue(model, "components")));
        if (components.isEmpty() && "vlm_pipeline".equals(modelType)) {
            components = List.of("vision_encoder", "language_model", "processor");
        }
        if (!components.isEmpty()) {
            metadata.put("components", components);
        }
        return metadata;
    }

    private String stagingModelType(KompileProjectModel model) {
        String configuredType = firstNonBlank(metadataValue(model, "registry.type"),
                metadataValue(model, "model.type"), metadataValue(model, "modelType"));
        if (configuredType != null) {
            return configuredType.toLowerCase(Locale.ROOT).replace('-', '_');
        }
        String role = normalizeEnumLike(firstNonBlank(model.getRole(), "MODEL"));
        if (role.contains("VLM")) {
            return "vlm_pipeline";
        }
        if (role.contains("OCR")) {
            return "ocr_pipeline";
        }
        if (role.contains("LLM")) {
            return "llm_ggml";
        }
        if (role.contains("CROSS") || role.contains("RERANK")) {
            return "cross_encoder";
        }
        if (role.contains("SPARSE")) {
            return "sparse_encoder";
        }
        return "dense_encoder";
    }

    private String stagingModelPath(KompileProjectModel model, String modelId, String modelFile) {
        String path = firstNonBlank(metadataValue(model, "registry.path"), model.getPath(), modelId);
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("data/models/")) {
            normalized = normalized.substring("data/models/".length());
        }
        if (modelFile != null && normalized.endsWith("/" + modelFile)) {
            normalized = normalized.substring(0, normalized.length() - modelFile.length() - 1);
        }
        return normalized.isBlank() ? modelId : normalized;
    }

    private String stagingModelFile(KompileProjectModel model) {
        String configured = firstNonBlank(metadataValue(model, "registry.modelFile"), metadataValue(model, "model.file"));
        if (configured != null) {
            return configured;
        }
        String path = firstNonBlank(model.getPath());
        if (path != null) {
            String normalized = path.replace('\\', '/');
            int index = normalized.lastIndexOf('/');
            String fileName = index >= 0 ? normalized.substring(index + 1) : normalized;
            if (fileName.contains(".")) {
                return fileName;
            }
        }
        return "model.sdz";
    }

    private String metadataValue(KompileProjectModel model, String key) {
        return model.getMetadata() == null ? null : trimToNull(model.getMetadata().get(key));
    }

    private String rawMetadataValue(KompileProjectModel model, String key) {
        return model.getMetadata() == null ? null : model.getMetadata().get(key);
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        String trimmed = trimToNull(value);
        if (trimmed != null) {
            target.put(key, trimmed);
        }
    }

    private String registryTimestamp(KompileProjectManifest manifest) {
        return manifest.getUpdatedAt() == null ? Instant.now().toString() : manifest.getUpdatedAt().toString();
    }

    private List<String> normalizeTags(Collection<String> input) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (input != null) {
            for (String tag : input) {
                String value = trimToNull(tag);
                if (value != null) {
                    normalized.add(value);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private List<String> splitCsv(String value) {
        List<String> values = new ArrayList<>();
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return values;
        }
        for (String part : trimmed.split(",")) {
            String item = trimToNull(part);
            if (item != null) {
                values.add(item);
            }
        }
        return values;
    }

    private List<String> defaultCodeIndexExcludes() {
        return List.of(".git", ".svn", ".hg", ".kompile", ".claude", ".codex", ".gemini",
                ".opencode", ".cursor", ".idea", ".vscode", ".settings", ".gradle",
                "target", "build", "dist", "out", "node_modules", "__pycache__",
                ".tox", ".mypy_cache", ".pytest_cache", ".angular", ".next", ".nuxt",
                "coverage", ".cache", "bin", "obj");
    }

    private List<String> effectiveCodeIndexExcludes(String requestedExcludes) {
        LinkedHashSet<String> excludes = new LinkedHashSet<>(defaultCodeIndexExcludes());
        excludes.addAll(splitCsv(requestedExcludes));
        return new ArrayList<>(excludes);
    }

    private String slug(String value) {
        String slug = firstNonBlank(value, "component")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "component" : slug;
    }

    private String normalizeEnumLike(String value) {
        return firstNonBlank(value, "COMMAND")
                .trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    private Path normalizeRoot(Path root) {
        if (root == null) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        return root.toAbsolutePath().normalize();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String guessContentType(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "application/msword";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "application/vnd.ms-excel";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/yaml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".eml")) return "message/rfc822";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return "application/gzip";
        return "application/octet-stream";
    }

    private KompileProjectMarkdownEntry parseMarkdownEntry(String relativePath, String raw) {
        KompileProjectMarkdownEntry entry = new KompileProjectMarkdownEntry();
        entry.setPath(relativePath);
        String body = raw == null ? "" : raw;
        if (body.startsWith("---")) {
            int end = body.indexOf("\n---", 3);
            if (end >= 0) {
                String block = body.substring(3, end).trim();
                body = body.substring(end + 4).trim();
                parseFrontmatterInto(entry, block);
            }
        }
        entry.setBody(body);
        if (trimToNull(entry.getTitle()) == null) {
            entry.setTitle(titleFromBody(body, relativePath));
        }
        return entry;
    }

    private void parseFrontmatterInto(KompileProjectMarkdownEntry entry, String block) {
        String activeListKey = null;
        List<String> tagsList = new ArrayList<>();
        for (String rawLine : block.split("\\R")) {
            String line = rawLine.stripTrailing();
            if (line.trim().startsWith("-") && "tags".equals(activeListKey)) {
                String tag = line.trim().substring(1).trim();
                if (tag.startsWith("\"") && tag.endsWith("\"")) {
                    tag = tag.substring(1, tag.length() - 1);
                }
                if (!tag.isBlank()) {
                    tagsList.add(tag);
                }
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                activeListKey = null;
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                activeListKey = key;
                continue;
            }
            activeListKey = null;
            switch (key) {
                case "title" -> entry.setTitle(unquoteFrontmatter(value));
                case "tags" -> entry.setTags(unquoteFrontmatter(value));
                case "created" -> entry.setCreatedAt(unquoteFrontmatter(value));
                case "updated" -> entry.setUpdatedAt(unquoteFrontmatter(value));
                case "source" -> entry.setSource(unquoteFrontmatter(value));
                case "source_path" -> entry.setSourcePath(unquoteFrontmatter(value));
                case "content_type" -> entry.setContentType(unquoteFrontmatter(value));
                case "converter" -> entry.setConverter(unquoteFrontmatter(value));
                case "crawl_profile" -> entry.setCrawlProfile(unquoteFrontmatter(value));
                case "fact_sheet" -> entry.setFactSheet(unquoteFrontmatter(value));
                case "collection" -> entry.setCollection(unquoteFrontmatter(value));
                case "project" -> entry.setProject(unquoteFrontmatter(value));
            }
        }
        if (!tagsList.isEmpty() && trimToNull(entry.getTags()) == null) {
            entry.setTags(String.join(",", tagsList));
        }
    }

    private String unquoteFrontmatter(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String titleFromBody(String body, String fallbackPath) {
        if (body != null) {
            for (String line : body.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("# ")) {
                    return trimmed.substring(2).trim();
                }
            }
        }
        String fileName = Path.of(fallbackPath).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String sanitizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Markdown relative path is required");
        }
        String value = relativePath.replace('\\', '/');
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.contains("..")) {
            value = Path.of(value).normalize().toString().replace('\\', '/');
        }
        return value;
    }

    private KompileProjectGitResult runProcess(Path directory, boolean allowFailure, String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toAbsolutePath().normalize().toFile());
        builder.redirectErrorStream(true);
        GitRunner.augmentPath(builder);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0 && !allowFailure) {
                throw new IllegalStateException("Command failed (" + String.join(" ", command) + "): " + output.trim());
            }
            return new KompileProjectGitResult(exitCode, output);
        } catch (IOException e) {
            if (allowFailure) {
                return new KompileProjectGitResult(127, e.getMessage());
            }
            throw new IllegalStateException("Failed to run command " + String.join(" ", command) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running command " + String.join(" ", command), e);
        }
    }
}
