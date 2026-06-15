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

import ai.kompile.cli.common.config.HardwareAutoConfigurator;
import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.build.config.*;
import ai.kompile.cli.main.config.AppConfigWizard;
import ai.kompile.cli.main.project.ProjectAutoDetection;
import ai.kompile.cli.main.project.ProjectCommand;
import ai.kompile.cli.main.util.EnvironmentUtils;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectPipeline;
import ai.kompile.project.KompileProjectScript;
import ai.kompile.project.KompileProjectStorageBackend;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.KompileProjectWorkflow;
import ai.kompile.project.KompileProjectWorkflowStep;
import org.apache.commons.io.FileUtils;
import org.apache.maven.shared.invoker.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Initializes a new self-contained kompile project directory, generates all
 * configuration files (POM, application.properties, schema, data scaffold),
 * builds the project, and optionally starts it.
 *
 * When invoked without --name (or with --wizard), launches an interactive wizard
 * that walks through project name, preset, module selection, port, API keys,
 * and build options.
 *
 * Usage:
 *   kompile init-project                          # launches wizard
 *   kompile init-project --wizard                 # launches wizard
 *   kompile init-project --name=my-rag-app
 *   kompile init-project --name=my-rag-app --preset=full --start
 *   kompile init-project --name=my-rag-app --llm=llm-openai,llm-anthropic --start
 */
@Command(name = "init-project", mixinStandardHelpOptions = true,
        description = "Initialize a new self-contained kompile project.\n\n" +
                "Generates a project directory with POM, application.properties, data scaffold,\n" +
                "builds the project JAR, and optionally starts it.\n\n" +
                "Run without arguments to launch the interactive wizard.\n\n" +
                "Examples:\n" +
                "  kompile init-project                              # interactive wizard\n" +
                "  kompile init-project --agent                      # agent-guided setup (auto-detect)\n" +
                "  kompile init-project --agent claude               # use Claude for guided setup\n" +
                "  kompile init-project --name=myapp --preset=full --start\n" +
                "  kompile init-project --name=myapp --llm=llm-openai,llm-anthropic --start\n" +
                "  kompile init-project --name=myapp --outputDir=/opt/projects --no-build\n")
public class InitProjectCommand implements Callable<Integer> {

    // --- Wizard mode ---
    @Option(names = {"--wizard", "-w"}, description = "Launch interactive project setup wizard")
    private boolean wizard;

    @Option(names = {"--agent", "-a"},
            description = "Use an AI agent (claude, codex, gemini) to guide project setup. " +
                    "Specify agent name or 'auto' to detect available agents.")
    private String agentName;

    // --- Project identity ---
    @Option(names = {"--name", "-n"},
            description = "Project name (used as directory name and Maven artifactId)")
    private String projectName;

    @Option(names = {"--outputDir", "-o"},
            description = "Parent directory where the project will be created. Default: current directory",
            defaultValue = ".")
    private File outputDir;

    @Option(names = {"--infer"}, defaultValue = "true", negatable = true,
            description = "Auto-detect code, data, and model assets from the source tree. Use --no-infer to disable.")
    private boolean inferProject = true;

    @Option(names = {"--infer-from"},
            description = "Directory to inspect for auto-detected code, data, and model assets. Defaults to --outputDir.")
    private File inferFrom;

    // --- Preset and module selection ---
    @Option(names = {"--preset", "-p"},
            description = "Application preset: hosted-llm-rag, samediff-rag, pipeline, cli-agent-rag, full, minimal, lite. Default: hosted-llm-rag",
            defaultValue = "hosted-llm-rag")
    private String presetName;

    @Option(names = {"--llm"}, description = "Override LLM modules (comma-separated)", split = ",")
    private List<String> llmOverride;

    @Option(names = {"--embedding"}, description = "Override embedding modules (comma-separated)", split = ",")
    private List<String> embeddingOverride;

    @Option(names = {"--vectorstore"}, description = "Override vectorstore modules (comma-separated)", split = ",")
    private List<String> vectorstoreOverride;

    @Option(names = {"--loaders"}, description = "Override loader modules (comma-separated)", split = ",")
    private List<String> loadersOverride;

    @Option(names = {"--chunkers"}, description = "Override chunker modules (comma-separated)", split = ",")
    private List<String> chunkersOverride;

    @Option(names = {"--tools"}, description = "Override tool modules (comma-separated)", split = ",")
    private List<String> toolsOverride;

    @Option(names = {"--include"}, description = "Include additional module IDs (comma-separated)", split = ",")
    private List<String> includeModules;

    @Option(names = {"--exclude"}, description = "Exclude module IDs (comma-separated)", split = ",")
    private List<String> excludeModules;

    // --- Build & run options ---
    @Option(names = {"--start", "-s"}, description = "Start the application after building", defaultValue = "false")
    private boolean startAfterBuild;

    @Option(names = {"--no-build"}, description = "Generate project files only, skip Maven build", defaultValue = "false")
    private boolean noBuild;

    @Option(names = {"--deploy"}, description = "Deploy the built project to ~/.kompile/instances/ as a self-contained package",
            defaultValue = "false")
    private boolean deploy;

    @Option(names = {"--container"}, description = "Build a container image (requires --deploy or --no-build=false)",
            defaultValue = "false")
    private boolean buildContainer;

    @Option(names = {"--containerImage"}, description = "Full container image name for deployment (e.g., gcr.io/project/myapp)")
    private String containerImageName;

    @Option(names = {"--containerRegistry"}, description = "Container registry prefix (e.g., docker.io/myuser, gcr.io/my-project)")
    private String containerRegistry;

    @Option(names = {"--port"}, description = "Server port for the application. Default: 8080", defaultValue = "8080")
    private int serverPort;

    @Option(names = {"--skipTests"}, description = "Skip Maven tests", defaultValue = "true", negatable = true)
    private boolean skipTests;

    // --- Platform / build ---
    @Option(names = {"--javacppPlatform"}, description = "JavaCPP platform (e.g., linux-x86_64)", defaultValue = "linux-x86_64")
    private String javacppPlatform;

    @Option(names = {"--javacppExtension"}, description = "JavaCPP extension (e.g., avx2, cuda)")
    private String javacppExtension;

    @Option(names = {"--backend"}, description = "ND4J backend artifactId", defaultValue = "nd4j-cuda-12.9")
    private String backend;

    @Option(names = {"--mavenHome"}, description = "Path to Maven installation")
    private File mavenHome;

    // --- App metadata ---
    @Option(names = {"--appTitle"}, description = "Application title for UI banner", defaultValue = "Kompile RAG Console")
    private String appTitle;

    @Option(names = {"--groupId"}, description = "Maven groupId", defaultValue = "ai.kompile.rag.instance")
    private String instanceGroupId;

    @Option(names = {"--version"}, description = "Maven version", defaultValue = "0.1.0-SNAPSHOT")
    private String instanceVersion;

    @Option(names = {"--kompileVersion"}, description = "Kompile modules version", defaultValue = "0.1.0-SNAPSHOT")
    private String ragMcpVersion;

    // --- Unified project repository metadata ---
    @Option(names = {"--project-backend"},
            description = "Unified project repository backend: local, git, git-xet. Default: local",
            defaultValue = "local")
    private String projectBackend;

    @Option(names = {"--project-remote"}, description = "Git remote URL for the unified project repository")
    private String projectRemoteUrl;

    @Option(names = {"--project-branch"}, description = "Git branch for the unified project repository", defaultValue = "main")
    private String projectBranch;

    @Option(names = {"--project-tag"}, description = "Unified project tags (comma-separated)", split = ",")
    private List<String> projectTags = new ArrayList<>();

    @Option(names = {"--project-git"}, description = "Initialize Git for the generated unified project repository")
    private boolean initializeProjectGit;

    @Option(names = {"--project-install-git-xet"},
            description = "Run 'git xet install' when --project-backend=git-xet")
    private boolean installProjectGitXet;

    // --- Project crawl initialization ---
    @Option(names = {"--crawl-name"}, description = "Initial crawl profile name.")
    private String crawlName;

    @Option(names = {"--crawl-schema-preset"}, description = "Initial crawl graph schema preset ID.")
    private String crawlSchemaPreset;

    @Option(names = {"--crawl-graph-schema-mode"}, description = "Initial crawl graph schema mode: NONE, LENIENT, STRICT.")
    private String crawlGraphSchemaMode;

    @Option(names = {"--crawl-watch"}, description = "Watch the initial crawl profile when scripts/init-crawl.sh is run.")
    private boolean crawlWatch;

    @Option(names = {"--crawl-max-depth"}, description = "Initial crawl max depth.", defaultValue = "3")
    private int crawlMaxDepth;

    @Option(names = {"--crawl-max-docs"}, description = "Initial crawl max documents, 0 = unlimited.", defaultValue = "0")
    private int crawlMaxDocuments;

    @Option(names = {"--crawl-include"}, split = ",", description = "Initial crawl include patterns.")
    private List<String> crawlIncludePatterns = new ArrayList<>();

    @Option(names = {"--crawl-exclude"}, split = ",", description = "Initial crawl exclude patterns.")
    private List<String> crawlExcludePatterns = new ArrayList<>();

    @Option(names = {"--crawl-content-types"}, split = ",", description = "Initial crawl allowed MIME types.")
    private List<String> crawlContentTypes = new ArrayList<>();

    @Option(names = {"--crawl-collection"}, description = "Initial crawl vector collection.")
    private String crawlCollection;

    @Option(names = {"--crawl-chunker"}, description = "Initial crawl chunker.")
    private String crawlChunker;

    @Option(names = {"--crawl-loader"}, description = "Initial crawl loader.")
    private String crawlLoader;

    @Option(names = {"--crawl-type"}, description = "Force initial crawl source type: web, directory, file, excel.")
    private String crawlSourceType;

    @Option(names = {"--crawl-follow-links"}, description = "Follow links in initial directory HTML crawls.")
    private boolean crawlFollowLinks;

    @Option(names = {"--crawl-include-hidden"}, description = "Include hidden files in initial directory crawls.")
    private boolean crawlIncludeHidden;

    @Option(names = {"--crawl-vlm-model"}, description = "VLM model ID for the initial crawl profile.")
    private String crawlVlmModel;

    @Option(names = {"--pdf-vlm-source", "--pdf-vlm-dir"}, split = ",",
            description = "Directory paths or PDF files to seed as a managed multimodal/VLM PDF workflow.")
    private List<String> pdfVlmSources = new ArrayList<>();

    @Option(names = {"--pdf-vlm-model"},
            description = "VLM model ID for the generated PDF/VLM workflow. Defaults to --crawl-vlm-model when set.")
    private String pdfVlmModel;

    @Option(names = {"--pdf-vlm-watch"}, description = "Watch the generated PDF/VLM crawl workflow.")
    private boolean pdfVlmWatch;

    // --- Database options ---
    @Option(names = {"--databaseUrl"}, description = "PostgreSQL database URL", defaultValue = "jdbc:postgresql://localhost:5432/kompile_db")
    private String databaseUrl;

    @Option(names = {"--databaseUsername"}, description = "Database username", defaultValue = "postgres")
    private String databaseUsername;

    @Option(names = {"--databasePassword"}, description = "Database password", defaultValue = "postgres")
    private String databasePassword;

    @Option(names = {"--enableSchemaInit"}, description = "Auto-initialize SQL schema", defaultValue = "true", negatable = true)
    private boolean enableSchemaInit;

    // --- Model / language options ---
    @Option(names = {"--supportedLanguages"}, description = "Language codes for OpenNLP models", split = ",", defaultValue = "en")
    private List<String> supportedLanguages;

    @Option(names = {"--anserini-indexes"}, description = "Anserini prebuilt index IDs", split = ",", arity = "0..*")
    private List<String> anseriniIndexIds = new ArrayList<>();

    @Option(names = {"--anserini-encoders"}, description = "Anserini encoder model IDs", split = ",", arity = "0..*")
    private List<String> anseriniEncoderModelIds = new ArrayList<>();

    // --- Data sources to crawl on first run ---
    @Option(names = {"--crawl-source", "--data-source"}, split = ",",
            description = "URLs or directory paths to crawl and index on first startup (comma-separated)")
    private List<String> dataSources;

    @Option(names = {"--crawl-depth"}, defaultValue = "3",
            description = "Crawl depth for initial data sources (default: ${DEFAULT-VALUE})")
    private int crawlDepth;

    @Option(names = {"--crawl-multimodal"}, defaultValue = "false",
            description = "Enable multimodal (VLM) processing for crawled PDFs/images")
    private boolean crawlMultimodal;

    @Option(names = {"--crawl-graph"}, defaultValue = "false",
            description = "Build knowledge graph from crawled content")
    private boolean crawlGraph;

    // Internal: set by wizard to provide an explicit module set (bypasses preset resolution)
    private Set<String> wizardModuleIds;

    // Internal: multi-backend list from wizard (overrides --backend when non-null)
    private List<String> wizardBackends;

    // Internal: API keys collected by wizard — patched into application.properties after generation
    private String wizardOpenaiApiKey;
    private String wizardAnthropicApiKey;
    private String wizardGeminiProjectId;

    // Internal: source tree inference results applied before module and manifest generation
    private ProjectAutoDetection.DetectedSignals inferredSignals;
    private ProjectAutoDetection.ProjectScenario inferredScenario;
    private List<KompileProjectModel> inferredModels = new ArrayList<>();
    private KompileCodingProject inferredCodingProject;

    @Override
    public Integer call() throws Exception {
        // Agent-guided setup: use an AI agent to recommend configuration
        if (agentName != null && !agentName.isBlank()) {
            InitProjectWizard.WizardResult result = AgentGuidedInit.run(agentName);
            if (result == null) return 1;
            applyWizardResult(result);
        }
        // Launch wizard if explicitly requested or if --name was not provided
        else if (wizard || projectName == null) {
            InitProjectWizard.WizardResult result = InitProjectWizard.run();
            if (result == null) {
                if (!wizard && projectName == null) {
                    System.err.println("Error: --name is required. Use --wizard for interactive setup.");
                }
                return 1;
            }
            applyWizardResult(result);
        }

        // 1. Resolve project directory
        File projectDir = new File(outputDir, projectName);
        if (projectDir.exists()) {
            // If the wizard already confirmed removal, delete it now
            if (wizard || wizardModuleIds != null) {
                FileUtils.deleteDirectory(projectDir);
            } else {
                System.err.println("Directory already exists: " + projectDir.getAbsolutePath());
                System.err.println("Remove it first or choose a different name.");
                return 1;
            }
        }

        System.out.println("Initializing new kompile project: " + projectName);
        System.out.println("  Location: " + projectDir.getAbsolutePath());
        applyAutoDetection(projectDir);

        // 2. Resolve modules — wizard provides an explicit set, otherwise resolve from preset
        ModuleSelection modules;
        BuildPreset.BackendAffinity backendAffinity;
        if (wizardModuleIds != null) {
            ModuleSelection.Builder selBuilder = ModuleSelection.empty();
            selBuilder.include(wizardModuleIds);
            modules = selBuilder.build();
            // Wizard mode: derive affinity from the backend string (cuda → CUDA_PREFERRED, else CPU_ONLY)
            backendAffinity = (backend != null && backend.contains("cuda"))
                    ? BuildPreset.BackendAffinity.CUDA_PREFERRED
                    : BuildPreset.BackendAffinity.CPU_ONLY;
        } else {
            BuildPreset preset = resolvePreset(presetName);
            modules = buildModuleSelection(preset);
            backendAffinity = preset.getBackendAffinity();
        }
        modules = augmentModulesForDetectedProject(modules);

        // 3. Create project directory
        if (!projectDir.mkdirs()) {
            System.err.println("Failed to create project directory: " + projectDir.getAbsolutePath());
            return 1;
        }

        // 4. Delegate to RagPomGenerator — the battle-tested generator that handles
        //    POM, application.properties, SQL schemas, provider configs, and native profiles.
        File pomFile = new File(projectDir, "pom.xml");
        RagPomGenerator generator = new RagPomGenerator();
        generator.configureFrom(
                modules,
                projectName, instanceGroupId, instanceVersion, ragMcpVersion,
                pomFile,  // outputFile = pom.xml path
                javacppPlatform, javacppExtension,
                databaseUrl, databaseUsername, databasePassword,
                enableSchemaInit,
                false,  // buildNative — init-project builds JARs, not native
                appTitle, supportedLanguages,
                anseriniIndexIds, anseriniEncoderModelIds,
                serverPort, backend, backendAffinity);
        generator.call();

        // 5. Patch API keys if collected by wizard
        patchApiKeys(projectDir);

        // 5b. Bootstrap JSON config files with sensible defaults.
        //     Writes to both ~/.kompile/config/ (global fallback) and
        //     <projectDir>/config/ (per-project, used when kompile.data.dir=<projectDir>).
        bootstrapKompileConfigs(modules, projectDir);

        // 6. Scaffold data directories and seed files
        scaffoldDataDirectories(projectDir);

        // 6a. Seed the unified project repository manifest
        seedProjectManifest(projectDir, modules);

        // 6b. Generate .gitignore
        generateGitignore(projectDir);

        // 6c. Install staging server executable into project
        installStagingServer(projectDir);

        // 7. Generate scripts, README, and AGENTS.md
        generateScripts(projectDir);
        generateReadme(projectDir, modules);
        generateAgentsMd(projectDir, modules);

        // 8. Print summary
        printSummary(projectDir, modules);

        // 12. Build unless --no-build
        if (noBuild) {
            System.out.println("\n  Project files generated (build skipped).");
            if (deploy) {
                System.out.println("  Note: --deploy requires a built JAR. Build first, then run:");
                System.out.println("    cd " + projectDir.getAbsolutePath());
                System.out.println("    mvn clean package -DskipTests");
                System.out.println("    kompile deploy --projectDir=" + projectDir.getAbsolutePath());
            } else {
                printRunInstructions(projectDir);
            }
            return 0;
        }

        int buildResult = invokeMavenBuild(projectDir, pomFile);
        if (buildResult != 0) {
            return buildResult;
        }

        // 12b. Deploy to ~/.kompile/instances/ if requested
        if (deploy) {
            int deployResult = DeployCommand.deployProject(
                    projectDir, projectName, serverPort,
                    buildContainer, containerRegistry, containerImageName);
            if (deployResult != 0) {
                System.err.println("Deployment failed, but project build succeeded.");
                System.err.println("You can retry with: kompile deploy --projectDir=" + projectDir.getAbsolutePath());
                return deployResult;
            }
        }

        // 13. Start if requested
        if (startAfterBuild) {
            // Start staging server first (if installed in project)
            startStagingServerIfPresent(projectDir);

            boolean hasDataSources = dataSources != null && !dataSources.isEmpty();
            if (hasDataSources) {
                return startApplicationWithCrawl(projectDir, pomFile);
            }
            return startApplication(projectDir, pomFile);
        }

        // If we have managed crawl workflows but aren't starting, print instructions
        if (hasInitialCrawl() || hasPdfVlmWorkflow()) {
            printCrawlInstructions();
        }

        printRunInstructions(projectDir);
        return 0;
    }

    /**
     * Bootstrap JSON config files with sensible defaults based on the selected modules.
     * Writes to both {@code ~/.kompile/config/} (global fallback) and
     * {@code <projectDir>/config/} (per-project configs used when the app is started
     * with {@code --kompile.data.dir=<projectDir>}).
     *
     * Global configs are only written if they don't already exist (preserving user
     * customizations). Per-project configs are always written fresh since the project
     * directory was just created.
     */
    private void bootstrapKompileConfigs(ModuleSelection modules, File projectDir) {
        String dataDir = System.getProperty("user.home") + "/.kompile";
        Path projectConfigDir = projectDir.toPath().resolve("config");

        // Detect hardware and determine whether local embeddings are used
        boolean hasLocalEmbedding = modules.has("embedding-anserini") || modules.has("app-anserini");
        HardwareAutoConfigurator.AutoConfigResult autoConfig =
                HardwareAutoConfigurator.autoConfigure(hasLocalEmbedding);

        System.out.println("  Hardware detected: " + autoConfig.hardware.get("totalRamGb") + " GB RAM, "
                + autoConfig.hardware.get("cpuCount") + " CPUs, tier=" + autoConfig.hardware.get("tier")
                + (Boolean.TRUE.equals(autoConfig.hardware.get("gpuAvailable")) ? ", GPU available" : ""));

        // --- app-index-config.json: vector store type, paths, subprocess, batch sizes ---
        Map<String, Object> appIndexConfig = new LinkedHashMap<>();
        appIndexConfig.put("appTitle", appTitle);
        if (modules.has("vectorstore-anserini") || modules.has("app-anserini")) {
            appIndexConfig.put("vectorStoreType", "ANSERINI");
        } else if (modules.has("vectorstore-pgvector")) {
            appIndexConfig.put("vectorStoreType", "PGVECTOR");
        } else if (modules.has("vectorstore-chroma")) {
            appIndexConfig.put("vectorStoreType", "CHROMA");
        } else {
            appIndexConfig.put("vectorStoreType", "ANSERINI");
        }
        // Per-project paths use relative paths resolved from kompile.data.dir at runtime
        appIndexConfig.put("vectorStorePath", "anserini/indexes/vector_index");
        appIndexConfig.put("keywordIndexPath", "anserini/indexes/default_index");
        appIndexConfig.put("subprocessEnabled", true);
        appIndexConfig.put("subprocessHeapSize", autoConfig.subprocessConfig.get("heapSize"));
        appIndexConfig.put("indexBatchSize", 100);
        appIndexConfig.put("adaptiveBatchSize", true);
        appIndexConfig.put("embeddingTargetBatchSize",
                autoConfig.pipelineConfig.get("defaultBatchSize"));
        if (modules.has("vectorstore-pgvector")) {
            appIndexConfig.put("pgvectorUrl", databaseUrl);
            appIndexConfig.put("pgvectorUsername", databaseUsername);
            appIndexConfig.put("pgvectorPassword", databasePassword);
            appIndexConfig.put("pgvectorTableName", "vector_store");
        }
        if (modules.has("vectorstore-chroma")) {
            appIndexConfig.put("chromaHost", "localhost");
            appIndexConfig.put("chromaPort", 8000);
            appIndexConfig.put("chromaCollectionName", "kompile");
        }
        saveGlobalAndProject(AppConfigWizard.APP_INDEX_CONFIG, appIndexConfig, dataDir, projectConfigDir);

        // --- cli-llm-config.json ---
        boolean hasLlm = modules.has("llm-openai") || modules.has("llm-anthropic")
                || modules.has("llm-gemini") || modules.has("llm-cli-agent") || modules.has("pipelines-llm");
        Map<String, Object> cliLlmConfig = new LinkedHashMap<>();
        cliLlmConfig.put("enabled", hasLlm);
        cliLlmConfig.put("command", detectAvailableCliAgent());
        cliLlmConfig.put("skipPermissions", true);
        cliLlmConfig.put("timeoutSeconds", 120);
        saveGlobalAndProject("cli-llm-config.json", cliLlmConfig, dataDir, projectConfigDir);

        // --- llm-provider-config.json ---
        Map<String, Object> llmConfig = new LinkedHashMap<>();
        if (modules.has("llm-cli-agent")) {
            llmConfig.put("provider", "cli-agent");
            llmConfig.put("command", detectAvailableCliAgent());
        } else if (modules.has("llm-openai")) {
            llmConfig.put("provider", "openai");
            llmConfig.put("model", "gpt-4o");
            String apiKey = wizardOpenaiApiKey != null ? wizardOpenaiApiKey : System.getenv("OPENAI_API_KEY");
            if (apiKey != null && !apiKey.isEmpty()) {
                llmConfig.put("apiKey", apiKey);
            }
        } else if (modules.has("llm-anthropic")) {
            llmConfig.put("provider", "anthropic");
            llmConfig.put("model", "claude-sonnet-4-20250514");
            String apiKey = wizardAnthropicApiKey != null ? wizardAnthropicApiKey : System.getenv("ANTHROPIC_API_KEY");
            if (apiKey != null && !apiKey.isEmpty()) {
                llmConfig.put("apiKey", apiKey);
            }
        } else if (modules.has("llm-gemini")) {
            llmConfig.put("provider", "gemini");
            String apiKey = wizardGeminiProjectId != null ? wizardGeminiProjectId : System.getenv("GOOGLE_API_KEY");
            if (apiKey != null && !apiKey.isEmpty()) {
                llmConfig.put("apiKey", apiKey);
            }
        }
        if (!llmConfig.isEmpty()) {
            saveGlobalAndProject(AppConfigWizard.LLM_PROVIDER_CONFIG, llmConfig, dataDir, projectConfigDir);
        }

        // --- pipeline-config.json ---
        saveGlobalAndProject("pipeline-config.json", autoConfig.pipelineConfig, dataDir, projectConfigDir);

        // --- subprocess-ingest-config.json ---
        saveGlobalAndProject("subprocess-ingest-config.json", autoConfig.subprocessConfig, dataDir, projectConfigDir);

        // --- nd4j-environment-config.json ---
        saveGlobalAndProject("nd4j-environment-config.json", autoConfig.nd4jConfig, dataDir, projectConfigDir);

        // --- feature-flags-config.json ---
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("guardrails", false);
        flags.put("queryTransformation", false);
        flags.put("contextualRag", false);
        flags.put("toolGatewayEnabled", false);
        flags.put("kvcache", false);
        flags.put("graphRag", false);
        flags.put("multiModal", false);
        flags.put("sourceAttribution", false);
        saveGlobalAndProject(AppConfigWizard.FEATURE_FLAGS_CONFIG, flags, dataDir, projectConfigDir);

        // --- tool-gateway-config.json ---
        Map<String, Object> gwConfig = new LinkedHashMap<>();
        gwConfig.put("modelSource", "STAGING");
        gwConfig.put("failOpen", true);
        gwConfig.put("evaluationTimeoutMs", 10000);
        gwConfig.put("verboseLogging", false);
        gwConfig.put("hotReload", false);
        gwConfig.put("dryRun", false);
        gwConfig.put("judgeScoringEnabled", false);
        saveGlobalAndProject("tool-gateway-config.json", gwConfig, dataDir, projectConfigDir);
    }

    /**
     * Save config to both the global ~/.kompile/config/ (if not already present)
     * and the per-project config/ directory (always).
     */
    private void saveGlobalAndProject(String filename, Map<String, Object> config,
                                      String globalDataDir, Path projectConfigDir) {
        // Global: only if not already present (preserve user customizations)
        if (!new File(globalDataDir, "config/" + filename).exists()) {
            AppConfigWizard.saveConfig(filename, config);
        }
        // Per-project: always write fresh
        AppConfigWizard.saveConfigTo(projectConfigDir, filename, config);
        System.out.println("  Bootstrapped: config/" + filename);
    }

    private void applyWizardResult(InitProjectWizard.WizardResult result) {
        projectName = result.projectName;
        outputDir = result.outputDir;
        if (result.preset != null) {
            presetName = result.preset.name().toLowerCase().replace("_", "-");
        }
        serverPort = result.serverPort;
        javacppPlatform = result.javacppPlatform;
        startAfterBuild = result.startAfterBuild;
        noBuild = result.noBuild;
        wizardModuleIds = result.moduleIds;
        wizardOpenaiApiKey = result.openaiApiKey;
        wizardAnthropicApiKey = result.anthropicApiKey;
        wizardGeminiProjectId = result.geminiProjectId;

        wizardBackends = result.backends;
        if (result.javacppExtension != null) {
            javacppExtension = result.javacppExtension;
        }
        if (result.appTitle != null) {
            appTitle = result.appTitle;
        }
        if (result.databaseUrl != null) {
            databaseUrl = result.databaseUrl;
        }
        if (result.databaseUsername != null) {
            databaseUsername = result.databaseUsername;
        }
        if (result.databasePassword != null) {
            databasePassword = result.databasePassword;
        }
        enableSchemaInit = result.enableSchemaInit;
        if (result.supportedLanguages != null && !result.supportedLanguages.isEmpty()) {
            supportedLanguages = result.supportedLanguages;
        }
        if (result.mavenHome != null) {
            mavenHome = result.mavenHome;
        }
        if (result.dataSources != null && !result.dataSources.isEmpty()) {
            dataSources = new ArrayList<>(result.dataSources);
            crawlDepth = result.crawlDepth;
            crawlMultimodal = result.crawlMultimodal;
            crawlGraph = result.crawlGraph;
        }
        // Mark as wizard-sourced for directory overwrite handling
        wizard = true;
    }

    private ModuleSelection buildModuleSelection(BuildPreset preset) {
        ModuleSelection.Builder selBuilder = ModuleSelection.fromPreset(preset);

        if (llmOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.LLM, llmOverride);
        if (embeddingOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.EMBEDDING, embeddingOverride);
        if (vectorstoreOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.VECTORSTORE, vectorstoreOverride);
        if (loadersOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.LOADER, loadersOverride);
        if (chunkersOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.CHUNKER, chunkersOverride);
        if (toolsOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.TOOL, toolsOverride);

        if (includeModules != null) selBuilder.include(includeModules);
        if (excludeModules != null) selBuilder.exclude(excludeModules);

        return selBuilder.build();
    }

    private void applyAutoDetection(File projectDir) {
        if (!inferProject) {
            return;
        }
        Path rootPath = (inferFrom != null ? inferFrom : outputDir).toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(rootPath)) {
            System.out.println("  Auto-detect skipped: source root does not exist: " + rootPath);
            return;
        }
        if (rootPath.equals(projectDir.toPath().toAbsolutePath().normalize()) && !projectDir.exists()) {
            return;
        }

        try {
            inferredSignals = ProjectAutoDetection.collectSignals(rootPath);
        } catch (RuntimeException e) {
            System.out.println("  Auto-detect skipped: " + e.getMessage());
            inferredSignals = null;
            return;
        }
        if (inferredSignals == null || !inferredSignals.hasAnySignal()) {
            return;
        }

        inferredScenario = ProjectAutoDetection.classifyScenario(inferredSignals);
        printDetectionSummary(rootPath);

        if (!hasInitialCrawl() && inferredSignals.hasData()) {
            dataSources = new ArrayList<>(inferredSignals.docDirs());
        }
        if ((crawlSourceType == null || crawlSourceType.isBlank()) && hasInitialCrawl()) {
            crawlSourceType = inferCommonCrawlSourceType(dataSources);
        }
        if (inferredSignals.hasPdfOrImages()) {
            crawlMultimodal = true;
        }
        if (inferredSignals.hasModels()) {
            inferredModels = new ArrayList<>(inferredSignals.models());
        }
        if (inferredSignals.hasCode()) {
            inferredCodingProject = buildInferredCodingProject(inferredSignals.codeProject());
        }
    }

    private void printDetectionSummary(Path rootPath) {
        String scenario = inferredScenario == null
                ? "unknown"
                : inferredScenario.name().toLowerCase(Locale.ROOT).replace('_', '-');
        System.out.println("  Auto-detected from: " + rootPath);
        System.out.println("    Project kind: " + scenario);
        if (!inferredSignals.docDirs().isEmpty()) {
            System.out.println("    Data sources: " + inferredSignals.docDirs().size());
        }
        if (!inferredSignals.models().isEmpty()) {
            System.out.println("    Models: " + inferredSignals.models().size());
        }
        if (inferredSignals.codeProject() != null) {
            ProjectAutoDetection.CodeProjectSignal code = inferredSignals.codeProject();
            System.out.println("    Code: " + code.language() + " (" + code.buildFile() + ")");
        }
    }

    private String inferCommonCrawlSourceType(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        String common = null;
        for (String source : sources) {
            String inferred = ProjectAutoDetection.inferCrawlSourceType(source);
            if (inferred == null || inferred.isBlank()) {
                return null;
            }
            if (common == null) {
                common = inferred;
            } else if (!common.equalsIgnoreCase(inferred)) {
                return null;
            }
        }
        return common;
    }

    private ModuleSelection augmentModulesForDetectedProject(ModuleSelection modules) {
        Set<String> extras = new LinkedHashSet<>();
        if (hasInitialCrawl() || hasPdfVlmWorkflow()) {
            addKnownModule(extras, "crawler-core");
            addKnownModule(extras, "crawl-graph");
            addKnownModule(extras, "tool-crawler");
            addKnownModule(extras, "loader-tika");
        }
        if (crawlGraph || crawlSchemaPreset != null) {
            addKnownModule(extras, "knowledge-graph");
        }
        boolean needsMultimodal = crawlMultimodal || hasPdfVlmWorkflow()
                || (inferredSignals != null && inferredSignals.hasPdfOrImages());
        if (needsMultimodal) {
            addKnownModule(extras, "loader-pdf-extended");
            addKnownModule(extras, "ocr-core");
            addKnownModule(extras, "ocr-models");
            addKnownModule(extras, "ocr-integration");
        }
        if (inferredCodingProject != null) {
            addKnownModule(extras, "code-indexer");
        }
        if (extras.isEmpty()) {
            return modules;
        }

        ModuleSelection.Builder builder = ModuleSelection.empty();
        builder.include(modules.getAll());
        builder.include(extras);
        if (excludeModules != null) {
            builder.exclude(excludeModules);
        }
        ModuleSelection augmented = builder.build();

        Set<String> added = new LinkedHashSet<>(augmented.getAll());
        added.removeAll(modules.getAll());
        if (!added.isEmpty()) {
            System.out.println("  Auto-enabled modules: " + String.join(", ", added));
        }
        return augmented;
    }

    private void addKnownModule(Set<String> moduleIds, String moduleId) {
        if (ModuleCatalog.get(moduleId) != null) {
            moduleIds.add(moduleId);
        }
    }

    private KompileCodingProject buildInferredCodingProject(ProjectAutoDetection.CodeProjectSignal signal) {
        Path root = signal.root().toAbsolutePath().normalize();
        String dirName = root.getFileName() != null ? root.getFileName().toString() : projectName;
        String id = safeId(dirName, "code");
        java.time.Instant now = java.time.Instant.now();

        KompileCodingProject cp = new KompileCodingProject();
        cp.setId(id);
        cp.setCodeProjectId(id);
        cp.setName(signal.language() + " project (" + dirName + ")");
        cp.setRootPath(root.toString());
        cp.setAutoIndex(true);
        cp.setTags(List.of("code", signal.language().toLowerCase(Locale.ROOT), "auto-detected", "init"));
        cp.setCreatedAt(now);
        cp.setUpdatedAt(now);

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("buildFile", signal.buildFile());
        metadata.put("language", signal.language());
        metadata.put("detectedBy", "init-project");
        cp.setMetadata(metadata);
        return cp;
    }

    private String safeId(String raw, String fallback) {
        String base = firstNonBlank(raw, fallback);
        if (base == null) {
            base = "project";
        }
        String id = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        id = id.replaceAll("^-+", "").replaceAll("-+$", "");
        return id.isBlank() ? fallback : id;
    }

    private BuildPreset resolvePreset(String name) {
        try {
            return BuildPreset.valueOf(name.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown preset: " + name + ". Available: " +
                    Arrays.stream(BuildPreset.values())
                            .map(p -> p.name().toLowerCase().replace("_", "-"))
                            .collect(Collectors.joining(", ")));
            throw e;
        }
    }

    private KompileProjectStorageBackend parseProjectStorageBackend(String backendName) {
        try {
            return KompileProjectStorageBackend.valueOf(
                    (backendName == null ? "local" : backendName)
                            .trim()
                            .replace("-", "_")
                            .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown project backend: " + backendName + ". Available: local, git, git-xet");
            throw e;
        }
    }

    private void scaffoldDataDirectories(File projectDir) throws IOException {
        // Create data directory structure matching the sample project layout
        File dataDir = new File(projectDir, "data");
        new File(dataDir, "input_documents/uploads").mkdirs();
        new File(dataDir, "markdown").mkdirs();
        new File(dataDir, "sources").mkdirs();
        new File(dataDir, "chats").mkdirs();
        new File(dataDir, "code-projects").mkdirs();
        new File(dataDir, "crawls").mkdirs();
        new File(dataDir, "workflows").mkdirs();
        new File(dataDir, "artifacts").mkdirs();
        new File(dataDir, "shared_files").mkdirs();
        new File(dataDir, "prompt-templates").mkdirs();
        new File(dataDir, "models/.staging").mkdirs();
        new File(dataDir, "logs").mkdirs();
        // Directories the app expects at runtime (created here to match reference projects)
        new File(dataDir, "tool-definitions").mkdirs();
        new File(dataDir, "folders").mkdirs();
        new File(dataDir, "mcp-bridges").mkdirs();
        new File(dataDir, "mcp-servers").mkdirs();
        new File(dataDir, "pids").mkdirs();

        // Create staging directory for model-staging server (native image or JAR)
        new File(projectDir, "staging").mkdirs();

        // Seed shared files sample
        writeFile(new File(dataDir, "shared_files/sample-shared.txt"),
                "This is a sample file in the shared MCP root: default\n");

        // Seed MCP config
        writeFile(new File(dataDir, "mcp-config.json"),
                "{\n" +
                "  \"mcpServers\" : { }\n" +
                "}\n");

        // Seed default prompt templates
        writePromptTemplate(dataDir, "rag_query", "RAG Query",
                "Standard template for RAG-based document Q&A", "rag",
                "Based on the following context, answer the question.\n\nContext:\n{{context}}\n\nQuestion: {{question}}\n\nProvide a clear and concise answer based only on the information in the context. If the answer cannot be found in the context, say so.\n",
                new String[]{"rag", "qa", "retrieval"},
                new String[][]{
                        {"context", "Context", "The retrieved document context"},
                        {"question", "Question", "The user's question"}
                });

        writePromptTemplate(dataDir, "summarize_text", "Summarize Text",
                "Summarize the provided text concisely", "text-processing",
                "Please summarize the following text concisely while preserving the key information:\n\n{{text}}\n\nSummary:\n",
                new String[]{"summarization", "text-processing"},
                new String[][]{
                        {"text", "Text", "The text to summarize"}
                });

        writePromptTemplate(dataDir, "extract_entities", "Extract Entities",
                "Extract named entities from text", "extraction",
                "Extract all named entities (people, organizations, locations, dates, etc.) from the following text:\n\n{{text}}\n\nReturn the entities as a structured list grouped by type.\n",
                new String[]{"extraction", "ner", "entities"},
                new String[][]{
                        {"text", "Text", "The text to extract entities from"}
                });

        writePromptTemplate(dataDir, "classify_text", "Classify Text",
                "Classify text into categories", "classification",
                "Classify the following text into one or more of these categories: {{categories}}\n\nText:\n{{text}}\n\nReturn your classification with a confidence score and brief justification.\n",
                new String[]{"classification", "categorization"},
                new String[][]{
                        {"text", "Text", "The text to classify"},
                        {"categories", "Categories", "Comma-separated list of categories"}
                });

        writePromptTemplate(dataDir, "code_review", "Code Review",
                "Review code for quality, bugs, and improvements", "code",
                "Review the following {{language}} code for:\n- Bugs and potential issues\n- Code quality and best practices\n- Performance considerations\n- Security concerns\n\nCode:\n```{{language}}\n{{code}}\n```\n\nProvide your review with specific suggestions for improvement.\n",
                new String[]{"code", "review", "quality"},
                new String[][]{
                        {"code", "Code", "The code to review"},
                        {"language", "Language", "Programming language"}
                });

        writePromptTemplateWithSystemPrompt(dataDir, "agent_system_prompt", "Agent System Prompt",
                "System prompt for configuring an AI agent", "system",
                "{{user_message}}",
                "You are {{agent_name}}, an AI assistant with the following characteristics:\n\nRole: {{role}}\n\nExpertise: {{expertise}}\n\nGuidelines:\n{{guidelines}}\n\nAlways be helpful, accurate, and professional in your responses.\n",
                new String[]{"system", "agent", "persona"},
                new String[][]{
                        {"agent_name", "Agent Name", "Name of the AI agent"},
                        {"role", "Role", "The agent's role or purpose"},
                        {"expertise", "Expertise", "Areas of expertise"},
                        {"guidelines", "Guidelines", "Behavioral guidelines"},
                        {"user_message", "User Message", "The user's message"}
                });

        // Seed a sample input document
        writeFile(new File(dataDir, "input_documents/sample.txt"),
                "This is a sample document for the kompile RAG application.\n" +
                "You can add your own documents to the data/input_documents/ directory\n" +
                "or upload them through the web UI.\n");

        System.out.println("  Scaffolded data directories and seed files");
    }

    private void seedProjectManifest(File projectDir, ModuleSelection modules) {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName(projectName);
        request.setDescription(appTitle + " generated with kompile init-project");
        request.setBackend(parseProjectStorageBackend(projectBackend));
        request.setRemoteUrl(projectRemoteUrl);
        request.setBranch(projectBranch);
        request.setInitializeGit(initializeProjectGit);
        request.setInstallGitXet(installProjectGitXet);

        List<String> tags = new ArrayList<>();
        tags.add("kompile");
        tags.add("rag");
        tags.add(presetName);
        if (projectTags != null) {
            tags.addAll(projectTags);
        }
        request.setTags(tags);
        request.setModules(new ArrayList<>(modules.getAll()));
        List<KompileProjectCrawlProfile> crawlProfiles = new ArrayList<>();
        List<KompileProjectModel> models = new ArrayList<>();
        List<KompileProjectPipeline> pipelines = new ArrayList<>();
        List<KompileProjectScript> scripts = new ArrayList<>();
        List<KompileProjectWorkflow> workflows = new ArrayList<>();
        if (inferredModels != null) {
            for (KompileProjectModel model : inferredModels) {
                addDetectedModelIfMissing(models, model);
            }
        }

        KompileProjectCrawlProfile initialCrawl = initialCrawlProfile();
        if (initialCrawl != null) {
            crawlProfiles.add(initialCrawl);
            seedModelsAndPipelinesForCrawl(initialCrawl, models, pipelines);
            scripts.add(generatedScript("init-crawl", "Initialize crawl", "scripts/init-crawl.sh",
                    "crawl", "Run the initial managed crawl profile.",
                    List.of("crawl", "init", "ingestion")));
            workflows.add(crawlOnlyWorkflow("initial-crawl", "Initial crawl",
                    "Run the generated initial crawl profile.", initialCrawl,
                    List.of("workflow", "crawl", "init")));
        }

        KompileProjectCrawlProfile pdfVlmCrawl = pdfVlmCrawlProfile();
        if (pdfVlmCrawl != null) {
            crawlProfiles.add(pdfVlmCrawl);
            seedModelsAndPipelinesForCrawl(pdfVlmCrawl, models, pipelines);
            scripts.add(generatedScript("init-pdf-vlm", "Initialize PDF VLM ingest",
                    "scripts/init-pdf-vlm.sh", "crawl",
                    "Start project services and run the managed PDF/VLM crawl workflow.",
                    List.of("crawl", "pdf", "vlm", "ttrpg", "ingestion")));
            workflows.add(pdfVlmWorkflow(pdfVlmCrawl));
        }

        if (!crawlProfiles.isEmpty()) {
            request.setCrawlProfiles(crawlProfiles);
        }
        if (!models.isEmpty()) {
            request.setModels(models);
        }
        if (!pipelines.isEmpty()) {
            request.setPipelines(pipelines);
        }
        if (!scripts.isEmpty()) {
            request.setScripts(scripts);
        }
        if (!workflows.isEmpty()) {
            request.setWorkflows(workflows);
        }
        if (inferredCodingProject != null) {
            request.setCodingProjects(List.of(inferredCodingProject));
        }

        KompileProjectManifest manifest = store.init(projectDir.toPath(), request);
        if (initialCrawl != null) {
            writeWorkflowScript(projectDir, "init-crawl.sh", "initial-crawl",
                    "Run the managed initial crawl profile generated by kompile init-project.");
        }
        if (pdfVlmCrawl != null) {
            writeWorkflowScript(projectDir, "init-pdf-vlm.sh", "pdf-vlm-ingest",
                    "Start services and run the managed PDF/VLM crawl workflow generated by kompile init-project.");
        }
        System.out.println("  Seeded unified project manifest: " + KompileProjectStore.MANIFEST_FILE
                + " (" + manifest.getComponents().size() + " components)");
    }

    private KompileProjectCrawlProfile initialCrawlProfile() {
        if (dataSources == null || dataSources.isEmpty()) {
            return null;
        }
        KompileProjectCrawlProfile crawlProfile = new KompileProjectCrawlProfile();
        crawlProfile.setId("initial-crawl");
        crawlProfile.setName(firstNonBlank(crawlName, projectName + " initial crawl"));
        crawlProfile.setSources(dataSources);
        crawlProfile.setMaxDepth(crawlDepth > 0 ? crawlDepth : crawlMaxDepth);
        crawlProfile.setMaxDocuments(crawlMaxDocuments);
        crawlProfile.setIncludePatterns(crawlIncludePatterns);
        crawlProfile.setExcludePatterns(crawlExcludePatterns);
        crawlProfile.setContentTypes(crawlContentTypes);
        crawlProfile.setCollection(crawlCollection);
        crawlProfile.setChunker(crawlChunker);
        crawlProfile.setLoader(crawlLoader);
        crawlProfile.setSourceType(crawlSourceType);
        crawlProfile.setFollowLinks(crawlFollowLinks);
        crawlProfile.setIncludeHidden(crawlIncludeHidden);
        crawlProfile.setMultimodal(crawlMultimodal);
        crawlProfile.setVlmModel(crawlVlmModel);
        crawlProfile.setGraphExtraction(crawlGraph || crawlSchemaPreset != null);
        crawlProfile.setSchemaPresetId(crawlSchemaPreset);
        crawlProfile.setGraphSchemaMode(crawlGraphSchemaMode);
        crawlProfile.setWatch(crawlWatch);
        crawlProfile.setTags(List.of("init", "crawl", "ingestion"));
        return crawlProfile;
    }

    private KompileProjectCrawlProfile pdfVlmCrawlProfile() {
        if (!hasPdfVlmWorkflow()) {
            return null;
        }
        KompileProjectCrawlProfile crawlProfile = new KompileProjectCrawlProfile();
        crawlProfile.setId("pdf-vlm-crawl");
        crawlProfile.setName(projectName + " PDF VLM crawl");
        crawlProfile.setDescription("Multimodal PDF crawl workflow for rulebooks, scanned pages, and TTRPG source folders.");
        crawlProfile.setSources(pdfVlmSources);
        crawlProfile.setMaxDepth(crawlMaxDepth > 0 ? crawlMaxDepth : 1);
        crawlProfile.setMaxDocuments(crawlMaxDocuments);
        crawlProfile.setIncludePatterns(crawlIncludePatterns == null || crawlIncludePatterns.isEmpty()
                ? List.of("**/*.pdf", "*.pdf")
                : crawlIncludePatterns);
        crawlProfile.setExcludePatterns(crawlExcludePatterns);
        crawlProfile.setContentTypes(crawlContentTypes == null || crawlContentTypes.isEmpty()
                ? List.of("application/pdf")
                : crawlContentTypes);
        crawlProfile.setCollection(crawlCollection);
        crawlProfile.setChunker(crawlChunker);
        crawlProfile.setLoader(crawlLoader);
        crawlProfile.setSourceType(firstNonBlank(crawlSourceType, inferPdfVlmSourceType()));
        crawlProfile.setFollowLinks(crawlFollowLinks);
        crawlProfile.setIncludeHidden(crawlIncludeHidden);
        crawlProfile.setMultimodal(true);
        crawlProfile.setVlmModel(firstNonBlank(pdfVlmModel, crawlVlmModel));
        crawlProfile.setGraphExtraction(crawlGraph || crawlSchemaPreset != null);
        crawlProfile.setSchemaPresetId(crawlSchemaPreset);
        crawlProfile.setGraphSchemaMode(crawlGraphSchemaMode);
        crawlProfile.setWatch(pdfVlmWatch || crawlWatch);
        crawlProfile.setTags(List.of("pdf", "vlm", "ttrpg", "crawl", "ingestion"));
        crawlProfile.setMetadata(Map.of(
                "workflowPreset", "pdf-vlm",
                "sourceKind", "pdf-directory"
        ));
        return crawlProfile;
    }

    private KompileProjectScript generatedScript(String id, String name, String path, String phase,
                                                 String description, List<String> tags) {
        KompileProjectScript script = new KompileProjectScript();
        script.setId(id);
        script.setName(name);
        script.setPath(path);
        script.setCommand("./" + path);
        script.setWorkingDirectory(".");
        script.setPhase(phase);
        script.setDescription(description);
        script.setPlatform("unix");
        script.setGenerated(true);
        script.setTags(tags);
        return script;
    }

    private KompileProjectWorkflow crawlOnlyWorkflow(String id, String name, String description,
                                                     KompileProjectCrawlProfile crawlProfile,
                                                     List<String> tags) {
        KompileProjectWorkflow workflow = new KompileProjectWorkflow();
        workflow.setId(id);
        workflow.setName(name);
        workflow.setPhase("crawl");
        workflow.setDescription(description);
        workflow.setGenerated(true);
        workflow.setTags(tags);
        workflow.setSteps(List.of(crawlStep("run-" + crawlProfile.getId(),
                "Run " + crawlProfile.getName(), crawlProfile.getId())));
        return workflow;
    }

    private KompileProjectWorkflow pdfVlmWorkflow(KompileProjectCrawlProfile crawlProfile) {
        KompileProjectWorkflow workflow = new KompileProjectWorkflow();
        workflow.setId("pdf-vlm-ingest");
        workflow.setName("PDF VLM ingest");
        workflow.setPhase("crawl");
        workflow.setDescription("Start project services, wait for the app, then ingest configured PDFs with multimodal/VLM processing.");
        workflow.setGenerated(true);
        workflow.setTags(List.of("workflow", "crawl", "pdf", "vlm", "ttrpg", "ingestion"));
        workflow.setMetadata(Map.of("workflowPreset", "pdf-vlm"));

        KompileProjectWorkflowStep startServices = new KompileProjectWorkflowStep();
        startServices.setId("start-services");
        startServices.setName("Start project services");
        startServices.setType("SCRIPT");
        startServices.setRef("start-all");
        startServices.setContinueOnFailure(true);

        KompileProjectWorkflowStep healthCheck = new KompileProjectWorkflowStep();
        healthCheck.setId("wait-for-app");
        healthCheck.setName("Wait for app health");
        healthCheck.setType("HEALTH_CHECK");
        healthCheck.setUrl("${appUrl}/actuator/health");
        healthCheck.setExpectedStatus(200);
        healthCheck.setTimeoutSeconds(180);

        workflow.setSteps(List.of(startServices, healthCheck,
                crawlStep("run-pdf-vlm-crawl", "Run PDF VLM crawl", crawlProfile.getId())));
        return workflow;
    }

    private void seedModelsAndPipelinesForCrawl(KompileProjectCrawlProfile crawlProfile,
                                                List<KompileProjectModel> models,
                                                List<KompileProjectPipeline> pipelines) {
        if (crawlProfile.isMultimodal() && firstNonBlank(crawlProfile.getVlmModel()) != null) {
            addModelIfMissing(models, crawlProfile.getVlmModel(), "VLM",
                    List.of("vlm", "crawl", "init"));
            KompileProjectPipeline pipeline = new KompileProjectPipeline();
            pipeline.setId(crawlProfile.getId() + "-vlm-ingest");
            pipeline.setPipelineId(crawlProfile.getId() + "-vlm-ingest");
            pipeline.setName(crawlProfile.getName() + " VLM ingest");
            pipeline.setRole("VLM_INGEST");
            pipeline.setVersion("1.0.0");
            pipeline.setModelRefs(List.of(crawlProfile.getVlmModel()));
            pipeline.setTags(List.of("vlm", "crawl", "init"));
            pipeline.setMetadata(Map.of("crawlProfileId", crawlProfile.getId()));
            addPipelineIfMissing(pipelines, pipeline);
        }
        if (firstNonBlank(crawlProfile.getGraphModelName()) != null) {
            KompileProjectModel graphModel = addModelIfMissing(models, crawlProfile.getGraphModelName(), "GRAPH",
                    List.of("graph", "crawl", "init"));
            graphModel.setSource(firstNonBlank(crawlProfile.getGraphModelProvider(), "runtime"));
            graphModel.setMetadata(Map.of("crawlProfileId", crawlProfile.getId()));
        }
    }

    private KompileProjectModel addModelIfMissing(List<KompileProjectModel> models, String modelId,
                                                  String role, List<String> tags) {
        for (KompileProjectModel model : models) {
            if (modelId.equals(model.getModelId())) {
                return model;
            }
        }
        KompileProjectModel model = new KompileProjectModel();
        model.setModelId(modelId);
        model.setRole(role);
        model.setSource("project");
        model.setRegistryModelId(modelId);
        model.setRequired(true);
        model.setTags(tags);
        models.add(model);
        return model;
    }

    private void addDetectedModelIfMissing(List<KompileProjectModel> models, KompileProjectModel model) {
        if (model == null) {
            return;
        }
        String modelKey = firstNonBlank(model.getModelId(), model.getId(), model.getPath());
        for (KompileProjectModel existing : models) {
            String existingKey = firstNonBlank(existing.getModelId(), existing.getId(), existing.getPath());
            if (modelKey != null && modelKey.equals(existingKey)) {
                return;
            }
        }
        models.add(model);
    }

    private void addPipelineIfMissing(List<KompileProjectPipeline> pipelines, KompileProjectPipeline pipeline) {
        for (KompileProjectPipeline existing : pipelines) {
            if (pipeline.getPipelineId().equals(existing.getPipelineId())) {
                return;
            }
        }
        pipelines.add(pipeline);
    }

    private KompileProjectWorkflowStep crawlStep(String id, String name, String crawlProfileId) {
        KompileProjectWorkflowStep crawlStep = new KompileProjectWorkflowStep();
        crawlStep.setId(id);
        crawlStep.setName(name);
        crawlStep.setType("CRAWL");
        crawlStep.setRef(crawlProfileId);
        return crawlStep;
    }

    private void writeWorkflowScript(File projectDir, String scriptName, String workflowId, String description) {
        File script = new File(projectDir, "scripts/" + scriptName);
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("# ").append(description).append("\n");
        sb.append("set -euo pipefail\n\n");
        sb.append("SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\n");
        sb.append("PROJECT_DIR=\"$(dirname \"$SCRIPT_DIR\")\"\n");
        sb.append("APP_URL=\"${APP_URL:-http://localhost:").append(serverPort).append("}\"\n\n");
        sb.append("cd \"$PROJECT_DIR\"\n");
        sb.append("kompile project workflow-run --id ").append(workflowId)
                .append(" --url \"$APP_URL\" \"$@\"\n");
        try {
            writeFile(script, sb.toString());
            script.setExecutable(true);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write workflow script: " + e.getMessage(), e);
        }
    }

    private void writePromptTemplate(File dataDir, String name, String displayName,
                                      String description, String category, String content,
                                      String[] tags, String[][] variables) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"id\" : \"").append(UUID.randomUUID()).append("\",\n");
        sb.append("  \"name\" : \"").append(name).append("\",\n");
        sb.append("  \"displayName\" : \"").append(displayName).append("\",\n");
        sb.append("  \"description\" : \"").append(description).append("\",\n");
        sb.append("  \"category\" : \"").append(category).append("\",\n");
        sb.append("  \"content\" : ").append(jsonEscape(content)).append(",\n");
        sb.append("  \"systemPrompt\" : null,\n");
        sb.append("  \"variables\" : [ ");
        for (int i = 0; i < variables.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("{\n");
            sb.append("    \"name\" : \"").append(variables[i][0]).append("\",\n");
            sb.append("    \"displayName\" : \"").append(variables[i][1]).append("\",\n");
            sb.append("    \"description\" : \"").append(variables[i][2]).append("\",\n");
            sb.append("    \"type\" : \"string\",\n");
            sb.append("    \"required\" : true,\n");
            sb.append("    \"defaultValue\" : null\n");
            sb.append("  }");
        }
        sb.append(" ],\n");
        sb.append("  \"examples\" : [ ],\n");
        sb.append("  \"tags\" : [ ");
        for (int i = 0; i < tags.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(tags[i]).append("\"");
        }
        sb.append(" ],\n");
        sb.append("  \"enabled\" : true,\n");
        sb.append("  \"builtIn\" : true\n");
        sb.append("}\n");
        writeFile(new File(dataDir, "prompt-templates/" + name + ".json"), sb.toString());
    }

    private void writePromptTemplateWithSystemPrompt(File dataDir, String name, String displayName,
                                                      String description, String category, String content,
                                                      String systemPrompt, String[] tags, String[][] variables) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"id\" : \"").append(UUID.randomUUID()).append("\",\n");
        sb.append("  \"name\" : \"").append(name).append("\",\n");
        sb.append("  \"displayName\" : \"").append(displayName).append("\",\n");
        sb.append("  \"description\" : \"").append(description).append("\",\n");
        sb.append("  \"category\" : \"").append(category).append("\",\n");
        sb.append("  \"content\" : ").append(jsonEscape(content)).append(",\n");
        sb.append("  \"systemPrompt\" : ").append(jsonEscape(systemPrompt)).append(",\n");
        sb.append("  \"variables\" : [ ");
        for (int i = 0; i < variables.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("{\n");
            sb.append("    \"name\" : \"").append(variables[i][0]).append("\",\n");
            sb.append("    \"displayName\" : \"").append(variables[i][1]).append("\",\n");
            sb.append("    \"description\" : \"").append(variables[i][2]).append("\",\n");
            sb.append("    \"type\" : \"string\",\n");
            sb.append("    \"required\" : true,\n");
            sb.append("    \"defaultValue\" : null\n");
            sb.append("  }");
        }
        sb.append(" ],\n");
        sb.append("  \"examples\" : [ ],\n");
        sb.append("  \"tags\" : [ ");
        for (int i = 0; i < tags.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(tags[i]).append("\"");
        }
        sb.append(" ],\n");
        sb.append("  \"enabled\" : true,\n");
        sb.append("  \"builtIn\" : true\n");
        sb.append("}\n");
        writeFile(new File(dataDir, "prompt-templates/" + name + ".json"), sb.toString());
    }

    private String jsonEscape(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private void generateGitignore(File projectDir) throws IOException {
        writeFile(new File(projectDir, ".gitignore"),
                "# Build output\n" +
                "target/\n" +
                "*.class\n" +
                "\n" +
                "# Runtime data\n" +
                "data/logs/\n" +
                "data/pids/\n" +
                "data/*.db\n" +
                "data/*.db.trace.db\n" +
                "data/*.db.mv.db\n" +
                "\n" +
                "# Staging server binaries\n" +
                "staging/\n" +
                "\n" +
                "# Crash dumps\n" +
                "hs_err_pid*.log\n" +
                "hotspot_pid*.log\n" +
                "*.hprof\n" +
                "*.heapdump\n" +
                "compute_sanitizer_*.log\n" +
                "replay_pid*.log\n" +
                "\n" +
                "# IDE\n" +
                ".idea/\n" +
                "*.iml\n" +
                ".vscode/\n" +
                ".settings/\n" +
                ".classpath\n" +
                ".project\n" +
                "\n" +
                "# OS\n" +
                ".DS_Store\n" +
                "Thumbs.db\n");
        System.out.println("  Generated .gitignore");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Scripts generation
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Generate multi-platform scripts for starting the application and all subprocesses.
     * Each subprocess script launches via the project's fat JAR, which has the correct
     * backend (CPU/CUDA) baked into the POM at init-project time.
     */
    private void generateScripts(File projectDir) throws IOException {
        File scriptsDir = new File(projectDir, "scripts");
        scriptsDir.mkdirs();

        String artifactId = projectName;
        String version = instanceVersion;
        String jarName = artifactId + "-" + version + ".jar";
        int appPort = serverPort;

        // ── Bash scripts ──

        // start-app.sh
        writeFile(new File(scriptsDir, "start-app.sh"), bashScript(
                "Start the main kompile application",
                "app",
                jarName,
                "--server.port=" + appPort,
                appPort));

        // start-serving.sh
        writeFile(new File(scriptsDir, "start-serving.sh"), bashScript(
                "Start the LLM serving subprocess (runs SameDiff/ONNX inference with GPU/CPU)",
                "serving",
                jarName,
                "--subprocess=serving --port=8091 --staging-url=http://localhost:8090",
                8091));

        // start-staging.sh
        writeFile(new File(scriptsDir, "start-staging.sh"), bashStagingScript());

        // start-all.sh
        writeFile(new File(scriptsDir, "start-all.sh"), bashStartAllScript(appPort, jarName));

        // stop-all.sh
        writeFile(new File(scriptsDir, "stop-all.sh"), bashStopAllScript());

        // ── Windows batch scripts ──

        // start-app.bat
        writeFile(new File(scriptsDir, "start-app.bat"), batScript(
                "Start the main kompile application",
                "app",
                jarName,
                "--server.port=" + appPort,
                appPort));

        // start-serving.bat
        writeFile(new File(scriptsDir, "start-serving.bat"), batScript(
                "Start the LLM serving subprocess",
                "serving",
                jarName,
                "--subprocess=serving --port=8091 --staging-url=http://localhost:8090",
                8091));

        // start-staging.bat
        writeFile(new File(scriptsDir, "start-staging.bat"), batStagingScript());

        // start-all.bat
        writeFile(new File(scriptsDir, "start-all.bat"), batStartAllScript(appPort, jarName));

        // stop-all.bat
        writeFile(new File(scriptsDir, "stop-all.bat"), batStopAllScript());

        // Make bash scripts executable
        for (File f : scriptsDir.listFiles((dir, name) -> name.endsWith(".sh"))) {
            f.setExecutable(true, false);
        }

        System.out.println("  Generated startup scripts in scripts/");
    }

    private String bashScript(String description, String type, String jarName,
                               String args, int port) {
        boolean isApp = "app".equals(type);
        String logFile = isApp ? "app.log" : type + "-subprocess.log";

        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("# ").append(description).append("\n");
        sb.append("# Generated by kompile init-project\n");
        sb.append("set -euo pipefail\n\n");

        sb.append("SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\n");
        sb.append("PROJECT_DIR=\"$(dirname \"$SCRIPT_DIR\")\"\n");
        sb.append("JAR=\"$PROJECT_DIR/target/").append(jarName).append("\"\n");
        sb.append("LOG_DIR=\"$PROJECT_DIR/data/logs\"\n");
        sb.append("PID_DIR=\"$PROJECT_DIR/data/pids\"\n");
        sb.append("mkdir -p \"$LOG_DIR\" \"$PID_DIR\"\n\n");

        // Build if JAR doesn't exist
        sb.append("if [ ! -f \"$JAR\" ]; then\n");
        sb.append("  echo \"JAR not found, building project...\"\n");
        sb.append("  cd \"$PROJECT_DIR\" && mvn package -DskipTests -q\n");
        sb.append("fi\n\n");

        // Check if already running
        sb.append("PID_FILE=\"$PID_DIR/").append(type).append(".pid\"\n");
        sb.append("if [ -f \"$PID_FILE\" ] && kill -0 \"$(cat \"$PID_FILE\")\" 2>/dev/null; then\n");
        sb.append("  echo \"").append(type).append(" is already running (PID $(cat \"$PID_FILE\"))\"\n");
        sb.append("  exit 1\n");
        sb.append("fi\n\n");

        // Launch
        if (isApp) {
            sb.append("echo \"Starting application on port ").append(port).append("...\"\n");
            sb.append("java -jar \"$JAR\" ").append(args);
            sb.append(" > \"$LOG_DIR/").append(logFile).append("\" 2>&1 &\n");
        } else {
            sb.append("echo \"Starting ").append(type).append(" subprocess on port ").append(port).append("...\"\n");
            sb.append("java -jar \"$JAR\" ").append(args);
            sb.append(" > \"$LOG_DIR/").append(logFile).append("\" 2>&1 &\n");
        }
        sb.append("APP_PID=$!\n");
        sb.append("echo \"$APP_PID\" > \"$PID_FILE\"\n");
        sb.append("echo \"Started ").append(type).append(" (PID $APP_PID). Logs: $LOG_DIR/").append(logFile).append("\"\n\n");

        // Wait for health
        sb.append("echo \"Waiting for port ").append(port).append("...\"\n");
        sb.append("for i in $(seq 1 60); do\n");
        sb.append("  if curl -sf http://localhost:").append(port).append("/actuator/health > /dev/null 2>&1; then\n");
        sb.append("    echo \"").append(type).append(" is healthy on port ").append(port).append("\"\n");
        sb.append("    exit 0\n");
        sb.append("  fi\n");
        sb.append("  sleep 2\n");
        sb.append("done\n");
        sb.append("echo \"Warning: ").append(type).append(" did not become healthy within 120s (may still be starting)\"\n");
        sb.append("echo \"Check logs: tail -f $LOG_DIR/").append(logFile).append("\"\n");

        return sb.toString();
    }

    private String bashStagingScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("# Start the model staging server\n");
        sb.append("# Generated by kompile init-project\n");
        sb.append("set -euo pipefail\n\n");

        sb.append("SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\n");
        sb.append("PROJECT_DIR=\"$(dirname \"$SCRIPT_DIR\")\"\n");
        sb.append("STAGING_DIR=\"$PROJECT_DIR/staging\"\n");
        sb.append("LOG_DIR=\"$PROJECT_DIR/data/logs\"\n");
        sb.append("PID_DIR=\"$PROJECT_DIR/data/pids\"\n");
        sb.append("MODELS_DIR=\"$PROJECT_DIR/data/models\"\n");
        sb.append("mkdir -p \"$LOG_DIR\" \"$PID_DIR\" \"$MODELS_DIR/.staging\"\n\n");

        sb.append("PID_FILE=\"$PID_DIR/staging.pid\"\n");
        sb.append("if [ -f \"$PID_FILE\" ] && kill -0 \"$(cat \"$PID_FILE\")\" 2>/dev/null; then\n");
        sb.append("  echo \"Staging server already running (PID $(cat \"$PID_FILE\"))\"\n");
        sb.append("  exit 1\n");
        sb.append("fi\n\n");

        sb.append("# Find staging executable (native or JAR)\n");
        sb.append("if [ -x \"$STAGING_DIR/kompile-model-staging\" ]; then\n");
        sb.append("  CMD=\"$STAGING_DIR/kompile-model-staging\"\n");
        sb.append("elif ls \"$STAGING_DIR\"/kompile-model-staging*.jar 1>/dev/null 2>&1; then\n");
        sb.append("  JAR=$(ls -t \"$STAGING_DIR\"/kompile-model-staging*.jar | head -1)\n");
        sb.append("  CMD=\"java -Xmx4g -jar $JAR\"\n");
        sb.append("else\n");
        sb.append("  echo \"No staging server found in $STAGING_DIR\"\n");
        sb.append("  echo \"Install with: kompile install kompile-model-staging\"\n");
        sb.append("  exit 1\n");
        sb.append("fi\n\n");

        sb.append("echo \"Starting staging server on port 8090...\"\n");
        sb.append("$CMD --server --server.port=8090 \\\n");
        sb.append("  --kompile.staging.model-dir=\"$MODELS_DIR\" \\\n");
        sb.append("  --kompile.staging.staging-dir=\"$MODELS_DIR/.staging\" \\\n");
        sb.append("  > \"$LOG_DIR/staging-server.log\" 2>&1 &\n");
        sb.append("echo \"$!\" > \"$PID_FILE\"\n");
        sb.append("echo \"Started staging server (PID $!). Logs: $LOG_DIR/staging-server.log\"\n");

        return sb.toString();
    }

    private String bashStartAllScript(int appPort, String jarName) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("# Start all kompile services in the correct order\n");
        sb.append("# Generated by kompile init-project\n");
        sb.append("set -euo pipefail\n\n");

        sb.append("SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\n\n");

        sb.append("echo \"=== Starting kompile services ===\"\n");
        sb.append("echo \"\"\n\n");

        sb.append("# 1. Staging server (model registry)\n");
        sb.append("echo \"[1/3] Starting staging server...\"\n");
        sb.append("\"$SCRIPT_DIR/start-staging.sh\" || true\n");
        sb.append("sleep 2\n\n");

        sb.append("# 2. LLM serving subprocess (inference)\n");
        sb.append("echo \"[2/3] Starting LLM serving subprocess...\"\n");
        sb.append("\"$SCRIPT_DIR/start-serving.sh\" || true\n");
        sb.append("sleep 2\n\n");

        sb.append("# 3. Main application (orchestrator + UI)\n");
        sb.append("echo \"[3/3] Starting main application...\"\n");
        sb.append("\"$SCRIPT_DIR/start-app.sh\" || true\n\n");

        sb.append("echo \"\"\n");
        sb.append("echo \"=== All services started ===\"\n");
        sb.append("echo \"  Staging:  http://localhost:8090\"\n");
        sb.append("echo \"  Serving:  http://localhost:8091\"\n");
        sb.append("echo \"  App:      http://localhost:").append(appPort).append("\"\n");
        sb.append("echo \"\"\n");
        sb.append("echo \"Stop all:   $SCRIPT_DIR/stop-all.sh\"\n");
        sb.append("echo \"View logs:  tail -f $(dirname $SCRIPT_DIR)/data/logs/*.log\"\n");

        return sb.toString();
    }

    private String bashStopAllScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("# Stop all kompile services\n");
        sb.append("# Generated by kompile init-project\n");
        sb.append("set -euo pipefail\n\n");

        sb.append("SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\n");
        sb.append("PID_DIR=\"$(dirname \"$SCRIPT_DIR\")/data/pids\"\n\n");

        sb.append("stopped=0\n");
        sb.append("for pid_file in \"$PID_DIR\"/*.pid; do\n");
        sb.append("  [ -f \"$pid_file\" ] || continue\n");
        sb.append("  name=$(basename \"$pid_file\" .pid)\n");
        sb.append("  pid=$(cat \"$pid_file\")\n");
        sb.append("  if kill -0 \"$pid\" 2>/dev/null; then\n");
        sb.append("    echo \"Stopping $name (PID $pid)...\"\n");
        sb.append("    kill \"$pid\"\n");
        sb.append("    stopped=$((stopped + 1))\n");
        sb.append("  else\n");
        sb.append("    echo \"$name not running (stale PID $pid)\"\n");
        sb.append("  fi\n");
        sb.append("  rm -f \"$pid_file\"\n");
        sb.append("done\n\n");

        sb.append("if [ $stopped -eq 0 ]; then\n");
        sb.append("  echo \"No running services found.\"\n");
        sb.append("else\n");
        sb.append("  echo \"Stopped $stopped service(s).\"\n");
        sb.append("fi\n");

        return sb.toString();
    }

    // ── Windows batch scripts ──

    private String batScript(String description, String type, String jarName,
                              String args, int port) {
        boolean isApp = "app".equals(type);
        String logFile = isApp ? "app.log" : type + "-subprocess.log";

        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\n");
        sb.append("REM ").append(description).append("\n");
        sb.append("REM Generated by kompile init-project\n\n");

        sb.append("set SCRIPT_DIR=%~dp0\n");
        sb.append("set PROJECT_DIR=%SCRIPT_DIR%..\n");
        sb.append("set JAR=%PROJECT_DIR%\\target\\").append(jarName).append("\n");
        sb.append("set LOG_DIR=%PROJECT_DIR%\\data\\logs\n");
        sb.append("set PID_DIR=%PROJECT_DIR%\\data\\pids\n");
        sb.append("if not exist \"%LOG_DIR%\" mkdir \"%LOG_DIR%\"\n");
        sb.append("if not exist \"%PID_DIR%\" mkdir \"%PID_DIR%\"\n\n");

        // Build if JAR doesn't exist
        sb.append("if not exist \"%JAR%\" (\n");
        sb.append("  echo JAR not found, building project...\n");
        sb.append("  cd /d \"%PROJECT_DIR%\" && mvn package -DskipTests -q\n");
        sb.append(")\n\n");

        sb.append("echo Starting ").append(type).append(" on port ").append(port).append("...\n");
        sb.append("start \"kompile-").append(type).append("\" /B java -jar \"%JAR%\" ").append(args);
        sb.append(" > \"%LOG_DIR%\\").append(logFile).append("\" 2>&1\n");
        sb.append("echo Started ").append(type).append(". Logs: %LOG_DIR%\\").append(logFile).append("\n");

        return sb.toString();
    }

    private String batStagingScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\n");
        sb.append("REM Start the model staging server\n");
        sb.append("REM Generated by kompile init-project\n\n");

        sb.append("set SCRIPT_DIR=%~dp0\n");
        sb.append("set PROJECT_DIR=%SCRIPT_DIR%..\n");
        sb.append("set STAGING_DIR=%PROJECT_DIR%\\staging\n");
        sb.append("set LOG_DIR=%PROJECT_DIR%\\data\\logs\n");
        sb.append("set MODELS_DIR=%PROJECT_DIR%\\data\\models\n");
        sb.append("if not exist \"%LOG_DIR%\" mkdir \"%LOG_DIR%\"\n");
        sb.append("if not exist \"%MODELS_DIR%\\.staging\" mkdir \"%MODELS_DIR%\\.staging\"\n\n");

        sb.append("if exist \"%STAGING_DIR%\\kompile-model-staging.exe\" (\n");
        sb.append("  set CMD=\"%STAGING_DIR%\\kompile-model-staging.exe\"\n");
        sb.append(") else (\n");
        sb.append("  for %%f in (\"%STAGING_DIR%\\kompile-model-staging*.jar\") do set CMD=java -Xmx4g -jar \"%%f\"\n");
        sb.append(")\n\n");

        sb.append("if not defined CMD (\n");
        sb.append("  echo No staging server found in %STAGING_DIR%\n");
        sb.append("  exit /b 1\n");
        sb.append(")\n\n");

        sb.append("echo Starting staging server on port 8090...\n");
        sb.append("start \"kompile-staging\" /B %CMD% --server --server.port=8090 ");
        sb.append("--kompile.staging.model-dir=\"%MODELS_DIR%\" ");
        sb.append("--kompile.staging.staging-dir=\"%MODELS_DIR%\\.staging\" ");
        sb.append("> \"%LOG_DIR%\\staging-server.log\" 2>&1\n");
        sb.append("echo Started staging server. Logs: %LOG_DIR%\\staging-server.log\n");

        return sb.toString();
    }

    private String batStartAllScript(int appPort, String jarName) {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\n");
        sb.append("REM Start all kompile services\n");
        sb.append("REM Generated by kompile init-project\n\n");

        sb.append("set SCRIPT_DIR=%~dp0\n\n");

        sb.append("echo === Starting kompile services ===\n");
        sb.append("echo.\n\n");

        sb.append("echo [1/3] Starting staging server...\n");
        sb.append("call \"%SCRIPT_DIR%start-staging.bat\"\n");
        sb.append("timeout /t 2 /nobreak >nul\n\n");

        sb.append("echo [2/3] Starting LLM serving subprocess...\n");
        sb.append("call \"%SCRIPT_DIR%start-serving.bat\"\n");
        sb.append("timeout /t 2 /nobreak >nul\n\n");

        sb.append("echo [3/3] Starting main application...\n");
        sb.append("call \"%SCRIPT_DIR%start-app.bat\"\n\n");

        sb.append("echo.\n");
        sb.append("echo === All services started ===\n");
        sb.append("echo   Staging:  http://localhost:8090\n");
        sb.append("echo   Serving:  http://localhost:8091\n");
        sb.append("echo   App:      http://localhost:").append(appPort).append("\n");

        return sb.toString();
    }

    private String batStopAllScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\n");
        sb.append("REM Stop all kompile services\n");
        sb.append("REM Generated by kompile init-project\n\n");

        sb.append("echo Stopping all kompile Java processes...\n");
        sb.append("REM This uses window titles set by start-*.bat scripts\n");
        sb.append("taskkill /FI \"WINDOWTITLE eq kompile-app\" /T /F 2>nul\n");
        sb.append("taskkill /FI \"WINDOWTITLE eq kompile-serving\" /T /F 2>nul\n");
        sb.append("taskkill /FI \"WINDOWTITLE eq kompile-staging\" /T /F 2>nul\n");
        sb.append("echo Done.\n");

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // README and AGENTS.md generation
    // ═══════════════════════════════════════════════════════════════════════════

    private void generateReadme(File projectDir, ModuleSelection modules) throws IOException {
        String jarName = projectName + "-" + instanceVersion + ".jar";
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(projectName).append("\n\n");
        sb.append("A kompile RAG application generated with `kompile init-project`.\n\n");

        // Quick start
        sb.append("## Quick Start\n\n");
        sb.append("```bash\n");
        sb.append("# Build with CUDA (default)\n");
        sb.append("mvn clean package -DskipTests\n\n");
        sb.append("# Build with CPU only\n");
        sb.append("mvn clean package -DskipTests -Pcpu\n\n");
        sb.append("# Launch with the kompile CLI (recommended)\n");
        sb.append("kompile web\n\n");
        sb.append("# Or use the generated scripts\n");
        sb.append("./scripts/start-all.sh\n");
        sb.append("```\n\n");
        sb.append("The application will start on port ").append(serverPort).append(". ");
        sb.append("Open http://localhost:").append(serverPort).append("/ in your browser.\n\n");

        // Architecture
        sb.append("## Architecture\n\n");
        sb.append("This project runs as three cooperating services:\n\n");
        sb.append("| Service | Port | Role |\n");
        sb.append("|---------|------|------|\n");
        sb.append("| **Staging Server** | 8090 | Model registry — downloads, caches, and serves model files |\n");
        sb.append("| **LLM Serving** | 8091 | Inference subprocess — runs SameDiff/ONNX models on ").append(backend).append(" |\n");
        sb.append("| **Main App** | ").append(serverPort).append(" | Orchestrator + Web UI — RAG pipeline, REST API, Angular frontend |\n");
        sb.append("\n");
        sb.append("The main app is a pure orchestrator and does **not** load ND4J or GPU libraries.\n");
        sb.append("All ML inference happens in the serving subprocess, which has the `").append(backend).append("` backend\n");
        sb.append("baked into this project's POM.\n\n");

        sb.append("```\n");
        sb.append("User -> Main App (:" + serverPort + ") -> LLM Serving (:8091) -> GPU/CPU\n");
        sb.append("                          |                      |\n");
        sb.append("                          v                      v\n");
        sb.append("                   Staging (:8090) <--- Model download/cache\n");
        sb.append("```\n\n");

        // Services
        sb.append("## Starting Services\n\n");

        sb.append("### Using the kompile CLI (recommended)\n\n");
        sb.append("`kompile web` is the managed way to launch. It handles the staging server,\n");
        sb.append("lifecycle management, and is visible to `kompile manage list/stop`.\n\n");
        sb.append("```bash\n");
        sb.append("cd ").append(projectName).append("\n\n");
        sb.append("# Launch app + staging server (foreground, Ctrl+C to stop)\n");
        sb.append("kompile web\n\n");
        sb.append("# Custom port\n");
        sb.append("kompile web --port=9090\n\n");
        sb.append("# Rebuild before starting\n");
        sb.append("kompile web --build\n\n");
        sb.append("# Check status / stop from another terminal\n");
        sb.append("kompile web status\n");
        sb.append("kompile web stop\n");
        sb.append("```\n\n");

        sb.append("### Using the CLI to interact\n\n");
        sb.append("Once the app is running, you can interact with it from the terminal:\n\n");
        sb.append("```bash\n");
        sb.append("# Interactive chat (connects to the running app)\n");
        sb.append("kompile chat\n\n");
        sb.append("# Ingest documents\n");
        sb.append("kompile app ingest file ./data/input_documents/my-rulebook.pdf\n");
        sb.append("kompile app ingest path ./data/input_documents/\n\n");
        sb.append("# Crawl a URL\n");
        sb.append("kompile app crawl url https://example.com/rules\n\n");
        sb.append("# Check indexing status\n");
        sb.append("kompile app index status\n");
        sb.append("```\n\n");

        sb.append("### Project Repository\n\n");
        sb.append("This directory contains a unified Kompile project manifest at `kompile.project.json`.\n");
        sb.append("Use `kompile project status` to inspect lifecycle, tags, repository backend, and registered components.\n");
        sb.append("Use `kompile project serve` to start the services implied by the registered scripts, workflows, models, and pipelines.\n");
        sb.append("Use `kompile project crawl` to select and run the managed crawl workflow or crawl profile for this project.\n\n");
        sb.append("```bash\n");
        sb.append("kompile project status\n");
        sb.append("kompile project model-list\n");
        sb.append("kompile project pipeline-list\n");
        sb.append("kompile project serve --dry-run\n");
        sb.append("kompile project crawl --dry-run\n");
        sb.append("```\n\n");
        sb.append("Project model and pipeline registry snapshots are written to `data/models/project-models.json` and `data/pipelines/project-pipelines.json`.\n");
        sb.append("The model-staging registry consumed by `kompile-model-staging` lives at `data/models/registry.json`.\n\n");
        sb.append("Local Kompile metadata lives under `.kompile/`. `kompile project open` records the active project in `.kompile/project/open.json`, while cache, session, and runtime state remain local.\n\n");
        if (hasInitialCrawl()) {
            sb.append("### Initial Crawl\n\n");
            sb.append("This project includes a managed initial crawl profile in `kompile.project.json`.\n\n");
            sb.append("```bash\n");
            sb.append("# Start the app first, then run the managed crawl profile\n");
            sb.append("./scripts/init-crawl.sh\n\n");
            sb.append("# Let the project command choose the crawl profile and optional services\n");
            sb.append("kompile project crawl --dry-run\n\n");
            sb.append("# Inspect or dry-run the generated crawl command\n");
            sb.append("kompile project crawl-list\n");
            sb.append("kompile project crawl-run --id initial-crawl --dry-run\n");
            sb.append("```\n\n");
        }
        if (hasPdfVlmWorkflow()) {
            sb.append("### PDF/VLM Workflow\n\n");
            sb.append("This project includes a managed PDF/VLM workflow for PDF directories such as TTRPG rulebooks.\n");
            sb.append("The workflow starts project services, waits for app health, then runs a multimodal PDF crawl.\n\n");
            sb.append("```bash\n");
            sb.append("# Start services and ingest the configured PDF directory with VLM processing\n");
            sb.append("./scripts/init-pdf-vlm.sh\n\n");
            sb.append("# Let the project command start services and choose the PDF/VLM crawl workflow\n");
            sb.append("kompile project crawl --dry-run\n\n");
            sb.append("# Inspect or dry-run the generated workflow\n");
            sb.append("kompile project workflow-list\n");
            sb.append("kompile project workflow-run --id pdf-vlm-ingest --dry-run\n");
            sb.append("kompile project crawl-run --id pdf-vlm-crawl --dry-run\n");
            sb.append("```\n\n");
        }

        sb.append("### Using shell scripts\n\n");
        sb.append("The `scripts/` directory contains standalone scripts that work without the\n");
        sb.append("kompile CLI installed.\n\n");
        sb.append("```bash\n");
        sb.append("./scripts/start-all.sh     # Start all (background)\n");
        sb.append("./scripts/stop-all.sh      # Stop all\n");
        sb.append("```\n\n");

        sb.append("### Running from JAR directly\n\n");
        sb.append("```bash\n");
        sb.append("java -jar target/").append(jarName).append("\n");
        sb.append("```\n\n");

        // Compute backend
        sb.append("## Compute Backend\n\n");
        sb.append("This project was initialised with **`").append(backend).append("`** as the primary backend.\n");
        sb.append("The backend is selected through Maven profiles in `pom.xml`. ");
        sb.append("CUDA is active by default for CUDA-capable presets; use `-Pcpu` to build a CPU-only JAR.\n\n");
        sb.append("```bash\n");
        sb.append("# Build with CUDA (default — requires CUDA toolkit on the build machine)\n");
        sb.append("mvn clean package -DskipTests\n\n");
        sb.append("# Build with CPU only\n");
        sb.append("mvn clean package -DskipTests -Pcpu\n");
        sb.append("```\n\n");
        sb.append("All subprocesses launched from this project's JAR automatically use the backend\n");
        sb.append("that was compiled in — no `-D` flags or environment variables needed.\n\n");
        sb.append("To create a project with a different default backend, re-run `kompile init-project --backend=nd4j-native`.\n\n");

        // Subprocess details
        sb.append("## Subprocess Types\n\n");
        sb.append("The main JAR supports subprocess routing via `--subprocess=TYPE`:\n\n");
        sb.append("| Type | Description |\n");
        sb.append("|------|-------------|\n");
        sb.append("| `serving` | LLM inference HTTP server (load/generate/status endpoints) |\n");
        sb.append("| `ingest` | Document ingestion (parsing, chunking, embedding, indexing) |\n");
        sb.append("| `vector-population` | Bulk vector index population |\n");
        sb.append("| `embedding` | Long-running embedding subprocess (stdin/stdout protocol) |\n");
        sb.append("| `model-init` | One-shot model initialization/download |\n");
        sb.append("| `vlm-test` | Vision-language model testing |\n");
        sb.append("| `training` | Model fine-tuning |\n");
        sb.append("\n");
        sb.append("Most subprocesses are launched automatically by the main app. The serving\n");
        sb.append("subprocess is started separately via `scripts/start-serving.sh` so it can\n");
        sb.append("run with full GPU access independently of the orchestrator.\n\n");

        sb.append("### Manual subprocess launch\n\n");
        sb.append("```bash\n");
        sb.append("java -jar target/").append(jarName).append(" --subprocess=serving --port=8091 --staging-url=http://localhost:8090\n");
        sb.append("java -jar target/").append(jarName).append(" --subprocess=ingest /path/to/args.json\n");
        sb.append("```\n\n");

        // Preset and modules
        sb.append("## Preset\n\n");
        sb.append("Generated with the `").append(presetName).append("` preset.\n\n");

        sb.append("## Enabled Modules\n\n");
        for (ModuleCatalog.Category cat : ModuleCatalog.Category.values()) {
            List<ModuleCatalog.ModuleEntry> catModules = modules.getByCategory(cat);
            if (!catModules.isEmpty()) {
                sb.append("- **").append(cat.name()).append("**: ");
                sb.append(catModules.stream().map(ModuleCatalog.ModuleEntry::getId).collect(Collectors.joining(", ")));
                sb.append("\n");
            }
        }
        sb.append("\n");

        // Project structure
        sb.append("## Project Structure\n\n");
        sb.append("```\n");
        sb.append(projectName).append("/\n");
        sb.append("  pom.xml                              # Maven project (backend=").append(backend).append(")\n");
        sb.append("  kompile.project.json                 # Unified project manifest, lifecycle, tags, components\n");
        sb.append("  scripts/\n");
        sb.append("    start-all.sh / .bat                # Start all services\n");
        sb.append("    stop-all.sh / .bat                 # Stop all services\n");
        sb.append("    start-app.sh / .bat                # Main app only\n");
        sb.append("    start-serving.sh / .bat            # LLM serving subprocess only\n");
        sb.append("    start-staging.sh                   # Staging server only\n");
        sb.append("    init-crawl.sh                      # Initial managed crawl profile, when configured\n");
        sb.append("    init-pdf-vlm.sh                    # PDF/VLM workflow, when configured\n");
        sb.append("  src/main/resources/\n");
        sb.append("    application.properties             # Application configuration\n");
        sb.append("  staging/                             # Staging server executable\n");
        sb.append("  data/\n");
        sb.append("    input_documents/                   # Documents for RAG ingestion\n");
        sb.append("    markdown/                          # Markdown note storage\n");
        sb.append("    sources/                           # Source repository metadata and staged source assets\n");
        sb.append("    chats/                             # Project-scoped chat exports\n");
        sb.append("    code-projects/                     # Context, AGENTS.md, and chats for external indexed code projects\n");
        sb.append("    crawls/                            # Crawl profile state and ingestion initialization metadata\n");
        sb.append("    workflows/                         # Workflow run metadata and orchestration outputs\n");
        sb.append("    artifacts/                         # Generated project artifacts\n");
        sb.append("    logs/                              # Service log files\n");
        sb.append("    pids/                              # PID files for service management\n");
        sb.append("    models/                            # Model files and descriptors; Git Xet-ready component\n");
        sb.append("    shared_files/                      # MCP shared filesystem root\n");
        sb.append("    prompt-templates/                  # Prompt template definitions\n");
        sb.append("  AGENTS.md                            # AI agent guide to this project\n");
        sb.append("```\n\n");

        // Configuration
        sb.append("## Configuration\n\n");
        sb.append("Runtime configuration is managed through the **web UI** (Developer Hub),\n");
        sb.append("not by editing `application.properties` directly.\n\n");
        sb.append("Settings are persisted as JSON files under `~/.kompile/config/`.\n\n");
        sb.append("The only settings in `application.properties` that you may want to edit:\n");
        sb.append("- LLM API keys (OpenAI, Anthropic, Gemini) — or set as environment variables\n");
        sb.append("- `server.port` — default ").append(serverPort).append("\n\n");

        // Logs
        sb.append("## Logs\n\n");
        sb.append("```bash\n");
        sb.append("# All logs\n");
        sb.append("tail -f data/logs/*.log\n\n");
        sb.append("# Specific service\n");
        sb.append("tail -f data/logs/app.log\n");
        sb.append("tail -f data/logs/serving-subprocess.log\n");
        sb.append("tail -f data/logs/staging-server.log\n");
        sb.append("```\n");

        writeFile(new File(projectDir, "README.md"), sb.toString());
    }

    /**
     * Generate AGENTS.md — a comprehensive guide for AI coding agents (Claude Code, Copilot,
     * Cursor, etc.) that describes the project architecture, subprocess lifecycle, backend
     * configuration, and how all the pieces fit together.
     */
    private void generateAgentsMd(File projectDir, ModuleSelection modules) throws IOException {
        String jarName = projectName + "-" + instanceVersion + ".jar";
        StringBuilder sb = new StringBuilder();

        sb.append("# AGENTS.md\n\n");
        sb.append("This file describes the ").append(projectName).append(" project architecture for AI coding agents.\n");
        sb.append("It was generated by `kompile init-project` and should be kept up to date.\n\n");

        // Architecture overview
        sb.append("## Architecture Overview\n\n");
        sb.append("This is a kompile RAG application with a **three-service architecture**:\n\n");
        sb.append("1. **Staging Server** (port 8090) — Model registry. Downloads, caches, and serves\n");
        sb.append("   model files (SameDiff `.sdnb`, tokenizers, ONNX models). Runs as a separate process.\n\n");
        sb.append("2. **LLM Serving Subprocess** (port 8091) — Inference server. Loads SameDiff/ONNX\n");
        sb.append("   models, runs them on `").append(backend).append("`, exposes REST endpoints for\n");
        sb.append("   load/unload/generate/status. This is where GPU/CPU compute happens.\n\n");
        sb.append("3. **Main Application** (port ").append(serverPort).append(") — Orchestrator. Spring Boot app with\n");
        sb.append("   Angular web UI. Handles RAG pipeline (ingestion, chunking, embedding, retrieval),\n");
        sb.append("   MCP tools, chat interface. Does NOT load ND4J or GPU libraries directly — delegates\n");
        sb.append("   all LLM inference to the serving subprocess via HTTP proxy.\n\n");

        // Key design decisions
        sb.append("## Key Design Decisions\n\n");
        sb.append("### Compute backend is a project-level setting\n\n");
        sb.append("The ND4J backend (`").append(backend).append("`) is set in `pom.xml` as a Maven property:\n\n");
        sb.append("```xml\n");
        sb.append("<backend>").append(backend).append("</backend>\n");
        sb.append("```\n\n");
        sb.append("This resolves at Maven build time to include the correct backend JAR on the classpath.\n");
        sb.append("**Never** use `-Dnd4j.backend=...` JVM flags — the POM is the source of truth.\n");
        sb.append("All subprocesses launched from the project's fat JAR inherit this backend automatically.\n\n");

        sb.append("### Main app is a pure orchestrator\n\n");
        sb.append("The main app (port ").append(serverPort).append(") does NOT initialize ND4J. It delegates LLM calls\n");
        sb.append("to the serving subprocess via `SameDiffLanguageModelImpl` in proxy mode. The proxy\n");
        sb.append("is activated when `kompile.llm.serving.url` is set in `application.properties`.\n\n");
        sb.append("The serving subprocess clears this property on startup so it always runs in\n");
        sb.append("DIRECT mode (never proxies to itself).\n\n");

        sb.append("### Subprocess isolation\n\n");
        sb.append("Each subprocess type runs in its own JVM with isolated ND4J state. This prevents\n");
        sb.append("GPU memory leaks, OOM crashes, or native library conflicts in one subprocess\n");
        sb.append("from affecting others. The main app spawns subprocesses using `ProcessBuilder`\n");
        sb.append("with classpath, environment, and ND4J config propagation.\n\n");

        // Subprocess types
        sb.append("## Subprocess Types\n\n");
        sb.append("All subprocesses are routed through `MainApplication.main()` via the\n");
        sb.append("`--subprocess=TYPE` flag. The dispatch happens BEFORE Spring Boot starts.\n\n");
        sb.append("| Type | Main Class | Description |\n");
        sb.append("|------|-----------|-------------|\n");
        sb.append("| `serving` | `ServingSubprocessMain` | HTTP server for LLM inference |\n");
        sb.append("| `ingest` | `IngestSubprocessMain` | Document ingestion pipeline |\n");
        sb.append("| `vector-population` | `VectorPopulationSubprocessMain` | Bulk vector indexing |\n");
        sb.append("| `embedding` | `EmbeddingSubprocessMain` | Persistent embedding service (stdin/stdout) |\n");
        sb.append("| `model-init` | `ModelInitSubprocessMain` | Model download and initialization |\n");
        sb.append("| `vlm-test` | `VlmTestSubprocessMain` | Vision-language model testing |\n");
        sb.append("| `training` | `TrainingSubprocessMain` | Model fine-tuning |\n\n");

        sb.append("### How subprocesses are launched\n\n");
        sb.append("1. Launcher service (e.g., `SubprocessIngestLauncher`) captures ND4J config\n");
        sb.append("2. Writes args to a temp JSON file (includes `nd4jConfigJson` field)\n");
        sb.append("3. Builds command: `java -jar <fat-jar> --subprocess=TYPE <args-file>`\n");
        sb.append("4. Propagates environment variables (`CUDA_VISIBLE_DEVICES`, `ND4J_*`, etc.)\n");
        sb.append("5. Subprocess reads args file, initializes ND4J via `Nd4jBackend.load()` (service\n");
        sb.append("   discovery finds CUDA or CPU backend on classpath), applies config, runs\n\n");

        // ND4J config propagation
        sb.append("## ND4J Configuration Propagation\n\n");
        sb.append("ND4J config flows from parent to subprocess via three mechanisms:\n\n");
        sb.append("1. **JSON args file** — `nd4jConfigJson` field carries full `Nd4jEnvironmentConfig`\n");
        sb.append("   (maxThreads, cudaDevice, memory limits, etc.)\n");
        sb.append("2. **Environment variables** — `CUDA_VISIBLE_DEVICES`, `OMP_NUM_THREADS`, `ND4J_*`\n");
        sb.append("   propagated via `propagateNd4jEnvironment()`\n");
        sb.append("3. **Service discovery** — `Nd4jBackend.load()` finds the backend JAR on classpath\n");
        sb.append("   (CUDA if `").append(backend).append("` is on classpath, CPU otherwise)\n\n");
        sb.append("Config is persisted at `~/.kompile/config/nd4j-environment-config.json` and\n");
        sb.append("managed via the web UI (Developer Hub > System > ND4J Environment).\n\n");

        // Scripts
        sb.append("## Scripts\n\n");
        sb.append("All scripts are in `scripts/`. Linux/macOS (`.sh`) and Windows (`.bat`).\n\n");
        sb.append("| Script | Purpose |\n");
        sb.append("|--------|---------|\n");
        sb.append("| `start-all.sh` | Start staging + serving + app in correct order |\n");
        sb.append("| `stop-all.sh` | Stop all running services via PID files |\n");
        sb.append("| `start-app.sh` | Start main app only (port ").append(serverPort).append(") |\n");
        sb.append("| `start-serving.sh` | Start LLM serving subprocess only (port 8091) |\n");
        sb.append("| `start-staging.sh` | Start staging server only (port 8090) |\n\n");
        sb.append("PID files are stored in `data/pids/`. Logs in `data/logs/`.\n\n");

        // LLM serving endpoints
        sb.append("## LLM Serving API (port 8091)\n\n");
        sb.append("| Endpoint | Method | Description |\n");
        sb.append("|----------|--------|-------------|\n");
        sb.append("| `/api/llm/status` | GET | Current model status (loaded, modelId, loadDurationMs) |\n");
        sb.append("| `/api/llm/load` | POST | Load a model: `{\"modelId\": \"...\"}` |\n");
        sb.append("| `/api/llm/generate` | POST | Generate text: `{\"prompt\": \"...\"}` |\n");
        sb.append("| `/api/llm/unload` | POST | Unload current model |\n\n");

        // RAG data flow
        sb.append("## RAG Data Flow\n\n");
        sb.append("```\n");
        sb.append("Documents -> Loaders -> Chunks -> Embeddings -> Vector Index\n");
        sb.append("Query -> Embed Query -> Vector Search -> Context -> LLM -> Response\n");
        sb.append("```\n\n");

        // Key files
        sb.append("## Key Files\n\n");
        sb.append("| File | Purpose |\n");
        sb.append("|------|---------|\n");
        sb.append("| `pom.xml` | Maven project — `<backend>` property controls CPU/CUDA |\n");
        sb.append("| `src/main/resources/application.properties` | Spring Boot config |\n");
        sb.append("| `~/.kompile/config/` | Runtime configs (managed by web UI) |\n");
        sb.append("| `data/logs/` | Service log files |\n");
        sb.append("| `data/models/` | Downloaded model cache |\n");
        sb.append("| `data/input_documents/` | Documents for RAG ingestion |\n\n");

        // Common tasks
        sb.append("## Common Tasks\n\n");
        sb.append("### Rebuild after code changes\n");
        sb.append("```bash\n");
        sb.append("mvn clean package -DskipTests\n");
        sb.append("./scripts/stop-all.sh && ./scripts/start-all.sh\n");
        sb.append("```\n\n");

        sb.append("### Check why inference is slow\n");
        sb.append("1. Check `data/logs/serving-subprocess.log` for backend type (CpuBackend vs CudaBackend)\n");
        sb.append("2. First inference after model load triggers DSP plan compilation — subsequent calls are fast\n");
        sb.append("3. The serving subprocess runs a warmup call during model load to pre-compile DSP plans\n\n");

        sb.append("### Ingest documents\n");
        sb.append("Upload via web UI or place files in `data/input_documents/`.\n");
        sb.append("Ingestion runs as a subprocess — check `data/logs/` for progress.\n\n");

        // Pitfalls
        sb.append("## Pitfalls\n\n");
        sb.append("- **Never** use `-Dnd4j.backend=...` — the backend is set at `init-project` time\n");
        sb.append("  and baked into the POM. To use a different backend, create a new project.\n");
        sb.append("- **Never** initialize ND4J in the main app — it's an orchestrator only\n");
        sb.append("- The serving subprocess clears `kompile.llm.serving.url` on startup to avoid\n");
        sb.append("  proxying to itself (handled in `ServingSubprocessMain`)\n");
        sb.append("- Runtime config is managed via the **web UI**, not `application.properties`\n");
        sb.append("- All subprocesses inherit the backend from the fat JAR classpath automatically\n");

        writeFile(new File(projectDir, "AGENTS.md"), sb.toString());
        System.out.println("  Generated AGENTS.md");
    }

    /**
     * Patches API keys collected by the wizard into application.properties.
     * Uncomments the placeholder lines and fills in the actual values.
     */
    private void patchApiKeys(File projectDir) throws IOException {
        boolean hasAnyKey = (wizardOpenaiApiKey != null && !wizardOpenaiApiKey.isEmpty())
                || (wizardAnthropicApiKey != null && !wizardAnthropicApiKey.isEmpty())
                || (wizardGeminiProjectId != null && !wizardGeminiProjectId.isEmpty());
        if (!hasAnyKey) return;

        File propsFile = new File(projectDir, "src/main/resources/application.properties");
        if (!propsFile.exists()) return;

        String content;
        try (BufferedReader reader = new BufferedReader(new FileReader(propsFile))) {
            content = reader.lines().collect(Collectors.joining("\n"));
        }

        if (wizardOpenaiApiKey != null && !wizardOpenaiApiKey.isEmpty()) {
            content = content.replace(
                    "# spring.ai.openai.api-key=${OPENAI_API_KEY}",
                    "spring.ai.openai.api-key=" + wizardOpenaiApiKey);
        }

        if (wizardAnthropicApiKey != null && !wizardAnthropicApiKey.isEmpty()) {
            content = content.replace(
                    "# spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}",
                    "spring.ai.anthropic.api-key=" + wizardAnthropicApiKey);
        }

        if (wizardGeminiProjectId != null && !wizardGeminiProjectId.isEmpty()) {
            content = content.replace(
                    "# spring.ai.vertex.ai.project-id=${GOOGLE_CLOUD_PROJECT_ID}",
                    "spring.ai.vertex.ai.project-id=" + wizardGeminiProjectId);
        }

        try (FileWriter writer = new FileWriter(propsFile)) {
            writer.write(content);
        }
    }

    /**
     * Install the staging server executable into the project's staging/ directory.
     * Searches for an existing global install at ~/.kompile/components/kompile-model-staging/
     * and copies the JAR (or symlinks the native image) into the project.
     */
    private void installStagingServer(File projectDir) {
        File stagingDir = new File(projectDir, "staging");

        // Check for native image in global install
        String home = System.getProperty("user.home");
        File globalComponentsDir = new File(home, ".kompile/components/kompile-model-staging");

        // 1. Look for native image
        File globalNative = new File(globalComponentsDir, "kompile-model-staging");
        if (globalNative.isFile() && globalNative.canExecute()) {
            File dest = new File(stagingDir, "kompile-model-staging");
            try {
                Files.createSymbolicLink(dest.toPath(), globalNative.toPath());
                System.out.println("  Linked staging server native image: " + dest);
                return;
            } catch (IOException e) {
                // Symlink failed (e.g., different filesystem), try copy
                try {
                    Files.copy(globalNative.toPath(), dest.toPath());
                    dest.setExecutable(true);
                    System.out.println("  Copied staging server native image: " + dest);
                    return;
                } catch (IOException e2) {
                    System.err.println("  Warning: Failed to copy native staging executable: " + e2.getMessage());
                }
            }
        }

        // 2. Look for JAR in versioned directories
        if (globalComponentsDir.isDirectory()) {
            File[] versionDirs = globalComponentsDir.listFiles(File::isDirectory);
            if (versionDirs != null && versionDirs.length > 0) {
                // Sort descending to get latest version
                java.util.Arrays.sort(versionDirs, (a, b) -> b.getName().compareTo(a.getName()));

                for (File versionDir : versionDirs) {
                    File[] jars = versionDir.listFiles(
                            (dir, name) -> name.startsWith("kompile-model-staging") && name.endsWith(".jar"));
                    if (jars != null && jars.length > 0) {
                        File sourceJar = jars[0];
                        File destJar = new File(stagingDir, sourceJar.getName());
                        try {
                            // Symlink to avoid 500MB copy
                            Files.createSymbolicLink(destJar.toPath(), sourceJar.toPath());
                            System.out.println("  Linked staging server JAR: " + destJar.getName());
                        } catch (IOException e) {
                            // Fallback: copy the JAR
                            try {
                                System.out.println("  Copying staging server JAR (" +
                                        (sourceJar.length() / (1024 * 1024)) + " MB)...");
                                Files.copy(sourceJar.toPath(), destJar.toPath());
                                System.out.println("  Copied staging server JAR: " + destJar.getName());
                            } catch (IOException e2) {
                                System.err.println("  Warning: Failed to copy staging JAR: " + e2.getMessage());
                            }
                        }
                        return;
                    }
                }
            }
        }

        System.out.println("  Note: No staging server found globally. Install with:");
        System.out.println("        kompile install kompile-model-staging");
        System.out.println("    Or place a JAR/native-image in: " + stagingDir.getAbsolutePath());
    }

    /**
     * Start the staging server from the project's staging/ directory before
     * launching the main application. Waits for it to become healthy.
     */
    private void startStagingServerIfPresent(File projectDir) {
        File stagingDir = new File(projectDir, "staging");
        int stagingPort = 8090;

        // Check if already running
        if (checkStagingHealth(stagingPort)) {
            System.out.println("  Staging server already running on port " + stagingPort);
            return;
        }

        // Find executable in project staging dir
        File nativeExe = new File(stagingDir, "kompile-model-staging");
        File[] jars = stagingDir.listFiles(
                (dir, name) -> name.startsWith("kompile-model-staging") && name.endsWith(".jar"));

        List<String> command = new ArrayList<>();
        File modelsDir = new File(projectDir, "data/models");

        if (nativeExe.isFile() && nativeExe.canExecute()) {
            command.add(nativeExe.getAbsolutePath());
        } else if (jars != null && jars.length > 0) {
            // Resolve the actual JAR path (follow symlinks)
            File jarFile = jars[0];
            try {
                jarFile = jarFile.toPath().toRealPath().toFile();
            } catch (IOException ignored) {}
            command.add("java");
            command.add("-Xmx4g");
            command.add("-jar");
            command.add(jarFile.getAbsolutePath());
        } else {
            System.out.println("  No staging server executable found, skipping auto-start.");
            System.out.println("  The main app will try to auto-start it if available.");
            return;
        }

        command.add("--server");
        command.add("--server.port=" + stagingPort);
        command.add("--kompile.staging.model-dir=" + modelsDir.getAbsolutePath());
        command.add("--kompile.staging.staging-dir=" +
                new File(modelsDir, ".staging").getAbsolutePath());

        System.out.println("  Starting staging server on port " + stagingPort + "...");

        try {
            File logDir = new File(projectDir, "data/logs");
            logDir.mkdirs();

            ProcessBuilder pb = new ProcessBuilder(command)
                    .redirectOutput(new File(logDir, "staging-server.out.log"))
                    .redirectError(new File(logDir, "staging-server.err.log"));
            Process process = pb.start();

            System.out.println("  Staging server PID: " + process.pid());

            // Wait up to 60 seconds for health
            boolean healthy = false;
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                if (checkStagingHealth(stagingPort)) {
                    healthy = true;
                    break;
                }
                Thread.sleep(2000);
            }

            if (healthy) {
                System.out.println("  Staging server is healthy on port " + stagingPort);
            } else {
                System.out.println("  Staging server started but health check timed out. " +
                        "It may still be initializing.");
            }
        } catch (Exception e) {
            System.err.println("  Warning: Failed to start staging server: " + e.getMessage());
            System.err.println("  The main app will try to auto-start it if available.");
        }
    }

    private boolean checkStagingHealth(int port) {
        try {
            java.net.URL url = new java.net.URL("http://localhost:" + port + "/actuator/health");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void printSummary(File projectDir, ModuleSelection modules) {
        System.out.println("\nProject initialized: " + projectDir.getAbsolutePath());
        System.out.println("  Preset: " + presetName);
        System.out.println("  Project manifest: " + new File(projectDir, KompileProjectStore.MANIFEST_FILE).getAbsolutePath());
        System.out.println("  Project backend: " + projectBackend);
        System.out.println("  Modules (" + modules.getAll().size() + "):");
        for (ModuleCatalog.Category cat : ModuleCatalog.Category.values()) {
            List<ModuleCatalog.ModuleEntry> catModules = modules.getByCategory(cat);
            if (!catModules.isEmpty()) {
                String names = catModules.stream().map(ModuleCatalog.ModuleEntry::getId).collect(Collectors.joining(", "));
                System.out.println("    " + cat.name() + ": " + names);
            }
        }
        if (dataSources != null && !dataSources.isEmpty()) {
            System.out.println("  Data sources (" + dataSources.size() + "):");
            for (String src : dataSources) {
                System.out.println("    " + src);
            }
            System.out.println("    Depth: " + crawlDepth
                    + (crawlMultimodal ? ", multimodal" : "")
                    + (crawlGraph ? ", graph extraction" : ""));
        }
    }

    private void printRunInstructions(File projectDir) {
        System.out.println("\nTo build and run:");
        System.out.println("  cd " + projectDir.getAbsolutePath());
        System.out.println("  mvn clean package -DskipTests");
        System.out.println("  kompile web                    # Recommended (managed lifecycle)");
        System.out.println("");
        System.out.println("Or use the generated scripts:");
        System.out.println("  ./scripts/start-all.sh         # Start all (background)");
        System.out.println("  ./scripts/stop-all.sh          # Stop all");
        System.out.println("");
        System.out.println("Once running, interact from the CLI:");
        System.out.println("  kompile chat                   # Interactive chat");
        System.out.println("  kompile app ingest file <path> # Ingest documents");
        System.out.println("  kompile app index status       # Check index status");
    }

    private int invokeMavenBuild(File projectDir, File pomFile) throws MavenInvocationException {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomFile);

        List<String> goals = new ArrayList<>();
        if (javacppPlatform != null && !javacppPlatform.isEmpty()) {
            goals.add("-Djavacpp.platform=" + javacppPlatform);
            if (javacppExtension != null && !javacppExtension.isEmpty()) {
                goals.add("-Djavacpp.platform.extension=" + javacppExtension);
            }
            goals.add("-Dorg.eclipse.python4j.numpyimport=false");
        }
        goals.add("clean");
        goals.add("package");
        request.setGoals(goals);

        Properties sysProps = new Properties();
        if (skipTests) sysProps.setProperty("skipTests", "true");
        request.setProperties(sysProps);

        Invoker invoker = new DefaultInvoker();
        File effectiveMaven = resolveMavenHome();
        if (effectiveMaven == null) {
            System.err.println("Maven not found. Searched: ~/.kompile/mvn, $M2_HOME, PATH. Use --mavenHome to specify.");
            return 1;
        }

        request.setMavenOpts("-Dfile.encoding=UTF-8");
        invoker.setMavenHome(effectiveMaven);
        invoker.setWorkingDirectory(projectDir);
        invoker.setOutputHandler(System.out::println);
        invoker.setErrorHandler(System.err::println);

        System.out.println("\nBuilding project...");
        System.out.println("  Directory: " + projectDir.getAbsolutePath());
        if (skipTests) System.out.println("  Tests: SKIPPED");

        InvocationResult result = invoker.execute(request);

        if (result.getExitCode() != 0) {
            System.err.println("Build failed! Exit code: " + result.getExitCode());
            if (result.getExecutionException() != null) {
                result.getExecutionException().printStackTrace(System.err);
            }
            return 1;
        }

        System.out.println("\nBuild successful!");
        File jar = new File(projectDir, "target/" + projectName + "-" + instanceVersion + ".jar");
        if (jar.exists()) {
            System.out.println("  JAR: " + jar.getAbsolutePath());
        }
        return 0;
    }

    private int startApplication(File projectDir, File pomFile) throws MavenInvocationException {
        System.out.println("\nStarting application on port " + serverPort + "...");
        System.out.println("  Press Ctrl+C to stop.\n");

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomFile);

        List<String> goals = new ArrayList<>();
        if (javacppPlatform != null && !javacppPlatform.isEmpty()) {
            goals.add("-Djavacpp.platform=" + javacppPlatform);
            if (javacppExtension != null && !javacppExtension.isEmpty()) {
                goals.add("-Djavacpp.platform.extension=" + javacppExtension);
            }
            goals.add("-Dorg.eclipse.python4j.numpyimport=false");
        }
        goals.add("spring-boot:run");
        request.setGoals(goals);

        Properties sysProps = new Properties();
        sysProps.setProperty("spring-boot.run.arguments", "--server.port=" + serverPort);
        request.setProperties(sysProps);

        Invoker invoker = new DefaultInvoker();
        File effectiveMaven = resolveMavenHome();
        if (effectiveMaven == null) {
            System.err.println("Maven not found. Searched: ~/.kompile/mvn, $M2_HOME, PATH. Use --mavenHome to specify.");
            return 1;
        }

        request.setMavenOpts("-Dfile.encoding=UTF-8");
        invoker.setMavenHome(effectiveMaven);
        invoker.setWorkingDirectory(projectDir);
        invoker.setOutputHandler(System.out::println);
        invoker.setErrorHandler(System.err::println);

        InvocationResult result = invoker.execute(request);
        return result.getExitCode();
    }

    /**
     * Starts the application in a background thread, waits for it to become healthy,
     * submits crawl jobs for configured data sources, then blocks on the app thread.
     */
    private int startApplicationWithCrawl(File projectDir, File pomFile) throws MavenInvocationException {
        System.out.println("\nStarting application on port " + serverPort + " (with initial data crawl)...");
        System.out.println("  Press Ctrl+C to stop.\n");

        AtomicInteger appExitCode = new AtomicInteger(-1);
        CountDownLatch appStarted = new CountDownLatch(1);

        // Launch the app in a daemon thread so we can submit crawls while it runs
        Thread appThread = new Thread(() -> {
            try {
                InvocationRequest request = new DefaultInvocationRequest();
                request.setPomFile(pomFile);

                List<String> goals = new ArrayList<>();
                if (javacppPlatform != null && !javacppPlatform.isEmpty()) {
                    goals.add("-Djavacpp.platform=" + javacppPlatform);
                    if (javacppExtension != null && !javacppExtension.isEmpty()) {
                        goals.add("-Djavacpp.platform.extension=" + javacppExtension);
                    }
                    goals.add("-Dorg.eclipse.python4j.numpyimport=false");
                }
                goals.add("spring-boot:run");
                request.setGoals(goals);

                Properties sysProps = new Properties();
                sysProps.setProperty("spring-boot.run.arguments", "--server.port=" + serverPort);
                request.setProperties(sysProps);

                Invoker invoker = new DefaultInvoker();
                File effectiveMaven = resolveMavenHome();
                if (effectiveMaven == null) {
                    System.err.println("Maven not found.");
                    appExitCode.set(1);
                    appStarted.countDown();
                    return;
                }

                request.setMavenOpts("-Dfile.encoding=UTF-8");
                invoker.setMavenHome(effectiveMaven);
                invoker.setWorkingDirectory(projectDir);
                invoker.setOutputHandler(line -> {
                    System.out.println(line);
                    // Detect Spring Boot started message to release the latch
                    if (line.contains("Started") && line.contains("in") && line.contains("seconds")) {
                        appStarted.countDown();
                    }
                });
                invoker.setErrorHandler(System.err::println);

                InvocationResult result = invoker.execute(request);
                appExitCode.set(result.getExitCode());
            } catch (MavenInvocationException e) {
                System.err.println("Failed to start application: " + e.getMessage());
                appExitCode.set(1);
            } finally {
                appStarted.countDown(); // ensure latch is released even on failure
            }
        }, "kompile-app-runner");
        appThread.setDaemon(true);
        appThread.start();

        // Wait for the app to start (up to 5 minutes)
        try {
            boolean started = appStarted.await(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!started) {
                System.err.println("Timeout waiting for application to start.");
                printCrawlInstructions();
            } else if (appExitCode.get() < 0) {
                // App is running, wait a moment for health endpoint to be ready
                Thread.sleep(3000);
                submitCrawlJobs(projectDir);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }

        // Block until the app exits (user hits Ctrl+C)
        try {
            appThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return appExitCode.get();
    }

    /**
     * Submits the generated crawl profile to the running kompile-app instance.
     */
    private void submitCrawlJobs(File projectDir) {
        String baseUrl = "http://localhost:" + serverPort;
        KompileHttpClient client = new KompileHttpClient(baseUrl);

        // Wait for health endpoint (app may still be initializing beans)
        System.out.println("\nWaiting for application to be ready...");
        boolean healthy = false;
        for (int i = 0; i < 30; i++) {
            if (client.isHealthy()) {
                healthy = true;
                break;
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (!healthy) {
            System.err.println("Application is not responding to health checks. Skipping initial crawl.");
            printCrawlInstructions();
            return;
        }

        System.out.println("Application is ready. Submitting initial crawl profile...\n");
        int exitCode = new CommandLine(new ProjectCommand()).execute(
                "crawl-run",
                "--root", projectDir.getAbsolutePath(),
                "--id", "initial-crawl",
                "--url", baseUrl);
        if (exitCode != 0) {
            System.err.println("Initial crawl profile failed to submit (exit code " + exitCode + ").");
            printCrawlInstructions();
        }
    }

    private void submitSingleCrawl(KompileHttpClient client, String source) throws Exception {
        Map<String, Object> config = new LinkedHashMap<>();

        boolean isWeb = source.startsWith("http://") || source.startsWith("https://");
        config.put("crawlerId", isWeb ? "web" : "filesystem");
        config.put("seed", source);
        config.put("maxDepth", crawlDepth);
        config.put("requestDelay", Duration.ofMillis(500).toString());
        config.put("timeout", Duration.ofHours(1).toString());
        config.put("sameDomainOnly", true);
        config.put("respectRobotsTxt", true);
        config.put("forceRecrawl", true);

        if (!isWeb) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("includeHidden", false);
            config.put("properties", properties);
        }

        if (crawlMultimodal) {
            config.put("pipelines", buildMultimodalPipelines());
            config.put("routeRules", buildMultimodalRouteRules());
            config.put("defaultPipelineId", "text");
        }

        String response = client.postString("/api/crawlers/start", config);
        System.out.println("  Crawl started: " + source);
        try {
            com.fasterxml.jackson.databind.JsonNode node = client.getObjectMapper().readTree(response);
            if (node.has("jobId")) {
                System.out.println("    Job ID: " + node.get("jobId").asText());
            }
        } catch (Exception ignored) {}
    }

    private void submitUnifiedCrawl(KompileHttpClient client) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", projectName + " initial crawl");

        List<Map<String, Object>> sourceList = new ArrayList<>();
        for (int i = 0; i < dataSources.size(); i++) {
            String source = dataSources.get(i);
            boolean isWeb = source.startsWith("http://") || source.startsWith("https://");
            Map<String, Object> srcConfig = new LinkedHashMap<>();
            srcConfig.put("label", isWeb ? extractHost(source) : Paths.get(source).getFileName().toString());
            srcConfig.put("sourceType", isWeb ? "WEB_CRAWL" : "DIRECTORY");
            srcConfig.put("pathOrUrl", source);
            srcConfig.put("maxDepth", crawlDepth);

            if (!isWeb) {
                srcConfig.put("properties", Map.of(
                        "followLinks", true,
                        "includeHidden", false
                ));
            }

            sourceList.add(srcConfig);
        }
        request.put("sources", sourceList);

        if (crawlGraph) {
            Map<String, Object> graphConfig = new LinkedHashMap<>();
            graphConfig.put("enabled", true);
            request.put("graphExtraction", graphConfig);
        }

        Map<String, Object> vectorConfig = new LinkedHashMap<>();
        vectorConfig.put("enabled", true);
        request.put("vectorIndex", vectorConfig);

        String response = client.postString("/api/unified-crawl/start", request);
        System.out.println("  Unified crawl started across " + dataSources.size() + " source(s)"
                + (crawlGraph ? " with graph extraction" : ""));
        try {
            com.fasterxml.jackson.databind.JsonNode node = client.getObjectMapper().readTree(response);
            if (node.has("jobId")) {
                System.out.println("    Job ID: " + node.get("jobId").asText());
            }
        } catch (Exception ignored) {}
    }

    private List<Map<String, Object>> buildMultimodalPipelines() {
        List<Map<String, Object>> pipelines = new ArrayList<>();

        Map<String, Object> textPipeline = new LinkedHashMap<>();
        textPipeline.put("pipelineId", "text");
        textPipeline.put("displayName", "Standard Text Pipeline");
        textPipeline.put("pipelineType", "STANDARD_TEXT");
        pipelines.add(textPipeline);

        Map<String, Object> vlmPipeline = new LinkedHashMap<>();
        vlmPipeline.put("pipelineId", "visual");
        vlmPipeline.put("displayName", "Vision/OCR Pipeline");
        vlmPipeline.put("pipelineType", "VLM");
        vlmPipeline.put("enableVlm", true);
        pipelines.add(vlmPipeline);

        Map<String, Object> tablePipeline = new LinkedHashMap<>();
        tablePipeline.put("pipelineId", "tables");
        tablePipeline.put("displayName", "Table-Aware Pipeline");
        tablePipeline.put("pipelineType", "TABLE_AWARE");
        pipelines.add(tablePipeline);

        Map<String, Object> emailPipeline = new LinkedHashMap<>();
        emailPipeline.put("pipelineId", "email");
        emailPipeline.put("displayName", "Email & Messaging Pipeline");
        emailPipeline.put("pipelineType", "STANDARD_TEXT");
        pipelines.add(emailPipeline);

        return pipelines;
    }

    private List<Map<String, Object>> buildMultimodalRouteRules() {
        List<Map<String, Object>> rules = new ArrayList<>();

        Map<String, Object> visualRule = new LinkedHashMap<>();
        visualRule.put("pipelineId", "visual");
        visualRule.put("priority", 10);
        visualRule.put("contentTypes", List.of(
                "application/pdf", "image/png", "image/jpeg", "image/gif",
                "image/webp", "image/tiff", "image/bmp", "image/svg+xml"));
        visualRule.put("fileExtensions", List.of(
                ".pdf", ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".tiff", ".tif", ".webp", ".svg"));
        rules.add(visualRule);

        Map<String, Object> tableRule = new LinkedHashMap<>();
        tableRule.put("pipelineId", "tables");
        tableRule.put("priority", 20);
        tableRule.put("contentTypes", List.of(
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/csv"));
        tableRule.put("fileExtensions", List.of(".xls", ".xlsx", ".csv"));
        rules.add(tableRule);

        Map<String, Object> emailRule = new LinkedHashMap<>();
        emailRule.put("pipelineId", "email");
        emailRule.put("priority", 30);
        emailRule.put("contentTypes", List.of(
                "message/rfc822", "application/mbox", "application/vnd.ms-outlook"));
        emailRule.put("fileExtensions", List.of(".eml", ".msg", ".mbox", ".emlx", ".pst"));
        rules.add(emailRule);

        return rules;
    }

    private String extractHost(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url.length() > 30 ? url.substring(0, 30) : url;
        }
    }

    private void printCrawlInstructions() {
        if (hasInitialCrawl()) {
            System.out.println("\nTo crawl your data sources after starting the app:");
            System.out.println("  kompile project crawl-run --id initial-crawl --url http://localhost:" + serverPort);
            System.out.println("\nEquivalent direct crawl commands:");
            for (String source : dataSources) {
                System.out.println("  kompile app crawl start " + shellQuote(source)
                        + " -d " + crawlDepth
                        + (crawlMultimodal ? " --multimodal" : "")
                        + (crawlGraph ? " --graph" : "")
                        + " --port " + serverPort);
            }
        }
        if (hasPdfVlmWorkflow()) {
            System.out.println("\nTo run the generated PDF/VLM workflow:");
            System.out.println("  ./scripts/init-pdf-vlm.sh");
            System.out.println("  kompile project workflow-run --id pdf-vlm-ingest --url http://localhost:" + serverPort);
            System.out.println("\nEquivalent direct PDF/VLM crawl commands:");
            for (String source : pdfVlmSources) {
                System.out.println("  kompile app crawl start " + shellQuote(source)
                        + " -d " + (crawlMaxDepth > 0 ? crawlMaxDepth : 1)
                        + " --include '**/*.pdf,*.pdf'"
                        + " --content-types application/pdf"
                        + " --multimodal"
                        + (firstNonBlank(pdfVlmModel, crawlVlmModel) == null
                            ? ""
                            : " --vlm-model " + shellQuote(firstNonBlank(pdfVlmModel, crawlVlmModel)))
                        + (crawlGraph ? " --graph" : "")
                        + " --port " + serverPort);
            }
        }
    }

    private File resolveMavenHome() {
        if (mavenHome != null && mavenHome.exists()) return mavenHome;
        return EnvironmentUtils.defaultMavenHome();
    }

    private boolean hasInitialCrawl() {
        return dataSources != null && !dataSources.isEmpty();
    }

    private boolean hasPdfVlmWorkflow() {
        return pdfVlmSources != null && !pdfVlmSources.isEmpty();
    }

    private String inferPdfVlmSourceType() {
        if (!hasPdfVlmWorkflow()) {
            return null;
        }
        boolean allPdfFiles = true;
        for (String source : pdfVlmSources) {
            if (source == null || !source.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                allPdfFiles = false;
                break;
            }
        }
        return allPdfFiles ? "file" : "directory";
    }

    /**
     * Detect the first available CLI agent on PATH.
     */
    private String detectAvailableCliAgent() {
        return ai.kompile.core.agent.CliAgentRegistry.detectFirstAvailable();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String shellQuote(String value) {
        if (value == null || value.isBlank()) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void writeFile(File file, String content) throws IOException {
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }
}
