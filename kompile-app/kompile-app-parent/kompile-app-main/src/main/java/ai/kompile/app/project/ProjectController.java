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

import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectChatSession;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectCrawlResult;
import ai.kompile.project.KompileProjectComponent;
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
import ai.kompile.project.KompileProjectWorkflow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectBackendService projectService;

    public ProjectController(ProjectBackendService projectService) {
        this.projectService = projectService;
    }

    @GetMapping("/current")
    public ProjectResponse current() {
        return projectService.current();
    }

    @PostMapping("/current/init")
    public ProjectResponse init(@RequestBody(required = false) KompileProjectInitRequest request) {
        return projectService.init(request);
    }

    @PostMapping("/current/open")
    public ProjectResponse open() {
        return projectService.open();
    }

    @PostMapping("/current/components")
    public ProjectResponse addComponent(@RequestBody KompileProjectComponent component) {
        return projectService.addComponent(component);
    }

    @PostMapping("/current/scripts")
    public ProjectResponse registerScript(@RequestBody KompileProjectScript script) {
        return projectService.registerScript(script);
    }

    @PostMapping("/current/crawl-profiles")
    public ProjectResponse registerCrawlProfile(@RequestBody KompileProjectCrawlProfile crawlProfile) {
        return projectService.registerCrawlProfile(crawlProfile);
    }

    @PostMapping("/current/workflows")
    public ProjectResponse registerWorkflow(@RequestBody KompileProjectWorkflow workflow) {
        return projectService.registerWorkflow(workflow);
    }

    @PostMapping("/current/models")
    public ProjectResponse registerModel(@RequestBody KompileProjectModel model) {
        return projectService.registerModel(model);
    }

    @PostMapping("/current/pipelines")
    public ProjectResponse registerPipeline(@RequestBody KompileProjectPipeline pipeline) {
        return projectService.registerPipeline(pipeline);
    }

    @PostMapping("/current/registries/sync")
    public ProjectResponse syncProjectRegistries() {
        return projectService.syncProjectRegistries();
    }

    @PostMapping("/current/code-projects")
    public ProjectResponse registerCodingProject(@RequestBody KompileCodingProject codingProject) {
        return projectService.registerCodingProject(codingProject);
    }

    @PostMapping("/current/code-projects/{codingProjectId}/index")
    public Map<String, Object> indexCodingProject(
            @PathVariable String codingProjectId,
            @RequestBody(required = false) IndexRequest request) {
        boolean forceReindex = request != null && request.forceReindex();
        projectService.indexCodingProject(codingProjectId, forceReindex);
        return Map.of("projectId", codingProjectId, "indexing", true, "forceReindex", forceReindex);
    }

    @PutMapping("/current/tags")
    public ProjectResponse setProjectTags(@RequestBody TagsRequest request) {
        return projectService.setProjectTags(request.tags());
    }

    @PutMapping("/current/components/{componentId}/tags")
    public ProjectResponse setComponentTags(@PathVariable String componentId, @RequestBody TagsRequest request) {
        return projectService.setComponentTags(componentId, request.tags());
    }

    @PutMapping("/current/lifecycle")
    public ProjectResponse setLifecycle(@RequestBody LifecycleRequest request) {
        return projectService.setLifecycle(KompileProjectLifecycleState.valueOf(
                request.lifecycle().trim().replace('-', '_').toUpperCase(Locale.ROOT)));
    }

    @GetMapping("/current/markdown")
    public List<KompileProjectMarkdownEntry> listMarkdown() {
        return projectService.listMarkdown();
    }

    @GetMapping("/current/markdown/read")
    public ResponseEntity<KompileProjectMarkdownEntry> readMarkdown(@RequestParam String path) {
        return projectService.readMarkdown(path)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/current/markdown/search")
    public List<KompileProjectMarkdownEntry> searchMarkdown(@RequestParam String q) {
        return projectService.searchMarkdown(q);
    }

    @PostMapping("/current/markdown/register-facts")
    public Map<String, Object> registerCrawlMarkdownAsFacts(
            @RequestBody(required = false) RegisterFactsRequest request) {
        String factSheetName = request != null ? request.factSheetName() : null;
        List<String> registered = projectService.registerCrawlMarkdownAsFacts(factSheetName);
        return Map.of("registered", registered.size(), "files", registered);
    }

    @GetMapping("/current/crawl-results")
    public List<KompileProjectCrawlResult> listCrawlResults() {
        return projectService.listCrawlResults();
    }

    @GetMapping("/current/sources")
    public List<KompileProjectSourceDocument> listSourceDocuments() {
        return projectService.listSourceDocuments();
    }

    @GetMapping("/current/prompt-templates")
    public List<KompileProjectPromptTemplate> listPromptTemplates() {
        return projectService.listPromptTemplates();
    }

    @GetMapping("/current/fact-sheets")
    public List<KompileProjectFactSheet> listFactSheets() {
        return projectService.listFactSheets();
    }

    @GetMapping("/current/chat-sessions")
    public List<KompileProjectChatSession> listChatSessions() {
        return projectService.listChatSessions();
    }

    @GetMapping("/current/note-sync")
    public List<KompileProjectNoteSyncConnection> listNoteSyncConnections() {
        return projectService.listNoteSyncConnections();
    }

    @GetMapping("/current/indexed-documents")
    public List<KompileProjectIndexedDocument> listIndexedDocuments() {
        return projectService.listIndexedDocuments();
    }

    @GetMapping("/current/scripts")
    public List<KompileProjectScript> listScripts() {
        KompileProjectManifest manifest = projectService.getManifest();
        return manifest.getScripts();
    }

    @GetMapping("/current/workflows")
    public List<KompileProjectWorkflow> listWorkflows() {
        KompileProjectManifest manifest = projectService.getManifest();
        return manifest.getWorkflows();
    }

    @GetMapping("/current/models")
    public List<KompileProjectModel> listModels() {
        KompileProjectManifest manifest = projectService.getManifest();
        return manifest.getModels();
    }

    @GetMapping("/current/pipelines")
    public List<KompileProjectPipeline> listPipelines() {
        KompileProjectManifest manifest = projectService.getManifest();
        return manifest.getPipelines();
    }

    @GetMapping("/current/code-projects")
    public List<KompileCodingProject> listCodingProjects() {
        KompileProjectManifest manifest = projectService.getManifest();
        return manifest.getCodingProjects();
    }

    @GetMapping("/current/crawl-profiles")
    public List<KompileProjectCrawlProfile> listCrawlProfiles() {
        KompileProjectManifest manifest = projectService.getManifest();
        return manifest.getCrawlProfiles();
    }

    @PostMapping("/current/git/commit")
    public KompileProjectGitResult commit(@RequestBody(required = false) GitMessageRequest request) {
        String message = request != null && request.message() != null && !request.message().isBlank()
                ? request.message()
                : "Update Kompile project";
        return projectService.commit(message);
    }

    @PostMapping("/current/git/pull")
    public KompileProjectGitResult pull() {
        return projectService.pull();
    }

    @PostMapping("/current/git/push")
    public KompileProjectGitResult push() {
        return projectService.push();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    public record TagsRequest(List<String> tags) {
    }

    public record LifecycleRequest(String lifecycle) {
    }

    public record GitMessageRequest(String message) {
    }

    public record IndexRequest(boolean forceReindex) {
    }

    public record RegisterFactsRequest(String factSheetName) {
    }
}
