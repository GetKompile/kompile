/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

// Location: kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-python/src/main/java/ai/kompile/pipelines/steps/python/
package ai.kompile.pipelines.steps.python;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory; // Updated interface
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig; // For configClass

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PythonRunnerFactory implements PipelineStepRunnerFactory {

    // Assume PythonRunnerConstants.STEP_TYPE_NAME exists, or define it
    public static final String STEP_TYPE_NAME =  "PYTHON_SCRIPT"; // Or "PYTHON_SCRIPT"

    @Override
    public String stepTypeName() {
        return STEP_TYPE_NAME;
    }

    @Override
    public String getRunnerType() {
        return PythonRunnerConstants.RUNNER_FQCN;
    }

    @Override
    public PipelineStepRunner create() {
        return new PythonRunner();
    }

    @Override
    public StepSchema getSchema() {
        List<ParameterSchema> params = Arrays.asList(
                ParameterSchema.builder().name("pythonCode")
                        .type(ValueType.STRING)
                        .description("The Python code to execute. Either this or 'pythonScriptPath' must be provided.")
                        .required(false).build(),
                ParameterSchema.builder().name("pythonScriptPath")
                        .type(ValueType.STRING)
                        .description("Path to the Python script file to execute. Either this or 'pythonCode' must be provided.")
                        .required(false).build(),
                ParameterSchema.builder().name("pythonConfigName")
                        .type(ValueType.STRING)
                        .description("Name of the PythonConfig bean to use (defines Python environment, path, etc.). If null, uses default.")
                        .required(false).build(),
                ParameterSchema.builder().name("inputs") // Defines how pipeline Data maps to Python script inputs
                        .type(ValueType.LIST)
                        .listElementType(ValueType.OBJECT) // Elements are mapping objects
                        .subTypeClassName(Map.class.getName()) // Each element is a map like {"pipelineName": "img", "pythonName": "image_data", "type": "NDARRAY"}
                        .description("List of input mappings from pipeline Data to Python script variables.")
                        .required(false).build(),
                ParameterSchema.builder().name("outputs") // Defines how Python script outputs map to pipeline Data
                        .type(ValueType.LIST)
                        .listElementType(ValueType.OBJECT) // Elements are mapping objects
                        .subTypeClassName(Map.class.getName()) // Each element is a map like {"pythonName": "result", "pipelineName": "py_output", "type": "STRING"}
                        .description("List of output mappings from Python script variables to pipeline Data.")
                        .required(false).build(),
                ParameterSchema.builder().name("setupCode")
                        .type(ValueType.STRING)
                        .description("(Optional) Python code to run once when the script is first loaded/initialized.")
                        .required(false).build(),
                ParameterSchema.builder().name("returnAllVariables")
                        .type(ValueType.BOOLEAN)
                        .description("If true, all Python global variables are returned in the output Data object (default: false).")
                        .defaultValue(false)
                        .required(false).build()
        );

        // Python inputs/outputs are highly dynamic, defined by the 'inputs' and 'outputs' parameters themselves.
        // The schema can just indicate generic Data in/out.
        List<ParameterSchema> genericInputs = Collections.singletonList(
                ParameterSchema.builder().name("input_data_for_python")
                        .type(ValueType.DATA)
                        .description("Input Data object containing variables to be passed to the Python script as per 'inputs' configuration.")
                        .required(false).build() // Input might be empty if script generates data
        );
        List<ParameterSchema> genericOutputs = Collections.singletonList(
                ParameterSchema.builder().name("output_data_from_python")
                        .type(ValueType.DATA)
                        .description("Output Data object containing variables returned from the Python script as per 'outputs' configuration or 'returnAllVariables'.")
                        .required(true).build()
        );


        return StepSchema.builder()
                .name(stepTypeName())
                .runnerClassName(PythonRunnerConstants.RUNNER_FQCN)
                .description("Executes a Python script or a block of Python code, allowing data exchange with the pipeline.")
                .configClass(GenericStepConfig.class.getName()) // Assuming PythonRunner uses GenericStepConfig or a specific PythonStepConfig
                .parameters(params)
                .inputs(genericInputs)
                .outputs(genericOutputs)
                .build();
    }
}