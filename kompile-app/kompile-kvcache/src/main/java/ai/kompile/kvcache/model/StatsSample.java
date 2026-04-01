package ai.kompile.kvcache.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsSample {
    private long timestamp;
    private long memoryUsedBytes;
    private int activeSequences;
    private double appendsPerSecond;
    private double evictionsPerSecond;
}
