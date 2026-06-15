package ai.kompile.rag.pipeline.domain;

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
public class RetrievalStageConfig {

    public enum RetrievalStrategy {
        SEMANTIC, KEYWORD, HYBRID
    }

    @Builder.Default
    private RetrievalStrategy strategy = RetrievalStrategy.HYBRID;
    @Builder.Default
    private int topK = 10;
    @Builder.Default
    private double similarityThreshold = 0.0;
}
