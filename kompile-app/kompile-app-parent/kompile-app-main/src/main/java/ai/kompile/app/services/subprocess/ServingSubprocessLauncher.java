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
import ai.kompile.app.config.SubprocessExecutableConfig;
import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.subprocess.ServingSubprocessArgs;
import ai.kompile.app.subprocess.SubprocessBackendResolver;
import ai.kompile.app.subprocess.SubprocessRegistry;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
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
 * <h3>Configuration properties</h3>
 * <pre>
 * kompile.llm.serving.port=8091        # HTTP port for the subprocess server
 * kompile.staging.url=http://localhost:8090  # Parent staging URL forwarded to subprocess
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
 *       contains only a fat JAR (same pattern as VlmTestSubprocessLauncher).</li>
 * </ul>
 */
@Service
public class ServingSubprocessLauncher {

    private static final Logger logger = LoggerFactory.getLogger(ServingSubprocessLauncher.class);

    /** Heap size for the LLM serving subprocess — larger than embedding (4g) due to model weights. */
    private static final String DEFAULT_HEAP_SIZE = "16g";

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
            "kompile-pipelines-steps-samediff-"
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

    private int servingPort = 8091;

    private String stagingUrl = KompileServerConstants.DEFAULT_STAGING_URL;

    /** When set (non-blank), the main app is in proxy mode — auto-start the subprocess. */
    private String servingUrl = "";

    // ── Injected dependencies (all optional to avoid circular wiring) ─────────

    @Autowired(required = false)
    private DeviceRoutingConfigService deviceRoutingConfigService;

    @Autowired(required = false)
    private Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;

    @Autowired(required = false)
    private SubprocessExecutableConfig subprocessExecutableConfig;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private SubprocessRegistry subprocessRegistry;

    @Autowired(required = false)
    private ai.kompile.app.services.scheduler.ResourceAwareJobScheduler resourceScheduler;

    /** Scheduler job ID for the current serving session (null if not tracked). */
    private volatile String schedulerJobId;

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

    /** Background reader for subprocess stdout (logs subprocess output at DEBUG). */
    private volatile Thread stdoutReaderThread;

    /** Background reader for subprocess stderr (DSP diagnostics + errors at INFO). */
    private volatile Thread stderrReaderThread;

    /** Shared HTTP client — not tied to individual requests so connections can be reused. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Lazily resolved ObjectMapper (may be null if Jackson is not on classpath). */
    private ObjectMapper resolvedMapper() {
        return objectMapper != null ? objectMapper : new ObjectMapper();
    }

    // ── Auto-start ────────────────────────────────────────────────────────────

    /**
     * No auto-start. The serving subprocess is demand-driven: it starts when
     * {@link #loadModel(String, String, Map)} is called with a model to serve.
     * Starting an empty subprocess that sits idle is wasteful.
     */
    @PostConstruct
    public void init() {
        logger.info("ServingSubprocessLauncher ready (demand-driven — subprocess starts on model load)");
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
                "0.0.0.0",
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
                // LLM defaults
                256, 0.7, 0,
                // DSP / optimizer flags — inherit from ND4J config
                null, null, null
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

        process = pb.start();
        running.set(true);

        // Register with subprocess registry for lifecycle tracking
        if (subprocessRegistry != null) {
            subprocessRegistry.register("serving", process, "serving");
        }

        // 6. Init log writer (non-fatal)
        String runId = UUID.randomUUID().toString();
        try {
            SubprocessLogWriter slw = new SubprocessLogWriter("serving", runId);
            slw.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    modelId, command, null, process.pid(), DEFAULT_HEAP_SIZE));
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

        // 8. Poll until the subprocess HTTP server is ready (model will be loaded by then)
        waitForReady();

        logger.info("LLM serving subprocess is ready on port {} (PID {}) with model '{}'",
                servingPort, process.pid(), modelId);

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
                                    // Block until the serving process exits (24h safety timeout)
                                    try {
                                        if (trackedProcess != null) {
                                            boolean exited = trackedProcess.waitFor(24, java.util.concurrent.TimeUnit.HOURS);
                                            if (!exited) {
                                                logger.warn("Serving process for model {} did not exit within 24h timeout", modelId);
                                            }
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                })
                                .priority(70)
                                .build();
                resourceScheduler.submit(job);
                logger.info("Serving job '{}' submitted to scheduler for GPU tracking", sJobId);
            } catch (Exception e) {
                logger.warn("Failed to submit serving job to scheduler: {}", e.getMessage());
            }
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
    public String loadModel(String modelId, String modelPath, Map<String, Object> options)
            throws IOException, InterruptedException {
        // If already running, stop the old subprocess first
        if (running.get()) {
            logger.info("Stopping existing serving subprocess to load new model '{}'...", modelId);
            stop();
            shuttingDown.set(false); // reset so we can start again
        }
        try {
            start(modelId, modelPath, null);
            // Return the status from the now-running subprocess (model is already loaded)
            return getJson("/api/llm/status");
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
    public String generate(String prompt) throws IOException, InterruptedException {
        requireRunning("generate");
        Map<String, Object> body = Map.of("prompt", prompt);
        return postJson("/api/llm/generate", body);
    }

    /**
     * Stop the serving subprocess: unload the model gracefully, then terminate the process.
     */
    @PreDestroy
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            logger.debug("Serving subprocess is not running — stop() is a no-op");
            return;
        }
        shuttingDown.set(true);
        logger.info("Stopping LLM serving subprocess...");

        // Attempt a graceful unload
        try {
            postJson("/api/llm/unload", Map.of());
            logger.info("Sent unload request to serving subprocess");
        } catch (Exception e) {
            logger.debug("Unload request failed (subprocess may already be down): {}", e.getMessage());
        }

        // Terminate the process
        Process p = this.process;
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
        logger.info("LLM serving subprocess stopped");
    }

    /**
     * Check whether the serving subprocess is currently running and reachable.
     */
    public boolean isRunning() {
        return running.get() && process != null && process.isAlive();
    }

    /**
     * Return the configured HTTP port for the subprocess server.
     */
    public int getServingPort() {
        return servingPort;
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
     * Build the full JVM command (or native-image command) for launching the subprocess.
     *
     * <p>Follows the same conventions as {@link ai.kompile.app.services.subprocess.VlmTestSubprocessLauncher}:
     * <ul>
     *   <li>Use {@link SubprocessExecutableConfig} when available, otherwise fall back to
     *       building the JVM command manually.</li>
     *   <li>Forward all relevant system properties with the prefixes in
     *       {@link #FORWARDED_PROPERTY_PREFIXES}.</li>
     *   <li>Set {@code -Dnd4j.environment.*} properties from the resolved ND4J config to
     *       propagate device-routing overrides.</li>
     *   <li>Set {@code -Dorg.bytedeco.javacpp.cachedir} to a fresh per-subprocess temp dir.</li>
     * </ul>
     */
    private List<String> buildCommand(Path argsFile, Nd4jEnvironmentConfig nd4jConfig) throws IOException {
        // If SubprocessExecutableConfig is present, use its buildServingCommand helper
        // (which handles native-image mode automatically).
        if (subprocessExecutableConfig != null) {
            String classpath = buildClasspath();
            String javaPath = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            return buildJvmCommand(argsFile, nd4jConfig, javaPath, classpath);
        }

        // Fallback: build the JVM command directly
        String javaPath = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = buildClasspath();
        return buildJvmCommand(argsFile, nd4jConfig, javaPath, classpath);
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
        command.add("-Dorg.bytedeco.javacpp.pathsFirst=true");
        command.add("-Dorg.bytedeco.javacpp.logger.debug=false");
        command.add("-Dorg.bytedeco.javacpp.nopointergc=true");

        // Off-heap: 2× heap for LLM (pinned host memory shared with VRAM)
        long heapBytes = 8L * 1024L * 1024L * 1024L;   // 8 GB in bytes
        long offHeapBytes = heapBytes * 2L;
        command.add("-Dorg.bytedeco.javacpp.maxbytes=" + offHeapBytes);
        command.add("-Dorg.bytedeco.javacpp.maxphysicalbytes=" + offHeapBytes);

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

        // Forward all matching system properties from the parent JVM
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

        command.add("-cp");
        command.add(classpath);
        command.add("ai.kompile.app.subprocess.ServingSubprocessMain");
        command.add(argsFile.toAbsolutePath().toString());

        return command;
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
     * into a sibling directory, exactly as {@link VlmTestSubprocessLauncher} does.</p>
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
                throw new IOException("Serving subprocess exited prematurely with code " + p.exitValue());
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
                    logger.info("Serving subprocess is ready: {}", response.body());
                    return;
                }
                logger.debug("Serving subprocess returned HTTP {}: {}", response.statusCode(), response.body());
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
        List<String> knownVars = List.of(
                "ND4J_BACKEND", "ND4J_RESOURCES_DIR",
                "OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS",
                "GOTO_NUM_THREADS", "VECLIB_MAXIMUM_THREADS", "NUMEXPR_NUM_THREADS",
                "CUDA_VISIBLE_DEVICES", "CUDA_DEVICE_ORDER", "CUDA_LAUNCH_BLOCKING",
                "CUDA_CACHE_PATH", "JAVACPP_PLATFORM", "JAVACPP_CACHESFX",
                "ND4J_HEAP_SPACE", "ND4J_OFF_HEAP_SPACE", "KOMPILE_MODELS_DIR",
                ND4JEnvironmentVars.ND4J_TRITON_CACHE_DIR, ND4JEnvironmentVars.ND4J_TRITON_DUMP_DIR
        );
        for (String var : knownVars) {
            String val = System.getenv(var);
            if (val != null && !val.isEmpty()) {
                env.put(var, val);
            }
        }
        // Also propagate any ND4J_ / KOMPILE_ prefixed env vars not in the list above
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String key = e.getKey();
            if ((key.startsWith("ND4J_") || key.startsWith("KOMPILE_")) && !env.containsKey(key)) {
                env.put(key, e.getValue());
            }
        }

        // When DSP diagnostics are enabled, ensure the subprocess uses "full" level
        // so C++ actually echoes [DSP_DIAG] lines to stdout (level 2).
        // "detailed" (level 1) does NOT produce live stdout output.
        if (env.containsKey("ND4J_DSP_DIAGNOSTICS") && !env.containsKey("ND4J_DSP_DIAGNOSTICS_LEVEL")) {
            env.put("ND4J_DSP_DIAGNOSTICS_LEVEL", "full");
            logger.info("Auto-set ND4J_DSP_DIAGNOSTICS_LEVEL=full for serving subprocess (DSP diagnostics enabled)");
        }

        // Propagate triton cache/dump dirs from config when not already set via env var.
        // The native code checks ND4J_TRITON_CACHE_DIR directly — without this,
        // the subprocess resolves to a different default dir and misses the kernel cache.
        if (nd4jConfig != null) {
            if (!env.containsKey(ND4JEnvironmentVars.ND4J_TRITON_CACHE_DIR)) {
                String cacheDir = nd4jConfig.tritonCacheDir();
                if (cacheDir != null && !cacheDir.isBlank()) {
                    env.put(ND4JEnvironmentVars.ND4J_TRITON_CACHE_DIR, cacheDir);
                    logger.info("Set {}={} from config for serving subprocess",
                            ND4JEnvironmentVars.ND4J_TRITON_CACHE_DIR, cacheDir);
                }
            }
            if (!env.containsKey(ND4JEnvironmentVars.ND4J_TRITON_DUMP_DIR)) {
                String dumpDir = nd4jConfig.tritonDumpDir();
                if (dumpDir != null && !dumpDir.isBlank()) {
                    env.put(ND4JEnvironmentVars.ND4J_TRITON_DUMP_DIR, dumpDir);
                }
            }
        }
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
