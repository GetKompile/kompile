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
     * When empty, the subprocess launcher will auto-calculate this value as (heapSize * 2).
     */
    private volatile String offHeapMaxBytes;
    private volatile int timeoutMinutes;
    private volatile int heartbeatIntervalSeconds;
    private volatile int staleThresholdSeconds;

    // Pipeline configuration (non-embedding settings - embedding batch sizes come from AnseriniEmbeddingProperties)
    private volatile int queueCapacity;
    private volatile boolean parallelIndexing;
    private volatile int indexingWorkers;
    private volatile int indexingBatchAccumulationSize;
    private volatile int embeddingThreads;

    // Defaults from @Value annotations - DEFAULT TO FALSE for in-process mode
    // Subprocess mode can be enabled via UI (Developer Hub > Processing Settings)
    // or via API: POST /api/subprocess-config/enable
    // or by setting kompile.ingest.subprocess.enabled=true in application.properties
    @Value("${kompile.ingest.subprocess.enabled:false}")
    private boolean defaultEnabled;

    @Value("${kompile.ingest.subprocess.java-path:java}")
    private String defaultJavaPath;

    @Value("${kompile.ingest.subprocess.heap-size:4g}")
    private String defaultHeapSize;

    @Value("${kompile.ingest.subprocess.offheap-max-bytes:}")
    private String defaultOffHeapMaxBytes;

    @Value("${kompile.ingest.subprocess.timeout-minutes:60}")
    private int defaultTimeoutMinutes;

    @Value("${kompile.ingest.subprocess.heartbeat-interval-seconds:10}")
    private int defaultHeartbeatIntervalSeconds;

    @Value("${kompile.ingest.subprocess.stale-threshold-seconds:120}")
    private int defaultStaleThresholdSeconds;

    // Pipeline defaults (embedding batch sizes come from AnseriniEmbeddingProperties/benchmark config)
    @Value("${kompile.ingest.subprocess.queue-capacity:1000}")
    private int defaultQueueCapacity;

    @Value("${kompile.ingest.subprocess.parallel-indexing:true}")
    private boolean defaultParallelIndexing;

    @Value("${kompile.ingest.subprocess.indexing-workers:4}")
    private int defaultIndexingWorkers;

    @Value("${kompile.ingest.subprocess.indexing-batch-accumulation:8}")
    private int defaultIndexingBatchAccumulationSize;

    @Value("${kompile.ingest.subprocess.embedding-threads:1}")
    private int defaultEmbeddingThreads;

    @Autowired
    public SubprocessConfigService(
            @Autowired(required = false) ServerPortService serverPortService,
            @Value("${kompile.data.dir:#{systemProperties['user.home'] + '/.kompile'}}") String dataDir
    ) {
        this.serverPortService = serverPortService;
        this.objectMapper = new ObjectMapper();
        this.configFilePath = Paths.get(dataDir, "config", CONFIG_FILENAME);
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
        this.timeoutMinutes = defaultTimeoutMinutes;
        this.heartbeatIntervalSeconds = defaultHeartbeatIntervalSeconds;
        this.staleThresholdSeconds = defaultStaleThresholdSeconds;

        // Pipeline defaults (embedding batch sizes come from AnseriniEmbeddingProperties)
        this.queueCapacity = defaultQueueCapacity;
        this.parallelIndexing = defaultParallelIndexing;
        this.indexingWorkers = defaultIndexingWorkers;
        this.indexingBatchAccumulationSize = defaultIndexingBatchAccumulationSize;
        this.embeddingThreads = defaultEmbeddingThreads;

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
            if (config.containsKey("timeoutMinutes")) {
                this.timeoutMinutes = ((Number) config.get("timeoutMinutes")).intValue();
            }
            if (config.containsKey("heartbeatIntervalSeconds")) {
                this.heartbeatIntervalSeconds = ((Number) config.get("heartbeatIntervalSeconds")).intValue();
            }
            if (config.containsKey("staleThresholdSeconds")) {
                this.staleThresholdSeconds = ((Number) config.get("staleThresholdSeconds")).intValue();
            }

            // Pipeline settings (embedding batch sizes come from AnseriniEmbeddingProperties/benchmark config)
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

            log.info("Loaded persisted subprocess config: enabled={}, heapSize={}, timeout={}min, " +
                            "queue={}, indexWorkers={}, embeddingThreads={}",
                    enabled, heapSize, timeoutMinutes, queueCapacity, indexingWorkers, embeddingThreads);

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
            config.put("timeoutMinutes", timeoutMinutes);
            config.put("heartbeatIntervalSeconds", heartbeatIntervalSeconds);
            config.put("staleThresholdSeconds", staleThresholdSeconds);

            // Pipeline settings (embedding batch sizes stored separately in batch-size-config.json)
            config.put("queueCapacity", queueCapacity);
            config.put("parallelIndexing", parallelIndexing);
            config.put("indexingWorkers", indexingWorkers);
            config.put("indexingBatchAccumulationSize", indexingBatchAccumulationSize);
            config.put("embeddingThreads", embeddingThreads);

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

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public int getStaleThresholdSeconds() {
        return staleThresholdSeconds;
    }

    // Pipeline settings getters (embedding batch sizes come from AnseriniEmbeddingProperties)
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

    // Pipeline settings setters (embedding batch sizes managed via BatchSizeConfigService)
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
        if (update.timeoutMinutes() != null) {
            this.timeoutMinutes = update.timeoutMinutes();
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
        this.timeoutMinutes = defaultTimeoutMinutes;
        this.heartbeatIntervalSeconds = defaultHeartbeatIntervalSeconds;
        this.staleThresholdSeconds = defaultStaleThresholdSeconds;
        // Pipeline settings (embedding batch sizes managed via BatchSizeConfigService)
        this.queueCapacity = defaultQueueCapacity;
        this.parallelIndexing = defaultParallelIndexing;
        this.indexingWorkers = defaultIndexingWorkers;
        this.indexingBatchAccumulationSize = defaultIndexingBatchAccumulationSize;
        this.embeddingThreads = defaultEmbeddingThreads;
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
                timeoutMinutes,
                heartbeatIntervalSeconds,
                staleThresholdSeconds,
                queueCapacity,
                parallelIndexing,
                indexingWorkers,
                indexingBatchAccumulationSize,
                embeddingThreads,
                getCallbackBaseUrl(),
                getActualServerPort(),
                availableMemoryMb,
                runtime.availableProcessors(),
                System.getProperty("os.name"),
                System.getProperty("java.version")
        );
    }

    /**
     * Get list of common heap size options.
     */
    public List<String> getHeapSizeOptions() {
        return List.of("1g", "2g", "4g", "6g", "8g", "12g", "16g");
    }

    // ============= DTOs =============

    /**
     * Configuration update request.
     * Note: Embedding batch sizes are managed via BatchSizeConfigService (benchmark results).
     */
    public record SubprocessConfigUpdate(
            Boolean enabled,
            String javaPath,
            String heapSize,
            String offHeapMaxBytes,
            Integer timeoutMinutes,
            Integer heartbeatIntervalSeconds,
            Integer staleThresholdSeconds,
            // Pipeline settings (embedding batch sizes managed via BatchSizeConfigService)
            Integer queueCapacity,
            Boolean parallelIndexing,
            Integer indexingWorkers,
            Integer indexingBatchAccumulationSize,
            Integer embeddingThreads
    ) {}

    /**
     * Configuration response.
     * Note: Embedding batch sizes come from AnseriniEmbeddingProperties (benchmark results in batch-size-config.json).
     */
    public record SubprocessConfigResponse(
            boolean enabled,
            String javaPath,
            String heapSize,
            String offHeapMaxBytes,
            int timeoutMinutes,
            int heartbeatIntervalSeconds,
            int staleThresholdSeconds,
            // Pipeline settings (embedding batch sizes managed via BatchSizeConfigService)
            int queueCapacity,
            boolean parallelIndexing,
            int indexingWorkers,
            int indexingBatchAccumulationSize,
            int embeddingThreads,
            // Computed/system info
            String callbackBaseUrl,
            int actualServerPort,
            long availableMemoryMb,
            int availableProcessors,
            String osName,
            String javaVersion
    ) {}

    private static String normalizeOptionalString(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed;
    }
}
