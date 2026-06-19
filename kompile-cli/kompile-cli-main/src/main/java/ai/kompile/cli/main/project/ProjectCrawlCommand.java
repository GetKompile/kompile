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

import ai.kompile.cli.main.app.CrawlCommand;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectPipeline;
import ai.kompile.project.KompileProjectScript;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.KompileProjectWorkflow;
import ai.kompile.project.KompileProjectWorkflowStep;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
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
import static ai.kompile.cli.main.project.ProjectCommandUtils.resolveProjectRoot;
import static ai.kompile.cli.main.project.ProjectCommandUtils.shellQuote;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printCrawlPlan;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printWorkflows;

/**
 * Picocli subcommand group for crawl and workflow management.
 * Contains Crawl, RunCrawlProfile, ListWorkflows, AddWorkflow, RunWorkflow subcommands,
 * plus the full local crawl engine.
 */
@Command(name = "crawl-group",
        mixinStandardHelpOptions = true,
        description = "Manage Kompile project crawls and workflows.",
        subcommands = {
                ProjectCrawlCommand.Crawl.class,
                ProjectCrawlCommand.RunCrawlProfile.class,
                ProjectCrawlCommand.ListWorkflows.class,
                ProjectCrawlCommand.AddWorkflow.class,
                ProjectCrawlCommand.RunWorkflow.class
        })
public class ProjectCrawlCommand implements Callable<Integer> {

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

    @Command(name = "crawl", mixinStandardHelpOptions = true,
            description = "Start the project if needed and run the best available crawl workflow or profile.")
    public static class Crawl implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--workflow", description = "Crawl workflow ID or name. Auto-selected when omitted.")
        private String workflowId;

        @Option(names = {"--profile", "--id"}, description = "Crawl profile ID or name. Auto-selected when omitted.")
        private String profileId;

        @Option(names = "--url", description = "Base URL of kompile-app, such as http://localhost:8080.")
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

        @Override
        public Integer call() throws Exception {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = autoconfigureModels(store, projectRoot, store.load(projectRoot));
            store.syncProjectRegistries(projectRoot);

            KompileProjectWorkflow workflow = selectCrawlWorkflow(store, manifest, workflowId);
            if (workflow != null) {
                printCrawlPlan(store, manifest, workflow, projectRoot);
                return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
            }

            KompileProjectCrawlProfile profile = selectCrawlProfile(store, manifest, profileId);
            if (profile == null) {
                System.err.println("No crawl workflow or crawl profile found in this project.");
                System.err.println("Add one with: kompile project crawl-add --source <path-or-url>");
                return 1;
            }

            if (shouldRunLocalCrawl(profile, local, appUrl, port)) {
                return runLocalCrawl(profile, projectRoot, dryRun);
            }

            boolean shouldServe = serve == null || serve;
            if (shouldServe) {
                int serveExit = runServeSelection(store, manifest, projectRoot, null,
                        false, false, false, appUrl, port, dryRun);
                if (serveExit != 0) {
                    return serveExit;
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

        @Option(names = "--url", description = "Base URL of kompile-app, such as http://localhost:8080.")
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
            Path projectRoot = resolveProjectRoot(store, root);
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
            KompileProjectManifest manifest = store.load(resolveProjectRoot(store, root));
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
            Path projectRoot = resolveProjectRoot(store, root);
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
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = store.load(projectRoot);
            KompileProjectWorkflow workflow = store.findWorkflow(manifest, workflowId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown workflow: " + workflowId));
            return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
        }
    }

    // ==================== Workflow execution engine ====================

    static int runServeSelection(KompileProjectStore store, KompileProjectManifest manifest,
                                 Path projectRoot, String workflowId,
                                 boolean stagingOnly, boolean servingOnly, boolean appOnly,
                                 String appUrl, Integer port, boolean dryRun) throws Exception {
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
        }
        if (scriptId != null) {
            return runSyntheticWorkflow(store, manifest, projectRoot, "serve-" + scriptId,
                    "Serve " + scriptId, List.of(scriptStep(scriptId, scriptId)), appUrl, port, dryRun);
        }

        KompileProjectWorkflow workflow = store.findWorkflow(manifest, "start-services")
                .or(() -> manifest.getWorkflows().stream()
                        .filter(candidate -> "START".equals(normalizeEnum(candidate.getPhase())))
                        .findFirst())
                .orElse(null);
        if (workflow != null) {
            return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
        }

        if (store.findScript(manifest, "start-all").isPresent()) {
            return runSyntheticWorkflow(store, manifest, projectRoot, "serve-start-all", "Serve project",
                    List.of(scriptStep("start-all", "start-all")), appUrl, port, dryRun);
        }
        System.err.println("No start workflow or start-all script found in this project.");
        return 1;
    }

    private static int runSyntheticWorkflow(KompileProjectStore store, KompileProjectManifest manifest,
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
        String baseUrl = firstNonBlank(appUrl, port == null ? null : "http://localhost:" + port, "http://localhost:8080");
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
        KompileProjectCrawlProfile profile = store.findCrawlProfile(manifest, step.getRef())
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
        String url = resolveTemplate(firstNonBlank(step.getUrl(), step.getRef(), "${appUrl}/actuator/health"),
                projectRoot, baseUrl);
        int expected = step.getExpectedStatus() == null ? 200 : step.getExpectedStatus();
        int timeoutSeconds = step.getTimeoutSeconds() == null ? 120 : step.getTimeoutSeconds();
        if (dryRun) {
            System.out.println("  wait for " + url + " status=" + expected + " timeout=" + timeoutSeconds + "s");
            return 0;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                KompileProjectWorkflowStep httpStep = new KompileProjectWorkflowStep();
                httpStep.setUrl(url);
                httpStep.setExpectedStatus(expected);
                httpStep.setTimeoutSeconds(5);
                if (runHttpStep(httpStep, projectRoot, baseUrl, false) == 0) {
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
        return value.replace("${appUrl}", baseUrl)
                .replace("${projectRoot}", projectRoot.toString());
    }

    private static Map<String, String> defaultServeEnvironment(KompileProjectManifest manifest,
                                                               KompileProjectScript script,
                                                               Path projectRoot) throws IOException {
        Map<String, String> environment = new LinkedHashMap<>();
        String scriptId = normalizeScriptId(script);
        if ("start-staging".equals(scriptId) && firstNonBlank(System.getenv("KOMPILE_STAGING_COMMAND")) == null) {
            defaultStagingCommand(projectRoot).ifPresent(command -> environment.put("KOMPILE_STAGING_COMMAND", command));
        } else if ("start-serving".equals(scriptId) && firstNonBlank(System.getenv("KOMPILE_SERVING_COMMAND")) == null) {
            defaultServingCommand(manifest, projectRoot).ifPresent(command -> environment.put("KOMPILE_SERVING_COMMAND", command));
        } else if ("start-app".equals(scriptId) && firstNonBlank(System.getenv("KOMPILE_APP_COMMAND")) == null) {
            defaultAppCommand(projectRoot).ifPresentOrElse(
                    command -> environment.put("KOMPILE_APP_COMMAND", command),
                    () -> environment.put("KOMPILE_APP_COMMAND",
                            "echo 'Kompile app command not configured for this project; set KOMPILE_APP_COMMAND to launch kompile-app.'; sleep 300"));
        }
        return environment;
    }

    private static String normalizeScriptId(KompileProjectScript script) {
        return firstNonBlank(script.getId(), script.getName(), script.getPath());
    }

    private static Optional<String> defaultStagingCommand(Path projectRoot) throws IOException {
        Path modelDir = projectRoot.resolve("data/models").normalize();
        Optional<Path> stagingJar = configuredPath("KOMPILE_MODEL_STAGING_JAR", "kompile.modelStaging.jar")
                .or(() -> findSourceRoot(projectRoot).flatMap(ProjectCrawlCommand::findModelStagingExecutableJar));
        if (stagingJar.isEmpty()) {
            System.err.println("No model staging jar found. Set KOMPILE_STAGING_COMMAND or KOMPILE_MODEL_STAGING_JAR.");
            return Optional.empty();
        }
        String port = firstNonBlank(System.getenv("KOMPILE_STAGING_PORT"),
                System.getProperty("kompile.staging.port"), "19090");
        return Optional.of("exec java -jar " + shellQuote(stagingJar.get().toString())
                + " --server.port=" + shellQuote(port)
                + " --kompile.staging.models-dir=" + shellQuote(modelDir.toString())
                + " --spring.main.banner-mode=off");
    }

    private static Optional<String> defaultServingCommand(KompileProjectManifest manifest,
                                                          Path projectRoot) throws IOException {
        Path argsPath = resolveServingArgsPath(manifest, projectRoot);
        String configuredCommand = firstNonBlank(System.getenv("KOMPILE_PIPELINE_SERVING_COMMAND"),
                System.getProperty("kompile.pipelineServing.command"));
        if (configuredCommand != null) {
            return Optional.of(configuredCommand.replace("{args}", shellQuote(argsPath.toString())));
        }
        String configuredClasspath = firstNonBlank(System.getenv("KOMPILE_PIPELINE_SERVING_CLASSPATH"),
                System.getProperty("kompile.pipelineServing.classpath"));
        if (configuredClasspath != null) {
            return Optional.of("exec java -cp " + shellQuote(configuredClasspath)
                    + " ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessMain "
                    + shellQuote(argsPath.toString()));
        }
        Optional<Path> sourceRoot = findSourceRoot(projectRoot);
        if (sourceRoot.isEmpty()) {
            System.err.println("No pipeline serving launcher found. Set KOMPILE_SERVING_COMMAND, "
                    + "KOMPILE_PIPELINE_SERVING_COMMAND, or KOMPILE_PIPELINE_SERVING_CLASSPATH.");
            return Optional.empty();
        }
        return Optional.of("cd " + shellQuote(sourceRoot.get().toString())
                + " && exec ./mvnw -q -pl kompile-app/kompile-pipeline-serving exec:java"
                + " -Dexec.mainClass=ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessMain"
                + " -Dexec.args=" + shellQuote(argsPath.toString())
                + " -DskipTests -Dskip.ui");
    }

    private static Optional<String> defaultAppCommand(Path projectRoot) throws IOException {
        Optional<Path> appJar = configuredPath("KOMPILE_APP_JAR", "kompile.app.jar")
                .or(() -> findSourceRoot(projectRoot).flatMap(ProjectCrawlCommand::findAppExecutableJar));
        String port = firstNonBlank(System.getenv("KOMPILE_APP_PORT"),
                System.getProperty("kompile.app.port"), "8080");
        if (appJar.isPresent()) {
            return Optional.of("exec java -jar " + shellQuote(appJar.get().toString())
                    + " --server.port=" + shellQuote(port)
                    + " --spring.main.banner-mode=off");
        }
        Optional<Path> sourceRoot = findSourceRoot(projectRoot);
        if (sourceRoot.isPresent()
                && Files.isRegularFile(sourceRoot.get().resolve("kompile-app/kompile-app-main/pom.xml"))) {
            return Optional.of("cd " + shellQuote(sourceRoot.get().toString())
                    + " && exec ./mvnw -q -pl kompile-app/kompile-app-main spring-boot:run"
                    + " -DskipTests -Dskip.ui"
                    + " -Dspring-boot.run.arguments="
                    + shellQuote("--server.port=" + port + " --spring.main.banner-mode=off"));
        }
        return Optional.empty();
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

    private static Optional<Path> findModelStagingExecutableJar(Path sourceRoot) {
        Path target = sourceRoot.resolve("kompile-app/kompile-model-staging/target");
        Path expected = target.resolve("kompile-model-staging-0.1.0-SNAPSHOT-exec.jar");
        if (Files.isRegularFile(expected)) {
            return Optional.of(expected);
        }
        if (!Files.isDirectory(target)) {
            return Optional.empty();
        }
        try (Stream<Path> listing = Files.list(target)) {
            return listing
                    .filter(path -> path.getFileName().toString().startsWith("kompile-model-staging-"))
                    .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
                    .filter(Files::isRegularFile)
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> findAppExecutableJar(Path sourceRoot) {
        Path target = sourceRoot.resolve("kompile-app/kompile-app-main/target");
        if (!Files.isDirectory(target)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(target)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith("-exec.jar")
                                || name.endsWith(".jar") && !name.endsWith("-sources.jar");
                    })
                    .sorted()
                    .findFirst();
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
        if (profile.isMultimodal() || profile.isGraphExtraction()) {
            return false;
        }
        String sourceType = normalizeEnum(profile.getSourceType());
        if ("WEB".equals(sourceType) || "URL".equals(sourceType)) {
            return false;
        }
        for (String source : profile.getSources()) {
            String lower = firstNonBlank(source, "").toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDefaultLocalAppUrl(String baseUrl) {
        return baseUrl == null || baseUrl.isBlank() || "http://localhost:8080".equals(baseUrl)
                || "http://127.0.0.1:8080".equals(baseUrl);
    }

    static int runLocalCrawl(KompileProjectCrawlProfile profile, Path projectRoot, boolean dryRun) {
        String crawlId = localArtifactId(profile);
        Path outputDir = projectRoot.resolve("data/crawls").resolve(crawlId).normalize();
        Path markdownDir = projectRoot.resolve("data/markdown").resolve(crawlId).normalize();
        if (!outputDir.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Local crawl output escapes project root: " + outputDir);
        }
        if (!markdownDir.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Local crawl markdown output escapes project root: " + markdownDir);
        }
        if (dryRun) {
            System.out.println("kompile project crawl local " + profile.getId());
            System.out.println("  output " + outputDir);
            System.out.println("  markdown " + markdownDir);
            for (String source : profile.getSources()) {
                System.out.println("  source " + resolveLocalCrawlSource(projectRoot, source));
            }
            return 0;
        }
        try {
            Files.createDirectories(outputDir);
            Files.createDirectories(markdownDir);
            KompileProjectStore store = new KompileProjectStore();
            String projectName = null;
            try {
                KompileProjectManifest manifest = store.load(projectRoot);
                if (manifest != null) {
                    projectName = manifest.getName();
                }
            } catch (Exception ignored) { }
            LocalCrawlResult result = collectLocalCrawl(profile, projectRoot, outputDir, markdownDir, projectName);
            writeLocalCrawlArtifacts(profile, projectRoot, outputDir, markdownDir, result);
            // Sync catalogs so crawled files are discoverable in the project
            try {
                store.syncMarkdownCatalog(projectRoot);
                store.syncCrawlCatalog(projectRoot);
            } catch (Exception ignored) { }
            System.out.println("Local crawl complete: " + profile.getId());
            System.out.println("  Documents: " + result.documents().size());
            System.out.println("  Chunks: " + result.chunks().size());
            System.out.println("  Markdown: " + result.markdownCount());
            System.out.println("  Output: " + outputDir);
            System.out.println("  Markdown output: " + markdownDir);
            if (profile.getFactSheetName() != null && !profile.getFactSheetName().isBlank()) {
                System.out.println("  Fact sheet: " + profile.getFactSheetName());
                // Best-effort: register markdown as facts via running backend
                tryRegisterMarkdownAsFacts(profile.getFactSheetName());
            }
            return 0;
        } catch (IOException e) {
            System.err.println("Local crawl failed: " + e.getMessage());
            return 1;
        }
    }

    private static LocalCrawlResult collectLocalCrawl(KompileProjectCrawlProfile profile,
                                                      Path projectRoot,
                                                      Path outputDir,
                                                      Path markdownDir) throws IOException {
        return collectLocalCrawl(profile, projectRoot, outputDir, markdownDir, null);
    }

    private static LocalCrawlResult collectLocalCrawl(KompileProjectCrawlProfile profile,
                                                      Path projectRoot,
                                                      Path outputDir,
                                                      Path markdownDir,
                                                      String projectName) throws IOException {
        List<LocalCrawlDocument> documents = new ArrayList<>();
        List<LocalCrawlChunk> chunks = new ArrayList<>();
        int maxDocuments = profile.getMaxDocuments();
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
                LocalMarkdownArtifact markdown = writeLocalCrawlMarkdown(projectRoot, markdownDir,
                        document, file, profile, projectName);
                document = document.withMarkdown(markdown);
                documents.add(document);
                if (markdown.markdown() != null && !markdown.markdown().isBlank()) {
                    chunks.addAll(localCrawlChunks(document, markdown.markdown()));
                }
            }
            if (maxDocuments > 0 && documents.size() >= maxDocuments) {
                break;
            }
        }
        return new LocalCrawlResult(documents, chunks);
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
        try (Stream<Path> stream = Files.walk(sourcePath)) {
            stream.filter(Files::isRegularFile)
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
                null, null, "PENDING", null, 0, 0);
    }

    private static LocalMarkdownArtifact writeLocalCrawlMarkdown(Path projectRoot, Path markdownDir,
                                                                 LocalCrawlDocument document, Path file) throws IOException {
        return writeLocalCrawlMarkdown(projectRoot, markdownDir, document, file, null, null);
    }

    private static LocalMarkdownArtifact writeLocalCrawlMarkdown(Path projectRoot, Path markdownDir,
                                                                 LocalCrawlDocument document, Path file,
                                                                 KompileProjectCrawlProfile profile,
                                                                 String projectName) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!isKnowledgeSource(name)) {
            return LocalMarkdownArtifact.skipped("Unsupported knowledge source type");
        }
        try {
            LocalMarkdownContent content = localMarkdownContent(file, name, document, profile, projectName);
            if (content.markdown() == null || content.markdown().isBlank()) {
                return LocalMarkdownArtifact.skipped("No extractable text");
            }
            Path markdownPath = markdownDir.resolve(document.documentId() + ".md").normalize();
            if (!markdownPath.startsWith(markdownDir)) {
                throw new IllegalArgumentException("Markdown artifact escapes markdown directory: " + markdownPath);
            }
            Files.writeString(markdownPath, content.markdown(), StandardCharsets.UTF_8);
            String relativeMarkdown = projectRoot.toAbsolutePath().normalize()
                    .relativize(markdownPath.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
            return LocalMarkdownArtifact.extracted(content.title(), relativeMarkdown, content.markdown());
        } catch (Exception e) {
            return LocalMarkdownArtifact.failed(e.getMessage());
        }
    }

    private static boolean isKnowledgeSource(String name) {
        return name.endsWith(".pdf") || isTextKnowledgeSource(name);
    }

    private static boolean isTextKnowledgeSource(String name) {
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")
                || name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".yaml")
                || name.endsWith(".yml") || name.endsWith(".csv") || name.endsWith(".html")
                || name.endsWith(".htm") || name.endsWith(".xml") || name.endsWith(".properties");
    }

    private static LocalMarkdownContent localMarkdownContent(Path file, String name,
                                                             LocalCrawlDocument document) throws IOException {
        return localMarkdownContent(file, name, document, null, null);
    }

    private static LocalMarkdownContent localMarkdownContent(Path file, String name,
                                                             LocalCrawlDocument document,
                                                             KompileProjectCrawlProfile profile,
                                                             String projectName) throws IOException {
        String title = file.getFileName().toString();
        String body;
        if (name.endsWith(".pdf")) {
            body = extractPdfText(file);
        } else if (name.endsWith(".html") || name.endsWith(".htm")) {
            org.jsoup.nodes.Document html = Jsoup.parse(file.toFile(), StandardCharsets.UTF_8.name());
            title = firstNonBlank(html.title(), title);
            body = htmlToMarkdown(html);
        } else if (name.endsWith(".md") || name.endsWith(".markdown")) {
            body = Files.readString(file, StandardCharsets.UTF_8);
        } else {
            body = normalizeKnowledgeText(Files.readString(file, StandardCharsets.UTF_8));
        }
        String markdown = knowledgeMarkdown(title, document, body, profile, projectName);
        return new LocalMarkdownContent(title, markdown);
    }

    private static String extractPdfText(Path file) throws IOException {
        if (isNativeImageRuntime()) {
            return extractPdfTextWithPdftotext(file);
        }
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            return normalizeKnowledgeText(new PDFTextStripper().getText(pdf));
        } catch (IOException e) {
            String fallback = extractPdfTextWithPdftotext(file);
            if (!fallback.isBlank()) {
                return fallback;
            }
            throw e;
        }
    }

    private static String extractPdfTextWithPdftotext(Path file) throws IOException {
        Process process = new ProcessBuilder("pdftotext", "-layout", file.toString(), "-")
                .redirectErrorStream(true)
                .start();
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("pdftotext timed out for " + file);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while extracting PDF text: " + file, e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("pdftotext failed for " + file + ": " + output.strip());
        }
        return normalizeKnowledgeText(output);
    }

    private static boolean isNativeImageRuntime() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    private static String htmlToMarkdown(org.jsoup.nodes.Document html) {
        html.select("script, style, noscript, svg, canvas").remove();
        StringBuilder markdown = new StringBuilder();
        Element body = html.body();
        if (body == null) {
            return "";
        }
        for (Element element : body.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, table")) {
            String tag = element.tagName().toLowerCase(Locale.ROOT);
            if ("table".equals(tag)) {
                appendHtmlTable(markdown, element);
                continue;
            }
            String text = normalizeKnowledgeText(element.text());
            if (text.isBlank()) {
                continue;
            }
            if (tag.matches("h[1-6]")) {
                int level = Math.max(1, Math.min(6, Integer.parseInt(tag.substring(1))));
                markdown.append("#".repeat(level)).append(' ').append(text).append("\n\n");
            } else if ("li".equals(tag)) {
                markdown.append("- ").append(text).append("\n");
            } else if ("blockquote".equals(tag)) {
                markdown.append("> ").append(text).append("\n\n");
            } else if ("pre".equals(tag)) {
                markdown.append("```\n").append(element.text()).append("\n```\n\n");
            } else {
                markdown.append(text).append("\n\n");
            }
        }
        if (markdown.isEmpty()) {
            return normalizeKnowledgeText(body.text());
        }
        return markdown.toString().trim();
    }

    private static void appendHtmlTable(StringBuilder markdown, Element table) {
        for (Element row : table.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : row.select("th, td")) {
                String text = normalizeKnowledgeText(cell.text());
                if (!text.isBlank()) {
                    cells.add(text);
                }
            }
            if (!cells.isEmpty()) {
                markdown.append("| ").append(String.join(" | ", cells)).append(" |\n");
            }
        }
        markdown.append('\n');
    }

    private static String knowledgeMarkdown(String title, LocalCrawlDocument document, String body) {
        return knowledgeMarkdown(title, document, body, null, null);
    }

    private static String knowledgeMarkdown(String title, LocalCrawlDocument document, String body,
                                            KompileProjectCrawlProfile profile, String projectName) {
        String normalizedBody = normalizeKnowledgeText(body);
        if (normalizedBody.isBlank()) {
            return "";
        }
        String resolvedTitle = firstNonBlank(title, document.relativePath(), document.documentId());
        StringBuilder fm = new StringBuilder("---\n");
        fm.append("title: \"").append(escapeYaml(resolvedTitle)).append("\"\n");
        fm.append("source: \"").append(escapeYaml(document.source())).append("\"\n");
        fm.append("source_path: \"").append(escapeYaml(document.relativePath())).append("\"\n");
        fm.append("content_type: \"").append(escapeYaml(document.contentType())).append("\"\n");
        fm.append("converter: kompile-project-crawl\n");
        if (profile != null) {
            fm.append("crawl_profile: \"").append(escapeYaml(firstNonBlank(profile.getId(), profile.getName()))).append("\"\n");
            if (profile.getFactSheetName() != null && !profile.getFactSheetName().isBlank()) {
                fm.append("fact_sheet: \"").append(escapeYaml(profile.getFactSheetName())).append("\"\n");
            }
            if (profile.getCollection() != null && !profile.getCollection().isBlank()) {
                fm.append("collection: \"").append(escapeYaml(profile.getCollection())).append("\"\n");
            }
            if (profile.getTags() != null && !profile.getTags().isEmpty()) {
                fm.append("tags:\n");
                for (String tag : profile.getTags()) {
                    fm.append("  - ").append(tag).append("\n");
                }
            }
        }
        if (projectName != null && !projectName.isBlank()) {
            fm.append("project: \"").append(escapeYaml(projectName)).append("\"\n");
        }
        fm.append("---\n\n");

        return fm.toString()
                + "# " + resolvedTitle + "\n\n"
                + normalizedBody + "\n";
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

    private static List<LocalCrawlChunk> localCrawlChunks(LocalCrawlDocument document, String text) {
        List<LocalCrawlChunk> chunks = new ArrayList<>();
        int chunkSize = 2_000;
        int overlap = 200;
        int index = 0;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isBlank()) {
                chunks.add(new LocalCrawlChunk(document.documentId() + "#chunk-" + index,
                        document.documentId(), index, start, end, chunkText));
                index++;
            }
            if (end == text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private static void writeLocalCrawlArtifacts(KompileProjectCrawlProfile profile, Path projectRoot, Path outputDir,
                                                 Path markdownDir,
                                                 LocalCrawlResult result) throws IOException {
        Instant finishedAt = Instant.now();
        Path analysisPath = outputDir.resolve("analysis.json");
        writeLocalKnowledgeAnalysis(profile, projectRoot, markdownDir, analysisPath, result, finishedAt);
        StringBuilder documents = new StringBuilder();
        for (LocalCrawlDocument document : result.documents()) {
            documents.append("{")
                    .append("\"documentId\":").append(jsonString(document.documentId())).append(",")
                    .append("\"source\":").append(jsonString(document.source())).append(",")
                    .append("\"relativePath\":").append(jsonString(document.relativePath())).append(",")
                    .append("\"sizeBytes\":").append(document.sizeBytes()).append(",")
                    .append("\"lastModified\":").append(jsonString(document.lastModified())).append(",")
                    .append("\"contentType\":").append(jsonString(document.contentType())).append(",")
                    .append("\"title\":").append(jsonString(document.title())).append(",")
                    .append("\"markdownPath\":").append(jsonString(document.markdownPath())).append(",")
                    .append("\"extractionStatus\":").append(jsonString(document.extractionStatus())).append(",")
                    .append("\"extractionMessage\":").append(jsonString(document.extractionMessage())).append(",")
                    .append("\"markdownChars\":").append(document.markdownChars()).append(",")
                    .append("\"wordCount\":").append(document.wordCount())
                    .append("}\n");
        }
        Files.writeString(outputDir.resolve("documents.jsonl"), documents.toString(), StandardCharsets.UTF_8);

        StringBuilder chunks = new StringBuilder();
        for (LocalCrawlChunk chunk : result.chunks()) {
            chunks.append("{")
                    .append("\"chunkId\":").append(jsonString(chunk.chunkId())).append(",")
                    .append("\"documentId\":").append(jsonString(chunk.documentId())).append(",")
                    .append("\"index\":").append(chunk.index()).append(",")
                    .append("\"start\":").append(chunk.start()).append(",")
                    .append("\"end\":").append(chunk.end()).append(",")
                    .append("\"text\":").append(jsonString(chunk.text()))
                    .append("}\n");
        }
        Files.writeString(outputDir.resolve("chunks.jsonl"), chunks.toString(), StandardCharsets.UTF_8);

        String summary = "{\n"
                + "  \"profileId\" : " + jsonString(profile.getId()) + ",\n"
                + "  \"name\" : " + jsonString(profile.getName()) + ",\n"
                + "  \"status\" : \"COMPLETED\",\n"
                + "  \"finishedAt\" : " + jsonString(finishedAt.toString()) + ",\n"
                + "  \"sources\" : " + jsonArray(profile.getSources()) + ",\n"
                + "  \"includePatterns\" : " + jsonArray(profile.getIncludePatterns()) + ",\n"
                + "  \"excludePatterns\" : " + jsonArray(profile.getExcludePatterns()) + ",\n"
                + "  \"loader\" : " + jsonString(firstNonBlank(profile.getLoader(), "local-text")) + ",\n"
                + "  \"chunker\" : " + jsonString(firstNonBlank(profile.getChunker(), "local-fixed")) + ",\n"
                + "  \"collection\" : " + jsonString(firstNonBlank(profile.getCollection(), profile.getId())) + ",\n"
                + "  \"factSheetName\" : " + jsonString(profile.getFactSheetName()) + ",\n"
                + "  \"markdownPath\" : " + jsonString(projectRelativePath(projectRoot, markdownDir)) + ",\n"
                + "  \"analysisPath\" : " + jsonString(projectRelativePath(projectRoot, analysisPath)) + ",\n"
                + "  \"documentCount\" : " + result.documents().size() + ",\n"
                + "  \"markdownCount\" : " + result.markdownCount() + ",\n"
                + "  \"chunkCount\" : " + result.chunks().size() + "\n"
                + "}\n";
        Files.writeString(outputDir.resolve("crawl-result.json"), summary, StandardCharsets.UTF_8);
    }

    /**
     * Best-effort call to register crawled markdown as facts via the running backend.
     * Silently skips if the backend is not reachable.
     */
    private static void tryRegisterMarkdownAsFacts(String factSheetName) {
        try {
            String body = "{\"factSheetName\":" + jsonString(factSheetName) + "}";
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:8080/api/projects/current/markdown/register-facts"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("  Registered markdown as facts in backend");
            }
        } catch (Exception ignored) {
            // Backend not running — facts can be registered later via:
            //   kompile project markdown-register-facts --fact-sheet <name>
        }
    }

    private static void writeLocalKnowledgeAnalysis(KompileProjectCrawlProfile profile, Path projectRoot, Path markdownDir,
                                                    Path analysisPath, LocalCrawlResult result,
                                                    Instant finishedAt) throws IOException {
        int totalWords = 0;
        Map<String, Integer> terms = new HashMap<>();
        for (LocalCrawlChunk chunk : result.chunks()) {
            for (String token : chunk.text().toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.length() < 3 || LOCAL_KNOWLEDGE_STOP_WORDS.contains(token)) {
                    continue;
                }
                totalWords++;
                terms.merge(token, 1, Integer::sum);
            }
        }
        List<LocalTerm> topTerms = terms.entrySet().stream()
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
                + "  \"chunkCount\" : " + result.chunks().size() + ",\n"
                + "  \"wordCount\" : " + totalWords + ",\n"
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
        if (profile.isGraphExtraction()) {
            args.add("--graph");
        }
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

    private static KompileProjectWorkflowStep scriptStep(String id, String ref) {
        KompileProjectWorkflowStep step = new KompileProjectWorkflowStep();
        step.setId(id);
        step.setName(id);
        step.setType("SCRIPT");
        step.setRef(ref);
        return step;
    }

    // ==================== Inner records ====================

    record LocalCrawlResult(List<LocalCrawlDocument> documents, List<LocalCrawlChunk> chunks) {
        int markdownCount() {
            int count = 0;
            for (LocalCrawlDocument document : documents) {
                if (document.markdownPath() != null && !document.markdownPath().isBlank()) {
                    count++;
                }
            }
            return count;
        }
    }

    record LocalCrawlDocument(String documentId, String source, String relativePath,
                              long sizeBytes, String lastModified, String contentType,
                              String title, String markdownPath, String extractionStatus,
                              String extractionMessage, int markdownChars, int wordCount) {
        LocalCrawlDocument withMarkdown(LocalMarkdownArtifact artifact) {
            return new LocalCrawlDocument(documentId, source, relativePath, sizeBytes, lastModified, contentType,
                    artifact.title(), artifact.markdownPath(), artifact.status(), artifact.message(),
                    artifact.markdownChars(), artifact.wordCount());
        }
    }

    record LocalCrawlChunk(String chunkId, String documentId, int index, int start, int end, String text) {
    }

    record LocalMarkdownContent(String title, String markdown) {
    }

    record LocalMarkdownArtifact(String title, String markdownPath, String markdown,
                                 String status, String message, int markdownChars, int wordCount) {
        static LocalMarkdownArtifact extracted(String title, String markdownPath, String markdown) {
            return new LocalMarkdownArtifact(title, markdownPath, markdown, "EXTRACTED", null,
                    markdown.length(), countWords(markdown));
        }

        static LocalMarkdownArtifact skipped(String message) {
            return new LocalMarkdownArtifact(null, null, null, "SKIPPED", message, 0, 0);
        }

        static LocalMarkdownArtifact failed(String message) {
            return new LocalMarkdownArtifact(null, null, null, "FAILED", message, 0, 0);
        }
    }

    record LocalTerm(String term, int count) {
    }

    private static int countWords(String text) {
        int count = 0;
        for (String token : firstNonBlank(text, "").split("[^\\p{Alnum}]+")) {
            if (!token.isBlank()) {
                count++;
            }
        }
        return count;
    }
}
