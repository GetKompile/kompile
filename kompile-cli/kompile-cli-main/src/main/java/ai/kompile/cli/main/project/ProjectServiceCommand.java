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
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
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

import ai.kompile.cli.common.logs.LogPaths;

import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static ai.kompile.cli.main.project.ProjectCommandUtils.firstNonBlank;
import static ai.kompile.cli.main.project.ProjectCommandUtils.normalizeEnum;
import static ai.kompile.cli.main.project.ProjectCommandUtils.requireExistingProjectRoot;
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
                ProjectServiceCommand.Logs.class,
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
            printServiceStatus(resolved);
            return 0;
        }

        /**
         * Print live service status: running instances (app + staging) from the
         * instance registry, their ports/URLs/alive state, and subprocess log paths.
         */
        static void printServiceStatus(Path projectRoot) {
            System.out.println("  Services:");
            String projectDir = projectRoot.toFile().getAbsolutePath();
            Path projectLogRoot = LogPaths.logsDirectory(projectRoot).toPath();
            try {
                List<InstanceInfo> instances = InstanceRegistry.findByProjectDir(projectDir);
                if (instances.isEmpty()) {
                    System.out.println("    No running instances registered for this project.");
                    System.out.println("    Start with: kompile project start");
                } else {
                    for (InstanceInfo info : instances) {
                        boolean alive = ProcessHandle.of(info.getPid())
                                .map(ProcessHandle::isAlive).orElse(false);
                        String health = alive ? "running" : "DEAD (stale registry)";
                        System.out.println("    " + info.getName()
                                + " [" + info.getType() + "]"
                                + " port=" + info.getPort()
                                + " pid=" + info.getPid()
                                + " url=http://localhost:" + info.getPort()
                                + " (" + health + ")");
                    }
                }
            } catch (Exception e) {
                System.out.println("    Could not read instance registry: " + e.getMessage());
            }
            // Subprocess log locations
            System.out.println("  Subprocess logs:");
            File subprocessLogsDir = LogPaths.subprocessesRoot(projectRoot);
            boolean anySubprocessLog = false;
            if (subprocessLogsDir.isDirectory()) {
                try (Stream<Path> logs = Files.find(subprocessLogsDir.toPath(), 8,
                        (p, attrs) -> attrs.isRegularFile()
                                && p.getFileName().toString().endsWith(".log"))) {
                    List<Path> sorted = logs
                            .sorted(Comparator.comparingLong((Path p) -> {
                                try {
                                    return Files.getLastModifiedTime(p).toMillis();
                                } catch (IOException e) {
                                    return 0L;
                                }
                            }).reversed())
                            .toList();

                    for (Path log : sorted) {
                        long sizeKb;
                        try {
                            sizeKb = Files.size(log) / 1024;
                        } catch (IOException e) {
                            sizeKb = 0L;
                        }
                        System.out.println("    " + log.toAbsolutePath()
                                + " (" + sizeKb + " KB)");
                        anySubprocessLog = true;
                    }
                } catch (IOException e) {
                    System.out.println("    Could not read subprocess logs: " + e.getMessage());
                }
            }

            if (!anySubprocessLog) {
                System.out.println("    " + subprocessLogsDir.getAbsolutePath()
                        + " (empty — subprocesses not yet started)");
            }
            // Project-local staging logs
            File projectLogDir = new File(projectDir, "data/logs");
            if (projectLogDir.isDirectory()) {
                File[] stagingLogs = projectLogDir.listFiles(
                        f -> f.isFile() && (f.getName().endsWith(".out.log") || f.getName().endsWith(".err.log")));
                if (stagingLogs != null && stagingLogs.length > 0) {
                    System.out.println("  Project service logs (staging/app):");
                    Arrays.sort(stagingLogs, Comparator.comparingLong(File::lastModified).reversed());
                    for (File log : stagingLogs) {
                        System.out.println("    " + log.getAbsolutePath()
                                + " (" + (log.length() / 1024) + " KB)");
                    }
                }
            }
            // MCP activity log
            File mcpLog = projectLogRoot.resolve("mcp-activity.log").toFile();
            if (mcpLog.isFile()) {
                System.out.println("  MCP activity log: " + mcpLog.getAbsolutePath()
                        + " (" + (mcpLog.length() / 1024) + " KB)");
            }
            System.out.println("  To tail logs: kompile project logs");
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
                description = "Port for the main web application. Default: Service Endpoints admin URL (initially 8080).")
        private Integer appPort;

        @Option(names = {"--staging-port"},
                description = "Port for model staging. Default: Service Endpoints staging URL (initially 8090).")
        private Integer stagingPort;

        @Option(names = {"--no-staging"},
                description = "Skip starting the model staging server.",
                defaultValue = "false")
        private boolean noStaging;

        // Persona apps. No defaultValue: null means "resolve from the routing ladder", so an
        // install that moved chat in service-endpoints.json is honoured instead of overridden.
        @Option(names = {"--chat-port"},
                description = "Port for the chat app. Default: resolved from service endpoints (8081).")
        private Integer chatPort;

        @Option(names = {"--crawl-manager-port"},
                description = "Port for the crawl manager. Default: resolved from service endpoints (8082).")
        private Integer crawlManagerPort;

        @Option(names = {"--no-chat"},
                description = "Skip starting the chat app.",
                defaultValue = "false")
        private boolean noChat;

        @Option(names = {"--no-crawl-manager"},
                description = "Skip starting the crawl manager. Auto-ingest crawls will not run.",
                defaultValue = "false")
        private boolean noCrawlManager;

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
            Path resolved = requireExistingProjectRoot(store, root);
            KompileProjectManifest manifest = store.ensureStandardServiceLifecycle(resolved);
            KompileProjectOpenState state = store.openProject(resolved);
            KompileProjectStatus status = store.status(resolved);
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
            ServiceEndpointsConfigManager endpointConfigManager =
                    ServiceEndpointsConfigManager.forProjectDirectory(resolved);
            ServiceEndpointsConfigManager.ServiceEndpointsConfig configuredEndpoints =
                    endpointConfigManager.current();
            appPort = appPort != null ? appPort : configuredEndpoints.port(KompileService.ADMIN);
            stagingPort = stagingPort != null ? stagingPort
                    : configuredEndpoints.stagingPort();

            // GC stale instance-registry entries before touching ports
            try {
                List<InstanceInfo> stale = InstanceRegistry.gcDeadInstances();
                if (!stale.isEmpty()) {
                    StringBuilder names = new StringBuilder();
                    for (InstanceInfo s : stale) {
                        if (names.length() > 0) names.append(", ");
                        names.append(s.getName());
                    }
                    System.out.println("  Cleaned " + stale.size()
                            + " stale instance registration(s): " + names);
                }
            } catch (Exception ignored) {}

            // 3. Find the kompile-app-main JAR from ~/.kompile/components/
            File appJar = findInstalledAppJar();
            if (appJar == null) {
                System.err.println("\nkompile-app-main not installed.");
                System.err.println("Install it with: kompile install kompile-app");
                return 1;
            }

            ServiceManager serviceManager = new ServiceManager();
            File projectDir = resolved.toFile();

            // 4. Reconcile the whole project bundle even when the admin persona is already up.
            // A prior partial start can leave :8080 healthy while chat or crawl is absent.
            boolean appAlreadyRunning = serviceManager.checkHealth(appPort);
            if (appAlreadyRunning) {
                String url = "http://localhost:" + appPort;
                System.out.println("\n  kompile-app-main is already running at " + url);
            } else {
                System.out.println("  App JAR: " + appJar.getAbsolutePath());
                System.out.println("  App port: " + appPort);
            }

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
                                stagingPort, projectDir,
                                projectRuntimeJvmArgs(projectDir, "stagingHeap", null),
                                stagingArgs, logDir, false);
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

            if (!noStaging && !serviceManager.checkHealth(stagingPort)) {
                System.err.println("  Project start failed: model staging is required but is not healthy on port "
                        + stagingPort + ". Use --no-staging only when that dependency is intentionally external.");
                stopOwnedComponent(stagingProcess, stagingInstanceName);
                return 1;
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

            // 6. Persist the topology before any consumer starts, then bring up app-main first.
            //    It owns the demand-driven serving child that chat/crawl call through servingUrl.
            Map<KompileService, Integer> personaPorts =
                    personaPorts(chatPort, crawlManagerPort, noChat, noCrawlManager,
                            endpointConfigManager);
            persistEndpointTopology(endpointConfigManager, appPort, stagingPort,
                    !noStaging, personaPorts);

            // Build shared application arguments — point every persona at the same project config.
            List<String> appArgs = buildProjectAppArgs(projectDir, appPort, stagingPort,
                    stagingProcess != null || serviceManager.checkHealth(stagingPort));

            Process appProcess = null;
            if (!appAlreadyRunning) {
                System.out.println("\nStarting kompile-app-main on port " + appPort + "...");
                try {
                    appProcess = serviceManager.startProjectComponent(
                            webInstanceName, "kompile-app-main", appJar, appPort,
                            projectDir, projectRuntimeJvmArgs(projectDir, "appHeap", jvmArgs),
                            appArgs, null, true);
                    System.out.println("  PID: " + appProcess.pid());
                    if (!serviceManager.waitForHealth(appPort, 180)) {
                        System.err.println("  Project start failed: kompile-app-main did not become healthy on port "
                                + appPort);
                        stopOwnedComponent(appProcess, webInstanceName);
                        stopOwnedComponent(stagingProcess, stagingInstanceName);
                        return 1;
                    }
                } catch (Exception e) {
                    stopOwnedComponent(appProcess, webInstanceName);
                    stopOwnedComponent(stagingProcess, stagingInstanceName);
                    System.err.println("  Project start failed: could not launch kompile-app-main — "
                            + e.getMessage());
                    return 1;
                }
            }

            // 6b. Start the end-user persona apps after their admin/serving coordinator is healthy.
            List<PersonaSidecar> personas = startPersonaApps(
                    serviceManager, projectName, projectDir, logDir, appArgs, personaPorts);
            List<KompileService> unavailablePersonas = personaPorts.entrySet().stream()
                    .filter(entry -> !serviceManager.checkHealth(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
            if (!unavailablePersonas.isEmpty()) {
                System.err.println("  Project start failed: required persona services are unavailable: "
                        + unavailablePersonas.stream().map(KompileService::componentId).toList());
                stopPersonaApps(personas);
                stopOwnedComponent(appProcess, webInstanceName);
                stopOwnedComponent(stagingProcess, stagingInstanceName);
                return 1;
            }

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
            // Each persona mounts /mcp/sse with its own tools — the chat tools live in
            // kompile-app-chat and the crawl tools in the crawl manager, so an agent needs an
            // entry per running persona to see the whole tool surface.
            for (Map.Entry<KompileService, Integer> persona : personaPorts.entrySet()) {
                if (serviceManager.checkHealth(persona.getValue())) {
                    addSseEntryToMcpJson(mcpJsonFile, persona.getKey().componentId(),
                            "http://localhost:" + persona.getValue() + "/mcp/sse");
                }
            }
            System.out.println("  MCP config: " + mcpJsonFile);

            // 8. Update open state with runtime service info
            state.getMetadata().put("appPort", String.valueOf(appPort));
            state.getMetadata().put("appUrl", backendUrl);
            recordPersonaMetadata(state, serviceManager, personaPorts);
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

            // 9. Register shutdown ownership only when this invocation owns the foreground admin.
            // If admin was already running, newly reconciled sidecars must remain alive after this
            // short command returns; `project stop` owns their registry-based cleanup.
            if (!appAlreadyRunning) {
                final Process stagingRef = stagingProcess;
                final List<PersonaSidecar> personaRef = personas;
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
                    stopPersonaApps(personaRef);
                    try {
                        InstanceRegistry.unregister(webInstanceName);
                        InstanceRegistry.unregister(stagingInstanceName);
                    } catch (Exception ignored) {}
                }));
            }

            // 10. Open browser after app starts
            if (!noOpenBrowser) {
                String url = "http://localhost:" + appPort;
                if (appAlreadyRunning) {
                    System.out.println("  Opening browser: " + url);
                    openBrowserUrl(url);
                } else {
                    Thread browserThread = new Thread(() -> {
                        if (waitForAppReady(appPort, 120)) {
                            System.out.println("  Opening browser: " + url);
                            openBrowserUrl(url);
                        }
                    });
                    browserThread.setDaemon(true);
                    browserThread.start();
                }
            }

            // 10b. Crawl prompt — once the crawl manager is healthy, ask about document ingestion.
            //      The port here is the crawl manager's, not appPort: /api/unified-crawl is not
            //      mounted on the admin console any more, so waiting on :8080 would wait on the
            //      wrong process and then POST into a 404.
            Integer crawlManagerRuntimePort = personaPorts.get(KompileService.CRAWL);
            if (!noCrawl && crawlManagerRuntimePort != null && !manifest.getCrawlProfiles().isEmpty()) {
                final int crawlAppPort = crawlManagerRuntimePort;
                final KompileProjectManifest crawlManifest = manifest;
                Runnable crawlAction = () -> {
                    if (!waitForAppReady(crawlAppPort, 120)) return;
                    if (crawl) {
                        // --crawl flag: run immediately without prompting
                        triggerAutoCrawl(crawlAppPort, crawlManifest);
                        return;
                    }
                    // Interactive prompt
                    Console console = System.console();
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
                };
                if (appAlreadyRunning) {
                    crawlAction.run();
                } else {
                    Thread crawlThread = new Thread(crawlAction);
                    crawlThread.setDaemon(true);
                    crawlThread.start();
                }
            } else if (!noCrawl && crawlManagerRuntimePort == null && !manifest.getCrawlProfiles().isEmpty()) {
                System.out.println("  Crawl profiles present but the crawl manager was skipped"
                        + " (--no-crawl-manager) — auto-ingest will not run.");
            }

            if (appAlreadyRunning) {
                System.out.println("  All requested project services have been reconciled.");
                return 0;
            }

            // 11. Keep ownership in the foreground until app-main exits or Ctrl+C stops the bundle.
            System.out.println("  Press Ctrl+C to stop.\n");
            if (appProcess == null) {
                System.err.println("  Project start lost ownership of kompile-app-main.");
                stopPersonaApps(personas);
                stopOwnedComponent(stagingProcess, stagingInstanceName);
                return 1;
            }
            int exitCode = appProcess.waitFor();

            // 12. Clean up
            McpToolInjection.removeTools(mcpJsonFile);
            clearRuntimeMetadata(store, resolved);
            stopPersonaApps(personas);
            InstanceRegistry.unregister(webInstanceName);
            if (stagingProcess != null) {
                InstanceRegistry.unregister(stagingInstanceName);
                if (stagingProcess.isAlive()) {
                    stagingProcess.destroy();
                }
            }

            return exitCode;
        }

        private static void stopOwnedComponent(Process process, String instanceName) {
            if (process == null) {
                return;
            }
            try {
                InstanceRegistry.unregister(instanceName);
            } catch (Exception ignored) {
            }
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
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
                settings.put("callback_url", "http://localhost:" + appPort);
                settings.put("auto_reload_enabled", true);

                HttpRequest putReq = HttpRequest.newBuilder()
                        .uri(URI.create(settingsUrl))
                        .header("Content-Type", "application/json")
                        .header("X-Kompile-Staging-Request", "1")
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
                // Persona sidecars: chatPort/chatUrl, crawlPort/crawlUrl. Written by
                // recordPersonaMetadata from the same KompileService accessors, so the two stay
                // in step if a persona is ever added.
                for (KompileService service : KompileService.values()) {
                    if (service == KompileService.ADMIN) {
                        continue;
                    }
                    meta.remove(service.id() + "Port");
                    meta.remove(service.configKey());
                }
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
         * A persona app this command launched, retained so the shutdown path can stop and
         * unregister it the way it already does for the staging sidecar.
         */
        record PersonaSidecar(KompileService service, String instanceName, int port, Process process) {
        }

        /**
         * Resolved port for a persona app: an explicit flag wins, otherwise the routing ladder.
         *
         * <p>The supplied manager is rooted in the project directory, which is the same managed
         * configuration every launched component receives through {@code kompile.data.dir}.</p>
         */
        static int personaPort(KompileService service, Integer override,
                               ServiceEndpointsConfigManager endpointConfigManager) {
            return override != null ? override : endpointConfigManager.current().port(service);
        }

        static int personaPort(KompileService service, Integer override) {
            return personaPort(service, override, ServiceEndpointsConfigManager.shared());
        }

        /**
         * Apply a launch port without discarding the explicitly configured endpoint host.
         *
         * <p>Project UIs use these managed URLs directly. Replacing {@code 127.0.0.1} with
         * {@code localhost} (or vice versa) makes an otherwise local browser request cross-origin,
         * so startup must mutate only the port. A malformed hand-edited URL retains the historical
         * localhost fallback rather than preventing project startup.</p>
         */
        static String endpointWithPort(String baseUrl, int port) {
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Endpoint port must be between 1 and 65535");
            }
            try {
                URI uri = URI.create(baseUrl);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                        && host != null && !host.isBlank()) {
                    String authorityHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
                    String rawPath = uri.getRawPath();
                    return scheme + "://" + authorityHost + ":" + port
                            + (rawPath != null ? rawPath : "");
                }
            } catch (IllegalArgumentException ignored) {
                // Preserve startup resilience for a hand-edited malformed managed config.
            }
            return "http://localhost:" + port;
        }

        /** Persist the exact local topology this launch is about to own. */
        static void persistEndpointTopology(ServiceEndpointsConfigManager manager,
                                            int appPort,
                                            int stagingPort,
                                            boolean includeStaging,
                                            Map<KompileService, Integer> personaPorts) throws IOException {
            ServiceEndpointsConfigManager.ServiceEndpointsConfig configured = manager.current();
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(KompileService.ADMIN.configKey(),
                    endpointWithPort(configured.effectiveUrl(KompileService.ADMIN), appPort));
            if (includeStaging) {
                updates.put(ServiceEndpointsConfigManager.STAGING_URL_KEY,
                        endpointWithPort(configured.effectiveStagingUrl(), stagingPort));
            }
            personaPorts.forEach((service, port) ->
                    updates.put(service.configKey(),
                            endpointWithPort(configured.effectiveUrl(service), port)));
            manager.update(updates);
        }

        /**
         * The persona apps to bring up next to the admin console, in start order.
         *
         * @param chatPort         explicit chat port, or null to resolve from the routing ladder
         * @param crawlManagerPort explicit crawl-manager port, or null to resolve
         * @param noChat           skip chat entirely
         * @param noCrawlManager   skip the crawl manager entirely
         */
        static Map<KompileService, Integer> personaPorts(Integer chatPort, Integer crawlManagerPort,
                                                         boolean noChat, boolean noCrawlManager) {
            return personaPorts(chatPort, crawlManagerPort, noChat, noCrawlManager,
                    ServiceEndpointsConfigManager.shared());
        }

        static Map<KompileService, Integer> personaPorts(Integer chatPort, Integer crawlManagerPort,
                                                         boolean noChat, boolean noCrawlManager,
                                                         ServiceEndpointsConfigManager endpointConfigManager) {
            Map<KompileService, Integer> ports = new LinkedHashMap<>();
            if (!noChat) {
                ports.put(KompileService.CHAT,
                        personaPort(KompileService.CHAT, chatPort, endpointConfigManager));
            }
            if (!noCrawlManager) {
                ports.put(KompileService.CRAWL,
                        personaPort(KompileService.CRAWL, crawlManagerPort, endpointConfigManager));
            }
            return ports;
        }

        /**
         * Start the end-user persona apps beside the admin console.
         *
         * <p>kompile-app-main serves the admin surface only, so an opened project is not usable
         * until these are up: the crawl trigger posts to the crawl manager and {@code kompile chat}
         * talks to the chat app, neither of which :8080 answers any more. Each app is optional — a
         * box with only the admin console installed still opens the project and simply reports
         * which surfaces are missing rather than failing the open.</p>
         *
         * <p>They take the same project args as app-main ({@code kompile.data.dir} and friends), so
         * all three read one project's config and data. Heap comes from separate
         * {@code project-runtime.json} keys so three JVMs on one box can be sized independently.</p>
         */
        static List<PersonaSidecar> startPersonaApps(ServiceManager serviceManager, String projectName,
                                                     File projectDir, File logDir, List<String> appArgs,
                                                     Map<KompileService, Integer> ports) {
            List<PersonaSidecar> started = new ArrayList<>();
            ComponentRegistry registry = new ComponentRegistry();
            for (Map.Entry<KompileService, Integer> entry : ports.entrySet()) {
                KompileService service = entry.getKey();
                int port = entry.getValue();
                String componentId = service.componentId();
                if (serviceManager.checkHealth(port)) {
                    System.out.println("  " + componentId + ": already running on port " + port);
                    continue;
                }
                File jar = registry.findInstalledJar(componentId);
                if (jar == null) {
                    System.out.println("  " + componentId + ": not installed — "
                            + surfacesOf(service) + " unavailable"
                            + " (install with: kompile install " + componentId + ")");
                    continue;
                }
                String instanceName = projectName + "-" + service.id();
                System.out.println("  Starting " + componentId + " on port " + port + "...");
                try {
                    Process process = serviceManager.startProjectComponent(
                            instanceName, componentId, jar, port, projectDir,
                            projectRuntimeJvmArgs(projectDir, service.id() + "Heap", null),
                            appArgs, logDir, false);
                    started.add(new PersonaSidecar(service, instanceName, port, process));
                    if (serviceManager.waitForHealth(port, 180)) {
                        System.out.println("  " + componentId + ": running on port " + port
                                + " (PID: " + process.pid() + ")");
                    } else {
                        System.out.println("  " + componentId + ": started but health check timed out"
                                + " (PID: " + process.pid() + ")");
                    }
                } catch (Exception e) {
                    System.err.println("  " + componentId + ": failed to start — " + e.getMessage());
                }
            }
            return started;
        }

        /** Human-readable surfaces a persona owns, for the "not installed" message. */
        static String surfacesOf(KompileService service) {
            return switch (service) {
                case CHAT -> "chat, agents, and RAG";
                case CRAWL -> "crawls, ingest, and indexing";
                case ADMIN -> "the admin console";
            };
        }

        /** Stop and unregister every persona sidecar this command started. */
        static void stopPersonaApps(List<PersonaSidecar> sidecars) {
            if (sidecars == null) {
                return;
            }
            for (PersonaSidecar sidecar : sidecars) {
                try {
                    InstanceRegistry.unregister(sidecar.instanceName());
                } catch (Exception ignored) {
                }
                Process process = sidecar.process();
                if (process != null && process.isAlive()) {
                    process.destroy();
                    try {
                        process.waitFor();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("  " + sidecar.service().componentId() + " stopped.");
                }
            }
        }

        /**
         * Record each <em>running</em> persona's port and URL in the project's open state.
         *
         * <p>Health-gated on purpose: a persona that is not installed leaves no entry, so anything
         * reading the open state sees the surfaces this project actually has rather than a URL
         * that will refuse every connection.</p>
         */
        static void recordPersonaMetadata(KompileProjectOpenState state, ServiceManager serviceManager,
                                          Map<KompileService, Integer> ports) {
            for (Map.Entry<KompileService, Integer> entry : ports.entrySet()) {
                if (!serviceManager.checkHealth(entry.getValue())) {
                    continue;
                }
                KompileService service = entry.getKey();
                state.getMetadata().put(service.id() + "Port", String.valueOf(entry.getValue()));
                state.getMetadata().put(service.configKey(), "http://localhost:" + entry.getValue());
            }
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

            long freeBytes;
            try {
                freeBytes = Files.getFileStore(projectDir.toPath()).getUsableSpace();
            } catch (Exception e) {
                freeBytes = -1;
            }
            final long headroomMb = 1024;
            long plannedMb = 0;

            for (KompileProjectModel model : models) {
                String modelId = firstNonBlank(model.getModelId(), model.getRegistryModelId(), model.getId());
                if (modelId == null || modelId.isBlank()) continue;

                long diskMb = requirementMb(model, "requirement.diskMb");
                if (freeBytes > 0 && diskMb > 0
                        && (plannedMb + diskMb + headroomMb) * 1024L * 1024L > freeBytes) {
                    System.out.println("    " + modelId + ": skipped — needs ~" + diskMb
                            + " MB disk but only " + (freeBytes / (1024L * 1024L))
                            + " MB free (1 GB headroom reserved)");
                    continue;
                }
                plannedMb += Math.max(diskMb, 0);

                try {
                    // Check if already staged
                    HttpURLConnection statusConn = (HttpURLConnection)
                            new URL(stagingBase + "/status/" + modelId).openConnection();
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
                    HttpURLConnection conn = (HttpURLConnection)
                            new URL(stagingBase + "/stage/catalog/" + modelId + "?autoPromote=true").openConnection();
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
                            HttpURLConnection conn = (HttpURLConnection)
                                    new URL(stagingBase + "/convert").openConnection();
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

        /** Numeric requirement from model metadata (e.g. requirement.diskMb); 0 when absent. */
        private static long requirementMb(KompileProjectModel model, String key) {
            try {
                String v = model.getMetadata().get(key);
                return v == null ? 0 : Long.parseLong(v.trim());
            } catch (Exception e) {
                return 0;
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
                    HttpURLConnection conn = (HttpURLConnection)
                            new URL("http://localhost:" + port + "/actuator/health").openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    int rc = conn.getResponseCode();
                    conn.disconnect();
                    if (rc == 200) return true;
                } catch (Exception ignored) {}
                // Fallback: simple root GET — if server responds at all, it's up
                try {
                    HttpURLConnection conn = (HttpURLConnection)
                            new URL("http://localhost:" + port + "/").openConnection();
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
                    "project's config/data, and start staging plus the admin, chat, and crawl-manager apps.%n%n" +
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
                description = "Port for the main web application. Default: Service Endpoints admin URL (initially 8080).")
        private Integer appPort;

        @Option(names = {"--staging-port"},
                description = "Port for model staging. Default: Service Endpoints staging URL (initially 8090).")
        private Integer stagingPort;

        @Option(names = {"--no-staging"},
                description = "Skip starting the model staging server.",
                defaultValue = "false")
        private boolean noStaging;

        @Option(names = {"--crawl"},
                description = "Auto-run crawl profiles after app is healthy.",
                defaultValue = "false")
        private boolean crawl;

        // Persona apps — see Open for why these have no literal default.
        @Option(names = {"--chat-port"},
                description = "Port for the chat app. Default: resolved from service endpoints (8081).")
        private Integer chatPort;

        @Option(names = {"--crawl-manager-port"},
                description = "Port for the crawl manager. Default: resolved from service endpoints (8082).")
        private Integer crawlManagerPort;

        @Option(names = {"--no-chat"},
                description = "Skip starting the chat app.",
                defaultValue = "false")
        private boolean noChat;

        @Option(names = {"--no-crawl-manager"},
                description = "Skip starting the crawl manager. Crawl profiles will not run.",
                defaultValue = "false")
        private boolean noCrawlManager;

        @Option(names = {"--jvm-args"},
                description = "Additional JVM arguments for the main application (comma-separated).",
                split = ",")
        private List<String> jvmArgs;

        @Override
        public Integer call() throws Exception {
            // 1. Resolve project and load manifest
            KompileProjectStore store = new KompileProjectStore();
            Path resolved = requireExistingProjectRoot(store, root);
            KompileProjectManifest manifest = store.ensureStandardServiceLifecycle(resolved);
            File projectDir = resolved.toFile();
            String projectName = manifest.getName() != null ? manifest.getName() : projectDir.getName();

            System.out.println("Starting project: " + projectName);
            System.out.println("  Root: " + resolved);

            // 2. Ensure global bootstrap
            GlobalBootstrap.ensureHomeDirectory();
            GlobalBootstrap.ensureConfigs();
            ServiceEndpointsConfigManager endpointConfigManager =
                    ServiceEndpointsConfigManager.forProjectDirectory(resolved);
            ServiceEndpointsConfigManager.ServiceEndpointsConfig configuredEndpoints =
                    endpointConfigManager.current();
            appPort = appPort != null ? appPort : configuredEndpoints.port(KompileService.ADMIN);
            stagingPort = stagingPort != null ? stagingPort : configuredEndpoints.stagingPort();

            // GC stale instance-registry entries before touching ports
            try {
                List<InstanceInfo> stale = InstanceRegistry.gcDeadInstances();
                if (!stale.isEmpty()) {
                    StringBuilder names = new StringBuilder();
                    for (InstanceInfo s : stale) {
                        if (names.length() > 0) names.append(", ");
                        names.append(s.getName());
                    }
                    System.out.println("  Cleaned " + stale.size()
                            + " stale instance registration(s): " + names);
                }
            } catch (Exception ignored) {}

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

            // 4. Reconcile all requested services even when the admin persona is already running.
            boolean appAlreadyRunning = serviceManager.checkHealth(appPort);
            if (appAlreadyRunning) {
                System.out.println("  Already running at http://localhost:" + appPort);
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
                                stagingPort, projectDir,
                                projectRuntimeJvmArgs(projectDir, "stagingHeap", null),
                                stagingArgs, logDir, false);
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

            // 8b. Start the end-user persona apps beside the admin console.
            Map<KompileService, Integer> personaPorts =
                    Open.personaPorts(chatPort, crawlManagerPort, noChat, noCrawlManager,
                            endpointConfigManager);
            Open.persistEndpointTopology(endpointConfigManager, appPort, stagingPort,
                    !noStaging, personaPorts);

            // 8. Build app arguments
            List<String> appArgs = Open.buildProjectAppArgs(projectDir, appPort, stagingPort,
                    stagingProcess != null || serviceManager.checkHealth(stagingPort));
            List<Open.PersonaSidecar> personas = Open.startPersonaApps(
                    serviceManager, projectName, projectDir, logDir, appArgs, personaPorts);

            // 9. Shutdown hook. When admin pre-existed, this invocation is a short reconciliation
            // command and the registry-backed `project stop` command owns the new sidecars.
            String webInstanceName = projectName + "-web";
            String stagingInstanceName = projectName + "-staging";
            if (!appAlreadyRunning) {
                final Process stagingRef = stagingProcess;
                final List<Open.PersonaSidecar> personaRef = personas;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("\nShutting down...");
                    if (stagingRef != null && stagingRef.isAlive()) {
                        stagingRef.destroy();
                        try { stagingRef.waitFor(); } catch (InterruptedException ignored) {}
                        System.out.println("  Staging stopped.");
                    }
                    Open.stopPersonaApps(personaRef);
                    try {
                        InstanceRegistry.unregister(webInstanceName);
                        InstanceRegistry.unregister(stagingInstanceName);
                    } catch (Exception ignored) {}
                }));
            }

            // 10. Crawl trigger (after the crawl manager is up, in background). Crawls go to the
            //     crawl manager — the admin console does not mount /api/unified-crawl.
            Integer crawlManagerRuntimePort = personaPorts.get(KompileService.CRAWL);
            if (crawl && crawlManagerRuntimePort != null && !manifest.getCrawlProfiles().isEmpty()) {
                final int crawlPort = crawlManagerRuntimePort;
                final KompileProjectManifest crawlManifest = manifest;
                Runnable crawlAction = () -> {
                    if (!Open.waitForAppReady(crawlPort, 120)) return;
                    Open.triggerAutoCrawl(crawlPort, crawlManifest);
                };
                if (appAlreadyRunning) {
                    crawlAction.run();
                } else {
                    Thread crawlThread = new Thread(crawlAction);
                    crawlThread.setDaemon(true);
                    crawlThread.start();
                }
            } else if (crawl && crawlManagerRuntimePort == null && !manifest.getCrawlProfiles().isEmpty()) {
                System.out.println("  --crawl requested but the crawl manager was skipped"
                        + " (--no-crawl-manager) — no crawl will run.");
            }

            if (appAlreadyRunning) {
                System.out.println("  All requested project services have been reconciled.");
                return 0;
            }

            // 11. Start app (foreground — blocks until Ctrl+C)
            System.out.println("\nStarting kompile-app-main...");
            System.out.println("  http://localhost:" + appPort);
            System.out.println("  Press Ctrl+C to stop.\n");

            Process appProcess = serviceManager.startProjectComponent(
                    webInstanceName, "kompile-app-main", appJar, appPort,
                    projectDir, projectRuntimeJvmArgs(projectDir, "appHeap", jvmArgs),
                    appArgs, null, true);

            int exitCode = appProcess.waitFor();

            // 12. Cleanup
            Open.stopPersonaApps(personas);
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
            description = "Stop a running Kompile project — stops staging and all app personas.%n%n" +
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
    /**
     * Per-project JVM args derived at init time by the hardware provisioner
     * (config/project-runtime.json). Explicit --jvm-args always win; when the
     * file is absent or unreadable returns null so the ServiceManager
     * machine-tier default applies.
     */
    static List<String> projectRuntimeJvmArgs(File projectDir, String heapKey, List<String> explicit) {
        if (explicit != null && !explicit.isEmpty()) return explicit;
        try {
            File f = new File(projectDir, "config/project-runtime.json");
            if (!f.isFile()) return null;
            JsonNode node = new ObjectMapper().readTree(f);
            JsonNode heap = node.get(heapKey);
            if (heap != null && heap.isTextual() && !heap.asText().isBlank()) {
                return List.of("-Xmx" + heap.asText().trim());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static int runQuickstart(Path resolved, int appPort, int stagingPort, boolean noStaging,
                                    boolean doCrawl, boolean doPush, boolean keepRunning,
                                    String commitMessage, List<String> jvmArgs) {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectManifest manifest = store.ensureStandardServiceLifecycle(resolved);
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

        // Quickstart takes no persona flags — `kompile project init --serve` drives it — so both
        // personas come up on this project's managed ports. Declared out here because the finally
        // block (and the early-return paths) have to stop them.
        ServiceEndpointsConfigManager endpointConfigManager =
                ServiceEndpointsConfigManager.forProjectDirectory(resolved);
        Map<KompileService, Integer> personaPorts = Open.personaPorts(
                null, null, false, false, endpointConfigManager);
        List<Open.PersonaSidecar> personas = new ArrayList<>();

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
                            stagingPort, projectDir,
                            projectRuntimeJvmArgs(projectDir, "stagingHeap", null),
                            stagingArgs, logDir, false);
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
            Open.persistEndpointTopology(endpointConfigManager, appPort, stagingPort,
                    !noStaging, personaPorts);
            List<String> appArgs = Open.buildProjectAppArgs(projectDir, appPort, stagingPort, stagingUp);
            System.out.println("  Starting kompile-app-main on port " + appPort + " (background)...");
            appProcess = serviceManager.startProjectComponent(
                    webInstanceName, "kompile-app-main", appJar, appPort,
                    projectDir, projectRuntimeJvmArgs(projectDir, "appHeap", jvmArgs),
                    appArgs, logDir, false);

            if (!serviceManager.waitForHealth(appPort, 180)) {
                System.err.println("  App did not become healthy within 180s. See "
                        + new File(logDir, webInstanceName + ".err.log"));
                stopServices(appProcess, stagingProcess, webInstanceName, stagingInstanceName, personas);
                servicesStopped = true;
                return 1;
            }
            System.out.println("  App: ready at http://localhost:" + appPort + " (PID: " + appProcess.pid() + ")");

            // 3b. Persona apps — chat (:8081) and the crawl manager (:8082). The crawl step below
            //     needs the crawl manager specifically: /api/unified-crawl no longer exists on the
            //     admin console, so a quickstart without it can serve but cannot ingest.
            personas = Open.startPersonaApps(
                    serviceManager, projectName, projectDir, logDir, appArgs, personaPorts);

            // 4. Crawl + wait for completion
            if (doCrawl) {
                Integer crawlPort = personaPorts.get(KompileService.CRAWL);
                if (manifest.getCrawlProfiles().isEmpty()) {
                    System.out.println("  No crawl profiles in the manifest; nothing to crawl.");
                    System.out.println("  Tip: re-run init with --detect-sources or --auto-crawl to create one.");
                } else if (crawlPort == null || !serviceManager.checkHealth(crawlPort)) {
                    System.err.println("  Crawl manager is not running — cannot crawl.");
                    System.err.println("  Install it with: kompile install kompile-app-crawl-manager");
                } else {
                    Open.triggerAutoCrawl(crawlPort, manifest);
                    waitForCrawlsToComplete(crawlPort, 60 * 60); // up to 1 hour
                }
            }

            // 5. Commit + push
            if (doPush) {
                // Stop services first (unless keep-running) so every index/file is flushed to disk
                // before we capture the working tree.
                if (!keepRunning) {
                    stopServices(appProcess, stagingProcess, webInstanceName, stagingInstanceName, personas);
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
                stopServices(appProcess, stagingProcess, webInstanceName, stagingInstanceName, personas);
            } else if (keepRunning) {
                System.out.println("\n  Services left running:");
                System.out.println("    App:     http://localhost:" + appPort);
                for (Open.PersonaSidecar persona : personas) {
                    System.out.println("    " + persona.service().componentId() + ": http://localhost:"
                            + persona.port());
                }
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
                                     String webInstanceName, String stagingInstanceName,
                                     List<Open.PersonaSidecar> personas) {
        System.out.println("\n  Stopping services...");
        Open.stopPersonaApps(personas);
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

    @Command(name = "logs", mixinStandardHelpOptions = true,
            description = "Show, follow, or scan logs for the current project's services and subprocesses.%n%n" +
                    "By default prints the last --tail lines from project subprocess logs and%n" +
                    "project service logs under data/logs, each prefixed with a '==> <path> <=='%n" +
                    "header matching the POSIX tail convention. Use --scan/--monitor for a%n" +
                    "targeted failure scan of current logs; add --all to include historical%n" +
                    "subprocess logs and MCP activity.%n%n" +
                    "Examples:%n" +
                    "  kompile project logs%n" +
                    "  kompile project logs -n 50%n" +
                    "  kompile project logs --follow%n" +
                    "  kompile project logs --scan%n" +
                    "  kompile project logs --subprocess embedding%n" +
                    "  kompile project logs --all%n")
    public static class Logs implements Callable<Integer> {

        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--tail", "-n"}, description = "Number of lines to show per log file. Default: 200.", defaultValue = "200")
        private int tail;

        @Option(names = {"--follow", "-f"}, description = "Stream new lines as they are appended (like tail -f).", defaultValue = "false")
        private boolean follow;

        @Option(names = {"--scan", "--monitor"},
                description = "Scan recent log lines for known failure signals and return non-zero when any are found.",
                defaultValue = "false")
        private boolean scan;

        @Option(names = "--subprocess",
                description = "Filter to a single subprocess log by name (e.g. 'embedding', 'graph-matrix', 'serving').")
        // package-visible for testing
        String subprocess;

        @Option(names = "--all",
                description = "Include historical subprocess logs and project MCP activity logs. Service logs are included by default.",
                defaultValue = "false")
        private boolean all;

        // package-visible for testing — can be overridden to inject a temp dir
        Path subprocessLogsBaseDir = null;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);

            // Collect log files in display order
            Map<String, Path> logFiles = discoverLogFiles(projectRoot);

            if (logFiles.isEmpty()) {
                System.out.println("No log files found for project at " + projectRoot);
                printSearchPaths(projectRoot);
                return 0;
            }

            if (scan) {
                Map<String, Path> monitorLogs = all ? logFiles : selectMonitorLogFiles(logFiles);
                return scanLogs(monitorLogs, tail);
            }
            if (follow) {
                return followLogs(logFiles);
            }
            return tailLogs(logFiles, tail);
        }

        /**
         * Discover all relevant log files for the project, returning an ordered map of
         * display-name → path. Applies subprocess filter and --all flag.
         */
        Map<String, Path> discoverLogFiles(Path projectRoot) {
            Map<String, Path> result = new LinkedHashMap<>();
            // Resolve subprocess logs base (allows injection of temp dir in tests)
            Path spBase = subprocessLogsBaseDir != null
                    ? subprocessLogsBaseDir
                    : LogPaths.subprocessesRoot(projectRoot).toPath();

            // 1. Subprocess logs (flat or nested under type dirs)
            if (subprocess != null && !subprocess.isBlank()) {
                // Filtered: only the named subprocess
                String name = subprocess.trim();
                String target = name + ".log";
                Path candidate = spBase.resolve(target);
                List<Path> matches = new ArrayList<>();
                if (Files.isRegularFile(candidate)) {
                    matches.add(candidate);
                } else if (Files.isDirectory(spBase)) {
                    try (Stream<Path> stream = Files.find(spBase, 8,
                            (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().equals(target))) {
                        stream.forEach(matches::add);
                    } catch (IOException ignored) {
                    }
                }

                if (matches.isEmpty()) {
                    System.out.println("No subprocess log found for '" + name + "' at " + spBase);
                } else {
                    matches.sort(Comparator.comparingLong((Path p) -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).reversed());
                    Path latest = matches.get(0);
                    result.put(relativeDisplayPath(spBase, latest), latest);
                }
                return result;
            }

            if (spBase.toFile().isDirectory()) {
                try (Stream<Path> stream = Files.walk(spBase, 8)) {
                    Comparator<Path> byMtime = Comparator.comparingLong((Path p) -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    });
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".log"))
                            .filter(Files::isRegularFile)
                            .sorted(byMtime.reversed())
                            .forEach(p -> result.put(relativeDisplayPath(spBase, p), p));
                } catch (IOException ignored) {}
            }

            // 2. Project-local service logs: <projectDir>/data/logs/*.out.log and *.err.log
            File projectLogDir = new File(projectRoot.toFile(), "data/logs");
            if (projectLogDir.isDirectory()) {
                File[] files = projectLogDir.listFiles(
                        f -> f.isFile() && (f.getName().endsWith(".out.log") || f.getName().endsWith(".err.log")));
                if (files != null) {
                    Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
                    for (File f : files) {
                        result.put("data/logs/" + f.getName(), f.toPath());
                    }
                }
            }

            // 3. MCP activity log is useful but noisy, so keep it behind --all.
            if (all) {
                Path mcpLog = LogPaths.logsDirectory(projectRoot).toPath().resolve("mcp-activity.log");
                if (Files.isRegularFile(mcpLog)) {
                    result.put("mcp-activity.log", mcpLog);
                }
            }

            return result;
        }

        /** Print the last {@code n} lines of each log file with a header. */
        private static int tailLogs(Map<String, Path> logFiles, int n) {
            for (Map.Entry<String, Path> entry : logFiles.entrySet()) {
                Path path = entry.getValue();
                System.out.println("\n==> " + path + " <==");
                try {
                    List<String> lines = tailFile(path, n);
                    if (lines.isEmpty()) {
                        System.out.println("(empty)");
                    } else {
                        lines.forEach(System.out::println);
                    }
                } catch (IOException e) {
                    System.out.println("(could not read: " + e.getMessage() + ")");
                }
            }
            return 0;
        }

        private static final List<LogSignalRule> DEFAULT_SCAN_RULES = List.of(
                new LogSignalRule("conversion-failed", LogSignalSeverity.ERROR,
                        Pattern.compile("(?i)\\bConversion failed\\b|Another variable with the name .* already exists")),
                new LogSignalRule("missing-downloader", LogSignalSeverity.ERROR,
                        Pattern.compile("(?i)No downloader available|Download failed: No downloader available")),
                new LogSignalRule("model-load-failed", LogSignalSeverity.ERROR,
                        Pattern.compile("(?i)Failed to load model|Model load failed|Model initialization failed")),
                new LogSignalRule("subprocess-exit", LogSignalSeverity.ERROR,
                        Pattern.compile("(?i)subprocess.*(failed|exited|terminated).*non[- ]?zero|exit code [1-9][0-9]*")),
                new LogSignalRule("http-error", LogSignalSeverity.ERROR,
                        Pattern.compile("(?i)^(?!.*Optional .* was not available).*(\\bHTTP [45][0-9][0-9]\\b|status[=: ]+[45][0-9][0-9]\\b)")),
                new LogSignalRule("connection-failure", LogSignalSeverity.ERROR,
                        Pattern.compile("(?i)Connection refused|Address already in use|Broken pipe")),
                new LogSignalRule("oom", LogSignalSeverity.ERROR,
                        Pattern.compile("(?i)OutOfMemoryError|CUDA out of memory|\\bOOM\\b")),
                new LogSignalRule("non-finite", LogSignalSeverity.ERROR,
                        Pattern.compile("(?i)non[- ]?finite|\\b(?:detected|contains|produced|found|invalid|output|embedding|vector|tensor|value)[^\\n]{0,80}\\bNaN\\b|\\bNaN\\b[^\\n]{0,80}\\b(?:detected|contains|produced|found|invalid|output|embedding|vector|tensor|value)\\b|\\bInfinity\\b")),
                new LogSignalRule("staging-failed", LogSignalSeverity.ERROR,
                        Pattern.compile("(?i)\\b(staging|stage|download|convert|model)\\b.*\\bfailed\\b|\\bfailed\\b.*\\b(staging|stage|download|convert|model)\\b|\\\"status\\\"\\s*:\\s*\\\"failed\\\"")),
                new LogSignalRule("zero-yield", LogSignalSeverity.WARN,
                        Pattern.compile("(?i)zero_yield|yield 0 ent|output-ceiling guard|invalid output|malformed json")),
                new LogSignalRule("health-timeout", LogSignalSeverity.WARN,
                        Pattern.compile("(?i)health check timed out|started but health check timed out")),
                new LogSignalRule("diagnostic-failed", LogSignalSeverity.WARN,
                        Pattern.compile("(?i)Diagnostic failed")),
                new LogSignalRule("error-level", LogSignalSeverity.ERROR,
                        Pattern.compile("\\b(ERROR|FATAL)\\b"))
        );

        enum LogSignalSeverity {
            ERROR,
            WARN
        }

        record LogSignalRule(String id, LogSignalSeverity severity, Pattern pattern) {}

        record LogSignal(String id, LogSignalSeverity severity, String logName, int lineNumber, String line) {}

        /**
         * Default monitor mode keeps only the latest log per subprocess type while
         * preserving project service logs. Use --all when historical subprocess logs are needed.
         */
        static Map<String, Path> selectMonitorLogFiles(Map<String, Path> logFiles) {
            Map<String, Map.Entry<String, Path>> selectedBySource = new LinkedHashMap<>();
            for (Map.Entry<String, Path> entry : logFiles.entrySet()) {
                String source = monitorSourceKey(entry.getKey());
                Map.Entry<String, Path> current = selectedBySource.get(source);
                if (current == null || lastModified(entry.getValue()) > lastModified(current.getValue())) {
                    selectedBySource.put(source, entry);
                }
            }
            Map<String, Path> selected = new LinkedHashMap<>();
            for (Map.Entry<String, Path> entry : selectedBySource.values()) {
                selected.put(entry.getKey(), entry.getValue());
            }
            return selected;
        }

        private static String monitorSourceKey(String displayName) {
            if (displayName == null || displayName.equals("mcp-activity.log")) {
                return displayName;
            }
            if (displayName.startsWith("data/logs/")) {
                String fileName = displayName.substring("data/logs/".length());
                String lower = fileName.toLowerCase(Locale.ROOT);
                String stream = lower.endsWith(".err.log") ? "err" : lower.endsWith(".out.log") ? "out" : "log";
                if (lower.contains("staging")) {
                    return "data/logs/staging." + stream;
                }
                if (lower.contains("serving")) {
                    return "data/logs/serving." + stream;
                }
                if (lower.contains("app") || lower.contains("web") || lower.contains("main")) {
                    return "data/logs/app." + stream;
                }
                return displayName;
            }
            int slash = displayName.indexOf('/');
            return slash > 0 ? displayName.substring(0, slash) : displayName;
        }

        private static long lastModified(Path path) {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }

        /** Scan recent log lines for targeted failure signals. */
        static int scanLogs(Map<String, Path> logFiles, int n) {
            List<LogSignal> findings = new ArrayList<>();
            for (Map.Entry<String, Path> entry : logFiles.entrySet()) {
                try {
                    findings.addAll(scanLogFile(entry.getKey(), entry.getValue(), n));
                } catch (IOException e) {
                    findings.add(new LogSignal("log-read-failed", LogSignalSeverity.WARN,
                            entry.getKey(), 0, "could not read log: " + e.getMessage()));
                }
            }

            if (findings.isEmpty()) {
                System.out.println("Log scan: no failure signals found across " + logFiles.size()
                        + " log file(s) (last " + Math.max(0, n) + " lines each).");
                return 0;
            }

            long errors = findings.stream().filter(f -> f.severity() == LogSignalSeverity.ERROR).count();
            long warnings = findings.size() - errors;
            System.out.println("Log scan found " + findings.size() + " signal(s) across "
                    + logFiles.size() + " log file(s): " + errors + " error, " + warnings + " warning.");
            for (LogSignal finding : findings) {
                String location = finding.lineNumber() > 0
                        ? finding.logName() + ":" + finding.lineNumber()
                        : finding.logName();
                System.out.println("  [" + finding.severity() + "] " + finding.id()
                        + " " + location + " - " + finding.line());
            }
            return errors > 0 ? 2 : 1;
        }

        static List<LogSignal> scanLogFile(String displayName, Path path, int n) throws IOException {
            List<LogSignal> findings = new ArrayList<>();
            if (n <= 0) {
                return findings;
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - n);
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                for (LogSignalRule rule : DEFAULT_SCAN_RULES) {
                    if (rule.pattern().matcher(line).find()) {
                        findings.add(new LogSignal(rule.id(), rule.severity(), displayName, i + 1, trimLogLine(line)));
                        break;
                    }
                }
            }
            return findings;
        }

        private static String trimLogLine(String line) {
            String normalized = line == null ? "" : line.strip();
            if (normalized.length() <= 500) {
                return normalized;
            }
            return normalized.substring(0, 497) + "...";
        }

        /**
         * Follow all discovered log files simultaneously by shelling out to {@code tail -f}.
         * Falls back to a simple polling loop if {@code tail} is not available.
         */
        private static int followLogs(Map<String, Path> logFiles) {
            List<String> cmd = new ArrayList<>();
            cmd.add("tail");
            cmd.add("-f");
            for (Path p : logFiles.values()) {
                cmd.add(p.toAbsolutePath().toString());
            }
            try {
                Process proc = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .start();
                // Forward output to stdout until interrupted
                Thread reader = new Thread(() -> {
                    try (BufferedReader br =
                                 new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            System.out.println(line);
                        }
                    } catch (IOException ignored) {}
                });
                reader.setDaemon(true);
                reader.start();
                // Block until Ctrl+C
                Runtime.getRuntime().addShutdownHook(new Thread(proc::destroyForcibly));
                proc.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // tail not available — fall back to printing last N lines and explaining
                System.out.println("Could not start 'tail -f': " + e.getMessage());
                System.out.println("Showing last 50 lines of each log instead.");
                tailLogs(logFiles, 50);
            }
            return 0;
        }

        /** Read the last {@code n} lines of a file efficiently. */
        static List<String> tailFile(Path file, int n) throws IOException {
            if (n <= 0) {
                return List.of();
            }
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (all.size() <= n) return all;
            return all.subList(all.size() - n, all.size());
        }

        /** Print paths that were searched, for the empty-result case. */
        private static void printSearchPaths(Path projectRoot) {
            System.out.println("Looked at:");
            System.out.println("  " + LogPaths.logsDirectory(projectRoot).toPath().resolve("subprocesses").toAbsolutePath()
                    + "/**/*.log");
            System.out.println("  " + projectRoot.toFile().getAbsolutePath() + "/data/logs/*.out.log");
            System.out.println("  " + projectRoot.toFile().getAbsolutePath() + "/data/logs/*.err.log");
            System.out.println("  " + LogPaths.logsDirectory(projectRoot).toPath().resolve("mcp-activity.log").toAbsolutePath()
                    + "  (use --all to include)");
            System.out.println("Start services first: kompile project start");
        }

        private static String relativeDisplayPath(Path baseDir, Path target) {
            try {
                return baseDir.relativize(target).toString();
            } catch (IllegalArgumentException e) {
                return target.getFileName().toString();
            }
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

        @Option(names = "--app-only", description = "Only start the admin console (kompile-app-main).")
        private boolean appOnly;

        @Option(names = "--chat-only", description = "Only start the chat app.")
        private boolean chatOnly;

        @Option(names = "--crawl-manager-only", description = "Only start the crawl manager.")
        private boolean crawlManagerOnly;

        @Option(names = "--url", description = "Base URL of kompile-app for health checks.")
        private String appUrl;

        @Option(names = {"--port", "-p"}, description = "Localhost kompile-app port.")
        private Integer port;

        @Option(names = "--dry-run", description = "Print selected steps without running them.")
        private boolean dryRun;

        @Override
        public Integer call() throws Exception {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = requireExistingProjectRoot(store, root);
            KompileProjectManifest manifest = ProjectCrawlCommand.autoconfigureModels(store, projectRoot, store.load(projectRoot));
            store.syncProjectRegistries(projectRoot);
            printServePlan(manifest, projectRoot);
            return ProjectCrawlCommand.runServeSelection(store, manifest, projectRoot, workflowId,
                    stagingOnly, servingOnly, appOnly, chatOnly, crawlManagerOnly, appUrl, port, dryRun);
        }
    }
}
