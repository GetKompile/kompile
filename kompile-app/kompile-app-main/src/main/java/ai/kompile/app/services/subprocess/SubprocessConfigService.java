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

import ai.kompile.app.services.ServerPortService;
import ai.kompile.cli.main.util.NativeImageInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing subprocess ingest configuration.
 * Provides runtime configuration management with persistence across restarts.
 */
@Service
public class SubprocessConfigService {

    private static final Logger log = LoggerFactory.getLogger(SubprocessConfigService.class);
    private static final String CONFIG_FILENAME = "subprocess-ingest-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private final ServerPortService serverPortService;

    // Configuration values with defaults from application.properties
    private volatile boolean enabled;
    private volatile String javaPath;
    private volatile String heapSize;
    /**
     * Maximum native/off-heap bytes for JavaCPP.
     *
     * When empty, the subprocess launcher will auto-calculate this value as
     * (heapSize * offHeapMultiplier).
     */
    private volatile String offHeapMaxBytes;
    /**
     * Off-heap memory multiplier relative to heap size.
     * Used when offHeapMaxBytes is empty. Default 2 for ingest/vectorpop, 3 for VLM.
     */
    private volatile int offHeapMultiplier;
    private volatile int timeoutMinutes;
    private volatile int heartbeatIntervalSeconds;
    private volatile int staleThresholdSeconds;

    // VLM subprocess configuration (separate from ingest/vectorpop)
    private volatile String vlmHeapSize;
    private volatile int vlmOffHeapMultiplier;
    private volatile int vlmTimeoutMinutes;
    private volatile int vlmCudaPinnedHostLimitMb;

    // Pipeline configuration (non-embedding settings - embedding batch sizes come
    // from AnseriniEmbeddingProperties)
    private volatile int queueCapacity;
    private volatile boolean parallelIndexing;
    private volatile int indexingWorkers;
    private volatile int indexingBatchAccumulationSize;
    private volatile int embeddingThreads;

    // Defaults from @Value annotations - DEFAULT TO FALSE for in-process mode
    // Subprocess mode can be enabled via UI (Developer Hub > Processing Settings)
    // or via API: POST /api/subprocess-config/enable
    // or by setting kompile.ingest.subprocess.enabled=true in
    // application.properties
    @Value("${kompile.ingest.subprocess.enabled:false}")
    private boolean defaultEnabled;

    @Value("${kompile.ingest.subprocess.java-path:java}")
    private String defaultJavaPath;

    @Value("${kompile.ingest.subprocess.heap-size:4g}")
    private String defaultHeapSize;

    @Value("${kompile.ingest.subprocess.offheap-max-bytes:}")
    private String defaultOffHeapMaxBytes;

    @Value("${kompile.ingest.subprocess.off-heap-multiplier:2}")
    private int defaultOffHeapMultiplier;

    @Value("${kompile.ingest.subprocess.timeout-minutes:60}")
    private int defaultTimeoutMinutes;

    // VLM subprocess defaults
    @Value("${kompile.vlm-test.subprocess.heap-size:16g}")
    private String defaultVlmHeapSize;

    @Value("${kompile.vlm-test.subprocess.off-heap-multiplier:3}")
    private int defaultVlmOffHeapMultiplier;

    @Value("${kompile.vlm-test.subprocess.timeout-minutes:30}")
    private int defaultVlmTimeoutMinutes;

    @Value("${kompile.vlm-test.subprocess.cuda-pinned-host-limit-mb:8192}")
    private int defaultVlmCudaPinnedHostLimitMb;

    @Value("${kompile.ingest.subprocess.heartbeat-interval-seconds:10}")
    private int defaultHeartbeatIntervalSeconds;

    @Value("${kompile.ingest.subprocess.stale-threshold-seconds:120}")
    private int defaultStaleThresholdSeconds;

    // Pipeline defaults (embedding batch sizes come from
    // AnseriniEmbeddingProperties/benchmark config)
    @Value("${kompile.ingest.subprocess.queue-capacity:1000}")
    private int defaultQueueCapacity;

    @Value("${kompile.ingest.subprocess.parallel-indexing:true}")
    private boolean defaultParallelIndexing;

    @Value("${kompile.ingest.subprocess.indexing-workers:4}")
    private int defaultIndexingWorkers;

    @Value("${kompile.ingest.subprocess.indexing-batch-accumulation:8}")
    private int defaultIndexingBatchAccumulationSize;

    // Embedding workers - use 1 worker to avoid thread contention
    // Parallelism comes from OpenMP/BLAS internally, not multiple workers
    private final int defaultEmbeddingThreads = 1;

    // Restart configuration defaults
    @Value("${kompile.subprocess.restart.enabled:true}")
    private boolean defaultRestartEnabled;

    @Value("${kompile.subprocess.restart.max-attempts:3}")
    private int defaultMaxRestartAttempts;

    @Value("${kompile.subprocess.restart.initial-backoff-ms:5000}")
    private int defaultInitialBackoffMs;

    @Value("${kompile.subprocess.restart.backoff-multiplier:2.0}")
    private double defaultBackoffMultiplier;

    @Value("${kompile.subprocess.restart.heap-increase-factor:1.25}")
    private double defaultHeapIncreaseFactor;

    @Value("${kompile.subprocess.restart.system-ram-safety-margin:0.15}")
    private double defaultSystemRamSafetyMargin;

    // Stall/deadlock detection defaults
    @Value("${kompile.subprocess.restart.on-stall:true}")
    private boolean defaultRestartOnStall;

    @Value("${kompile.subprocess.restart.on-timeout:true}")
    private boolean defaultRestartOnTimeout;

    @Value("${kompile.subprocess.restart.stall-detection-threshold-seconds:300}")
    private int defaultStallDetectionThresholdSeconds;

    @Value("${kompile.subprocess.restart.progress-stall-warning-seconds:60}")
    private int defaultProgressStallWarningSeconds;

    // GPU memory monitoring defaults
    @Value("${kompile.memory.gpu-threshold-percent:75}")
    private int defaultGpuMemoryThresholdPercent;

    @Value("${kompile.memory.gpu-critical-percent:85}")
    private int defaultGpuMemoryCriticalPercent;

    @Value("${kompile.memory.gpu-kill-threshold-percent:92}")
    private int defaultGpuMemoryKillThresholdPercent;

    // Off-heap (JavaCPP native) memory monitoring defaults
    @Value("${kompile.memory.offheap-threshold-percent:80}")
    private int defaultOffHeapThresholdPercent;

    @Value("${kompile.memory.offheap-critical-percent:90}")
    private int defaultOffHeapCriticalPercent;

    @Value("${kompile.memory.offheap-kill-threshold-percent:95}")
    private int defaultOffHeapKillThresholdPercent;

    // Runtime restart configuration
    private volatile boolean restartEnabled;
    private volatile int maxRestartAttempts;
    private volatile int initialBackoffMs;
    private volatile double backoffMultiplier;
    private volatile double heapIncreaseFactor;
    private volatile double systemRamSafetyMargin;

    // Stall/deadlock detection runtime config
    private volatile boolean restartOnStall;
    private volatile boolean restartOnTimeout;
    private volatile int stallDetectionThresholdSeconds;
    private volatile int progressStallWarningSeconds;

    // GPU memory monitoring runtime config
    private volatile int gpuMemoryThresholdPercent;
    private volatile int gpuMemoryCriticalPercent;
    private volatile int gpuMemoryKillThresholdPercent;

    // Off-heap (JavaCPP native) memory monitoring runtime config
    private volatile int offHeapThresholdPercent;
    private volatile int offHeapCriticalPercent;
    private volatile int offHeapKillThresholdPercent;

    // Native executable configuration - runtime values
    // Mode: "auto" (detect based on runtime context), "jvm" (always use classpath), "native" (always use native executable)
    private volatile String nativeExecutableMode;
    private volatile String nativeExecutablePath;
    private volatile String ingestExecutablePath;
    private volatile String vectorPopulationExecutablePath;
    private volatile String embeddingExecutablePath;
    private volatile String modelInitExecutablePath;
    private volatile String subprocessTypeFlag;

    // Native executable defaults
    @Value("${kompile.subprocess.executable.mode:auto}")
    private String defaultNativeExecutableMode;

    @Value("${kompile.subprocess.executable.native-path:}")
    private String defaultNativeExecutablePath;

    @Value("${kompile.subprocess.executable.ingest-path:}")
    private String defaultIngestExecutablePath;

    @Value("${kompile.subprocess.executable.vector-population-path:}")
    private String defaultVectorPopulationExecutablePath;

    @Value("${kompile.subprocess.executable.embedding-path:}")
    private String defaultEmbeddingExecutablePath;

    @Value("${kompile.subprocess.executable.model-init-path:}")
    private String defaultModelInitExecutablePath;

    @Value("${kompile.subprocess.executable.type-flag:--subprocess=}")
    private String defaultSubprocessTypeFlag;

    @Autowired
    public SubprocessConfigService(
            @Autowired(required = false) ServerPortService serverPortService,
            @Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.serverPortService = serverPortService;
        this.objectMapper = new ObjectMapper();

        // Use provided dataDir, or fall back to ~/.kompile if not set
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
            log.info("kompile.data.dir not set, using default: {}", effectiveDataDir);
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        log.info("SubprocessConfigService initialized, config path: {}", configFilePath);
    }

    /**
     * Load persisted configuration on startup.
     */
    @PostConstruct
    public void loadPersistedConfig() {
        // Start with defaults
        this.enabled = defaultEnabled;
        this.javaPath = defaultJavaPath;
        this.heapSize = defaultHeapSize;
        this.offHeapMaxBytes = normalizeOptionalString(defaultOffHeapMaxBytes);
        this.offHeapMultiplier = defaultOffHeapMultiplier;
        this.timeoutMinutes = defaultTimeoutMinutes;
        this.vlmHeapSize = defaultVlmHeapSize;
        this.vlmOffHeapMultiplier = defaultVlmOffHeapMultiplier;
        this.vlmTimeoutMinutes = defaultVlmTimeoutMinutes;
        this.vlmCudaPinnedHostLimitMb = defaultVlmCudaPinnedHostLimitMb;
        this.heartbeatIntervalSeconds = defaultHeartbeatIntervalSeconds;
        this.staleThresholdSeconds = defaultStaleThresholdSeconds;

        // Pipeline defaults (embedding batch sizes come from
        // AnseriniEmbeddingProperties)
        this.queueCapacity = defaultQueueCapacity;
        this.parallelIndexing = defaultParallelIndexing;
        this.indexingWorkers = defaultIndexingWorkers;
        this.indexingBatchAccumulationSize = defaultIndexingBatchAccumulationSize;
        this.embeddingThreads = defaultEmbeddingThreads;

        // Restart configuration defaults
        this.restartEnabled = defaultRestartEnabled;
        this.maxRestartAttempts = defaultMaxRestartAttempts;
        this.initialBackoffMs = defaultInitialBackoffMs;
        this.backoffMultiplier = defaultBackoffMultiplier;
        this.heapIncreaseFactor = defaultHeapIncreaseFactor;
        this.systemRamSafetyMargin = defaultSystemRamSafetyMargin;

        // Stall/deadlock detection defaults
        this.restartOnStall = defaultRestartOnStall;
        this.restartOnTimeout = defaultRestartOnTimeout;
        this.stallDetectionThresholdSeconds = defaultStallDetectionThresholdSeconds;
        this.progressStallWarningSeconds = defaultProgressStallWarningSeconds;

        // GPU memory monitoring defaults
        this.gpuMemoryThresholdPercent = defaultGpuMemoryThresholdPercent;
        this.gpuMemoryCriticalPercent = defaultGpuMemoryCriticalPercent;
        this.gpuMemoryKillThresholdPercent = defaultGpuMemoryKillThresholdPercent;

        // Off-heap memory monitoring defaults
        this.offHeapThresholdPercent = defaultOffHeapThresholdPercent;
        this.offHeapCriticalPercent = defaultOffHeapCriticalPercent;
        this.offHeapKillThresholdPercent = defaultOffHeapKillThresholdPercent;

        // Native executable defaults
        this.nativeExecutableMode = normalizeOptionalString(defaultNativeExecutableMode);
        if (this.nativeExecutableMode.isEmpty()) {
            this.nativeExecutableMode = "auto";
        }
        this.nativeExecutablePath = normalizeOptionalString(defaultNativeExecutablePath);
        this.ingestExecutablePath = normalizeOptionalString(defaultIngestExecutablePath);
        this.vectorPopulationExecutablePath = normalizeOptionalString(defaultVectorPopulationExecutablePath);
        this.embeddingExecutablePath = normalizeOptionalString(defaultEmbeddingExecutablePath);
        this.modelInitExecutablePath = normalizeOptionalString(defaultModelInitExecutablePath);
        this.subprocessTypeFlag = defaultSubprocessTypeFlag != null && !defaultSubprocessTypeFlag.isBlank()
                ? defaultSubprocessTypeFlag : "--subprocess=";

        // Skip loading if configFilePath is null (dataDir not configured)
        if (configFilePath == null) {
            log.warn("Cannot load subprocess config - kompile.data.dir not configured. Using defaults.");
            return;
        }

        log.info("Loading persisted subprocess config from: {}", configFilePath);

        if (!Files.exists(configFilePath)) {
            log.info("No persisted subprocess config found at {} - using defaults", configFilePath);
            return;
        }

        try {
            String json = Files.readString(configFilePath);
            Map<String, Object> config = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));

            if (config.containsKey("enabled")) {
                this.enabled = (Boolean) config.get("enabled");
            }
            if (config.containsKey("javaPath")) {
                this.javaPath = (String) config.get("javaPath");
            }
            if (config.containsKey("heapSize")) {
                this.heapSize = (String) config.get("heapSize");
            }
            if (config.containsKey("offHeapMaxBytes")) {
                Object v = config.get("offHeapMaxBytes");
                this.offHeapMaxBytes = normalizeOptionalString(v != null ? v.toString() : null);
            }
            if (config.containsKey("offHeapMultiplier")) {
                this.offHeapMultiplier = ((Number) config.get("offHeapMultiplier")).intValue();
            }
            if (config.containsKey("timeoutMinutes")) {
                this.timeoutMinutes = ((Number) config.get("timeoutMinutes")).intValue();
            }
            if (config.containsKey("vlmHeapSize")) {
                this.vlmHeapSize = (String) config.get("vlmHeapSize");
            }
            if (config.containsKey("vlmOffHeapMultiplier")) {
                this.vlmOffHeapMultiplier = ((Number) config.get("vlmOffHeapMultiplier")).intValue();
            }
            if (config.containsKey("vlmTimeoutMinutes")) {
                this.vlmTimeoutMinutes = ((Number) config.get("vlmTimeoutMinutes")).intValue();
            }
            if (config.containsKey("vlmCudaPinnedHostLimitMb")) {
                this.vlmCudaPinnedHostLimitMb = ((Number) config.get("vlmCudaPinnedHostLimitMb")).intValue();
            }
            if (config.containsKey("heartbeatIntervalSeconds")) {
                this.heartbeatIntervalSeconds = ((Number) config.get("heartbeatIntervalSeconds")).intValue();
            }
            if (config.containsKey("staleThresholdSeconds")) {
                this.staleThresholdSeconds = ((Number) config.get("staleThresholdSeconds")).intValue();
            }

            // Pipeline settings (embedding batch sizes come from
            // AnseriniEmbeddingProperties/benchmark config)
            if (config.containsKey("queueCapacity")) {
                this.queueCapacity = ((Number) config.get("queueCapacity")).intValue();
            }
            if (config.containsKey("parallelIndexing")) {
                this.parallelIndexing = (Boolean) config.get("parallelIndexing");
            }
            if (config.containsKey("indexingWorkers")) {
                this.indexingWorkers = ((Number) config.get("indexingWorkers")).intValue();
            }
            if (config.containsKey("indexingBatchAccumulationSize")) {
                this.indexingBatchAccumulationSize = ((Number) config.get("indexingBatchAccumulationSize")).intValue();
            }
            if (config.containsKey("embeddingThreads")) {
                this.embeddingThreads = ((Number) config.get("embeddingThreads")).intValue();
            }

            // Restart configuration
            if (config.containsKey("restartEnabled")) {
                this.restartEnabled = (Boolean) config.get("restartEnabled");
            }
            if (config.containsKey("maxRestartAttempts")) {
                this.maxRestartAttempts = ((Number) config.get("maxRestartAttempts")).intValue();
            }
            if (config.containsKey("initialBackoffMs")) {
                this.initialBackoffMs = ((Number) config.get("initialBackoffMs")).intValue();
            }
            if (config.containsKey("backoffMultiplier")) {
                this.backoffMultiplier = ((Number) config.get("backoffMultiplier")).doubleValue();
            }
            if (config.containsKey("heapIncreaseFactor")) {
                this.heapIncreaseFactor = ((Number) config.get("heapIncreaseFactor")).doubleValue();
            }
            if (config.containsKey("systemRamSafetyMargin")) {
                this.systemRamSafetyMargin = ((Number) config.get("systemRamSafetyMargin")).doubleValue();
            }

            // Stall/deadlock detection config
            if (config.containsKey("restartOnStall")) {
                this.restartOnStall = (Boolean) config.get("restartOnStall");
            }
            if (config.containsKey("restartOnTimeout")) {
                this.restartOnTimeout = (Boolean) config.get("restartOnTimeout");
            }
            if (config.containsKey("stallDetectionThresholdSeconds")) {
                this.stallDetectionThresholdSeconds = ((Number) config.get("stallDetectionThresholdSeconds")).intValue();
            }
            if (config.containsKey("progressStallWarningSeconds")) {
                this.progressStallWarningSeconds = ((Number) config.get("progressStallWarningSeconds")).intValue();
            }

            // GPU memory monitoring config
            if (config.containsKey("gpuMemoryThresholdPercent")) {
                this.gpuMemoryThresholdPercent = ((Number) config.get("gpuMemoryThresholdPercent")).intValue();
            }
            if (config.containsKey("gpuMemoryCriticalPercent")) {
                this.gpuMemoryCriticalPercent = ((Number) config.get("gpuMemoryCriticalPercent")).intValue();
            }
            if (config.containsKey("gpuMemoryKillThresholdPercent")) {
                this.gpuMemoryKillThresholdPercent = ((Number) config.get("gpuMemoryKillThresholdPercent")).intValue();
            }

            // Off-heap memory monitoring config
            if (config.containsKey("offHeapThresholdPercent")) {
                this.offHeapThresholdPercent = ((Number) config.get("offHeapThresholdPercent")).intValue();
            }
            if (config.containsKey("offHeapCriticalPercent")) {
                this.offHeapCriticalPercent = ((Number) config.get("offHeapCriticalPercent")).intValue();
            }
            if (config.containsKey("offHeapKillThresholdPercent")) {
                this.offHeapKillThresholdPercent = ((Number) config.get("offHeapKillThresholdPercent")).intValue();
            }

            // Native executable config
            if (config.containsKey("nativeExecutableMode")) {
                Object v = config.get("nativeExecutableMode");
                this.nativeExecutableMode = v != null ? v.toString() : "auto";
            }
            if (config.containsKey("nativeExecutablePath")) {
                Object v = config.get("nativeExecutablePath");
                this.nativeExecutablePath = normalizeOptionalString(v != null ? v.toString() : null);
            }
            if (config.containsKey("ingestExecutablePath")) {
                Object v = config.get("ingestExecutablePath");
                this.ingestExecutablePath = normalizeOptionalString(v != null ? v.toString() : null);
            }
            if (config.containsKey("vectorPopulationExecutablePath")) {
                Object v = config.get("vectorPopulationExecutablePath");
                this.vectorPopulationExecutablePath = normalizeOptionalString(v != null ? v.toString() : null);
            }
            if (config.containsKey("embeddingExecutablePath")) {
                Object v = config.get("embeddingExecutablePath");
                this.embeddingExecutablePath = normalizeOptionalString(v != null ? v.toString() : null);
            }
            if (config.containsKey("modelInitExecutablePath")) {
                Object v = config.get("modelInitExecutablePath");
                this.modelInitExecutablePath = normalizeOptionalString(v != null ? v.toString() : null);
            }
            if (config.containsKey("subprocessTypeFlag")) {
                Object v = config.get("subprocessTypeFlag");
                this.subprocessTypeFlag = v != null && !v.toString().isBlank() ? v.toString() : "--subprocess=";
            }

            log.info("Loaded persisted subprocess config: enabled={}, heapSize={}, timeout={}min, " +
                    "queue={}, indexWorkers={}, embeddingThreads={}, restartEnabled={}, maxRestartAttempts={}, " +
                    "restartOnStall={}, stallThreshold={}s, nativeMode={}",
                    enabled, heapSize, timeoutMinutes, queueCapacity, indexingWorkers, embeddingThreads,
                    restartEnabled, maxRestartAttempts, restartOnStall, stallDetectionThresholdSeconds,
                    nativeExecutableMode);

        } catch (IOException e) {
            log.error("Failed to load persisted subprocess config from {}: {}",
                    configFilePath, e.getMessage(), e);
        }
    }

    /**
     * Persist current configuration to file.
     */
    private void persistConfig() {
        try {
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }

            Map<String, Object> config = new ConcurrentHashMap<>();
            config.put("enabled", enabled);
            config.put("javaPath", javaPath);
            config.put("heapSize", heapSize);
            config.put("offHeapMaxBytes", offHeapMaxBytes);
            config.put("offHeapMultiplier", offHeapMultiplier);
            config.put("timeoutMinutes", timeoutMinutes);
            config.put("vlmHeapSize", vlmHeapSize);
            config.put("vlmOffHeapMultiplier", vlmOffHeapMultiplier);
            config.put("vlmTimeoutMinutes", vlmTimeoutMinutes);
            config.put("vlmCudaPinnedHostLimitMb", vlmCudaPinnedHostLimitMb);
            config.put("heartbeatIntervalSeconds", heartbeatIntervalSeconds);
            config.put("staleThresholdSeconds", staleThresholdSeconds);

            // Pipeline settings (embedding batch sizes stored separately in
            // batch-size-config.json)
            config.put("queueCapacity", queueCapacity);
            config.put("parallelIndexing", parallelIndexing);
            config.put("indexingWorkers", indexingWorkers);
            config.put("indexingBatchAccumulationSize", indexingBatchAccumulationSize);
            config.put("embeddingThreads", embeddingThreads);

            // Restart configuration
            config.put("restartEnabled", restartEnabled);
            config.put("maxRestartAttempts", maxRestartAttempts);
            config.put("initialBackoffMs", initialBackoffMs);
            config.put("backoffMultiplier", backoffMultiplier);
            config.put("heapIncreaseFactor", heapIncreaseFactor);
            config.put("systemRamSafetyMargin", systemRamSafetyMargin);

            // Stall/deadlock detection config
            config.put("restartOnStall", restartOnStall);
            config.put("restartOnTimeout", restartOnTimeout);
            config.put("stallDetectionThresholdSeconds", stallDetectionThresholdSeconds);
            config.put("progressStallWarningSeconds", progressStallWarningSeconds);

            // GPU memory monitoring config
            config.put("gpuMemoryThresholdPercent", gpuMemoryThresholdPercent);
            config.put("gpuMemoryCriticalPercent", gpuMemoryCriticalPercent);
            config.put("gpuMemoryKillThresholdPercent", gpuMemoryKillThresholdPercent);

            // Off-heap memory monitoring config
            config.put("offHeapThresholdPercent", offHeapThresholdPercent);
            config.put("offHeapCriticalPercent", offHeapCriticalPercent);
            config.put("offHeapKillThresholdPercent", offHeapKillThresholdPercent);

            // Native executable config
            config.put("nativeExecutableMode", nativeExecutableMode);
            config.put("nativeExecutablePath", nativeExecutablePath);
            config.put("ingestExecutablePath", ingestExecutablePath);
            config.put("vectorPopulationExecutablePath", vectorPopulationExecutablePath);
            config.put("embeddingExecutablePath", embeddingExecutablePath);
            config.put("modelInitExecutablePath", modelInitExecutablePath);
            config.put("subprocessTypeFlag", subprocessTypeFlag);

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configFilePath, json);
            log.info("Persisted subprocess config to {}", configFilePath);

        } catch (IOException e) {
            log.error("Failed to persist subprocess config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    // ============= Getters =============

    public boolean isEnabled() {
        log.debug("SubprocessConfigService.isEnabled() called, returning: {}", enabled);
        return enabled;
    }

    public String getJavaPath() {
        return javaPath;
    }

    public String getHeapSize() {
        return heapSize;
    }

    public String getOffHeapMaxBytes() {
        return offHeapMaxBytes;
    }

    public int getOffHeapMultiplier() {
        return offHeapMultiplier;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    // VLM subprocess getters
    public String getVlmHeapSize() {
        return vlmHeapSize;
    }

    public int getVlmOffHeapMultiplier() {
        return vlmOffHeapMultiplier;
    }

    public int getVlmTimeoutMinutes() {
        return vlmTimeoutMinutes;
    }

    public int getVlmCudaPinnedHostLimitMb() {
        return vlmCudaPinnedHostLimitMb;
    }

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public int getStaleThresholdSeconds() {
        return staleThresholdSeconds;
    }

    // Pipeline settings getters (embedding batch sizes come from
    // AnseriniEmbeddingProperties)
    public int getQueueCapacity() {
        return queueCapacity;
    }

    public boolean isParallelIndexing() {
        return parallelIndexing;
    }

    public int getIndexingWorkers() {
        return indexingWorkers;
    }

    public int getIndexingBatchAccumulationSize() {
        return indexingBatchAccumulationSize;
    }

    public int getEmbeddingThreads() {
        return embeddingThreads;
    }

    // Restart configuration getters
    public boolean isRestartEnabled() {
        return restartEnabled;
    }

    public int getMaxRestartAttempts() {
        return maxRestartAttempts;
    }

    public int getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public double getHeapIncreaseFactor() {
        return heapIncreaseFactor;
    }

    public double getSystemRamSafetyMargin() {
        return systemRamSafetyMargin;
    }

    // Stall/deadlock detection getters
    public boolean isRestartOnStall() {
        return restartOnStall;
    }

    public boolean isRestartOnTimeout() {
        return restartOnTimeout;
    }

    public int getStallDetectionThresholdSeconds() {
        return stallDetectionThresholdSeconds;
    }

    public int getProgressStallWarningSeconds() {
        return progressStallWarningSeconds;
    }

    // GPU memory monitoring getters
    public int getGpuMemoryThresholdPercent() {
        return gpuMemoryThresholdPercent;
    }

    public int getGpuMemoryCriticalPercent() {
        return gpuMemoryCriticalPercent;
    }

    public int getGpuMemoryKillThresholdPercent() {
        return gpuMemoryKillThresholdPercent;
    }

    // Off-heap memory monitoring getters
    public int getOffHeapThresholdPercent() {
        return offHeapThresholdPercent;
    }

    public int getOffHeapCriticalPercent() {
        return offHeapCriticalPercent;
    }

    public int getOffHeapKillThresholdPercent() {
        return offHeapKillThresholdPercent;
    }

    // Native executable configuration getters
    public String getNativeExecutableMode() {
        return nativeExecutableMode;
    }

    public String getNativeExecutablePath() {
        return nativeExecutablePath;
    }

    public String getIngestExecutablePath() {
        return ingestExecutablePath;
    }

    public String getVectorPopulationExecutablePath() {
        return vectorPopulationExecutablePath;
    }

    public String getEmbeddingExecutablePath() {
        return embeddingExecutablePath;
    }

    public String getModelInitExecutablePath() {
        return modelInitExecutablePath;
    }

    public String getSubprocessTypeFlag() {
        return subprocessTypeFlag;
    }

    /**
     * Determine if native executable mode should be used.
     * When mode is "auto", detects based on runtime context (native image vs JVM).
     */
    public boolean shouldUseNativeExecutableMode() {
        if ("native".equalsIgnoreCase(nativeExecutableMode)) {
            return true;
        }
        if ("jvm".equalsIgnoreCase(nativeExecutableMode)) {
            return false;
        }
        // Auto mode: detect based on runtime context
        NativeImageInfo.SubprocessLaunchMode recommended = NativeImageInfo.getRecommendedLaunchMode();
        return recommended == NativeImageInfo.SubprocessLaunchMode.NATIVE_EXECUTABLE;
    }

    /**
     * Get the executable path for a specific subprocess type.
     * Falls back to the unified native executable path if no specific path is configured.
     *
     * @param subprocessType One of: "ingest", "vector-population", "embedding", "model-init"
     * @return The executable path, or null if not configured
     */
    public String getExecutablePathForType(String subprocessType) {
        String specificPath = switch (subprocessType.toLowerCase()) {
            case "ingest" -> ingestExecutablePath;
            case "vector-population" -> vectorPopulationExecutablePath;
            case "embedding" -> embeddingExecutablePath;
            case "model-init" -> modelInitExecutablePath;
            default -> null;
        };

        // Use specific path if configured and valid
        if (specificPath != null && !specificPath.isBlank()) {
            Path path = Paths.get(specificPath);
            if (Files.exists(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().toString();
            }
            log.warn("Specific subprocess path for {} does not exist or is not executable: {}", subprocessType, specificPath);
        }

        // Fall back to unified executable path
        if (nativeExecutablePath != null && !nativeExecutablePath.isBlank()) {
            Path path = Paths.get(nativeExecutablePath);
            if (Files.exists(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().toString();
            }
        }

        // Try to detect from current native image
        if (NativeImageInfo.isRunningInNativeImage()) {
            String execPath = NativeImageInfo.getExecutablePath();
            if (execPath != null) {
                return execPath;
            }
        }

        return null;
    }

    /**
     * Check if the unified executable should be used (vs separate executables).
     *
     * @param subprocessType One of: "ingest", "vector-population", "embedding", "model-init"
     * @return true if should use unified executable with --subprocess flag
     */
    public boolean useUnifiedExecutable(String subprocessType) {
        String specificPath = switch (subprocessType.toLowerCase()) {
            case "ingest" -> ingestExecutablePath;
            case "vector-population" -> vectorPopulationExecutablePath;
            case "embedding" -> embeddingExecutablePath;
            case "model-init" -> modelInitExecutablePath;
            default -> null;
        };
        return specificPath == null || specificPath.isBlank();
    }

    /**
     * Get the actual callback URL that subprocesses will use.
     */
    public String getCallbackBaseUrl() {
        return serverPortService != null
                ? serverPortService.getBaseUrl()
                : "http://localhost:8080";
    }

    /**
     * Get the actual server port.
     */
    public int getActualServerPort() {
        return serverPortService != null
                ? serverPortService.getActualPort()
                : 8080;
    }

    // ============= Setters with Persistence =============

    public void setEnabled(boolean enabled) {
        boolean oldValue = this.enabled;
        this.enabled = enabled;
        persistConfig();
        log.info("=== SUBPROCESS CONFIG CHANGED === enabled: {} -> {} (persisted to {})",
                oldValue, enabled, configFilePath);
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath;
        persistConfig();
        log.info("Updated subprocess javaPath: {}", javaPath);
    }

    public void setHeapSize(String heapSize) {
        this.heapSize = heapSize;
        persistConfig();
        log.info("Updated subprocess heapSize: {}", heapSize);
    }

    public void setOffHeapMaxBytes(String offHeapMaxBytes) {
        this.offHeapMaxBytes = normalizeOptionalString(offHeapMaxBytes);
        persistConfig();
        log.info("Updated subprocess offHeapMaxBytes: {}", this.offHeapMaxBytes);
    }

    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
        persistConfig();
        log.info("Updated subprocess timeoutMinutes: {}", timeoutMinutes);
    }

    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        persistConfig();
        log.info("Updated subprocess heartbeatIntervalSeconds: {}", heartbeatIntervalSeconds);
    }

    public void setStaleThresholdSeconds(int staleThresholdSeconds) {
        this.staleThresholdSeconds = staleThresholdSeconds;
        persistConfig();
        log.info("Updated subprocess staleThresholdSeconds: {}", staleThresholdSeconds);
    }

    // Pipeline settings setters (embedding batch sizes managed via
    // BatchSizeConfigService)
    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
        persistConfig();
        log.info("Updated subprocess queueCapacity: {}", queueCapacity);
    }

    public void setParallelIndexing(boolean parallelIndexing) {
        this.parallelIndexing = parallelIndexing;
        persistConfig();
        log.info("Updated subprocess parallelIndexing: {}", parallelIndexing);
    }

    public void setIndexingWorkers(int indexingWorkers) {
        this.indexingWorkers = indexingWorkers;
        persistConfig();
        log.info("Updated subprocess indexingWorkers: {}", indexingWorkers);
    }

    public void setIndexingBatchAccumulationSize(int indexingBatchAccumulationSize) {
        this.indexingBatchAccumulationSize = indexingBatchAccumulationSize;
        persistConfig();
        log.info("Updated subprocess indexingBatchAccumulationSize: {}", indexingBatchAccumulationSize);
    }

    public void setEmbeddingThreads(int embeddingThreads) {
        this.embeddingThreads = embeddingThreads;
        persistConfig();
        log.info("Updated subprocess embeddingThreads: {}", embeddingThreads);
    }

    // Restart configuration setters
    public void setRestartEnabled(boolean restartEnabled) {
        this.restartEnabled = restartEnabled;
        persistConfig();
        log.info("Updated subprocess restartEnabled: {}", restartEnabled);
    }

    public void setMaxRestartAttempts(int maxRestartAttempts) {
        this.maxRestartAttempts = maxRestartAttempts;
        persistConfig();
        log.info("Updated subprocess maxRestartAttempts: {}", maxRestartAttempts);
    }

    public void setInitialBackoffMs(int initialBackoffMs) {
        this.initialBackoffMs = initialBackoffMs;
        persistConfig();
        log.info("Updated subprocess initialBackoffMs: {}", initialBackoffMs);
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
        persistConfig();
        log.info("Updated subprocess backoffMultiplier: {}", backoffMultiplier);
    }

    public void setHeapIncreaseFactor(double heapIncreaseFactor) {
        this.heapIncreaseFactor = heapIncreaseFactor;
        persistConfig();
        log.info("Updated subprocess heapIncreaseFactor: {}", heapIncreaseFactor);
    }

    public void setSystemRamSafetyMargin(double systemRamSafetyMargin) {
        this.systemRamSafetyMargin = systemRamSafetyMargin;
        persistConfig();
        log.info("Updated subprocess systemRamSafetyMargin: {}", systemRamSafetyMargin);
    }

    // Stall/deadlock detection setters
    public void setRestartOnStall(boolean restartOnStall) {
        this.restartOnStall = restartOnStall;
        persistConfig();
        log.info("Updated subprocess restartOnStall: {}", restartOnStall);
    }

    public void setRestartOnTimeout(boolean restartOnTimeout) {
        this.restartOnTimeout = restartOnTimeout;
        persistConfig();
        log.info("Updated subprocess restartOnTimeout: {}", restartOnTimeout);
    }

    public void setStallDetectionThresholdSeconds(int stallDetectionThresholdSeconds) {
        this.stallDetectionThresholdSeconds = stallDetectionThresholdSeconds;
        persistConfig();
        log.info("Updated subprocess stallDetectionThresholdSeconds: {}", stallDetectionThresholdSeconds);
    }

    public void setProgressStallWarningSeconds(int progressStallWarningSeconds) {
        this.progressStallWarningSeconds = progressStallWarningSeconds;
        persistConfig();
        log.info("Updated subprocess progressStallWarningSeconds: {}", progressStallWarningSeconds);
    }

    // Native executable configuration setters
    public void setNativeExecutableMode(String nativeExecutableMode) {
        this.nativeExecutableMode = nativeExecutableMode != null && !nativeExecutableMode.isBlank()
                ? nativeExecutableMode : "auto";
        persistConfig();
        log.info("Updated subprocess nativeExecutableMode: {}", this.nativeExecutableMode);
    }

    public void setNativeExecutablePath(String nativeExecutablePath) {
        this.nativeExecutablePath = normalizeOptionalString(nativeExecutablePath);
        persistConfig();
        log.info("Updated subprocess nativeExecutablePath: {}", this.nativeExecutablePath);
    }

    public void setIngestExecutablePath(String ingestExecutablePath) {
        this.ingestExecutablePath = normalizeOptionalString(ingestExecutablePath);
        persistConfig();
        log.info("Updated subprocess ingestExecutablePath: {}", this.ingestExecutablePath);
    }

    public void setVectorPopulationExecutablePath(String vectorPopulationExecutablePath) {
        this.vectorPopulationExecutablePath = normalizeOptionalString(vectorPopulationExecutablePath);
        persistConfig();
        log.info("Updated subprocess vectorPopulationExecutablePath: {}", this.vectorPopulationExecutablePath);
    }

    public void setEmbeddingExecutablePath(String embeddingExecutablePath) {
        this.embeddingExecutablePath = normalizeOptionalString(embeddingExecutablePath);
        persistConfig();
        log.info("Updated subprocess embeddingExecutablePath: {}", this.embeddingExecutablePath);
    }

    public void setModelInitExecutablePath(String modelInitExecutablePath) {
        this.modelInitExecutablePath = normalizeOptionalString(modelInitExecutablePath);
        persistConfig();
        log.info("Updated subprocess modelInitExecutablePath: {}", this.modelInitExecutablePath);
    }

    public void setSubprocessTypeFlag(String subprocessTypeFlag) {
        this.subprocessTypeFlag = subprocessTypeFlag != null && !subprocessTypeFlag.isBlank()
                ? subprocessTypeFlag : "--subprocess=";
        persistConfig();
        log.info("Updated subprocess subprocessTypeFlag: {}", this.subprocessTypeFlag);
    }

    /**
     * Update multiple configuration values at once.
     */
    public void updateConfiguration(SubprocessConfigUpdate update) {
        if (update.enabled() != null) {
            this.enabled = update.enabled();
        }
        if (update.javaPath() != null) {
            this.javaPath = update.javaPath();
        }
        if (update.heapSize() != null) {
            this.heapSize = update.heapSize();
        }
        if (update.offHeapMaxBytes() != null) {
            this.offHeapMaxBytes = normalizeOptionalString(update.offHeapMaxBytes());
        }
        if (update.offHeapMultiplier() != null) {
            this.offHeapMultiplier = update.offHeapMultiplier();
        }
        if (update.timeoutMinutes() != null) {
            this.timeoutMinutes = update.timeoutMinutes();
        }
        if (update.vlmHeapSize() != null) {
            this.vlmHeapSize = update.vlmHeapSize();
        }
        if (update.vlmOffHeapMultiplier() != null) {
            this.vlmOffHeapMultiplier = update.vlmOffHeapMultiplier();
        }
        if (update.vlmTimeoutMinutes() != null) {
            this.vlmTimeoutMinutes = update.vlmTimeoutMinutes();
        }
        if (update.vlmCudaPinnedHostLimitMb() != null) {
            this.vlmCudaPinnedHostLimitMb = update.vlmCudaPinnedHostLimitMb();
        }
        if (update.heartbeatIntervalSeconds() != null) {
            this.heartbeatIntervalSeconds = update.heartbeatIntervalSeconds();
        }
        if (update.staleThresholdSeconds() != null) {
            this.staleThresholdSeconds = update.staleThresholdSeconds();
        }
        // Pipeline settings (embedding batch sizes managed via BatchSizeConfigService)
        if (update.queueCapacity() != null) {
            this.queueCapacity = update.queueCapacity();
        }
        if (update.parallelIndexing() != null) {
            this.parallelIndexing = update.parallelIndexing();
        }
        if (update.indexingWorkers() != null) {
            this.indexingWorkers = update.indexingWorkers();
        }
        if (update.indexingBatchAccumulationSize() != null) {
            this.indexingBatchAccumulationSize = update.indexingBatchAccumulationSize();
        }
        if (update.embeddingThreads() != null) {
            this.embeddingThreads = update.embeddingThreads();
        }
        // Restart configuration
        if (update.restartEnabled() != null) {
            this.restartEnabled = update.restartEnabled();
        }
        if (update.maxRestartAttempts() != null) {
            this.maxRestartAttempts = update.maxRestartAttempts();
        }
        if (update.initialBackoffMs() != null) {
            this.initialBackoffMs = update.initialBackoffMs();
        }
        if (update.backoffMultiplier() != null) {
            this.backoffMultiplier = update.backoffMultiplier();
        }
        if (update.heapIncreaseFactor() != null) {
            this.heapIncreaseFactor = update.heapIncreaseFactor();
        }
        if (update.systemRamSafetyMargin() != null) {
            this.systemRamSafetyMargin = update.systemRamSafetyMargin();
        }
        // Stall/deadlock detection config
        if (update.restartOnStall() != null) {
            this.restartOnStall = update.restartOnStall();
        }
        if (update.restartOnTimeout() != null) {
            this.restartOnTimeout = update.restartOnTimeout();
        }
        if (update.stallDetectionThresholdSeconds() != null) {
            this.stallDetectionThresholdSeconds = update.stallDetectionThresholdSeconds();
        }
        if (update.progressStallWarningSeconds() != null) {
            this.progressStallWarningSeconds = update.progressStallWarningSeconds();
        }
        // Native executable config
        if (update.nativeExecutableMode() != null) {
            this.nativeExecutableMode = update.nativeExecutableMode().isBlank() ? "auto" : update.nativeExecutableMode();
        }
        if (update.nativeExecutablePath() != null) {
            this.nativeExecutablePath = normalizeOptionalString(update.nativeExecutablePath());
        }
        if (update.ingestExecutablePath() != null) {
            this.ingestExecutablePath = normalizeOptionalString(update.ingestExecutablePath());
        }
        if (update.vectorPopulationExecutablePath() != null) {
            this.vectorPopulationExecutablePath = normalizeOptionalString(update.vectorPopulationExecutablePath());
        }
        if (update.embeddingExecutablePath() != null) {
            this.embeddingExecutablePath = normalizeOptionalString(update.embeddingExecutablePath());
        }
        if (update.modelInitExecutablePath() != null) {
            this.modelInitExecutablePath = normalizeOptionalString(update.modelInitExecutablePath());
        }
        if (update.subprocessTypeFlag() != null) {
            this.subprocessTypeFlag = update.subprocessTypeFlag().isBlank() ? "--subprocess=" : update.subprocessTypeFlag();
        }
        // Memory watchdog thresholds
        if (update.offHeapThresholdPercent() != null) {
            this.offHeapThresholdPercent = update.offHeapThresholdPercent();
        }
        if (update.offHeapCriticalPercent() != null) {
            this.offHeapCriticalPercent = update.offHeapCriticalPercent();
        }
        if (update.offHeapKillThresholdPercent() != null) {
            this.offHeapKillThresholdPercent = update.offHeapKillThresholdPercent();
        }
        if (update.gpuMemoryThresholdPercent() != null) {
            this.gpuMemoryThresholdPercent = update.gpuMemoryThresholdPercent();
        }
        if (update.gpuMemoryCriticalPercent() != null) {
            this.gpuMemoryCriticalPercent = update.gpuMemoryCriticalPercent();
        }
        if (update.gpuMemoryKillThresholdPercent() != null) {
            this.gpuMemoryKillThresholdPercent = update.gpuMemoryKillThresholdPercent();
        }
        persistConfig();
        log.info("Updated subprocess configuration: {}", update);
    }

    /**
     * Reset configuration to defaults.
     */
    public void resetToDefaults() {
        this.enabled = defaultEnabled;
        this.javaPath = defaultJavaPath;
        this.heapSize = defaultHeapSize;
        this.offHeapMaxBytes = normalizeOptionalString(defaultOffHeapMaxBytes);
        this.offHeapMultiplier = defaultOffHeapMultiplier;
        this.timeoutMinutes = defaultTimeoutMinutes;
        this.vlmHeapSize = defaultVlmHeapSize;
        this.vlmOffHeapMultiplier = defaultVlmOffHeapMultiplier;
        this.vlmTimeoutMinutes = defaultVlmTimeoutMinutes;
        this.vlmCudaPinnedHostLimitMb = defaultVlmCudaPinnedHostLimitMb;
        this.heartbeatIntervalSeconds = defaultHeartbeatIntervalSeconds;
        this.staleThresholdSeconds = defaultStaleThresholdSeconds;
        // Pipeline settings (embedding batch sizes managed via BatchSizeConfigService)
        this.queueCapacity = defaultQueueCapacity;
        this.parallelIndexing = defaultParallelIndexing;
        this.indexingWorkers = defaultIndexingWorkers;
        this.indexingBatchAccumulationSize = defaultIndexingBatchAccumulationSize;
        this.embeddingThreads = defaultEmbeddingThreads;
        // Restart configuration
        this.restartEnabled = defaultRestartEnabled;
        this.maxRestartAttempts = defaultMaxRestartAttempts;
        this.initialBackoffMs = defaultInitialBackoffMs;
        this.backoffMultiplier = defaultBackoffMultiplier;
        this.heapIncreaseFactor = defaultHeapIncreaseFactor;
        this.systemRamSafetyMargin = defaultSystemRamSafetyMargin;
        // Stall/deadlock detection
        this.restartOnStall = defaultRestartOnStall;
        this.restartOnTimeout = defaultRestartOnTimeout;
        this.stallDetectionThresholdSeconds = defaultStallDetectionThresholdSeconds;
        this.progressStallWarningSeconds = defaultProgressStallWarningSeconds;
        // Native executable config
        this.nativeExecutableMode = normalizeOptionalString(defaultNativeExecutableMode);
        if (this.nativeExecutableMode.isEmpty()) {
            this.nativeExecutableMode = "auto";
        }
        this.nativeExecutablePath = normalizeOptionalString(defaultNativeExecutablePath);
        this.ingestExecutablePath = normalizeOptionalString(defaultIngestExecutablePath);
        this.vectorPopulationExecutablePath = normalizeOptionalString(defaultVectorPopulationExecutablePath);
        this.embeddingExecutablePath = normalizeOptionalString(defaultEmbeddingExecutablePath);
        this.modelInitExecutablePath = normalizeOptionalString(defaultModelInitExecutablePath);
        this.subprocessTypeFlag = defaultSubprocessTypeFlag != null && !defaultSubprocessTypeFlag.isBlank()
                ? defaultSubprocessTypeFlag : "--subprocess=";
        // Memory watchdog thresholds
        this.offHeapThresholdPercent = defaultOffHeapThresholdPercent;
        this.offHeapCriticalPercent = defaultOffHeapCriticalPercent;
        this.offHeapKillThresholdPercent = defaultOffHeapKillThresholdPercent;
        this.gpuMemoryThresholdPercent = defaultGpuMemoryThresholdPercent;
        this.gpuMemoryCriticalPercent = defaultGpuMemoryCriticalPercent;
        this.gpuMemoryKillThresholdPercent = defaultGpuMemoryKillThresholdPercent;
        persistConfig();
        log.info("Reset subprocess configuration to defaults");
    }

    /**
     * Get the current configuration as a response object.
     */
    public SubprocessConfigResponse getConfiguration() {
        Runtime runtime = Runtime.getRuntime();
        long availableMemoryMb = runtime.maxMemory() / (1024 * 1024);

        return new SubprocessConfigResponse(
                enabled,
                javaPath,
                heapSize,
                offHeapMaxBytes,
                offHeapMultiplier,
                timeoutMinutes,
                vlmHeapSize,
                vlmOffHeapMultiplier,
                vlmTimeoutMinutes,
                vlmCudaPinnedHostLimitMb,
                heartbeatIntervalSeconds,
                staleThresholdSeconds,
                queueCapacity,
                parallelIndexing,
                indexingWorkers,
                indexingBatchAccumulationSize,
                embeddingThreads,
                // Restart configuration
                restartEnabled,
                maxRestartAttempts,
                initialBackoffMs,
                backoffMultiplier,
                heapIncreaseFactor,
                systemRamSafetyMargin,
                // Stall/deadlock detection
                restartOnStall,
                restartOnTimeout,
                stallDetectionThresholdSeconds,
                progressStallWarningSeconds,
                // Native executable config
                nativeExecutableMode,
                nativeExecutablePath,
                ingestExecutablePath,
                vectorPopulationExecutablePath,
                embeddingExecutablePath,
                modelInitExecutablePath,
                subprocessTypeFlag,
                shouldUseNativeExecutableMode(),
                NativeImageInfo.isRunningInNativeImage(),
                NativeImageInfo.hasClasspath(),
                // Memory watchdog thresholds
                offHeapThresholdPercent,
                offHeapCriticalPercent,
                offHeapKillThresholdPercent,
                gpuMemoryThresholdPercent,
                gpuMemoryCriticalPercent,
                getGpuMemoryKillThresholdPercent(),
                // Computed/system info
                getCallbackBaseUrl(),
                getActualServerPort(),
                availableMemoryMb,
                runtime.availableProcessors(),
                System.getProperty("os.name"),
                System.getProperty("java.version"));
    }

    /**
     * Get list of heap size options dynamically based on system RAM.
     * Returns options up to 80% of system RAM, capped at 1TB maximum.
     */
    public List<String> getHeapSizeOptions() {
        long systemRamBytes = getSystemRamBytes();
        long maxHeapBytes = Math.min(
                (long) (systemRamBytes * 0.80),  // 80% of system RAM
                1024L * 1024L * 1024L * 1024L    // 1TB absolute cap
        );
        long maxHeapGb = maxHeapBytes / (1024L * 1024L * 1024L);

        java.util.List<String> options = new java.util.ArrayList<>();
        // Always include small options
        options.add("1g");
        options.add("2g");
        options.add("4g");
        options.add("6g");
        options.add("8g");

        // Add larger options based on available RAM
        if (maxHeapGb >= 12) options.add("12g");
        if (maxHeapGb >= 16) options.add("16g");
        if (maxHeapGb >= 24) options.add("24g");
        if (maxHeapGb >= 32) options.add("32g");
        if (maxHeapGb >= 48) options.add("48g");
        if (maxHeapGb >= 64) options.add("64g");
        if (maxHeapGb >= 96) options.add("96g");
        if (maxHeapGb >= 128) options.add("128g");
        if (maxHeapGb >= 192) options.add("192g");
        if (maxHeapGb >= 256) options.add("256g");
        if (maxHeapGb >= 384) options.add("384g");
        if (maxHeapGb >= 512) options.add("512g");
        if (maxHeapGb >= 768) options.add("768g");
        if (maxHeapGb >= 1024) options.add("1024g");

        log.debug("Generated heap size options for {}GB system RAM (max {}GB @ 80%): {}",
                systemRamBytes / (1024L * 1024L * 1024L), maxHeapGb, options);
        return options;
    }

    /**
     * Get maximum allowed heap size (80% of system RAM, capped at 1TB).
     */
    public String getMaxHeapSize() {
        long systemRamBytes = getSystemRamBytes();
        long maxHeapBytes = Math.min(
                (long) (systemRamBytes * 0.80),
                1024L * 1024L * 1024L * 1024L  // 1TB cap
        );
        long maxHeapGb = maxHeapBytes / (1024L * 1024L * 1024L);
        return maxHeapGb + "g";
    }

    /**
     * Get total system RAM in bytes.
     */
    private long getSystemRamBytes() {
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                return sunOsBean.getTotalMemorySize();
            }
        } catch (Exception e) {
            log.warn("Failed to get system RAM, using fallback: {}", e.getMessage());
        }
        // Fallback: estimate based on JVM max memory (assumes ~25% allocated to JVM)
        return Runtime.getRuntime().maxMemory() * 4;
    }

    // ============= DTOs =============

    /**
     * Configuration update request.
     * Note: Embedding batch sizes are managed via BatchSizeConfigService (benchmark
     * results).
     */
    public record SubprocessConfigUpdate(
            Boolean enabled,
            String javaPath,
            String heapSize,
            String offHeapMaxBytes,
            Integer offHeapMultiplier,
            Integer timeoutMinutes,
            // VLM subprocess configuration
            String vlmHeapSize,
            Integer vlmOffHeapMultiplier,
            Integer vlmTimeoutMinutes,
            Integer vlmCudaPinnedHostLimitMb,
            Integer heartbeatIntervalSeconds,
            Integer staleThresholdSeconds,
            // Pipeline settings (embedding batch sizes managed via BatchSizeConfigService)
            Integer queueCapacity,
            Boolean parallelIndexing,
            Integer indexingWorkers,
            Integer indexingBatchAccumulationSize,
            Integer embeddingThreads,
            // Restart configuration
            Boolean restartEnabled,
            Integer maxRestartAttempts,
            Integer initialBackoffMs,
            Double backoffMultiplier,
            Double heapIncreaseFactor,
            Double systemRamSafetyMargin,
            // Stall/deadlock detection
            Boolean restartOnStall,
            Boolean restartOnTimeout,
            Integer stallDetectionThresholdSeconds,
            Integer progressStallWarningSeconds,
            // Native executable configuration
            String nativeExecutableMode,
            String nativeExecutablePath,
            String ingestExecutablePath,
            String vectorPopulationExecutablePath,
            String embeddingExecutablePath,
            String modelInitExecutablePath,
            String subprocessTypeFlag,
            // Memory watchdog thresholds
            Integer offHeapThresholdPercent,
            Integer offHeapCriticalPercent,
            Integer offHeapKillThresholdPercent,
            Integer gpuMemoryThresholdPercent,
            Integer gpuMemoryCriticalPercent,
            Integer gpuMemoryKillThresholdPercent) {
    }

    /**
     * Configuration response.
     * Note: Embedding batch sizes come from AnseriniEmbeddingProperties (benchmark
     * results in batch-size-config.json).
     */
    public record SubprocessConfigResponse(
            boolean enabled,
            String javaPath,
            String heapSize,
            String offHeapMaxBytes,
            int offHeapMultiplier,
            int timeoutMinutes,
            // VLM subprocess configuration
            String vlmHeapSize,
            int vlmOffHeapMultiplier,
            int vlmTimeoutMinutes,
            int vlmCudaPinnedHostLimitMb,
            int heartbeatIntervalSeconds,
            int staleThresholdSeconds,
            // Pipeline settings (embedding batch sizes managed via BatchSizeConfigService)
            int queueCapacity,
            boolean parallelIndexing,
            int indexingWorkers,
            int indexingBatchAccumulationSize,
            int embeddingThreads,
            // Restart configuration
            boolean restartEnabled,
            int maxRestartAttempts,
            int initialBackoffMs,
            double backoffMultiplier,
            double heapIncreaseFactor,
            double systemRamSafetyMargin,
            // Stall/deadlock detection
            boolean restartOnStall,
            boolean restartOnTimeout,
            int stallDetectionThresholdSeconds,
            int progressStallWarningSeconds,
            // Native executable configuration
            String nativeExecutableMode,
            String nativeExecutablePath,
            String ingestExecutablePath,
            String vectorPopulationExecutablePath,
            String embeddingExecutablePath,
            String modelInitExecutablePath,
            String subprocessTypeFlag,
            boolean resolvedNativeMode,
            boolean runningInNativeImage,
            boolean hasClasspath,
            // Memory watchdog thresholds
            int offHeapThresholdPercent,
            int offHeapCriticalPercent,
            int offHeapKillThresholdPercent,
            int gpuMemoryThresholdPercent,
            int gpuMemoryCriticalPercent,
            int gpuMemoryKillThresholdPercent,
            // Computed/system info
            String callbackBaseUrl,
            int actualServerPort,
            long availableMemoryMb,
            int availableProcessors,
            String osName,
            String javaVersion) {
    }

    private static String normalizeOptionalString(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed;
    }
}
