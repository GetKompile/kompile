package ai.kompile.kvcache.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KVCacheConfig {
    private String type;
    private Integer blockSize;
    private Integer maxBatchSize;
    private Integer maxSeqLen;
    private Integer numKvHeads;
    private Integer headDim;
    private String dataType;
    private Double poolSizeFactor;
    private String evictionPolicy;
    private Integer tokenBudget;
    private String quantFormat;
    private Integer turboQuantBits;
    private Boolean tieredEnabled;
    private Double gpuPressureThreshold;
    private Integer hostPoolMaxBlocks;
    private String diskOffloadPath;
}
