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
package ai.kompile.cli.main.project;

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.main.GlobalBootstrap;
import ai.kompile.cli.main.app.CrawlCommand;
import ai.kompile.cli.main.chat.mcp.McpToolInjection;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.manage.ServiceManager;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectGitResult;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectOpenState;
import ai.kompile.project.KompileProjectStatus;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.KompileProjectWorkflow;
import ai.kompile.project.KompileProjectWorkflowStep;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static ai.kompile.cli.main.project.ProjectCommandUtils.firstNonBlank;
import static ai.kompile.cli.main.project.ProjectCommandUtils.normalizeEnum;
import static ai.kompile.cli.main.project.ProjectCommandUtils.resolveProjectRoot;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printManifest;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printServePlan;

/**
 * Picocli subcommand group for service lifecycle management.
 * Contains Status, Open, Start, Stop, and Serve subcommands.
 */
@Command(name = "service",
        mixinStandardHelpOptions = true,
        description = "Manage Kompile project service lifecycle.",
        subcommands = {
                ProjectServiceCommand.Status.class,
                ProjectServiceCommand.Open.class,
                ProjectServiceCommand.Start.class,
                ProjectServiceCommand.Stop.class,
                ProjectServiceCommand.Serve.class
        })
public class ProjectServiceCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @Command(name = "status", mixinStandardHelpOptions = true,
            description = "Show project manifest, component, Git, and Git Xet status.")
    public static class Status implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            return run(new KompileProjectStore(), root);
        }

        Integer run(KompileProjectStore store, File root) {
            Path resolved = resolveProjectRoot(store, root);
            KompileProjectStatus status = store.status(resolved);
            if (!status.isManifestPresent()) {
                System.out.println("No Kompile project manifest found at " + status.getManifestPath());
                System.out.println("Run: kompile project init --name <name>");
                return 1;
            }
            printManifest(store.load(resolved), status);
            return 0;
        }
    }

    @Command(name = "open", mixinStandardHelpOptions = true,
            description = "Open a Kompile project directory and optionally start the web UI.%n%n" +
                    "Uses the pre-installed kompile-app-main JAR from ~/.kompile/components/%n" +
                    "so no Maven build is required. The project's application.properties and%n" +
                    "data/ directories are passed to the running JAR.%n%n" +
                    "Examples:%n" +
                    "  kompile project open .                   # open + start web UI%n" +
                    "  kompile project open /path/to/project    # open a specific directory%n" +
                    "  kompile project open . --no-serve        # metadata only, don't start%n" +
                    "  kompile project open . --port=9090       # custom port%n")
    public static class Open implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--no-serve"},
                description = "Only write open metadata, do not start services.",
                defaultValue = "false")
        private boolean noServe;

        @Option(names = {"--port", "-p"},
                description = "Port for the main web application. Default: 8080",
                defaultValue = "8080")
        private int appPort;

        @Option(names = {"--staging-port"},
                description = "Port for the model staging server. Default: 8090",
                defaultValue = "8090")
        private int stagingPort;

        @Option(names = {"--no-staging"},
                description = "Skip starting the model staging server.",
                defaultValue = "false")
        private boolean noStaging;

        @Option(names = {"--no-open"},
                description = "Do not automatically open the browser.",
                defaultValue = "false")
        private boolean noOpenBrowser;

        @Option(names = {"--crawl"},
                description = "Force-run auto-ingest crawl after services are healthy (skip prompt).",
                defaultValue = "false")
        private boolean crawl;

        @Option(names = {"--no-crawl"},
                description = "Suppress the crawl prompt entirely.",
                defaultValue = "false")
        private boolean noCrawl;

        @Option(names = {"--jvm-args"},
                description = "Additional JVM arguments for the main application (comma-separated).",
                split = ",")
        private List<String> jvmArgs;

        @Override
        public Integer call() throws Exception {
            // 1. Open the project (write metadata)
            KompileProjectStore store = new KompileProjectStore();
            Path resolved = resolveProjectRoot(store, root);
            KompileProjectOpenState state = store.openProject(resolved);
            KompileProjectStatus status = store.status(resolved);
            KompileProjectManifest manifest = store.load(resolved);
            System.out.println("Opened Kompile project: " + state.getName());
            System.out.println("  ID: " + state.getProjectId());
            System.out.println("  Root: " + resolved);
            System.out.println("  Modules: " + manifest.getModules().size());

            if (noServe) {
                System.out.println("  Open state: " + status.getOpenStatePath());
                return 0;
            }

            // 2. Ensure global bootstrap
            GlobalBootstrap.ensureHomeDirectory();
            GlobalBootstrap.ensureConfigs();

            // 3. Find the kompile-app-main JAR from ~/.kompile/components/
            File appJar = findInstalledAppJar();
            if (appJar == null) {
                System.err.println("\nkompile-app-main not installed.");
                System.err.println("Install it with: kompile install kompile-app");
                return 1;
            }

            ServiceManager serviceManager = new ServiceManager();
            File projectDir = resolved.toFile();

            // 4. Check if already running on the target port
            if (serviceManager.checkHealth(appPort)) {
                String url = "http://localhost:" + appPort;
                System.out.println("\n  kompile-app-main is already running at " + url);
                if (!noOpenBrowser) {
                    openBrowserUrl(url);
                }
                return 0;
            }

            System.out.println("  App JAR: " + appJar.getAbsolutePath());
            System.out.println("  App port: " + appPort);

            String projectName = manifest.getName() != null ? manifest.getName() : projectDir.getName();
            String webInstanceName = projectName + "-web";
            String stagingInstanceName = projectName + "-staging";
            File logDir = new File(projectDir, "data/logs");
            logDir.mkdirs();

            // 5. Start staging server (unless --no-staging)
            Process stagingProcess = null;
            if (!noStaging) {
                File stagingJar = findInstalledStagingJar();
                if (stagingJar != null) {
                    if (serviceManager.checkHealth(stagingPort)) {
                        System.out.println("  Staging: already running on port " + stagingPort);
                    } else {
                        System.out.println("  Starting staging server on port " + stagingPort + "...");
                        List<String> stagingArgs = buildStagingArgs(projectDir, appPort);
                        stagingProcess = serviceManager.startProjectComponent(
                                stagingInstanceName, "kompile-model-staging", stagingJar,
                                stagingPort, projectDir, null, stagingArgs, logDir, false);
                        // Brief wait for staging to initialize
                        boolean stagingHealthy = serviceManager.waitForHealth(stagingPort, 60);
                        if (stagingHealthy) {
                            configureStagingCallback(stagingPort, appPort);
                            System.out.println("  Staging: running on port " + stagingPort
                                    + " (PID: " + stagingProcess.pid() + ")");
                        } else {
                            System.out.println("  Staging: started but health check timed out"
                                    + " (PID: " + stagingProcess.pid() + ")");
                        }
                    }
                } else {
                    System.out.println("  Staging: not installed (install with: kompile install kompile-model-staging)");
                }
            }

            // 5b. Auto-register project models with staging
            boolean stagingAvailableForModels = !noStaging && serviceManager.checkHealth(stagingPort);
            if (stagingAvailableForModels && !manifest.getModels().isEmpty()) {
                autoStageProjectModels(manifest.getModels(), stagingPort, projectDir);
            }

            // 5c. Auto-index coding projects
            if (!manifest.getCodingProjects().isEmpty()) {
                autoIndexCodingProjects(manifest.getCodingProjects());
            }

            // 6. Build app arguments — point at project's config and data
            List<String> appArgs = buildProjectAppArgs(projectDir, appPort, stagingPort, stagingProcess != null || serviceManager.checkHealth(stagingPort));

            // 7. Write .mcp.json so CLI MCP tools and external agents point at this project's backend.
            //    - "kompile" entry: stdio CLI MCP server with --url pointing at this backend
            //    - "kompile-app" entry: SSE direct connection to backend
            //    - "kompile-model-staging" entry: SSE to staging (if running)
            String backendUrl = "http://localhost:" + appPort;
            String sseUrl = backendUrl + "/mcp/sse";
            Path mcpJsonFile = McpToolInjection.injectTools(resolved, "claude", null); // stdio entry
            addStdioUrlArg(mcpJsonFile, "kompile", backendUrl);
            addSseEntryToMcpJson(mcpJsonFile, "kompile-app", sseUrl);
            if (!noStaging && serviceManager.checkHealth(stagingPort)) {
                addSseEntryToMcpJson(mcpJsonFile, "kompile-model-staging",
                        "http://localhost:" + stagingPort + "/mcp/sse");
            }
            System.out.println("  MCP config: " + mcpJsonFile);

            // 8. Update open state with runtime service info
            state.getMetadata().put("appPort", String.valueOf(appPort));
            state.getMetadata().put("appUrl", backendUrl);
            state.getMetadata().put("sseUrl", sseUrl);
            state.getMetadata().put("mcpConfigPath", mcpJsonFile != null ? mcpJsonFile.toString() : "");
            if (!noStaging) {
                state.getMetadata().put("stagingPort", String.valueOf(stagingPort));
                state.getMetadata().put("stagingUrl", "http://localhost:" + stagingPort);
            }
            state.getMetadata().put("appJar", appJar.getAbsolutePath());
            state.setUpdatedAt(Instant.now());
            try {
                JsonUtils.standardMapper()
                        .writeValue(store.openStatePath(resolved).toFile(), state);
            } catch (Exception e) {
                System.err.println("  Warning: could not update open state: " + e.getMessage());
            }

            // 9. Register shutdown hook
            final Process stagingRef = stagingProcess;
            final Path mcpJsonCleanup = mcpJsonFile;
            final KompileProjectStore storeRef = store;
            final Path resolvedRef = resolved;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down...");
                // Restore or remove .mcp.json
                try {
                    McpToolInjection.removeTools(mcpJsonCleanup);
                } catch (Exception e) {
                    System.err.println("  Warning: could not clean up .mcp.json: " + e.getMessage());
                }
                // Clear runtime metadata from open state
                clearRuntimeMetadata(storeRef, resolvedRef);
                if (stagingRef != null && stagingRef.isAlive()) {
                    stagingRef.destroy();
                    try { stagingRef.waitFor(); } catch (InterruptedException ignored) {}
                    System.out.println("  Staging server stopped.");
                }
                try {
                    InstanceRegistry.unregister(webInstanceName);
                    InstanceRegistry.unregister(stagingInstanceName);
                } catch (Exception ignored) {}
            }));

            // 10. Open browser after app starts
            if (!noOpenBrowser) {
                String url = "http://localhost:" + appPort;
                Thread browserThread = new Thread(() -> {
                    if (waitForAppReady(appPort, 120)) {
                        System.out.println("  Opening browser: " + url);
                        openBrowserUrl(url);
                    }
                });
                browserThread.setDaemon(true);
                browserThread.start();
            }

            // 10b. Crawl prompt — after app-main is healthy, ask user about document ingestion
            if (!noCrawl && !manifest.getCrawlProfiles().isEmpty()) {
                final int crawlAppPort = appPort;
                final KompileProjectManifest crawlManifest = manifest;
                Thread crawlThread = new Thread(() -> {
                    if (!waitForAppReady(crawlAppPort, 120)) return;
                    if (crawl) {
                        // --crawl flag: run immediately without prompting
                        triggerAutoCrawl(crawlAppPort, crawlManifest);
                        return;
                    }
                    // Interactive prompt
                    java.io.Console console = System.console();
                    if (console != null) {
                        System.out.println();
                        System.out.println("  Documents detected in this project.");
                        System.out.println("  Run a crawl to index them for RAG search?");
                        System.out.print("  [Y/n]: ");
                        String answer = console.readLine();
                        if (answer == null || answer.isBlank() || answer.trim().toLowerCase().startsWith("y")) {
                            triggerAutoCrawl(crawlAppPort, crawlManifest);
                        } else {
                            System.out.println("  To crawl later: kompile project workflow-run --id auto-ingest");
                        }
                    } else {
                        // Non-interactive: print manual command
                        System.out.println("\n  Documents detected. To index them: kompile project workflow-run --id auto-ingest");
                    }
                });
                crawlThread.setDaemon(true);
                crawlThread.start();
            }

            // 11. Start main app (foreground, blocks until Ctrl+C)
            System.out.println("\nStarting kompile-app-main on port " + appPort + "...");
            System.out.println("  Press Ctrl+C to stop.\n");

            Process appProcess = serviceManager.startProjectComponent(
                    webInstanceName, "kompile-app-main", appJar, appPort,
                    projectDir, jvmArgs, appArgs, null, true);

            System.out.println("  PID: " + appProcess.pid());

            int exitCode = appProcess.waitFor();

            // 12. Clean up
            McpToolInjection.removeTools(mcpJsonFile);
            clearRuntimeMetadata(store, resolved);
            InstanceRegistry.unregister(webInstanceName);
            if (stagingProcess != null) {
                InstanceRegistry.unregister(stagingInstanceName);
                if (stagingProcess.isAlive()) {
                    stagingProcess.destroy();
                }
            }

            return exitCode;
        }

        /**
         * Build staging server arguments with project-scoped directories and callback URL.
         *
         * @param projectDir the project root directory
         * @param appPort    the main app port (for callback URL)
         */
        static List<String> buildStagingArgs(File projectDir, int appPort) {
            List<String> stagingArgs = new ArrayList<>();
            stagingArgs.add("--server");
            File modelsDir = new File(projectDir, "data/models");
            modelsDir.mkdirs();
            stagingArgs.add("--kompile.staging.model-dir=" + modelsDir.getAbsolutePath());
            stagingArgs.add("--kompile.staging.staging-dir=" +
                    new File(modelsDir, ".staging").getAbsolutePath());
            stagingArgs.add("--kompile.staging.settings-dir=" +
                    new File(projectDir, "data").getAbsolutePath());
            stagingArgs.add("--kompile.staging.project-dir=" + projectDir.getAbsolutePath());
            stagingArgs.add("--kompile.staging.callback-url=http://localhost:" + appPort);
            return stagingArgs;
        }

        /**
         * Configure the staging server's callback URL via REST after it's healthy.
         * Non-fatal — staging works without it, just won't auto-notify app of model changes.
         *
         * @param stagingPort the staging server port
         * @param appPort     the main app port (callback target)
         */
        static void configureStagingCallback(int stagingPort, int appPort) {
            try {
                String settingsUrl = "http://localhost:" + stagingPort + "/api/staging/settings";
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();

                // GET current settings
                HttpRequest getReq = HttpRequest.newBuilder()
                        .uri(URI.create(settingsUrl))
                        .GET()
                        .build();
                HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
                if (getResp.statusCode() != 200) {
                    System.out.println("  Staging callback: could not read settings (HTTP " + getResp.statusCode() + ")");
                    return;
                }

                // Parse, update callback URL, PUT back
                ObjectMapper om = JsonUtils.standardMapper();
                ObjectNode settings =
                        (ObjectNode) om.readTree(getResp.body());
                settings.put("callbackUrl", "http://localhost:" + appPort);
                settings.put("autoReloadEnabled", true);

                HttpRequest putReq = HttpRequest.newBuilder()
                        .uri(URI.create(settingsUrl))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(settings)))
                        .build();
                HttpResponse<String> putResp = client.send(putReq, HttpResponse.BodyHandlers.ofString());
                if (putResp.statusCode() == 200) {
                    System.out.println("  Staging callback: configured → http://localhost:" + appPort);
                } else {
                    System.out.println("  Staging callback: update failed (HTTP " + putResp.statusCode() + ")");
                }
            } catch (Exception e) {
                System.out.println("  Staging callback: could not configure (" + e.getMessage() + ")");
            }
        }

        /**
         * Build application arguments that point the pre-installed JAR at this project's
         * configuration and data directories.
         * <p>
         * The key property is {@code kompile.data.dir} — all config services resolve their
         * JSON config files relative to this directory. Setting it to the project root makes
         * all configs (app-index, pipeline, nd4j-environment, feature-flags, etc.) per-project.
         *
         * @param projectDir       the project root directory
         * @param appPort          the application port (unused currently, reserved for future use)
         * @param stagingPort      the staging server port
         * @param stagingAvailable whether staging is running
         */
        static List<String> buildProjectAppArgs(File projectDir, int appPort, int stagingPort, boolean stagingAvailable) {
            List<String> args = new ArrayList<>();

            // Per-project config: all config services read from <projectDir>/config/
            args.add("--kompile.data.dir=" + projectDir.getAbsolutePath());

            // Point Spring Boot at the project's application.properties
            File propsFile = new File(projectDir, "src/main/resources/application.properties");
            if (propsFile.isFile()) {
                args.add("--spring.config.additional-location=file:" + propsFile.getAbsolutePath());
            }

            // Model and data directories — eagerly create so args are always passed
            File modelsDir = new File(projectDir, "data/models");
            modelsDir.mkdirs();
            args.add("--kompile.staging.model-dir=" + modelsDir.getAbsolutePath());

            // Document sources
            File uploadsDir = new File(projectDir, "data/input_documents/uploads");
            if (uploadsDir.isDirectory()) {
                args.add("--app.document.uploads-path=" + uploadsDir.getAbsolutePath());
            }

            // Shared files root
            File sharedFiles = new File(projectDir, "data/shared_files");
            if (sharedFiles.isDirectory()) {
                args.add("--mcp.filesystem.roots.default.path=" + sharedFiles.getAbsolutePath());
            }

            // MCP config directory (so backend reads project-level mcp-config.json)
            File mcpConfigDir = new File(projectDir, "data");
            args.add("--kompile.mcp.config.path=" + mcpConfigDir.getAbsolutePath());

            // Staging server connection
            if (stagingAvailable) {
                args.add("--kompile.staging.url=http://localhost:" + stagingPort);
                args.add("--kompile.staging.port=" + stagingPort);
            }

            return args;
        }

        /**
         * Add {@code --url <backendUrl>} to a stdio MCP server entry's args in .mcp.json.
         * This tells the CLI stdio MCP server which backend instance to connect to,
         * avoiding the port auto-probe (which would miss non-standard ports).
         */
        static void addStdioUrlArg(Path mcpJsonFile, String serverName, String backendUrl) {
            if (mcpJsonFile == null || !Files.exists(mcpJsonFile)) return;
            try {
                ObjectMapper om = JsonUtils.standardMapper();
                ObjectNode root =
                        (ObjectNode) om.readTree(Files.readString(mcpJsonFile));
                JsonNode servers = root.path("mcpServers").path(serverName);
                if (servers.isMissingNode() || !servers.has("args")) return;
                ArrayNode args =
                        (ArrayNode) servers.get("args");
                args.add("--url");
                args.add(backendUrl);
                Files.writeString(mcpJsonFile, om.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            } catch (Exception e) {
                System.err.println("  Warning: could not add --url to " + serverName + " args: " + e.getMessage());
            }
        }

        /**
         * Add an SSE MCP server entry to an existing .mcp.json file.
         * Used to register the kompile-app backend and staging server alongside the
         * CLI stdio entry that McpToolInjection already wrote.
         */
        static void addSseEntryToMcpJson(Path mcpJsonFile, String serverName, String sseUrl) {
            if (mcpJsonFile == null || !Files.exists(mcpJsonFile)) return;
            try {
                ObjectMapper om = JsonUtils.standardMapper();
                ObjectNode root =
                        (ObjectNode) om.readTree(Files.readString(mcpJsonFile));
                ObjectNode mcpServers;
                if (root.has("mcpServers") && root.get("mcpServers").isObject()) {
                    mcpServers = (ObjectNode) root.get("mcpServers");
                } else {
                    mcpServers = root.putObject("mcpServers");
                }
                ObjectNode entry = mcpServers.putObject(serverName);
                entry.put("type", "sse");
                entry.put("url", sseUrl);
                Files.writeString(mcpJsonFile, om.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            } catch (Exception e) {
                System.err.println("  Warning: could not add " + serverName + " to .mcp.json: " + e.getMessage());
            }
        }

        /**
         * Remove runtime service metadata from the open state file so stale port/URL
         * information isn't left behind after the server shuts down.
         */
        static void clearRuntimeMetadata(KompileProjectStore store, Path projectRoot) {
            try {
                Optional<KompileProjectOpenState> opt = store.readOpenState(projectRoot);
                if (opt.isEmpty()) return;
                KompileProjectOpenState state = opt.get();
                Map<String, String> meta = state.getMetadata();
                if (meta == null) return;
                meta.remove("appPort");
                meta.remove("appUrl");
                meta.remove("sseUrl");
                meta.remove("mcpConfigPath");
                meta.remove("stagingPort");
                meta.remove("stagingUrl");
                meta.remove("appJar");
                state.setUpdatedAt(Instant.now());
                JsonUtils.standardMapper()
                        .writeValue(store.openStatePath(projectRoot).toFile(), state);
            } catch (Exception e) {
                System.err.println("  Warning: could not clear runtime metadata: " + e.getMessage());
            }
        }

        /**
         * Find the kompile-app-main JAR from global install locations.
         * Delegates to ComponentRegistry.findInstalledJar() which searches
         * dist, canonical, exec, any-version, and native exe locations.
         */
        static File findInstalledAppJar() {
            return new ComponentRegistry().findInstalledJar(ComponentRegistry.KOMPILE_APP_MAIN);
        }

        /**
         * Find the kompile-model-staging JAR from global install locations.
         * Delegates to ComponentRegistry.findInstalledJar() which searches
         * dist, canonical, exec, any-version, and native exe locations.
         */
        static File findInstalledStagingJar() {
            return new ComponentRegistry().findInstalledJar(ComponentRegistry.KOMPILE_MODEL_STAGING);
        }

        /**
         * Auto-index coding projects that have autoIndex=true.
         * Runs LocalCodeIndexer.index() for each, which is incremental
         * (only re-parses files changed since last run).
         */
        static void autoIndexCodingProjects(List<KompileCodingProject> codingProjects) {
            for (KompileCodingProject cp : codingProjects) {
                if (!cp.isAutoIndex()) continue;
                String projectId = firstNonBlank(cp.getCodeProjectId(), cp.getId(), cp.getName());
                String rootPath = cp.getRootPath();
                if (projectId == null || rootPath == null) continue;

                Path root = Path.of(rootPath);
                if (!Files.isDirectory(root)) {
                    System.out.println("  Code index: skipping " + projectId + " (root not found: " + rootPath + ")");
                    continue;
                }

                System.out.println("  Indexing code project: " + projectId + " (" + rootPath + ")");
                try {
                    LocalCodeIndexer indexer = new LocalCodeIndexer();
                    LocalCodeIndexer.IndexResult result = indexer.index(
                            root, projectId, cp.getIncludePatterns(), cp.getExcludePatterns(), System.out);
                    System.out.println("    Indexed " + result.filesProcessed() + " files, "
                            + result.entitiesFound() + " entities"
                            + (result.filesSkipped() > 0 ? " (" + result.filesSkipped() + " unchanged)" : ""));
                } catch (Exception e) {
                    System.out.println("    Index failed: " + e.getMessage());
                }
            }
        }

        /**
         * Automatically register project models with the staging server.
         * For each model in the project manifest:
         *   1. Check if staging already has it (skip if so)
         *   2. Try catalog staging first (for well-known model IDs)
         *   3. Fall back to local file staging if model files exist on disk
         */
        static void autoStageProjectModels(List<KompileProjectModel> models, int stagingPort, File projectDir) {
            String stagingBase = "http://localhost:" + stagingPort + "/api/staging";
            System.out.println("  Auto-registering " + models.size() + " model(s) with staging...");

            for (KompileProjectModel model : models) {
                String modelId = model.getModelId();
                if (modelId == null || modelId.isBlank()) continue;

                try {
                    // Check if already staged
                    java.net.HttpURLConnection statusConn = (java.net.HttpURLConnection)
                            new java.net.URL(stagingBase + "/status/" + modelId).openConnection();
                    statusConn.setConnectTimeout(3000);
                    statusConn.setReadTimeout(3000);
                    int statusCode = statusConn.getResponseCode();
                    if (statusCode == 200) {
                        System.out.println("    " + modelId + ": already in staging, skipping");
                        statusConn.disconnect();
                        continue;
                    }
                    statusConn.disconnect();
                } catch (Exception e) {
                    // Not found or error — proceed to stage
                }

                String framework = model.getMetadata().getOrDefault("registry.framework", "onnx");
                String modelFile = model.getMetadata().getOrDefault("registry.modelFile", "");

                // Try catalog staging first
                boolean catalogStaged = false;
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL(stagingBase + "/stage/catalog/" + modelId + "?autoPromote=true").openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    int rc = conn.getResponseCode();
                    if (rc == 202 || rc == 200) {
                        System.out.println("    " + modelId + ": staging from catalog (async)");
                        catalogStaged = true;
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    // Catalog staging failed — try local
                }

                // If catalog didn't work, try local file staging
                if (!catalogStaged && model.getPath() != null) {
                    Path localModelDir = projectDir.toPath().resolve(model.getPath());
                    Path localModelFile = modelFile.isEmpty()
                            ? localModelDir
                            : localModelDir.resolve(modelFile);

                    if (Files.exists(localModelFile)) {
                        try {
                            String json = String.format(
                                    "{\"modelId\":\"%s\",\"inputPath\":\"%s\",\"format\":\"%s\",\"autoPromote\":true}",
                                    modelId,
                                    localModelFile.toAbsolutePath().toString().replace("\\", "\\\\"),
                                    framework);
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                                    new java.net.URL(stagingBase + "/convert").openConnection();
                            conn.setRequestMethod("POST");
                            conn.setRequestProperty("Content-Type", "application/json");
                            conn.setDoOutput(true);
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(30000);
                            conn.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
                            int rc = conn.getResponseCode();
                            if (rc == 202 || rc == 200) {
                                System.out.println("    " + modelId + ": staging from local path " + localModelFile);
                            } else {
                                System.out.println("    " + modelId + ": local staging returned " + rc);
                            }
                            conn.disconnect();
                        } catch (Exception e) {
                            System.out.println("    " + modelId + ": local staging failed: " + e.getMessage());
                        }
                    } else {
                        System.out.println("    " + modelId + ": model file not found at " + localModelFile);
                    }
                }
            }
        }

        /**
         * Wait for the app-main server to be ready. Tries /actuator/health first,
         * then falls back to a simple HTTP GET on the root path (some installs
         * don't bundle Spring Boot Actuator).
         */
        static boolean waitForAppReady(int port, int timeoutSeconds) {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL("http://localhost:" + port + "/actuator/health").openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    int rc = conn.getResponseCode();
                    conn.disconnect();
                    if (rc == 200) return true;
                } catch (Exception ignored) {}
                // Fallback: simple root GET — if server responds at all, it's up
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL("http://localhost:" + port + "/").openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    int rc = conn.getResponseCode();
                    conn.disconnect();
                    if (rc >= 200 && rc < 500) return true;
                } catch (Exception ignored) {}
                try { Thread.sleep(2000); } catch (InterruptedException e) { return false; }
            }
            return false;
        }

        /**
         * Trigger auto-ingest crawl via the unified crawl REST API.
         * Builds a UnifiedCrawlRequest from manifest crawl profiles and POSTs
         * to /api/unified-crawl/start. Runs asynchronously on the server.
         *
         * When the crawl profile is multimodal (VLM-enabled), includes a
         * processingRoute block with pdfRoutingMode=AUTO so the backend
         * classifies each PDF — text-only PDFs go through standard text
         * extraction while scanned/image PDFs route to the VLM pipeline.
         */
        static void triggerAutoCrawl(int appPort, KompileProjectManifest manifest) {
            System.out.println("  Starting document crawl...");
            for (KompileProjectCrawlProfile profile : manifest.getCrawlProfiles()) {
                String profileId = profile.getId() != null ? profile.getId() : "auto-ingest";
                try {
                    List<String> args = ProjectCrawlCommand.buildCrawlArgs(profile, "http://localhost:" + appPort, null, null);
                    int exitCode = new CommandLine(new CrawlCommand()).execute(args.toArray(String[]::new));
                    if (exitCode == 0) {
                        if (profile.isMultimodal()) {
                            System.out.println("    Crawl '" + profileId + "' started (async, multi-route PDF: text->extraction, scanned->VLM).");
                        } else {
                            System.out.println("    Crawl '" + profileId + "' started (async).");
                        }
                    } else {
                        System.out.println("    Crawl '" + profileId + "' failed with exit code " + exitCode);
                    }
                } catch (Exception e) {
                    System.out.println("    Crawl '" + profileId + "' failed: " + e.getMessage());
                }
            }
        }

        private static void openBrowserUrl(String url) {
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", url);
                } else if (os.contains("win")) {
                    pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
                } else {
                    pb = new ProcessBuilder("xdg-open", url);
                }
                Process p = pb.redirectErrorStream(true).start();
                p.getInputStream().transferTo(OutputStream.nullOutputStream());
                p.waitFor(10, TimeUnit.SECONDS);
                p.destroyForcibly();
            } catch (Exception ignored) {}
        }
    }

    @Command(name = "start", aliases = "launch", mixinStandardHelpOptions = true,
            description = "Start a Kompile project — find the installed app JAR, point it at this " +
                    "project's config/data, start staging and the main app.%n%n" +
                    "This is the simple way to launch a project. For the full interactive experience%n" +
                    "(browser open, crawl prompts, MCP injection), use 'kompile project open'.%n%n" +
                    "Examples:%n" +
                    "  kompile project start%n" +
                    "  kompile project start --port 9090%n" +
                    "  kompile project start --root /path/to/project --crawl%n" +
                    "  kompile project start --no-staging --port 8082%n")
    public static class Start implements Callable<Integer> {

        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--port", "-p"},
                description = "Port for the main web application. Default: 8080",
                defaultValue = "8080")
        private int appPort;

        @Option(names = {"--staging-port"},
                description = "Port for the model staging server. Default: 8090",
                defaultValue = "8090")
        private int stagingPort;

        @Option(names = {"--no-staging"},
                description = "Skip starting the model staging server.",
                defaultValue = "false")
        private boolean noStaging;

        @Option(names = {"--crawl"},
                description = "Auto-run crawl profiles after app is healthy.",
                defaultValue = "false")
        private boolean crawl;

        @Option(names = {"--jvm-args"},
                description = "Additional JVM arguments for the main application (comma-separated).",
                split = ",")
        private List<String> jvmArgs;

        @Override
        public Integer call() throws Exception {
            // 1. Resolve project and load manifest
            KompileProjectStore store = new KompileProjectStore();
            Path resolved = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = store.load(resolved);
            File projectDir = resolved.toFile();
            String projectName = manifest.getName() != null ? manifest.getName() : projectDir.getName();

            System.out.println("Starting project: " + projectName);
            System.out.println("  Root: " + resolved);

            // 2. Ensure global bootstrap
            GlobalBootstrap.ensureHomeDirectory();
            GlobalBootstrap.ensureConfigs();

            // 3. Find installed app (JAR or native executable)
            File appJar = Open.findInstalledAppJar();
            if (appJar == null) {
                System.err.println("\nkompile-app-main not installed.");
                System.err.println("Install it with: kompile install kompile-app");
                return 1;
            }

            boolean appIsNative = !appJar.getName().endsWith(".jar");
            if (appIsNative && jvmArgs != null && !jvmArgs.isEmpty()) {
                System.err.println("  Warning: --jvm-args ignored for native executable: " + appJar.getName());
            }

            ServiceManager serviceManager = new ServiceManager();

            // 4. Check if already running
            if (serviceManager.checkHealth(appPort)) {
                System.out.println("  Already running at http://localhost:" + appPort);
                return 0;
            }

            System.out.println("  " + (appIsNative ? "Executable" : "JAR") + ": " + appJar.getName());
            System.out.println("  Port: " + appPort);

            File logDir = new File(projectDir, "data/logs");
            logDir.mkdirs();

            // 5. Start staging server (background)
            Process stagingProcess = null;
            if (!noStaging) {
                File stagingJar = Open.findInstalledStagingJar();
                if (stagingJar != null) {
                    if (serviceManager.checkHealth(stagingPort)) {
                        System.out.println("  Staging: already running on port " + stagingPort);
                    } else {
                        System.out.println("  Starting staging on port " + stagingPort + "...");
                        List<String> stagingArgs = Open.buildStagingArgs(projectDir, appPort);
                        stagingProcess = serviceManager.startProjectComponent(
                                projectName + "-staging", "kompile-model-staging", stagingJar,
                                stagingPort, projectDir, null, stagingArgs, logDir, false);
                        boolean stagingHealthy = serviceManager.waitForHealth(stagingPort, 60);
                        if (stagingHealthy) {
                            Open.configureStagingCallback(stagingPort, appPort);
                            System.out.println("  Staging: ready (PID: " + stagingProcess.pid() + ")");
                        } else {
                            System.out.println("  Staging: started but health check timed out (PID: " + stagingProcess.pid() + ")");
                        }
                    }
                } else {
                    System.out.println("  Staging: not installed (install with: kompile install kompile-model-staging)");
                }
            }

            // 6. Auto-register models with staging
            boolean stagingAvailableForModels = !noStaging && serviceManager.checkHealth(stagingPort);
            if (stagingAvailableForModels && !manifest.getModels().isEmpty()) {
                Open.autoStageProjectModels(manifest.getModels(), stagingPort, projectDir);
            }

            // 7. Auto-index coding projects
            if (!manifest.getCodingProjects().isEmpty()) {
                Open.autoIndexCodingProjects(manifest.getCodingProjects());
            }

            // 8. Build app arguments
            List<String> appArgs = Open.buildProjectAppArgs(projectDir, appPort, stagingPort,
                    stagingProcess != null || serviceManager.checkHealth(stagingPort));

            // 9. Shutdown hook
            final Process stagingRef = stagingProcess;
            String webInstanceName = projectName + "-web";
            String stagingInstanceName = projectName + "-staging";
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down...");
                if (stagingRef != null && stagingRef.isAlive()) {
                    stagingRef.destroy();
                    try { stagingRef.waitFor(); } catch (InterruptedException ignored) {}
                    System.out.println("  Staging stopped.");
                }
                try {
                    InstanceRegistry.unregister(webInstanceName);
                    InstanceRegistry.unregister(stagingInstanceName);
                } catch (Exception ignored) {}
            }));

            // 10. Crawl trigger (after app starts, in background)
            if (crawl && !manifest.getCrawlProfiles().isEmpty()) {
                final int crawlPort = appPort;
                final KompileProjectManifest crawlManifest = manifest;
                Thread crawlThread = new Thread(() -> {
                    if (!Open.waitForAppReady(crawlPort, 120)) return;
                    Open.triggerAutoCrawl(crawlPort, crawlManifest);
                });
                crawlThread.setDaemon(true);
                crawlThread.start();
            }

            // 11. Start app (foreground — blocks until Ctrl+C)
            System.out.println("\nStarting kompile-app-main...");
            System.out.println("  http://localhost:" + appPort);
            System.out.println("  Press Ctrl+C to stop.\n");

            Process appProcess = serviceManager.startProjectComponent(
                    webInstanceName, "kompile-app-main", appJar, appPort,
                    projectDir, jvmArgs, appArgs, null, true);

            int exitCode = appProcess.waitFor();

            // 12. Cleanup
            InstanceRegistry.unregister(webInstanceName);
            if (stagingProcess != null) {
                InstanceRegistry.unregister(stagingInstanceName);
                if (stagingProcess.isAlive()) {
                    stagingProcess.destroy();
                }
            }

            return exitCode;
        }
    }

    @Command(name = "stop", aliases = "shutdown", mixinStandardHelpOptions = true,
            description = "Stop a running Kompile project — kills the app and staging processes.%n%n" +
                    "Finds running instances by project directory (from the instance registry at%n" +
                    "~/.kompile/instances/) and gracefully shuts them down. Also cleans up MCP%n" +
                    "config and open-state metadata.%n%n" +
                    "Examples:%n" +
                    "  kompile project stop%n" +
                    "  kompile project stop --root /path/to/project%n" +
                    "  kompile project stop --port 8082%n" +
                    "  kompile project stop --all%n")
    public static class Stop implements Callable<Integer> {

        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--port", "-p"},
                description = "Stop the instance running on this port (ignores --root).")
        private Integer port;

        @Option(names = {"--all"},
                description = "Stop all registered Kompile instances.",
                defaultValue = "false")
        private boolean all;

        @Option(names = {"--force", "-f"},
                description = "Force-kill processes instead of graceful shutdown.",
                defaultValue = "false")
        private boolean force;

        @Override
        public Integer call() throws Exception {
            ServiceManager serviceManager = new ServiceManager();
            List<InstanceInfo> targets = new ArrayList<>();

            if (all) {
                // Stop everything
                targets.addAll(InstanceRegistry.listAll());
                if (targets.isEmpty()) {
                    System.out.println("No running instances found.");
                    return 0;
                }
                System.out.println("Stopping all " + targets.size() + " instance(s)...");
            } else if (port != null) {
                // Stop by port
                InstanceInfo info = InstanceRegistry.findByPort(port);
                if (info != null) {
                    targets.add(info);
                } else {
                    System.err.println("No registered instance on port " + port);
                    return 1;
                }
            } else {
                // Stop by project directory
                KompileProjectStore store = new KompileProjectStore();
                Path resolved = resolveProjectRoot(store, root);
                String projectDir = resolved.toFile().getAbsolutePath();

                targets.addAll(InstanceRegistry.findByProjectDir(projectDir));

                if (targets.isEmpty()) {
                    // Fallback: look for instances whose name matches the project name
                    KompileProjectManifest manifest = null;
                    try {
                        manifest = store.load(resolved);
                    } catch (Exception ignored) {}

                    if (manifest != null) {
                        String projectName = manifest.getName() != null ? manifest.getName() : resolved.toFile().getName();
                        for (InstanceInfo info : InstanceRegistry.listAll()) {
                            if (info.getName() != null && info.getName().startsWith(projectName)) {
                                targets.add(info);
                            }
                        }
                    }
                }

                if (targets.isEmpty()) {
                    System.out.println("No running instances found for project at " + resolved);
                    // Show all registered instances as a hint
                    List<InstanceInfo> allInstances = InstanceRegistry.listAll();
                    if (!allInstances.isEmpty()) {
                        System.out.println("\nRegistered instances:");
                        for (InstanceInfo info : allInstances) {
                            boolean alive = ProcessHandle.of(info.getPid()).map(ProcessHandle::isAlive).orElse(false);
                            System.out.println("  " + info.getName() + " [" + info.getType() + "] "
                                    + "port=" + info.getPort() + " pid=" + info.getPid()
                                    + (alive ? " (running)" : " (dead)"));
                        }
                        System.out.println("\nUse --port, --all, or --root to target specific instances.");
                    }
                    return 1;
                }
            }

            int stopped = 0;
            int alreadyDead = 0;
            for (InstanceInfo info : targets) {
                Optional<ProcessHandle> ph = ProcessHandle.of(info.getPid());
                if (ph.isEmpty() || !ph.get().isAlive()) {
                    System.out.println("  " + info.getName() + ": not running (cleaning up stale registry entry)");
                    InstanceRegistry.unregister(info.getName());
                    alreadyDead++;
                    continue;
                }

                System.out.println("  Stopping " + info.getName()
                        + " [" + info.getType() + "] (PID: " + info.getPid()
                        + ", port: " + info.getPort() + ")...");

                ProcessHandle process = ph.get();
                if (force) {
                    process.destroyForcibly();
                } else {
                    process.destroy();
                    try {
                        process.onExit().get(15, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        System.out.println("    Graceful shutdown timed out, force-killing...");
                        process.destroyForcibly();
                    }
                }

                InstanceRegistry.unregister(info.getName());
                System.out.println("    Stopped.");
                stopped++;
            }

            // Clean up MCP config and open-state if we stopped a project
            if (!all && port == null) {
                KompileProjectStore store = new KompileProjectStore();
                Path resolved = resolveProjectRoot(store, root);
                // Clean up MCP .mcp.json if present
                Path mcpJson = resolved.resolve(".mcp.json");
                try {
                    McpToolInjection.removeTools(mcpJson);
                } catch (Exception ignored) {}
                // Clear runtime metadata from open state
                Open.clearRuntimeMetadata(store, resolved);
            }

            System.out.println("\n" + stopped + " stopped"
                    + (alreadyDead > 0 ? ", " + alreadyDead + " already dead" : "") + ".");
            return 0;
        }
    }

    /** Resolved end-to-end steps after applying implications. */
    public record QuickstartPlan(boolean serve, boolean crawl, boolean push) {}

    /**
     * Apply the quickstart flag implications: {@code --push} implies {@code --crawl} implies
     * {@code --serve}. Pure function, extracted so the contract can be unit-tested.
     */
    public static QuickstartPlan quickstartPlan(boolean serve, boolean crawl, boolean push) {
        boolean doPush = push;
        boolean doCrawl = crawl || doPush;
        boolean doServe = serve || doCrawl;
        return new QuickstartPlan(doServe, doCrawl, doPush);
    }

    /**
     * One-shot end-to-end orchestration used by {@code kompile project init --serve/--crawl/--push}.
     * <p>
     * Starts staging + the main app in the <em>background</em> (so this call keeps control), then
     * optionally runs the manifest's crawl profiles and waits for them to finish, then optionally
     * commits all project changes and pushes them to the configured git remote. Unless
     * {@code keepRunning} is set, the services this method started are stopped before returning —
     * giving a clean "init → crawl → push → done" single invocation.
     *
     * @return process exit code (0 = success)
     */
    public static int runQuickstart(Path resolved, int appPort, int stagingPort, boolean noStaging,
                                    boolean doCrawl, boolean doPush, boolean keepRunning,
                                    String commitMessage, List<String> jvmArgs) {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectManifest manifest = store.load(resolved);
        File projectDir = resolved.toFile();
        String projectName = manifest.getName() != null ? manifest.getName() : projectDir.getName();

        System.out.println("\n=== Quickstart: bringing up services for " + projectName + " ===");
        GlobalBootstrap.ensureHomeDirectory();
        GlobalBootstrap.ensureConfigs();

        File appJar = Open.findInstalledAppJar();
        if (appJar == null) {
            System.err.println("\nkompile-app-main not installed.");
            System.err.println("Install it with: kompile install kompile-app");
            return 1;
        }

        ServiceManager serviceManager = new ServiceManager();
        if (serviceManager.checkHealth(appPort)) {
            System.err.println("  A service is already running on port " + appPort
                    + ". Stop it first or pass a different --serve-port.");
            return 1;
        }

        File logDir = new File(projectDir, "data/logs");
        logDir.mkdirs();
        String webInstanceName = projectName + "-web";
        String stagingInstanceName = projectName + "-staging";

        Process stagingProcess = null;
        Process appProcess = null;
        boolean servicesStopped = false;
        try {
            // 1. Staging server (background)
            if (!noStaging) {
                File stagingJar = Open.findInstalledStagingJar();
                if (stagingJar == null) {
                    System.out.println("  Staging: not installed (skipping).");
                } else if (serviceManager.checkHealth(stagingPort)) {
                    System.out.println("  Staging: already running on port " + stagingPort + ".");
                } else {
                    System.out.println("  Starting staging on port " + stagingPort + "...");
                    List<String> stagingArgs = Open.buildStagingArgs(projectDir, appPort);
                    stagingProcess = serviceManager.startProjectComponent(
                            stagingInstanceName, "kompile-model-staging", stagingJar,
                            stagingPort, projectDir, null, stagingArgs, logDir, false);
                    if (serviceManager.waitForHealth(stagingPort, 60)) {
                        Open.configureStagingCallback(stagingPort, appPort);
                        System.out.println("  Staging: ready (PID: " + stagingProcess.pid() + ")");
                    } else {
                        System.out.println("  Staging: health check timed out (continuing).");
                    }
                }
            }

            // 2. Auto-register models + index coding projects (best effort)
            boolean stagingUp = !noStaging && serviceManager.checkHealth(stagingPort);
            if (stagingUp && !manifest.getModels().isEmpty()) {
                Open.autoStageProjectModels(manifest.getModels(), stagingPort, projectDir);
            }
            if (!manifest.getCodingProjects().isEmpty()) {
                Open.autoIndexCodingProjects(manifest.getCodingProjects());
            }

            // 3. Main app (background — we keep control to crawl/push/stop)
            List<String> appArgs = Open.buildProjectAppArgs(projectDir, appPort, stagingPort, stagingUp);
            System.out.println("  Starting kompile-app-main on port " + appPort + " (background)...");
            appProcess = serviceManager.startProjectComponent(
                    webInstanceName, "kompile-app-main", appJar, appPort,
                    projectDir, jvmArgs, appArgs, logDir, false);

            if (!serviceManager.waitForHealth(appPort, 180)) {
                System.err.println("  App did not become healthy within 180s. See "
                        + new File(logDir, webInstanceName + ".err.log"));
                stopServices(appProcess, stagingProcess, webInstanceName, stagingInstanceName);
                servicesStopped = true;
                return 1;
            }
            System.out.println("  App: ready at http://localhost:" + appPort + " (PID: " + appProcess.pid() + ")");

            // 4. Crawl + wait for completion
            if (doCrawl) {
                if (manifest.getCrawlProfiles().isEmpty()) {
                    System.out.println("  No crawl profiles in the manifest; nothing to crawl.");
                    System.out.println("  Tip: re-run init with --detect-sources or --auto-crawl to create one.");
                } else {
                    Open.triggerAutoCrawl(appPort, manifest);
                    waitForCrawlsToComplete(appPort, 60 * 60); // up to 1 hour
                }
            }

            // 5. Commit + push
            if (doPush) {
                // Stop services first (unless keep-running) so every index/file is flushed to disk
                // before we capture the working tree.
                if (!keepRunning) {
                    stopServices(appProcess, stagingProcess, webInstanceName, stagingInstanceName);
                    servicesStopped = true;
                }
                String msg = (commitMessage == null || commitMessage.isBlank())
                        ? "Initialize Kompile project" : commitMessage;
                System.out.println("\n  Committing project changes...");
                KompileProjectGitResult commit = store.gitCommitAll(resolved, msg);
                printGitOutput(commit);
                System.out.println("  Pushing to remote...");
                KompileProjectGitResult push = store.gitPush(resolved);
                printGitOutput(push);
                if (push.getExitCode() != 0) {
                    System.err.println("  Push failed (exit " + push.getExitCode() + "). "
                            + "Ensure a remote is configured (init with --backend git --remote <url>).");
                    return push.getExitCode();
                }
                System.out.println("  Pushed.");
            }

            return 0;
        } catch (Exception e) {
            System.err.println("  Quickstart failed: " + e.getMessage());
            return 1;
        } finally {
            if (!keepRunning && !servicesStopped) {
                stopServices(appProcess, stagingProcess, webInstanceName, stagingInstanceName);
            } else if (keepRunning) {
                System.out.println("\n  Services left running:");
                System.out.println("    App:     http://localhost:" + appPort);
                if (!noStaging) {
                    System.out.println("    Staging: http://localhost:" + stagingPort);
                }
                System.out.println("  Stop them with: kompile project stop --root " + resolved);
            }
        }
    }

    private static void printGitOutput(KompileProjectGitResult result) {
        if (result != null && result.getOutput() != null && !result.getOutput().isBlank()) {
            System.out.println("    " + result.getOutput().trim().replace("\n", "\n    "));
        }
    }

    /** Gracefully stop background services started by quickstart and clean up registry entries. */
    private static void stopServices(Process appProcess, Process stagingProcess,
                                     String webInstanceName, String stagingInstanceName) {
        System.out.println("\n  Stopping services...");
        destroyProcess(appProcess);
        destroyProcess(stagingProcess);
        try { InstanceRegistry.unregister(webInstanceName); } catch (Exception ignored) {}
        try { InstanceRegistry.unregister(stagingInstanceName); } catch (Exception ignored) {}
    }

    private static void destroyProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Poll {@code GET /api/unified-crawl/jobs/active} until no jobs are running (two consecutive
     * empty responses) or the timeout elapses. Best-effort: transient HTTP failures are ignored.
     */
    static void waitForCrawlsToComplete(int appPort, int maxWaitSeconds) {
        System.out.println("  Waiting for crawl(s) to finish...");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        // Give the job time to register before treating an empty active-list as "done".
        sleepQuietly(8000);
        int consecutiveEmpty = 0;
        int lastActive = -1;
        while (System.currentTimeMillis() < deadline) {
            Integer active = countActiveCrawlJobs(client, appPort);
            if (active == null) {
                sleepQuietly(5000);
                continue;
            }
            if (active == 0) {
                if (++consecutiveEmpty >= 2) {
                    System.out.println("  Crawl(s) finished.");
                    sleepQuietly(5000); // brief grace for final index/embedding flush
                    return;
                }
            } else {
                consecutiveEmpty = 0;
                if (active != lastActive) {
                    System.out.println("    " + active + " crawl job(s) running...");
                    lastActive = active;
                }
            }
            sleepQuietly(5000);
        }
        System.out.println("  Crawl wait timed out after " + maxWaitSeconds + "s; proceeding.");
    }

    /** @return number of active crawl jobs, or {@code null} if the endpoint could not be read. */
    private static Integer countActiveCrawlJobs(HttpClient client, int appPort) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + appPort + "/api/unified-crawl/jobs/active"))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonNode node = JsonUtils.standardMapper().readTree(resp.body());
            return (node != null && node.isArray()) ? node.size() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Command(name = "serve", mixinStandardHelpOptions = true,
            description = "Start model staging, serving, and app services for this project based on the manifest.")
    public static class Serve implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--workflow", "--id"}, description = "Workflow to run. Defaults to start-services.")
        private String workflowId;

        @Option(names = "--staging-only", description = "Only start the model-staging service.")
        private boolean stagingOnly;

        @Option(names = "--serving-only", description = "Only start the model-serving subprocess.")
        private boolean servingOnly;

        @Option(names = "--app-only", description = "Only start the main app.")
        private boolean appOnly;

        @Option(names = "--url", description = "Base URL of kompile-app for health checks.")
        private String appUrl;

        @Option(names = {"--port", "-p"}, description = "Localhost kompile-app port.")
        private Integer port;

        @Option(names = "--dry-run", description = "Print selected steps without running them.")
        private boolean dryRun;

        @Override
        public Integer call() throws Exception {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = ProjectCrawlCommand.autoconfigureModels(store, projectRoot, store.load(projectRoot));
            store.syncProjectRegistries(projectRoot);
            printServePlan(manifest, projectRoot);
            return ProjectCrawlCommand.runServeSelection(store, manifest, projectRoot, workflowId,
                    stagingOnly, servingOnly, appOnly, appUrl, port, dryRun);
        }
    }
}
