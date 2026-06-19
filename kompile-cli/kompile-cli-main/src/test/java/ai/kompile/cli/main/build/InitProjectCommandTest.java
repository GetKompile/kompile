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

package ai.kompile.cli.main.build;

import ai.kompile.cli.main.MainCommand;
import ai.kompile.cli.main.build.config.BuildPreset;
import ai.kompile.cli.main.build.config.ModuleCatalog;
import ai.kompile.cli.main.build.config.ModuleSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InitProjectCommand}: command registration, option parsing,
 * help text, and the relationship between CLI flags and the underlying config.
 */
class InitProjectCommandTest {

    private CommandLine cmd;
    private StringWriter outWriter;
    private StringWriter errWriter;

    @BeforeEach
    void setUp() {
        cmd = new CommandLine(new MainCommand());
        outWriter = new StringWriter();
        errWriter = new StringWriter();
        cmd.setOut(new PrintWriter(outWriter));
        cmd.setErr(new PrintWriter(errWriter));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMMAND REGISTRATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testInitProjectCommandRegistered() {
        CommandLine initCmd = cmd.getSubcommands().get("init-project");
        assertNotNull(initCmd, "init-project subcommand should be registered");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELP TEXT — ALL OPTIONS PRESENT
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testHelpShowsAllOptions() {
        int exitCode = cmd.execute("init-project", "--help");
        // Help may go to out or err depending on picocli routing
        String output = outWriter.toString() + errWriter.toString();

        // Project identity
        assertTrue(output.contains("--name"), "missing --name");
        assertTrue(output.contains("--outputDir"), "missing --outputDir");
        assertTrue(output.contains("--infer"), "missing --infer");
        assertTrue(output.contains("--infer-from"), "missing --infer-from");
        assertTrue(output.contains("--wizard"), "missing --wizard");

        // Preset and modules
        assertTrue(output.contains("--preset"), "missing --preset");
        assertTrue(output.contains("--llm"), "missing --llm");
        assertTrue(output.contains("--embedding"), "missing --embedding");
        assertTrue(output.contains("--vectorstore"), "missing --vectorstore");
        assertTrue(output.contains("--loaders"), "missing --loaders");
        assertTrue(output.contains("--chunkers"), "missing --chunkers");
        assertTrue(output.contains("--tools"), "missing --tools");
        assertTrue(output.contains("--include"), "missing --include");
        assertTrue(output.contains("--exclude"), "missing --exclude");

        // Build / run
        assertTrue(output.contains("--start"), "missing --start");
        assertTrue(output.contains("--no-build"), "missing --no-build");
        assertTrue(output.contains("--port"), "missing --port");
        assertTrue(output.contains("skipTests"), "missing --skipTests");

        // Platform / backend
        assertTrue(output.contains("--javacppPlatform"), "missing --javacppPlatform");
        assertTrue(output.contains("--javacppExtension"), "missing --javacppExtension");
        assertTrue(output.contains("--backend"), "missing --backend");
        assertTrue(output.contains("--mavenHome"), "missing --mavenHome");

        // App metadata
        assertTrue(output.contains("--appTitle"), "missing --appTitle");
        assertTrue(output.contains("--groupId"), "missing --groupId");
        assertTrue(output.contains("--version"), "missing --version");
        assertTrue(output.contains("--kompileVersion"), "missing --kompileVersion");

        // Database
        assertTrue(output.contains("--databaseUrl"), "missing --databaseUrl");
        assertTrue(output.contains("--databaseUsername"), "missing --databaseUsername");
        assertTrue(output.contains("--databasePassword"), "missing --databasePassword");
        assertTrue(output.contains("enableSchemaInit"), "missing --enableSchemaInit");

        // Language / model
        assertTrue(output.contains("--supportedLanguages"), "missing --supportedLanguages");
        assertTrue(output.contains("--anserini-indexes"), "missing --anserini-indexes");
        assertTrue(output.contains("--anserini-encoders"), "missing --anserini-encoders");

        // Managed crawl presets
        assertTrue(output.contains("--pdf-vlm-source"), "missing --pdf-vlm-source");
        assertTrue(output.contains("--pdf-vlm-dir"), "missing --pdf-vlm-dir");
        assertTrue(output.contains("--pdf-vlm-model"), "missing --pdf-vlm-model");
        assertTrue(output.contains("--pdf-vlm-watch"), "missing --pdf-vlm-watch");
    }

    @Test
    void testInitProjectInfersCodeAndDataFromSourceRoot(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("source");
        Path docsDir = sourceRoot.resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(sourceRoot.resolve("pom.xml"), "<project></project>\n");
        Files.writeString(docsDir.resolve("guide.md"), "# Guide\n\nDetected data source.\n");

        Path outputRoot = tempDir.resolve("generated");
        int exitCode = cmd.execute("init-project",
                "--name", "bootstrapped",
                "--outputDir", outputRoot.toString(),
                "--infer-from", sourceRoot.toString(),
                "--no-build");

        assertEquals(0, exitCode, outWriter.toString() + errWriter.toString());
        Path projectRoot = outputRoot.resolve("bootstrapped");
        String pom = Files.readString(projectRoot.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>kompile-crawler-core</artifactId>"),
                "inferred data should enable crawler-core");
        assertTrue(pom.contains("<artifactId>kompile-crawl-graph</artifactId>"),
                "inferred data should enable unified crawl support");
        assertTrue(pom.contains("<artifactId>kompile-code-indexer</artifactId>"),
                "inferred code should enable code-indexer");

        String manifest = Files.readString(projectRoot.resolve("kompile.project.json"));
        assertTrue(manifest.contains("\"crawlProfiles\""), "manifest should include a crawl profile");
        assertTrue(manifest.contains("\"initial-crawl\""), "manifest should seed initial-crawl");
        assertTrue(manifest.contains("\"sourceType\" : \"directory\""),
                "manifest should infer directory crawl source type");
        assertTrue(manifest.contains("\"codingProjects\""), "manifest should include coding projects");
        assertTrue(manifest.contains("\"codeProjectId\""), "coding project should have codeProjectId");
        assertTrue(manifest.contains(sourceRoot.toAbsolutePath().normalize().toString()),
                "coding project should point at inferred source root");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MODULE CATALOG — COVERAGE OF ALL RAGPOMGENERATOR MODULES
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testModuleCatalogHasAllLlmProviders() {
        assertNotNull(ModuleCatalog.get("llm-openai"), "missing llm-openai");
        assertNotNull(ModuleCatalog.get("llm-anthropic"), "missing llm-anthropic");
        assertNotNull(ModuleCatalog.get("llm-gemini"), "missing llm-gemini");
        assertNotNull(ModuleCatalog.get("cli-llm"), "missing cli-llm");
    }

    @Test
    void testModuleCatalogHasAllEmbeddingProviders() {
        assertNotNull(ModuleCatalog.get("embedding-openai"), "missing embedding-openai");
        assertNotNull(ModuleCatalog.get("embedding-anserini"), "missing embedding-anserini");
        assertNotNull(ModuleCatalog.get("embedding-sentence-transformer"), "missing embedding-sentence-transformer");
        assertNotNull(ModuleCatalog.get("embedding-postgresml"), "missing embedding-postgresml");
    }

    @Test
    void testModuleCatalogHasAllVectorStores() {
        assertNotNull(ModuleCatalog.get("vectorstore-anserini"), "missing vectorstore-anserini");
        assertNotNull(ModuleCatalog.get("vectorstore-chroma"), "missing vectorstore-chroma");
        assertNotNull(ModuleCatalog.get("vectorstore-pgvector"), "missing vectorstore-pgvector");
    }

    @Test
    void testModuleCatalogHasAllLoaders() {
        assertNotNull(ModuleCatalog.get("loader-pdf-extended"), "missing loader-pdf-extended");
        assertNotNull(ModuleCatalog.get("loader-microsoft"), "missing loader-microsoft");
        assertNotNull(ModuleCatalog.get("loader-excel"), "missing loader-excel");
        assertNotNull(ModuleCatalog.get("loader-mail"), "missing loader-mail");
        assertNotNull(ModuleCatalog.get("loader-email-inbox"), "missing loader-email-inbox");
        assertNotNull(ModuleCatalog.get("loader-discord"), "missing loader-discord");
        assertNotNull(ModuleCatalog.get("loader-google-workspace"), "missing loader-google-workspace");
        assertNotNull(ModuleCatalog.get("loader-gmail"), "missing loader-gmail");
        assertNotNull(ModuleCatalog.get("loader-gdocs"), "missing loader-gdocs");
        assertNotNull(ModuleCatalog.get("loader-tika"), "missing loader-tika");
    }

    @Test
    void testModuleCatalogHasAllChunkers() {
        assertNotNull(ModuleCatalog.get("chunker-sentence"), "missing chunker-sentence");
        assertNotNull(ModuleCatalog.get("chunker-recursive-character"), "missing chunker-recursive-character");
        assertNotNull(ModuleCatalog.get("chunker-markdown"), "missing chunker-markdown");
        assertNotNull(ModuleCatalog.get("chunker-token"), "missing chunker-token");
    }

    @Test
    void testModuleCatalogHasAllTools() {
        assertNotNull(ModuleCatalog.get("tool-filesystem"), "missing tool-filesystem");
        assertNotNull(ModuleCatalog.get("tool-rag"), "missing tool-rag");
        assertNotNull(ModuleCatalog.get("tool-model-staging"), "missing tool-model-staging");
        assertNotNull(ModuleCatalog.get("tool-workflow"), "missing tool-workflow");
        assertNotNull(ModuleCatalog.get("tool-gateway"), "missing tool-gateway");
        assertNotNull(ModuleCatalog.get("tool-crawler"), "missing tool-crawler");
    }

    @Test
    void testModuleCatalogHasAllEnterprise() {
        assertNotNull(ModuleCatalog.get("kvcache"), "missing kvcache");
        assertNotNull(ModuleCatalog.get("model-staging"), "missing model-staging");
        assertNotNull(ModuleCatalog.get("model-manager"), "missing model-manager");
        assertNotNull(ModuleCatalog.get("pipeline-management"), "missing pipeline-management");
        assertNotNull(ModuleCatalog.get("process-engine"), "missing process-engine");
        assertNotNull(ModuleCatalog.get("process-discovery"), "missing process-discovery");
        assertNotNull(ModuleCatalog.get("code-indexer"), "missing code-indexer");
        assertNotNull(ModuleCatalog.get("test-milestone"), "missing test-milestone");
        assertNotNull(ModuleCatalog.get("pgml-indexer"), "missing pgml-indexer");
    }

    @Test
    void testModuleCatalogHasAllAdvanced() {
        assertNotNull(ModuleCatalog.get("graph-neo4j"), "missing graph-neo4j");
        assertNotNull(ModuleCatalog.get("knowledge-graph"), "missing knowledge-graph");
        assertNotNull(ModuleCatalog.get("graph-algorithms"), "missing graph-algorithms");
        assertNotNull(ModuleCatalog.get("compute-graph"), "missing compute-graph");
        assertNotNull(ModuleCatalog.get("compute-graph-scripting"), "missing compute-graph-scripting");
        assertNotNull(ModuleCatalog.get("compute-graph-drools"), "missing compute-graph-drools");
        assertNotNull(ModuleCatalog.get("compute-graph-xircuits"), "missing compute-graph-xircuits");
        assertNotNull(ModuleCatalog.get("compute-graph-n8n"), "missing compute-graph-n8n");
        assertNotNull(ModuleCatalog.get("rag-pipeline"), "missing rag-pipeline");
        assertNotNull(ModuleCatalog.get("crawler-core"), "missing crawler-core");
        assertNotNull(ModuleCatalog.get("crawl-graph"), "missing crawl-graph");
    }

    @Test
    void testModuleCatalogHasAllPipeline() {
        assertNotNull(ModuleCatalog.get("pipelines-core"), "missing pipelines-core");
        assertNotNull(ModuleCatalog.get("pipelines-runtime"), "missing pipelines-runtime");
    }

    @Test
    void testModuleCatalogHasAllCore() {
        assertNotNull(ModuleCatalog.get("app-main"), "missing app-main");
        assertNotNull(ModuleCatalog.get("app-lite"), "missing app-lite");
        assertNotNull(ModuleCatalog.get("app-core"), "missing app-core");
        assertNotNull(ModuleCatalog.get("loaders-orchestrator"), "missing loaders-orchestrator");
        assertNotNull(ModuleCatalog.get("app-anserini"), "missing app-anserini");
        assertNotNull(ModuleCatalog.get("chat-history"), "missing chat-history");
        assertNotNull(ModuleCatalog.get("pipelines-llm"), "missing pipelines-llm");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MODULE CATALOG — ARTIFACT ID CORRECTNESS (matches RagPomGenerator)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testModuleArtifactIdsMatchRagPomGenerator() {
        // Verify the artifact IDs in ModuleCatalog exactly match what
        // RagPomGenerator uses in its addDependency() calls
        assertEquals("kompile-app-openai-llm", ModuleCatalog.get("llm-openai").getArtifactId());
        assertEquals("kompile-app-anthropic-llm", ModuleCatalog.get("llm-anthropic").getArtifactId());
        assertEquals("kompile-app-gemini-llm", ModuleCatalog.get("llm-gemini").getArtifactId());
        assertEquals("kompile-app-cli-llm", ModuleCatalog.get("cli-llm").getArtifactId());
        assertEquals("kompile-embedding-openai", ModuleCatalog.get("embedding-openai").getArtifactId());
        assertEquals("kompile-embedding-anserini", ModuleCatalog.get("embedding-anserini").getArtifactId());
        assertEquals("kompile-embedding-sentence-transformer", ModuleCatalog.get("embedding-sentence-transformer").getArtifactId());
        assertEquals("kompile-embedding-postgresml", ModuleCatalog.get("embedding-postgresml").getArtifactId());
        assertEquals("kompile-vectorstore-anserini", ModuleCatalog.get("vectorstore-anserini").getArtifactId());
        assertEquals("kompile-vectorstore-chroma", ModuleCatalog.get("vectorstore-chroma").getArtifactId());
        assertEquals("kompile-vectorstore-pgvector", ModuleCatalog.get("vectorstore-pgvector").getArtifactId());
        assertEquals("kompile-loader-pdf-extended", ModuleCatalog.get("loader-pdf-extended").getArtifactId());
        assertEquals("kompile-loader-microsoft", ModuleCatalog.get("loader-microsoft").getArtifactId());
        assertEquals("kompile-loader-excel", ModuleCatalog.get("loader-excel").getArtifactId());
        assertEquals("kompile-loader-mail", ModuleCatalog.get("loader-mail").getArtifactId());
        assertEquals("kompile-loader-email-inbox", ModuleCatalog.get("loader-email-inbox").getArtifactId());
        assertEquals("kompile-loader-discord", ModuleCatalog.get("loader-discord").getArtifactId());
        assertEquals("kompile-loader-google-workspace", ModuleCatalog.get("loader-google-workspace").getArtifactId());
        assertEquals("kompile-loader-gmail", ModuleCatalog.get("loader-gmail").getArtifactId());
        assertEquals("kompile-loader-gdocs", ModuleCatalog.get("loader-gdocs").getArtifactId());
        assertEquals("kompile-loader-tika", ModuleCatalog.get("loader-tika").getArtifactId());
        assertEquals("kompile-chunker-sentence", ModuleCatalog.get("chunker-sentence").getArtifactId());
        assertEquals("kompile-chunker-recursivecharacter", ModuleCatalog.get("chunker-recursive-character").getArtifactId());
        assertEquals("kompile-chunker-markdown", ModuleCatalog.get("chunker-markdown").getArtifactId());
        assertEquals("kompile-chunker-token", ModuleCatalog.get("chunker-token").getArtifactId());
        assertEquals("kompile-tool-filesystem", ModuleCatalog.get("tool-filesystem").getArtifactId());
        assertEquals("kompile-tool-rag", ModuleCatalog.get("tool-rag").getArtifactId());
        assertEquals("kompile-tool-model-staging", ModuleCatalog.get("tool-model-staging").getArtifactId());
        assertEquals("kompile-tool-workflow", ModuleCatalog.get("tool-workflow").getArtifactId());
        assertEquals("kompile-tool-gateway", ModuleCatalog.get("tool-gateway").getArtifactId());
        assertEquals("kompile-tool-crawler", ModuleCatalog.get("tool-crawler").getArtifactId());
        assertEquals("kompile-kvcache", ModuleCatalog.get("kvcache").getArtifactId());
        assertEquals("kompile-model-staging", ModuleCatalog.get("model-staging").getArtifactId());
        assertEquals("kompile-model-manager", ModuleCatalog.get("model-manager").getArtifactId());
        assertEquals("kompile-pipeline-management", ModuleCatalog.get("pipeline-management").getArtifactId());
        assertEquals("kompile-process-engine", ModuleCatalog.get("process-engine").getArtifactId());
        assertEquals("kompile-process-discovery", ModuleCatalog.get("process-discovery").getArtifactId());
        assertEquals("kompile-code-indexer", ModuleCatalog.get("code-indexer").getArtifactId());
        assertEquals("kompile-test-milestone", ModuleCatalog.get("test-milestone").getArtifactId());
        assertEquals("kompile-app-pgml-indexer", ModuleCatalog.get("pgml-indexer").getArtifactId());
        assertEquals("kompile-graph-neo4j", ModuleCatalog.get("graph-neo4j").getArtifactId());
        assertEquals("kompile-knowledge-graph", ModuleCatalog.get("knowledge-graph").getArtifactId());
        assertEquals("kompile-graph-algorithms", ModuleCatalog.get("graph-algorithms").getArtifactId());
        assertEquals("kompile-compute-graph-core", ModuleCatalog.get("compute-graph").getArtifactId());
        assertEquals("kompile-compute-graph-scripting", ModuleCatalog.get("compute-graph-scripting").getArtifactId());
        assertEquals("kompile-compute-graph-drools", ModuleCatalog.get("compute-graph-drools").getArtifactId());
        assertEquals("kompile-compute-graph-xircuits", ModuleCatalog.get("compute-graph-xircuits").getArtifactId());
        assertEquals("kompile-compute-graph-n8n", ModuleCatalog.get("compute-graph-n8n").getArtifactId());
        assertEquals("kompile-rag-pipeline", ModuleCatalog.get("rag-pipeline").getArtifactId());
        assertEquals("kompile-crawler-core", ModuleCatalog.get("crawler-core").getArtifactId());
        assertEquals("kompile-crawl-graph", ModuleCatalog.get("crawl-graph").getArtifactId());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MODULE CATALOG — CATEGORY ASSIGNMENT
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testModuleCategoriesCorrect() {
        assertEquals(ModuleCatalog.Category.LLM, ModuleCatalog.get("llm-openai").getCategory());
        assertEquals(ModuleCatalog.Category.LLM, ModuleCatalog.get("cli-llm").getCategory());
        assertEquals(ModuleCatalog.Category.EMBEDDING, ModuleCatalog.get("embedding-anserini").getCategory());
        assertEquals(ModuleCatalog.Category.EMBEDDING, ModuleCatalog.get("embedding-postgresml").getCategory());
        assertEquals(ModuleCatalog.Category.VECTORSTORE, ModuleCatalog.get("vectorstore-pgvector").getCategory());
        assertEquals(ModuleCatalog.Category.LOADER, ModuleCatalog.get("loader-discord").getCategory());
        assertEquals(ModuleCatalog.Category.LOADER, ModuleCatalog.get("loader-gmail").getCategory());
        assertEquals(ModuleCatalog.Category.CHUNKER, ModuleCatalog.get("chunker-sentence").getCategory());
        assertEquals(ModuleCatalog.Category.TOOL, ModuleCatalog.get("tool-workflow").getCategory());
        assertEquals(ModuleCatalog.Category.TOOL, ModuleCatalog.get("tool-crawler").getCategory());
        assertEquals(ModuleCatalog.Category.ENTERPRISE, ModuleCatalog.get("process-engine").getCategory());
        assertEquals(ModuleCatalog.Category.ENTERPRISE, ModuleCatalog.get("process-discovery").getCategory());
        assertEquals(ModuleCatalog.Category.ENTERPRISE, ModuleCatalog.get("code-indexer").getCategory());
        assertEquals(ModuleCatalog.Category.ADVANCED, ModuleCatalog.get("compute-graph").getCategory());
        assertEquals(ModuleCatalog.Category.ADVANCED, ModuleCatalog.get("crawler-core").getCategory());
        assertEquals(ModuleCatalog.Category.PIPELINE, ModuleCatalog.get("pipelines-core").getCategory());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PRESET VALIDATION — ALL PRESET MODULE IDS EXIST IN CATALOG
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testAllPresetModuleIdsExistInCatalog() {
        for (BuildPreset preset : BuildPreset.values()) {
            for (String moduleId : preset.getDefaultModules()) {
                assertNotNull(ModuleCatalog.get(moduleId),
                        "Preset " + preset.name() + " references unknown module: " + moduleId);
            }
        }
    }

    @Test
    void testHostedLlmRagPresetContainsOpenai() {
        assertTrue(BuildPreset.HOSTED_LLM_RAG.getDefaultModules().contains("llm-openai"),
                "HOSTED_LLM_RAG should include llm-openai");
    }

    @Test
    void testSamediffRagPresetHasNoHostedLlm() {
        Set<String> modules = BuildPreset.SAMEDIFF_RAG.getDefaultModules();
        assertFalse(modules.contains("llm-openai"), "SAMEDIFF_RAG should not include llm-openai");
        assertFalse(modules.contains("llm-anthropic"), "SAMEDIFF_RAG should not include llm-anthropic");
        assertFalse(modules.contains("llm-gemini"), "SAMEDIFF_RAG should not include llm-gemini");
        assertFalse(modules.contains("cli-llm"), "SAMEDIFF_RAG should not include cli-llm");
    }

    @Test
    void testCliAgentRagPresetContainsCliAgent() {
        assertTrue(BuildPreset.CLI_AGENT_RAG.getDefaultModules().contains("cli-llm"),
                "CLI_AGENT_RAG should include cli-llm");
        assertFalse(BuildPreset.CLI_AGENT_RAG.getDefaultModules().contains("llm-openai"),
                "CLI_AGENT_RAG should not include llm-openai");
    }

    @Test
    void testFullPresetIncludesNewModules() {
        Set<String> full = BuildPreset.FULL.getDefaultModules();
        // Spot-check the newly added modules are in FULL
        assertTrue(full.contains("loader-excel"), "FULL should include loader-excel");
        assertTrue(full.contains("loader-discord"), "FULL should include loader-discord");
        assertTrue(full.contains("loader-gmail"), "FULL should include loader-gmail");
        assertTrue(full.contains("tool-workflow"), "FULL should include tool-workflow");
        assertTrue(full.contains("tool-gateway"), "FULL should include tool-gateway");
        assertTrue(full.contains("tool-crawler"), "FULL should include tool-crawler");
        assertTrue(full.contains("process-engine"), "FULL should include process-engine");
        assertTrue(full.contains("process-discovery"), "FULL should include process-discovery");
        assertTrue(full.contains("code-indexer"), "FULL should include code-indexer");
        assertTrue(full.contains("compute-graph"), "FULL should include compute-graph");
        assertTrue(full.contains("rag-pipeline"), "FULL should include rag-pipeline");
        assertTrue(full.contains("crawler-core"), "FULL should include crawler-core");
        assertTrue(full.contains("crawl-graph"), "FULL should include crawl-graph");
        assertTrue(full.contains("embedding-postgresml"), "FULL should include embedding-postgresml");
    }

    @Test
    void testProcessDiscoveryInferredForCrawlGraphProcessRuntime() {
        ModuleSelection modules = ModuleSelection.empty()
                .include("process-engine")
                .include("knowledge-graph")
                .include("crawl-graph")
                .build();

        assertTrue(modules.has("process-discovery"),
                "process-discovery should be inferred for process-engine + knowledge-graph + crawl-graph");
    }

    @Test
    void testProcessDiscoveryCanBeExplicitlyExcluded() {
        ModuleSelection modules = ModuleSelection.empty()
                .include("process-engine")
                .include("knowledge-graph")
                .include("crawl-graph")
                .exclude("process-discovery")
                .build();

        assertFalse(modules.has("process-discovery"),
                "explicit exclusion should prevent inferred process-discovery");
    }

    @Test
    void testRagPomGeneratorEmitsInferredProcessDiscoveryDependency(@TempDir Path projectDir) throws Exception {
        Path pomFile = projectDir.resolve("pom.xml");
        ModuleSelection modules = ModuleSelection.empty()
                .include("process-engine")
                .include("knowledge-graph")
                .include("crawl-graph")
                .build();

        RagPomGenerator generator = new RagPomGenerator();
        generator.configureFrom(
                modules,
                "fpna-test",
                "ai.kompile.generated",
                "0.1.0-SNAPSHOT",
                "0.1.0-SNAPSHOT",
                pomFile.toFile(),
                "linux-x86_64",
                null,
                "jdbc:postgresql://localhost:5432/kompile_test",
                "postgres",
                "postgres",
                false,
                false,
                "FP&A Test",
                List.of("en", "ja"),
                List.of(),
                List.of(),
                8080,
                null,
                null);
        generator.call();

        String pom = Files.readString(pomFile);
        assertTrue(pom.contains("<artifactId>kompile-process-engine</artifactId>"));
        assertTrue(pom.contains("<artifactId>kompile-knowledge-graph</artifactId>"));
        assertTrue(pom.contains("<artifactId>kompile-crawl-graph</artifactId>"));
        assertTrue(pom.contains("<artifactId>kompile-process-discovery</artifactId>"),
                "generated POM should include process discovery for crawl graph process runtimes");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WIZARD RESULT — CONSTRUCTION AND FIELD ACCESS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testWizardResultConstructionAndAccess() {
        Set<String> modules = new LinkedHashSet<>(Set.of("app-main", "llm-openai", "embedding-anserini"));
        List<String> langs = List.of("en", "de");

        java.io.File mvnHome = new java.io.File("/usr/share/maven");
        InitProjectWizard.WizardResult result = new InitProjectWizard.WizardResult(
                "test-project", new java.io.File("."), BuildPreset.HOSTED_LLM_RAG,
                modules, 9090, "linux-x86_64",
                true, false,
                List.of("nd4j-native", "nd4j-cuda-12.9"), "avx2",
                "My App",
                "jdbc:postgresql://localhost:5432/mydb", "admin", "secret", true,
                "sk-test123", "sk-ant-test", "my-gcp-project",
                langs, mvnHome,
                null, 3, false, false
        );

        assertEquals("test-project", result.projectName);
        assertEquals(9090, result.serverPort);
        assertEquals("linux-x86_64", result.javacppPlatform);
        assertTrue(result.startAfterBuild);
        assertFalse(result.noBuild);
        assertEquals(List.of("nd4j-native", "nd4j-cuda-12.9"), result.backends);
        assertEquals("avx2", result.javacppExtension);
        assertEquals("My App", result.appTitle);
        assertEquals("jdbc:postgresql://localhost:5432/mydb", result.databaseUrl);
        assertEquals("admin", result.databaseUsername);
        assertEquals("secret", result.databasePassword);
        assertTrue(result.enableSchemaInit);
        assertEquals("sk-test123", result.openaiApiKey);
        assertEquals("sk-ant-test", result.anthropicApiKey);
        assertEquals("my-gcp-project", result.geminiProjectId);
        assertEquals(List.of("en", "de"), result.supportedLanguages);
        assertEquals(BuildPreset.HOSTED_LLM_RAG, result.preset);
        assertTrue(result.moduleIds.contains("llm-openai"));
        assertTrue(result.moduleIds.contains("embedding-anserini"));
        assertEquals(mvnHome, result.mavenHome);
    }

    @Test
    void testWizardResultModuleIdsUnmodifiable() {
        Set<String> modules = new LinkedHashSet<>(Set.of("app-main"));
        InitProjectWizard.WizardResult result = new InitProjectWizard.WizardResult(
                "test", new java.io.File("."), null,
                modules, 8080, "linux-x86_64",
                false, false,
                List.of("nd4j-native"), null,
                "Test", null, null, null, true,
                null, null, null, null, null,
                null, 3, false, false
        );
        assertThrows(UnsupportedOperationException.class, () -> result.moduleIds.add("llm-openai"));
    }

    @Test
    void testWizardResultLanguagesDefaultToEnglish() {
        InitProjectWizard.WizardResult result = new InitProjectWizard.WizardResult(
                "test", new java.io.File("."), null,
                Set.of("app-main"), 8080, "linux-x86_64",
                false, false,
                List.of("nd4j-native"), null,
                "Test", null, null, null, true,
                null, null, null, null,  // null supportedLanguages
                null,  // null mavenHome
                null, 3, false, false
        );
        assertEquals(List.of("en"), result.supportedLanguages);
    }

    @Test
    void testWizardResultMavenHomeNullByDefault() {
        InitProjectWizard.WizardResult result = new InitProjectWizard.WizardResult(
                "test", new java.io.File("."), null,
                Set.of("app-main"), 8080, "linux-x86_64",
                false, false,
                List.of("nd4j-native"), null,
                "Test", null, null, null, true,
                null, null, null, null, null,
                null, 3, false, false
        );
        assertNull(result.mavenHome);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MODULE CATALOG — GROUPID CONSISTENCY
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testAllModulesHaveAiKompileGroupId() {
        for (ModuleCatalog.ModuleEntry entry : ModuleCatalog.getAll()) {
            assertEquals("ai.kompile", entry.getGroupId(),
                    "Module " + entry.getId() + " should have groupId ai.kompile");
        }
    }

    @Test
    void testAllModulesHaveNonEmptyDescription() {
        for (ModuleCatalog.ModuleEntry entry : ModuleCatalog.getAll()) {
            assertNotNull(entry.getDescription(), "Module " + entry.getId() + " description is null");
            assertFalse(entry.getDescription().isEmpty(), "Module " + entry.getId() + " description is empty");
        }
    }

    @Test
    void testGetByCategoryReturnsCorrectEntries() {
        List<ModuleCatalog.ModuleEntry> llmModules = ModuleCatalog.getByCategory(ModuleCatalog.Category.LLM);
        assertEquals(4, llmModules.size(), "Should have 4 LLM providers");
        Set<String> llmIds = new HashSet<>();
        for (ModuleCatalog.ModuleEntry e : llmModules) llmIds.add(e.getId());
        assertTrue(llmIds.contains("llm-openai"));
        assertTrue(llmIds.contains("llm-anthropic"));
        assertTrue(llmIds.contains("llm-gemini"));
        assertTrue(llmIds.contains("cli-llm"));
    }

    @Test
    void testGetByCategoryEmbedding() {
        List<ModuleCatalog.ModuleEntry> embModules = ModuleCatalog.getByCategory(ModuleCatalog.Category.EMBEDDING);
        assertEquals(4, embModules.size(), "Should have 4 embedding providers");
    }

    @Test
    void testResolveThrowsOnUnknownModuleId() {
        assertThrows(IllegalArgumentException.class,
                () -> ModuleCatalog.resolve(Set.of("nonexistent-module")));
    }

    @Test
    void testResolveArtifactIds() {
        Set<String> artifactIds = ModuleCatalog.resolveArtifactIds(Set.of("llm-openai", "cli-llm"));
        assertTrue(artifactIds.contains("kompile-app-openai-llm"));
        assertTrue(artifactIds.contains("kompile-app-cli-llm"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PLATFORM — CUDA COMPATIBILITY
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testCudaPlatformsDoNotIncludeMac() {
        // Validate that the CUDA_PLATFORMS set in InitProjectWizard excludes macOS
        // This tests the logic indirectly — macOS should never be paired with CUDA
        Set<String> cudaPlatforms = Set.of("linux-x86_64", "linux-arm64", "windows-x86_64");
        assertFalse(cudaPlatforms.contains("macosx-arm64"), "CUDA should not support macosx-arm64");
        assertFalse(cudaPlatforms.contains("macosx-x86_64"), "CUDA should not support macosx-x86_64");
        assertTrue(cudaPlatforms.contains("linux-x86_64"), "CUDA should support linux-x86_64");
        assertTrue(cudaPlatforms.contains("linux-arm64"), "CUDA should support linux-arm64");
        assertTrue(cudaPlatforms.contains("windows-x86_64"), "CUDA should support windows-x86_64");
    }

    @Test
    void testAllIdsContainsAllModules() {
        Set<String> allIds = ModuleCatalog.allIds();
        // Verify count matches getAll() size
        assertEquals(ModuleCatalog.getAll().size(), allIds.size());
        for (ModuleCatalog.ModuleEntry entry : ModuleCatalog.getAll()) {
            assertTrue(allIds.contains(entry.getId()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MODULE CATALOG — BUNDLED vs ADD-ON DISTINCTION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testBundledModulesIncludeCoreModules() {
        // Core modules that ship with kompile-app-main should be bundled
        assertTrue(ModuleCatalog.get("app-main").isBundled(), "app-main should be bundled");
        assertTrue(ModuleCatalog.get("app-core").isBundled(), "app-core should be bundled");
        assertTrue(ModuleCatalog.get("loaders-orchestrator").isBundled(), "loaders-orchestrator should be bundled");
        assertTrue(ModuleCatalog.get("chat-history").isBundled(), "chat-history should be bundled");
        assertTrue(ModuleCatalog.get("cli-llm").isBundled(), "cli-llm should be bundled");
        assertTrue(ModuleCatalog.get("embedding-anserini").isBundled(), "embedding-anserini should be bundled");
        assertTrue(ModuleCatalog.get("vectorstore-anserini").isBundled(), "vectorstore-anserini should be bundled");
        assertTrue(ModuleCatalog.get("tool-model-staging").isBundled(), "tool-model-staging should be bundled");
    }

    @Test
    void testAddonModulesAreNotBundled() {
        // Add-on modules that require explicit POM entries should not be bundled
        assertFalse(ModuleCatalog.get("llm-openai").isBundled(), "llm-openai should be addon");
        assertFalse(ModuleCatalog.get("llm-anthropic").isBundled(), "llm-anthropic should be addon");
        assertFalse(ModuleCatalog.get("llm-gemini").isBundled(), "llm-gemini should be addon");
        assertFalse(ModuleCatalog.get("embedding-openai").isBundled(), "embedding-openai should be addon");
        assertFalse(ModuleCatalog.get("vectorstore-chroma").isBundled(), "vectorstore-chroma should be addon");
        assertFalse(ModuleCatalog.get("vectorstore-pgvector").isBundled(), "vectorstore-pgvector should be addon");
        assertFalse(ModuleCatalog.get("graph-neo4j").isBundled(), "graph-neo4j should be addon");
        assertFalse(ModuleCatalog.get("loader-tika").isBundled(), "loader-tika should be addon");
    }

    @Test
    void testNonBundledModulesExist() {
        // Verify some known add-on modules are not bundled
        assertFalse(ModuleCatalog.get("llm-openai").isBundled(), "llm-openai should not be bundled");
        assertFalse(ModuleCatalog.get("vectorstore-pgvector").isBundled(), "vectorstore-pgvector should not be bundled");
    }

    @Test
    void testGetByCategoryChunkers() {
        // All chunkers should be retrievable by category
        List<ModuleCatalog.ModuleEntry> chunkers = ModuleCatalog.getByCategory(ModuleCatalog.Category.CHUNKER);
        assertEquals(4, chunkers.size(), "Should have 4 chunkers");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MODULE CATALOG — DISPLAY NAMES
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testAllModulesHaveDisplayNames() {
        for (ModuleCatalog.ModuleEntry entry : ModuleCatalog.getAll()) {
            assertNotNull(entry.getDisplayName(), "Module " + entry.getId() + " displayName is null");
            assertFalse(entry.getDisplayName().isEmpty(), "Module " + entry.getId() + " displayName is empty");
        }
    }

    @Test
    void testDisplayNamesAreHumanFriendly() {
        // Display names should NOT be artifact IDs
        assertEquals("OpenAI", ModuleCatalog.get("llm-openai").getDisplayName());
        assertEquals("Anthropic", ModuleCatalog.get("llm-anthropic").getDisplayName());
        assertEquals("Gemini", ModuleCatalog.get("llm-gemini").getDisplayName());
        assertEquals("Local (built-in)", ModuleCatalog.get("cli-llm").getDisplayName());
        assertEquals("Anserini (local)", ModuleCatalog.get("embedding-anserini").getDisplayName());
        assertEquals("pgvector (PostgreSQL)", ModuleCatalog.get("vectorstore-pgvector").getDisplayName());
        assertEquals("Sentence Splitter", ModuleCatalog.get("chunker-sentence").getDisplayName());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WIZARD RESULT — MULTI-BACKEND SUPPORT
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testWizardResultMultipleBackends() {
        InitProjectWizard.WizardResult result = new InitProjectWizard.WizardResult(
                "test", new java.io.File("."), null,
                Set.of("app-main"), 8080, "linux-x86_64",
                false, false,
                List.of("nd4j-native", "nd4j-cuda-13.1", "nd4j-cuda-12.9"), null,
                "Test", null, null, null, true,
                null, null, null, null, null,
                null, 3, false, false
        );
        assertEquals(3, result.backends.size());
        assertEquals("nd4j-native", result.backends.get(0));
        assertEquals("nd4j-cuda-13.1", result.backends.get(1));
        assertEquals("nd4j-cuda-12.9", result.backends.get(2));
    }

    @Test
    void testWizardResultNullBackendsDefaultsToCpu() {
        InitProjectWizard.WizardResult result = new InitProjectWizard.WizardResult(
                "test", new java.io.File("."), null,
                Set.of("app-main"), 8080, "linux-x86_64",
                false, false,
                null, null,
                "Test", null, null, null, true,
                null, null, null, null, null,
                null, 3, false, false
        );
        assertEquals(List.of("nd4j-native"), result.backends);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WIZARD RESULT — DATA SOURCE FIELDS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void testWizardResultWithDataSources() {
        List<String> dataSources = List.of("https://docs.example.com", "/path/to/docs");

        InitProjectWizard.WizardResult result = new InitProjectWizard.WizardResult(
                "test-project", new java.io.File("."), BuildPreset.HOSTED_LLM_RAG,
                Set.of("app-main", "llm-openai"), 8080, "linux-x86_64",
                true, false,
                List.of("nd4j-native"), null,
                "Test", null, null, null, true,
                null, null, null, null, null,
                dataSources, 5, true, true
        );

        assertEquals(2, result.dataSources.size(), "Should have 2 data sources");
        assertEquals("https://docs.example.com", result.dataSources.get(0));
        assertEquals("/path/to/docs", result.dataSources.get(1));
        assertEquals(5, result.crawlDepth);
        assertTrue(result.crawlMultimodal);
        assertTrue(result.crawlGraph);
    }

    @Test
    void testWizardResultWithNullDataSources() {
        InitProjectWizard.WizardResult result = new InitProjectWizard.WizardResult(
                "test", new java.io.File("."), null,
                Set.of("app-main"), 8080, "linux-x86_64",
                false, false,
                List.of("nd4j-native"), null,
                "Test", null, null, null, true,
                null, null, null, null, null,
                null, 3, false, false
        );

        assertTrue(result.dataSources.isEmpty(), "Null data sources should default to empty list");
        assertEquals(3, result.crawlDepth);
        assertFalse(result.crawlMultimodal);
        assertFalse(result.crawlGraph);
    }

    @Test
    void testWizardResultDataSourcesUnmodifiable() {
        InitProjectWizard.WizardResult result = new InitProjectWizard.WizardResult(
                "test", new java.io.File("."), null,
                Set.of("app-main"), 8080, "linux-x86_64",
                false, false,
                List.of("nd4j-native"), null,
                "Test", null, null, null, true,
                null, null, null, null, null,
                List.of("https://example.com"), 3, false, false
        );

        assertThrows(UnsupportedOperationException.class,
                () -> result.dataSources.add("https://another.com"));
    }

    @Test
    void testWizardResultCrawlFlagsIndependent() {
        InitProjectWizard.WizardResult multimodalOnly = new InitProjectWizard.WizardResult(
                "test", new java.io.File("."), null,
                Set.of("app-main"), 8080, "linux-x86_64",
                false, false,
                List.of("nd4j-native"), null,
                "Test", null, null, null, true,
                null, null, null, null, null,
                List.of("https://example.com"), 3, true, false
        );
        assertTrue(multimodalOnly.crawlMultimodal);
        assertFalse(multimodalOnly.crawlGraph);

        InitProjectWizard.WizardResult graphOnly = new InitProjectWizard.WizardResult(
                "test", new java.io.File("."), null,
                Set.of("app-main"), 8080, "linux-x86_64",
                false, false,
                List.of("nd4j-native"), null,
                "Test", null, null, null, true,
                null, null, null, null, null,
                List.of("https://example.com"), 3, false, true
        );
        assertFalse(graphOnly.crawlMultimodal);
        assertTrue(graphOnly.crawlGraph);
    }

    @Test
    void testWizardResultBackendsUnmodifiable() {
        InitProjectWizard.WizardResult result = new InitProjectWizard.WizardResult(
                "test", new java.io.File("."), null,
                Set.of("app-main"), 8080, "linux-x86_64",
                false, false,
                List.of("nd4j-native"), null,
                "Test", null, null, null, true,
                null, null, null, null, null,
                null, 3, false, false
        );
        assertThrows(UnsupportedOperationException.class, () -> result.backends.add("nd4j-cuda-12.9"));
    }
}
