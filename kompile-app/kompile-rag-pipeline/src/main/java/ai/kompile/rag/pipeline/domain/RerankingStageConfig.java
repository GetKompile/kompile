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
public class RerankingStageConfig {
    @Builder.Default
    private boolean enabled = false;
    /** Reranker type: "none", "cross_encoder", "rrf", "mmr", "rm3", "bm25prf", "rocchio", "axiom" */
    @Builder.Default
    private String rerankerType = "none";
    /** Cross-encoder model ID, e.g. "ms-marco-MiniLM-L-6-v2" */
    private String crossEncoderModel;
    /** Model source: "default", "registry", "archive" */
    @Builder.Default
    private String crossEncoderModelSource = "default";
    /** Archive ID for cross-encoder when source is "archive" */
    private String crossEncoderArchiveId;
    /** Number of top documents to rerank */
    @Builder.Default
    private int topK = 100;
    /** MMR lambda for diversity vs relevance trade-off (only used when rerankerType is "mmr") */
    @Builder.Default
    private double mmrLambda = 0.5;
}
