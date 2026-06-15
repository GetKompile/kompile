package ai.kompile.kvcache.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KVCacheSummary {
    private String name;
    private String type;
    private long createdAt;
    private long memoryUsageBytes;
    private int activeSequences;
    private int freeBlocks;
    private int totalBlocks;
}
