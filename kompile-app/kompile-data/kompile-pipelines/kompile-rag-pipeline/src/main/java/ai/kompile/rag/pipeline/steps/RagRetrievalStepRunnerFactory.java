package ai.kompile.rag.pipeline.steps;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;

import java.util.List;

public class RagRetrievalStepRunnerFactory implements PipelineStepRunnerFactory {

    public static final String STEP_TYPE = "RAG_RETRIEVAL";

    @Override
    public String stepTypeName() {
        return STEP_TYPE;
    }

    @Override
    public String getRunnerType() {
        return RagRetrievalStepRunner.class.getName();
    }

    @Override
    public PipelineStepRunner create() {
        return new RagRetrievalStepRunner();
    }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name(STEP_TYPE)
                .runnerClassName(getRunnerType())
                .description("Retrieves documents using semantic, keyword, or hybrid search")
                .configClass(GenericStepConfig.class.getName())
                .parameters(List.of(
                        ParameterSchema.builder()
                                .name(RagRetrievalStepRunner.PARAM_STRATEGY)
                                .description("Retrieval strategy: SEMANTIC, KEYWORD, or HYBRID")
                                .type(ValueType.STRING)
                                .required(false)
                                .defaultValue("HYBRID")
                                .build(),
                        ParameterSchema.builder()
                                .name(RagRetrievalStepRunner.PARAM_TOP_K)
                                .description("Number of top documents to retrieve")
                                .type(ValueType.INT64)
                                .required(false)
                                .defaultValue("10")
                                .build(),
                        ParameterSchema.builder()
                                .name(RagRetrievalStepRunner.PARAM_SIMILARITY_THRESHOLD)
                                .description("Minimum similarity score threshold")
                                .type(ValueType.DOUBLE)
                                .required(false)
                                .defaultValue("0.0")
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
                                .name("documents")
                                .description("Retrieved document contents")
                                .type(ValueType.LIST)
                                .listElementType(ValueType.STRING)
                                .build(),
                        ParameterSchema.builder()
                                .name("document_count")
                                .description("Number of documents retrieved")
                                .type(ValueType.INT64)
                                .build()
                ))
                .build();
    }
}
