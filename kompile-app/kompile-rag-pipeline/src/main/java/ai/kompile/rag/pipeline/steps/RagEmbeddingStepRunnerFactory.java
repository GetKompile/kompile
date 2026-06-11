package ai.kompile.rag.pipeline.steps;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;

import java.util.List;

public class RagEmbeddingStepRunnerFactory implements PipelineStepRunnerFactory {

    public static final String STEP_TYPE = "RAG_EMBEDDING";

    @Override
    public String stepTypeName() {
        return STEP_TYPE;
    }

    @Override
    public String getRunnerType() {
        return RagEmbeddingStepRunner.class.getName();
    }

    @Override
    public PipelineStepRunner create() {
        return new RagEmbeddingStepRunner();
    }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name(STEP_TYPE)
                .runnerClassName(getRunnerType())
                .description("Generates query embeddings using the configured embedding model")
                .configClass(GenericStepConfig.class.getName())
                .parameters(List.of(
                        ParameterSchema.builder()
                                .name(RagEmbeddingStepRunner.PARAM_EMBEDDING_MODEL_ID)
                                .description("Embedding model identifier (e.g. bge-base-en-v1.5)")
                                .type(ValueType.STRING)
                                .required(true)
                                .build(),
                        ParameterSchema.builder()
                                .name(RagEmbeddingStepRunner.PARAM_EMBEDDING_MODEL_SOURCE)
                                .description("Model source: default, registry, or archive")
                                .type(ValueType.STRING)
                                .required(false)
                                .defaultValue("default")
                                .build(),
                        ParameterSchema.builder()
                                .name(RagEmbeddingStepRunner.PARAM_EMBEDDING_ARCHIVE_ID)
                                .description("Archive ID when source is 'archive'")
                                .type(ValueType.STRING)
                                .required(false)
                                .build()
                ))
                .inputs(List.of(
                        ParameterSchema.builder()
                                .name("query")
                                .description("The search query text")
                                .type(ValueType.STRING)
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
                                .name("query_embedded")
                                .description("Whether embedding was generated")
                                .type(ValueType.BOOLEAN)
                                .build()
                ))
                .build();
    }
}
