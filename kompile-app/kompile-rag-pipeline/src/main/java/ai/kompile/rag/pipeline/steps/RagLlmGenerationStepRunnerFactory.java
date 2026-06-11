package ai.kompile.rag.pipeline.steps;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;

import java.util.List;

public class RagLlmGenerationStepRunnerFactory implements PipelineStepRunnerFactory {

    public static final String STEP_TYPE = "RAG_LLM_GENERATION";

    @Override
    public String stepTypeName() {
        return STEP_TYPE;
    }

    @Override
    public String getRunnerType() {
        return RagLlmGenerationStepRunner.class.getName();
    }

    @Override
    public PipelineStepRunner create() {
        return new RagLlmGenerationStepRunner();
    }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name(STEP_TYPE)
                .runnerClassName(getRunnerType())
                .description("Generates LLM response from query and retrieved document context")
                .configClass(GenericStepConfig.class.getName())
                .parameters(List.of(
                        ParameterSchema.builder()
                                .name(RagLlmGenerationStepRunner.PARAM_PROVIDER)
                                .description("LLM provider: OPENAI, ANTHROPIC, GEMINI, LOCAL_SAMEDIFF")
                                .type(ValueType.STRING)
                                .required(false)
                                .defaultValue("OPENAI")
                                .build(),
                        ParameterSchema.builder()
                                .name(RagLlmGenerationStepRunner.PARAM_MODEL)
                                .description("LLM model name (e.g. gpt-4, claude-3-5-sonnet)")
                                .type(ValueType.STRING)
                                .required(false)
                                .defaultValue("gpt-4")
                                .build(),
                        ParameterSchema.builder()
                                .name(RagLlmGenerationStepRunner.PARAM_SYSTEM_PROMPT)
                                .description("System prompt prepended to the context")
                                .type(ValueType.STRING)
                                .required(false)
                                .build(),
                        ParameterSchema.builder()
                                .name(RagLlmGenerationStepRunner.PARAM_TEMPERATURE)
                                .description("Temperature for generation (0.0-2.0)")
                                .type(ValueType.DOUBLE)
                                .required(false)
                                .defaultValue("0.7")
                                .build(),
                        ParameterSchema.builder()
                                .name(RagLlmGenerationStepRunner.PARAM_MAX_TOKENS)
                                .description("Maximum tokens to generate")
                                .type(ValueType.INT64)
                                .required(false)
                                .defaultValue("1024")
                                .build()
                ))
                .inputs(List.of(
                        ParameterSchema.builder()
                                .name("query")
                                .description("The user query")
                                .type(ValueType.STRING)
                                .required(true)
                                .build(),
                        ParameterSchema.builder()
                                .name("documents")
                                .description("Retrieved document contents for context")
                                .type(ValueType.LIST)
                                .listElementType(ValueType.STRING)
                                .required(false)
                                .build()
                ))
                .outputs(List.of(
                        ParameterSchema.builder()
                                .name("response")
                                .description("Generated LLM response")
                                .type(ValueType.STRING)
                                .build(),
                        ParameterSchema.builder()
                                .name("context")
                                .description("Concatenated document context used")
                                .type(ValueType.STRING)
                                .build(),
                        ParameterSchema.builder()
                                .name("document_count")
                                .description("Number of context documents used")
                                .type(ValueType.INT64)
                                .build()
                ))
                .build();
    }
}
