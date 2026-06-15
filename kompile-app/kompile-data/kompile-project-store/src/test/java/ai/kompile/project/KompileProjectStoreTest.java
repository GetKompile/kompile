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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KompileProjectStoreTest {

    @TempDir
    Path tempDir;

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
        crawlProfile.setSchemaPresetId("fpna-cpg-channel-v1");
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
        assertTrue(Files.isRegularFile(tempDir.resolve("scripts/start-all.sh")));
        assertTrue(Files.isRegularFile(tempDir.resolve("scripts/start-staging.sh")));
        assertTrue(Files.isRegularFile(tempDir.resolve("scripts/start-serving.sh")));
        assertTrue(Files.isRegularFile(tempDir.resolve("scripts/start-app.sh")));
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
        KompileProjectStatus status = store.status(tempDir);
        assertTrue(status.isMetadataPresent());
        assertTrue(status.isOpen());
        assertEquals(manifest.getProjectId(), status.getOpenProjectId());
        assertEquals(1, manifest.getCrawlProfiles().size());
        assertTrue(manifest.getCrawlProfiles().get(0).isGraphExtraction());
        assertTrue(manifest.getWorkflows().stream().anyMatch(w -> "start-services".equals(w.getId())));
        assertTrue(manifest.getWorkflows().stream().anyMatch(w -> "bootstrap-crawl".equals(w.getId())));
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
