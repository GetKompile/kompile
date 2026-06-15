package ai.kompile.kvcache.service;

import ai.kompile.kvcache.config.KVCacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Persists KV cache configuration to ~/.kompile/kvcache/config.json
 * so that UI-driven changes survive restarts.
 */
@Slf4j
public class KVCacheConfigPersistence {

    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.kompile/kvcache";
    private static final String CONFIG_FILE = "config.json";

    private final ObjectMapper objectMapper;
    private final Path configPath;

    public KVCacheConfigPersistence() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.configPath = Paths.get(CONFIG_DIR, CONFIG_FILE);
    }

    /**
     * Loads persisted config and applies it to the given properties.
     * Properties set via application.properties take lowest priority;
     * persisted config overrides them.
     */
    public void loadInto(KVCacheProperties properties) {
        if (!Files.exists(configPath)) {
            log.debug("No persisted KV cache config found at {}", configPath);
            return;
        }
        try {
            Map<String, Object> saved = objectMapper.readValue(configPath.toFile(), Map.class);
            applyMap(saved, properties);
            log.info("Loaded persisted KV cache config from {}", configPath);
        } catch (IOException e) {
            log.warn("Failed to load persisted KV cache config: {}", e.getMessage());
        }
    }

    /**
     * Saves the current properties to disk.
     */
    public void save(KVCacheProperties properties) {
        try {
            Files.createDirectories(configPath.getParent());
            objectMapper.writeValue(configPath.toFile(), toMap(properties));
            log.info("Saved KV cache config to {}", configPath);
        } catch (IOException e) {
            log.error("Failed to save KV cache config: {}", e.getMessage());
        }
    }

    private Map<String, Object> toMap(KVCacheProperties p) {
        return Map.ofEntries(
                Map.entry("enabled", p.isEnabled()),
                Map.entry("defaultType", p.getDefaultType()),
                Map.entry("blockSize", p.getBlockSize()),
                Map.entry("maxBatchSize", p.getMaxBatchSize()),
                Map.entry("maxSeqLen", p.getMaxSeqLen()),
                Map.entry("numKvHeads", p.getNumKvHeads()),
                Map.entry("headDim", p.getHeadDim()),
                Map.entry("dataType", p.getDataType()),
                Map.entry("poolSizeFactor", p.getPoolSizeFactor()),
                Map.entry("evictionPolicy", p.getEvictionPolicy()),
                Map.entry("tokenBudget", p.getTokenBudget()),
                Map.entry("quantFormat", p.getQuantFormat()),
                Map.entry("tieredEnabled", p.isTieredEnabled()),
                Map.entry("gpuPressureThreshold", p.getGpuPressureThreshold()),
                Map.entry("hostPoolMaxBlocks", p.getHostPoolMaxBlocks()),
                Map.entry("diskOffloadPath", p.getDiskOffloadPath()),
                Map.entry("prefixCacheEnabled", p.isPrefixCacheEnabled()),
                Map.entry("prefixCacheMaxEntries", p.getPrefixCacheMaxEntries()),
                Map.entry("checkpointEnabled", p.isCheckpointEnabled()),
                Map.entry("maxCheckpoints", p.getMaxCheckpoints()),
                Map.entry("checkpointDir", p.getCheckpointDir()),
                Map.entry("statsWindowSeconds", p.getStatsWindowSeconds())
        );
    }

    @SuppressWarnings("unchecked")
    private void applyMap(Map<String, Object> saved, KVCacheProperties p) {
        if (saved.containsKey("enabled")) p.setEnabled((Boolean) saved.get("enabled"));
        if (saved.containsKey("defaultType")) p.setDefaultType((String) saved.get("defaultType"));
        if (saved.containsKey("blockSize")) p.setBlockSize(toInt(saved.get("blockSize")));
        if (saved.containsKey("maxBatchSize")) p.setMaxBatchSize(toInt(saved.get("maxBatchSize")));
        if (saved.containsKey("maxSeqLen")) p.setMaxSeqLen(toInt(saved.get("maxSeqLen")));
        if (saved.containsKey("numKvHeads")) p.setNumKvHeads(toInt(saved.get("numKvHeads")));
        if (saved.containsKey("headDim")) p.setHeadDim(toInt(saved.get("headDim")));
        if (saved.containsKey("dataType")) p.setDataType((String) saved.get("dataType"));
        if (saved.containsKey("poolSizeFactor")) p.setPoolSizeFactor(toDouble(saved.get("poolSizeFactor")));
        if (saved.containsKey("evictionPolicy")) p.setEvictionPolicy((String) saved.get("evictionPolicy"));
        if (saved.containsKey("tokenBudget")) p.setTokenBudget(toInt(saved.get("tokenBudget")));
        if (saved.containsKey("quantFormat")) p.setQuantFormat((String) saved.get("quantFormat"));
        if (saved.containsKey("tieredEnabled")) p.setTieredEnabled((Boolean) saved.get("tieredEnabled"));
        if (saved.containsKey("gpuPressureThreshold")) p.setGpuPressureThreshold(toDouble(saved.get("gpuPressureThreshold")));
        if (saved.containsKey("hostPoolMaxBlocks")) p.setHostPoolMaxBlocks(toInt(saved.get("hostPoolMaxBlocks")));
        if (saved.containsKey("diskOffloadPath")) p.setDiskOffloadPath((String) saved.get("diskOffloadPath"));
        if (saved.containsKey("prefixCacheEnabled")) p.setPrefixCacheEnabled((Boolean) saved.get("prefixCacheEnabled"));
        if (saved.containsKey("prefixCacheMaxEntries")) p.setPrefixCacheMaxEntries(toInt(saved.get("prefixCacheMaxEntries")));
        if (saved.containsKey("checkpointEnabled")) p.setCheckpointEnabled((Boolean) saved.get("checkpointEnabled"));
        if (saved.containsKey("maxCheckpoints")) p.setMaxCheckpoints(toInt(saved.get("maxCheckpoints")));
        if (saved.containsKey("checkpointDir")) p.setCheckpointDir((String) saved.get("checkpointDir"));
        if (saved.containsKey("statsWindowSeconds")) p.setStatsWindowSeconds(toInt(saved.get("statsWindowSeconds")));
    }

    private int toInt(Object val) {
        if (val instanceof Number) return ((Number) val).intValue();
        return Integer.parseInt(val.toString());
    }

    private double toDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        return Double.parseDouble(val.toString());
    }
}
