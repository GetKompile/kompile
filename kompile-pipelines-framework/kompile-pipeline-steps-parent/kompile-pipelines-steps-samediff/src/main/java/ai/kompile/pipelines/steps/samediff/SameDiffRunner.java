/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.steps.samediff;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.framework.core.config.ConfigAccessor;
import ai.kompile.pipelines.framework.core.config.SchemaRegistry;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.steps.samediff.utils.SameDiffDataUtils; // Use the utility class

import ai.kompile.pipelines.util.URIUtils;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.File;
import java.io.IOException;
import java.util.*;


public class SameDiffRunner implements PipelineStepRunner {

    private SameDiff sd;
    private List<String> configuredOutputNames;
    private String[] actualModelInputNames; // Store actual names from the loaded model
    private File modelFileRef; // Keep track if model was loaded from classpath temp file

    private boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        StepSchema schema = SchemaRegistry.getInstance().getSchema(stepConfig.runnerClassName())
                .orElseThrow(() -> new IllegalStateException(
                        "No schema found for runner: " + stepConfig.runnerClassName()));
        ConfigAccessor config = new ConfigAccessor(stepConfig.getParameters(), schema);

        String modelUriString = config.getString(SameDiffConstants.PARAM_MODEL_URI);
        this.configuredOutputNames = config.getStringList(SameDiffConstants.PARAM_OUTPUT_NAMES);
        boolean debugMode = config.getBoolean(SameDiffConstants.PARAM_DEBUG_MODE);
        boolean verboseMode = config.getBoolean(SameDiffConstants.PARAM_VERBOSE_MODE);

        Objects.requireNonNull(modelUriString, "Parameter '" + SameDiffConstants.PARAM_MODEL_URI + "' is required.");
        Objects.requireNonNull(configuredOutputNames, "Parameter '" + SameDiffConstants.PARAM_OUTPUT_NAMES + "' is required.");
        if (configuredOutputNames.isEmpty()) {
            throw new IllegalArgumentException("Parameter '" + SameDiffConstants.PARAM_OUTPUT_NAMES + "' cannot be empty.");
        }

        // Resolve URI and load model
        try {
            this.modelFileRef = URIUtils.resolveToTempOrLocalFile(modelUriString, context);
            if (!modelFileRef.exists()) {
                throw new IOException("SameDiff model file not found at resolved path: " + modelFileRef.getAbsolutePath());
            }
            sd = SameDiff.load(modelFileRef, true);
        } catch (Exception e) {
            throw new IOException("Failed to load SameDiff model from URI: " + modelUriString, e);
        }

        // Store actual model input names
        this.actualModelInputNames = sd.inputs().toArray(new String[0]);

        // Validate provided output names against actual model outputs (optional but good practice)
        List<String> actualOutputNames = sd.outputs();


        // Set ND4J executioner modes
        Nd4j.getExecutioner().enableDebugMode(debugMode);
        Nd4j.getExecutioner().enableVerboseMode(verboseMode);

        this.initialized = true;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized || sd == null) {
            throw new IllegalStateException("SameDiffRunner is not initialized.");
        }
        Objects.requireNonNull(input, "Input Data cannot be null.");

        Map<String, INDArray> placeholderMap = new HashMap<>();

        // Prepare inputs based on actual model input names
        for (String modelInputName : this.actualModelInputNames) {
            if (!input.has(modelInputName)) {
                throw new IllegalArgumentException("Input Data is missing required NDArray for model input key: '" + modelInputName + "'. Available keys: " + input.keySet());
            }
            NDArray kompileInput = input.getNDArray(modelInputName);
            if (kompileInput == null) {
                throw new IllegalArgumentException("Input Data contains null NDArray for model input key: '" + modelInputName + "'.");
            }
            try {
                placeholderMap.put(modelInputName, SameDiffDataUtils.convertToINDArray(kompileInput, modelInputName));
            } catch (Exception e) {
                throw new RuntimeException("Error converting input NDArray '" + modelInputName + "'", e);
            }
        }

        // Execute the model
        Map<String, INDArray> outputMap;
        try {
            // Filter requested outputs to only those that actually exist in the model
            List<String> validRequestedOutputs = new ArrayList<>();
            List<String> modelOutputs = sd.outputs();
            for(String requested : configuredOutputNames) {
                if(modelOutputs.contains(requested)) {
                    validRequestedOutputs.add(requested);
                }
            }
            if (validRequestedOutputs.isEmpty()) {
                throw new IllegalArgumentException("No valid output names provided matching the model's outputs.");
            }

            outputMap = sd.output(placeholderMap, validRequestedOutputs.toArray(new String[0]));
        } catch (Exception e) {
            throw e;
        }

        // Prepare output Data
        Data outputData = Data.empty();
        for (Map.Entry<String, INDArray> entry : outputMap.entrySet()) {
            String outputName = entry.getKey();
            if (configuredOutputNames.contains(outputName)) { // Ensure we only return what was requested
                try {
                    outputData.put(outputName, SameDiffDataUtils.convertFromINDArray(entry.getValue(), outputName));
                } catch (Exception e) {
                    // Decide how to handle: throw, skip, put error marker? Throwing for now.
                    throw new RuntimeException("Error converting output INDArray '" + outputName + "'", e);
                }
            }
        }

        return outputData;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        // SameDiff object itself doesn't have a close method.
        // Underlying resources are typically managed by ND4J's lifecycle.
        sd = null; // Release reference
        initialized = false;
        URIUtils.deleteTempFileQuietly(modelFileRef); // Clean up temp file if created
    }
}