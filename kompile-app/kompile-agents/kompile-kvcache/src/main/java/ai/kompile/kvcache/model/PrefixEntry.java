package ai.kompile.kvcache.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrefixEntry {
    private String prefixHash;
    private int tokenCount;
    private int blockCount;
    private long accessCount;
    private long lastAccessed;
}
