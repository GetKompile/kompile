package ai.kompile.embedding.anserini;

import ai.kompile.core.crawl.graph.AgentCallContext;
import ai.kompile.core.crawl.graph.CrawlJobScoped;
import ai.kompile.core.crawl.graph.CrawlProgressEvent;
import ai.kompile.core.crawl.graph.HeavyMemoryCoordinator;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.embedding.anserini.config.AnseriniEmbeddingConfiguration.AnseriniEmbeddingProperties;
import ai.kompile.embedding.anserini.event.EmbeddingSubprocessEvent;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessLauncher;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMessage;
import jakarta.annotation.PreDestroy;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import ai.kompile.app.subprocess.SubprocessLogBus;
import ai.kompile.app.subprocess.SubprocessLogEvent;
import ai.kompile.app.subprocess.SubprocessRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import ai.kompile.embedding.anserini.config.EmbeddingRestartConfig;
import ai.kompile.embedding.anserini.config.EmbeddingRestartConfigService;
import ai.kompile.embedding.anserini.config.EmbeddingRestartStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Anserini embedding model implementation that delegates ALL embedding work to a subprocess.
 *
 * <p><b>CRITICAL:</b> This class NEVER loads SameDiff/ND4J models in the main JVM. All model
 * loading and inference happens in an isolated subprocess via {@link EmbeddingSubprocessLauncher}.</p>
 *
 * <p>Benefits of subprocess isolation:
 * <ul>
 *   <li>Main JVM startup is fast (no model loading delay)</li>
 *   <li>Native library conflicts are avoided</li>
 *   <li>Memory management is isolated (subprocess can be killed/restarted on OOM)</li>
 *   <li>Model crashes don't crash the main application</li>
 * </ul>
 * </p>
 */
@Service("anseriniEmbeddingModelImpl")
@ConditionalOnClass(name = "ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl")
@ConditionalOnProperty(value = "kompile.embedding.anserini.enabled", havingValue = "true", matchIfMissing = true)
@Lazy
@Primary
public class AnseriniEmbeddingModelImpl implements EmbeddingModel, CrawlJobScoped {

    private static final Logger log = LoggerFactory.getLogger(AnseriniEmbeddingModelImpl.class);

    private static final String DEFAULT_MODEL_IDENTIFIER = "bge-base-en-v1.5";
    private static final double MIN_EMBEDDING_MAGNITUDE = 1e-18;

    /**
     * Enum indicating where the model was loaded from.
     */
    public enum ModelSource {
        /** Model loaded in subprocess from registry */
        REGISTRY,
        /** Model not yet initialized */
        NOT_INITIALIZED,
        /** Model initialization failed - can retry later */
        FAILED
    }

    /**
     * Enum indicating the current loading phase during lazy initialization.
     */
    public enum LoadingPhase {
        IDLE,
        STARTING,
        STARTING_SUBPROCESS,
        LOADING_MODEL,
        TESTING_ENCODER,
        COMPLETE,
        FAILED
    }

    // Subprocess launcher - ALL embedding work goes through this
    private volatile EmbeddingSubprocessLauncher subprocessLauncher;
    private final Object launcherLock = new Object();

    @Autowired(required = false)
    private SubprocessRegistry subprocessRegistry;

    /**
     * Shared subprocess log/lifecycle bus. The embedding subprocess's own progress, phase
     * transitions, log lines, and crashes are republished here (keyed by the active crawl
     * {@code jobId}) so the crawl that owns this run sees them live via its per-job sink.
     */
    @Autowired(required = false)
    private SubprocessLogBus subprocessLogBus;

    // Restart-governor configuration (manual toggle + native-crash threshold), read live.
    @Autowired(required = false)
    private EmbeddingRestartConfigService restartConfigService;

    // Model state (mirrors subprocess state)
    private volatile String modelIdentifier;
    private volatile int embeddingDimensions = -1;
    private volatile String encoderType = "UNKNOWN";
    private volatile ModelSource modelSource = ModelSource.NOT_INITIALIZED;
    private volatile boolean initialized = false;
    private volatile String initializationError = null;
    private volatile boolean initializationErrorRetriable = false;

    // Loading progress tracking
    private volatile boolean loading = false;
    private volatile LoadingPhase loadingPhase = LoadingPhase.IDLE;
    private volatile long loadingStartTime = 0;
    private volatile String loadingMessage = null;

    // Batch configuration
    private static final int DEFAULT_OPTIMAL_BATCH = 32;
    private static final int DEFAULT_MAX_BATCH = 64;
    private final int configuredOptimalBatch;
    private final int configuredMaxBatch;

    // Properties for deferred resolution (timeouts are read dynamically from this)
    private volatile AnseriniEmbeddingProperties storedEmbeddingProperties;

    // Event publisher for broadcasting subprocess events to UI
    private final ApplicationEventPublisher eventPublisher;

    // Heavy-memory serialization gate — optional; absent in unit tests and CPU-only builds.
    // When present, ensures embedding and KGE training never run concurrently.
    @Autowired(required = false)
    private HeavyMemoryCoordinator heavyMemoryCoordinator;

    // Job id of the crawl batch currently being embedded; set on the crawl thread at the top
    // of embedBatch() and read by the subprocess-reader-thread batch-resize callback.
    // Serialization through the heavy-memory gate makes this volatile correlation safe.
    private volatile String currentEmbedJobId;

    // Explicit crawl-scoped jobId set by the owning crawl via CrawlJobScoped, independent of
    // AgentCallContext thread-locals (which don't reach the embedding executor threads). Preferred
    // over currentEmbedJobId when set, so ALL embedding events during a crawl are attributed to it.
    private volatile String activeCrawlJobId;

    // Op timing state - stored so it can be applied when subprocess becomes available
    private volatile boolean desiredOpTimingEnabled = false;
    private volatile boolean desiredOpTimingDetailedMode = false;

    // Device routing overrides - set by kompile-app-main to configure device routing
    // before subprocess start
    private volatile Integer deviceRoutingMaxThreads;
    private volatile Integer deviceRoutingMaxMasterThreads;
    private volatile Integer deviceRoutingCudaDevice;
    private volatile Long deviceRoutingMaxDeviceMemory;
    private volatile boolean deviceRoutingEnabled = false;

    // Preemption state - managed by ModelLifecycleManager to prevent auto-restart
    // during GPU preemption by higher-priority services (e.g., VLM)
    private volatile boolean preempted = false;
    private volatile String preemptionReason = null;

    // Restart governor: manual master switch + native-crash circuit breaker.
    // State lives on this long-lived @Service (NOT the per-launch EmbeddingSubprocessLauncher),
    // because ensureInitialized()/reloadModel() build a fresh launcher on each call, which would
    // reset any launcher-local counter and defeat the breaker — the root cause of the observed
    // hours-long embedding crash loop.
    private final AtomicInteger consecutiveNativeCrashes =
            new AtomicInteger(0);
    // Hard cross-session ceiling on ALL restartable embedding failures (stall / native crash / exit-crash).
    // Lives on the @Service so resume()/poll spawning fresh launchers can't reset it and loop forever.
    private final AtomicInteger consecutiveFailures =
            new AtomicInteger(0);
    // Tracks whether the subprocess has ever produced a valid embedding result since the @Service started.
    // Sticky: set on the first successful embedBatch() result; never reset.
    // Distinguishes two stall types at the circuit breaker:
    //   - LOAD stall (wasEverHealthy=false): model never initialized — restart is unlikely to help (e.g. CPU
    //     too slow); defer after the FIRST stall (existing behaviour, unchanged).
    //   - RUNTIME-DEGRADATION stall (wasEverHealthy=true): subprocess ran successfully for a while, then
    //     degraded (e.g. accumulated memory / GC pressure). A fresh restart clears that state and is
    //     restart-recoverable; allow up to MAX_RUNTIME_STALL_RESTARTS before deferring.
    private final AtomicBoolean wasEverHealthy =
            new AtomicBoolean(false);
    // Separate cap for runtime-degradation stall restarts; deliberately small and independent from
    // maxAttempts (which guards ordinary restarts) so the general churn ceiling is not disturbed.
    private static final int MAX_RUNTIME_STALL_RESTARTS = 5;
    private final AtomicInteger runtimeStallRestarts =
            new AtomicInteger(0);

    // Request-timeout forced-restart governor.
    // consecutiveRequestTimeouts counts back-to-back embed/embedBatch TimeoutException hits with no
    // intervening success.  When the count reaches the threshold (default 2, overridable via
    // -Dkompile.embedding.maxConsecutiveRequestTimeouts) it arms forceRestartBeforeNextEmbed.
    // ensureInitialized() picks that flag up lazily at the top of the next call so the restart
    // happens off the embed-caller hot path and never races a concurrent in-flight request.
    // requestTimeoutForcedRestarts is a hard cap (mirroring MAX_RUNTIME_STALL_RESTARTS) that prevents
    // infinite restart loops on permanently-degraded boxes; it is NOT reset on successful model loads.
    private static final int MAX_REQUEST_TIMEOUT_RESTARTS = 5;
    private final AtomicInteger consecutiveRequestTimeouts =
            new AtomicInteger(0);
    private final AtomicBoolean forceRestartBeforeNextEmbed =
            new AtomicBoolean(false);
    private final AtomicInteger requestTimeoutForcedRestarts =
            new AtomicInteger(0);

    private volatile boolean restartsPaused = false;
    private volatile String restartsPausedReason = null;
    private volatile String lastObservedCrashReason = null;

    /** No-arg for Spring AOT */
    public AnseriniEmbeddingModelImpl() {
        this(DEFAULT_MODEL_IDENTIFIER, DEFAULT_OPTIMAL_BATCH, DEFAULT_MAX_BATCH, null);
        // No-arg constructor sets default timeouts in the chained constructor
    }

    /**
     * Set device routing overrides for the embedding subprocess.
     * Called by kompile-app-main when device routing is enabled.
     */
    public void setDeviceRoutingOverrides(Integer maxThreads, Integer maxMasterThreads,
                                          Integer cudaDevice, Long maxDeviceMemory) {
        this.deviceRoutingMaxThreads = maxThreads;
        this.deviceRoutingMaxMasterThreads = maxMasterThreads;
        this.deviceRoutingCudaDevice = cudaDevice;
        this.deviceRoutingMaxDeviceMemory = maxDeviceMemory;
        this.deviceRoutingEnabled = true;

        // Also update existing subprocess launcher if already running
        EmbeddingSubprocessLauncher launcher = this.subprocessLauncher;
        if (launcher != null) {
            launcher.setDeviceRoutingOverrides(maxThreads, maxMasterThreads, cudaDevice, maxDeviceMemory);
        }
    }

    /** Main constructor: uses AnseriniEmbeddingProperties for configuration */
    @Autowired
    public AnseriniEmbeddingModelImpl(
            @Autowired(required = false) AnseriniEmbeddingProperties embeddingProperties,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher) {

        this.storedEmbeddingProperties = embeddingProperties;
        this.eventPublisher = eventPublisher;

        this.modelIdentifier = embeddingProperties != null && embeddingProperties.getModelIdentifier() != null
                && !embeddingProperties.getModelIdentifier().isBlank()
                ? embeddingProperties.getModelIdentifier()
                : DEFAULT_MODEL_IDENTIFIER;

        int optimalBatchSize = embeddingProperties != null
                ? embeddingProperties.getEffectiveOptimalBatchSize(modelIdentifier)
                : DEFAULT_OPTIMAL_BATCH;
        int maxBatchSize = embeddingProperties != null
                ? embeddingProperties.getEffectiveMaxBatchSize(modelIdentifier)
                : DEFAULT_MAX_BATCH;

        this.configuredOptimalBatch = Math.max(1, Math.min(optimalBatchSize, 256));
        this.configuredMaxBatch = Math.max(this.configuredOptimalBatch, Math.min(maxBatchSize, 512));

        log.info("Configured AnseriniEmbeddingModel (SUBPROCESS mode) - NO model loading in main JVM");
        log.info("Model identifier: {} (will be loaded in subprocess on first use)", this.modelIdentifier);
        log.info("Batch size configuration: optimal={}, max={}", configuredOptimalBatch, configuredMaxBatch);
        log.info("Timeout configuration read dynamically from properties (0 = no timeout)");
    }

    // ========== Timeout getters (read dynamically from properties) ==========

    private long getModelLoadTimeoutSeconds() {
        return storedEmbeddingProperties != null ? storedEmbeddingProperties.getModelLoadTimeoutSeconds() : 0;
    }

    private long getRequestTimeoutMs() {
        return storedEmbeddingProperties != null ? storedEmbeddingProperties.getRequestTimeoutMs() : 0;
    }

    private long getLoadModelTimeoutMs() {
        return storedEmbeddingProperties != null ? storedEmbeddingProperties.getLoadModelTimeoutMs() : 0;
    }

    private long getHeartbeatTimeoutMs() {
        return storedEmbeddingProperties != null ? storedEmbeddingProperties.getHeartbeatTimeoutMs() : 0;
    }

    private long getEmbedTimeoutSeconds() {
        return storedEmbeddingProperties != null ? storedEmbeddingProperties.getEmbedTimeoutSeconds() : 0;
    }

    private long getEmbedBatchTimeoutSeconds() {
        return storedEmbeddingProperties != null ? storedEmbeddingProperties.getEmbedBatchTimeoutSeconds() : 0;
    }

    /**
     * Maximum number of back-to-back embed/embedBatch TimeoutExceptions before a forced subprocess
     * restart is armed.  Configurable via {@code -Dkompile.embedding.maxConsecutiveRequestTimeouts}.
     * Default is 2 so a single spurious timeout doesn't trigger a restart, but two in a row do.
     */
    private int getMaxConsecutiveRequestTimeouts() {
        return Integer.getInteger("kompile.embedding.maxConsecutiveRequestTimeouts", 2);
    }

    /** Constructor with explicit batch sizes */
    public AnseriniEmbeddingModelImpl(String modelIdentifier, int optimalBatchSize, int maxBatchSize) {
        this(modelIdentifier, optimalBatchSize, maxBatchSize, null);
    }

    /** Constructor with explicit batch sizes and event publisher */
    public AnseriniEmbeddingModelImpl(String modelIdentifier, int optimalBatchSize, int maxBatchSize,
                                      ApplicationEventPublisher eventPublisher) {
        this.modelIdentifier = (modelIdentifier != null && !modelIdentifier.isBlank())
                ? modelIdentifier : DEFAULT_MODEL_IDENTIFIER;
        this.eventPublisher = eventPublisher;

        this.configuredOptimalBatch = Math.max(1, Math.min(optimalBatchSize, 256));
        this.configuredMaxBatch = Math.max(this.configuredOptimalBatch, Math.min(maxBatchSize, 512));

        log.info("Configured AnseriniEmbeddingModel (SUBPROCESS mode) with model: {}", this.modelIdentifier);
        log.info("Batch size configuration: optimal={}, max={}", configuredOptimalBatch, configuredMaxBatch);
    }

    /**
     * Ensures the subprocess is started and model is loaded.
     * Thread-safe - only one thread will perform initialization.
     */
    private void ensureInitialized() {
        // --- Request-timeout lazy force-restart ---
        // This check precedes the initialized fast-path: the subprocess may be marked initialized
        // yet be unable to serve requests (heartbeat alive but every embed times out).  A lazy
        // approach (armed by the timeout catch, executed here) avoids restarting on the hot embed
        // path and prevents racing concurrent in-flight requests.
        // Guards mirror the existing restart-governor: preempted/paused/disabled all suppress it.
        if (forceRestartBeforeNextEmbed.get() && !preempted && !restartsPaused && isAutoRestartEnabled()) {
            synchronized (launcherLock) {
                // Double-check inside the lock; only one thread executes the teardown.
                if (forceRestartBeforeNextEmbed.compareAndSet(true, false)) {
                    int restartCount = requestTimeoutForcedRestarts.incrementAndGet();
                    log.warn("REQUEST_TIMEOUT_FORCED_RESTART #{}/{}: {} consecutive request timeouts — "
                            + "restarting embedding subprocess to clear degradation",
                            restartCount, MAX_REQUEST_TIMEOUT_RESTARTS,
                            consecutiveRequestTimeouts.get());
                    consecutiveRequestTimeouts.set(0);
                    // Hard cap: if the subprocess keeps timing out after every restart, defer.
                    if (restartCount > MAX_REQUEST_TIMEOUT_RESTARTS) {
                        pauseRestarts("Circuit breaker: " + restartCount
                                + " request-timeout forced restarts exceeded cap of "
                                + MAX_REQUEST_TIMEOUT_RESTARTS + " — subprocess persistently fails to "
                                + "serve requests. Resume via the UI / POST /api/embedding-restart/resume.");
                        log.warn("REQUEST_TIMEOUT_FORCED_RESTART capped after {} restarts (max {})",
                                restartCount, MAX_REQUEST_TIMEOUT_RESTARTS);
                        return;
                    }
                    // Also count toward the general churn ceiling so a box that crashes on every
                    // restart eventually trips restartsPaused via the shared counter.
                    if (consecutiveFailures.incrementAndGet() >= Math.max(2, nativeCrashThreshold())) {
                        pauseRestarts("Circuit breaker: " + consecutiveFailures.get()
                                + " consecutive failures (including request-timeout forced restart) — "
                                + "deferred. Resume via the UI / POST /api/embedding-restart/resume.");
                        return;
                    }
                    // Tear down state exactly as reloadModel() does (we already hold launcherLock).
                    if (subprocessLauncher != null) {
                        try { subprocessLauncher.stop(); } catch (Exception ignored) {}
                        publishEvent(EmbeddingSubprocessEvent.subprocessStopped(this, modelIdentifier));
                        subprocessLauncher = null;
                    }
                    initialized = false;
                    modelSource = ModelSource.NOT_INITIALIZED;
                    initializationError = null;
                    // Fall through: initialized is now false, so the standard init path below fires.
                }
            }
        }

        if (initialized) {
            return;
        }
        if (modelSource == ModelSource.FAILED) {
            return;
        }
        // If preempted by lifecycle manager, do NOT initialize — wait for resumeFromPreemption()
        if (preempted) {
            log.debug("ensureInitialized() skipped — service is preempted: {}", preemptionReason);
            return;
        }

        synchronized (launcherLock) {
            if (initialized) {
                return;
            }
            // Restart governor: do NOT (re)spawn the subprocess when auto-restart is disabled, or
            // restarts are paused (breaker tripped). This is the single choke point that EVERY
            // respawn path funnels through — crash handler, background polling, the scheduled
            // ModelAutoInitializationService, and on-demand requests all reach ensureInitialized() —
            // so gating here makes the manual OFF toggle authoritative across every caller.
            if (!isAutoRestartEnabled() || restartsPaused) {
                log.warn("ensureInitialized() skipped — embedding (re)start suppressed (autoRestartEnabled={}, paused={}, reason={})",
                        isAutoRestartEnabled(), restartsPaused, restartsPausedReason);
                return;
            }
            if (modelSource == ModelSource.FAILED) {
                return;
            }
            if (loading) {
                return;
            }

            loading = true;
            loadingStartTime = System.currentTimeMillis();
            loadingPhase = LoadingPhase.STARTING;
            loadingMessage = "Starting embedding subprocess...";

            try {
                // Clean up any dead subprocess from a previous failed attempt.
                // This handles the case where the subprocess process died (e.g., OOM)
                // but the launcher object still exists with stale state.
                if (subprocessLauncher != null && !subprocessLauncher.isRunning()) {
                    log.info("Cleaning up stale subprocess launcher from previous failed attempt");
                    try {
                        subprocessLauncher.stop();
                    } catch (Exception ignored) {
                        // Best effort cleanup
                    }
                    subprocessLauncher = null;
                }

                // Phase 1: Start subprocess
                loadingPhase = LoadingPhase.STARTING_SUBPROCESS;
                loadingMessage = "Starting embedding subprocess...";
                log.info("Starting embedding subprocess for model: {}", modelIdentifier);

                // Configure subprocess launcher with timeouts (0 = no timeout)
                int heapMb = storedEmbeddingProperties != null
                        ? storedEmbeddingProperties.getSubprocessHeapMb()
                        : 4096;
                long physicalMb = storedEmbeddingProperties != null
                        ? storedEmbeddingProperties.getEffectiveSubprocessMaxPhysicalMb()
                        : (long) heapMb * 4;
                EmbeddingSubprocessLauncher.Builder launcherBuilder = EmbeddingSubprocessLauncher.builder()
                        .maxHeapMb(heapMb)
                        .maxPhysicalMb(physicalMb)
                        .progressCallback(this::handleProgress)
                        .phaseTransitionCallback(this::handlePhaseTransition)
                        .logCallback(this::handleLog)
                        .errorCallback(this::handleError)
                        .crashCallback(this::handleCrash)
                        .batchResizeCallback(this::handleBatchResize);

                // Only set timeouts if configured (> 0), otherwise leave as default (no timeout)
                if (getRequestTimeoutMs() > 0) {
                    launcherBuilder.requestTimeoutMs(getRequestTimeoutMs());
                }
                // LoadModel timeout: defaults to 240000ms (4 min) in properties to cover CPU warm-up
                // (~111s observed); falls back to requestTimeoutMs inside the launcher if not set.
                if (getLoadModelTimeoutMs() > 0) {
                    launcherBuilder.loadModelTimeoutMs(getLoadModelTimeoutMs());
                }
                if (getHeartbeatTimeoutMs() > 0) {
                    launcherBuilder.heartbeatTimeoutMs(getHeartbeatTimeoutMs());
                }

                subprocessLauncher = launcherBuilder.build();

                // Inject subprocess registry for lifecycle tracking
                if (subprocessRegistry != null) {
                    subprocessLauncher.setSubprocessRegistry(subprocessRegistry);
                }

                // Apply device routing overrides if configured
                if (deviceRoutingEnabled) {
                    subprocessLauncher.setDeviceRoutingOverrides(
                            deviceRoutingMaxThreads,
                            deviceRoutingMaxMasterThreads,
                            deviceRoutingCudaDevice,
                            deviceRoutingMaxDeviceMemory);
                }

                // Configure restart policy callback for tracking restarts
                subprocessLauncher.setRestartPolicyCallback(createRestartPolicyCallback());
                subprocessLauncher.setRestartConfig(3, 5000, 2.0); // 3 attempts, 5s initial backoff, 2x multiplier

                subprocessLauncher.start();
                log.info("Embedding subprocess started");

                // Publish SUBPROCESS_STARTED event for job history tracking
                publishEvent(EmbeddingSubprocessEvent.subprocessStarted(this, modelIdentifier));

                // Enable op timing in subprocess BEFORE model loads (if desired)
                if (desiredOpTimingEnabled) {
                    log.info("Enabling op timing in subprocess before model load: detailed={}",
                            desiredOpTimingDetailedMode);
                    try {
                        subprocessLauncher.configureOpTiming(desiredOpTimingEnabled, desiredOpTimingDetailedMode)
                                .get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("Failed to enable op timing before model load", e);
                    }
                }

                // Phase 2: Load model in subprocess
                loadingPhase = LoadingPhase.LOADING_MODEL;
                loadingMessage = "Loading model in subprocess: " + modelIdentifier;
                log.info("Loading model in subprocess: {}", modelIdentifier);

                int configuredAbsoluteMaxBatch = storedEmbeddingProperties != null
                        ? storedEmbeddingProperties.getAbsoluteMaxBatchSize()
                        : configuredMaxBatch;
                CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> loadFuture =
                        subprocessLauncher.loadModel(modelIdentifier, configuredOptimalBatch, configuredMaxBatch,
                                configuredAbsoluteMaxBatch, null);

                // Use configurable timeout for model loading (0 = no timeout)
                EmbeddingSubprocessMessage.LoadModelResponse response;
                if (getModelLoadTimeoutSeconds() > 0) {
                    response = loadFuture.get(getModelLoadTimeoutSeconds(), TimeUnit.SECONDS);
                } else {
                    response = loadFuture.get(); // No timeout - wait indefinitely
                }

                if (!response.success()) {
                    throw new IOException("Subprocess failed to load model: " + response.error());
                }

                // Update state from response
                this.embeddingDimensions = response.dimensions();
                this.encoderType = response.encoderType();
                this.modelSource = ModelSource.REGISTRY;
                this.initialized = true;
                this.initializationError = null;
                // Healthy load — reset the restart circuit-breaker counters.
                consecutiveNativeCrashes.set(0);
                consecutiveFailures.set(0);

                loadingPhase = LoadingPhase.COMPLETE;
                loadingMessage = "Model loaded successfully in subprocess";

                long loadTimeMs = System.currentTimeMillis() - loadingStartTime;
                log.info("Model loaded successfully in subprocess in {}ms: {} (dims={}, type={})",
                        loadTimeMs, modelIdentifier, embeddingDimensions, encoderType);

                // Publish MODEL_LOADED event for job history tracking
                publishEvent(EmbeddingSubprocessEvent.modelLoaded(this, modelIdentifier,
                        embeddingDimensions, encoderType));

            } catch (Exception e) {
                log.error("Failed to initialize embedding subprocess: {}", e.getMessage(), e);
                this.initializationError = e.getMessage();
                this.initializationErrorRetriable = isRetriableError(e);
                loadingPhase = LoadingPhase.FAILED;
                loadingMessage = "Error: " + e.getMessage();

                // A failed initialization attempt must remain failed even if the launcher has stale
                // model metadata from an earlier subprocess state. Retry may keep the process alive.
                if (subprocessLauncher != null) {
                    // Only stop subprocess on non-retriable errors
                    if (!initializationErrorRetriable) {
                        try {
                            subprocessLauncher.stop();
                        } catch (Exception stopEx) {
                            log.warn("Failed to stop embedding subprocess after non-retriable init error: {}", stopEx.getMessage());
                        }
                        subprocessLauncher = null;
                    } else {
                        log.info("Keeping subprocess alive for retry (retriable error: {})", e.getMessage());
                    }
                }

                this.modelSource = ModelSource.FAILED;

                // Publish MODEL_FAILED event for job history tracking
                publishEvent(EmbeddingSubprocessEvent.modelFailed(this, modelIdentifier, e.getMessage()));
            } finally {
                loading = false;
            }
        }
    }

    /**
     * Idempotently starts the configured embedding model without stopping a running subprocess.
     * Scheduled startup polling must use this path; {@link #reloadModel()} is intentionally
     * disruptive and is reserved for explicit model switches/restarts.
     */
    public boolean initializeIfNeeded() {
        ensureInitialized();
        return isInitialized();
    }

    private boolean isRetriableError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String upper = msg.toUpperCase();
        return upper.contains("TIMEOUT") || upper.contains("CONNECTION") || upper.contains("UNAVAILABLE")
                || upper.contains("STREAM CLOSED") || upper.contains("NOT ALIVE")
                || upper.contains("PROCESS MAY HAVE CRASHED") || upper.contains("CRASH HANDLING TRIGGERED")
                || upper.contains("SUBPROCESS CRASHED");
    }

    // ========== Subprocess callbacks ==========

    private void handleProgress(EmbeddingSubprocessMessage.Progress progress) {
        log.debug("[subprocess] Progress: {} {}% - {}", progress.phase(), progress.progressPercent(), progress.message());
        publishEvent(EmbeddingSubprocessEvent.progress(this, modelIdentifier,
                progress.phase(), progress.progressPercent(), progress.message()));
        publishToBus("INFO", "[" + progress.phase() + "] " + progress.progressPercent()
                + "% - " + progress.message(), false);
    }

    private void handlePhaseTransition(EmbeddingSubprocessMessage.PhaseTransition transition) {
        log.info("[subprocess] Phase: {} -> {} ({}ms)", transition.fromPhase(), transition.toPhase(), transition.phaseDurationMs());
        publishEvent(EmbeddingSubprocessEvent.phaseTransition(this, modelIdentifier,
                transition.fromPhase(), transition.toPhase(), transition.phaseDurationMs()));
        publishToBus("INFO", "phase " + transition.fromPhase() + " → " + transition.toPhase()
                + " (" + transition.phaseDurationMs() + "ms)", true);
    }

    private void handleLog(EmbeddingSubprocessMessage.Log logMsg) {
        switch (logMsg.level()) {
            case "ERROR":
                log.error("[subprocess:{}] {}", logMsg.source(), logMsg.message());
                break;
            case "WARN":
                log.warn("[subprocess:{}] {}", logMsg.source(), logMsg.message());
                break;
            default:
                log.info("[subprocess:{}] {}", logMsg.source(), logMsg.message());
        }
        publishEvent(EmbeddingSubprocessEvent.log(this, modelIdentifier,
                logMsg.level(), logMsg.source(), logMsg.message()));
        publishToBus(logMsg.level(), "[" + logMsg.source() + "] " + logMsg.message(), false);
    }

    private void handleError(EmbeddingSubprocessMessage.Error error) {
        log.error("[subprocess] Error in {}: {} - {}", error.phase(), error.errorType(), error.errorMessage());
        publishEvent(EmbeddingSubprocessEvent.error(this, modelIdentifier,
                error.errorMessage(), error.errorType(), error.phase()));
    }

    private void handleCrash(Exception e) {
        log.error("[subprocess] CRASH", e);
        this.lastObservedCrashReason = (e != null ? e.getMessage() : null);
        publishEvent(EmbeddingSubprocessEvent.subprocessCrashed(this, modelIdentifier, e.getMessage()));
        publishToBus("ERROR", "subprocess crashed: " + (e != null ? e.getMessage() : "unknown"), false);
        // Subprocess launcher will handle restart via RestartPolicyCallback
    }

    private void handleBatchResize(EmbeddingSubprocessMessage.BatchResizeNotice notice) {
        // Structured log line that higher layers can grep for auto-tuning.
        log.warn("EMBED_DECISION batchResize model={} old={} new={} reason={} physicalBytes={} maxPhysicalBytes={}",
                modelIdentifier, notice.oldBatch(), notice.newBatch(), notice.reason(),
                notice.physicalBytes(), notice.maxPhysicalBytes());

        // Publish a DECISION crawl-progress event so the SSE UI can surface the resize live.
        // This callback runs on the subprocess reader thread where the AgentCallContext thread-local
        // is NOT set; read currentEmbedJobId (volatile, written on the crawl thread before the gate
        // acquire) instead.  Skip publishing when there is no active crawl job (jobId == null) or
        // no event publisher (unit tests / non-Spring contexts).
        String jobId = resolveJobId();
        if (jobId != null && eventPublisher != null) {
            String decisionMsg = "embedding batch resized " + notice.oldBatch() + "→" + notice.newBatch()
                    + " (native memory pressure: " + notice.physicalBytes()
                    + "/" + notice.maxPhysicalBytes() + ")";
            try {
                eventPublisher.publishEvent(new CrawlProgressEvent(
                        this,
                        jobId,
                        null,
                        CrawlProgressEvent.EventType.DECISION,
                        decisionMsg));
            } catch (Exception ex) {
                log.debug("Failed to publish DECISION crawl-progress event for batch resize: {}", ex.getMessage());
            }
        }
    }

    @Override
    public void setActiveCrawlJobId(String jobId) {
        this.activeCrawlJobId = (jobId == null || jobId.isBlank()) ? null : jobId;
    }

    /** Prefer the explicit crawl-set jobId; fall back to the AgentCallContext-derived one. */
    private String resolveJobId() {
        String explicit = this.activeCrawlJobId;
        return explicit != null ? explicit : this.currentEmbedJobId;
    }

    /** Republish an embedding-subprocess event onto the shared {@link SubprocessLogBus}, keyed by the active crawl job. */
    private void publishToBus(String level, String message, boolean lifecycle) {
        SubprocessLogBus bus = this.subprocessLogBus;
        if (bus == null) {
            return;
        }
        String jobId = resolveJobId();
        if (lifecycle) {
            bus.publish(SubprocessLogEvent.lifecycle("embedding", "embedding", jobId, message));
        } else {
            bus.publish(SubprocessLogEvent.line("embedding", "embedding", jobId,
                    SubprocessLogEvent.Stream.STDOUT, level, message));
        }
    }

    /**
     * Create a RestartPolicyCallback implementation that publishes events and uses default restart logic.
     */
    private EmbeddingSubprocessLauncher.RestartPolicyCallback createRestartPolicyCallback() {
        return new EmbeddingSubprocessLauncher.RestartPolicyCallback() {

            @Override
            public EmbeddingSubprocessLauncher.RestartConfiguration shouldRestart(
                    String taskId, int exitCode, String crashReason, int attemptNumber) {
                // CRITICAL: If preempted by lifecycle manager, do NOT restart.
                // The lifecycle manager will call resumeFromPreemption() when ready.
                if (preempted) {
                    log.info("Restart suppressed — service is preempted by lifecycle manager: {}", preemptionReason);
                    return null;
                }

                // Restart governor: manual master switch. When disabled, never auto-restart; pause so
                // the on-demand and polling paths also stay down until the user re-enables/resumes.
                if (!isAutoRestartEnabled()) {
                    pauseRestarts("Auto-restart disabled by configuration");
                    return null;
                }
                // Circuit breaker already tripped — stay down until manually resumed.
                if (restartsPaused) {
                    log.warn("Restart suppressed — embedding subprocess restarts are paused: {}", restartsPausedReason);
                    return null;
                }

                // Default restart policy: allow restarts for memory-related failures
                int maxAttempts = subprocessLauncher != null ? subprocessLauncher.getMaxRestartAttempts() : 3;

                if (attemptNumber > maxAttempts) {
                    log.warn("Restart attempt {} exceeds max {} for task {}", attemptNumber, maxAttempts, taskId);
                    return null; // Don't restart
                }

                // Calculate backoff with exponential increase
                long backoffMs = (long) (5000 * Math.pow(2.0, attemptNumber - 1));

                // Categorize failure and decide if restartable
                boolean isRestartable = isRestartableFailure(exitCode, crashReason);
                if (!isRestartable) {
                    log.info("Failure not restartable (exit code: {}, reason: {})", exitCode, crashReason);
                    return null;
                }

                String reason = categorizeFailureReason(exitCode, crashReason);

                // Stall circuit breaker: a heartbeat-timeout / no-heartbeat exit means either:
                //   (a) LOAD STALL (wasEverHealthy=false): the model never initialized within the heartbeat
                //       window — persistent on slow CPU. Retrying just churns (start→stall→kill) and wedges
                //       the shared H2 DB. Defer on the FIRST stall.
                //   (b) RUNTIME-DEGRADATION STALL (wasEverHealthy=true): the subprocess ran successfully for
                //       a while, then slowed due to accumulated memory / GC pressure. A fresh restart clears
                //       that state — restart-recoverable up to MAX_RUNTIME_STALL_RESTARTS times.
                if ((crashReason != null && crashReason.toLowerCase().contains("heartbeat"))
                        || "STALLED_NO_HEARTBEAT".equals(reason)) {
                    if (wasEverHealthy.get()) {
                        // Runtime-degradation stall — allow a bounded number of restarts before deferring.
                        int stallAttempt = runtimeStallRestarts.incrementAndGet();
                        if (stallAttempt > MAX_RUNTIME_STALL_RESTARTS) {
                            pauseRestarts("Circuit breaker: " + stallAttempt
                                    + " runtime-degradation stalls (heartbeat timeout after healthy operation) "
                                    + "— deferred after cap of " + MAX_RUNTIME_STALL_RESTARTS + ". "
                                    + "Resume via the UI / POST /api/embedding-restart/resume.");
                            return null;
                        }
                        log.warn("Runtime-degradation stall (subprocess was healthy) — restarting to clear "
                                + "accumulated state, attempt {}/{}", stallAttempt, MAX_RUNTIME_STALL_RESTARTS);
                        // Runtime-degradation stalls are governed by their own cap (MAX_RUNTIME_STALL_RESTARTS).
                        // Return a restart config directly — do NOT fall through to the general churn ceiling,
                        // which would double-count this failure and trip prematurely.
                        long backoffMsStall = (long) (5000 * Math.pow(2.0, stallAttempt - 1));
                        long heapBytesStall = 4L * 1024 * 1024 * 1024;
                        return new EmbeddingSubprocessLauncher.RestartConfiguration(
                                backoffMsStall, heapBytesStall, configuredOptimalBatch,
                                Runtime.getRuntime().availableProcessors(), reason);
                    } else {
                        // Load stall — model never initialized; defer immediately (unchanged behaviour).
                        pauseRestarts("Circuit breaker: embedding stalled (heartbeat timeout) — deferred after "
                                + "1 attempt; model load is too slow to initialize within the heartbeat window "
                                + "(e.g. CPU). Resume via the UI / POST /api/embedding-restart/resume, or run on GPU.");
                        return null;
                    }
                }

                // General churn ceiling: ANY restartable failure (incl. clean-ish exits that are neither
                // native crashes nor stalls) counts toward a hard cap across launcher respawns, so
                // resume()/poll spawning fresh launchers can't loop forever on a box where the model never
                // settles. Defer once exceeded.
                if (consecutiveFailures.incrementAndGet() >= Math.max(2, nativeCrashThreshold())) {
                    pauseRestarts("Circuit breaker: " + consecutiveFailures.get() + " consecutive embedding "
                            + "restart failures (" + reason + ") — deferred. Resume via the UI / "
                            + "POST /api/embedding-restart/resume, or run on GPU.");
                    return null;
                }

                // Native-crash circuit breaker: count CONSECUTIVE native crashes (SIGABRT/SIGSEGV).
                // After the configured threshold, trip the breaker and stop respawning until resumed.
                // The counter lives on the @Service so it survives the fresh launcher built by each
                // ensureInitialized()/reloadModel() — otherwise it would reset every respawn and loop forever.
                if (isNativeCrash(exitCode)) {
                    int crashes = consecutiveNativeCrashes.incrementAndGet();
                    int threshold = nativeCrashThreshold();
                    if (crashes >= threshold) {
                        pauseRestarts("Circuit breaker: " + crashes
                                + " consecutive native crashes (exit " + exitCode + ", " + reason + ")");
                        return null;
                    }
                    log.warn("Native crash {} of {} before embedding restart circuit breaker trips (exit {})",
                            crashes, threshold, exitCode);
                }

                // Use current configuration (could be enhanced to adjust based on failure type)
                long heapBytes = 4L * 1024 * 1024 * 1024; // 4GB default
                int batchSize = configuredOptimalBatch;
                int threads = Runtime.getRuntime().availableProcessors();

                return new EmbeddingSubprocessLauncher.RestartConfiguration(
                        backoffMs, heapBytes, batchSize, threads, reason);
            }

            @Override
            public void onRestartAttempt(String taskId, String fileName, int attemptNumber, int maxAttempts,
                                          String reason, EmbeddingSubprocessLauncher.RestartConfiguration config) {
                log.info("Publishing restart attempt event: attempt {}/{} for model {} (reason: {})",
                        attemptNumber, maxAttempts, modelIdentifier, reason);

                long heapBytes = config != null ? config.newHeapBytes() : 0;
                int batchSize = config != null ? config.newBatchSize() : 0;
                int threads = config != null ? config.newThreadCount() : 0;
                long backoffMs = config != null ? config.backoffMs() : 0;

                publishEvent(EmbeddingSubprocessEvent.subprocessRestarting(
                        AnseriniEmbeddingModelImpl.this, modelIdentifier,
                        attemptNumber, maxAttempts, reason, backoffMs,
                        heapBytes, batchSize, threads));
            }

            @Override
            public void onRestartSuccess(String taskId, int attemptNumber) {
                log.info("Publishing restart success event: attempt {} for model {}", attemptNumber, modelIdentifier);
                publishEvent(EmbeddingSubprocessEvent.subprocessRestartSuccess(
                        AnseriniEmbeddingModelImpl.this, modelIdentifier, attemptNumber));
            }

            @Override
            public void onRestartExhausted(String taskId, int totalAttempts, String lastReason) {
                log.warn("Publishing restart exhausted event: {} attempts for model {} (last reason: {})",
                        totalAttempts, modelIdentifier, lastReason);
                // Make the give-up STICKY at the @Service level. Stalls / heartbeat-timeouts are not
                // native crashes, so they never hit the consecutive-native-crash breaker above and would
                // otherwise be respawned forever by the auto-init poll / reloadModel(). Tripping the
                // restart governor here defers the model (visible in the UI) instead of crash-looping and
                // wedging the shared DB. Resume is explicit (UI / POST /api/embedding-restart/resume / a job).
                pauseRestarts("Restarts exhausted after " + totalAttempts + " attempt(s): " + lastReason);
                publishEvent(EmbeddingSubprocessEvent.subprocessRestartExhausted(
                        AnseriniEmbeddingModelImpl.this, modelIdentifier, totalAttempts, lastReason));
            }

            private boolean isRestartableFailure(int exitCode, String crashReason) {
                // Restart for OOM, native crashes, and stalls
                return switch (exitCode) {
                    case 137 -> true;  // OOM killed
                    case 134, 136, 139 -> true;  // Native crashes
                    case -1 -> true;  // Unknown (might be heartbeat timeout)
                    case 130, 143 -> false;  // User cancelled (SIGINT, SIGTERM)
                    case 0 -> false;  // Normal exit
                    default -> crashReason != null &&
                            (crashReason.contains("OutOfMemory") || crashReason.contains("heartbeat"));
                };
            }

            private String categorizeFailureReason(int exitCode, String crashReason) {
                if (crashReason != null && crashReason.contains("OutOfMemory")) {
                    return "OUT_OF_MEMORY";
                }
                return switch (exitCode) {
                    case 137 -> "OOM_KILLED";
                    case 134, 136, 139 -> "NATIVE_CRASH";
                    case 130, 143 -> "CANCELLED";
                    case -1 -> crashReason != null && crashReason.contains("heartbeat") ?
                            "STALLED_NO_HEARTBEAT" : "UNKNOWN";
                    default -> "EXIT_" + exitCode;
                };
            }
        };
    }

    /**
     * Publish an event to the Spring ApplicationEventPublisher if available.
     */
    private void publishEvent(EmbeddingSubprocessEvent event) {
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(event);
            } catch (Exception e) {
                log.debug("Failed to publish subprocess event: {}", e.getMessage());
            }
        }
    }

    // ========== EmbeddingModel interface ==========

    private void validateEmbeddingBatch(List<float[]> embeddings, int expectedCount, String label) {
        if (embeddings == null) {
            throw new IllegalStateException(label + " returned null embeddings");
        }
        if (embeddings.size() != expectedCount) {
            throw new IllegalStateException(label + " returned " + embeddings.size()
                    + " embeddings for " + expectedCount + " text(s)");
        }
        for (int i = 0; i < embeddings.size(); i++) {
            validateEmbeddingVector(embeddings.get(i), label + " index=" + i);
        }
    }

    private void validateEmbeddingVector(float[] embedding, String label) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException(label + " produced no vector");
        }
        int expectedDimensions = dimensions();
        if (expectedDimensions > 0 && embedding.length != expectedDimensions) {
            throw new IllegalStateException(label + " produced dimension " + embedding.length
                    + " but expected " + expectedDimensions);
        }
        double magnitudeSquared = 0.0;
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException(label + " produced a non-finite vector value");
            }
            magnitudeSquared += (double) value * value;
        }
        if (magnitudeSquared <= MIN_EMBEDDING_MAGNITUDE) {
            throw new IllegalStateException(label + " produced a near-zero vector");
        }
    }

    @Override
    public INDArray embed(String text) {
        if (text == null || text.isBlank()) return Nd4j.empty(DataType.FLOAT);

        ensureInitialized();

        if (!initialized || subprocessLauncher == null || !subprocessLauncher.isRunning()) {
            log.debug("Embed called but subprocess not running");
            return Nd4j.empty(DataType.FLOAT);
        }

        try {
            // Use configurable timeout for single embed (0 = no timeout)
            float[] embedding;
            if (getEmbedTimeoutSeconds() > 0) {
                embedding = subprocessLauncher.embed(text).get(getEmbedTimeoutSeconds(), TimeUnit.SECONDS);
            } else {
                embedding = subprocessLauncher.embed(text).get(); // No timeout - wait indefinitely
            }
            validateEmbeddingVector(embedding, "single embed");
            // Success: lane is healthy — reset the request-timeout counter.
            consecutiveRequestTimeouts.set(0);
            return Nd4j.create(embedding);
        } catch (TimeoutException te) {
            long timeoutSec = getEmbedTimeoutSeconds();
            String msg = "Embedding lane timed out after " + timeoutSec + "s — lane-dead, single embed skipped";
            log.error("EMBEDDING_TIMEOUT: single embed timed out after {}s in subprocess", timeoutSec);
            int n = consecutiveRequestTimeouts.incrementAndGet();
            if (n >= getMaxConsecutiveRequestTimeouts() && !restartsPaused && !preempted && isAutoRestartEnabled()) {
                forceRestartBeforeNextEmbed.set(true);
                log.warn("EMBEDDING_REQUEST_TIMEOUT_ARMING_RESTART: {} consecutive request timeouts — "
                        + "forcing embedding subprocess restart before next embed to clear degradation", n);
            }
            publishToBus("ERROR", msg, false);
            publishEvent(EmbeddingSubprocessEvent.error(this, modelIdentifier, msg, "TIMEOUT", "embed"));
            return Nd4j.empty(DataType.FLOAT);
        } catch (Exception e) {
            log.error("Error embedding text via subprocess", e);
            return Nd4j.empty(DataType.FLOAT);
        }
    }

    /**
     * Result of embedding with detailed timing information.
     * Tracks both total wall-clock time (including subprocess overhead) and actual inference time.
     */
    public record EmbedTimingResult(
            INDArray embedding,
            int dimensions,
            long totalWallClockMs,      // Total time from request to response (includes subprocess IPC overhead)
            long subprocessInferenceMs, // Actual time spent in subprocess doing inference
            long subprocessOverheadMs,  // Difference: totalWallClockMs - subprocessInferenceMs
            boolean success,
            String error
    ) {
        public static EmbedTimingResult success(INDArray embedding, int dims, long wallClockMs, long inferenceMs) {
            return new EmbedTimingResult(embedding, dims, wallClockMs, inferenceMs,
                    wallClockMs - inferenceMs, true, null);
        }

        public static EmbedTimingResult failure(String error, long wallClockMs) {
            return new EmbedTimingResult(Nd4j.empty(DataType.FLOAT), 0, wallClockMs, 0, wallClockMs, false, error);
        }
    }

    /**
     * Embed text and return detailed timing information.
     * This allows callers to see both the total time (including subprocess IPC overhead)
     * and the actual inference time within the subprocess.
     */
    public EmbedTimingResult embedWithTiming(String text) {
        if (text == null || text.isBlank()) {
            return EmbedTimingResult.failure("Empty text", 0);
        }

        ensureInitialized();

        if (!initialized || subprocessLauncher == null || !subprocessLauncher.isRunning()) {
            log.debug("embedWithTiming called but subprocess not running");
            return EmbedTimingResult.failure("Subprocess not running", 0);
        }

        long startTime = System.currentTimeMillis();
        try {
            EmbeddingSubprocessMessage.EmbedResponse response;
            if (getEmbedTimeoutSeconds() > 0) {
                response = subprocessLauncher.embedWithTiming(text).get(getEmbedTimeoutSeconds(), TimeUnit.SECONDS);
            } else {
                response = subprocessLauncher.embedWithTiming(text).get();
            }
            long totalWallClockMs = System.currentTimeMillis() - startTime;

            if (response == null || !response.success()) {
                String error = response != null ? response.error() : "Null response";
                return EmbedTimingResult.failure(error, totalWallClockMs);
            }

            float[] embedding = response.embedding();
            try {
                validateEmbeddingVector(embedding, "timed embed");
            } catch (IllegalStateException invalid) {
                return EmbedTimingResult.failure(invalid.getMessage(), totalWallClockMs);
            }

            INDArray result = Nd4j.create(embedding);
            return EmbedTimingResult.success(result, embedding.length, totalWallClockMs, response.embedTimeMs());

        } catch (Exception e) {
            long totalWallClockMs = System.currentTimeMillis() - startTime;
            log.error("Error in embedWithTiming", e);
            return EmbedTimingResult.failure(e.getMessage(), totalWallClockMs);
        }
    }

    @Override
    public INDArray embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return Nd4j.empty(DataType.FLOAT);

        List<float[]> embeddings = embedBatch(texts);
        if (embeddings == null || embeddings.isEmpty()) {
            return Nd4j.empty(DataType.FLOAT);
        }

        int dims = embeddingDimensions > 0 ? embeddingDimensions : embeddings.get(0).length;
        float[] flatBuffer = new float[texts.size() * dims];
        for (int i = 0; i < embeddings.size(); i++) {
            float[] emb = embeddings.get(i);
            if (emb != null) {
                System.arraycopy(emb, 0, flatBuffer, i * dims, Math.min(emb.length, dims));
            }
        }
        return Nd4j.create(flatBuffer, new int[]{texts.size(), dims}, 'c');
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        // Capture job id on the crawl thread (thread-local; NOT set on the subprocess reader thread).
        // Also publish to the volatile field so the batch-resize callback (subprocess reader thread)
        // can correlate its DECISION events to the right job.
        String capturedJobId = AgentCallContext.getJobId();
        if (capturedJobId == null) {
            // Embedding often runs on executor threads with no AgentCallContext; fall back to the
            // crawl-set jobId so events stay attributed to the owning crawl.
            capturedJobId = activeCrawlJobId;
        }
        if (capturedJobId != null) {
            this.currentEmbedJobId = capturedJobId;
        }

        ensureInitialized();

        if (!initialized || subprocessLauncher == null || !subprocessLauncher.isRunning()) {
            log.error("embedBatch called but subprocess not running (initialized={}, source={})",
                    initialized, modelSource);
            return List.of();
        }

        try {
            long start = System.currentTimeMillis();
            // Acquire the heavy-memory serialization gate so embedding and KGE training never
            // run concurrently.  The gate is a Semaphore(1); acquire() blocks until the permit
            // is available.  When the coordinator is absent (unit tests, CPU-only contexts) the
            // null-check makes this a no-op and behavior is identical to before.
            try (AutoCloseable gate = heavyMemoryCoordinator != null
                    ? heavyMemoryCoordinator.acquire("embedding", capturedJobId)
                    : null) {
                // Use one timeout at this layer while the heavy-memory gate is held. The launcher
                // request correlator must not expire earlier; fixed-shape CPU batches can take longer
                // than the generic request timeout even when the subprocess is healthy.
                List<float[]> result;
                if (getEmbedBatchTimeoutSeconds() > 0) {
                    result = subprocessLauncher.embedBatch(texts, 0).get(getEmbedBatchTimeoutSeconds(), TimeUnit.SECONDS);
                } else {
                    result = subprocessLauncher.embedBatch(texts, 0).get(); // No timeout - wait indefinitely
                }
                long elapsed = System.currentTimeMillis() - start;

                validateEmbeddingBatch(result, texts.size(), "batch embed");

                log.info("EMBED_BATCH_DONE: {} texts in {}ms ({} ms/text) via subprocess",
                        texts.size(), elapsed, texts.isEmpty() ? 0 : elapsed / texts.size());

                // Mark runtime health on the first successful batch result. This flag distinguishes
                // a RUNTIME-DEGRADATION stall (subprocess was healthy, then slowed down due to
                // accumulated memory) from a LOAD stall (model never initialized). Sticky — not reset.
                if (!result.isEmpty()) {
                    wasEverHealthy.set(true);
                    // Success: lane is healthy — reset the request-timeout counter.
                    consecutiveRequestTimeouts.set(0);
                }

                return result;
            }
        } catch (TimeoutException te) {
            long timeoutSec = getEmbedBatchTimeoutSeconds();
            String msg = "Embedding lane timed out after " + timeoutSec + "s — lane-dead, batch skipped";
            log.error("EMBEDDING_TIMEOUT: {} texts timed out after {}s in subprocess", texts.size(), timeoutSec);
            int n = consecutiveRequestTimeouts.incrementAndGet();
            if (n >= getMaxConsecutiveRequestTimeouts() && !restartsPaused && !preempted && isAutoRestartEnabled()) {
                forceRestartBeforeNextEmbed.set(true);
                log.warn("EMBEDDING_REQUEST_TIMEOUT_ARMING_RESTART: {} consecutive request timeouts — "
                        + "forcing embedding subprocess restart before next embed to clear degradation", n);
            }
            publishToBus("ERROR", msg, false);
            // Publish an event so the UI sees the timeout
            publishEvent(EmbeddingSubprocessEvent.error(this, modelIdentifier, msg, "TIMEOUT", "embedBatch"));
            // Return empty — the crawl-side isEmbeddingUnavailableError pattern will catch "Embedding lane"
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("embedBatch interrupted while waiting for heavy-memory gate: {}", e.getMessage(), e);
            return List.of();
        } catch (Exception e) {
            log.error("Error embedding batch via subprocess: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return Nd4j.empty(DataType.FLOAT);
        var contents = documents.stream()
                .map(Document::getText)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toList());
        return embed(contents);
    }

    @Override
    public int dimensions() {
        // Priority 1: Local state from successful initialization
        if (initialized && embeddingDimensions > 0) {
            return embeddingDimensions;
        }
        // Priority 2: Check subprocess launcher if running and has a loaded model
        if (subprocessLauncher != null && subprocessLauncher.isModelLoaded()) {
            int subprocessDim = subprocessLauncher.getCurrentDimensions();
            if (subprocessDim > 0) {
                return subprocessDim;
            }
        }
        // Priority 3: Use registry metadata - DON'T trigger subprocess start just for dimensions
        Integer registryDim = AnseriniEncoderFactory.getEmbeddingDimension(modelIdentifier);
        if (registryDim != null && registryDim > 0) {
            return registryDim;
        }
        // Priority 4: Local state even if not initialized
        if (embeddingDimensions > 0) {
            return embeddingDimensions;
        }
        return -1;
    }

    @Override
    public List<String> getSupportedLanguages() {
        return AnseriniEncoderFactory.getSupportedLanguages(modelIdentifier);
    }

    @Override
    public int getOptimalBatchSize() {
        if (storedEmbeddingProperties != null) {
            int dynamicOptimal = storedEmbeddingProperties.getEffectiveOptimalBatchSize(modelIdentifier);
            if (dynamicOptimal > 0) {
                return Math.max(1, Math.min(dynamicOptimal, 8192));
            }
        }
        return configuredOptimalBatch;
    }

    @Override
    public int getMaxBatchSize() {
        if (storedEmbeddingProperties != null) {
            int dynamicMax = storedEmbeddingProperties.getEffectiveMaxBatchSize(modelIdentifier);
            if (dynamicMax > 0) {
                return Math.max(1, Math.min(dynamicMax, 8192));
            }
        }
        return configuredMaxBatch;
    }

    @Override
    public BatchInfo getCurrentBatchInfo() {
        // Could query subprocess for current batch info if needed
        return BatchInfo.empty();
    }

    @Override
    public boolean isLoading() {
        return this.loading;
    }

    @Override
    public String getLoadingPhase() {
        return this.loadingPhase != null ? this.loadingPhase.name() : "IDLE";
    }

    @Override
    public String getLoadingMessage() {
        return this.loadingMessage;
    }

    @Override
    public long getLoadingElapsedMs() {
        if (loadingStartTime == 0 || !loading) {
            return 0;
        }
        return System.currentTimeMillis() - loadingStartTime;
    }

    @Override
    public String getInitializationError() {
        return this.initializationError;
    }

    @Override
    public void close() throws Exception {
        cleanup();
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up embedding model subprocess for: {}", modelIdentifier);
        if (subprocessLauncher != null) {
            try {
                subprocessLauncher.stop();
                // Publish SUBPROCESS_STOPPED event for job history tracking
                publishEvent(EmbeddingSubprocessEvent.subprocessStopped(this, modelIdentifier));
            } catch (Exception e) {
                log.warn("Error stopping subprocess", e);
            }
            subprocessLauncher = null;
        }
        initialized = false;
    }

    // ========== Preemption support (used by ModelLifecycleManager) ==========

    /**
     * Suspend this embedding service for GPU preemption by a higher-priority service.
     *
     * <p>Sets a preemption flag that prevents auto-restart, then stops the subprocess.
     * The flag is cleared by {@link #resumeFromPreemption()}.</p>
     *
     * @param reason human-readable reason for the preemption
     * @return true if the subprocess was successfully stopped
     */
    public boolean suspendForPreemption(String reason) {
        synchronized (launcherLock) {
            this.preempted = true;
            this.preemptionReason = reason;
            log.info("PREEMPTION: Suspending embedding subprocess — {}", reason);

            if (subprocessLauncher != null) {
                try {
                    subprocessLauncher.stop();
                    publishEvent(EmbeddingSubprocessEvent.subprocessStopped(this, modelIdentifier));
                } catch (Exception e) {
                    log.warn("Error stopping subprocess during preemption", e);
                }
                subprocessLauncher = null;
            }

            initialized = false;
            modelSource = ModelSource.NOT_INITIALIZED;
            loadingPhase = LoadingPhase.IDLE;
            loadingMessage = "Preempted: " + reason;

            log.info("PREEMPTION: Embedding subprocess suspended successfully");
            return true;
        }
    }

    /**
     * Resume this embedding service after preemption ends.
     * Clears the preemption flag and re-initializes the subprocess.
     *
     * @return true if the subprocess was successfully restarted
     */
    public boolean resumeFromPreemption() {
        synchronized (launcherLock) {
            if (!preempted) {
                log.info("Resume called but service was not preempted — no-op");
                return isInitialized();
            }

            log.info("PREEMPTION: Resuming embedding subprocess (was preempted: {})", preemptionReason);
            this.preempted = false;
            this.preemptionReason = null;
            this.initializationError = null;
            this.modelSource = ModelSource.NOT_INITIALIZED;

            // Re-initialize — will start subprocess and load model
            ensureInitialized();

            log.info("PREEMPTION: Resume complete (initialized={})", initialized);
            return initialized;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Restart governor: manual master switch + native-crash circuit breaker.
    // ---------------------------------------------------------------------------------------------

    private boolean isAutoRestartEnabled() {
        return restartConfigService == null || restartConfigService.getConfig().isAutoRestartEnabledOrDefault();
    }

    private int nativeCrashThreshold() {
        return restartConfigService == null
                ? EmbeddingRestartConfig.DEFAULT_NATIVE_CRASH_THRESHOLD
                : restartConfigService.getConfig().nativeCrashThresholdOrDefault();
    }

    private static boolean isNativeCrash(int exitCode) {
        // 134 = SIGABRT, 136 = SIGFPE/other, 139 = SIGSEGV — matches the NATIVE_CRASH categorization.
        return exitCode == 134 || exitCode == 136 || exitCode == 139;
    }

    private void pauseRestarts(String reason) {
        this.restartsPaused = true;
        this.restartsPausedReason = reason;
        log.warn("Embedding subprocess restarts PAUSED: {} (resume via the UI or POST /api/embedding-restart/resume)",
                reason);
    }

    // ========== Package-private accessors for unit tests ==========
    // (same pattern as EmbeddingSubprocessLauncher#resolveSystemPhysicalCeilingMb)

    /** Expose wasEverHealthy for test assertions. // visible for testing */
    boolean isWasEverHealthy() {
        return wasEverHealthy.get();
    }

    /** Force-set wasEverHealthy from a test without running a real embed. // visible for testing */
    void setWasEverHealthyForTest(boolean value) {
        wasEverHealthy.set(value);
    }

    /** Check whether restarts are currently paused (circuit breaker tripped). // visible for testing */
    boolean isRestartsPaused() {
        return restartsPaused;
    }

    /** Expose the runtime-stall restart counter for test assertions. // visible for testing */
    int getRuntimeStallRestarts() {
        return runtimeStallRestarts.get();
    }

    /** Reset restart-governor state (paused, counters) for a clean test slate. // visible for testing */
    void resetRestartGovernorForTest() {
        this.restartsPaused = false;
        this.restartsPausedReason = null;
        this.consecutiveNativeCrashes.set(0);
        this.consecutiveFailures.set(0);
        this.runtimeStallRestarts.set(0);
        this.consecutiveRequestTimeouts.set(0);
        this.forceRestartBeforeNextEmbed.set(false);
        this.requestTimeoutForcedRestarts.set(0);
    }

    /** Expose createRestartPolicyCallback so tests can drive it directly. // visible for testing */
    EmbeddingSubprocessLauncher.RestartPolicyCallback createRestartPolicyCallbackForTest() {
        return createRestartPolicyCallback();
    }

    /** Expose forceRestartBeforeNextEmbed flag for test assertions. // visible for testing */
    boolean isForceRestartArmed() {
        return forceRestartBeforeNextEmbed.get();
    }

    /** Expose consecutiveRequestTimeouts counter for test assertions. // visible for testing */
    int getConsecutiveRequestTimeouts() {
        return consecutiveRequestTimeouts.get();
    }

    /** Expose requestTimeoutForcedRestarts counter for test assertions. // visible for testing */
    int getRequestTimeoutForcedRestarts() {
        return requestTimeoutForcedRestarts.get();
    }

    /** Force-set consecutiveRequestTimeouts from a test. // visible for testing */
    void setConsecutiveRequestTimeoutsForTest(int value) {
        consecutiveRequestTimeouts.set(value);
    }

    /** Force-arm (or clear) forceRestartBeforeNextEmbed from a test. // visible for testing */
    void setForceRestartArmedForTest(boolean value) {
        forceRestartBeforeNextEmbed.set(value);
    }

    /** Directly set restartsPaused from a test (mirrors the effect of pauseRestarts()). // visible for testing */
    void setRestartsPausedForTest(boolean paused) {
        this.restartsPaused = paused;
        if (!paused) this.restartsPausedReason = null;
    }

    /** Set consecutiveFailures counter from a test. // visible for testing */
    void setConsecutiveFailuresForTest(int value) {
        consecutiveFailures.set(value);
    }

    /**
     * Package-private seam that exercises the force-restart decision logic in
     * {@link #ensureInitialized()} without actually spawning a subprocess.
     * Returns {@code true} if the restart WOULD have proceeded (flag was set,
     * all guards passed, not capped), {@code false} if it was suppressed.
     * // visible for testing
     */
    boolean triggerForceRestartCheckForTest() {
        if (!forceRestartBeforeNextEmbed.get() || preempted || restartsPaused || !isAutoRestartEnabled()) {
            return false;
        }
        if (!forceRestartBeforeNextEmbed.compareAndSet(true, false)) {
            return false; // another thread beat us
        }
        int restartCount = requestTimeoutForcedRestarts.incrementAndGet();
        consecutiveRequestTimeouts.set(0);
        if (restartCount > MAX_REQUEST_TIMEOUT_RESTARTS) {
            pauseRestarts("Circuit breaker (test): " + restartCount
                    + " request-timeout forced restarts exceeded cap of " + MAX_REQUEST_TIMEOUT_RESTARTS);
            return false;
        }
        if (consecutiveFailures.incrementAndGet() >= Math.max(2, nativeCrashThreshold())) {
            pauseRestarts("Circuit breaker (test): " + consecutiveFailures.get()
                    + " consecutive failures (including request-timeout forced restart)");
            return false;
        }
        return true;
    }

    /**
     * Manually clear a paused/tripped restart state and attempt to bring the subprocess back.
     * Invoked by the Resume action in the UI/REST API.
     *
     * @return true if the subprocess is initialized after the resume attempt
     */
    public boolean resumeRestarts() {
        synchronized (launcherLock) {
            log.info("Resuming embedding subprocess restarts (was paused={}, reason={})",
                    restartsPaused, restartsPausedReason);
            this.restartsPaused = false;
            this.restartsPausedReason = null;
            this.consecutiveNativeCrashes.set(0);
            this.consecutiveFailures.set(0);
            this.initializationError = null;
            if (modelSource == ModelSource.FAILED) {
                this.modelSource = ModelSource.NOT_INITIALIZED;
            }
            // Attempt re-init — will start the subprocess and load the model.
            ensureInitialized();
            log.info("Embedding restart resume complete (initialized={})", initialized);
            return initialized;
        }
    }

    /** Returns the current restart-governor state for the status REST endpoint and UI. */
    public EmbeddingRestartStatus getRestartGovernorStatus() {
        boolean running = subprocessLauncher != null && subprocessLauncher.isRunning();
        return EmbeddingRestartStatus.builder()
                .autoRestartEnabled(isAutoRestartEnabled())
                .nativeCrashThreshold(nativeCrashThreshold())
                .restartsPaused(restartsPaused)
                .consecutiveNativeCrashes(consecutiveNativeCrashes.get())
                .pausedReason(restartsPausedReason)
                .lastCrashReason(lastObservedCrashReason)
                .subprocessRunning(running)
                .modelAvailable(true)
                .build();
    }

    /**
     * True when the restart governor has tripped (consecutive-native-crash breaker OR restarts
     * exhausted) and the embedding subprocess is DEFERRED until explicitly resumed. Callers (e.g. the
     * auto-init poll) must consult this and stop respawning instead of looping.
     */
    public boolean isRestartDeferred() {
        return restartsPaused;
    }

    /** Human-readable reason the restart governor deferred the model, or {@code null} if not deferred. */
    public String getRestartDeferredReason() {
        return restartsPausedReason;
    }

    /**
     * Check if this service is currently preempted (suspended by lifecycle manager).
     * When preempted, the auto-restart policy should NOT restart the subprocess.
     */
    public boolean isPreempted() {
        return preempted;
    }

    /**
     * Get the reason for the current preemption, if any.
     */
    public String getPreemptionReason() {
        return preemptionReason;
    }

    /**
     * Get the CUDA device ID this embedding subprocess is configured to use.
     * Returns null if device routing is not enabled (defaults to device 0 in that case).
     */
    public Integer getConfiguredCudaDevice() {
        if (deviceRoutingEnabled && deviceRoutingCudaDevice != null) {
            return deviceRoutingCudaDevice;
        }
        return null; // No explicit device routing — subprocess uses default (typically device 0)
    }

    // ========== Getters and status methods ==========

    public String getModelIdentifier() { return modelIdentifier; }

    /**
     * Returns the encoder type. First checks the local state, then falls back to
     * the subprocess launcher if available and has a loaded model.
     */
    public String getEncoderType() {
        // If we have a valid encoder type from initialization, use it
        if (encoderType != null && !encoderType.equals("UNKNOWN")) {
            return encoderType;
        }
        // Fall back to subprocess launcher if available
        if (subprocessLauncher != null && subprocessLauncher.isModelLoaded()) {
            String subprocessEncoderType = subprocessLauncher.getEncoderType();
            if (subprocessEncoderType != null && !subprocessEncoderType.equals("UNKNOWN")) {
                return subprocessEncoderType;
            }
        }
        return encoderType;
    }

    /**
     * Returns true if the embedding model is initialized and ready for use.
     * Checks both the local state and the subprocess launcher state.
     */
    public boolean isInitialized() {
        // Check local state first (volatile read)
        if (initialized) {
            return true;
        }
        // Also check subprocess launcher - it may have loaded the model
        // even if the local state hasn't been updated yet.
        // Synchronize to prevent concurrent threads from all updating state simultaneously.
        EmbeddingSubprocessLauncher launcher = this.subprocessLauncher;
        if (launcher != null && launcher.isModelLoaded()) {
            int subDim = launcher.getCurrentDimensions();
            if (subDim > 0) {
                synchronized (launcherLock) {
                    // Double-check after acquiring lock
                    if (initialized) {
                        return true;
                    }
                    // Subprocess has a loaded model with valid dimensions
                    // Update local state to reflect this
                    this.embeddingDimensions = subDim;
                    this.encoderType = launcher.getEncoderType();
                    this.modelSource = ModelSource.REGISTRY;
                    // Also update loading state since model is ready
                    this.loading = false;
                    this.loadingPhase = LoadingPhase.COMPLETE;
                    this.loadingMessage = "Model loaded (synced from subprocess)";
                    this.initializationError = null;
                    // Set initialized last (volatile write publishes all above writes)
                    this.initialized = true;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the source from which the model was loaded.
     * Checks both local state and subprocess launcher state.
     */
    public ModelSource getModelSource() {
        // Check local state first
        if (modelSource != ModelSource.NOT_INITIALIZED) {
            return modelSource;
        }
        // Also check subprocess launcher - it may have loaded the model
        // Use isInitialized() to sync state under lock, avoiding duplicate logic
        if (isInitialized()) {
            return modelSource;
        }
        return modelSource;
    }

    /**
     * Returns true if the initialization error is retriable (transient).
     * Retriable errors include timeouts, connection issues, and temporary unavailability.
     */
    public boolean isInitializationErrorRetriable() {
        return initializationErrorRetriable;
    }

    /**
     * Returns true if background polling should continue.
     * Polling should continue if:
     * - The model is not initialized AND
     * - Either there's no error, or the error is retriable (transient)
     */
    public boolean shouldContinuePolling() {
        // Restart governor: stop background respawn polling when restarts are paused or disabled.
        if (restartsPaused || !isAutoRestartEnabled()) {
            return false;
        }
        if (initialized) {
            return false; // Model is ready, no need to poll
        }
        if (initializationError == null) {
            return true; // No error yet, keep polling
        }
        return initializationErrorRetriable; // Continue only if error is retriable
    }

    @Override
    public int getEmbeddingDimension() {
        Integer dim = AnseriniEncoderFactory.getEmbeddingDimension(modelIdentifier);
        return dim != null ? dim : 768;
    }

    public Map<String, Object> getModelStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("modelId", this.modelIdentifier);
        // Use isInitialized() to properly sync state from subprocess launcher
        status.put("initialized", isInitialized());
        // Use getModelSource() to ensure it's synced with subprocess state
        status.put("source", getModelSource().name());
        // Use getter methods to include subprocess launcher data if available
        status.put("encoderType", getEncoderType());
        status.put("dimensions", dimensions());
        status.put("optimalBatchSize", getOptimalBatchSize());
        status.put("maxBatchSize", getMaxBatchSize());
        status.put("loading", this.loading);
        status.put("loadingPhase", this.loadingPhase.name());
        status.put("loadingMessage", this.loadingMessage);
        status.put("subprocessMode", true);  // Indicate subprocess mode
        status.put("preempted", this.preempted);
        if (this.preempted) {
            status.put("preemptionReason", this.preemptionReason);
        }

        if (subprocessLauncher != null) {
            status.put("subprocessRunning", subprocessLauncher.isRunning());
            status.put("subprocessModelLoaded", subprocessLauncher.isModelLoaded());
            // Also expose subprocess launcher's direct state for debugging
            status.put("subprocessDimensions", subprocessLauncher.getCurrentDimensions());
            status.put("subprocessEncoderType", subprocessLauncher.getEncoderType());
            status.put("subprocessModelId", subprocessLauncher.getCurrentModelId());
        }

        if (this.modelSource == ModelSource.FAILED && this.initializationError != null) {
            status.put("error", this.initializationError);
            status.put("errorRetriable", this.initializationErrorRetriable);
        }

        return status;
    }

    public Set<String> listAvailableModelIds() {
        return AnseriniEncoderFactory.getAvailableModelIds();
    }

    public Map<String, String> listAvailableModels() {
        return AnseriniEncoderFactory.getAvailableModelsWithSources();
    }

    /**
     * Switches the model in the subprocess.
     */
    public boolean switchModel(String newModelIdentifier) {
        if (newModelIdentifier == null || newModelIdentifier.isBlank()) {
            throw new IllegalArgumentException("Model identifier cannot be null or empty");
        }

        if (newModelIdentifier.equals(this.modelIdentifier) && initialized) {
            return true;
        }

        synchronized (launcherLock) {
            log.info("Switching model from '{}' to '{}' in subprocess", this.modelIdentifier, newModelIdentifier);

            this.modelIdentifier = newModelIdentifier;
            this.initialized = false;
            this.modelSource = ModelSource.NOT_INITIALIZED;

            if (subprocessLauncher != null && subprocessLauncher.isRunning()) {
                try {
                    // Publish event for model switch (subprocess already running)
                    publishEvent(EmbeddingSubprocessEvent.subprocessStarted(this, newModelIdentifier));

                    int switchAbsoluteMaxBatch = storedEmbeddingProperties != null
                            ? storedEmbeddingProperties.getAbsoluteMaxBatchSize()
                            : configuredMaxBatch;
                    CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> loadFuture =
                            subprocessLauncher.loadModel(newModelIdentifier, configuredOptimalBatch, configuredMaxBatch,
                                    switchAbsoluteMaxBatch, null);

                    // Use configurable timeout for model loading (0 = no timeout)
                    EmbeddingSubprocessMessage.LoadModelResponse response;
                    if (getModelLoadTimeoutSeconds() > 0) {
                        response = loadFuture.get(getModelLoadTimeoutSeconds(), TimeUnit.SECONDS);
                    } else {
                        response = loadFuture.get(); // No timeout - wait indefinitely
                    }

                    if (response.success()) {
                        this.embeddingDimensions = response.dimensions();
                        this.encoderType = response.encoderType();
                        this.modelSource = ModelSource.REGISTRY;
                        this.initialized = true;
                        log.info("Switched to model: {} (dims={}, type={})",
                                newModelIdentifier, embeddingDimensions, encoderType);

                        // Apply stored op timing state
                        applyStoredOpTimingState();

                        // Publish MODEL_LOADED event
                        publishEvent(EmbeddingSubprocessEvent.modelLoaded(this, newModelIdentifier,
                                embeddingDimensions, encoderType));
                        return true;
                    } else {
                        log.error("Failed to switch model: {}", response.error());
                        // Publish MODEL_FAILED event
                        publishEvent(EmbeddingSubprocessEvent.modelFailed(this, newModelIdentifier, response.error()));
                        return false;
                    }
                } catch (Exception e) {
                    log.error("Error switching model", e);
                    // Publish MODEL_FAILED event
                    publishEvent(EmbeddingSubprocessEvent.modelFailed(this, newModelIdentifier, e.getMessage()));
                    return false;
                }
            } else {
                // Subprocess not running, will be started on next use
                ensureInitialized();
                return initialized;
            }
        }
    }

    public boolean reloadModel() {
        return reloadModel(this.modelIdentifier);
    }

    public boolean reloadModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }

        synchronized (launcherLock) {
            // Stop existing subprocess
            if (subprocessLauncher != null) {
                String oldModelId = this.modelIdentifier;
                subprocessLauncher.stop();
                subprocessLauncher = null;
                // Publish SUBPROCESS_STOPPED event for the old model
                publishEvent(EmbeddingSubprocessEvent.subprocessStopped(this, oldModelId));
            }

            // Reset state
            this.modelIdentifier = modelId;
            this.initialized = false;
            this.modelSource = ModelSource.NOT_INITIALIZED;
            this.initializationError = null;

            // Re-initialize
            ensureInitialized();
            return initialized;
        }
    }

    public void initiateShutdown() {
        log.info("Initiating shutdown for model: {}", modelIdentifier);
        if (subprocessLauncher != null) {
            subprocessLauncher.stop();
        }
    }

    public boolean isShuttingDown() {
        return subprocessLauncher == null || !subprocessLauncher.isRunning();
    }

    /**
     * Restarts the embedding subprocess with updated environment variables.
     * This is useful for debugging when ND4J configuration has been changed
     * and needs to be applied to the subprocess.
     *
     * <p>The restart process:
     * <ol>
     *   <li>Stops the current subprocess gracefully</li>
     *   <li>Clears initialization state</li>
     *   <li>Re-initializes, which will start a new subprocess with current system properties</li>
     * </ol>
     *
     * @return true if restart was successful, false otherwise
     */
    public boolean restartWithUpdatedEnvironment() {
        synchronized (launcherLock) {
            log.info("Restarting embedding subprocess with updated environment for model: {}", modelIdentifier);

            // Publish event for restart initiation
            publishEvent(EmbeddingSubprocessEvent.subprocessStopped(this, modelIdentifier));

            // Stop existing subprocess
            if (subprocessLauncher != null) {
                try {
                    subprocessLauncher.stop();
                } catch (Exception e) {
                    log.warn("Error stopping subprocess during restart", e);
                }
                subprocessLauncher = null;
            }

            // Reset state
            initialized = false;
            modelSource = ModelSource.NOT_INITIALIZED;
            initializationError = null;
            loadingPhase = LoadingPhase.IDLE;
            loadingMessage = null;

            // Re-initialize - this will pick up current system properties
            // which include any newly set ND4J environment variables
            ensureInitialized();

            return initialized;
        }
    }

    /**
     * Gets the subprocess status information including whether it's running,
     * model loaded state, and any relevant metrics.
     *
     * @return Map containing subprocess status details
     */
    public Map<String, Object> getSubprocessStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("modelId", modelIdentifier);
        status.put("initialized", initialized);
        status.put("modelSource", modelSource.name());
        status.put("loadingPhase", loadingPhase.name());
        status.put("loadingMessage", loadingMessage);

        if (subprocessLauncher != null) {
            status.put("subprocessRunning", subprocessLauncher.isRunning());
            status.put("subprocessModelLoaded", subprocessLauncher.isModelLoaded());
            status.put("subprocessDimensions", subprocessLauncher.getCurrentDimensions());
            status.put("subprocessEncoderType", subprocessLauncher.getEncoderType());
            status.put("subprocessModelId", subprocessLauncher.getCurrentModelId());
            status.put("totalEmbeddingsProcessed", subprocessLauncher.getTotalEmbeddingsProcessed());
            status.put("launchMode", subprocessLauncher.getLaunchMode().name());
            status.put("isNativeMode", subprocessLauncher.isNativeMode());
            status.put("lastCrashReason", subprocessLauncher.getLastCrashReason());
        } else {
            status.put("subprocessRunning", false);
            status.put("subprocessModelLoaded", false);
        }

        if (initializationError != null) {
            status.put("error", initializationError);
            status.put("errorRetriable", initializationErrorRetriable);
        }

        return status;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // OP TIMING METHODS (delegate to subprocess)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Configure op timing in the embedding subprocess.
     * This enables ND4J operation timing collection in the subprocess JVM.
     *
     * Note: The desired state is stored so it can be applied when the subprocess starts
     * (in case op timing is enabled before the subprocess is running).
     *
     * @param enabled Whether to enable op timing
     * @param detailedMode Whether to use detailed mode (more info but more overhead)
     * @return CompletableFuture with success status
     */
    public CompletableFuture<Boolean> configureSubprocessOpTiming(boolean enabled, boolean detailedMode) {
        // Always store the desired state - will be applied when subprocess starts if not running
        this.desiredOpTimingEnabled = enabled;
        this.desiredOpTimingDetailedMode = detailedMode;
        log.info("Op timing desired state stored: enabled={}, detailed={}", enabled, detailedMode);

        if (subprocessLauncher == null || !subprocessLauncher.isRunning()) {
            log.info("Subprocess not running - op timing will be applied when subprocess starts");
            return CompletableFuture.completedFuture(true); // Stored for later
        }

        return subprocessLauncher.configureOpTiming(enabled, detailedMode)
                .thenApply(resp -> {
                    if (resp.success()) {
                        log.info("Subprocess op timing configured: enabled={}, detailed={}",
                                resp.enabled(), resp.detailedMode());
                        return true;
                    } else {
                        log.warn("Failed to configure subprocess op timing: {}", resp.error());
                        return false;
                    }
                })
                .exceptionally(e -> {
                    log.error("Error configuring subprocess op timing", e);
                    return false;
                });
    }

    /**
     * Flush op timing stats from the embedding subprocess.
     *
     * @param topN Number of top ops to return (by total time), 0 for all
     * @param reset Whether to reset timing data after flush
     * @return CompletableFuture with timing response (includes hotspots list)
     */
    public CompletableFuture<EmbeddingSubprocessMessage.OpTimingFlushResponse> flushSubprocessOpTiming(
            int topN, boolean reset) {
        if (subprocessLauncher == null || !subprocessLauncher.isRunning()) {
            log.warn("Cannot flush op timing: subprocess not running");
            return CompletableFuture.completedFuture(
                    new EmbeddingSubprocessMessage.OpTimingFlushResponse(
                            "N/A", false, 0, 0, List.of(), "Subprocess not running"));
        }

        return subprocessLauncher.flushOpTiming(topN, reset)
                .exceptionally(e -> {
                    log.error("Error flushing subprocess op timing", e);
                    return new EmbeddingSubprocessMessage.OpTimingFlushResponse(
                            "N/A", false, 0, 0, List.of(), e.getMessage());
                });
    }

    /**
     * Check if subprocess is available for op timing.
     */
    public boolean isSubprocessAvailableForOpTiming() {
        return subprocessLauncher != null && subprocessLauncher.isRunning();
    }

    /**
     * Apply stored op timing state to the subprocess.
     * Called after subprocess starts and model loads successfully.
     */
    private void applyStoredOpTimingState() {
        if (!desiredOpTimingEnabled) {
            return; // Nothing to apply
        }
        if (subprocessLauncher == null || !subprocessLauncher.isRunning()) {
            log.warn("Cannot apply op timing state: subprocess not running");
            return;
        }

        log.info("Applying stored op timing state to subprocess: enabled={}, detailed={}",
                desiredOpTimingEnabled, desiredOpTimingDetailedMode);

        try {
            subprocessLauncher.configureOpTiming(desiredOpTimingEnabled, desiredOpTimingDetailedMode)
                    .thenAccept(resp -> {
                        if (resp.success()) {
                            log.info("Op timing state applied to subprocess: enabled={}, detailed={}",
                                    resp.enabled(), resp.detailedMode());
                        } else {
                            log.warn("Failed to apply op timing state to subprocess: {}", resp.error());
                        }
                    })
                    .exceptionally(e -> {
                        log.error("Error applying op timing state to subprocess", e);
                        return null;
                    });
        } catch (Exception e) {
            log.error("Exception applying op timing state", e);
        }
    }

    /**
     * Check if op timing is currently desired/enabled.
     */
    public boolean isOpTimingDesired() {
        return desiredOpTimingEnabled;
    }

    /**
     * Returns the currently active model ID.
     * Returns the subprocess's loaded model ID if available, otherwise the configured model ID.
     */
    public String getActiveModelId() {
        // If subprocess has a loaded model, return its model ID
        if (subprocessLauncher != null && subprocessLauncher.isModelLoaded()) {
            String subprocessModelId = subprocessLauncher.getCurrentModelId();
            if (subprocessModelId != null && !subprocessModelId.isBlank()) {
                return subprocessModelId;
            }
        }
        // Fall back to configured model identifier
        return modelIdentifier;
    }

    /**
     * Returns the model type (DENSE or SPARSE).
     * In subprocess mode, we determine this from the encoder type or default to DENSE.
     */
    public String getModelType() {
        if (encoderType != null && encoderType.toLowerCase().contains("sparse")) {
            return "SPARSE";
        }
        return "DENSE";
    }

    /**
     * Returns whether auto model management is used.
     * With subprocess mode, we always use registry-based model management.
     */
    public boolean usesAutoModelManagement() {
        return true;
    }

    /**
     * Returns detailed info about available models.
     */
    public Map<String, Map<String, Object>> getAvailableModelsInfo() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Set<String> modelIds = AnseriniEncoderFactory.getAvailableModelIds();
        Map<String, String> modelSources = AnseriniEncoderFactory.getAvailableModelsWithSources();

        for (String modelId : modelIds) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("modelId", modelId);
            info.put("source", modelSources.getOrDefault(modelId, "UNKNOWN"));
            Integer dim = AnseriniEncoderFactory.getEmbeddingDimension(modelId);
            info.put("dimensions", dim != null ? dim : -1);
            info.put("active", modelId.equals(this.modelIdentifier));
            result.put(modelId, info);
        }

        return result;
    }

    /**
     * Returns detailed model info for the current model.
     */
    public Map<String, Object> getModelInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("modelId", modelIdentifier);
        info.put("initialized", initialized);
        info.put("source", modelSource.name());
        // Use getter methods to include subprocess launcher data if available
        info.put("encoderType", getEncoderType());
        info.put("dimensions", dimensions());
        info.put("optimalBatchSize", getOptimalBatchSize());
        info.put("maxBatchSize", getMaxBatchSize());
        info.put("modelType", getModelType());
        info.put("subprocessMode", true);

        if (subprocessLauncher != null) {
            info.put("subprocessRunning", subprocessLauncher.isRunning());
            info.put("subprocessModelLoaded", subprocessLauncher.isModelLoaded());
            // Also expose subprocess launcher's direct state for debugging
            info.put("subprocessDimensions", subprocessLauncher.getCurrentDimensions());
            info.put("subprocessEncoderType", subprocessLauncher.getEncoderType());
        }

        return info;
    }

    /**
     * Returns the optimal batch size for a given sequence length.
     * This is a simplified implementation that doesn't require direct encoder access.
     */
    public int getOptimalBatchSizeForSeqLength(int seqLength) {
        // Simple heuristic: reduce batch size for longer sequences
        if (seqLength <= 128) {
            return configuredOptimalBatch;
        } else if (seqLength <= 256) {
            return Math.max(1, configuredOptimalBatch / 2);
        } else if (seqLength <= 512) {
            return Math.max(1, configuredOptimalBatch / 4);
        } else {
            return Math.max(1, configuredOptimalBatch / 8);
        }
    }

    /**
     * Returns the encoder instance.
     * In subprocess mode, we don't have direct access to the encoder.
     * This method returns null - callers should use embed/embedBatch methods instead.
     *
     * @deprecated Use embed() or embedBatch() methods instead. Direct encoder access
     *             is not available in subprocess mode.
     */
    @Deprecated
    public Object getEncoder() {
        // In subprocess mode, we don't have direct access to the encoder
        // Return null - callers should use embed/embedBatch methods
        log.warn("getEncoder() called but encoder is in subprocess - use embed/embedBatch methods instead");
        return null;
    }
}
