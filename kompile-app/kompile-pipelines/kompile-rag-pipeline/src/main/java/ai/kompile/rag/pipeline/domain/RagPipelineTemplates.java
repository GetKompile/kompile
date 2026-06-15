package ai.kompile.rag.pipeline.domain;

import java.time.Instant;
import java.util.List;

/**
 * Built-in RAG pipeline templates that ship with kompile.
 * Users can select these from the pipeline dropdown or derive custom pipelines from them.
 */
public final class RagPipelineTemplates {

    private RagPipelineTemplates() {}

    public static final String DEFAULT_HYBRID_ID = "default-hybrid";
    public static final String FAST_KEYWORD_ID = "fast-keyword";
    public static final String HIGH_ACCURACY_ID = "high-accuracy";
    public static final String LOCAL_SAMEDIFF_ID = "local-samediff";

    /**
     * Default Hybrid RAG: BGE embeddings + hybrid (semantic + keyword) retrieval
     * + cross-encoder reranking + OpenAI LLM.
     */
    public static RagPipelineDefinition defaultHybrid() {
        return RagPipelineDefinition.builder()
                .id(DEFAULT_HYBRID_ID)
                .name("Default Hybrid RAG")
                .description("BGE embeddings with hybrid retrieval, cross-encoder reranking, and OpenAI generation")
                .builtin(true)
                .enabled(true)
                .createdAt(Instant.EPOCH)
                .updatedAt(Instant.EPOCH)
                .embedding(EmbeddingStageConfig.builder()
                        .modelId("bge-base-en-v1.5")
                        .modelSource("default")
                        .build())
                .retrieval(RetrievalStageConfig.builder()
                        .strategy(RetrievalStageConfig.RetrievalStrategy.HYBRID)
                        .topK(10)
                        .similarityThreshold(0.0)
                        .build())
                .reranking(RerankingStageConfig.builder()
                        .enabled(true)
                        .rerankerType("cross_encoder")
                        .crossEncoderModel("ms-marco-MiniLM-L-6-v2")
                        .crossEncoderModelSource("default")
                        .topK(100)
                        .build())
                .llm(LlmStageConfig.builder()
                        .provider(LlmStageConfig.LlmProvider.OPENAI)
                        .model("gpt-4")
                        .temperature(0.7)
                        .maxTokens(1024)
                        .build())
                .build();
    }

    /**
     * Fast Keyword Search: BM25-only retrieval, no embedding, no reranking.
     * Fastest pipeline for simple keyword-based search.
     */
    public static RagPipelineDefinition fastKeywordSearch() {
        return RagPipelineDefinition.builder()
                .id(FAST_KEYWORD_ID)
                .name("Fast Keyword Search")
                .description("BM25 keyword-only retrieval with no reranking for maximum speed")
                .builtin(true)
                .enabled(true)
                .createdAt(Instant.EPOCH)
                .updatedAt(Instant.EPOCH)
                .embedding(EmbeddingStageConfig.builder()
                        .modelId("none")
                        .modelSource("default")
                        .build())
                .retrieval(RetrievalStageConfig.builder()
                        .strategy(RetrievalStageConfig.RetrievalStrategy.KEYWORD)
                        .topK(10)
                        .build())
                .reranking(RerankingStageConfig.builder()
                        .enabled(false)
                        .rerankerType("none")
                        .build())
                .llm(LlmStageConfig.builder()
                        .provider(LlmStageConfig.LlmProvider.OPENAI)
                        .model("gpt-4")
                        .temperature(0.7)
                        .maxTokens(1024)
                        .build())
                .build();
    }

    /**
     * High Accuracy: BGE embeddings + hybrid retrieval + cross-encoder reranking
     * + RRF fusion + higher top-K for maximum retrieval quality.
     */
    public static RagPipelineDefinition highAccuracy() {
        return RagPipelineDefinition.builder()
                .id(HIGH_ACCURACY_ID)
                .name("High Accuracy RAG")
                .description("Hybrid retrieval with cross-encoder reranking and RRF fusion for maximum accuracy")
                .builtin(true)
                .enabled(true)
                .createdAt(Instant.EPOCH)
                .updatedAt(Instant.EPOCH)
                .embedding(EmbeddingStageConfig.builder()
                        .modelId("bge-base-en-v1.5")
                        .modelSource("default")
                        .build())
                .retrieval(RetrievalStageConfig.builder()
                        .strategy(RetrievalStageConfig.RetrievalStrategy.HYBRID)
                        .topK(20)
                        .similarityThreshold(0.0)
                        .build())
                .reranking(RerankingStageConfig.builder()
                        .enabled(true)
                        .rerankerType("cross_encoder")
                        .crossEncoderModel("ms-marco-MiniLM-L-12-v2")
                        .crossEncoderModelSource("default")
                        .topK(200)
                        .build())
                .llm(LlmStageConfig.builder()
                        .provider(LlmStageConfig.LlmProvider.OPENAI)
                        .model("gpt-4")
                        .temperature(0.3)
                        .maxTokens(2048)
                        .build())
                .build();
    }

    /**
     * Local SameDiff: Fully local pipeline using SameDiff embeddings, cross-encoder,
     * and SmolLM for generation — no external API calls needed.
     */
    public static RagPipelineDefinition localSameDiff() {
        return RagPipelineDefinition.builder()
                .id(LOCAL_SAMEDIFF_ID)
                .name("Local SameDiff LLM")
                .description("Fully local pipeline using SameDiff models and SmolLM — no external API calls")
                .builtin(true)
                .enabled(true)
                .createdAt(Instant.EPOCH)
                .updatedAt(Instant.EPOCH)
                .embedding(EmbeddingStageConfig.builder()
                        .modelId("bge-base-en-v1.5")
                        .modelSource("default")
                        .build())
                .retrieval(RetrievalStageConfig.builder()
                        .strategy(RetrievalStageConfig.RetrievalStrategy.HYBRID)
                        .topK(10)
                        .similarityThreshold(0.0)
                        .build())
                .reranking(RerankingStageConfig.builder()
                        .enabled(true)
                        .rerankerType("cross_encoder")
                        .crossEncoderModel("ms-marco-MiniLM-L-6-v2")
                        .crossEncoderModelSource("default")
                        .topK(100)
                        .build())
                .llm(LlmStageConfig.builder()
                        .provider(LlmStageConfig.LlmProvider.LOCAL_SAMEDIFF)
                        .model("smollm-135m-instruct")
                        .temperature(0.7)
                        .maxTokens(512)
                        .build())
                .build();
    }

    /** Returns all built-in pipeline templates. */
    public static List<RagPipelineDefinition> allBuiltins() {
        return List.of(defaultHybrid(), fastKeywordSearch(), highAccuracy(), localSameDiff());
    }
}
