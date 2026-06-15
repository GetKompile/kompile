package ai.kompile.kvcache.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KVCacheStats {
    private String cacheName;
    private String cacheType;
    private long totalAppends;
    private long totalEvictions;
    private long totalFrees;
    private long hitCount;
    private long missCount;
    private double hitRate;
    private long memoryUsedBytes;
    private long memoryCapacityBytes;
    private double memoryUtilization;
    private int activeSequences;
    private int freeBlocks;
    private int totalBlocks;
    private Map<Integer, Integer> perSequenceTokenCounts;
    private List<StatsSample> recentSamples;
    private long collectedAt;
}
