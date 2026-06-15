package ai.kompile.kvcache.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrefixCacheStats {
    private int totalEntries;
    private int maxEntries;
    private long totalLookups;
    private long totalHits;
    private double hitRate;
}
