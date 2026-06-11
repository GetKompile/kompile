package ai.kompile.rag.pipeline.steps;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;

import java.util.List;

public class RagRerankingStepRunnerFactory implements PipelineStepRunnerFactory {

    public static final String STEP_TYPE = "RAG_RERANKING";

    @Override
    public String stepTypeName() {
        return STEP_TYPE;
    }

    @Override
    public String getRunnerType() {
        return RagRerankingStepRunner.class.getName();
    }

    @Override
    public PipelineStepRunner create() {
        return new RagRerankingStepRunner();
    }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name(STEP_TYPE)
                .runnerClassName(getRunnerType())
                .description("Reranks retrieved documents using cross-encoder, MMR, RRF, or other strategies")
                .configClass(GenericStepConfig.class.getName())
                .parameters(List.of(
                        ParameterSchema.builder()
                                .name(RagRerankingStepRunner.PARAM_ENABLED)
                                .description("Whether reranking is enabled")
                                .type(ValueType.BOOLEAN)
                                .required(false)
                                .defaultValue("false")
                                .build(),
                        ParameterSchema.builder()
                                .name(RagRerankingStepRunner.PARAM_RERANKER_TYPE)
                                .description("Reranker type: none, cross_encoder, rrf, mmr, rm3, bm25prf")
                                .type(ValueType.STRING)
                                .required(false)
                                .defaultValue("none")
                                .build(),
                        ParameterSchema.builder()
                                .name(RagRerankingStepRunner.PARAM_CROSS_ENCODER_MODEL)
                                .description("Cross-encoder model ID (e.g. ms-marco-MiniLM-L-6-v2)")
                                .type(ValueType.STRING)
                                .required(false)
                                .build(),
                        ParameterSchema.builder()
                                .name(RagRerankingStepRunner.PARAM_TOP_K)
                                .description("Number of top documents to rerank")
                                .type(ValueType.INT64)
                                .required(false)
                                .defaultValue("100")
                                .build(),
                        ParameterSchema.builder()
                                .name(RagRerankingStepRunner.PARAM_MMR_LAMBDA)
                                .description("MMR lambda for diversity vs relevance (0.0-1.0)")
                                .type(ValueType.DOUBLE)
                                .required(false)
                                .defaultValue("0.5")
                                .build()
                ))
                .inputs(List.of(
                        ParameterSchema.builder()
                                .name("query")
                                .description("The search query text")
                                .type(ValueType.STRING)
                                .required(true)
                                .build(),
                        ParameterSchema.builder()
                                .name("documents")
                                .description("Retrieved document contents to rerank")
                                .type(ValueType.LIST)
                                .listElementType(ValueType.STRING)
                                .required(true)
                                .build()
                ))
                .outputs(List.of(
                        ParameterSchema.builder()
                                .name("query")
                                .description("Pass-through query text")
                                .type(ValueType.STRING)
                                .build(),
                        ParameterSchema.builder()
                                .name("documents")
                                .description("Reranked document contents")
                                .type(ValueType.LIST)
                                .listElementType(ValueType.STRING)
                                .build(),
                        ParameterSchema.builder()
                                .name("document_count")
                                .description("Number of documents after reranking")
                                .type(ValueType.INT64)
                                .build()
                ))
                .build();
    }
}
