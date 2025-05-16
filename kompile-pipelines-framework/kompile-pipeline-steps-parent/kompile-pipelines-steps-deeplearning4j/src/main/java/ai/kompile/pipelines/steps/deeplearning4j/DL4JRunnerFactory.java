// Location: kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-deeplearning4j/src/main/java/ai/kompile/pipelines/steps/deeplearning4j/
package ai.kompile.pipelines.steps.deeplearning4j;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory; // Updated interface
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig; // For configClass

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DL4JRunnerFactory implements PipelineStepRunnerFactory {

    // Assume DL4JRunnerConstants.STEP_TYPE_NAME exists, or define it
    public static final String STEP_TYPE_NAME = "DL4J_INFERENCE"; // Or "DL4J_INFERENCE"

    @Override
    public String stepTypeName() {
        return STEP_TYPE_NAME;
    }

    @Override
    public String getRunnerType() {
        return DL4JRunnerConstants.RUNNER_FQCN;
    }

    @Override
    public PipelineStepRunner create() {
        return new DL4JRunner();
    }

    @Override
    public StepSchema getSchema() {
        List<ParameterSchema> params = Arrays.asList(
                ParameterSchema.builder().name("modelUri")
                        .type(ValueType.STRING)
                        .description("URI of the DL4J model file (ComputationGraph or MultiLayerNetwork).")
                        .required(true).build(),
                ParameterSchema.builder().name("inputNames")
                        .type(ValueType.LIST)
                        .listElementType(ValueType.STRING)
                        .description("List of input names (keys in input Data) for the model. Order matters if model has multiple inputs.")
                        .required(true).build(),
                ParameterSchema.builder().name("outputNames")
                        .type(ValueType.LIST)
                        .listElementType(ValueType.STRING)
                        .description("List of output names (keys for output Data) for the model's predictions. Order corresponds to model output order.")
                        .required(true).build()
        );

        // Inputs and Outputs are dynamic based on inputNames/outputNames parameters
        // For schema, we can list generic expectations or leave them more open.
        List<ParameterSchema> inputs = Collections.singletonList(
                ParameterSchema.builder().name("dynamic_input_*")
                        .type(ValueType.OBJECT) // Could be NDArray, Image, etc.
                        .description("Dynamic inputs based on the 'inputNames' parameter. Each key from 'inputNames' is expected here.")
                        .required(true).build()
        );
        List<ParameterSchema> outputs = Collections.singletonList(
                ParameterSchema.builder().name("dynamic_output_*")
                        .type(ValueType.OBJECT) // Typically NDArray
                        .description("Dynamic outputs based on the 'outputNames' parameter. Each key from 'outputNames' will be present here.")
                        .required(true).build()
        );


        return StepSchema.builder()
                .name(stepTypeName())
                .runnerClassName(DL4JRunnerConstants.RUNNER_FQCN)
                .description("Executes a pre-trained Deeplearning4j model (ComputationGraph or MultiLayerNetwork) for general inference tasks.")
                .configClass(GenericStepConfig.class.getName()) // Assuming DL4JRunner uses GenericStepConfig
                .parameters(params)
                .inputs(inputs) // These are illustrative as actual keys are from parameters
                .outputs(outputs) // These are illustrative
                .build();
    }
}