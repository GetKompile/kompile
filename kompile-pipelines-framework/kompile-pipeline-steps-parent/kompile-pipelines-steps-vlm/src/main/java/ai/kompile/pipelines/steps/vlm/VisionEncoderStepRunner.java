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

package ai.kompile.pipelines.steps.vlm;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.steps.vlm.util.VLMSameDiffUtils;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.io.File;
import java.util.*;

/**
 * Runs the vision encoder stage of a VLM pipeline.
 *
 * Input Data keys:
 *   - pixel_values: NDArray [1, num_tiles, 3, H, W]
 *   - pixel_attention_mask: NDArray [1, num_tiles, H, W]
 *
 * Output Data keys:
 *   - image_features: NDArray [1, seq_len, hidden_size]
 *
 * Config parameters:
 *   - modelUri: Path to the vision encoder SameDiff model (.fb file)
 *   - outputNames: List of model output names to capture
 */
public class VisionEncoderStepRunner implements PipelineStepRunner {

    private SameDiff sd;
    private List<String> outputNames;
    private String[] modelInputNames;
    private boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        Data params = stepConfig.getParameters();
        String modelUri = params.getString(VLMConstants.PARAM_MODEL_URI);
        Objects.requireNonNull(modelUri, "modelUri is required for VisionEncoderStepRunner");

        this.outputNames = params.getList(VLMConstants.PARAM_OUTPUT_NAMES,
                ai.kompile.pipelines.framework.api.data.ValueType.STRING);
        if (this.outputNames == null || this.outputNames.isEmpty()) {
            throw new IllegalArgumentException("outputNames is required for VisionEncoderStepRunner");
        }

        File modelFile = new File(modelUri);
        if (!modelFile.exists()) {
            throw new IllegalArgumentException("Vision encoder model not found: " + modelUri);
        }
        this.sd = SameDiff.load(modelFile, true);
        this.modelInputNames = sd.inputs().toArray(new String[0]);
        this.initialized = true;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("VisionEncoderStepRunner not initialized");
        }

        Map<String, INDArray> placeholders = new HashMap<>();
        Map<String, INDArray> outputMap = null;

        try {
            // Map inputs from Data to SameDiff placeholders
            for (String inputName : modelInputNames) {
                NDArray ndArr = input.getNDArray(inputName);
                if (ndArr == null) {
                    throw new IllegalArgumentException("Missing required input: " + inputName);
                }
                placeholders.put(inputName, VLMSameDiffUtils.toINDArray(ndArr));
            }

            outputMap = sd.output(placeholders, outputNames.toArray(new String[0]));

            Data result = Data.empty();
            for (Map.Entry<String, INDArray> entry : outputMap.entrySet()) {
                // Dup the output before closing to maintain independent references
                INDArray duped = entry.getValue().dup();
                result.put(entry.getKey(), VLMSameDiffUtils.fromINDArray(duped, entry.getKey()));
            }

            return result;
        } finally {
            VLMSameDiffUtils.closeAll(placeholders);
            VLMSameDiffUtils.closeAll(outputMap);
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        if (sd != null) {
            try {
                java.lang.reflect.Method closeMethod = sd.getClass().getMethod("close");
                closeMethod.invoke(sd);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {
            }
            sd = null;
        }
        initialized = false;
    }
}
