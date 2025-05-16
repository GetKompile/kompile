package ai.kompile.pipelines.steps.samediff;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory; // Updated interface
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
// No longer need StepSchemaProvider if PipelineStepRunnerFactory includes getSchema
import ai.kompile.pipelines.framework.api.data.NDArray; // For output type hint
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig; // Assuming this is the config class

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map; // For subTypeClassName if needed for a Map parameter

public class SameDiffRunnerFactory implements PipelineStepRunnerFactory {

    // It's good practice to have a constant for the symbolic step type name.
    // If SameDiffConstants doesn't have one, we define it here.
    public static final String STEP_TYPE_NAME = "SAMEDIFF_INFERENCE"; // Or use SameDiffConstants.STEP_TYPE_NAME

    @Override
    public String stepTypeName() {
        return STEP_TYPE_NAME;
    }

    @Override
    public String getRunnerType() {
        // This should return the fully qualified class name of the runner
        return SameDiffConstants.RUNNER_FQCN; // From your provided SameDiffConstants.java
    }

    @Override
    public PipelineStepRunner create() {
        return new SameDiffRunner();
    }

    @Override
    public StepSchema getSchema() {
        List<ParameterSchema> params = Arrays.asList(
                ParameterSchema.builder().name(SameDiffConstants.PARAM_MODEL_URI)
                        .type(ValueType.STRING)
                        .description("URI of the SameDiff model file (.fb or .zip).")
                        .required(true).build(),
                ParameterSchema.builder().name(SameDiffConstants.PARAM_OUTPUT_NAMES)
                        .type(ValueType.LIST)
                        .listElementType(ValueType.STRING) // The list contains strings (output names)
                        .description("List of output variable names to fetch from the SameDiff graph. Order might be preserved.")
                        .required(true).build(),
                ParameterSchema.builder().name(SameDiffConstants.PARAM_DEBUG_MODE)
                        .type(ValueType.BOOLEAN)
                        .description("Enable ND4J executioner debug mode (default: false).")
                        .defaultValue(false)
                        .required(false).build(),
                ParameterSchema.builder().name(SameDiffConstants.PARAM_VERBOSE_MODE)
                        .type(ValueType.BOOLEAN)
                        .description("Enable ND4J executioner verbose mode (default: false).")
                        .defaultValue(false)
                        .required(false).build()
                // Add other parameters if SameDiffRunner supports them via StepConfig
        );

        // Inputs for SameDiffRunner are dynamic, based on the placeholders in the loaded model.
        // The runner code itself iterates through `sd.inputs()` and expects corresponding keys in the input Data.
        // We can represent this in the schema as a generic Data input or a note about dynamic keys.
        List<ParameterSchema> inputs = Collections.singletonList(
                ParameterSchema.builder().name("input_data_map")
                        .type(ValueType.DATA) // The input is a Data object containing multiple NDArrays
                        .description("Input Data object. Keys should match the placeholder names in the SameDiff model. " +
                                "Values should be Kompile NDArray types.")
                        .required(true).build()
        );

        // Outputs are also dynamic, based on the 'outputNames' parameter.
        // Each output specified will be an NDArray.
        List<ParameterSchema> outputs = Collections.singletonList(
                ParameterSchema.builder().name("output_data_map")
                        .type(ValueType.DATA) // The output is a Data object containing multiple NDArrays
                        .description("Output Data object. Keys will match the names specified in the '" +
                                SameDiffConstants.PARAM_OUTPUT_NAMES + "' parameter. Values will be Kompile NDArray types.")
                        .required(true).build()
        );

        return StepSchema.builder()
                .name(stepTypeName()) // Symbolic type name for this factory/step
                .runnerClassName(SameDiffConstants.RUNNER_FQCN) // FQN of the runner
                .description("Executes a pre-trained SameDiff model for inference.")
                // Assuming SameDiffRunner uses GenericStepConfig if it doesn't have its own specific StepConfig class
                .configClass(GenericStepConfig.class.getName())
                .parameters(params)  // Defined configuration parameters
                .inputs(inputs)      // Expected input Data contract
                .outputs(outputs)    // Expected output Data contract
                .build();
    }
}