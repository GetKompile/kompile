package ai.kompile.kvcache.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Data
@Component
@JsonIgnoreProperties({"configFilePath", "objectMapper"})
public class KVCacheProperties {

    private static final Logger log = LoggerFactory.getLogger(KVCacheProperties.class);
    private static final String CONFIG_FILENAME = "kvcache-config.json";

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Path configFilePath;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ObjectMapper objectMapper;

    private boolean enabled = false;
    private String defaultType = "paged";
    private int blockSize = 64;
    private int maxBatchSize = 8;
    private int maxSeqLen = 4096;
    private int numKvHeads = 32;
    private int headDim = 128;
    private String dataType = "FLOAT";
    private double poolSizeFactor = 1.2;
    private String evictionPolicy = "h2o";
    private int tokenBudget = 2048;
    private String quantFormat = "INT8";
    private int turboQuantBits = 3;
    private boolean tieredEnabled = false;
    private double gpuPressureThreshold = 0.10;
    private int hostPoolMaxBlocks = 1024;
    private String diskOffloadPath = System.getProperty("user.home") + "/.kompile/kvcache/disk";
    private boolean prefixCacheEnabled = false;
    private int prefixCacheMaxEntries = 1024;
    private boolean checkpointEnabled = false;
    private int maxCheckpoints = 16;
    private String checkpointDir = System.getProperty("user.home") + "/.kompile/kvcache/checkpoints";
    private int statsWindowSeconds = 300;

    // Priority-based eviction
    private boolean priorityEvictionEnabled = false;
    private int defaultBlockPriority = 50;
    private int systemPromptPriority = 90;

    // Content-hash prefix indexing
    private boolean prefixHashEnabled = true;
    private int prefixHashMaxEntries = 4096;

    @Autowired
    public KVCacheProperties(@Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.objectMapper = new ObjectMapper();
        String effectiveDataDir = (dataDir == null || dataDir.isBlank())
                ? System.getProperty("user.home") + "/.kompile"
                : dataDir;
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        log.info("KVCacheProperties initialized, config path: {}", configFilePath);
    }

    /**
     * Loads the persisted JSON config from disk on startup and overlays its values
     * onto this bean's fields. Any field absent from the JSON retains its default value.
     * Never throws — a missing or unreadable file is silently treated as "use defaults".
     */
    @PostConstruct
    public void loadConfig() {
        if (!Files.exists(configFilePath)) {
            log.info("No KV cache config found at {} - using defaults", configFilePath);
            return;
        }
        try {
            String json = Files.readString(configFilePath);
            objectMapper.readerForUpdating(this).readValue(json);
            log.info("Loaded KV cache config from {}", configFilePath);
        } catch (IOException e) {
            log.warn("Could not read KV cache config from {}: {} - using defaults", configFilePath, e.getMessage());
        }
    }

    /**
     * Persists the current field values to {@code <dataDir>/config/kvcache-config.json}
     * as pretty-printed JSON. Creates parent directories if they do not exist.
     */
    public void persist() {
        try {
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
            Files.writeString(configFilePath, json);
            log.info("Persisted KV cache config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist KV cache config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }
}
