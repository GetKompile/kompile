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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KompileProjectStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void staleManifestSaveMergesConcurrentCodeAndCrawlGraphState() {
        KompileProjectStore first = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("concurrent-graph-state");
        request.setIncludeStandardComponents(false);
        first.init(tempDir, request);

        KompileProjectManifest stale = first.load(tempDir);
        KompileCodingProject code = new KompileCodingProject();
        code.setId("app-code");
        code.setCodeProjectId("app-code");
        code.setRootPath(tempDir.toString());
        code.getMetadata().put("knowledgeBaseId", "app-code-knowledge");
        new KompileProjectStore().registerCodingProject(tempDir, code);

        KompileProjectCrawlProfile crawl = new KompileProjectCrawlProfile();
        crawl.setId("app-code-knowledge");
        crawl.setName("App code graph");
        crawl.setSources(List.of("."));
        stale.getCrawlProfiles().add(crawl);
        first.save(tempDir, stale);

        KompileProjectManifest restored = first.load(tempDir);
        assertTrue(restored.getCodingProjects().stream().anyMatch(candidate ->
                "app-code".equals(candidate.getCodeProjectId())
                        && "app-code-knowledge".equals(
                        candidate.getMetadata().get("knowledgeBaseId"))));
        assertTrue(restored.getCrawlProfiles().stream().anyMatch(candidate ->
                "app-code-knowledge".equals(candidate.getId())));
    }

    @Test
    void initCreatesManifestWithStandardComponentsAndTags() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("demo-project");
        request.setBackend(KompileProjectStorageBackend.GIT_XET);
        request.setTags(List.of("demo", "rag", "demo"));
        request.setModules(List.of("app-main", "model-manager"));
        KompileProjectModel model = new KompileProjectModel();
        model.setModelId("ttrpg-vlm");
        model.setRole("vlm");
        model.setVersion("1.0.0");
        request.setModels(List.of(model));
        KompileProjectPipeline pipeline = new KompileProjectPipeline();
        pipeline.setPipelineId("pdf-vlm-ingest");
        pipeline.setRole("vlm_ingest");
        pipeline.setVersion("1.0.0");
        pipeline.setModelRefs(List.of("ttrpg-vlm"));
        request.setPipelines(List.of(pipeline));
        KompileProjectCrawlProfile crawlProfile = new KompileProjectCrawlProfile();
        crawlProfile.setName("Initial Crawl");
        crawlProfile.setSources(List.of("data/input_documents"));
        crawlProfile.setSchemaPresetId("example-schema-v1");
        crawlProfile.setGraphSchemaMode("LENIENT");
        crawlProfile.setWatch(true);
        request.setCrawlProfiles(List.of(crawlProfile));
        KompileProjectWorkflow workflow = new KompileProjectWorkflow();
        workflow.setName("Bootstrap Crawl");
        KompileProjectWorkflowStep workflowStep = new KompileProjectWorkflowStep();
        workflowStep.setType("crawl");
        workflowStep.setRef("initial-crawl");
        workflow.setSteps(List.of(workflowStep));
        request.setWorkflows(List.of(workflow));

        KompileProjectManifest manifest = store.init(tempDir, request);

        assertEquals("demo-project", manifest.getName());
        assertEquals(List.of("demo", "rag"), manifest.getTags());
        assertEquals(KompileProjectStorageBackend.GIT_XET, manifest.getRepository().getBackend());
        assertTrue(manifest.getRepository().isGitXetEnabled());
        assertTrue(Files.isRegularFile(tempDir.resolve(KompileProjectStore.MANIFEST_FILE)));
        assertTrue(Files.isDirectory(tempDir.resolve("data/markdown")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/models")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/models/.staging")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/pipelines")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/code-projects")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/crawls")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/workflows")));
        assertTrue(Files.isDirectory(tempDir.resolve("scripts")));
        // Complete project layout: every registered/used directory is scaffolded.
        assertTrue(Files.isDirectory(tempDir.resolve("data/fact-sheets")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/note-sync")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/indexed-documents")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/artifacts")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/distributions")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/indices")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/input_documents/uploads")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/shared_files")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/mcp-bridges")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/mcp-servers")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/tool-definitions")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/folders")));
        assertTrue(Files.isDirectory(tempDir.resolve("config")));
        assertTrue(Files.isRegularFile(tempDir.resolve("scripts/start-all.sh")));
        assertTrue(Files.isRegularFile(tempDir.resolve("scripts/start-staging.sh")));
        assertTrue(Files.isRegularFile(tempDir.resolve("scripts/start-serving.sh")));
        assertTrue(Files.isRegularFile(tempDir.resolve("scripts/start-app.sh")));
        // Chat and the crawl manager are separate processes: the admin console does not mount
        // /api/agents/chat or /api/unified-crawl, so a project that can only run start-app.sh
        // serves its admin API but cannot chat or ingest.
        assertTrue(Files.isRegularFile(tempDir.resolve("scripts/start-chat.sh")));
        assertTrue(Files.isRegularFile(tempDir.resolve("scripts/start-crawl-manager.sh")));
        assertTrue(Files.isExecutable(tempDir.resolve("scripts/start-chat.sh")));
        assertTrue(Files.isExecutable(tempDir.resolve("scripts/start-crawl-manager.sh")));
        String stagingScript = Files.readString(tempDir.resolve("scripts/start-staging.sh"));
        String servingScript = Files.readString(tempDir.resolve("scripts/start-serving.sh"));
        String appScript = Files.readString(tempDir.resolve("scripts/start-app.sh"));
        assertTrue(stagingScript.contains("kompile project serve --staging-only"));
        assertFalse(stagingScript.contains("kompile manage start staging"));
        assertTrue(servingScript.contains("kompile project serve --serving-only"));
        assertFalse(servingScript.contains("kompile manage start serving"));
        assertTrue(appScript.contains("kompile project serve --app-only"));
        assertFalse(appScript.contains("kompile project service start"));
        assertTrue(Files.isRegularFile(tempDir.resolve("scripts/stop-all.sh")));
        assertTrue(Files.isExecutable(tempDir.resolve("scripts/start-all.sh")));
        assertTrue(Files.isDirectory(tempDir.resolve(".kompile/project")));
        assertTrue(Files.isDirectory(tempDir.resolve(".kompile/cache")));
        assertTrue(Files.isDirectory(tempDir.resolve(".kompile/sessions")));
        assertTrue(Files.isDirectory(tempDir.resolve(".kompile/state")));
        assertTrue(Files.isRegularFile(tempDir.resolve(".kompile/README.md")));
        assertTrue(Files.isRegularFile(tempDir.resolve(".kompile/.gitignore")));
        assertTrue(Files.isRegularFile(tempDir.resolve(KompileProjectStore.OPEN_STATE_FILE)));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "models".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "metadata".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "model-registry".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "pipelines".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "pipeline-registry".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "code-projects".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "crawls".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "workflows".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "scripts".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "artifacts".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "distributions".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "indexed-documents".equals(c.getId())));
        assertTrue(manifest.getComponents().stream().anyMatch(c -> "config".equals(c.getId())));
        // Large-binary components must use the git-xet backend so native distributions
        // and build artifacts are versioned without bloating git history.
        assertEquals(KompileProjectStorageBackend.GIT_XET, manifest.getComponents().stream()
                .filter(c -> "distributions".equals(c.getId())).findFirst().orElseThrow().getStorageBackend());
        assertEquals(KompileProjectComponentType.DISTRIBUTION, manifest.getComponents().stream()
                .filter(c -> "distributions".equals(c.getId())).findFirst().orElseThrow().getType());
        assertEquals(KompileProjectStorageBackend.GIT_XET, manifest.getComponents().stream()
                .filter(c -> "artifacts".equals(c.getId())).findFirst().orElseThrow().getStorageBackend());
        assertTrue(manifest.getScripts().stream().anyMatch(script -> "start-all".equals(script.getId())));
        assertEquals(1, manifest.getModels().size());
        assertEquals("VLM", manifest.getModels().get(0).getRole());
        assertEquals(1, manifest.getPipelines().size());
        assertTrue(Files.isRegularFile(tempDir.resolve("data/models/project-models.json")));
        assertTrue(Files.isRegularFile(tempDir.resolve("data/models/registry.json")));
        assertTrue(Files.isRegularFile(tempDir.resolve("data/pipelines/project-pipelines.json")));
        String stagingRegistry = Files.readString(tempDir.resolve("data/models/registry.json"));
        assertTrue(stagingRegistry.contains("\"model_id\" : \"ttrpg-vlm\""));
        assertTrue(stagingRegistry.contains("\"type\" : \"vlm_pipeline\""));
        assertTrue(stagingRegistry.contains("\"vision_encoder\""));
        assertTrue(stagingRegistry.contains("\"status\" : \"staged\""),
                "A desired project model must not be advertised as active before its artifact exists");
        assertFalse(stagingRegistry.contains("\"status\" : \"active\""));
        Path materializedModelDir = tempDir.resolve("data/models/ttrpg-vlm");
        Files.createDirectories(materializedModelDir);
        Files.writeString(materializedModelDir.resolve("unrelated.shard0-of-2.sdnb"), "wrong-0");
        Files.writeString(materializedModelDir.resolve("unrelated.shard1-of-2.sdnb"), "wrong-1");
        store.save(tempDir, manifest);
        assertTrue(Files.readString(tempDir.resolve("data/models/registry.json"))
                        .contains("\"status\" : \"staged\""),
                "An unrelated complete shard set must not activate the configured model");

        Files.writeString(materializedModelDir.resolve("model.shard0-of-2.sdnb"), "expected-0");
        store.save(tempDir, manifest);
        assertTrue(Files.readString(tempDir.resolve("data/models/registry.json"))
                        .contains("\"status\" : \"staged\""),
                "An incomplete configured shard set must remain staged");

        Files.writeString(materializedModelDir.resolve("model.shard1-of-2.sdnb"), "expected-1");
        store.save(tempDir, manifest);
        assertTrue(Files.readString(tempDir.resolve("data/models/registry.json"))
                        .contains("\"status\" : \"active\""),
                "A complete configured shard set should make the staging registry entry active");

        Files.delete(materializedModelDir.resolve("model.shard0-of-2.sdnb"));
        Files.delete(materializedModelDir.resolve("model.shard1-of-2.sdnb"));
        Files.writeString(materializedModelDir.resolve("model.sdz"), "materialized");
        store.save(tempDir, manifest);
        String materializedRegistry = Files.readString(tempDir.resolve("data/models/registry.json"));
        assertTrue(materializedRegistry.contains("\"status\" : \"active\""),
                "A non-empty configured model artifact should make the staging registry entry active");
        KompileProjectStatus status = store.status(tempDir);
        assertTrue(status.isMetadataPresent());
        assertTrue(status.isOpen());
        assertEquals(manifest.getProjectId(), status.getOpenProjectId());
        assertEquals(1, manifest.getCrawlProfiles().size());
        assertTrue(manifest.getCrawlProfiles().get(0).isGraphExtraction());
        assertTrue(manifest.getWorkflows().stream().anyMatch(w -> "start-services".equals(w.getId())));
        assertTrue(manifest.getWorkflows().stream().anyMatch(w -> "bootstrap-crawl".equals(w.getId())));

        // Registering the scripts is what makes `kompile project serve --chat-only` /
        // --crawl-manager-only resolve a script instead of falling back to a direct launch.
        assertTrue(manifest.getScripts().stream().anyMatch(s -> "start-chat".equals(s.getId())));
        assertTrue(manifest.getScripts().stream().anyMatch(s -> "start-crawl-manager".equals(s.getId())));

        KompileProjectWorkflow startServices = manifest.getWorkflows().stream()
                .filter(w -> "start-services".equals(w.getId())).findFirst().orElseThrow();
        List<String> startStepIds = startServices.getSteps().stream()
                .map(KompileProjectWorkflowStep::getId).toList();
        assertTrue(startStepIds.containsAll(List.of("start-app", "start-chat", "start-crawl-manager")),
                "start-services must bring up all three personas, got: " + startStepIds);

        // The crawl POST that follows is served by the crawl manager, not the admin console. An
        // untargeted health check passes as soon as :8080 answers and the crawl then fires into a
        // connection refused, so this step must probe an endpoint only the crawl manager mounts.
        KompileProjectWorkflow autoIngest = manifest.getWorkflows().stream()
                .filter(w -> "auto-ingest".equals(w.getId())).findFirst().orElseThrow();
        KompileProjectWorkflowStep health = autoIngest.getSteps().stream()
                .filter(s -> "HEALTH_CHECK".equals(s.getType())).findFirst().orElseThrow();
        assertEquals("${appUrl}/api/unified-crawl/jobs/active", health.getUrl());
        assertEquals(200, health.getExpectedStatus());
    }

    @Test
    void ensureStandardServiceLifecycleUpgradesGeneratedLegacyBundle() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("legacy-project");
        store.init(tempDir, request);

        KompileProjectManifest manifest = store.load(tempDir);
        manifest.setScripts(manifest.getScripts().stream()
                .filter(script -> !List.of("start-chat", "start-crawl-manager").contains(script.getId()))
                .toList());
        KompileProjectWorkflow startServices = manifest.getWorkflows().stream()
                .filter(workflow -> "start-services".equals(workflow.getId()))
                .findFirst().orElseThrow();
        startServices.setSteps(startServices.getSteps().stream().limit(3).toList());
        store.save(tempDir, manifest);

        Path startAll = tempDir.resolve("scripts/start-all.sh");
        Path stopAll = tempDir.resolve("scripts/stop-all.sh");
        Path startStaging = tempDir.resolve("scripts/start-staging.sh");
        Path startServing = tempDir.resolve("scripts/start-serving.sh");
        Path startApp = tempDir.resolve("scripts/start-app.sh");
        Files.writeString(startAll, Files.readString(startAll)
                .replace("\"$ROOT/scripts/start-chat.sh\"\n", "")
                .replace("\"$ROOT/scripts/start-crawl-manager.sh\"\n", ""));
        Files.writeString(stopAll, Files.readString(stopAll)
                .replace("chat crawl-manager app serving staging", "app serving staging"));
        Files.writeString(startStaging, Files.readString(startStaging)
                .replace("kompile project serve --staging-only", "kompile manage start staging"));
        Files.writeString(startServing, Files.readString(startServing)
                .replace("kompile project serve --serving-only", "kompile manage start serving"));
        Files.writeString(startApp, Files.readString(startApp)
                .replace("kompile project serve --app-only", "kompile project service start"));
        Files.delete(tempDir.resolve("scripts/start-chat.sh"));
        Files.delete(tempDir.resolve("scripts/start-crawl-manager.sh"));

        KompileProjectManifest upgraded = store.ensureStandardServiceLifecycle(tempDir);

        assertTrue(upgraded.getScripts().stream().anyMatch(script -> "start-chat".equals(script.getId())));
        assertTrue(upgraded.getScripts().stream().anyMatch(script -> "start-crawl-manager".equals(script.getId())));
        KompileProjectWorkflow upgradedStart = upgraded.getWorkflows().stream()
                .filter(workflow -> "start-services".equals(workflow.getId()))
                .findFirst().orElseThrow();
        assertEquals(List.of("start-staging", "start-serving", "start-app", "start-chat",
                        "start-crawl-manager"),
                upgradedStart.getSteps().stream().map(KompileProjectWorkflowStep::getRef).toList());
        assertTrue(Files.readString(startAll).contains("$ROOT/scripts/start-chat.sh"));
        assertTrue(Files.readString(startAll).contains("$ROOT/scripts/start-crawl-manager.sh"));
        assertTrue(Files.readString(stopAll).contains("chat crawl-manager app serving staging"));
        assertTrue(Files.readString(startStaging).contains("kompile project serve --staging-only"));
        assertTrue(Files.readString(startServing).contains("kompile project serve --serving-only"));
        assertTrue(Files.readString(startApp).contains("kompile project serve --app-only"));
        assertTrue(Files.isExecutable(tempDir.resolve("scripts/start-chat.sh")));
        assertTrue(Files.isExecutable(tempDir.resolve("scripts/start-crawl-manager.sh")));
    }

    @Test
    void ensureStandardServiceLifecyclePreservesCustomizedLifecycle() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("custom-project");
        store.init(tempDir, request);

        Path startAll = tempDir.resolve("scripts/start-all.sh");
        String customStart = "#!/usr/bin/env bash\necho custom-start\n";
        Files.writeString(startAll, customStart);
        KompileProjectManifest manifest = store.load(tempDir);
        KompileProjectWorkflow startServices = manifest.getWorkflows().stream()
                .filter(workflow -> "start-services".equals(workflow.getId()))
                .findFirst().orElseThrow();
        startServices.setGenerated(false);
        startServices.setSteps(startServices.getSteps().stream().limit(3).toList());
        store.save(tempDir, manifest);

        KompileProjectManifest preserved = store.ensureStandardServiceLifecycle(tempDir);

        assertEquals(customStart, Files.readString(startAll));
        KompileProjectWorkflow preservedStart = preserved.getWorkflows().stream()
                .filter(workflow -> "start-services".equals(workflow.getId()))
                .findFirst().orElseThrow();
        assertFalse(preservedStart.isGenerated());
        assertEquals(List.of("start-staging", "start-serving", "start-app"),
                preservedStart.getSteps().stream().map(KompileProjectWorkflowStep::getRef).toList());
    }

    @Test
    void openProjectWritesLocalOpenMetadata() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("demo-project");
        KompileProjectManifest manifest = store.init(tempDir, request);
        Files.deleteIfExists(store.openStatePath(tempDir));

        KompileProjectOpenState openState = store.openProject(tempDir);

        assertEquals(manifest.getProjectId(), openState.getProjectId());
        assertEquals("demo-project", openState.getName());
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), openState.getRoot());
        assertTrue(Files.isRegularFile(store.openStatePath(tempDir)));
        assertTrue(store.readOpenState(tempDir).isPresent());
        assertTrue(store.status(tempDir).isOpen());
    }

    @Test
    void gitXetProjectWithoutRemoteDoesNotExposeGitErrorAsRemoteUrl() {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("gitxet-project");
        request.setBackend(KompileProjectStorageBackend.GIT_XET);

        store.init(tempDir, request);

        KompileProjectStatus status = store.status(tempDir);
        assertTrue(status.isGitRepository());
        assertTrue(status.isGitXetEnabled());
        assertNull(status.getRemoteUrl());
        assertTrue(Files.isRegularFile(tempDir.resolve("data/models/README.md")));
    }

    @Test
    void canonicalGitignoreIsWrittenForLocalProjectWithoutGit() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("local-project");
        request.setBackend(KompileProjectStorageBackend.LOCAL);
        request.setInitializeGit(false);

        store.init(tempDir, request);

        Path gitignore = tempDir.resolve(".gitignore");
        assertTrue(Files.isRegularFile(gitignore), ".gitignore must be written even without a git repo");
        String body = Files.readString(gitignore);
        assertTrue(body.contains("data/pids/"));
        assertTrue(body.contains("config/secrets/"));
        assertTrue(body.contains("config/oauth-encryption.key"));
        assertTrue(body.contains("config/channel-admin.token"));
        assertTrue(body.contains(".env*"));
        assertTrue(body.contains("data/orchestrator-db*"));
    }

    @Test
    void existingGitignoreKeepsCustomRulesAndGainsChannelCredentialRule() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), "custom-output/\n");
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("existing-ignore");
        request.setBackend(KompileProjectStorageBackend.LOCAL);
        request.setInitializeGit(false);

        new KompileProjectStore().init(tempDir, request);

        String body = Files.readString(tempDir.resolve(".gitignore"));
        assertTrue(body.contains("custom-output/"));
        assertTrue(body.contains("config/channel-admin.token"));
    }

    @Test
    void gitattributesTracksArtifactsAndDistributions() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("xet-attrs");
        request.setBackend(KompileProjectStorageBackend.GIT_XET);

        store.init(tempDir, request);

        Path attrs = tempDir.resolve(".gitattributes");
        assertTrue(Files.isRegularFile(attrs));
        String body = Files.readString(attrs);
        assertTrue(body.contains("data/artifacts/**"));
        assertTrue(body.contains("data/distributions/**"));
        assertTrue(body.contains("data/models/**"));
    }

    @Test
    void componentTagsCanBeUpdated() {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("demo-project");
        store.init(tempDir, request);

        KompileProjectManifest manifest = store.setComponentTags(tempDir, "models", List.of("foundation", "local"));

        assertEquals(List.of("foundation", "local"),
                manifest.getComponents().stream()
                        .filter(component -> "models".equals(component.getId()))
                        .findFirst()
                        .orElseThrow()
                        .getTags());
    }

    @Test
    void codingProjectRegistersExternalRootAndLocalContextOnly() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("demo-project");
        store.init(tempDir, request);

        Path externalCodeRoot = Files.createTempDirectory("external-code-root");
        Files.writeString(externalCodeRoot.resolve("Example.java"), "class Example {}\n");

        KompileCodingProject codingProject = new KompileCodingProject();
        codingProject.setName("Example Code");
        codingProject.setRootPath(externalCodeRoot.toString());
        codingProject.setCodeProjectId("example-code");
        codingProject.setTags(List.of("java", "demo"));

        KompileProjectManifest manifest = store.registerCodingProject(tempDir, codingProject);

        KompileCodingProject registered = manifest.getCodingProjects().stream()
                .filter(project -> "example-code".equals(project.getCodeProjectId()))
                .findFirst()
                .orElseThrow();
        assertEquals(externalCodeRoot.toAbsolutePath().normalize().toString(), registered.getRootPath());
        assertEquals("data/code-projects/example-code", registered.getContextPath());
        assertTrue(Files.isRegularFile(tempDir.resolve("data/code-projects/example-code/AGENTS.md")));
        assertTrue(Files.isDirectory(tempDir.resolve("data/code-projects/example-code/chats")));
        assertTrue(Files.isRegularFile(externalCodeRoot.resolve("Example.java")));
    }

    @Test
    void listMarkdownReturnsEmptyWhenNoFiles() {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("empty-md");
        store.init(tempDir, request);

        List<KompileProjectMarkdownEntry> entries = store.listMarkdown(tempDir);

        assertTrue(entries.isEmpty());
    }

    @Test
    void writeAndListMarkdownWithFrontmatter() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("md-project");
        store.init(tempDir, request);

        String content = "---\ntitle: Test Note\ntags:\n  - rag\n  - demo\ncreated: 2025-01-01T00:00:00Z\nupdated: 2025-06-01T00:00:00Z\n---\n\n# Test Note\n\nSome body text about RAG pipelines.\n";
        store.writeMarkdown(tempDir, "notes/test-note.md", content);

        List<KompileProjectMarkdownEntry> entries = store.listMarkdown(tempDir);
        assertEquals(1, entries.size());

        KompileProjectMarkdownEntry entry = entries.get(0);
        assertEquals("notes/test-note.md", entry.getPath());
        assertEquals("Test Note", entry.getTitle());
        assertEquals("rag,demo", entry.getTags());
        assertEquals("2025-01-01T00:00:00Z", entry.getCreatedAt());
        assertEquals("2025-06-01T00:00:00Z", entry.getUpdatedAt());
        assertTrue(entry.getBody().contains("Some body text about RAG pipelines."));
    }

    @Test
    void readMarkdownReturnsParsedEntry() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("md-read");
        store.init(tempDir, request);

        String content = "# Introduction\n\nWelcome to the project.\n";
        store.writeMarkdown(tempDir, "intro.md", content);

        var entry = store.readMarkdown(tempDir, "intro.md");
        assertTrue(entry.isPresent());
        assertEquals("intro.md", entry.get().getPath());
        assertEquals("Introduction", entry.get().getTitle());
        assertTrue(entry.get().getBody().contains("Welcome to the project."));
    }

    @Test
    void readMarkdownReturnsEmptyForMissingFile() {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("md-missing");
        store.init(tempDir, request);

        var entry = store.readMarkdown(tempDir, "nonexistent.md");
        assertTrue(entry.isEmpty());
    }

    @Test
    void searchMarkdownFiltersByQuery() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("md-search");
        store.init(tempDir, request);

        store.writeMarkdown(tempDir, "alpha.md", "# Alpha\n\nThis covers vector stores and embeddings.\n");
        store.writeMarkdown(tempDir, "beta.md", "# Beta\n\nThis covers pipeline execution.\n");
        store.writeMarkdown(tempDir, "gamma.md", "---\ntags: vector,search\n---\n\n# Gamma\n\nTagged note.\n");

        List<KompileProjectMarkdownEntry> vectorResults = store.searchMarkdown(tempDir, "vector");
        assertEquals(2, vectorResults.size());

        List<KompileProjectMarkdownEntry> pipelineResults = store.searchMarkdown(tempDir, "pipeline");
        assertEquals(1, pipelineResults.size());
        assertEquals("beta.md", pipelineResults.get(0).getPath());

        List<KompileProjectMarkdownEntry> allResults = store.searchMarkdown(tempDir, null);
        assertEquals(3, allResults.size());
    }

    @Test
    void syncMarkdownCatalogWritesCatalogFile() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("catalog-test");
        store.init(tempDir, request);

        store.writeMarkdown(tempDir, "doc1.md", "# Document One\n\nFirst doc.\n");
        store.writeMarkdown(tempDir, "sub/doc2.md", "---\ntitle: Second Document\ntags: important\n---\n\nSecond doc.\n");
        store.syncMarkdownCatalog(tempDir);

        Path catalogPath = tempDir.resolve("data/markdown/project-markdown.json");
        assertTrue(Files.isRegularFile(catalogPath));
        String catalog = Files.readString(catalogPath);
        assertTrue(catalog.contains("\"count\" : 2"));
        assertTrue(catalog.contains("doc1.md"));
        assertTrue(catalog.contains("sub/doc2.md"));
        assertTrue(catalog.contains("Second Document"));
    }

    @Test
    void saveAutoSyncsMarkdownCatalog() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("auto-catalog");
        store.init(tempDir, request);

        store.writeMarkdown(tempDir, "readme.md", "# README\n\nProject readme.\n");
        KompileProjectManifest manifest = store.load(tempDir);
        store.save(tempDir, manifest);

        Path catalogPath = tempDir.resolve("data/markdown/project-markdown.json");
        assertTrue(Files.isRegularFile(catalogPath));
        String catalog = Files.readString(catalogPath);
        assertTrue(catalog.contains("readme.md"));
    }

    @Test
    void writeMarkdownPathEscapeIsRejected() {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("escape-test");
        store.init(tempDir, request);

        try {
            store.writeMarkdown(tempDir, "../../etc/passwd.md", "bad");
            assertTrue(false, "Expected exception for path escape");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("escapes"));
        }
    }

    @Test
    void cloneInitsManifestWhenAbsent() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        // Create a non-bare repo with an initial commit on main so clone can find the branch
        Path sourceRepo = tempDir.resolve("source-repo");
        Files.createDirectories(sourceRepo);
        runGit(sourceRepo, "init");
        runGit(sourceRepo, "checkout", "-B", "main");
        Files.writeString(sourceRepo.resolve("README.md"), "# Test\n");
        runGit(sourceRepo, "add", "-A");
        runGit(sourceRepo, "-c", "user.name=Test", "-c", "user.email=test@test.com",
                "commit", "-m", "init");

        Path cloneTarget = tempDir.resolve("cloned-project");
        Path cloned = store.cloneRepository(sourceRepo.toString(), cloneTarget, "main", false);

        assertTrue(Files.isRegularFile(cloned.resolve(KompileProjectStore.MANIFEST_FILE)));
        assertTrue(Files.isDirectory(cloned.resolve("data/markdown")));
        assertTrue(Files.isDirectory(cloned.resolve("data/models")));
        KompileProjectManifest manifest = store.load(cloned);
        assertEquals(cloned.getFileName().toString(), manifest.getName());
    }

    @Test
    void codingProjectFactSheetBindingRoundTripsThroughManifest() {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("graph-project");
        KompileProjectManifest manifest = store.init(tempDir, request);
        KompileCodingProject codingProject = new KompileCodingProject();
        codingProject.setId("app");
        codingProject.setCodeProjectId("app-code");
        codingProject.setName("Application");
        codingProject.setRootPath(tempDir.toString());
        codingProject.setFactSheetId(42L);
        manifest.getCodingProjects().add(codingProject);

        store.save(tempDir, manifest);

        KompileCodingProject restored = store.load(tempDir).getCodingProjects().get(0);
        assertEquals(42L, restored.getFactSheetId());
        assertEquals("app-code", restored.getCodeProjectId());
    }

    @Test
    void runtimeModelSnapshotPreservesChecksumEmbeddingAndTokenizerContract() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("runtime-model-project");
        store.init(tempDir, request);
        Path bundle = Files.createDirectories(tempDir.resolve("data/models/encoder-a"));
        Files.write(bundle.resolve("model.sdz"), new byte[]{1, 2, 3});
        Files.writeString(bundle.resolve("tokenizer.json"), "{}");
        KompileProjectModel model = new KompileProjectModel();
        model.setId("encoder-a");
        model.setModelId("encoder-a");
        model.setRegistryModelId("encoder-a");
        model.setRole("ENCODER");
        model.setPath("data/models/encoder-a/model.sdz");
        model.setMetadata(Map.ofEntries(
                Map.entry("registry.type", "dense_encoder"),
                Map.entry("registry.modelFile", "model.sdz"),
                Map.entry("registry.vocabFile", "tokenizer.json"),
                Map.entry("registry.checksum", "abc123"),
                Map.entry("embedding_dim", "384"),
                Map.entry("pooling_strategy", "MEAN"),
                Map.entry("input_prefix", "query: "),
                Map.entry("normalize_output", "true"),
                Map.entry("supported_languages", "multilingual"),
                Map.entry("registry.tokenizerDoLowerCase", "false"),
                Map.entry("registry.tokenizerStripAccents", "false"),
                Map.entry("registry.tokenizerAddSpecialTokens", "true"),
                Map.entry("registry.tokenizerMaxLength", "512"),
                Map.entry("registry.tokenizerPadding", "max_length"),
                Map.entry("registry.tokenizerTruncation", "true")));

        store.registerModel(tempDir, model);

        JsonNode entry = new ObjectMapper().readTree(
                tempDir.resolve("data/models/registry.json").toFile())
                .path("models").path("encoder-a");
        assertEquals("abc123", entry.path("checksum").asText());
        assertEquals(384, entry.path("metadata").path("embedding_dim").asInt());
        assertEquals("MEAN", entry.path("metadata").path("pooling_strategy").asText());
        assertEquals("query: ", entry.path("metadata").path("input_prefix").asText());
        assertFalse(entry.path("tokenizer").path("do_lower_case").asBoolean(true));
        assertEquals(512, entry.path("tokenizer").path("max_length").asInt());
        assertEquals("tokenizer.json", entry.path("vocab_file").asText());
    }

    @Test
    void sourceStageArtifactNeverBecomesAnActiveRuntimeModel() throws Exception {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("source-model-project");
        store.init(tempDir, request);
        Path bundle = Files.createDirectories(tempDir.resolve("data/models/source-a"));
        Files.write(bundle.resolve("model.onnx"), new byte[]{1, 2, 3});
        KompileProjectModel model = new KompileProjectModel();
        model.setId("source-a");
        model.setModelId("source-a");
        model.setRegistryModelId("source-a");
        model.setRole("ENCODER");
        model.setPath("data/models/source-a/model.onnx");
        model.setMetadata(Map.of(
                "registry.type", "dense_encoder",
                "artifact.stage", "SOURCE",
                "runtime.ready", "false"));

        store.registerModel(tempDir, model);

        JsonNode entry = new ObjectMapper().readTree(
                tempDir.resolve("data/models/registry.json").toFile())
                .path("models").path("source-a");
        assertEquals("staged", entry.path("status").asText());
    }

    private static void runGit(Path directory, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args) + " failed");
    }
}
