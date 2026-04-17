package ai.kompile.rag.pipeline.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RagPipelineDefinition {
    private String id;
    private String name;
    private String description;
    @Builder.Default
    private boolean builtin = false;
    @Builder.Default
    private boolean enabled = true;
    private Instant createdAt;
    private Instant updatedAt;

    private EmbeddingStageConfig embedding;
    private RetrievalStageConfig retrieval;
    private RerankingStageConfig reranking;
    private LlmStageConfig llm;
}
