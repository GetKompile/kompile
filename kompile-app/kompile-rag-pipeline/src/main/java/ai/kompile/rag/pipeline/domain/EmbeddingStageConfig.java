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
public class EmbeddingStageConfig {
    /** Model identifier, e.g. "bge-base-en-v1.5" */
    private String modelId;
    /** Where to load the model from: "default", "registry", "archive" */
    @Builder.Default
    private String modelSource = "default";
    /** Archive ID when modelSource is "archive" */
    private String archiveId;
    /** Embedding dimensions — auto-detected from registry if null */
    private Integer dimensions;
}
