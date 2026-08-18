/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.subprocess;

import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.KompileServerConstants;
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.subprocess.BackendConfigurable;
import ai.kompile.app.subprocess.RestartableSubprocess;
import ai.kompile.app.subprocess.ServingSubprocessArgs;
import ai.kompile.app.subprocess.SubprocessBackendResolver;
import ai.kompile.app.subprocess.SubprocessEnvironmentPropagator;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessPlacementSupport;
import ai.kompile.app.subprocess.SubprocessRegistry;
import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import ai.kompile.utils.NativeImageInfo;
import ai.kompile.utils.NativeRuntimePathSelector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nd4j.common.config.ND4JEnvironmentVars;
import org.nd4j.common.config.ND4JSystemProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Spring service that manages the LLM serving subprocess lifecycle.
 *
 * <p>Unlike the embedding subprocess (which uses a stdin/stdout JSON protocol),
 * the serving subprocess starts a full Spring Boot HTTP server on a configurable
 * port. This launcher communicates with it via HTTP using {@link HttpClient}.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #start()} — builds the JVM command, launches the process via
 *       {@link ProcessBuilder}, then polls {@code GET /api/llm/status} until the
 *       subprocess HTTP server is ready (up to 120 seconds).</li>
 *   <li>{@link #loadModel(String)} — {@code POST /api/llm/load}</li>
 *   <li>{@link #generate(String)} — {@code POST /api/llm/generate}</li>
 *   <li>{@link #getStatus()} — {@code GET /api/llm/status}</li>
 *   <li>{@link #stop()} — {@code POST /api/llm/unload} then forcibly terminates
 *       the process.</li>
 * </ol>
 *
 * <h3>Managed endpoints</h3>
 * <pre>
 * service-endpoints.json: servingUrl=http://127.0.0.1:8091
 * service-endpoints.json: stagingUrl=http://localhost:8090
 * </pre>
 *
 * <h3>JVM command construction</h3>
 * <ul>
 *   <li>Heap: {@code -Xmx8g} (larger than embedding's 4g, needed for LLM weights)</li>
 *   <li>All {@code org.nd4j.*}, {@code org.bytedeco.*}, {@code nd4j.*}, {@code cuda.*},
 *       {@code cudnn.*}, {@code openblas.*}, {@code mkl.*} system properties are forwarded.</li>
 *   <li>Device-routing overrides are set as {@code -Dnd4j.environment.*} properties.</li>
 *   <li>JavaCPP cache dir is set to a per-subprocess temp directory to avoid
 *       conflicts with the parent process native libs.</li>
 *   <li>Spring Boot fat-JAR BOOT-INF entries are extracted when the classpath
 *       contains only a fat JAR (the shared managed-subprocess pattern).</li>
 * </ul>
 */
@Service
public class ServingSubprocessLauncher implements RestartableSubprocess, BackendConfigurable {

    private static final Logger logger = LoggerFactory.getLogger(ServingSubprocessLauncher.class);

    /** Shared device-agnostic placement (same base infra every subprocess uses). */
    private final SubprocessPlacementSupport placement = new SubprocessPlacementSupport();

    /** {@link BackendConfigurable} — the scheduler assigns backend/device/memory before spawn. */
    @Override
    public void applyPlacement(SubprocessPlacement p) {
        this.placement.applyPlacement(p);
    }

    /** Heap gigabytes for the LLM serving subprocess — larger than embedding (4g) due to model weights. */
    private static final long DEFAULT_HEAP_GB = 16L;

    /** Heap size for the LLM serving subprocess, derived from {@link #DEFAULT_HEAP_GB}. */
    private static final String DEFAULT_HEAP_SIZE = DEFAULT_HEAP_GB + "g";

    /** How long to poll for HTTP readiness before declaring startup failed. */
    private static final long READY_POLL_TIMEOUT_MS = 120_000L;

    /** Interval between HTTP readiness polls. */
    private static final long READY_POLL_INTERVAL_MS = 500L;

    /** System-property prefixes forwarded to the subprocess. */
    private static final String[] FORWARDED_PROPERTY_PREFIXES = {
        "org.nd4j.",
        "org.bytedeco.",
        "nd4j.",
        "cuda.",
        "cudnn.",
        "openblas.",
        "mkl.",
    };

    private static final List<String> SERVING_KOMPILE_JAR_PREFIXES = List.of(
            "kompile-app-main-",
            "kompile-app-core-",
            "kompile-app-llm-pipeline-",
            "kompile-pipelines-framework-api-",
            "kompile-pipelines-framework-core-",
            "kompile-pipelines-steps-samediff-",
            // ServingSubprocessMain moved out of app-main classes in the subprocess module split —
            // without these two the spawned JVM dies with ClassNotFoundException (found live 2026-07-05,
            // first-ever serving launch after the bridge shard-cache fix unblocked the load path).
            "kompile-app-subprocess-serving-",
            "kompile-app-subprocess-common-",
            // Transitive deps of app-core needed on the subprocess classpath (JsonUtils CNFE, same day):
            "kompile-cli-common-",
            "kompile-utils-",
            "kompile-ocr-core-",
            // Spring factories on the included jars reference these (KompileBootstrapEnvironmentPostProcessor
            // CNFE, same day). Offline classpath test with these two added: Spring boots in 1.2s, Tomcat
            // binds :8091, model pre-load starts — closure complete.
            "kompile-app-config-",
            "kompile-app-dto-"
    );

    private static final List<String> SERVING_THIRD_PARTY_JAR_PREFIXES = List.of(
            "agrona-",
            "annotations-",
            "antlr-runtime-",
            "antlr4-runtime-",
            "asm-",
            "byteunits-",
            "checker-qual-",
            "classgraph-",
            "commons-cli-",
            "commons-codec-",
            "commons-collections4-",
            "commons-compress-",
            "commons-io-",
            "commons-lang3-",
            "commons-logging-",
            "commons-math3-",
            "commons-text-",
            "context-propagation-",
            "cuda-",
            "cudnn-",
            "cutensor-",
            "deeplearning4j-",
            "error_prone_annotations-",
            "failureaccess-",
            "flatbuffers-java-",
            "gson-",
            "guava-",
            "HdrHistogram-",
            "jackson-",
            "jakarta.annotation-api-",
            "jakarta.inject-api-",
            "jakarta.servlet-api-",
            "javacpp-",
            "jboss-logging-",
            "jcl-over-slf4j-",
            "jna-",
            "jna-platform-",
            "json-",
            "jsr305-",
            "jul-to-slf4j-",
            "kotlin-",
            "LatencyUtils-",
            "libnd4j-",
            "libtokenizers-",
            "listenablefuture-",
            "log4j-api-",
            "log4j-to-slf4j-",
            "logback-",
            "micrometer-",
            "mkl-",
            "nd4j-",
            "netty-common-",
            "openblas-",
            "oshi-core-",
            "protobuf-",
            "reactive-streams-",
            "reactor-core-",
            "samediff-llm-",
            "slf4j-api-",
            "snakeyaml-",
            "spring-aop-",
            "spring-ai-commons-",
            "spring-ai-model-",
            "spring-beans-",
            "spring-boot-",
            "spring-context-",
            "spring-core-",
            "spring-expression-",
            "spring-jcl-",
            "spring-retry-",
            "spring-web-",
            "spring-webmvc-",
            "ST4-",
            "threetenbp-",
            "tokenizers-native-",
            "tokenizers-native-preset-",
            "tomcat-",
            "zstd-jni-"
    );

    private static final List<String> SERVING_CLASSES_DIR_MARKERS = List.of(
            "/.boot-inf-extracted/classes",
            "/kompile-app-main/target/classes",
            "/kompile-app-core/target/classes",
            "/kompile-pipelines-app-llm/target/classes",
            "/kompile-pipelines-framework-api/target/classes",
            "/kompile-pipelines-framework-core/target/classes",
            "/kompile-pipelines-steps-samediff/target/classes"
    );

    // ── Configuration ────────────────────────────────────────────────────────

    static final String SERVING_BIND_HOST = "127.0.0.1";

    private volatile int servingPort = 8091;

    private volatile String stagingUrl = KompileServerConstants.DEFAULT_STAGING_URL;

    private ServiceEndpointsConfigManager endpointConfigManager = ServiceEndpointsConfigManager.shared();

    // ── Injected dependencies (all optional to avoid circular wiring) ─────────

    @Autowired(required = false)
    private DeviceRoutingConfigService deviceRoutingConfigService;

    @Autowired(required = false)
    private Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private SubprocessRegistry subprocessRegistry;

    @Autowired(required = false)
    private ai.kompile.app.services.scheduler.ResourceAwareJobScheduler resourceScheduler;

    /** Scheduler job ID for the current serving session (null if not tracked). */
    private volatile String schedulerJobId;

    // ── Last-loaded model args (needed by RestartableSubprocess.requestRestart) ──

    /** Model id of the most recently loaded model; retained across stop/start for watchdog-triggered restarts. */
    private volatile String lastModelId;
    /** Model path of the most recently loaded model. */
    private volatile String lastModelPath;
    /** Model identity confirmed by the live serving status endpoint; null unless ready. */
    private volatile String activeModelId;

    // ── Runtime state ─────────────────────────────────────────────────────────

    /** The serving subprocess process handle — set on {@link #start()}, cleared on {@link #stop()}. */
    private volatile Process process;

    /** True while the subprocess is considered running and reachable. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Prevents double-start / double-stop races. */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /** Temp directory used for JavaCPP native lib extraction in the subprocess JVM. */
    private volatile Path subprocessTempDir;

    /** Args JSON file passed to the subprocess — must be cleaned up in {@link #stop()}. */
    private volatile Path argsFile;

    /** Central log writer: {@code ~/.kompile/logs/subprocesses/serving/<runId>.log}. */
    private volatile SubprocessLogWriter subprocessLogWriter;

    /**
     * Bounded tail of the subprocess's most recent output lines (stdout + stderr interleaved).
     * Kept so a premature exit can surface the subprocess's actual failure (e.g. an
     * UnsatisfiedLinkError from a missing native library) in the thrown exception immediately,
     * instead of burying it in the per-run log file.
     */
    private final Deque<String> recentOutputTail = new ArrayDeque<>();
    private static final int RECENT_OUTPUT_MAX_LINES = 80;

    /** Background reader for subprocess stdout (logs subprocess output at DEBUG). */
    private volatile Thread stdoutReaderThread;

    /** Background reader for subprocess stderr (DSP diagnostics + errors at INFO). */
    private volatile Thread stderrReaderThread;

    /** Shared HTTP client — not tied to individual requests so connections can be reused. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ── Model-loaded TTL cache (for isModelLoaded()) ──────────────────────────

    /** Cached result of the last /api/llm/status poll for model-loaded state. */
    private volatile boolean cachedModelLoaded = false;

    /** Timestamp (nanoTime) of the last successful /api/llm/status poll. */
    private volatile long modelLoadedCacheTimeNs = 0L;

    /** TTL for the model-loaded cache: 3 seconds in nanoseconds. */
    private static final long MODEL_LOADED_CACHE_TTL_NS = 3_000_000_000L;

    /** Lazily resolved ObjectMapper (may be null if Jackson is not on classpath). */
    private ObjectMapper resolvedMapper() {
        return objectMapper != null ? objectMapper : JsonUtils.standardMapper();
    }

    private void refreshManagedEndpoints() {
        try {
            ServiceEndpointsConfigManager.ServiceEndpointsConfig endpoints =
                    endpointConfigManager.current();
            servingPort = endpoints.servingPort();
            stagingUrl = endpoints.effectiveStagingUrl();
        } catch (Exception e) {
            logger.warn("Could not refresh managed serving/staging endpoints; using {} and {}: {}",
                    servingPort, stagingUrl, e.getMessage());
        }
    }

    /** Package-private test seam for an isolated managed-config file. */
    void setEndpointConfigManager(ServiceEndpointsConfigManager endpointConfigManager) {
        this.endpointConfigManager = Objects.requireNonNull(endpointConfigManager);
    }

    // ── Auto-start ────────────────────────────────────────────────────────────

    /**
     * No auto-start. The serving subprocess is demand-driven: it starts when
     * {@link #loadModel(String, String, Map)} is called with a model to serve.
     * Starting an empty subprocess that sits idle is wasteful.
     */
    @PostConstruct
    public void init() {
        refreshManagedEndpoints();
        logger.info("ServingSubprocessLauncher ready on {} (staging {}; demand-driven — subprocess starts on model load)",
                servingPort, stagingUrl);
    }

    // ── Public lifecycle API ──────────────────────────────────────────────────

    /**
     * Start the LLM serving subprocess with a model to serve.
     *
     * <p>The subprocess starts, initialises ND4J, loads the specified model
     * (including DSP warmup), and becomes HTTP-ready. There is no "empty start"
     * — a subprocess always has a model to serve.</p>
     *
     * @param modelId       model identifier (for logging / status)
     * @param modelPath     absolute path to the SameDiff model directory
     * @param tokenizerPath absolute path to tokenizer.json (null = auto-detect next to model)
     * @throws IOException          if the process cannot be launched
     * @throws InterruptedException if the readiness poll is interrupted
     * @throws TimeoutException     if the subprocess does not become ready within
     *                              {@value #READY_POLL_TIMEOUT_MS} ms
     */
    public synchronized void start(String modelId, String modelPath, String tokenizerPath)
            throws IOException, InterruptedException, TimeoutException {
        if (running.get()) {
            logger.info("Serving subprocess already running on port {} — stop it first to load a different model", servingPort);
            return;
        }
        if (shuttingDown.get()) {
            throw new IllegalStateException("ServingSubprocessLauncher is shutting down");
        }
        if (modelId == null || modelPath == null) {
            throw new IllegalArgumentException("modelId and modelPath are required — subprocess does not start empty");
        }

        // Pick up changes made in the central admin Service Endpoints panel before each child start.
        refreshManagedEndpoints();

        this.lastModelId = modelId;
        this.lastModelPath = modelPath;
        logger.info("Starting LLM serving subprocess on port {} with model '{}' from {}...",
                servingPort, modelId, modelPath);

        // 1. Resolve ND4J config for the LLM service
        Nd4jEnvironmentConfig nd4jConfig = resolveNd4jConfig();

        // 2. Serialise the config to JSON so the subprocess can deserialise it
        String nd4jConfigJson = null;
        try {
            nd4jConfigJson = resolvedMapper().writeValueAsString(nd4jConfig);
        } catch (Exception e) {
            logger.warn("Failed to serialise ND4J config for serving subprocess: {}", e.getMessage());
        }

        // 3. Build the ServingSubprocessArgs WITH the model — the subprocess preloads
        //    on startup so it's ready to serve as soon as it reports healthy.
        ServingSubprocessArgs args = new ServingSubprocessArgs(
                servingPort,
                SERVING_BIND_HOST,
                stagingUrl,
                modelId,
                modelPath,
                tokenizerPath,
                nd4jConfigJson,
                // Memory watchdog thresholds (sensible defaults)
                85, 90, 95, 5000L,
                85, 90, 95,
                80,             // gpuSoftLimitPercent — 5 below the 85 GPU stop threshold
                85, 90, 95,
                // Output budget is generic; sampling is resolved from the model family in the child.
                256, null, null,
                // DSP / optimizer flags — inherit from the persisted project ND4J config.
                // The existing "Native Decode Inputs" recovery toggle is the managed master
                // switch for decoder DSP compilation in the isolated serving process.
                !Boolean.TRUE.equals(nd4jConfig.dspNoNativeDecode()),
                nd4jConfig.optimizerEnabled(),
                nd4jConfig.optimizerFp16()
        );

        Path argsFileTmp = Files.createTempFile("serving-subprocess-args-", ".json");
        this.argsFile = argsFileTmp;
        try {
            resolvedMapper().writeValue(argsFileTmp.toFile(), args);
        } catch (Exception e) {
            Files.deleteIfExists(argsFileTmp);
            this.argsFile = null;
            throw new IOException("Failed to write serving subprocess args to " + argsFileTmp, e);
        }

        // 4. Build the launch command
        List<String> command = buildCommand(argsFileTmp, nd4jConfig);
        logger.info("Serving subprocess command: {}", String.join(" ", command));

        // 5. Start the process
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        propagateNd4jEnvironment(pb.environment(), nd4jConfig);
        // Device-agnostic per-device memory bound (SD_MAX_DEVICE_BYTES) — shared base infra.
        placement.applyEnv(pb.environment());

        process = pb.start();
        running.set(true);
        Process startedProcess = process;
        startedProcess.onExit().thenAccept(this::handleProcessExit);

        // Register with subprocess registry for lifecycle tracking and watchdog restart
        if (subprocessRegistry != null) {
            subprocessRegistry.register("serving", process, "serving");
            subprocessRegistry.registerRestartHandler(getSubprocessId(), this);
        }

        // 6. Init log writer (non-fatal)
        synchronized (recentOutputTail) {
            recentOutputTail.clear();
        }
        String runId = UUID.randomUUID().toString();
        try {
            String workingDir = pb.directory() != null
                    ? pb.directory().getAbsolutePath()
                    : System.getProperty("user.dir");
            SubprocessLogWriter slw = new SubprocessLogWriter("serving", runId, workingDir);
            slw.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    modelId, command, workingDir, process.pid(), DEFAULT_HEAP_SIZE));
            subprocessLogWriter = slw;
        } catch (Exception e) {
            logger.debug("SubprocessLogWriter init failed (non-fatal): {}", e.getMessage());
        }

        // 7. Start output reader threads
        stdoutReaderThread = new Thread(() -> readStdout(process), "serving-subprocess-stdout");
        stdoutReaderThread.setDaemon(true);
        stdoutReaderThread.start();

        stderrReaderThread = new Thread(() -> readStderr(process), "serving-subprocess-stderr");
        stderrReaderThread.setDaemon(true);
        stderrReaderThread.start();

        // 8. Poll until the subprocess HTTP server is ready, then verify that the live process
        //    actually loaded the exact requested model before publishing it as active.
        try {
            waitForReady();
            String statusJson = getJson("/api/llm/status");
            JsonNode status = resolvedMapper().readTree(statusJson);
            boolean loaded = status.path("loaded").asBoolean(false);
            String servedModelId = status.path("modelId").asText(null);
            if (!loaded || !modelId.equals(servedModelId)) {
                throw new IOException("Serving subprocess reported model '" + servedModelId
                        + "' (loaded=" + loaded + ") after requesting '" + modelId + "'");
            }
            activeModelId = modelId;
        } catch (IOException | InterruptedException | TimeoutException | RuntimeException e) {
            stop();
            throw e;
        }

        logger.info("LLM serving subprocess is ready on port {} (PID {}) with model '{}'",
                servingPort, process.pid(), modelId);
        invalidateModelLoadedCache(); // force next isModelLoaded() to re-poll

        // Track in scheduler for GPU resource awareness and history
        if (resourceScheduler != null) {
            try {
                String sJobId = "serving-" + modelId + "-" + System.currentTimeMillis();
                this.schedulerJobId = sJobId;
                final Process trackedProcess = process;
                ai.kompile.app.services.scheduler.ScheduledJob job =
                        ai.kompile.app.services.scheduler.ScheduledJob.builder()
                                .jobId(sJobId)
                                .jobType("llmServing")
                                .description("LLM Serving: " + modelId)
                                .resourceProfile(ai.kompile.app.services.scheduler.JobResourceProfiles.LLM_SERVING)
                                .executor(ctx -> {
                                    awaitServingProcess(trackedProcess, ctx);
                                })
                                .longLivedGpuHold(true)
                                .priority(70)
                                .build();
                resourceScheduler.submit(job);
                logger.info("Serving job '{}' submitted to scheduler for GPU tracking", sJobId);
            } catch (Exception e) {
                logger.warn("Failed to submit serving job to scheduler: {}", e.getMessage());
            }
        }
    }

    static void awaitServingProcess(
            Process trackedProcess,
            ai.kompile.app.services.scheduler.ScheduledJob.JobExecutionContext context)
            throws InterruptedException {
        if (trackedProcess == null) {
            return;
        }
        while (trackedProcess.isAlive()) {
            context.throwIfCancellationRequested();
            trackedProcess.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    synchronized void handleProcessExit(Process exitedProcess) {
        if (exitedProcess == null || process != exitedProcess) {
            return;
        }
        int exitCode;
        try {
            exitCode = exitedProcess.exitValue();
        } catch (IllegalThreadStateException stillRunning) {
            return;
        }
        running.set(false);
        activeModelId = null;
        invalidateModelLoadedCache();
        if (shuttingDown.get()) {
            logger.info("Serving subprocess exited during shutdown with code {}", exitCode);
        } else {
            logger.error("Serving subprocess exited unexpectedly with code {}{}",
                    exitCode, prematureExitDetail());
        }
    }

    /**
     * Load a model by starting (or restarting) the serving subprocess.
     *
     * <p>If no subprocess is running, one is started with the model pre-configured.
     * If a subprocess is already running, it is stopped first and a new one is
     * started with the new model. The subprocess is never left idle without a model.</p>
     *
     * @param modelId   the model identifier
     * @param modelPath absolute path to the model directory
     * @param options   optional generation config (unused for now, reserved for future)
     * @return the raw JSON status response from the subprocess
     * @throws IOException          on launch or I/O failure
     * @throws InterruptedException if interrupted
     */
    public synchronized String loadModel(String modelId, String modelPath, Map<String, Object> options)
            throws IOException, InterruptedException {
        // Clean up an existing live or exited child before starting the requested model.
        if (process != null) {
            logger.info("Stopping existing serving subprocess to load new model '{}'...", modelId);
            stop();
            shuttingDown.set(false); // reset so we can start again
        }
        try {
            start(modelId, modelPath, null);
            // Return the status from the now-running subprocess (model is already loaded)
            String status = getJson("/api/llm/status");
            if (!modelId.equals(activeModelId)) {
                throw new IOException("Serving model transition completed without activating requested model '"
                        + modelId + "'");
            }
            return status;
        } catch (TimeoutException e) {
            throw new IOException("Serving subprocess timed out starting with model " + modelId, e);
        }
    }

    /**
     * Query the current status of the serving subprocess.
     *
     * @return the raw JSON response body from {@code GET /api/llm/status}
     * @throws IOException          on HTTP or I/O failure
     * @throws InterruptedException if the request is interrupted
     */
    public String getStatus() throws IOException, InterruptedException {
        requireRunning("getStatus");
        return getJson("/api/llm/status");
    }

    /**
     * Run text generation on the currently loaded model.
     *
     * @param prompt the input prompt
     * @return the raw JSON response body from {@code POST /api/llm/generate}
     * @throws IOException          on HTTP or I/O failure
     * @throws InterruptedException if the request is interrupted
     */
    public synchronized String generate(String prompt) throws IOException, InterruptedException {
        requireRunning("generate");
        return postJson("/api/llm/generate", Map.of("prompt", prompt));
    }

    /**
     * Run text generation with a request-scoped output-token budget.
     */
    public synchronized String generate(String prompt, int maxNewTokens)
            throws IOException, InterruptedException {
        if (maxNewTokens <= 0) {
            throw new IllegalArgumentException("maxNewTokens must be positive");
        }
        requireRunning("generate");
        return postJson("/api/llm/generate",
                Map.of("prompt", prompt, "maxTokens", maxNewTokens));
    }

    /**
     * Atomically verify the active model and generate while holding the lifecycle monitor, so a
     * concurrent operator load or watchdog restart cannot substitute a model between check and use.
     */
    public synchronized String generateForModel(String modelId, String prompt)
            throws IOException, InterruptedException {
        if (modelId == null || !modelId.equals(activeModelId)) {
            throw new IllegalStateException("Requested serving model '" + modelId
                    + "' is not active (active=" + activeModelId + ")");
        }
        requireRunning("generateForModel");
        return postJson("/api/llm/generate", Map.of("prompt", prompt));
    }

    /**
     * Atomically verify the active model and generate with a request-scoped output-token budget.
     */
    public synchronized String generateForModel(String modelId, String prompt, int maxNewTokens)
            throws IOException, InterruptedException {
        if (modelId == null || !modelId.equals(activeModelId)) {
            throw new IllegalStateException("Requested serving model '" + modelId
                    + "' is not active (active=" + activeModelId + ")");
        }
        if (maxNewTokens <= 0) {
            throw new IllegalArgumentException("maxNewTokens must be positive");
        }
        requireRunning("generateForModel");
        return postJson("/api/llm/generate",
                Map.of("prompt", prompt, "maxTokens", maxNewTokens));
    }

    /** Send structured chat to the model-owned template and native parser. */
    public synchronized String generateChat(
            StructuredChatLanguageModel.Request request, int maxNewTokens)
            throws IOException, InterruptedException {
        if (maxNewTokens <= 0) {
            throw new IllegalArgumentException("maxNewTokens must be positive");
        }
        requireRunning("generateChat");
        return postJson("/api/llm/chat",
                Map.of("request", request, "maxTokens", maxNewTokens));
    }

    /** Structured chat guarded by the exact active model identity. */
    public synchronized String generateChatForModel(
            String modelId,
            StructuredChatLanguageModel.Request request,
            int maxNewTokens) throws IOException, InterruptedException {
        if (modelId == null || !modelId.equals(activeModelId)) {
            throw new IllegalStateException("Requested serving model '" + modelId
                    + "' is not active (active=" + activeModelId + ")");
        }
        if (maxNewTokens <= 0) {
            throw new IllegalArgumentException("maxNewTokens must be positive");
        }
        requireRunning("generateChatForModel");
        return postJson("/api/llm/chat",
                Map.of("request", request, "maxTokens", maxNewTokens));
    }

    /**
     * Stop the serving subprocess: unload the model gracefully, then terminate the process.
     */
    @PreDestroy
    public synchronized void stop() {
        activeModelId = null;
        boolean wasRunning = running.getAndSet(false);
        Process existingProcess = this.process;
        if (!wasRunning && existingProcess == null) {
            logger.debug("Serving subprocess is not running — stop() is a no-op");
            return;
        }
        shuttingDown.set(true);
        logger.info("Stopping LLM serving subprocess...");

        // Attempt a graceful unload only while the child is reachable.
        if (wasRunning && existingProcess != null && existingProcess.isAlive()) {
            try {
                postJson("/api/llm/unload", Map.of());
                logger.info("Sent unload request to serving subprocess");
            } catch (Exception e) {
                logger.debug("Unload request failed (subprocess may already be down): {}", e.getMessage());
            }
        }

        // Terminate the process
        Process p = existingProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                boolean exited = p.waitFor(10, TimeUnit.SECONDS);
                if (!exited) {
                    logger.warn("Serving subprocess did not stop gracefully — forcibly terminating");
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        this.process = null;

        // Deregister from subprocess registry
        if (subprocessRegistry != null) {
            subprocessRegistry.deregister("serving");
        }

        // Clean up log writer
        closeLogWriter("STOPPED", 0, null);

        // Clean up args file
        Path af = argsFile;
        if (af != null) {
            try {
                Files.deleteIfExists(af);
            } catch (Exception e) {
                logger.debug("Failed to clean up subprocess args file {}: {}", af, e.getMessage());
            }
            argsFile = null;
        }

        // Clean up temp dir
        Path tmpDir = subprocessTempDir;
        if (tmpDir != null) {
            try {
                deleteRecursively(tmpDir);
            } catch (Exception e) {
                logger.debug("Failed to clean up subprocess temp dir {}: {}", tmpDir, e.getMessage());
            }
            subprocessTempDir = null;
        }

        // Cancel scheduler job if tracked
        if (resourceScheduler != null && schedulerJobId != null) {
            try {
                resourceScheduler.cancel(schedulerJobId);
                logger.info("Cancelled scheduler job '{}' for serving", schedulerJobId);
            } catch (Exception e) {
                logger.debug("Failed to cancel scheduler job '{}': {}", schedulerJobId, e.getMessage());
            }
            schedulerJobId = null;
        }

        shuttingDown.set(false);
        invalidateModelLoadedCache(); // subprocess stopped — next poll will reflect not-loaded
        logger.info("LLM serving subprocess stopped");
    }

    /**
     * Check whether the serving subprocess is currently running and reachable.
     */
    public boolean isRunning() {
        return running.get() && process != null && process.isAlive();
    }

    /**
     * Return the model identifier configured for this serving subprocess lifecycle.
     *
     * <p>The value is retained across stop/start because the watchdog uses the same configuration
     * for restarts. Callers must still use {@link #isModelLoaded()} before generation.</p>
     */
    public String getConfiguredModelId() {
        return lastModelId;
    }

    /** Return the model identity confirmed by the live status endpoint, or null when not ready. */
    public String getActiveModelId() {
        return activeModelId;
    }

    /**
     * Check whether the serving subprocess is running AND has a model fully loaded
     * (ready to serve generation requests).
     *
     * <p>Calls {@code GET /api/llm/status} and inspects the {@code loaded} field.
     * The result is cached for {@link #MODEL_LOADED_CACHE_TTL_NS} nanoseconds
     * (~3 seconds) so hot dispatch paths do not spam the subprocess over HTTP.
     * Any HTTP or parse error is treated as "not loaded".</p>
     */
    public boolean isModelLoaded() {
        if (!isRunning()) {
            return false;
        }
        long nowNs = System.nanoTime();
        if (nowNs - modelLoadedCacheTimeNs < MODEL_LOADED_CACHE_TTL_NS) {
            return cachedModelLoaded;
        }
        // Cache miss — poll the subprocess
        try {
            String statusJson = getJson("/api/llm/status");
            com.fasterxml.jackson.databind.JsonNode node =
                    resolvedMapper().readTree(statusJson);
            String servedModelId = node.path("modelId").asText(null);
            boolean loaded = node.path("loaded").asBoolean(false)
                    && activeModelId != null
                    && activeModelId.equals(servedModelId);
            cachedModelLoaded = loaded;
            modelLoadedCacheTimeNs = System.nanoTime();
            return loaded;
        } catch (Exception e) {
            // Network error, parse error, or subprocess starting up — treat as not loaded
            logger.debug("isModelLoaded status poll failed (treating as not loaded): {}", e.getMessage());
            cachedModelLoaded = false;
            modelLoadedCacheTimeNs = System.nanoTime();
            return false;
        }
    }

    /**
     * Invalidate the model-loaded TTL cache. Should be called after start/stop/loadModel
     * to force the next {@link #isModelLoaded()} poll to hit the subprocess.
     */
    public void invalidateModelLoadedCache() {
        modelLoadedCacheTimeNs = 0L;
    }

    /**
     * Return the configured HTTP port for the subprocess server.
     */
    public int getServingPort() {
        if (!running.get()) {
            refreshManagedEndpoints();
        }
        return servingPort;
    }

    // ── RestartableSubprocess implementation ──────────────────────────────────

    @Override
    public String getSubprocessId() {
        return "serving";
    }

    /**
     * Request a watchdog-triggered restart of the serving subprocess.
     *
     * <p>Stops the current subprocess (if running) then restarts it with the
     * last-loaded model. Runs on a daemon thread so the watchdog's scheduler
     * thread is never blocked. This also fills the previously-missing restart
     * gap in {@link ServingSubprocessLauncher}: until now, a crashed serving
     * subprocess was never automatically recovered.</p>
     *
     * @param reason human-readable explanation from the watchdog
     */
    @Override
    public void requestRestart(String reason) {
        String mid = lastModelId;
        String mpath = lastModelPath;
        if (mid == null || mpath == null) {
            logger.warn("Watchdog restart requested for serving subprocess but no model was previously loaded — destroying only (reason: {})", reason);
            Process p = this.process;
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
            return;
        }
        logger.warn("Watchdog-triggered restart requested for serving subprocess: {} (model={})", reason, mid);
        Thread t = new Thread(() -> {
            try {
                synchronized (ServingSubprocessLauncher.this) {
                    if (!Objects.equals(mid, lastModelId) || !Objects.equals(mpath, lastModelPath)) {
                        logger.info("Skipping stale watchdog restart for model '{}'; lifecycle moved to '{}'",
                                mid, lastModelId);
                        return;
                    }
                    loadModel(mid, mpath, null);
                    logger.info("Serving subprocess successfully restarted by watchdog with model '{}'", mid);
                }
            } catch (Exception e) {
                logger.error("Watchdog restart of serving subprocess failed: {}", e.getMessage(), e);
            }
        }, "serving-watchdog-restart");
        t.setDaemon(true);
        t.start();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Resolve the ND4J environment config for the LLM service, merging any
     * device-routing overrides configured for {@link DeviceRoutingConfig#SERVICE_LLM}.
     */
    private Nd4jEnvironmentConfig resolveNd4jConfig() {
        if (deviceRoutingConfigService != null) {
            try {
                return deviceRoutingConfigService.resolveNd4jConfigForService(DeviceRoutingConfig.SERVICE_LLM);
            } catch (Exception e) {
                logger.warn("DeviceRoutingConfigService.resolveNd4jConfigForService failed: {}", e.getMessage());
            }
        }
        if (nd4jEnvironmentConfigService != null) {
            try {
                return nd4jEnvironmentConfigService.getConfiguration();
            } catch (Exception e) {
                logger.warn("Nd4jEnvironmentConfigService.getConfiguration failed: {}", e.getMessage());
            }
        }
        return Nd4jEnvironmentConfig.defaults();
    }

    /**
     * Build the native self-exec or JVM classpath command for launching the subprocess.
     *
     * <p>A classpathless GraalVM image must re-exec the unified binary and dispatch through
     * {@code --subprocess=serving}. JVM-only classpath discovery, Java executable lookup, GC
     * flags, and JavaCPP extraction directories remain confined to the JVM branch.</p>
     */
    List<String> buildCommand(Path argsFile, Nd4jEnvironmentConfig nd4jConfig) throws IOException {
        if (shouldUseNativeSelfExec()) {
            return buildNativeSelfExecCommand(argsFile, nd4jConfig, nativeSelfExecutablePath());
        }

        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IllegalStateException("JVM serving subprocess launch requires java.home");
        }
        String javaPath = Path.of(javaHome, "bin", "java").toString();
        String classpath = buildClasspath();
        return buildJvmCommand(argsFile, nd4jConfig, javaPath, classpath);
    }

    /** Native launch-mode seam kept package-private for focused command-selection tests. */
    boolean shouldUseNativeSelfExec() {
        return NativeImageInfo.isRunningInNativeImage() && !NativeImageInfo.hasClasspath();
    }

    /** Native executable-resolution seam kept package-private for focused command-selection tests. */
    String nativeSelfExecutablePath() {
        return NativeImageInfo.getExecutablePath();
    }

    /**
     * Build the classpathless native-image command. Package-private for focused command tests.
     */
    List<String> buildNativeSelfExecCommand(
            Path argsFile, Nd4jEnvironmentConfig nd4jConfig, String executablePath) {
        if (executablePath == null || executablePath.isBlank()) {
            throw new IllegalStateException(
                    "Native serving subprocess launch could not resolve the native self-executable");
        }

        List<String> command = new ArrayList<>();
        command.add(executablePath);
        command.add("-Xmx" + DEFAULT_HEAP_SIZE);
        command.add("-Dfile.encoding=UTF-8");

        appendNd4jEnvironmentProperties(command, nd4jConfig);
        appendForwardedSystemProperties(command);
        appendJavaCppChildProperties(command, null);
        appendManagedChildProperties(command);
        command.addAll(placement.jvmFlags());
        command.add("--subprocess=serving");
        command.add(argsFile.toAbsolutePath().toString());
        return command;
    }

    /** Child-specific JavaCPP settings must follow forwarded parent properties so they win. */
    private void appendJavaCppChildProperties(List<String> command, String childClasspath) {
        command.add("-Dorg.bytedeco.javacpp.pathsFirst=true");
        command.add("-Dorg.bytedeco.javacpp.logger.debug="
                + System.getProperty("kompile.serving.javacpp.debug", "false"));
        command.add("-Dorg.bytedeco.javacpp.nopointergc=true");

        String sharedRuntimePath = System.getProperty("org.nd4j.presets.sharedRuntimePath");
        String compatibleRuntimePath = backendCompatibleSharedRuntimePath(sharedRuntimePath, childClasspath);
        if (compatibleRuntimePath != null && !compatibleRuntimePath.isBlank()) {
            command.add("-Dorg.nd4j.presets.sharedRuntimePath=" + compatibleRuntimePath);
        }

        long heapBytes = DEFAULT_HEAP_GB * 1024L * 1024L * 1024L;
        long offHeapBytes = heapBytes * 2L;
        command.add("-Dorg.bytedeco.javacpp.maxbytes=" + offHeapBytes);
        command.add("-Dorg.bytedeco.javacpp.maxphysicalbytes="
                + resolveSystemPhysicalCeilingBytes(offHeapBytes));
    }

    static String backendCompatibleSharedRuntimePath(String runtimePath, String childClasspath) {
        return NativeRuntimePathSelector.forChild(runtimePath, childClasspath);
    }

    /**
     * Resolve JavaCPP's system-wide physical-memory guard independently from the per-process
     * {@code maxbytes} budget. Using the off-heap budget for both values makes a healthy serving
     * process false-OOM whenever sibling processes push total host usage above that budget.
     */
    long resolveSystemPhysicalCeilingBytes(long offHeapFloorBytes) {
        try {
            long totalBytes = ((com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean())
                    .getTotalMemorySize();
            double fraction = Double.parseDouble(
                    System.getProperty("kompile.subprocess.maxphysical-fraction", "0.95"));
            long ceilingBytes = (long) (totalBytes * fraction);
            return Math.max(offHeapFloorBytes, ceilingBytes);
        } catch (Throwable t) {
            return offHeapFloorBytes * 3L;
        }
    }

    /**
     * Build a JVM classpath-mode command for the serving subprocess.
     */
    private List<String> buildJvmCommand(Path argsFile, Nd4jEnvironmentConfig nd4jConfig,
                                          String javaPath, String classpath) throws IOException {
        List<String> command = new ArrayList<>();

        command.add(javaPath);
        command.add("-Xmx" + DEFAULT_HEAP_SIZE);
        command.add("-Xms1g");
        command.add("-XX:+UseG1GC");
        command.add("-XX:MaxGCPauseMillis=200");
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-Dfile.encoding=UTF-8");

        // Per-subprocess temp dir for native lib extraction
        try {
            this.subprocessTempDir = Files.createTempDirectory("serving-subprocess-javacpp-");
            command.add("-Dorg.bytedeco.javacpp.cachedir=" + subprocessTempDir.toAbsolutePath());
            command.add("-Djava.io.tmpdir=" + subprocessTempDir.toAbsolutePath());
            logger.info("Serving subprocess using temp directory: {}", subprocessTempDir);
        } catch (IOException e) {
            logger.warn("Could not create subprocess temp dir, using default: {}", e.getMessage());
        }

        // Forward relevant ND4J environment settings as system properties
        appendNd4jEnvironmentProperties(command, nd4jConfig);

        appendForwardedSystemProperties(command);
        appendJavaCppChildProperties(command, classpath);
        appendManagedChildProperties(command);

        // Device-agnostic backend/device selection from the shared base infra — added last so scheduler
        // placement wins over any forwarded parent org.nd4j.* property. No CUDA_VISIBLE_DEVICES.
        command.addAll(placement.jvmFlags());

        command.add("-cp");
        command.add(classpath);
        command.add("ai.kompile.app.subprocess.ServingSubprocessMain");
        command.add(argsFile.toAbsolutePath().toString());

        return command;
    }

    /** Propagate project-scoped, CLI-managed process configuration to either child mode. */
    private void appendManagedChildProperties(List<String> command) {
        String dataDir = System.getProperty("kompile.data.dir");
        if (dataDir != null && !dataDir.isBlank()) {
            command.add("-Dkompile.data.dir=" + dataDir);
        }
        command.add("-Dkompile.llm.cache.dir="
                + KompileHome.llmCacheDirectory().getAbsolutePath());
    }

    /** Forward matching parent system properties to either JVM or native subprocess commands. */
    private void appendForwardedSystemProperties(List<String> command) {
        for (String key : System.getProperties().stringPropertyNames()) {
            for (String prefix : FORWARDED_PROPERTY_PREFIXES) {
                if (key.startsWith(prefix)) {
                    String value = System.getProperty(key);
                    if (value != null && !value.isBlank()) {
                        command.add("-D" + key + "=" + value);
                        logger.debug("Forwarding system property to serving subprocess: {}={}", key, value);
                    }
                    break;
                }
            }
        }
    }

    /**
     * Append {@code -Dnd4j.environment.*} system properties derived from the resolved
     * ND4J config (and any device-routing overrides already merged into it).
     */
    private void appendNd4jEnvironmentProperties(List<String> command, Nd4jEnvironmentConfig config) {
        if (config == null) return;

        // Helper: add -D flag only when the config value is non-null (and non-blank for strings)
        java.util.function.BiConsumer<String, Object> add = (prop, val) -> {
            if (val == null) return;
            if (val instanceof String s && s.isBlank()) return;
            if (val instanceof Long l && l <= 0) return;
            command.add("-D" + prop + "=" + val);
        };

        // Thread counts
        add.accept(ND4JSystemProperties.ENV_MAX_THREADS, config.maxThreads());
        add.accept(ND4JSystemProperties.ENV_MAX_MASTER_THREADS, config.maxMasterThreads());

        // Memory limit
        add.accept(ND4JSystemProperties.ENV_MAX_DEVICE_MEMORY, config.maxDeviceMemory());

        // Debug / verbose flags
        add.accept(ND4JSystemProperties.ENV_DEBUG, config.debug());
        add.accept(ND4JSystemProperties.ENV_VERBOSE, config.verbose());
        add.accept(ND4JSystemProperties.ENV_PROFILING, config.profiling());
        add.accept(ND4JSystemProperties.ENV_LIFECYCLE_TRACKING, config.lifecycleTracking());

        // Triton compiler configuration
        add.accept(ND4JSystemProperties.ENV_TRITON_CACHE_ENABLED, config.tritonCacheEnabled());
        add.accept(ND4JSystemProperties.ENV_TRITON_CACHE_DIR, config.tritonCacheDir());
        add.accept(ND4JSystemProperties.ENV_TRITON_DUMP_DIR, config.tritonDumpDir());
        add.accept(ND4JSystemProperties.ENV_TRITON_BUILD_THREADS, config.tritonBuildThreads());
        add.accept(ND4JSystemProperties.ENV_TRITON_VERBOSE, config.tritonVerbose());
        add.accept(ND4JSystemProperties.ENV_TRITON_ALWAYS_COMPILE, config.tritonAlwaysCompile());
        add.accept(ND4JSystemProperties.ENV_TRITON_NUM_WARPS, config.tritonNumWarps());
        add.accept(ND4JSystemProperties.ENV_TRITON_NUM_STAGES, config.tritonNumStages());
        add.accept(ND4JSystemProperties.ENV_TRITON_NUM_CTAS, config.tritonNumCTAs());
        add.accept(ND4JSystemProperties.ENV_TRITON_ENABLE_FP_FUSION, config.tritonEnableFpFusion());
        add.accept(ND4JSystemProperties.ENV_TRITON_OVERRIDE_ARCH, config.tritonOverrideArch());
    }

    /**
     * Build the classpath string for the serving subprocess.
     *
     * <p>Handles Spring Boot fat JARs by extracting BOOT-INF/lib and BOOT-INF/classes
     * into a sibling directory using the shared managed-subprocess layout.</p>
     */
    private String buildClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        String pathSeparator = System.getProperty("path.separator");

        // 1. java.class.path
        String systemCp = System.getProperty("java.class.path");
        if (systemCp != null && !systemCp.isBlank()) {
            for (String entry : systemCp.split(pathSeparator)) {
                if (!entry.isBlank()) entries.add(entry);
            }
        }

        // 2. Walk the classloader hierarchy (catches Spring Boot's LaunchedURLClassLoader)
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = getClass().getClassLoader();
        while (cl != null) {
            extractUrlsFromClassloader(cl, entries);
            cl = cl.getParent();
        }

        // 3. Expand Spring Boot fat JARs if present
        Set<String> fatJarExpanded = new LinkedHashSet<>();
        for (String entry : new ArrayList<>(entries)) {
            if (entry.endsWith(".jar") && isSpringBootFatJar(entry)) {
                logger.info("Detected Spring Boot fat JAR in serving classpath: {}", entry);
                try {
                    extractBootInfClasspath(entry, fatJarExpanded);
                } catch (Exception e) {
                    logger.warn("Failed to extract BOOT-INF from {}: {}", entry, e.getMessage());
                }
            }
        }
        if (!fatJarExpanded.isEmpty()) {
            entries.addAll(fatJarExpanded);
            logger.info("Added {} BOOT-INF entries to serving subprocess classpath", fatJarExpanded.size());
        }

        int beforeFilter = entries.size();
        entries = filterServingClasspath(entries);
        logger.info("Serving subprocess classpath filtered from {} to {} entries", beforeFilter, entries.size());

        // 4. Augment classpath with the correct ND4J backend based on device routing config.
        //    When the LLM service is configured for CUDA but the parent classpath only has
        //    nd4j-native (common in dev mode when running kompile-app-main directly), we need
        //    to add nd4j-cuda JARs from the Maven local repository.
        boolean needsCuda = false;
        if (deviceRoutingConfigService != null) {
            try {
                DeviceRoutingConfig routingConfig = deviceRoutingConfigService.getConfiguration();
                if (routingConfig.hasRouteFor(DeviceRoutingConfig.SERVICE_LLM)) {
                    DeviceRoutingConfig.ServiceDeviceConfig llmRoute =
                            routingConfig.serviceRoutes().get(DeviceRoutingConfig.SERVICE_LLM);
                    needsCuda = "cuda".equalsIgnoreCase(llmRoute.deviceType());
                }
            } catch (Exception e) {
                logger.debug("Could not check device routing for LLM backend: {}", e.getMessage());
            }
        }
        SubprocessBackendResolver.augmentClasspathForBackend(entries, needsCuda, "LLM_SERVING");

        logger.info("Built serving subprocess classpath with {} entries", entries.size());
        return String.join(pathSeparator, entries);
    }

    private Set<String> filterServingClasspath(Set<String> entries) {
        Set<String> filtered = new LinkedHashSet<>();
        List<String> skippedKompile = new ArrayList<>();
        int skipped = 0;

        for (String entry : entries) {
            if (isServingClasspathEntry(entry)) {
                filtered.add(entry);
            } else {
                skipped++;
                String name = fileName(entry);
                if (name.startsWith("kompile-")) {
                    skippedKompile.add(name);
                }
            }
        }

        if (!skippedKompile.isEmpty()) {
            logger.info("Serving subprocess skipped {} Kompile jars/classes: {}",
                    skippedKompile.size(), String.join(", ", skippedKompile));
        }
        logger.debug("Serving subprocess skipped {} non-serving classpath entries", skipped);
        return filtered;
    }

    private boolean isServingClasspathEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return false;
        }
        String normalized = entry.replace('\\', '/');
        if (normalized.endsWith(".jar")) {
            return isServingClasspathJar(fileName(normalized));
        }
        for (String marker : SERVING_CLASSES_DIR_MARKERS) {
            if (normalized.endsWith(marker) || normalized.contains(marker + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean isServingClasspathJar(String jarName) {
        for (String prefix : SERVING_KOMPILE_JAR_PREFIXES) {
            if (jarName.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : SERVING_THIRD_PARTY_JAR_PREFIXES) {
            if (jarName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String fileName(String entry) {
        if (entry == null || entry.isBlank()) {
            return "";
        }
        String normalized = entry.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private void extractUrlsFromClassloader(ClassLoader cl, Set<String> entries) {
        if (cl instanceof java.net.URLClassLoader urlCl) {
            for (java.net.URL url : urlCl.getURLs()) {
                addUrlToEntries(url, entries);
            }
            return;
        }
        // Reflective fallback for Spring Boot's classloaders
        try {
            java.lang.reflect.Method getUrls = cl.getClass().getMethod("getURLs");
            Object result = getUrls.invoke(cl);
            if (result instanceof java.net.URL[] urls) {
                for (java.net.URL url : urls) {
                    addUrlToEntries(url, entries);
                }
            }
        } catch (NoSuchMethodException ignored) {
            // Not a URL-based classloader
        } catch (Exception e) {
            logger.debug("Could not extract URLs from classloader {}: {}", cl.getClass().getName(), e.getMessage());
        }
    }

    private void addUrlToEntries(java.net.URL url, Set<String> entries) {
        try {
            String path = url.toURI().getPath();
            if (path != null && !path.isBlank()) entries.add(path);
        } catch (Exception e) {
            String s = url.toString();
            if (s.startsWith("file:")) entries.add(s.substring(5));
        }
    }

    private boolean isSpringBootFatJar(String jarPath) {
        try (JarFile jf = new JarFile(jarPath)) {
            return jf.getEntry("BOOT-INF/lib/") != null || jf.getEntry("BOOT-INF/classes/") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void extractBootInfClasspath(String fatJarPath, Set<String> outputEntries) throws IOException {
        Path fatJar = Path.of(fatJarPath).toAbsolutePath();
        Path extractDir = fatJar.getParent().resolve(".boot-inf-extracted");
        Path libDir = extractDir.resolve("lib");
        Path classesDir = extractDir.resolve("classes");

        try (JarFile jarFile = new JarFile(fatJarPath)) {
            if (jarFile.getEntry("BOOT-INF/classes/") != null) {
                Files.createDirectories(classesDir);
                Enumeration<JarEntry> jarEntries = jarFile.entries();
                while (jarEntries.hasMoreElements()) {
                    JarEntry entry = jarEntries.nextElement();
                    if (entry.getName().startsWith("BOOT-INF/classes/") && !entry.isDirectory()) {
                        String rel = entry.getName().substring("BOOT-INF/classes/".length());
                        Path target = classesDir.resolve(rel);
                        Files.createDirectories(target.getParent());
                        if (!Files.exists(target) ||
                                Files.getLastModifiedTime(target).toMillis() < entry.getTime()) {
                            try (InputStream is = jarFile.getInputStream(entry)) {
                                Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                }
                outputEntries.add(classesDir.toString());
            }
            if (jarFile.getEntry("BOOT-INF/lib/") != null) {
                Files.createDirectories(libDir);
                Enumeration<JarEntry> jarEntries = jarFile.entries();
                int includedLibs = 0;
                int skippedLibs = 0;
                while (jarEntries.hasMoreElements()) {
                    JarEntry entry = jarEntries.nextElement();
                    if (entry.getName().startsWith("BOOT-INF/lib/") && entry.getName().endsWith(".jar")) {
                        String jarName = entry.getName().substring("BOOT-INF/lib/".length());
                        if (!isServingClasspathJar(jarName)) {
                            skippedLibs++;
                            continue;
                        }
                        Path targetJar = libDir.resolve(jarName);
                        if (!Files.exists(targetJar) || Files.size(targetJar) != entry.getSize()) {
                            try (InputStream is = jarFile.getInputStream(entry)) {
                                Files.copy(is, targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        outputEntries.add(targetJar.toString());
                        includedLibs++;
                    }
                }
                logger.info("Serving BOOT-INF/lib filter for {} included {} libs and skipped {} libs",
                        fatJar.getFileName(), includedLibs, skippedLibs);
            }
        }
        logger.info("Extracted BOOT-INF entries to {}", extractDir);
    }

    // ── HTTP readiness polling ────────────────────────────────────────────────

    /**
     * Poll {@code GET http://localhost:{port}/api/llm/status} until the subprocess
     * HTTP server responds, or until {@link #READY_POLL_TIMEOUT_MS} elapses.
     *
     * @throws InterruptedException if the polling thread is interrupted
     * @throws TimeoutException     if the subprocess does not become ready in time
     */
    private void waitForReady() throws IOException, InterruptedException, TimeoutException {
        long deadline = System.currentTimeMillis() + READY_POLL_TIMEOUT_MS;
        String statusUrl = "http://localhost:" + servingPort + "/api/llm/status";

        logger.info("Waiting for serving subprocess to become ready at {} (timeout {}s)...",
                statusUrl, READY_POLL_TIMEOUT_MS / 1000);

        while (System.currentTimeMillis() < deadline) {
            // Check that the process is still alive
            Process p = this.process;
            if (p != null && !p.isAlive()) {
                running.set(false);
                throw new IOException("Serving subprocess exited prematurely with code " + p.exitValue()
                        + prematureExitDetail());
            }

            // Attempt a single GET request
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(statusUrl))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    if (statusIndicatesModelReady(response.body(), lastModelId)) {
                        logger.info("Serving subprocess model is ready: {}", response.body());
                        return;
                    }
                    logger.debug("Serving subprocess HTTP server is up but model '{}' is not ready yet: {}",
                            lastModelId, response.body());
                } else {
                    logger.debug("Serving subprocess returned HTTP {}: {}", response.statusCode(), response.body());
                }
            } catch (IOException e) {
                // Connection refused — subprocess not yet listening
                logger.debug("Serving subprocess not yet listening ({}): {}", statusUrl, e.getMessage());
            }

            Thread.sleep(READY_POLL_INTERVAL_MS);
        }

        running.set(false);
        throw new TimeoutException("Serving subprocess did not become ready within "
                + READY_POLL_TIMEOUT_MS + " ms on port " + servingPort);
    }

    /**
     * A listening HTTP server is not sufficient readiness: model loading and DSP warmup
     * continue after the status endpoint starts returning 200. Require the requested model
     * to be fully loaded before exposing the subprocess as ready.
     */
    boolean statusIndicatesModelReady(String statusJson, String expectedModelId) {
        if (statusJson == null || statusJson.isBlank() || expectedModelId == null || expectedModelId.isBlank()) {
            return false;
        }
        try {
            JsonNode status = resolvedMapper().readTree(statusJson);
            return status.path("loaded").asBoolean(false)
                    && expectedModelId.equals(status.path("modelId").asText(null));
        } catch (Exception e) {
            logger.debug("Invalid serving status response while waiting for model '{}': {}",
                    expectedModelId, e.getMessage());
            return false;
        }
    }

    // ── HTTP proxy helpers ────────────────────────────────────────────────────

    /**
     * Send {@code GET http://localhost:{port}{path}} and return the response body.
     */
    private String getJson(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + servingPort + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GET " + path + " returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        return response.body();
    }

    /**
     * Serialise {@code body} to JSON and send {@code POST http://localhost:{port}{path}}.
     * Returns the response body.
     */
    private String postJson(String path, Object body) throws IOException, InterruptedException {
        String bodyJson;
        try {
            bodyJson = resolvedMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new IOException("Failed to serialise request body for POST " + path, e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + servingPort + path))
                .timeout(Duration.ofMinutes(10)) // generation may be long-running
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("POST " + path + " returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        return response.body();
    }

    // ── Output readers ────────────────────────────────────────────────────────

    /**
     * Reads stdout from the subprocess. Native C++ DSP diagnostics ([DSP_DIAG]) and
     * Triton compilation output arrive here; those lines are elevated to INFO for visibility.
     */
    private void readStdout(Process p) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isDspOrTritonLine(line)) {
                    logger.info("[serving-dsp] {}", line);
                } else {
                    logger.debug("[serving-subprocess] {}", line);
                }
                recordRecentOutput(line);
                SubprocessLogWriter slw = subprocessLogWriter;
                if (slw != null) {
                    try {
                        slw.writeLine(AgentLogRecord.Stream.STDOUT, line);
                    } catch (Exception e) {
                        // non-fatal
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                logger.warn("Error reading serving subprocess stdout: {}", e.getMessage());
            }
        }
    }

    /**
     * Reads stderr from the subprocess. Application logs (Spring Boot, ND4J) arrive here.
     * DSP/Triton/compilation lines are elevated to INFO regardless of their log level.
     */
    private void readStderr(Process p) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(" ERROR ") || line.contains("Exception")
                        || line.contains("FATAL")) {
                    logger.warn("[serving-subprocess] {}", line);
                } else if (isDspOrTritonLine(line) || line.contains(" INFO ")) {
                    logger.info("[serving-subprocess] {}", line);
                } else {
                    logger.debug("[serving-subprocess] {}", line);
                }
                recordRecentOutput(line);
                SubprocessLogWriter slw = subprocessLogWriter;
                if (slw != null) {
                    try {
                        slw.writeLine(AgentLogRecord.Stream.STDERR, line);
                    } catch (Exception e) {
                        // non-fatal
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                logger.warn("Error reading serving subprocess stderr: {}", e.getMessage());
            }
        }
    }

    private void recordRecentOutput(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        synchronized (recentOutputTail) {
            recentOutputTail.addLast(line);
            while (recentOutputTail.size() > RECENT_OUTPUT_MAX_LINES) {
                recentOutputTail.removeFirst();
            }
        }
    }

    /**
     * Failure context appended to the premature-exit exception: the error-bearing lines from the
     * subprocess's recent output (falling back to the plain tail when nothing matches), plus the
     * per-run log path. The reader threads may still be draining the pipes when the exit is
     * observed, so give them a brief moment to flush the crash stack first.
     */
    private String prematureExitDetail() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        List<String> snapshot;
        synchronized (recentOutputTail) {
            snapshot = new ArrayList<>(recentOutputTail);
        }
        StringBuilder sb = new StringBuilder();
        if (!snapshot.isEmpty()) {
            List<String> errorLines = snapshot.stream()
                    .filter(l -> l.contains("Caused by")
                            || l.contains("Exception")
                            || l.contains("Error")
                            || l.contains(" ERROR ")
                            || l.contains("cannot open")
                            || l.contains("FATAL"))
                    .toList();
            List<String> pick = errorLines.isEmpty() ? snapshot : errorLines;
            int from = Math.max(0, pick.size() - 10);
            sb.append(" — last subprocess output:\n    ")
              .append(String.join("\n    ", pick.subList(from, pick.size())));
        }
        SubprocessLogWriter slw = subprocessLogWriter;
        if (slw != null && slw.getLogFile() != null) {
            sb.append("\n    (full log: ").append(slw.getLogFile().getAbsolutePath()).append(')');
        }
        return sb.toString();
    }

    /**
     * Returns true if the line contains DSP diagnostics, Triton compilation,
     * or kernel cache status markers — these should be visible at INFO level.
     */
    private static boolean isDspOrTritonLine(String line) {
        return line.contains("[DSP_DIAG]")
                || line.contains("[TRITON]")
                || line.contains("[COMPILE_VIOLATION]")
                || line.contains("DSP diagnostics enabled")
                || line.contains("DSP auto-compile")
                || line.contains("[Lifecycle]")
                || line.contains("[GGUF-KV]")
                || line.contains("[Perf]")
                || line.contains("TritonGraphBackend")
                || line.contains("disk cache HIT")
                || line.contains("disk cache MISS")
                || line.contains("compileToGpuBinary")
                || line.contains("kernel compiled")
                || line.contains("frozen DSP plan")
                || line.contains("warmup decode")
                || line.contains("Triton compilation")
                || line.contains("triton_cache")
                || line.contains("KV cache max-allocation");
    }

    // ── ND4J environment propagation ──────────────────────────────────────────

    /**
     * Copy well-known ND4J-related environment variables from the parent process
     * into the subprocess's environment map.
     */
    private void propagateNd4jEnvironment(Map<String, String> env, Nd4jEnvironmentConfig nd4jConfig) {
        String tritonCacheDir = nd4jConfig != null ? nd4jConfig.tritonCacheDir() : null;
        String tritonDumpDir = nd4jConfig != null ? nd4jConfig.tritonDumpDir() : null;
        SubprocessEnvironmentPropagator.propagateToEnvironment(
                env, tritonCacheDir, tritonDumpDir);
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    private void requireRunning(String operation) {
        if (!running.get()) {
            throw new IllegalStateException(
                    "Cannot invoke " + operation + "() — serving subprocess is not running. Call start() first.");
        }
    }

    private void closeLogWriter(String state, Integer exitCode, String errorMessage) {
        SubprocessLogWriter slw = subprocessLogWriter;
        if (slw != null) {
            try {
                slw.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                        state, exitCode, errorMessage, false, false));
            } catch (Exception e) {
                logger.debug("SubprocessLogWriter writeEnd failed: {}", e.getMessage());
            }
            try {
                slw.close();
            } catch (Exception e) {
                logger.debug("SubprocessLogWriter close failed: {}", e.getMessage());
            }
            subprocessLogWriter = null;
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
        }
    }
}
