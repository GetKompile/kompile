package ai.kompile.kvcache.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "kompile.kvcache")
public class KVCacheProperties {
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
}
