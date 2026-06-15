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
package ai.kompile.app.project;

import ai.kompile.codeindexer.domain.CodeProject;
import ai.kompile.codeindexer.domain.CodeProjectRepository;
import ai.kompile.codeindexer.service.CodebaseIndexer;
import ai.kompile.chat.history.domain.ChatSession;
import ai.kompile.chat.history.repository.ChatSessionRepository;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectChatSession;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectComponent;
import ai.kompile.project.KompileProjectCrawlResult;
import ai.kompile.project.KompileProjectFactSheet;
import ai.kompile.project.KompileProjectIndexedDocument;
import ai.kompile.project.KompileProjectGitResult;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectLifecycleState;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectMarkdownEntry;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectNoteSyncConnection;
import ai.kompile.project.KompileProjectPipeline;
import ai.kompile.project.KompileProjectPromptTemplate;
import ai.kompile.project.KompileProjectScript;
import ai.kompile.project.KompileProjectSourceDocument;
import ai.kompile.project.KompileProjectStatus;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.KompileProjectWorkflow;
import ai.kompile.app.facts.domain.Fact;
import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.ingest.domain.IndexedDocument;
import ai.kompile.app.ingest.repository.IndexedDocumentRepository;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
public class ProjectBackendService {

    private static final Logger log = LoggerFactory.getLogger(ProjectBackendService.class);
    private final KompileProjectStore store = new KompileProjectStore();
    @Autowired
    private CodeProjectRepository codeProjectRepository;
    @Autowired(required = false)
    private CodebaseIndexer codebaseIndexer;

    @Autowired(required = false)
    private FactSheetService factSheetService;

    @Autowired(required = false)
    private ChatSessionRepository chatSessionRepository;

    @Autowired(required = false)
    private NoteSyncConnectionRepository noteSyncConnectionRepository;

    @Autowired(required = false)
    private IndexedDocumentRepository indexedDocumentRepository;

    @Value("${kompile.project.root:}")
    private String configuredRoot;

    public ProjectBackendService(CodeProjectRepository codeProjectRepository, CodebaseIndexer codebaseIndexer) {
        this.codeProjectRepository = codeProjectRepository;
        this.codebaseIndexer = codebaseIndexer;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected ProjectBackendService() {}


    public ProjectResponse current() {
        Path root = resolveRoot();
        KompileProjectStatus status = store.status(root);
        KompileProjectManifest manifest = status.isManifestPresent() ? store.load(root) : null;
        return new ProjectResponse(manifest, status);
    }

    public ProjectResponse init(KompileProjectInitRequest request) {
        Path root = resolveRoot();
        KompileProjectInitRequest effective = request == null ? new KompileProjectInitRequest() : request;
        if (effective.getName() == null || effective.getName().isBlank()) {
            effective.setName(root.getFileName() != null ? root.getFileName().toString() : "kompile-project");
        }
        KompileProjectManifest manifest = store.init(root, effective);
        return new ProjectResponse(manifest, store.status(root));
    }

    public ProjectResponse open() {
        Path root = requireProjectRoot();
        exportProjectCatalogs(root);
        store.openProject(root);
        return new ProjectResponse(store.load(root), store.status(root));
    }

    public ProjectResponse addComponent(KompileProjectComponent component) {
        Path root = requireProjectRoot();
        KompileProjectManifest manifest = store.addComponent(root, component);
        return new ProjectResponse(manifest, store.status(root));
    }

    public ProjectResponse registerScript(KompileProjectScript script) {
        Path root = requireProjectRoot();
        KompileProjectManifest manifest = store.registerScript(root, script);
        return new ProjectResponse(manifest, store.status(root));
    }

    public ProjectResponse registerCrawlProfile(KompileProjectCrawlProfile crawlProfile) {
        Path root = requireProjectRoot();
        KompileProjectManifest manifest = store.registerCrawlProfile(root, crawlProfile);
        return new ProjectResponse(manifest, store.status(root));
    }

    public ProjectResponse registerWorkflow(KompileProjectWorkflow workflow) {
        Path root = requireProjectRoot();
        KompileProjectManifest manifest = store.registerWorkflow(root, workflow);
        return new ProjectResponse(manifest, store.status(root));
    }

    public ProjectResponse registerModel(KompileProjectModel model) {
        Path root = requireProjectRoot();
        KompileProjectManifest manifest = store.registerModel(root, model);
        return new ProjectResponse(manifest, store.status(root));
    }

    public ProjectResponse registerPipeline(KompileProjectPipeline pipeline) {
        Path root = requireProjectRoot();
        KompileProjectManifest manifest = store.registerPipeline(root, pipeline);
        return new ProjectResponse(manifest, store.status(root));
    }

    public ProjectResponse syncProjectRegistries() {
        Path root = requireProjectRoot();
        KompileProjectManifest manifest = store.syncProjectRegistries(root);
        return new ProjectResponse(manifest, store.status(root));
    }

    @Transactional
    public ProjectResponse registerCodingProject(KompileCodingProject codingProject) {
        Path root = requireProjectRoot();
        KompileProjectManifest manifest = store.registerCodingProject(root, codingProject);
        KompileCodingProject registered = manifest.getCodingProjects().stream()
                .filter(project -> project.getId().equals(codingProject.getId()))
                .findFirst()
                .orElse(codingProject);
        registerWithCodeIndexer(registered);
        return new ProjectResponse(manifest, store.status(root));
    }

    public ProjectResponse setProjectTags(Collection<String> tags) {
        Path root = requireProjectRoot();
        KompileProjectManifest manifest = store.setProjectTags(root, tags);
        return new ProjectResponse(manifest, store.status(root));
    }

    public ProjectResponse setComponentTags(String componentId, Collection<String> tags) {
        Path root = requireProjectRoot();
        KompileProjectManifest manifest = store.setComponentTags(root, componentId, tags);
        return new ProjectResponse(manifest, store.status(root));
    }

    public ProjectResponse setLifecycle(KompileProjectLifecycleState lifecycle) {
        Path root = requireProjectRoot();
        KompileProjectManifest manifest = store.setLifecycle(root, lifecycle);
        return new ProjectResponse(manifest, store.status(root));
    }

    public KompileProjectGitResult commit(String message) {
        return store.gitCommitAll(requireProjectRoot(), message);
    }

    public KompileProjectGitResult pull() {
        return store.gitPull(requireProjectRoot());
    }

    public KompileProjectGitResult push() {
        return store.gitPush(requireProjectRoot());
    }

    public List<KompileProjectMarkdownEntry> listMarkdown() {
        return store.listMarkdown(requireProjectRoot());
    }

    public Optional<KompileProjectMarkdownEntry> readMarkdown(String relativePath) {
        return store.readMarkdown(requireProjectRoot(), relativePath);
    }

    public List<KompileProjectMarkdownEntry> searchMarkdown(String query) {
        return store.searchMarkdown(requireProjectRoot(), query);
    }

    public List<KompileProjectCrawlResult> listCrawlResults() {
        return store.listCrawlResults(requireProjectRoot());
    }

    public List<KompileProjectSourceDocument> listSourceDocuments() {
        return store.listSourceDocuments(requireProjectRoot());
    }

    public List<KompileProjectPromptTemplate> listPromptTemplates() {
        return store.listPromptTemplates(requireProjectRoot());
    }

    public List<KompileProjectFactSheet> listFactSheets() {
        Path root = requireProjectRoot();
        exportProjectCatalogs(root);
        return store.listFactSheets(root);
    }

    public List<KompileProjectChatSession> listChatSessions() {
        Path root = requireProjectRoot();
        exportProjectCatalogs(root);
        return store.listChatSessions(root);
    }

    public List<KompileProjectNoteSyncConnection> listNoteSyncConnections() {
        Path root = requireProjectRoot();
        exportProjectCatalogs(root);
        return store.listNoteSyncConnections(root);
    }

    public List<KompileProjectIndexedDocument> listIndexedDocuments() {
        Path root = requireProjectRoot();
        exportProjectCatalogs(root);
        return store.listIndexedDocuments(root);
    }

    public KompileProjectManifest getManifest() {
        return store.load(requireProjectRoot());
    }

    @Transactional
    public List<String> registerCrawlMarkdownAsFacts(String factSheetName) {
        if (factSheetService == null) {
            throw new IllegalStateException("Fact sheet service is not available");
        }
        Path root = requireProjectRoot();
        List<KompileProjectMarkdownEntry> entries = store.listMarkdown(root);
        FactSheet sheet;
        if (factSheetName != null && !factSheetName.isBlank()) {
            sheet = factSheetService.getSheetByName(factSheetName)
                    .orElseThrow(() -> new IllegalArgumentException("Fact sheet not found: " + factSheetName));
        } else {
            sheet = factSheetService.getActiveSheet();
            if (sheet == null) {
                throw new IllegalStateException("No active fact sheet found");
            }
        }
        List<String> registered = new ArrayList<>();
        for (KompileProjectMarkdownEntry entry : entries) {
            Path filePath = root.resolve("data/markdown").resolve(entry.getPath()).normalize();
            if (!Files.isRegularFile(filePath)) continue;
            long size;
            try { size = Files.size(filePath); } catch (Exception e) { size = 0; }
            factSheetService.addFact(sheet.getId(),
                    filePath.getFileName().toString(),
                    filePath.toAbsolutePath().toString(),
                    null,
                    Fact.SourceType.STORED,
                    "md",
                    "text/markdown",
                    size,
                    Fact.ViewMode.TEXT,
                    true,
                    null);
            registered.add(entry.getPath());
        }
        return registered;
    }

    @Transactional
    public void indexCodingProject(String codingProjectId, boolean forceReindex) {
        KompileProjectManifest manifest = store.load(requireProjectRoot());
        KompileCodingProject codingProject = manifest.getCodingProjects().stream()
                .filter(project -> codingProjectId.equals(project.getId())
                        || codingProjectId.equals(project.getCodeProjectId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown coding project: " + codingProjectId));
        registerWithCodeIndexer(codingProject);
        codebaseIndexer.indexDirectoryAsync(codingProject.getCodeProjectId(), codingProject.getRootPath(), forceReindex);
    }

    private void registerWithCodeIndexer(KompileCodingProject codingProject) {
        CodeProject project = codeProjectRepository.findByProjectId(codingProject.getCodeProjectId())
                .orElseGet(() -> CodeProject.builder()
                        .projectId(codingProject.getCodeProjectId())
                        .name(codingProject.getName())
                        .color("#4caf50")
                        .icon("code")
                        .build());
        project.setName(codingProject.getName());
        project.setDescription(codingProject.getDescription());
        project.setTags(codingProject.getTags() != null ? String.join(",", codingProject.getTags()) : "");
        project.setAutoIndex(codingProject.isAutoIndex());
        project.setIncludePatterns(codingProject.getIncludePatterns());
        project.setExcludePatterns(codingProject.getExcludePatterns());
        codeProjectRepository.save(project);

        codebaseIndexer.addDirectory(
                codingProject.getCodeProjectId(),
                codingProject.getRootPath(),
                codingProject.getName(),
                codingProject.getIncludePatterns(),
                codingProject.getExcludePatterns(),
                null,
                codingProject.getDescription(),
                codingProject.getTags() != null ? String.join(",", codingProject.getTags()) : "");
    }

    /**
     * Exports backend-managed data (fact sheets, chat sessions, note sync connections)
     * to the project's local file format so they are tracked in the project directory.
     */
    public void exportProjectCatalogs(Path root) {
        // Export fact sheets
        if (factSheetService != null) {
            try {
                List<FactSheet> sheets = factSheetService.getAllSheets();
                List<KompileProjectFactSheet> exported = new ArrayList<>();
                for (FactSheet sheet : sheets) {
                    KompileProjectFactSheet pfs = new KompileProjectFactSheet();
                    pfs.setId(sheet.getId());
                    pfs.setName(sheet.getName());
                    pfs.setDescription(sheet.getDescription());
                    pfs.setActive(Boolean.TRUE.equals(sheet.getIsActive()));
                    pfs.setColor(sheet.getColor());
                    pfs.setIcon(sheet.getIcon());
                    pfs.setVectorStorePath(sheet.getVectorStorePath());
                    pfs.setKeywordIndexPath(sheet.getKeywordIndexPath());
                    pfs.setEmbeddingModel(sheet.getEmbeddingModel());
                    pfs.setEmbeddingModelSource(sheet.getEmbeddingModelSource());
                    pfs.setRerankingEnabled(Boolean.TRUE.equals(sheet.getRerankingEnabled()));
                    pfs.setRerankerType(sheet.getRerankerType());
                    pfs.setEnableGraphBuilding(Boolean.TRUE.equals(sheet.getEnableGraphBuilding()));
                    pfs.setGraphBuilderType(sheet.getGraphBuilderType());
                    pfs.setGraphStorageType(sheet.getGraphStorageType());
                    pfs.setFactCount(sheet.getFacts() != null ? sheet.getFacts().size() : 0);
                    pfs.setIndexedAt(sheet.getIndexedAt() != null ? sheet.getIndexedAt().toString() : null);
                    pfs.setCreatedAt(sheet.getCreatedAt() != null ? sheet.getCreatedAt().toString() : null);
                    pfs.setUpdatedAt(sheet.getUpdatedAt() != null ? sheet.getUpdatedAt().toString() : null);
                    exported.add(pfs);
                }
                store.writeFactSheetCatalog(root, exported);
            } catch (Exception e) {
                log.warn("Failed to export fact sheet catalog: {}", e.getMessage(), e);
            }
        }

        // Export chat sessions
        if (chatSessionRepository != null) {
            try {
                List<ChatSession> sessions = chatSessionRepository.findAll();
                List<KompileProjectChatSession> exported = new ArrayList<>();
                for (ChatSession session : sessions) {
                    KompileProjectChatSession pcs = new KompileProjectChatSession();
                    pcs.setSessionId(session.getSessionId());
                    pcs.setTitle(session.getTitle());
                    pcs.setSource(session.getSource());
                    pcs.setMessageCount(session.getMessageCount());
                    pcs.setCodeProjectId(session.getCodeProjectId());
                    if (session.getFactSheetId() != null && factSheetService != null) {
                        factSheetService.getSheetById(session.getFactSheetId())
                                .ifPresent(fs -> pcs.setFactSheetName(fs.getName()));
                    }
                    pcs.setCreatedAt(session.getCreatedAt() != null ? session.getCreatedAt().toString() : null);
                    pcs.setUpdatedAt(session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : null);
                    exported.add(pcs);
                }
                store.writeChatCatalog(root, exported);
            } catch (Exception e) {
                log.warn("Failed to export chat session catalog: {}", e.getMessage(), e);
            }
        }

        // Export note sync connections
        if (noteSyncConnectionRepository != null) {
            try {
                List<NoteSyncConnection> connections = noteSyncConnectionRepository.findAll();
                List<KompileProjectNoteSyncConnection> exported = new ArrayList<>();
                for (NoteSyncConnection conn : connections) {
                    KompileProjectNoteSyncConnection pnc = new KompileProjectNoteSyncConnection();
                    pnc.setId(conn.getId());
                    pnc.setProvider(conn.getProvider() != null ? conn.getProvider().name() : null);
                    pnc.setExternalScope(conn.getExternalScope());
                    pnc.setDirection(conn.getDirection() != null ? conn.getDirection().name() : null);
                    pnc.setEnabled(Boolean.TRUE.equals(conn.getEnabled()));
                    pnc.setRepositoryUrl(conn.getRepositoryUrl());
                    pnc.setGitBranch(conn.getGitBranch());
                    pnc.setLastSyncAt(conn.getLastSyncAt() != null ? conn.getLastSyncAt().toString() : null);
                    pnc.setLastSyncStatus(conn.getLastSyncStatus());
                    pnc.setCreatedAt(conn.getCreatedAt() != null ? conn.getCreatedAt().toString() : null);
                    pnc.setUpdatedAt(conn.getUpdatedAt() != null ? conn.getUpdatedAt().toString() : null);
                    // Resolve fact sheet name
                    if (conn.getFactSheetId() != null && factSheetService != null) {
                        factSheetService.getSheetById(conn.getFactSheetId())
                                .ifPresent(fs -> pnc.setFactSheetName(fs.getName()));
                    }
                    exported.add(pnc);
                }
                store.writeNoteSyncCatalog(root, exported);
            } catch (Exception e) {
                log.warn("Failed to export note sync connection catalog: {}", e.getMessage(), e);
            }
        }

        // Export indexed documents (managed sources)
        if (indexedDocumentRepository != null) {
            try {
                List<IndexedDocument> documents = indexedDocumentRepository.findAll();
                List<KompileProjectIndexedDocument> exported = new ArrayList<>();
                for (IndexedDocument doc : documents) {
                    KompileProjectIndexedDocument pid = new KompileProjectIndexedDocument();
                    pid.setId(doc.getId());
                    pid.setSourceId(doc.getSourceId());
                    pid.setFileName(doc.getFileName());
                    pid.setChecksum(doc.getChecksum());
                    pid.setKeywordIndexStatus(doc.getKeywordIndexStatus() != null ? doc.getKeywordIndexStatus().name() : null);
                    pid.setKeywordPassageCount(doc.getKeywordPassageCount() != null ? doc.getKeywordPassageCount() : 0);
                    pid.setVectorStoreStatus(doc.getVectorStoreStatus() != null ? doc.getVectorStoreStatus().name() : null);
                    pid.setVectorPassageCount(doc.getVectorPassageCount() != null ? doc.getVectorPassageCount() : 0);
                    pid.setGraphStatus(doc.getGraphStatus() != null ? doc.getGraphStatus().name() : null);
                    pid.setGraphNodeCount(doc.getGraphNodeCount() != null ? doc.getGraphNodeCount() : 0);
                    pid.setOverallStatus(doc.getOverallStatus() != null ? doc.getOverallStatus().name() : null);
                    pid.setCreatedAt(doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null);
                    pid.setUpdatedAt(doc.getUpdatedAt() != null ? doc.getUpdatedAt().toString() : null);
                    // Resolve fact sheet name
                    if (doc.getFactSheetId() != null && factSheetService != null) {
                        factSheetService.getSheetById(doc.getFactSheetId())
                                .ifPresent(fs -> pid.setFactSheetName(fs.getName()));
                    }
                    exported.add(pid);
                }
                store.writeIndexedDocumentCatalog(root, exported);
            } catch (Exception e) {
                log.warn("Failed to export indexed document catalog: {}", e.getMessage(), e);
            }
        }
    }

    private Path requireProjectRoot() {
        Path root = resolveRoot();
        if (!store.status(root).isManifestPresent()) {
            throw new IllegalStateException("No Kompile project manifest found. Initialize the project first.");
        }
        return root;
    }

    private Path resolveRoot() {
        Path start = configuredRoot != null && !configuredRoot.isBlank()
                ? Path.of(configuredRoot)
                : Path.of(System.getProperty("user.dir"));
        Optional<Path> manifestRoot = store.findProjectRoot(start);
        return manifestRoot.orElse(start.toAbsolutePath().normalize());
    }
}
