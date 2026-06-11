package ai.kompile.rag.pipeline.steps;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipeline step that generates query embeddings using the configured embedding model.
 * <p>
 * Input: {@code "query"} (String)<br>
 * Output: {@code "query"} (String, pass-through) + {@code "query_embedded"} (boolean flag)
 * <p>
 * The actual INDArray embedding is stored in the pipeline Context under {@code "query_embedding"}
 * so downstream steps (retrieval) can use it without serialization overhead.
 * <p>
 * If the embedding model ID is "none", this step passes through without embedding.
 */
public class RagEmbeddingStepRunner implements PipelineStepRunner {

    private static final Logger log = LoggerFactory.getLogger(RagEmbeddingStepRunner.class);

    public static final String PARAM_EMBEDDING_MODEL_ID = "embeddingModelId";
    public static final String PARAM_EMBEDDING_MODEL_SOURCE = "embeddingModelSource";
    public static final String PARAM_EMBEDDING_ARCHIVE_ID = "embeddingArchiveId";

    private StepConfig stepConfig;
    private EmbeddingModel embeddingModel;
    private boolean initialized;
    private boolean skipEmbedding;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        this.stepConfig = stepConfig;
        String modelId = stepConfig.get(PARAM_EMBEDDING_MODEL_ID);
        this.skipEmbedding = "none".equals(modelId) || modelId == null;

        if (!skipEmbedding) {
            this.embeddingModel = context.get("embeddingModel", EmbeddingModel.class).orElse(null);
            if (this.embeddingModel == null) {
                throw new IllegalStateException("EmbeddingModel not found in pipeline context.");
            }
            log.info("Initialized RAG embedding step with model: {}", modelId);
        } else {
            log.info("Embedding step configured with modelId=none, skipping embedding");
        }
        this.initialized = true;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        String query = input.get("query");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Missing required input key 'query'");
        }

        Data output = Data.empty();
        output.put("query", query);

        if (!skipEmbedding) {
            var embedding = embeddingModel.embed(query);
            // Store INDArray in context for the retrieval step to use natively
            context.put("query_embedding", embedding);
            output.put("query_embedded", true);
            log.debug("Generated embedding for query, dimensions={}", embedding.columns());
        } else {
            output.put("query_embedded", false);
        }

        return output;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        // EmbeddingModel lifecycle managed by Spring
    }
}
