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

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;
import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.cli.main.CliProcessLauncher;
import ai.kompile.cli.main.app.CrawlCommand;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.manage.ServiceManager;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectPipeline;
import ai.kompile.project.KompileProjectScript;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.KompileProjectWorkflow;
import ai.kompile.project.KompileProjectWorkflowStep;
import ai.kompile.utils.NativeImageInfo;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.parser.Parser;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static ai.kompile.cli.main.project.ProjectCommandUtils.firstNonBlank;
import static ai.kompile.cli.main.project.ProjectCommandUtils.hasTag;
import static ai.kompile.cli.main.project.ProjectCommandUtils.jsonArray;
import static ai.kompile.cli.main.project.ProjectCommandUtils.jsonString;
import static ai.kompile.cli.main.project.ProjectCommandUtils.normalizeEnum;
import static ai.kompile.cli.main.project.ProjectCommandUtils.requireExistingProjectRoot;
import static ai.kompile.cli.main.project.ProjectCommandUtils.resolveProjectRoot;
import static ai.kompile.cli.main.project.ProjectCommandUtils.shellQuote;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printCrawlPlan;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printWorkflows;

/**
 * Picocli subcommand group for crawl and workflow management.
 * Contains Serve, Crawl, RunCrawlProfile, ListWorkflows, AddWorkflow, RunWorkflow subcommands,
 * plus the full local crawl engine.
 */
@Command(name = "crawl-group",
        mixinStandardHelpOptions = true,
        description = "Manage Kompile project crawls and workflows.",
        subcommands = {
                ProjectCrawlCommand.Serve.class,
                ProjectCrawlCommand.Crawl.class,
                ProjectCrawlCommand.RunCrawlProfile.class,
                ProjectCrawlCommand.ListWorkflows.class,
                ProjectCrawlCommand.AddWorkflow.class,
                ProjectCrawlCommand.RunWorkflow.class
        })
public class ProjectCrawlCommand implements Callable<Integer> {

    /** Workflow-step template token standing in for "the kompile server". */
    private static final String APP_URL_TOKEN = "${appUrl}";
    private static final int LOCAL_IO_BUFFER_CHARS = 16 * 1024;
    private static final int NO_OP_STREAM_BATCH_CHARS = 64 * 1024;
    private static final int MAX_HTML_TOKEN_CHARS = 8 * 1024;

    private static final Set<String> LOCAL_KNOWLEDGE_STOP_WORDS = Set.of(
            "the", "and", "for", "that", "with", "this", "from", "are", "was", "were",
            "will", "you", "your", "have", "has", "had", "not", "but", "all", "can",
            "our", "into", "about", "their", "there", "these", "those", "then", "than",
            "also", "over", "under", "using", "use", "used", "per", "via", "its", "it"
    );

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @Command(name = "serve", mixinStandardHelpOptions = true,
            description = "Run the project's managed service-start workflow or one service script.")
    public static class Serve implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--workflow", description = "Explicit service-start workflow ID or name.")
        private String workflowId;

        @Option(names = "--staging-only", description = "Start only model staging.")
        private boolean stagingOnly;

        @Option(names = "--serving-only", description = "Start only standalone model serving when the project requires it.")
        private boolean servingOnly;

        @Option(names = "--app-only", description = "Start only the admin application.")
        private boolean appOnly;

        @Option(names = "--chat-only", description = "Start only the chat application.")
        private boolean chatOnly;

        @Option(names = "--crawl-manager-only", description = "Start only the crawl-manager application.")
        private boolean crawlManagerOnly;

        @Option(names = "--url", description = "Pin the backend base URL for workflow template expansion.")
        private String appUrl;

        @Option(names = {"--port", "-p"}, description = "Pin a localhost backend port for workflow template expansion.")
        private Integer port;

        @Option(names = "--dry-run", description = "Print selected steps without running them.")
        private boolean dryRun;

        @Override
        public Integer call() throws Exception {
            int selectedServices = (stagingOnly ? 1 : 0)
                    + (servingOnly ? 1 : 0)
                    + (appOnly ? 1 : 0)
                    + (chatOnly ? 1 : 0)
                    + (crawlManagerOnly ? 1 : 0);
            if (selectedServices > 1 || (selectedServices == 1
                    && workflowId != null && !workflowId.isBlank())) {
                System.err.println("Choose one --*-only option or --workflow, not multiple selections.");
                return 2;
            }
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = requireExistingProjectRoot(store, root);
            KompileProjectManifest manifest = store.ensureStandardServiceLifecycle(projectRoot);
            return runServeSelection(store, manifest, projectRoot, workflowId,
                    stagingOnly, servingOnly, appOnly, chatOnly, crawlManagerOnly,
                    appUrl, port, dryRun);
        }
    }

    @Command(name = "crawl", mixinStandardHelpOptions = true,
            description = "Start the project if needed and run the best available crawl workflow or profile.")
    public static class Crawl implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--workflow", description = "Crawl workflow ID or name. Auto-selected when omitted.")
        private String workflowId;

        @Option(names = {"--profile", "--id"}, description = "Crawl profile ID or name. Auto-selected when omitted.")
        private String profileId;

        @Option(names = "--url", description = "Pin the backend base URL, such as http://localhost:8082. "
                + "Defaults to routing each API to its service (crawl manager, chat, admin).")
        private String appUrl;

        @Option(names = {"--port", "-p"}, description = "Localhost kompile-app port.")
        private Integer port;

        @Option(names = "--watch", description = "Override profile watch setting.")
        private Boolean watch;

        @Option(names = "--local", negatable = true,
                description = "Run a local directory/file crawl and write project crawl artifacts when possible.")
        private Boolean local;

        @Option(names = "--serve", negatable = true,
                description = "Start project services before running a raw crawl profile.")
        private Boolean serve;

        @Option(names = "--dry-run", description = "Print selected steps without running them.")
        private boolean dryRun;

        @Option(names = {"--graph-extraction", "--graph"},
                description = "Compatibility flag; graph extraction is always enabled for project crawls.")
        private boolean graphExtraction;

        @Option(names = "--schema-preset",
                description = "Override schema preset ID for graph extraction.")
        private String schemaPreset;

        @Option(names = "--schema-mode",
                description = "Override graph schema mode: NONE, LENIENT, or STRICT.")
        private String schemaMode;

        @Override
        public Integer call() throws Exception {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = requireExistingProjectRoot(store, root);
            KompileProjectManifest manifest = autoconfigureModels(store, projectRoot, store.load(projectRoot));
            store.syncProjectRegistries(projectRoot);

            // An explicit --profile/--id targets a profile run; don't auto-select
            // the standard auto-ingest workflow over it (its health-check step
            // waits on the app and times out when services aren't running).
            KompileProjectWorkflow workflow = (profileId == null || workflowId != null)
                    ? selectCrawlWorkflow(store, manifest, workflowId)
                    : null;
            if (workflow != null) {
                printCrawlPlan(store, manifest, workflow, projectRoot);
                if (Boolean.TRUE.equals(serve)) {
                    int targetPort = crawlServicePort(appUrl, port);
                    if (new ServiceManager().checkHealth(targetPort)) {
                        System.out.println("Crawl backend already running at http://localhost:" + targetPort
                                + " — skipping start-services.");
                    } else {
                        int serveExit = runServeSelection(store, manifest, projectRoot, null,
                                false, false, false, false, false, appUrl, port, dryRun);
                        if (serveExit != 0) {
                            return serveExit;
                        }
                    }
                }
                return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
            }

            KompileProjectCrawlProfile profile = selectCrawlProfile(store, manifest, profileId);
            if (profile == null) {
                System.err.println("No crawl workflow or crawl profile found in this project.");
                System.err.println("Add one with: kompile project crawl-add --source <path-or-url>");
                return 1;
            }

            // Check local-crawl eligibility BEFORE forcing graph extraction so that file-only
            // profiles with no graph extraction can still run headlessly (no app required).
            if (shouldRunLocalCrawl(profile, local, appUrl, port)) {
                return runLocalCrawl(profile, projectRoot, dryRun);
            }

            // For app-backed runs, graph extraction is mandatory; schema/model remain configurable.
            profile.setGraphExtraction(true);
            if (schemaPreset != null) {
                profile.setSchemaPresetId(schemaPreset);
            }
            if (schemaMode != null) {
                profile.setGraphSchemaMode(schemaMode);
            }

            boolean shouldServe = serve == null || serve;
            if (shouldServe) {
                int targetPort = crawlServicePort(appUrl, port);
                // Skip start-services if the crawl backend is already healthy on the target port
                if (new ServiceManager().checkHealth(targetPort)) {
                    System.out.println("Crawl backend already running at http://localhost:" + targetPort
                            + " — skipping start-services.");
                } else {
                    int serveExit = runServeSelection(store, manifest, projectRoot, null,
                            false, false, false, false, false, appUrl, port, dryRun);
                    if (serveExit != 0) {
                        return serveExit;
                    }
                    if (!dryRun && !ensureCrawlBackend(targetPort, appUrl, projectRoot)) {
                        return 1;
                    }
                }
            }

            List<String> args = buildCrawlArgs(profile, appUrl, port, watch);
            if (dryRun) {
                System.out.println("kompile app crawl " + String.join(" ", quoteArgs(args)));
                return 0;
            }
            return new CommandLine(new CrawlCommand()).execute(args.toArray(String[]::new));
        }
    }

    @Command(name = "crawl-run", mixinStandardHelpOptions = true,
            description = "Run a managed crawl profile through the existing app crawl command.")
    public static class RunCrawlProfile implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--id", "--profile"}, required = true, description = "Crawl profile ID or name.")
        private String profileId;

        @Option(names = "--url", description = "Pin the backend base URL, such as http://localhost:8082. "
                + "Defaults to routing each API to its service (crawl manager, chat, admin).")
        private String appUrl;

        @Option(names = {"--port", "-p"}, description = "Localhost kompile-app port.")
        private Integer port;

        @Option(names = "--watch", description = "Override profile watch setting.")
        private Boolean watch;

        @Option(names = "--local", negatable = true,
                description = "Run a local directory/file crawl and write project crawl artifacts when possible.")
        private Boolean local;

        @Option(names = "--dry-run", description = "Print the app crawl command without running it.")
        private boolean dryRun;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = requireExistingProjectRoot(store, root);
            KompileProjectManifest manifest = store.load(projectRoot);
            KompileProjectCrawlProfile profile = store.findCrawlProfile(manifest, profileId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown crawl profile: " + profileId));
            if (shouldRunLocalCrawl(profile, local, appUrl, port)) {
                return runLocalCrawl(profile, projectRoot, dryRun);
            }
            List<String> args = buildCrawlArgs(profile, appUrl, port, watch);
            if (dryRun) {
                System.out.println("kompile app crawl " + String.join(" ", quoteArgs(args)));
                return 0;
            }
            return new CommandLine(new CrawlCommand()).execute(args.toArray(String[]::new));
        }
    }

    @Command(name = "workflow-list", mixinStandardHelpOptions = true,
            description = "List managed project workflows.")
    public static class ListWorkflows implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectManifest manifest = store.load(requireExistingProjectRoot(store, root));
            printWorkflows(manifest);
            return 0;
        }
    }

    @Command(name = "workflow-add", mixinStandardHelpOptions = true,
            description = "Register a simple workflow with one step.")
    public static class AddWorkflow implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--id", description = "Workflow ID. Defaults to a slug of the name.")
        private String id;

        @Option(names = "--name", required = true, description = "Workflow display name.")
        private String name;

        @Option(names = "--phase", description = "Workflow phase: init, start, crawl, verify, analyze, stop, run.", defaultValue = "run")
        private String phase;

        @Option(names = "--description", description = "Workflow description.")
        private String description;

        @Option(names = "--step-type", required = true,
                description = "Step type: SCRIPT, COMMAND, CRAWL, HTTP, HEALTH_CHECK, WAIT.")
        private String stepType;

        @Option(names = "--step-ref", description = "Referenced script ID or crawl profile ID.")
        private String stepRef;

        @Option(names = "--command", description = "Command for COMMAND steps.")
        private String command;

        @Option(names = "--url", description = "URL for HTTP or HEALTH_CHECK steps.")
        private String url;

        @Option(names = "--method", description = "HTTP method.", defaultValue = "GET")
        private String method;

        @Option(names = "--body", description = "HTTP request body.")
        private String body;

        @Option(names = "--expected-status", description = "Expected HTTP status.")
        private Integer expectedStatus;

        @Option(names = "--timeout-seconds", description = "Step timeout seconds.")
        private Integer timeoutSeconds;

        @Option(names = "--wait-seconds", description = "Wait duration for WAIT steps.")
        private Integer waitSeconds;

        @Option(names = "--continue-on-failure", description = "Continue workflow when this step fails.")
        private boolean continueOnFailure;

        @Option(names = "--tag", split = ",", description = "Workflow tags. Can be repeated or comma-separated.")
        private List<String> tags = new ArrayList<>();

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = requireExistingProjectRoot(store, root);
            KompileProjectWorkflowStep step = new KompileProjectWorkflowStep();
            step.setName(name + " step");
            step.setType(stepType);
            step.setRef(stepRef);
            step.setCommand(command);
            step.setUrl(url);
            step.setMethod(method);
            step.setBody(body);
            step.setExpectedStatus(expectedStatus);
            step.setTimeoutSeconds(timeoutSeconds);
            step.setWaitSeconds(waitSeconds);
            step.setContinueOnFailure(continueOnFailure);

            KompileProjectWorkflow workflow = new KompileProjectWorkflow();
            workflow.setId(id);
            workflow.setName(name);
            workflow.setPhase(phase);
            workflow.setDescription(description);
            workflow.setTags(tags);
            workflow.setSteps(List.of(step));
            KompileProjectManifest manifest = store.registerWorkflow(projectRoot, workflow);
            printWorkflows(manifest);
            return 0;
        }
    }

    @Command(name = "workflow-run", mixinStandardHelpOptions = true,
            description = "Run a managed project workflow.")
    public static class RunWorkflow implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--id", "--workflow"}, required = true, description = "Workflow ID or name.")
        private String workflowId;

        @Option(names = "--url", description = "Base URL of kompile-app for crawl and HTTP template expansion.")
        private String appUrl;

        @Option(names = {"--port", "-p"}, description = "Localhost kompile-app port.")
        private Integer port;

        @Option(names = "--dry-run", description = "Print workflow steps without running them.")
        private boolean dryRun;

        @Override
        public Integer call() throws Exception {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = requireExistingProjectRoot(store, root);
            KompileProjectManifest manifest = store.ensureStandardServiceLifecycle(projectRoot);
            KompileProjectWorkflow workflow = store.findWorkflow(manifest, workflowId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown workflow: " + workflowId));
            return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
        }
    }

    // ==================== Workflow execution engine ====================

    static int runServeSelection(KompileProjectStore store, KompileProjectManifest manifest,
                                 Path projectRoot, String workflowId,
                                 boolean stagingOnly, boolean servingOnly, boolean appOnly,
                                 boolean chatOnly, boolean crawlManagerOnly,
                                 String appUrl, Integer port, boolean dryRun) throws Exception {
        manifest = store.ensureStandardServiceLifecycle(projectRoot);
        if (workflowId != null && !workflowId.isBlank()) {
            KompileProjectWorkflow workflow = store.findWorkflow(manifest, workflowId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown serve workflow: " + workflowId));
            return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
        }

        String scriptId = null;
        if (stagingOnly) {
            scriptId = "start-staging";
        } else if (servingOnly) {
            scriptId = "start-serving";
        } else if (appOnly) {
            scriptId = "start-app";
        } else if (chatOnly) {
            scriptId = "start-chat";
        } else if (crawlManagerOnly) {
            scriptId = "start-crawl-manager";
        }
        if (scriptId != null) {
            KompileService persona = personaForScript(scriptId);
            // Projects generated before the persona split ship start-app but no start-chat or
            // start-crawl-manager. Rather than fail on a missing script ref, launch the persona
            // directly — the script would only have wrapped the same command.
            if (persona != null && store.findScript(manifest, scriptId).isEmpty()) {
                Optional<String> command = defaultPersonaCommand(persona, projectRoot);
                if (command.isEmpty()) {
                    System.err.println(launcherFor(persona).componentId() + " not found — install with: "
                            + "kompile install " + launcherFor(persona).componentId());
                    return 1;
                }
                System.out.println("No " + scriptId + " script in this project — starting "
                        + launcherFor(persona).componentId() + " directly.");
                return runAdHocWorkflow(store, manifest, projectRoot, "serve-" + scriptId,
                        "Serve " + scriptId, List.of(commandStep(scriptId, command.get())),
                        appUrl, port, dryRun);
            }
            return runAdHocWorkflow(store, manifest, projectRoot, "serve-" + scriptId,
                    "Serve " + scriptId, List.of(scriptStep(scriptId, scriptId)), appUrl, port, dryRun);
        }

        KompileProjectWorkflow workflow = store.findWorkflow(manifest, "start-services").orElse(null);
        if (workflow == null) {
            workflow = manifest.getWorkflows().stream()
                    .filter(candidate -> "START".equals(normalizeEnum(candidate.getPhase())))
                    .findFirst()
                    .orElse(null);
        }
        if (workflow != null) {
            return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
        }

        if (store.findScript(manifest, "start-all").isPresent()) {
            return runAdHocWorkflow(store, manifest, projectRoot, "serve-start-all", "Serve project",
                    List.of(scriptStep("start-all", "start-all")), appUrl, port, dryRun);
        }
        System.err.println("No start workflow or start-all script found in this project.");
        return 1;
    }

    private static int runAdHocWorkflow(KompileProjectStore store, KompileProjectManifest manifest,
                                        Path projectRoot, String id, String name,
                                        List<KompileProjectWorkflowStep> steps,
                                        String appUrl, Integer port, boolean dryRun) throws Exception {
        KompileProjectWorkflow workflow = new KompileProjectWorkflow();
        workflow.setId(id);
        workflow.setName(name);
        workflow.setPhase("run");
        workflow.setSteps(steps);
        return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
    }

    static int runWorkflow(KompileProjectStore store, KompileProjectManifest manifest,
                           KompileProjectWorkflow workflow, Path projectRoot,
                           String appUrl, Integer port, boolean dryRun) throws Exception {
        // Null means "route each step by its API path" — crawl steps go to the crawl manager,
        // chat/RAG steps to the chat app. A pinned --url/--port still overrides everything.
        String baseUrl = firstNonBlank(appUrl, port == null ? null : "http://localhost:" + port);
        System.out.println("Workflow: " + workflow.getName() + " (" + workflow.getId() + ")");
        int index = 1;
        for (KompileProjectWorkflowStep step : workflow.getSteps()) {
            System.out.println("[" + index + "/" + workflow.getSteps().size() + "] " + step.getName()
                    + " <" + step.getType() + ">");
            int exitCode = runWorkflowStep(store, manifest, step, projectRoot, baseUrl, dryRun);
            if (exitCode != 0 && !step.isContinueOnFailure()) {
                System.err.println("Workflow failed at step " + step.getId() + " with exit code " + exitCode);
                return exitCode;
            }
            index++;
        }
        return 0;
    }

    private static int runWorkflowStep(KompileProjectStore store, KompileProjectManifest manifest,
                                       KompileProjectWorkflowStep step, Path projectRoot,
                                       String baseUrl, boolean dryRun) throws Exception {
        String type = normalizeEnum(step.getType());
        return switch (type) {
            case "SCRIPT" -> runScriptStep(store, manifest, step, projectRoot, dryRun);
            case "COMMAND" -> runCommandStep(step, projectRoot, dryRun);
            case "CRAWL" -> runCrawlStep(store, manifest, step, projectRoot, baseUrl, dryRun);
            case "HTTP" -> runHttpStep(step, projectRoot, baseUrl, dryRun);
            case "HEALTH_CHECK" -> runHealthCheckStep(step, projectRoot, baseUrl, dryRun);
            case "WAIT" -> runWaitStep(step, dryRun);
            default -> throw new IllegalArgumentException("Unsupported workflow step type: " + step.getType());
        };
    }

    private static int runScriptStep(KompileProjectStore store, KompileProjectManifest manifest,
                                     KompileProjectWorkflowStep step, Path projectRoot,
                                     boolean dryRun) throws IOException, InterruptedException {
        KompileProjectScript script = store.findScript(manifest, step.getRef())
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow script ref: " + step.getRef()));
        String command = firstNonBlank(script.getCommand(), script.getPath());
        KompileProjectWorkflowStep commandStep = new KompileProjectWorkflowStep();
        commandStep.setCommand(command);
        commandStep.setWorkingDirectory(firstNonBlank(script.getWorkingDirectory(), step.getWorkingDirectory(), "."));
        Map<String, String> environment = new LinkedHashMap<>(defaultServeEnvironment(manifest, script, projectRoot));
        environment.putAll(step.getEnvironment());
        if ("start-serving".equals(normalizeScriptId(script))
                && firstNonBlank(environment.get("KOMPILE_SERVING_COMMAND"),
                System.getenv("KOMPILE_SERVING_COMMAND")) == null) {
            Optional<String> servingCommand = defaultServingCommand(manifest, projectRoot);
            if (servingCommand.isEmpty()) {
                System.out.println("  Pipeline serving is app-managed for this project — no standalone service started.");
                return 0;
            }
            environment.put("KOMPILE_SERVING_COMMAND", servingCommand.get());
        }
        commandStep.setEnvironment(environment);
        return runCommandStep(commandStep, projectRoot, dryRun);
    }

    static int runCommandStep(KompileProjectWorkflowStep step, Path projectRoot,
                              boolean dryRun) throws IOException, InterruptedException {
        String command = firstNonBlank(step.getCommand(), step.getRef());
        if (command == null) {
            throw new IllegalArgumentException("COMMAND workflow step requires command");
        }
        Path workdir = projectRoot.resolve(firstNonBlank(step.getWorkingDirectory(), ".")).normalize();
        if (!workdir.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Workflow step working directory escapes project root: " + workdir);
        }
        if (dryRun) {
            System.out.println("  " + command + "  (cwd=" + workdir + ")");
            return 0;
        }
        ProcessBuilder builder = new ProcessBuilder("bash", "-lc", command);
        builder.directory(workdir.toFile());
        builder.inheritIO();
        builder.environment().putAll(step.getEnvironment());
        return builder.start().waitFor();
    }

    private static int runCrawlStep(KompileProjectStore store, KompileProjectManifest manifest,
                                    KompileProjectWorkflowStep step, Path projectRoot, String baseUrl,
                                    boolean dryRun) {
        // If the workflow step has no ref (e.g. generated before the null-ref fix), fall back
        // to the first available crawl profile so existing manifests continue to work.
        String profileRef = firstNonBlank(step.getRef());
        if (profileRef == null && !manifest.getCrawlProfiles().isEmpty()) {
            profileRef = manifest.getCrawlProfiles().stream()
                    .map(KompileProjectCrawlProfile::getId)
                    .filter(id -> id != null && !id.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        final String resolvedRef = profileRef;
        KompileProjectCrawlProfile profile = store.findCrawlProfile(manifest, resolvedRef)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow crawl ref: " + step.getRef()));
        if (canRunLocalCrawl(profile) && isDefaultLocalAppUrl(baseUrl)) {
            return runLocalCrawl(profile, projectRoot, dryRun);
        }
        List<String> args = buildCrawlArgs(profile, baseUrl, null, null);
        if (dryRun) {
            System.out.println("  kompile app crawl " + String.join(" ", quoteArgs(args)));
            return 0;
        }
        return new CommandLine(new CrawlCommand()).execute(args.toArray(String[]::new));
    }

    private static int runHttpStep(KompileProjectWorkflowStep step, Path projectRoot,
                                   String baseUrl, boolean dryRun) throws IOException, InterruptedException {
        String url = resolveTemplate(firstNonBlank(step.getUrl(), step.getRef()), projectRoot, baseUrl);
        if (url == null) {
            throw new IllegalArgumentException("HTTP workflow step requires url");
        }
        String method = firstNonBlank(step.getMethod(), "GET").toUpperCase(Locale.ROOT);
        String body = resolveTemplate(step.getBody(), projectRoot, baseUrl);
        int expected = step.getExpectedStatus() == null ? 0 : step.getExpectedStatus();
        if (dryRun) {
            System.out.println("  " + method + " " + url + (body == null ? "" : " body=" + body));
            return 0;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(step.getTimeoutSeconds() == null ? 60 : step.getTimeoutSeconds()));
        if (body == null || body.isBlank()) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (expected > 0) {
            return response.statusCode() == expected ? 0 : response.statusCode();
        }
        return response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : response.statusCode();
    }

    private static int runHealthCheckStep(KompileProjectWorkflowStep step, Path projectRoot,
                                          String baseUrl, boolean dryRun) throws Exception {
        // Use the robust dual-probe (KompileHttpClient.isHealthy) when no explicit URL is
        // given, or when the URL is the legacy default "${appUrl}/actuator/health".
        // Generated kompile apps do NOT ship Spring Boot Actuator, so /actuator/health 404s.
        // KompileHttpClient.isHealthy() probes /actuator/health first and falls back to
        // /api/setup/status, which every generated app does expose.
        // Only bypass to a direct HTTP check when an explicit, non-default URL is set
        // (e.g. a kompile-model-staging instance that genuinely exposes /actuator/health).
        String stepUrl = firstNonBlank(step.getUrl(), step.getRef());
        boolean useKompileProbe = stepUrl == null
                || "${appUrl}/actuator/health".equals(stepUrl)
                || "${appUrl}/actuator/health".equals(step.getUrl());
        String resolvedUrl = useKompileProbe ? null : resolveTemplate(stepUrl, projectRoot, baseUrl);
        int timeoutSeconds = step.getTimeoutSeconds() == null ? 120 : step.getTimeoutSeconds();
        if (dryRun) {
            String probeTarget = baseUrl != null ? baseUrl : "any kompile service";
            String target = resolvedUrl != null ? resolvedUrl : probeTarget + " (kompile readiness probe)";
            System.out.println("  wait for " + target + " timeout=" + timeoutSeconds + "s");
            return 0;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                boolean healthy;
                if (useKompileProbe) {
                    healthy = (baseUrl != null ? new KompileHttpClient(baseUrl) : KompileHttpClient.routed())
                            .isHealthy();
                } else {
                    KompileProjectWorkflowStep httpStep = new KompileProjectWorkflowStep();
                    httpStep.setUrl(resolvedUrl);
                    httpStep.setExpectedStatus(step.getExpectedStatus() == null ? 200 : step.getExpectedStatus());
                    httpStep.setTimeoutSeconds(5);
                    healthy = runHttpStep(httpStep, projectRoot, baseUrl, false) == 0;
                }
                if (healthy) {
                    return 0;
                }
            } catch (Exception ignored) {
                // Retry until timeout.
            }
            Thread.sleep(2_000L);
        }
        return 1;
    }

    private static int runWaitStep(KompileProjectWorkflowStep step, boolean dryRun) throws InterruptedException {
        int seconds = step.getWaitSeconds() == null ? 1 : step.getWaitSeconds();
        if (dryRun) {
            System.out.println("  sleep " + seconds + "s");
            return 0;
        }
        Thread.sleep(Duration.ofSeconds(seconds).toMillis());
        return 0;
    }

    private static String resolveTemplate(String value, Path projectRoot, String baseUrl) {
        if (value == null) {
            return null;
        }
        return substituteAppUrl(value, baseUrl)
                .replace("${projectRoot}", projectRoot.toString());
    }

    /**
     * Replace every {@code ${appUrl}} with the base URL of the service that owns the path
     * following it. Workflow steps were authored against one server on :8080; now
     * {@code /api/unified-crawl} belongs to the crawl manager and {@code /api/rag} to chat,
     * so the token resolves per path. A pinned base ({@code --url}/{@code --port}) wins.
     */
    static String substituteAppUrl(String value, String pinnedBaseUrl) {
        int token = value.indexOf(APP_URL_TOKEN);
        if (token < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (token >= 0) {
            out.append(value, cursor, token);
            cursor = token + APP_URL_TOKEN.length();
            out.append(KompileServiceEndpoints.baseUrlForPath(value.substring(cursor), pinnedBaseUrl));
            token = value.indexOf(APP_URL_TOKEN, cursor);
        }
        out.append(value.substring(cursor));
        return out.toString();
    }

    private static Map<String, String> defaultServeEnvironment(KompileProjectManifest manifest,
                                                               KompileProjectScript script,
                                                               Path projectRoot) throws IOException {
        Map<String, String> environment = new LinkedHashMap<>();
        String scriptId = normalizeScriptId(script);
        if ("start-staging".equals(scriptId) && firstNonBlank(System.getenv("KOMPILE_STAGING_COMMAND")) == null) {
            Optional<String> stagingCmd = defaultStagingCommand(projectRoot);
            if (stagingCmd.isPresent()) {
                environment.put("KOMPILE_STAGING_COMMAND", stagingCmd.get());
            } else {
                environment.put("KOMPILE_STAGING_COMMAND",
                        "echo 'Model staging not found — install with: kompile install kompile-model-staging' >&2; exit 1");
            }
        } else {
            // Persona start scripts. start-app is the admin console; the chat app and the crawl
            // manager are separate processes with separate scripts, because /api/unified-crawl and
            // /api/agents/chat are no longer mounted on :8080.
            KompileService persona = personaForScript(scriptId);
            if (persona != null) {
                String variable = personaCommandVariable(persona);
                if (firstNonBlank(System.getenv(variable)) == null) {
                    PersonaLauncher launcher = launcherFor(persona);
                    defaultPersonaCommand(persona, projectRoot).ifPresentOrElse(
                            command -> environment.put(variable, command),
                            () -> environment.put(variable, "echo '" + launcher.componentId()
                                    + " not found — install with: kompile install "
                                    + launcher.componentId() + "' >&2; exit 1"));
                }
            }
        }
        return environment;
    }

    /** Script id → persona, for the three {@code start-*} scripts generated projects ship. */
    private static KompileService personaForScript(String scriptId) {
        if (scriptId == null) {
            return null;
        }
        return switch (scriptId) {
            case "start-app" -> KompileService.ADMIN;
            case "start-chat" -> KompileService.CHAT;
            case "start-crawl-manager" -> KompileService.CRAWL;
            default -> null;
        };
    }

    /**
     * Environment variable a generated project's start script reads for its launch command.
     * {@code KOMPILE_APP_COMMAND} keeps its name so projects generated before the split still run.
     */
    static String personaCommandVariable(KompileService service) {
        return switch (service) {
            case ADMIN -> "KOMPILE_APP_COMMAND";
            case CHAT -> "KOMPILE_CHAT_COMMAND";
            case CRAWL -> "KOMPILE_CRAWL_MANAGER_COMMAND";
        };
    }

    private static String normalizeScriptId(KompileProjectScript script) {
        return firstNonBlank(script.getId(), script.getName(), script.getPath());
    }

    private static Optional<String> defaultStagingCommand(Path projectRoot) throws IOException {
        String stagingPort = firstNonBlank(System.getenv("KOMPILE_STAGING_PORT"),
                System.getProperty("kompile.staging.port"), "8090");
        // If the staging service is already listening, return a no-op rather than spawning a duplicate
        if (isPortInUse(Integer.parseInt(stagingPort))) {
            System.out.println("Model staging already running on port " + stagingPort + " — skipping.");
            return Optional.of("echo 'Model staging already running on port " + stagingPort + " — skipping.'");
        }
        Path modelDir = projectRoot.resolve("data/models").normalize();
        ComponentRegistry registry = new ComponentRegistry();
        Optional<Path> stagingExecutable = configuredPath(
                "KOMPILE_MODEL_STAGING_EXECUTABLE", "kompile.model.staging.executable")
                .filter(path -> Files.isRegularFile(path) && Files.isExecutable(path))
                .or(() -> {
                    File binary = registry.getDistributionBinaryPath(
                            ComponentRegistry.KOMPILE_MODEL_STAGING);
                    return binary != null && binary.isFile() && binary.canExecute()
                            ? Optional.of(binary.toPath().toAbsolutePath().normalize())
                            : Optional.empty();
                });
        if (stagingExecutable.isPresent()) {
            return Optional.of(artifactLaunchCommand(stagingExecutable.get(), List.of(
                    "--server.port=" + stagingPort,
                    "--kompile.staging.models-dir=" + modelDir,
                    "--spring.main.banner-mode=off")));
        }
        if (CliProcessLauncher.requiresNativeChildren()) {
            System.err.println("Native Kompile execution requires bin/kompile-model-staging; "
                    + "the executable-JAR fallback is disabled.");
            return Optional.empty();
        }
        // JVM development resolution: explicit JAR → installed component → source-tree build output.
        Optional<Path> stagingJar = configuredPath("KOMPILE_MODEL_STAGING_JAR", "kompile.modelStaging.jar")
                .or(() -> {
                    File installed = registry.findInstalledJar(ComponentRegistry.KOMPILE_MODEL_STAGING);
                    return installed != null ? Optional.of(installed.toPath()) : Optional.empty();
                })
                .or(() -> findSourceRoot(projectRoot)
                        .flatMap(root -> findModuleExecutableJar(root, "kompile-model-staging")));
        if (stagingJar.isEmpty()) {
            System.err.println("No model staging jar found. Install with: kompile install kompile-model-staging"
                    + " or set KOMPILE_STAGING_COMMAND / KOMPILE_MODEL_STAGING_JAR.");
            return Optional.empty();
        }
        return Optional.of(artifactLaunchCommand(stagingJar.get(), List.of(
                "--server.port=" + stagingPort,
                "--kompile.staging.models-dir=" + modelDir,
                "--spring.main.banner-mode=off")));
    }

    private static Optional<String> defaultServingCommand(KompileProjectManifest manifest,
                                                          Path projectRoot) throws IOException {
        boolean standaloneServingRequired = manifest.getPipelines().stream()
                .filter(KompileProjectPipeline::isActive)
                .anyMatch(pipeline -> "SERVING".equals(normalizeEnum(pipeline.getRole()))
                        || hasTag(pipeline.getTags(), "serving"));
        if (!standaloneServingRequired) {
            return Optional.empty();
        }
        String servingPort = firstNonBlank(System.getenv("KOMPILE_SERVING_PORT"),
                System.getProperty("kompile.serving.port"), "8091");
        if (isPortInUse(Integer.parseInt(servingPort))) {
            System.out.println("Pipeline serving already running on port " + servingPort + " — skipping.");
            return Optional.of("echo 'Pipeline serving already running on port " + servingPort + " — skipping.'");
        }
        Path argsPath = resolveServingArgsPath(manifest, projectRoot);
        String configuredCommand = firstNonBlank(System.getenv("KOMPILE_PIPELINE_SERVING_COMMAND"),
                System.getProperty("kompile.pipelineServing.command"));
        if (configuredCommand != null) {
            return Optional.of(configuredCommand.replace("{args}", shellQuote(argsPath.toString())));
        }
        ComponentRegistry registry = new ComponentRegistry();
        Optional<Path> runtime = configuredPath(
                "KOMPILE_PIPELINE_SERVING_EXECUTABLE",
                "kompile.pipeline.serving.executable")
                .filter(path -> Files.isRegularFile(path) && Files.isExecutable(path))
                .or(() -> {
                    File binary = registry.getDistributionBinaryPath(
                            ComponentRegistry.KOMPILE_PIPELINE_SERVING);
                    Path path = binary == null ? null : binary.toPath().toAbsolutePath().normalize();
                    return path != null && Files.isRegularFile(path) && Files.isExecutable(path)
                            ? Optional.of(path) : Optional.empty();
                });
        if (runtime.isEmpty() && CliProcessLauncher.requiresNativeChildren()) {
            System.err.println("Native Kompile execution requires bin/kompile-pipeline-serving; "
                    + "the executable-JAR fallback is disabled.");
            return Optional.empty();
        }
        if (runtime.isEmpty()) {
            runtime = configuredPath(
                    "KOMPILE_PIPELINE_SERVING_JAR",
                    "kompile.pipeline.serving.jar")
                    .filter(Files::isRegularFile)
                    .or(() -> {
                    File installed = registry.findInstalledJar(
                            ComponentRegistry.KOMPILE_PIPELINE_SERVING);
                    return installed != null && installed.isFile()
                            ? Optional.of(installed.toPath().toAbsolutePath().normalize())
                            : Optional.empty();
                    })
                    .or(() -> findSourceRoot(projectRoot)
                            .flatMap(root -> findModuleExecutableJar(
                                    root, ComponentRegistry.KOMPILE_PIPELINE_SERVING)));
        }
        if (runtime.isEmpty()) {
            System.err.println("No standalone pipeline-serving binary or executable JAR found. "
                    + "Install kompile-pipeline-serving or set "
                    + "KOMPILE_PIPELINE_SERVING_EXECUTABLE / KOMPILE_PIPELINE_SERVING_JAR.");
            return Optional.empty();
        }
        return Optional.of(artifactLaunchCommand(
                runtime.get(), List.of(argsPath.toAbsolutePath().normalize().toString())));
    }

    /**
     * How one persona's launcher is discovered. Kept next to the launch code rather than on
     * {@link KompileService}, which describes routing — where a request goes — not where a jar
     * lives on this machine.
     *
     * @param componentId  install-registry id, for {@code ~/.kompile/components}
     * @param jarEnv       env var pinning the jar outright
     * @param jarProperty  system property pinning the jar outright
     * @param moduleName   Maven module directory, for a source checkout
     * @param portEnv      env var pinning the port
     * @param portProperty system property pinning the port
     */
    private record PersonaLauncher(String componentId, String jarEnv, String jarProperty,
                                   String moduleName, String portEnv, String portProperty) {
    }

    private static PersonaLauncher launcherFor(KompileService service) {
        return switch (service) {
            case ADMIN -> new PersonaLauncher(ComponentRegistry.KOMPILE_APP_MAIN,
                    "KOMPILE_APP_JAR", "kompile.app.jar", "kompile-app-main",
                    "KOMPILE_APP_PORT", "kompile.app.port");
            case CHAT -> new PersonaLauncher(ComponentRegistry.KOMPILE_APP_CHAT,
                    "KOMPILE_CHAT_JAR", "kompile.chat.jar", "kompile-app-chat",
                    "KOMPILE_CHAT_PORT", "kompile.chat.port");
            case CRAWL -> new PersonaLauncher(ComponentRegistry.KOMPILE_APP_CRAWL_MANAGER,
                    "KOMPILE_CRAWL_MANAGER_JAR", "kompile.crawlManager.jar", "kompile-app-crawl-manager",
                    "KOMPILE_CRAWL_MANAGER_PORT", "kompile.crawlManager.port");
        };
    }

    /**
     * Build the shell command that starts one persona: the admin console, chat, or the crawl
     * manager. Generated projects call this through {@code KOMPILE_*_COMMAND}, so a project that
     * only ships {@code start-app} keeps working — it just starts the admin console now, and the
     * crawl manager comes up under its own script.
     *
     * <p>Resolution order per persona: pinned jar env/property → installed component → the module's
     * build output in a source checkout. There is no {@code spring-boot:run} fallback: app-main is a
     * library whose plain artifact is a thin jar that cannot boot, so an absent jar is reported as
     * "install this" rather than papered over with a run goal that would fail later and less
     * legibly.</p>
     */
    private static Optional<String> defaultPersonaCommand(KompileService service, Path projectRoot) {
        PersonaLauncher launcher = launcherFor(service);
        int port = personaCommandPort(service, launcher);
        String label = launcher.componentId();
        // If it is already listening, emit a no-op rather than spawning a duplicate.
        if (isPortInUse(port)) {
            System.out.println(label + " already running on port " + port + " — skipping.");
            return Optional.of("echo '" + label + " already running on port " + port + " — skipping.'");
        }
        Optional<Path> jar = configuredPath(launcher.jarEnv(), launcher.jarProperty())
                .or(() -> {
                    File installed = new ComponentRegistry().findInstalledJar(launcher.componentId());
                    return installed != null ? Optional.of(installed.toPath()) : Optional.empty();
                })
                .or(() -> findSourceRoot(projectRoot)
                        .flatMap(root -> findModuleExecutableJar(root, launcher.moduleName())));
        return jar.map(path -> artifactLaunchCommand(path, List.of(
                "--server.port=" + port,
                "--kompile.project.root=" + projectRoot.toAbsolutePath().normalize(),
                "--spring.main.banner-mode=off")));
    }

    /**
     * Build the direct launch command used by generated project scripts. Native artifacts are
     * executed directly; JARs use the distribution runtime when available. Artifacts inside a
     * distribution receive the same side-loaded lib contract as the canonical launch scripts and
     * {@link ai.kompile.cli.main.manage.ServiceManager}: distribution properties plus lib/bin search
     * paths, without forced preloading.
     */
    static String artifactLaunchCommand(Path artifact, List<String> arguments) {
        Path normalizedArtifact = artifact.toAbsolutePath().normalize();
        Optional<Path> distributionHome = Optional.ofNullable(
                ComponentRegistry.inferDistributionHome(normalizedArtifact)).map(File::toPath);
        StringBuilder command = new StringBuilder();
        distributionHome.ifPresent(root -> {
            String nativeSearchPath = root.resolve("bin") + File.pathSeparator + root.resolve("lib");
            command.append("export KOMPILE_DIST_HOME=").append(shellQuote(root.toString())).append("; ")
                    .append("export KOMPILE_NATIVE_LIB_DIR=")
                    .append(shellQuote(root.resolve("lib").toString())).append("; ")
                    .append("export LD_LIBRARY_PATH=").append(shellQuote(nativeSearchPath))
                    .append("${LD_LIBRARY_PATH:+:\"$LD_LIBRARY_PATH\"}; ")
                    .append("export DYLD_LIBRARY_PATH=").append(shellQuote(nativeSearchPath))
                    .append("${DYLD_LIBRARY_PATH:+:\"$DYLD_LIBRARY_PATH\"}; ");
        });

        boolean jar = normalizedArtifact.getFileName().toString().endsWith(".jar");
        if (jar) {
            Path bundledJava = distributionHome
                    .map(root -> root.resolve("runtime/bin/java"))
                    .filter(Files::isExecutable)
                    .orElse(null);
            command.append("exec ")
                    .append(bundledJava == null
                            ? shellQuote(JavaRuntimeLocator.javaExecutable())
                            : shellQuote(bundledJava.toString()));
            distributionHome.ifPresent(root -> command.append(" ")
                    .append(shellQuote("-Dkompile.dist.home=" + root)));
            command.append(" -jar ").append(shellQuote(normalizedArtifact.toString()));
        } else {
            command.append("exec ").append(shellQuote(normalizedArtifact.toString()));
            distributionHome.ifPresent(root -> command.append(" ")
                    .append(shellQuote("-Dkompile.dist.home=" + root)));
        }
        if (arguments != null) {
            for (String argument : arguments) {
                command.append(" ").append(shellQuote(argument));
            }
        }
        return command.toString();
    }

    /**
     * Port to launch a persona on. An explicit port env/property wins — those name a port outright.
     * Otherwise the routing ladder decides, so the port a project <em>starts</em> a persona on is
     * the one the rest of the CLI <em>looks</em> for it on.
     */
    private static int personaCommandPort(KompileService service, PersonaLauncher launcher) {
        String pinned = firstNonBlank(System.getenv(launcher.portEnv()), System.getProperty(launcher.portProperty()));
        if (pinned != null) {
            try {
                return Integer.parseInt(pinned.trim());
            } catch (NumberFormatException e) {
                System.err.println("Ignoring non-numeric " + launcher.portEnv() + "=" + pinned);
            }
        }
        return KompileServiceEndpoints.resolve(service).port();
    }

    private static Path resolveServingArgsPath(KompileProjectManifest manifest, Path projectRoot) throws IOException {
        Optional<Path> configuredArgs = configuredPath("KOMPILE_PIPELINE_SERVING_ARGS", "kompile.pipelineServing.args");
        if (configuredArgs.isPresent()) {
            return configuredArgs.get();
        }
        Optional<KompileProjectPipeline> servingPipeline = manifest.getPipelines().stream()
                .filter(KompileProjectPipeline::isActive)
                .filter(pipeline -> "SERVING".equals(normalizeEnum(pipeline.getRole()))
                        || hasTag(pipeline.getTags(), "serving"))
                .findFirst();
        if (servingPipeline.isPresent() && firstNonBlank(servingPipeline.get().getDefinitionPath()) != null) {
            Path definition = projectRoot.resolve(servingPipeline.get().getDefinitionPath()).normalize();
            if (!definition.startsWith(projectRoot)) {
                throw new IllegalArgumentException("Serving pipeline definition escapes project root: " + definition);
            }
            if (Files.isRegularFile(definition)) {
                String definitionJson = Files.readString(definition, StandardCharsets.UTF_8);
                if (definitionJson.contains("\"pipelineDefinitionJson\"")) {
                    return definition;
                }
                return writeServingArgs(projectRoot, servingPipeline.get(), definitionJson);
            }
        }
        return writeServingArgs(projectRoot, servingPipeline.orElse(null), defaultCpuPipelineDefinition(manifest, servingPipeline.orElse(null)));
    }

    private static Path writeServingArgs(Path projectRoot, KompileProjectPipeline pipeline,
                                         String pipelineDefinitionJson) throws IOException {
        Path argsPath = projectRoot.resolve(".kompile/state/project-serving-args.json").normalize();
        if (!argsPath.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Serving args path escapes project root: " + argsPath);
        }
        Files.createDirectories(argsPath.getParent());
        String pipelineId = pipeline == null ? "project-cpu-noop" : firstNonBlank(pipeline.getPipelineId(), pipeline.getId(), "project-cpu-noop");
        String port = firstNonBlank(System.getenv("KOMPILE_SERVING_PORT"),
                System.getProperty("kompile.serving.port"), "9090");
        String argsJson = "{\n"
                + "  \"taskId\" : " + jsonString(pipelineId) + ",\n"
                + "  \"pipelineDefinitionJson\" : " + jsonString(pipelineDefinitionJson) + ",\n"
                + "  \"executionMode\" : \"PERSISTENT_SERVING\",\n"
                + "  \"requestDataJson\" : null,\n"
                + "  \"servingPort\" : " + port + ",\n"
                + "  \"nd4jConfigJson\" : \"{\\\"backend\\\":\\\"cpu\\\"}\",\n"
                + "  \"memoryStopPercent\" : 80,\n"
                + "  \"memoryCriticalPercent\" : 90,\n"
                + "  \"memoryKillPercent\" : 95,\n"
                + "  \"memoryCheckIntervalMs\" : 1000,\n"
                + "  \"gpuMemoryStopPercent\" : 100,\n"
                + "  \"gpuMemoryCriticalPercent\" : 100,\n"
                + "  \"gpuMemoryKillPercent\" : 100,\n"
                + "  \"heartbeatIntervalMs\" : 3000,\n"
                + "  \"callbackBaseUrl\" : null\n"
                + "}\n";
        Files.writeString(argsPath, argsJson, StandardCharsets.UTF_8);
        return argsPath;
    }

    private static String defaultCpuPipelineDefinition(KompileProjectManifest manifest, KompileProjectPipeline pipeline) {
        String pipelineId = pipeline == null ? "project-cpu-noop" : firstNonBlank(pipeline.getPipelineId(), pipeline.getId(), "project-cpu-noop");
        String displayName = pipeline == null ? "Project CPU no-op" : firstNonBlank(pipeline.getName(), pipelineId);
        String modelSetId = manifest.getModels().stream()
                .filter(KompileProjectModel::isRequired)
                .findFirst()
                .or(() -> manifest.getModels().stream().findFirst())
                .map(model -> firstNonBlank(model.getRegistryModelId(), model.getModelId(), model.getId()))
                .orElse("project-default");
        String port = firstNonBlank(System.getenv("KOMPILE_SERVING_PORT"),
                System.getProperty("kompile.serving.port"), "9090");
        return "{"
                + "\"pipelineId\":" + jsonString(pipelineId) + ","
                + "\"displayName\":" + jsonString(displayName) + ","
                + "\"description\":\"Generated CPU no-op pipeline for Kompile project serve.\","
                + "\"kind\":\"GENERIC\","
                + "\"topology\":\"SEQUENCE\","
                + "\"pipelineSpec\":{"
                + "\"@class\":\"ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline\","
                + "\"id\":" + jsonString(pipelineId) + ","
                + "\"steps\":[]"
                + "},"
                + "\"modelSetId\":" + jsonString(modelSetId) + ","
                + "\"serving\":{"
                + "\"heapSize\":\"512m\","
                + "\"port\":" + port + ","
                + "\"replicas\":1,"
                + "\"gpuDeviceId\":\"cpu\","
                + "\"memoryStopPercent\":80,"
                + "\"memoryCriticalPercent\":90,"
                + "\"memoryKillPercent\":95,"
                + "\"gpuStopPercent\":100,"
                + "\"gpuCriticalPercent\":100,"
                + "\"gpuKillPercent\":100,"
                + "\"heartbeatIntervalMs\":3000"
                + "},"
                + "\"builtin\":false,"
                + "\"enabled\":true,"
                + "\"tags\":{\"generated\":true,\"cpu\":true,\"projectServe\":true}"
                + "}";
    }

    private static Optional<Path> configuredPath(String envName, String propertyName) {
        String configured = firstNonBlank(System.getenv(envName), System.getProperty(propertyName));
        return configured == null ? Optional.empty() : Optional.of(Path.of(configured).toAbsolutePath().normalize());
    }

    /** Directory names never worth descending into when locating a module in a source checkout. */
    private static final Set<String> MODULE_SCAN_SKIP =
            Set.of("target", "node_modules", "src", ".git", "dist", "docs");

    /**
     * Find a module's runnable jar in a source checkout, by module directory name.
     *
     * <p>Searched rather than hardcoded because the nesting under {@code kompile-app/} moves:
     * app-main and the persona apps live under {@code kompile-app-parent/}, model-staging under
     * {@code kompile-models/}, pipeline-serving under {@code kompile-data/kompile-pipelines/}.
     * A path constant here silently stops resolving the next time a module is regrouped, and the
     * failure looks like "not installed" rather than "moved".</p>
     */
    private static Optional<Path> findModuleExecutableJar(Path sourceRoot, String moduleName) {
        return findModuleDirectory(sourceRoot.resolve("kompile-app"), moduleName, 3)
                .map(module -> module.resolve("target"))
                .filter(Files::isDirectory)
                .flatMap(ProjectCrawlCommand::pickExecutableJar);
    }

    private static Optional<Path> findModuleDirectory(Path dir, String moduleName, int depth) {
        if (depth < 0 || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        List<Path> children;
        try (Stream<Path> listing = Files.list(dir)) {
            children = listing.filter(Files::isDirectory)
                    .filter(path -> !MODULE_SCAN_SKIP.contains(path.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return Optional.empty();
        }
        for (Path child : children) {
            if (moduleName.equals(child.getFileName().toString())) {
                return Optional.of(child);
            }
        }
        // Breadth first: a module named X directly under kompile-app wins over one nested deeper.
        for (Path child : children) {
            Optional<Path> found = findModuleDirectory(child, moduleName, depth - 1);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * The runnable jar in a {@code target/} directory. {@code -exec.jar} wins outright: app-main's
     * plain artifact is a thin library jar that has no dependencies inside it and cannot boot.
     */
    private static Optional<Path> pickExecutableJar(Path target) {
        try (Stream<Path> listing = Files.list(target)) {
            List<Path> jars = listing
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar")
                                && !name.endsWith("-tests.jar");
                    })
                    .sorted()
                    .toList();
            return jars.stream()
                    .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
                    .findFirst()
                    .or(() -> jars.stream().findFirst());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> findSourceRoot(Path projectRoot) {
        List<Path> seeds = new ArrayList<>();
        String configured = firstNonBlank(System.getenv("KOMPILE_SOURCE_ROOT"),
                System.getenv("KOMPILE_DEV_ROOT"), System.getProperty("kompile.sourceRoot"));
        if (configured != null) {
            seeds.add(Path.of(configured));
        }
        seeds.add(Path.of("").toAbsolutePath());
        ProcessHandle.current().info().command().map(Path::of).ifPresent(seeds::add);
        seeds.add(projectRoot);
        for (Path seed : seeds) {
            Optional<Path> found = findSourceRootFrom(seed.toAbsolutePath().normalize());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> findSourceRootFrom(Path seed) {
        Path current = Files.isDirectory(seed) ? seed : seed.getParent();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("kompile-cli/pom.xml"))
                    && Files.isRegularFile(current.resolve("kompile-app/kompile-model-staging/pom.xml"))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    // ==================== Crawl workflow selection helpers ====================

    private static KompileProjectWorkflow selectCrawlWorkflow(KompileProjectStore store,
                                                              KompileProjectManifest manifest,
                                                              String workflowId) {
        if (workflowId != null && !workflowId.isBlank()) {
            return store.findWorkflow(manifest, workflowId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown crawl workflow: " + workflowId));
        }
        return store.findWorkflow(manifest, "pdf-vlm-ingest")
                .or(() -> store.findWorkflow(manifest, "initial-crawl"))
                .or(() -> manifest.getWorkflows().stream()
                        .filter(workflow -> "CRAWL".equals(normalizeEnum(workflow.getPhase()))
                                || workflow.getSteps().stream().anyMatch(step -> "CRAWL".equals(normalizeEnum(step.getType()))))
                        .findFirst())
                .orElse(null);
    }

    private static KompileProjectCrawlProfile selectCrawlProfile(KompileProjectStore store,
                                                                  KompileProjectManifest manifest,
                                                                  String profileId) {
        if (profileId != null && !profileId.isBlank()) {
            return store.findCrawlProfile(manifest, profileId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown crawl profile: " + profileId));
        }
        return manifest.getCrawlProfiles().stream()
                .filter(KompileProjectCrawlProfile::isMultimodal)
                .findFirst()
                .or(() -> manifest.getCrawlProfiles().stream().findFirst())
                .orElse(null);
    }

    static KompileProjectManifest autoconfigureModels(KompileProjectStore store, Path projectRoot,
                                                      KompileProjectManifest manifest) {
        boolean changed = false;
        for (KompileProjectCrawlProfile profile : manifest.getCrawlProfiles()) {
            if (profile.isMultimodal() && firstNonBlank(profile.getVlmModel()) != null
                    && store.findModel(manifest, profile.getVlmModel()).isEmpty()) {
                KompileProjectModel model = inferredModel(profile.getVlmModel(), "VLM",
                        List.of("vlm", "crawl", "autoconfig"));
                model.setMetadata(Map.of("inferredFromCrawlProfile", profile.getId()));
                manifest = store.registerModel(projectRoot, model);
                changed = true;
            }
            if (firstNonBlank(profile.getGraphModelName()) != null
                    && store.findModel(manifest, profile.getGraphModelName()).isEmpty()) {
                KompileProjectModel model = inferredModel(profile.getGraphModelName(), "GRAPH",
                        List.of("graph", "crawl", "autoconfig"));
                model.setSource(firstNonBlank(profile.getGraphModelProvider(), "runtime"));
                model.setMetadata(Map.of("inferredFromCrawlProfile", profile.getId()));
                manifest = store.registerModel(projectRoot, model);
                changed = true;
            }
            if (profile.isMultimodal() && firstNonBlank(profile.getVlmModel()) != null
                    && store.findPipeline(manifest, profile.getId() + "-vlm-ingest").isEmpty()) {
                KompileProjectPipeline pipeline = new KompileProjectPipeline();
                pipeline.setId(profile.getId() + "-vlm-ingest");
                pipeline.setPipelineId(profile.getId() + "-vlm-ingest");
                pipeline.setName(profile.getName() + " VLM ingest");
                pipeline.setRole("VLM_INGEST");
                pipeline.setVersion("1.0.0");
                pipeline.setModelRefs(List.of(profile.getVlmModel()));
                pipeline.setTags(List.of("vlm", "crawl", "autoconfig"));
                pipeline.setMetadata(Map.of("inferredFromCrawlProfile", profile.getId()));
                manifest = store.registerPipeline(projectRoot, pipeline);
                changed = true;
            }
        }
        return changed ? store.load(projectRoot) : manifest;
    }

    private static KompileProjectModel inferredModel(String modelId, String role, List<String> tags) {
        KompileProjectModel model = new KompileProjectModel();
        model.setModelId(modelId);
        model.setRole(role);
        model.setSource("project");
        model.setRegistryModelId(modelId);
        model.setRequired(true);
        model.setTags(tags);
        return model;
    }

    /**
     * Make sure something answers the crawl API on {@code port} before a crawl is submitted.
     * A project's start scripts may only bring up the admin app, which no longer serves
     * {@code /api/unified-crawl}, so fall back to starting the installed crawl-manager
     * component. A caller-pinned {@code --url} is never second-guessed: they named the server.
     */
    private static boolean ensureCrawlBackend(int port, String appUrl, Path projectRoot) throws Exception {
        KompileProjectWorkflowStep readiness = new KompileProjectWorkflowStep();
        readiness.setTimeoutSeconds(120);
        String baseUrl = firstNonBlank(appUrl, "http://localhost:" + port);
        if (runHealthCheckStep(readiness, projectRoot, baseUrl, false) == 0) {
            return true;
        }
        if (appUrl != null && !appUrl.isBlank()) {
            System.err.println("Crawl backend did not become ready at " + baseUrl + " within 120 seconds.");
            return false;
        }
        System.out.println("No crawl backend on port " + port + " — starting "
                + ComponentRegistry.KOMPILE_APP_CRAWL_MANAGER + "...");
        ServiceManager services = new ServiceManager();
        ServiceManager.ProcessResult started = services.startComponent(
                ComponentRegistry.KOMPILE_APP_CRAWL_MANAGER, port, List.of(),
                List.of("--kompile.project.root=" + projectRoot.toAbsolutePath().normalize()));
        if (!started.isSuccess() || !services.waitForHealth(port, 120)) {
            System.err.println("Could not start the crawl manager: " + started.getMessage());
            System.err.println("Install it with: kompile install " + ComponentRegistry.KOMPILE_APP_CRAWL_MANAGER
                    + ", or point --url at a running crawl backend.");
            return false;
        }
        return true;
    }

    /**
     * Local port of the service that answers the crawl API. {@code --port} pins it outright;
     * otherwise it comes from the routing ladder, which lands on the crawl manager (:8082)
     * rather than the admin console — {@code /api/unified-crawl} no longer lives on :8080.
     */
    private static int crawlServicePort(String appUrl, Integer port) {
        if (port != null) {
            return port;
        }
        return KompileServiceEndpoints.resolve(KompileService.CRAWL, appUrl).port();
    }

    // ==================== Local crawl engine ====================

    private static boolean shouldRunLocalCrawl(KompileProjectCrawlProfile profile, Boolean local,
                                               String appUrl, Integer port) {
        if (Boolean.FALSE.equals(local)) {
            return false;
        }
        boolean canRunLocal = canRunLocalCrawl(profile);
        if (Boolean.TRUE.equals(local) && !canRunLocal) {
            throw new IllegalArgumentException("Crawl profile requires kompile-app or model services and cannot run locally: "
                    + profile.getId());
        }
        return canRunLocal && (Boolean.TRUE.equals(local) || (appUrl == null && port == null));
    }

    static boolean canRunLocalCrawl(KompileProjectCrawlProfile profile) {
        if (profile == null || profile.getSources().isEmpty()) {
            return false;
        }
        // Multimodal filesystem profiles run through the isolated document-model worker.
        // Web/URL source types require network fetching handled by the app crawler.
        String sourceType = normalizeEnum(profile.getSourceType());
        if ("WEB".equals(sourceType) || "URL".equals(sourceType)) {
            return false;
        }
        // Any HTTP/HTTPS source string also needs the app crawler.
        for (String source : profile.getSources()) {
            String lower = firstNonBlank(source, "").toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return false;
            }
        }
        // All sources are local filesystem paths — can run headlessly.
        return true;
    }

    /**
     * True when the caller never named a specific backend, so a headless local crawl is still
     * on the table. Every persona's built-in local URL counts as "not named" — the split gave
     * localhost three default ports, not one.
     */
    private static boolean isDefaultLocalAppUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true;
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        for (KompileService service : KompileService.values()) {
            if (normalized.equals(service.defaultUrl())
                    || normalized.equals("http://127.0.0.1:" + service.defaultPort())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Structured result for callers that embed the local crawl engine (notably the stdio MCP server).
     * The programmatic path never writes to stdout and never probes a remote Kompile service.
     */
    public record LocalCrawlExecution(String crawlId,
                                      Path outputDirectory,
                                      Path markdownDirectory,
                                      int documentCount,
                                      int chunkCount,
                                      int markdownCount,
                                      String status,
                                      List<LocalCrawlFailure> documentFailures,
                                      boolean dryRun) {
        public LocalCrawlExecution {
            documentFailures = documentFailures == null ? List.of() : List.copyOf(documentFailures);
        }
    }

    /** A document extraction failure surfaced to local crawl callers and MCP tool responses. */
    public record LocalCrawlFailure(String documentId, String source, String relativePath,
                                    String message, String pipelineId, String pipelineType) {
    }

    /**
     * Executes the model-backed processor selected for one resolved document pipeline.
     *
     * <p>The default implementation is {@link LocalModelPipelineRunner#extract(Path, Path,
     * LocalCrawlCapabilities.ResolvedPipeline, String)}. Embedders may supply the same boundary
     * when exercising the complete crawl lifecycle in one JVM; normal CLI and MCP execution keeps
     * the request-scoped subprocess implementation.</p>
     */
    @FunctionalInterface
    public interface ModelPipelineExecutor {
        String extract(Path projectRoot, Path file,
                       LocalCrawlCapabilities.ResolvedPipeline pipeline,
                       String loadedText) throws Exception;
    }

    /**
     * Execute the project-local crawl engine without CLI output or remote registration.
     *
     * <p>This is the local backend behind the crawl MCP tools. Keeping it separate from
     * {@link #runLocalCrawl(KompileProjectCrawlProfile, Path, boolean)} is important for stdio:
     * arbitrary stdout would corrupt the MCP transport.</p>
     */
    public static LocalCrawlExecution executeLocalCrawl(KompileProjectCrawlProfile profile,
                                                        Path projectRoot,
                                                        boolean dryRun) throws IOException {
        return executeLocalCrawl(profile, projectRoot, dryRun, null);
    }

    /** Execute a local crawl with the same per-document pipeline request accepted by MCP. */
    public static LocalCrawlExecution executeLocalCrawl(KompileProjectCrawlProfile profile,
                                                        Path projectRoot,
                                                        boolean dryRun,
                                                        JsonNode request) throws IOException {
        return executeLocalCrawl(
                profile, projectRoot, dryRun, request, LocalModelPipelineRunner::extract);
    }

    /**
     * Execute a local crawl with an explicit model-pipeline boundary.
     *
     * <p>This overload exists for JVM embedding and integration harnesses. Production callers use
     * the four-argument overload and therefore retain the standalone subprocess contract.</p>
     */
    public static LocalCrawlExecution executeLocalCrawl(
            KompileProjectCrawlProfile profile,
            Path projectRoot,
            boolean dryRun,
            JsonNode request,
            ModelPipelineExecutor modelPipelineExecutor) throws IOException {
        if (modelPipelineExecutor == null) {
            throw new IllegalArgumentException("modelPipelineExecutor is required");
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        String crawlId = localArtifactId(profile);
        Path outputDir = normalizedRoot.resolve("data/crawls").resolve(crawlId).normalize();
        Path markdownDir = normalizedRoot.resolve("data/markdown").resolve(crawlId).normalize();
        if (!outputDir.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Local crawl output escapes project root: " + outputDir);
        }
        if (!markdownDir.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Local crawl markdown output escapes project root: " + markdownDir);
        }
        if (dryRun) {
            return new LocalCrawlExecution(crawlId, outputDir, markdownDir,
                    0, 0, 0, "DRY_RUN", List.of(), true);
        }

        Files.createDirectories(outputDir);
        Files.createDirectories(markdownDir);
        KompileProjectStore store = new KompileProjectStore();
        String projectName = null;
        try {
            KompileProjectManifest manifest = store.load(normalizedRoot);
            if (manifest != null) {
                projectName = manifest.getName();
            }
        } catch (Exception ignored) {
            // A plain code directory is a valid implicit local project.
        }
        LocalCrawlResult result = collectLocalCrawl(
                profile, normalizedRoot, outputDir, markdownDir, projectName, request,
                modelPipelineExecutor);
        writeLocalCrawlArtifacts(profile, normalizedRoot, outputDir, markdownDir, result);
        try {
            store.syncMarkdownCatalog(normalizedRoot);
            store.syncCrawlCatalog(normalizedRoot);
        } catch (Exception ignored) {
            // Catalog synchronization requires a manifest; local artifacts do not.
        }
        return new LocalCrawlExecution(crawlId, outputDir, markdownDir,
                result.documents().size(), result.chunkCount(), result.markdownCount(),
                result.status(), result.failures(), false);
    }

    static int runLocalCrawl(KompileProjectCrawlProfile profile, Path projectRoot, boolean dryRun) {
        try {
            LocalCrawlExecution execution = executeLocalCrawl(profile, projectRoot, dryRun);
            if (dryRun) {
                System.out.println("kompile project crawl local " + profile.getId());
                System.out.println("  output " + execution.outputDirectory());
                System.out.println("  markdown " + execution.markdownDirectory());
                for (String source : profile.getSources()) {
                    System.out.println("  source " + resolveLocalCrawlSource(projectRoot, source));
                }
                return 0;
            }

            System.out.println("Local crawl " + execution.status().toLowerCase(Locale.ROOT)
                    .replace('_', ' ') + ": " + profile.getId());
            System.out.println("  Documents: " + execution.documentCount());
            System.out.println("  Chunks: " + execution.chunkCount());
            System.out.println("  Markdown: " + execution.markdownCount());
            if (!execution.documentFailures().isEmpty()) {
                System.out.println("  Failed documents: " + execution.documentFailures().size());
                for (LocalCrawlFailure failure : execution.documentFailures()) {
                    System.out.println("    " + failure.relativePath() + ": " + failure.message());
                }
            }
            System.out.println("  Output: " + execution.outputDirectory());
            System.out.println("  Markdown output: " + execution.markdownDirectory());
            if (profile.getFactSheetName() != null && !profile.getFactSheetName().isBlank()) {
                System.out.println("  Fact sheet: " + profile.getFactSheetName());
                // Best-effort: register markdown as facts via running backend
                tryRegisterMarkdownAsFacts(profile.getFactSheetName());
            }
            return "FAILED".equals(execution.status()) ? 1
                    : "COMPLETED_WITH_ERRORS".equals(execution.status()) ? 2 : 0;
        } catch (IOException e) {
            System.err.println("Local crawl failed: " + e.getMessage());
            return 1;
        }
    }

    private static LocalCrawlResult collectLocalCrawl(KompileProjectCrawlProfile profile,
                                                      Path projectRoot,
                                                      Path outputDir,
                                                      Path markdownDir) throws IOException {
        return collectLocalCrawl(profile, projectRoot, outputDir, markdownDir, null, null,
                LocalModelPipelineRunner::extract);
    }

    private static LocalCrawlResult collectLocalCrawl(KompileProjectCrawlProfile profile,
                                                      Path projectRoot,
                                                      Path outputDir,
                                                      Path markdownDir,
                                                      String projectName,
                                                      JsonNode request,
                                                      ModelPipelineExecutor modelPipelineExecutor)
            throws IOException {
        List<LocalCrawlDocument> documents = new ArrayList<>();
        LocalCrawlStatistics statistics = new LocalCrawlStatistics();
        int maxDocuments = profile.getMaxDocuments();
        Path chunksPath = outputDir.resolve("chunks.jsonl");
        try (BufferedWriter chunks = Files.newBufferedWriter(chunksPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (String source : profile.getSources()) {
                Path sourcePath = resolveLocalCrawlSource(projectRoot, source);
                if (!Files.exists(sourcePath)) {
                    throw new IOException("Crawl source does not exist: " + sourcePath);
                }
                List<Path> sourceFiles = localCrawlFiles(sourcePath, projectRoot, outputDir, markdownDir, profile);
                for (Path file : sourceFiles) {
                    if (maxDocuments > 0 && documents.size() >= maxDocuments) {
                        break;
                    }
                    LocalCrawlDocument document = localCrawlDocument(projectRoot, sourcePath, file);
                    LocalCrawlCapabilities.ResolvedPipeline pipeline =
                            LocalCrawlCapabilities.resolve(request, profile, sourcePath, file);
                    LocalMarkdownArtifact markdown = writeLocalCrawlMarkdown(projectRoot, markdownDir,
                            document, file, profile, projectName, pipeline, modelPipelineExecutor);
                    document = document.withMarkdown(markdown);
                    if (markdown.markdownPath() != null) {
                        Path markdownPath = projectRoot.resolve(markdown.markdownPath()).normalize();
                        LocalChunkingStats documentStats = streamLocalCrawlChunks(
                                document, markdownPath, pipeline, chunks, statistics);
                        markdown = markdown.withStats(documentStats.markdownChars(), documentStats.wordCount());
                        document = document.withMarkdown(markdown);
                    }
                    documents.add(document);
                }
                if (maxDocuments > 0 && documents.size() >= maxDocuments) {
                    break;
                }
            }
        }
        return new LocalCrawlResult(documents, statistics.chunkCount,
                statistics.analysisWordCount, Map.copyOf(statistics.terms));
    }

    private static List<Path> localCrawlFiles(Path sourcePath, Path projectRoot, Path outputDir, Path markdownDir,
                                              KompileProjectCrawlProfile profile) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(sourcePath)) {
            if (includeLocalCrawlFile(sourcePath, sourcePath.getFileName(), projectRoot, outputDir, markdownDir, profile)) {
                files.add(sourcePath);
            }
            return files;
        }
        boolean hasExplicitIncludes = profile != null && !profile.getIncludePatterns().isEmpty();
        try (Stream<Path> stream = Files.walk(sourcePath)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> hasExplicitIncludes || isKnowledgeSource(
                            path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .filter(path -> includeLocalCrawlFile(path, sourcePath.relativize(path), projectRoot, outputDir,
                            markdownDir, profile))
                    .forEach(files::add);
        }
        files.sort(Path::compareTo);
        return files;
    }

    private static boolean includeLocalCrawlFile(Path file, Path relative, Path projectRoot, Path outputDir,
                                                 Path markdownDir,
                                                 KompileProjectCrawlProfile profile) {
        Path normalized = file.toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        if (normalized.startsWith(outputDir.toAbsolutePath().normalize())) {
            return false;
        }
        if (normalized.startsWith(markdownDir.toAbsolutePath().normalize())) {
            return false;
        }
        Path projectRelative = normalized.startsWith(root) ? root.relativize(normalized) : relative;
        if (!profile.isIncludeHidden() && hasHiddenPathSegment(projectRelative)) {
            return false;
        }
        if (matchesAny(projectRelative, List.of(".git/**", ".kompile/state/**", "data/crawls/**"))) {
            return false;
        }
        if (!profile.getExcludePatterns().isEmpty() && matchesAny(projectRelative, profile.getExcludePatterns())) {
            return false;
        }
        return profile.getIncludePatterns().isEmpty() || matchesAny(projectRelative, profile.getIncludePatterns());
    }

    private static boolean hasHiddenPathSegment(Path path) {
        for (Path segment : path) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(Path path, List<String> patterns) {
        for (String pattern : patterns) {
            if (matchesGlob(path, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesGlob(Path path, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String normalizedPattern = pattern.replace('\\', '/');
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalizedPattern);
        if (matcher.matches(path) || path.getFileName() != null && matcher.matches(path.getFileName())) {
            return true;
        }
        if (normalizedPattern.startsWith("**/")) {
            PathMatcher basenameMatcher = FileSystems.getDefault().getPathMatcher("glob:" + normalizedPattern.substring(3));
            return basenameMatcher.matches(path)
                    || path.getFileName() != null && basenameMatcher.matches(path.getFileName());
        }
        return false;
    }

    private static LocalCrawlDocument localCrawlDocument(Path projectRoot, Path sourcePath, Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        Path relative = normalized.startsWith(root) ? root.relativize(normalized) : sourcePath.relativize(normalized);
        if (relative.toString().isBlank()) {
            relative = normalized.getFileName();
        }
        String documentId = localArtifactId(relative.toString());
        String contentType = firstNonBlank(Files.probeContentType(normalized), "application/octet-stream");
        return new LocalCrawlDocument(documentId, normalized.toString(), relative.toString().replace('\\', '/'),
                Files.size(normalized), Files.getLastModifiedTime(normalized).toInstant().toString(), contentType,
                null, null, "PENDING", null, null, null, null, null, 0, 0);
    }

    private static LocalMarkdownArtifact writeLocalCrawlMarkdown(Path projectRoot, Path markdownDir,
                                                                 LocalCrawlDocument document, Path file) throws IOException {
        LocalCrawlCapabilities.ResolvedPipeline pipeline = LocalCrawlCapabilities.resolve(
                null, null, file, file);
        return writeLocalCrawlMarkdown(projectRoot, markdownDir, document, file, null, null, pipeline,
                LocalModelPipelineRunner::extract);
    }

    private static LocalMarkdownArtifact writeLocalCrawlMarkdown(Path projectRoot, Path markdownDir,
                                                                 LocalCrawlDocument document, Path file,
                                                                 KompileProjectCrawlProfile profile,
                                                                 String projectName,
                                                                 LocalCrawlCapabilities.ResolvedPipeline pipeline,
                                                                 ModelPipelineExecutor modelPipelineExecutor)
            throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!LocalCrawlCapabilities.loaderSupports(pipeline.loaderName(), file)) {
            return LocalMarkdownArtifact.failed("Loader '" + pipeline.loaderName()
                    + "' does not support " + file.getFileName(), pipeline);
        }
        Path bodyPath = null;
        Path temporaryMarkdown = null;
        try {
            bodyPath = Files.createTempFile(markdownDir, document.documentId() + "-body-", ".tmp");
            String title;
            try (Writer fileWriter = Files.newBufferedWriter(bodyPath, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
                 NormalizedTextWriter bodyWriter = new NormalizedTextWriter(fileWriter)) {
                if (LocalCrawlCapabilities.usesProcessingSubprocess(pipeline)) {
                    String extracted = modelPipelineExecutor.extract(projectRoot, file, pipeline, "");
                    bodyWriter.write(extracted);
                    title = file.getFileName().toString();
                } else {
                    title = streamLocalMarkdownBody(file, name, pipeline.loaderName(), bodyWriter);
                }
            }
            if (Files.size(bodyPath) == 0) {
                return LocalMarkdownArtifact.skipped("No extractable text", pipeline);
            }
            Path markdownPath = markdownDir.resolve(document.documentId() + ".md").normalize();
            if (!markdownPath.startsWith(markdownDir)) {
                throw new IllegalArgumentException("Markdown artifact escapes markdown directory: " + markdownPath);
            }
            temporaryMarkdown = Files.createTempFile(markdownDir, document.documentId() + "-markdown-", ".tmp");
            try (BufferedWriter markdown = Files.newBufferedWriter(temporaryMarkdown, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
                 BufferedReader body = Files.newBufferedReader(bodyPath, StandardCharsets.UTF_8)) {
                markdown.write(knowledgeMarkdownHeader(title, document, profile, projectName));
                char[] buffer = new char[LOCAL_IO_BUFFER_CHARS];
                int read;
                while ((read = body.read(buffer)) >= 0) {
                    if (read > 0) markdown.write(buffer, 0, read);
                }
                markdown.write('\n');
            }
            Files.move(temporaryMarkdown, markdownPath, StandardCopyOption.REPLACE_EXISTING);
            temporaryMarkdown = null;
            String relativeMarkdown = projectRoot.toAbsolutePath().normalize()
                    .relativize(markdownPath.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
            return LocalMarkdownArtifact.extracted(title, relativeMarkdown, pipeline);
        } catch (Exception e) {
            return LocalMarkdownArtifact.failed(e.getMessage(), pipeline);
        } finally {
            if (bodyPath != null) Files.deleteIfExists(bodyPath);
            if (temporaryMarkdown != null) Files.deleteIfExists(temporaryMarkdown);
        }
    }

    private static boolean isKnowledgeSource(String name) {
        return name.endsWith(".pdf") || isTextKnowledgeSource(name) || isCodeKnowledgeSource(name);
    }

    private static boolean isTextKnowledgeSource(String name) {
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")
                || name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".yaml")
                || name.endsWith(".yml") || name.endsWith(".csv") || name.endsWith(".tsv")
                || name.endsWith(".html")
                || name.endsWith(".htm") || name.endsWith(".xml") || name.endsWith(".properties");
    }

    private static boolean isCodeKnowledgeSource(String name) {
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts")
                || name.endsWith(".scala") || name.endsWith(".groovy")
                || name.endsWith(".clj") || name.endsWith(".cljs")
                || name.endsWith(".py") || name.endsWith(".js") || name.endsWith(".jsx")
                || name.endsWith(".ts") || name.endsWith(".tsx")
                || name.endsWith(".go") || name.endsWith(".rs")
                || name.endsWith(".c") || name.endsWith(".h")
                || name.endsWith(".cc") || name.endsWith(".cpp") || name.endsWith(".cxx")
                || name.endsWith(".hpp") || name.endsWith(".cs")
                || name.endsWith(".fs") || name.endsWith(".fsx")
                || name.endsWith(".rb") || name.endsWith(".php")
                || name.endsWith(".swift") || name.endsWith(".m") || name.endsWith(".mm")
                || name.endsWith(".sh") || name.endsWith(".bash") || name.endsWith(".zsh")
                || name.endsWith(".fish") || name.endsWith(".sql")
                || name.endsWith(".proto") || name.endsWith(".graphql") || name.endsWith(".gql")
                || name.endsWith(".toml") || name.endsWith(".gradle")
                || name.endsWith(".vue") || name.endsWith(".svelte")
                || name.endsWith(".css") || name.endsWith(".scss")
                || name.endsWith(".sass") || name.endsWith(".less")
                || name.equals("dockerfile") || name.equals("makefile");
    }

    private static String streamLocalMarkdownBody(Path file, String name, String loaderName,
                                                  Writer output) throws IOException {
        String title = file.getFileName().toString();
        if ("pdf".equals(loaderName)) {
            streamPdfText(file, output);
        } else if ("html".equals(loaderName)) {
            title = firstNonBlank(streamHtmlToMarkdown(file, output), title);
        } else if ("table".equals(loaderName)) {
            if (name.endsWith(".html") || name.endsWith(".htm")) {
                title = firstNonBlank(streamHtmlToMarkdown(file, output), title);
            } else if (name.endsWith(".csv") || name.endsWith(".tsv")) {
                streamDelimitedTable(file, name.endsWith(".tsv") ? '\t' : ',', output);
            } else {
                streamPdfText(file, output);
            }
        } else {
            streamUtf8(file, output);
        }
        return title;
    }

    private static void streamUtf8(Path file, Writer output) throws IOException {
        try (BufferedReader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            char[] buffer = new char[LOCAL_IO_BUFFER_CHARS];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
        }
    }

    private static void streamPdfText(Path file, Writer output) throws IOException {
        if (NativeImageInfo.isRunningInNativeImage()) {
            streamPdfTextWithPdftotext(file, output);
            return;
        }
        boolean wrotePage = false;
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                output.write(stripper.getText(pdf));
                output.write("\n\n");
                wrotePage = true;
            }
        } catch (IOException e) {
            if (!wrotePage) {
                streamPdfTextWithPdftotext(file, output);
                return;
            }
            throw new IOException("Unable to extract PDF text from " + file + ": " + e.getMessage(), e);
        }
    }

    private static void streamPdfTextWithPdftotext(Path file, Writer output) throws IOException {
        Process process = new ProcessBuilder("pdftotext", "-layout", file.toString(), "-")
                .redirectErrorStream(true)
                .start();
        StringBuilder tail = new StringBuilder();
        try {
            try (Reader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[LOCAL_IO_BUFFER_CHARS];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    output.write(buffer, 0, read);
                    tail.append(buffer, 0, read);
                    if (tail.length() > 4_000) tail.delete(0, tail.length() - 4_000);
                }
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("pdftotext timed out for " + file);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while extracting PDF text: " + file, e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("pdftotext failed for " + file + ": " + tail.toString().strip());
        }
    }

    private static String streamHtmlToMarkdown(Path file, Writer output) throws IOException {
        StreamingHtmlMarkdown html = new StreamingHtmlMarkdown(output);
        try (BufferedReader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            char[] buffer = new char[LOCAL_IO_BUFFER_CHARS];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) html.accept(buffer, read);
            }
        }
        html.finish();
        return html.title();
    }

    private static void streamDelimitedTable(Path file, char delimiter, Writer output) throws IOException {
        boolean quoted = false;
        boolean rowStarted = false;
        boolean firstRow = true;
        int columns = 1;
        try (BufferedReader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            int value;
            while ((value = input.read()) >= 0) {
                char current = (char) value;
                if (!rowStarted && (current == '\r' || current == '\n')) continue;
                if (!rowStarted) {
                    output.write("| ");
                    rowStarted = true;
                    columns = 1;
                }
            if (current == '"') {
                    if (quoted) {
                        input.mark(1);
                        int next = input.read();
                        if (next == '"') output.write('"');
                        else {
                            quoted = false;
                            if (next >= 0) input.reset();
                        }
                    } else quoted = true;
            } else if (current == delimiter && !quoted) {
                    output.write(" | ");
                    columns++;
                } else if ((current == '\r' || current == '\n') && !quoted) {
                    if (current == '\r') {
                        input.mark(1);
                        int next = input.read();
                        if (next != '\n' && next >= 0) input.reset();
                    }
                    output.write(" |\n");
                    if (firstRow) {
                        writeMarkdownTableSeparator(output, columns);
                        firstRow = false;
                    }
                    rowStarted = false;
                } else if ((current == '\r' || current == '\n') && quoted) {
                    output.write(' ');
                } else if (current == '|') {
                    output.write("\\|");
                } else {
                    output.write(current);
                }
            }
        }
        if (rowStarted) {
            output.write(" |\n");
            if (firstRow) writeMarkdownTableSeparator(output, columns);
        }
    }

    private static void writeMarkdownTableSeparator(Writer output, int columns) throws IOException {
        output.write('|');
        for (int i = 0; i < columns; i++) output.write(" --- |");
        output.write('\n');
    }

    private static String knowledgeMarkdownHeader(String title, LocalCrawlDocument document,
                                                  KompileProjectCrawlProfile profile, String projectName) {
        String resolvedTitle = firstNonBlank(title, document.relativePath(), document.documentId());
        StringBuilder fm = new StringBuilder("---\n");
        fm.append("title: \"").append(escapeYaml(resolvedTitle)).append("\"\n");
        fm.append("source: \"").append(escapeYaml(document.source())).append("\"\n");
        fm.append("source_path: \"").append(escapeYaml(document.relativePath())).append("\"\n");
        fm.append("content_type: \"").append(escapeYaml(document.contentType())).append("\"\n");
        fm.append("converter: kompile-project-crawl\n");
        if (profile != null) {
            fm.append("crawl_profile: \"").append(escapeYaml(firstNonBlank(profile.getId(), profile.getName())))
                    .append("\"\n");
            if (profile.getFactSheetName() != null && !profile.getFactSheetName().isBlank()) {
                fm.append("fact_sheet: \"").append(escapeYaml(profile.getFactSheetName())).append("\"\n");
            }
            if (profile.getCollection() != null && !profile.getCollection().isBlank()) {
                fm.append("collection: \"").append(escapeYaml(profile.getCollection())).append("\"\n");
            }
            if (profile.getTags() != null && !profile.getTags().isEmpty()) {
                fm.append("tags:\n");
                for (String tag : profile.getTags()) fm.append("  - ").append(tag).append('\n');
            }
        }
        if (projectName != null && !projectName.isBlank()) {
            fm.append("project: \"").append(escapeYaml(projectName)).append("\"\n");
        }
        return fm.append("---\n\n# ").append(resolvedTitle).append("\n\n").toString();
    }

    private static String escapeYaml(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalizeKnowledgeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static LocalChunkingStats streamLocalCrawlChunks(LocalCrawlDocument document,
                                                              Path markdownPath,
                                                              LocalCrawlCapabilities.ResolvedPipeline pipeline,
                                                              Writer chunks,
                                                              LocalCrawlStatistics statistics) throws IOException {
        StreamingLocalChunker chunker = new StreamingLocalChunker(document, pipeline, chunks, statistics);
        long markdownChars = 0;
        long wordCount = 0;
        boolean inWord = false;
        try (BufferedReader markdown = Files.newBufferedReader(markdownPath, StandardCharsets.UTF_8)) {
            char[] buffer = new char[LOCAL_IO_BUFFER_CHARS];
            int read;
            while ((read = markdown.read(buffer)) >= 0) {
                if (read == 0) continue;
                markdownChars += read;
                for (int i = 0; i < read; i++) {
                    boolean wordCharacter = Character.isLetterOrDigit(buffer[i]);
                    if (wordCharacter && !inWord) wordCount++;
                    inWord = wordCharacter;
                }
                chunker.accept(buffer, read);
            }
        }
        chunker.finish();
        return new LocalChunkingStats(markdownChars, wordCount);
    }

    private static final class NormalizedTextWriter extends Writer {
        private final Writer delegate;
        private boolean started;
        private boolean pendingSpace;
        private int pendingNewlines;
        private boolean previousCarriageReturn;

        private NormalizedTextWriter(Writer delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(char[] value, int offset, int length) throws IOException {
            for (int i = offset; i < offset + length; i++) write(value[i]);
        }

        @Override
        public void write(int value) throws IOException {
            char current = (char) value;
            if (current == '\n' && previousCarriageReturn) {
                previousCarriageReturn = false;
                return;
            }
            previousCarriageReturn = current == '\r';
            if (current == '\r' || current == '\n') {
                pendingSpace = false;
                if (started) pendingNewlines = Math.min(2, pendingNewlines + 1);
                return;
            }
            if (current == ' ' || current == '\t' || Character.isWhitespace(current)) {
                if (started && pendingNewlines == 0) pendingSpace = true;
                return;
            }
            while (pendingNewlines-- > 0) delegate.write('\n');
            pendingNewlines = 0;
            if (pendingSpace && started) delegate.write(' ');
            pendingSpace = false;
            delegate.write(current);
            started = true;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class StreamingHtmlMarkdown {
        private final Writer output;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder tag = new StringBuilder();
        private final StringBuilder title = new StringBuilder();
        private boolean inTag;
        private boolean tagOverflow;
        private char tagQuote;
        private boolean inHead;
        private boolean inTitle;
        private boolean bodySeen;
        private boolean inBody;
        private String ignoredTag;

        private StreamingHtmlMarkdown(Writer output) {
            this.output = output;
        }

        private void accept(char[] value, int length) throws IOException {
            for (int i = 0; i < length; i++) {
                char current = value[i];
                if (inTag) {
                    if ((current == '\'' || current == '"')) {
                        if (tagQuote == 0) tagQuote = current;
                        else if (tagQuote == current) tagQuote = 0;
                    }
                    if (current == '>' && tagQuote == 0) {
                        handleTag(tag.toString());
                        tag.setLength(0);
                        tagOverflow = false;
                        inTag = false;
                    } else if (!tagOverflow) {
                        if (tag.length() < MAX_HTML_TOKEN_CHARS) tag.append(current);
                        else tagOverflow = true;
                    }
                } else if (current == '<') {
                    flushText();
                    inTag = true;
                    tagQuote = 0;
                } else {
                    text.append(current);
                    if (text.length() >= MAX_HTML_TOKEN_CHARS) flushText();
                }
            }
        }

        private void finish() throws IOException {
            flushText();
        }

        private String title() {
            return normalizeKnowledgeText(title.toString());
        }

        private void flushText() throws IOException {
            if (text.isEmpty()) return;
            String decoded = Parser.unescapeEntities(text.toString(), false);
            text.setLength(0);
            if (ignoredTag != null) return;
            if (inTitle) {
                int available = Math.max(0, MAX_HTML_TOKEN_CHARS - title.length());
                if (available > 0) title.append(decoded, 0, Math.min(available, decoded.length()));
            } else if (!inHead && (!bodySeen || inBody)) {
                output.write(decoded);
            }
        }

        private void handleTag(String rawTag) throws IOException {
            if (tagOverflow) return;
            String cleaned = rawTag.strip();
            if (cleaned.isEmpty() || cleaned.startsWith("!") || cleaned.startsWith("?")) return;
            boolean closing = cleaned.startsWith("/");
            if (closing) cleaned = cleaned.substring(1).stripLeading();
            int end = 0;
            while (end < cleaned.length()) {
                char value = cleaned.charAt(end);
                if (!Character.isLetterOrDigit(value)) break;
                end++;
            }
            if (end == 0) return;
            String name = cleaned.substring(0, end).toLowerCase(Locale.ROOT);
            if (ignoredTag != null) {
                if (closing && ignoredTag.equals(name)) ignoredTag = null;
                return;
            }
            if (!closing && Set.of("script", "style", "noscript", "svg", "canvas").contains(name)) {
                ignoredTag = name;
                return;
            }
            if ("title".equals(name)) {
                inTitle = !closing;
                return;
            }
            if ("head".equals(name)) {
                inHead = !closing;
                return;
            }
            if ("body".equals(name)) {
                bodySeen = true;
                inBody = !closing;
                return;
            }
            if (inHead) return;
            if (!closing) {
                if (name.matches("h[1-6]")) {
                    output.write("\n\n" + "#".repeat(name.charAt(1) - '0') + " ");
                } else if ("p".equals(name)) {
                    output.write("\n\n");
                } else if ("li".equals(name)) {
                    output.write("\n- ");
                } else if ("blockquote".equals(name)) {
                    output.write("\n\n> ");
                } else if ("pre".equals(name)) {
                    output.write("\n\n```\n");
                } else if ("br".equals(name)) {
                    output.write('\n');
                } else if ("tr".equals(name)) {
                    output.write('\n');
                } else if ("td".equals(name) || "th".equals(name)) {
                    output.write("| ");
                }
            } else if (name.matches("h[1-6]") || Set.of("p", "blockquote").contains(name)) {
                output.write("\n\n");
            } else if ("li".equals(name) || "tr".equals(name)) {
                output.write('\n');
            } else if ("pre".equals(name)) {
                output.write("\n```\n\n");
            } else if ("td".equals(name) || "th".equals(name)) {
                output.write(" | ");
            }
        }
    }

    private static final class StreamingLocalChunker {
        private final LocalCrawlDocument document;
        private final LocalCrawlCapabilities.ResolvedPipeline pipeline;
        private final Writer output;
        private final LocalCrawlStatistics statistics;
        private final StringBuilder pending = new StringBuilder();
        private final int targetSize;
        private final int overlap;
        private long pendingStart;
        private long lastEmittedEnd;
        private int index;

        private StreamingLocalChunker(LocalCrawlDocument document,
                                      LocalCrawlCapabilities.ResolvedPipeline pipeline,
                                      Writer output,
                                      LocalCrawlStatistics statistics) {
            this.document = document;
            this.pipeline = pipeline;
            this.output = output;
            this.statistics = statistics;
            this.targetSize = "no-op".equals(pipeline.chunkerName())
                    ? NO_OP_STREAM_BATCH_CHARS : Math.max(1, pipeline.chunkSize());
            this.overlap = "no-op".equals(pipeline.chunkerName()) ? 0 : pipeline.chunkOverlap();
        }

        private void accept(char[] value, int length) throws IOException {
            pending.append(value, 0, length);
            while (pending.length() >= targetSize) emit(chooseBoundary(), false);
        }

        private void finish() throws IOException {
            while (pending.length() > targetSize) emit(chooseBoundary(), false);
            if (pendingStart + pending.length() > lastEmittedEnd) emit(pending.length(), true);
        }

        private int chooseBoundary() {
            if ("no-op".equals(pipeline.chunkerName())) return targetSize;
            int minimum = Math.max(1, targetSize / 2);
            if ("sentence".equals(pipeline.chunkerName())) {
                for (int i = targetSize - 1; i >= minimum; i--) {
                    char current = pending.charAt(i);
                    if ((current == '.' || current == '!' || current == '?')
                            && (i + 1 >= pending.length() || Character.isWhitespace(pending.charAt(i + 1)))) {
                        return i + 1;
                    }
                }
            } else {
                for (String separator : List.of("\n\n", "\n", ". ", " ")) {
                    int candidate = pending.lastIndexOf(separator, targetSize - 1);
                    if (candidate >= minimum) return candidate + separator.length();
                }
            }
            return targetSize;
        }

        private void emit(int boundary, boolean finalChunk) throws IOException {
            if (boundary <= 0) return;
            int start = 0;
            int end = Math.min(boundary, pending.length());
            while (start < end && Character.isWhitespace(pending.charAt(start))) start++;
            while (end > start && Character.isWhitespace(pending.charAt(end - 1))) end--;
            if (end > start) {
                String text = pending.substring(start, end);
                long absoluteStart = pendingStart + start;
                long absoluteEnd = pendingStart + end;
                output.write("{\"chunkId\":");
                output.write(jsonString(document.documentId() + "#chunk-" + index));
                output.write(",\"documentId\":");
                output.write(jsonString(document.documentId()));
                output.write(",\"index\":" + index + ",\"start\":" + absoluteStart + ",\"end\":" + absoluteEnd);
                output.write(",\"pipelineId\":");
                output.write(jsonString(pipeline.pipelineId()));
                output.write(",\"chunker\":");
                output.write(jsonString(pipeline.chunkerName()));
                output.write(",\"text\":");
                output.write(jsonString(text));
                output.write("}\n");
                statistics.acceptChunk(text);
                lastEmittedEnd = Math.max(lastEmittedEnd, absoluteEnd);
                index++;
            }
            int discard = finalChunk ? boundary : Math.max(1, boundary - Math.min(overlap, boundary - 1));
            discard = Math.min(discard, pending.length());
            pending.delete(0, discard);
            pendingStart += discard;
        }
    }

    private static final class LocalCrawlStatistics {
        private int chunkCount;
        private long analysisWordCount;
        private final Map<String, Integer> terms = new HashMap<>();

        private void acceptChunk(String text) {
            chunkCount++;
            for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.length() < 3 || LOCAL_KNOWLEDGE_STOP_WORDS.contains(token)) continue;
                analysisWordCount++;
                terms.merge(token, 1, Integer::sum);
            }
        }
    }

    private static void writeLocalCrawlArtifacts(KompileProjectCrawlProfile profile, Path projectRoot, Path outputDir,
                                                 Path markdownDir,
                                                 LocalCrawlResult result) throws IOException {
        Instant finishedAt = Instant.now();
        Path analysisPath = outputDir.resolve("analysis.json");
        writeLocalKnowledgeAnalysis(profile, projectRoot, markdownDir, analysisPath, result, finishedAt);
        try (BufferedWriter documents = Files.newBufferedWriter(outputDir.resolve("documents.jsonl"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (LocalCrawlDocument document : result.documents()) {
                documents.write("{");
                documents.write("\"documentId\":" + jsonString(document.documentId()) + ",");
                documents.write("\"source\":" + jsonString(document.source()) + ",");
                documents.write("\"relativePath\":" + jsonString(document.relativePath()) + ",");
                documents.write("\"sizeBytes\":" + document.sizeBytes() + ",");
                documents.write("\"lastModified\":" + jsonString(document.lastModified()) + ",");
                documents.write("\"contentType\":" + jsonString(document.contentType()) + ",");
                documents.write("\"title\":" + jsonString(document.title()) + ",");
                documents.write("\"markdownPath\":" + jsonString(document.markdownPath()) + ",");
                documents.write("\"extractionStatus\":" + jsonString(document.extractionStatus()) + ",");
                documents.write("\"extractionMessage\":" + jsonString(document.extractionMessage()) + ",");
                documents.write("\"pipelineId\":" + jsonString(document.pipelineId()) + ",");
                documents.write("\"pipelineType\":" + jsonString(document.pipelineType()) + ",");
                documents.write("\"loader\":" + jsonString(document.loader()) + ",");
                documents.write("\"chunker\":" + jsonString(document.chunker()) + ",");
                documents.write("\"markdownChars\":" + document.markdownChars() + ",");
                documents.write("\"wordCount\":" + document.wordCount() + "}\n");
            }
        }

        String summary = "{\n"
                + "  \"profileId\" : " + jsonString(profile.getId()) + ",\n"
                + "  \"name\" : " + jsonString(profile.getName()) + ",\n"
                + "  \"status\" : " + jsonString(result.status()) + ",\n"
                + "  \"finishedAt\" : " + jsonString(finishedAt.toString()) + ",\n"
                + "  \"sources\" : " + jsonArray(profile.getSources()) + ",\n"
                + "  \"includePatterns\" : " + jsonArray(profile.getIncludePatterns()) + ",\n"
                + "  \"excludePatterns\" : " + jsonArray(profile.getExcludePatterns()) + ",\n"
                + "  \"loader\" : " + jsonString(firstNonBlank(profile.getLoader(), "local-text")) + ",\n"
                + "  \"chunker\" : " + jsonString(firstNonBlank(profile.getChunker(), "local-fixed")) + ",\n"
                + "  \"collection\" : " + jsonString(firstNonBlank(profile.getCollection(), profile.getId())) + ",\n"
                + "  \"factSheetName\" : " + jsonString(profile.getFactSheetName()) + ",\n"
                + "  \"executionMode\" : " + jsonString(LocalCrawlSubprocessRunner.executionMode()) + ",\n"
                + "  \"markdownPath\" : " + jsonString(projectRelativePath(projectRoot, markdownDir)) + ",\n"
                + "  \"analysisPath\" : " + jsonString(projectRelativePath(projectRoot, analysisPath)) + ",\n"
                + "  \"documentCount\" : " + result.documents().size() + ",\n"
                + "  \"failedDocumentCount\" : " + result.failures().size() + ",\n"
                + "  \"documentFailures\" : " + failureJsonArray(result.failures()) + ",\n"
                + "  \"markdownCount\" : " + result.markdownCount() + ",\n"
                + "  \"chunkCount\" : " + result.chunkCount() + "\n"
                + "}\n";
        Files.writeString(outputDir.resolve("crawl-result.json"), summary, StandardCharsets.UTF_8);
    }

    private static String failureJsonArray(List<LocalCrawlFailure> failures) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < failures.size(); i++) {
            LocalCrawlFailure failure = failures.get(i);
            if (i > 0) result.append(',');
            result.append("{")
                    .append("\"documentId\":").append(jsonString(failure.documentId())).append(",")
                    .append("\"source\":").append(jsonString(failure.source())).append(",")
                    .append("\"relativePath\":").append(jsonString(failure.relativePath())).append(",")
                    .append("\"message\":").append(jsonString(failure.message())).append(",")
                    .append("\"pipelineId\":").append(jsonString(failure.pipelineId())).append(",")
                    .append("\"pipelineType\":").append(jsonString(failure.pipelineType()))
                    .append("}");
        }
        return result.append(']').toString();
    }

    /**
     * Best-effort call to register crawled markdown as facts via the running backend.
     * Silently skips if no backend is reachable.
     *
     * <p>{@code /api/projects} is part of the shared surface every persona app mounts, so this
     * walks the services rather than assuming the admin console is the one that is running —
     * an end-user install may only have chat and the crawl manager up.</p>
     */
    private static void tryRegisterMarkdownAsFacts(String factSheetName) {
        String path = "/api/projects/current/markdown/register-facts";
        String body = "{\"factSheetName\":" + jsonString(factSheetName) + "}";
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(KompileServiceEndpoints.baseUrlForPath(path, null));
        for (KompileService service : KompileService.values()) {
            candidates.add(KompileServiceEndpoints.resolve(service).baseUrl());
        }
        HttpClient http = HttpClient.newHttpClient();
        for (String candidate : candidates) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(candidate + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    System.out.println("  Registered markdown as facts in backend (" + candidate + ")");
                    return;
                }
            } catch (Exception ignored) {
                // Try the next service.
            }
        }
        // No backend running — facts can be registered later via:
        //   kompile project markdown-register-facts --fact-sheet <name>
    }

    private static void writeLocalKnowledgeAnalysis(KompileProjectCrawlProfile profile, Path projectRoot, Path markdownDir,
                                                    Path analysisPath, LocalCrawlResult result,
                                                    Instant finishedAt) throws IOException {
        List<LocalTerm> topTerms = result.terms().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(20)
                .map(entry -> new LocalTerm(entry.getKey(), entry.getValue()))
                .toList();
        String analysis = "{\n"
                + "  \"profileId\" : " + jsonString(profile.getId()) + ",\n"
                + "  \"analyzedAt\" : " + jsonString(finishedAt.toString()) + ",\n"
                + "  \"markdownPath\" : " + jsonString(projectRelativePath(projectRoot, markdownDir)) + ",\n"
                + "  \"documentCount\" : " + result.documents().size() + ",\n"
                + "  \"markdownCount\" : " + result.markdownCount() + ",\n"
                + "  \"chunkCount\" : " + result.chunkCount() + ",\n"
                + "  \"wordCount\" : " + result.analysisWordCount() + ",\n"
                + "  \"topTerms\" : " + topTermsJson(topTerms) + "\n"
                + "}\n";
        Files.writeString(analysisPath, analysis, StandardCharsets.UTF_8);
    }

    private static String projectRelativePath(Path projectRoot, Path path) {
        return projectRoot.toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private static String topTermsJson(List<LocalTerm> terms) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (LocalTerm term : terms) {
            if (!first) {
                builder.append(", ");
            }
            builder.append("{\"term\":").append(jsonString(term.term()))
                    .append(",\"count\":").append(term.count())
                    .append("}");
            first = false;
        }
        builder.append("]");
        return builder.toString();
    }

    private static Path resolveLocalCrawlSource(Path projectRoot, String source) {
        Path path = Path.of(source);
        if (!path.isAbsolute()) {
            path = projectRoot.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    static String localArtifactId(KompileProjectCrawlProfile profile) {
        return localArtifactId(firstNonBlank(profile.getId(), profile.getName(), "crawl"));
    }

    static String localArtifactId(String value) {
        String id = firstNonBlank(value, "crawl")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return id.isBlank() ? "crawl" : id;
    }

    // ==================== buildCrawlArgs (used by service trigger too) ====================

    static List<String> buildCrawlArgs(KompileProjectCrawlProfile profile, String appUrl,
                                       Integer port, Boolean watchOverride) {
        List<String> args = new ArrayList<>();
        args.add("start");
        if (appUrl != null && !appUrl.isBlank()) {
            args.add("--url");
            args.add(appUrl);
        } else if (port != null) {
            args.add("--port");
            args.add(String.valueOf(port));
        }

        args.addAll(profile.getSources());
        args.add("--depth");
        args.add(String.valueOf(profile.getMaxDepth()));
        if (profile.getMaxDocuments() > 0) {
            args.add("--max-docs");
            args.add(String.valueOf(profile.getMaxDocuments()));
        }
        if (!profile.isSameDomain()) {
            args.add("--no-same-domain");
        }
        if (!profile.isRobots()) {
            args.add("--no-robots");
        }
        if (profile.getDelayMs() > 0 && profile.getDelayMs() != 500) {
            args.add("--delay");
            args.add(String.valueOf(profile.getDelayMs()));
        }
        if (profile.getTimeoutMin() > 0 && profile.getTimeoutMin() != 60) {
            args.add("--timeout");
            args.add(String.valueOf(profile.getTimeoutMin()));
        }
        addJoined(args, "--include", profile.getIncludePatterns());
        addJoined(args, "--exclude", profile.getExcludePatterns());
        addJoined(args, "--content-types", profile.getContentTypes());
        addValue(args, "--chunker", profile.getChunker());
        addValue(args, "--loader", profile.getLoader());
        addValue(args, "--collection", profile.getCollection());
        if (profile.isMultimodal()) {
            args.add("--multimodal");
        }
        addValue(args, "--vlm-model", profile.getVlmModel());
        args.add("--graph");
        addJoined(args, "--graph-entities", profile.getGraphEntityTypes());
        addJoined(args, "--graph-relations", profile.getGraphRelationTypes());
        addValue(args, "--graph-model-provider", profile.getGraphModelProvider());
        addValue(args, "--graph-model-name", profile.getGraphModelName());
        addValue(args, "--graph-temperature", profile.getGraphTemperature());
        addValue(args, "--graph-min-confidence", profile.getGraphMinConfidence());
        if (profile.getGraphAutoAccept() != null) {
            args.add("--graph-auto-accept");
            args.add(String.valueOf(profile.getGraphAutoAccept()));
        }
        addValue(args, "--graph-auto-accept-threshold", profile.getGraphAutoAcceptThreshold());
        addValue(args, "--graph-schema-mode", profile.getGraphSchemaMode());
        addValue(args, "--schema-preset", profile.getSchemaPresetId());
        addValue(args, "--graph-prompt", profile.getGraphCustomPrompt());
        if (profile.isGraphLocal()) {
            args.add("--graph-local");
        }
        if (profile.isGraphAutoStart()) {
            args.add("--graph-auto-start");
        }
        if (profile.isFollowLinks()) {
            args.add("--follow-links");
        }
        if (profile.isIncludeHidden()) {
            args.add("--include-hidden");
        }
        addValue(args, "--type", profile.getSourceType());
        addValue(args, "--fact-sheet", profile.getFactSheetName());
        if (profileMetadataBoolean(profile, "preprocessing.languageDetection", false)) {
            args.add("--language-detection");
        }
        String translationTarget = profileMetadataValue(profile, "preprocessing.translationTarget", null);
        if (profileMetadataBoolean(profile, "preprocessing.translation", false) || translationTarget != null) {
            args.add("--translate-to");
            args.add(firstNonBlank(translationTarget, "en"));
        }
        if (profileMetadataBoolean(profile, "preprocessing.translationDualIndex", false)) {
            args.add("--translation-dual-index");
        }
        addValue(args, "--name", profile.getName());
        boolean shouldWatch = watchOverride == null ? profile.isWatch() : watchOverride;
        if (shouldWatch) {
            args.add("--watch");
        }
        return args;
    }

    private static void addJoined(List<String> args, String option, List<String> values) {
        if (values != null && !values.isEmpty()) {
            args.add(option);
            args.add(String.join(",", values));
        }
    }

    private static void addValue(List<String> args, String option, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            args.add(option);
            args.add(String.valueOf(value));
        }
    }

    private static boolean profileMetadataBoolean(KompileProjectCrawlProfile profile, String key, boolean fallback) {
        String value = profileMetadataValue(profile, key, null);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static String profileMetadataValue(KompileProjectCrawlProfile profile, String key, String fallback) {
        if (profile != null && profile.getMetadata() != null) {
            String value = profile.getMetadata().get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return fallback;
    }

    private static List<String> quoteArgs(List<String> args) {
        List<String> quoted = new ArrayList<>();
        for (String arg : args) {
            if (arg.indexOf(' ') >= 0 || arg.indexOf('&') >= 0) {
                quoted.add("'" + arg.replace("'", "'\"'\"'") + "'");
            } else {
                quoted.add(arg);
            }
        }
        return quoted;
    }

    /**
     * Returns {@code true} when the given loopback port is already bound (i.e.
     * something is listening on it). Used to skip service-start steps when the
     * target service is already running.
     */
    static boolean isPortInUse(int port) {
        try (ServerSocket s = new ServerSocket(port, 0, InetAddress.getLoopbackAddress())) {
            return false; // successfully bound — port is free
        } catch (IOException e) {
            return true; // could not bind — port is in use
        }
    }

    private static KompileProjectWorkflowStep scriptStep(String id, String ref) {
        KompileProjectWorkflowStep step = new KompileProjectWorkflowStep();
        step.setId(id);
        step.setName(id);
        step.setType("SCRIPT");
        step.setRef(ref);
        return step;
    }

    private static KompileProjectWorkflowStep commandStep(String id, String command) {
        KompileProjectWorkflowStep step = new KompileProjectWorkflowStep();
        step.setId(id);
        step.setName(id);
        step.setType("COMMAND");
        step.setCommand(command);
        return step;
    }

    // ==================== Inner records ====================

    record LocalCrawlResult(List<LocalCrawlDocument> documents, int chunkCount,
                            long analysisWordCount, Map<String, Integer> terms) {
        int markdownCount() {
            int count = 0;
            for (LocalCrawlDocument document : documents) {
                if (document.markdownPath() != null && !document.markdownPath().isBlank()) {
                    count++;
                }
            }
            return count;
        }

        List<LocalCrawlFailure> failures() {
            List<LocalCrawlFailure> failures = new ArrayList<>();
            for (LocalCrawlDocument document : documents) {
                if ("FAILED".equals(document.extractionStatus())) {
                    failures.add(new LocalCrawlFailure(document.documentId(), document.source(),
                            document.relativePath(), document.extractionMessage(),
                            document.pipelineId(), document.pipelineType()));
                }
            }
            return List.copyOf(failures);
        }

        String status() {
            int failures = failures().size();
            if (failures == 0) return "COMPLETED";
            return failures == documents.size() ? "FAILED" : "COMPLETED_WITH_ERRORS";
        }
    }

    record LocalCrawlDocument(String documentId, String source, String relativePath,
                              long sizeBytes, String lastModified, String contentType,
                              String title, String markdownPath, String extractionStatus,
                              String extractionMessage, String pipelineId, String pipelineType,
                              String loader, String chunker, long markdownChars, long wordCount) {
        LocalCrawlDocument withMarkdown(LocalMarkdownArtifact artifact) {
            return new LocalCrawlDocument(documentId, source, relativePath, sizeBytes, lastModified, contentType,
                    artifact.title(), artifact.markdownPath(), artifact.status(), artifact.message(),
                    artifact.pipelineId(), artifact.pipelineType(), artifact.loader(), artifact.chunker(),
                    artifact.markdownChars(), artifact.wordCount());
        }
    }

    record LocalMarkdownArtifact(String title, String markdownPath,
                                 String status, String message, String pipelineId, String pipelineType,
                                 String loader, String chunker, long markdownChars, long wordCount) {
        static LocalMarkdownArtifact extracted(String title, String markdownPath,
                                               LocalCrawlCapabilities.ResolvedPipeline pipeline) {
            return new LocalMarkdownArtifact(title, markdownPath, "EXTRACTED", null,
                    pipeline.pipelineId(), pipeline.pipelineType(), pipeline.loaderName(), pipeline.chunkerName(),
                    0, 0);
        }

        LocalMarkdownArtifact withStats(long markdownChars, long wordCount) {
            return new LocalMarkdownArtifact(title, markdownPath, status, message, pipelineId, pipelineType,
                    loader, chunker, markdownChars, wordCount);
        }

        static LocalMarkdownArtifact skipped(String message,
                                             LocalCrawlCapabilities.ResolvedPipeline pipeline) {
            return new LocalMarkdownArtifact(null, null, "SKIPPED", message,
                    pipeline.pipelineId(), pipeline.pipelineType(), pipeline.loaderName(), pipeline.chunkerName(), 0, 0);
        }

        static LocalMarkdownArtifact failed(String message,
                                            LocalCrawlCapabilities.ResolvedPipeline pipeline) {
            return new LocalMarkdownArtifact(null, null, "FAILED", message,
                    pipeline.pipelineId(), pipeline.pipelineType(), pipeline.loaderName(), pipeline.chunkerName(), 0, 0);
        }
    }

    record LocalTerm(String term, int count) {
    }

    record LocalChunkingStats(long markdownChars, long wordCount) {
    }
}
