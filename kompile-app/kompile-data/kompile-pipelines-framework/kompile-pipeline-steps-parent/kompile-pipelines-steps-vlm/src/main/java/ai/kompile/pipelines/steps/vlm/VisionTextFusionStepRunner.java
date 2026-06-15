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
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.Objects;

/**
 * Fuses vision and text embeddings by replacing image token embeddings with
 * actual vision encoder outputs.
 *
 * This follows the Idefics3/SmolDocling pattern where special &lt;image&gt; tokens
 * in the text sequence are replaced by the corresponding vision encoder outputs.
 *
 * Input Data keys:
 *   - image_features: NDArray [1, vision_seq_len, hidden_size] from vision encoder
 *   - text_embeddings: NDArray [1, text_seq_len, hidden_size] from embed_tokens
 *   - input_ids: NDArray [1, text_seq_len] of INT64 (to locate image token positions)
 *
 * Output Data keys:
 *   - inputs_embeds: NDArray [1, total_seq_len, hidden_size] with image tokens replaced
 *
 * Config parameters:
 *   - imageTokenId: The token ID that represents the &lt;image&gt; placeholder (e.g., 49153)
 */
public class VisionTextFusionStepRunner implements PipelineStepRunner {

    private int imageTokenId;
    private boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        Data params = stepConfig.getParameters();
        Long tokenId = params.getInt64(VLMConstants.PARAM_IMAGE_TOKEN_ID);
        Objects.requireNonNull(tokenId, "imageTokenId is required for VisionTextFusionStepRunner");
        this.imageTokenId = tokenId.intValue();
        this.initialized = true;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("VisionTextFusionStepRunner not initialized");
        }

        INDArray imageFeatures = null;
        INDArray textEmbeddings = null;
        INDArray inputIds = null;

        try {
            imageFeatures = VLMSameDiffUtils.toINDArray(
                    Objects.requireNonNull(input.getNDArray(VLMConstants.KEY_IMAGE_FEATURES),
                            "image_features is required"));
            textEmbeddings = VLMSameDiffUtils.toINDArray(
                    Objects.requireNonNull(input.getNDArray(VLMConstants.KEY_TEXT_EMBEDDINGS),
                            "text_embeddings is required"));
            inputIds = VLMSameDiffUtils.toINDArray(
                    Objects.requireNonNull(input.getNDArray(VLMConstants.KEY_INPUT_IDS),
                            "input_ids is required"));

            long textSeqLen = textEmbeddings.shape()[1];
            long hiddenSize = textEmbeddings.shape()[2];
            long imageSeqLen = imageFeatures.shape()[1];

            // Create a copy of text embeddings to modify
            INDArray fusedEmbeds = textEmbeddings.dup();

            // Get raw float arrays for manipulation
            float[] fusedData = fusedEmbeds.data().asFloat();
            float[] imageData = imageFeatures.data().asFloat();

            // Find image token positions and replace with vision features
            int imageFeatureIdx = 0;
            for (int pos = 0; pos < textSeqLen; pos++) {
                long tokenId = inputIds.getLong(0, pos);
                if (tokenId == this.imageTokenId && imageFeatureIdx < imageSeqLen) {
                    // Replace text embedding at this position with image feature
                    int textOffset = (int) (pos * hiddenSize);
                    int imageOffset = (int) (imageFeatureIdx * hiddenSize);
                    System.arraycopy(imageData, imageOffset, fusedData, textOffset, (int) hiddenSize);
                    imageFeatureIdx++;
                }
            }

            // Create result INDArray from modified data
            INDArray result = Nd4j.create(fusedData, new long[]{1, textSeqLen, hiddenSize}, 'c');
            fusedEmbeds.close();

            Data output = Data.empty();
            output.put(VLMConstants.KEY_INPUTS_EMBEDS, VLMSameDiffUtils.fromINDArray(result, VLMConstants.KEY_INPUTS_EMBEDS));

            return output;
        } finally {
            // Don't close arrays that came from input NDArrays (they may be reused)
            // Only close arrays we explicitly created
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        initialized = false;
    }
}
