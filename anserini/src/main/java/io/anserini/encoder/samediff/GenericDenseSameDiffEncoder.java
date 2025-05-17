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

package io.anserini.encoder.samediff;

// import io.anserini.encoder.Encoder;
import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.jetbrains.annotations.NotNull;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A generic dense encoder using SameDiff, assuming BERT-like input and CLS token embedding output.
 * Subclasses can override post-processing if needed.
 */
// public class GenericDenseSameDiffEncoder extends SameDiffEncoder<float[]> implements Encoder {
public class GenericDenseSameDiffEncoder extends SameDiffEncoder<float[]> {

    // Default tensor names, can be overridden by constructor if specific model differs.
    public static final String DEFAULT_INPUT_IDS_NAME = "input_ids";
    public static final String DEFAULT_ATTENTION_MASK_NAME = "attention_mask";
    public static final String DEFAULT_TOKEN_TYPE_IDS_NAME = "token_type_ids";
    public static final String DEFAULT_OUTPUT_NAME = "last_hidden_state"; // Common output for BERT hidden states

    /**
     * Constructor for the generic dense encoder.
     *
     * @param modelName                  Filename of the SameDiff model.
     * @param modelUrl                   URL to download the SameDiff model.
     * @param vocabName                  Filename of the vocabulary (e.g., "vocab.txt").
     * @param vocabUrl                   URL to download the vocabulary.
     * @param inputTensorNamesForModel   List of input tensor names for the SameDiff model.
     * Order should match how INDArrays will be mapped.
     * Example: ["input_ids", "attention_mask", "token_type_ids"]
     * @param outputTensorNameFromModel  Name of the output tensor to fetch (e.g., "last_hidden_state").
     * @param doLowerCaseAndStripAccents Whether to lowercase text and strip accents.
     * @param maxSequenceLength          Maximum sequence length for tokenization.
     * @param addSpecialTokens           Whether to add [CLS] and [SEP] tokens.
     * @throws IOException        If model or vocab loading fails.
     * @throws URISyntaxException If URLs are malformed.
     */
    public GenericDenseSameDiffEncoder(@NotNull String modelName, @NotNull String modelUrl,
                                       @NotNull String vocabName, @NotNull String vocabUrl,
                                       @NotNull List<String> inputTensorNamesForModel,
                                       @NotNull String outputTensorNameFromModel,
                                       boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens)
            throws IOException, URISyntaxException {
        super(modelName, modelUrl,
                vocabName, vocabUrl,
                inputTensorNamesForModel,
                Collections.singletonList(outputTensorNameFromModel), // Assuming one primary output for embedding
                doLowerCaseAndStripAccents,
                maxSequenceLength,
                addSpecialTokens);
    }


    @Override
    public Map<String, Integer> encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();

        // Map based on the order provided in inputTensorNamesForModel
        if (this.inputTensorNamesForModel.contains(DEFAULT_INPUT_IDS_NAME)) {
            placeholderMap.put(DEFAULT_INPUT_IDS_NAME, inputIdsArr);
        } else if (!this.inputTensorNamesForModel.isEmpty()){ // Fallback to first name if specific not found
            placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        }


        if (this.inputTensorNamesForModel.contains(DEFAULT_ATTENTION_MASK_NAME)) {
            placeholderMap.put(DEFAULT_ATTENTION_MASK_NAME, attentionMaskArr);
        } else if (this.inputTensorNamesForModel.size() > 1){
            placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
        }

        if (this.inputTensorNamesForModel.contains(DEFAULT_TOKEN_TYPE_IDS_NAME)) {
            placeholderMap.put(DEFAULT_TOKEN_TYPE_IDS_NAME, tokenTypeIdsArr);
        } else if (this.inputTensorNamesForModel.size() > 2){
            placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);
        }


        try {
            // outputTensorNamesFromModel is a list, get(0) for the primary output
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray embeddingTensor = outputMap.get(this.outputTensorNamesFromModel.get(0));

            if (embeddingTensor == null) {
                return null;
            }

            return processOutputTensor(embeddingTensor);

        } catch (Exception e) {
            // throw new RuntimeException("Error during SameDiff encoding for query: " + query, e);
            return null;
        }
    }

    /**
     * Processes the raw output tensor from the SameDiff model to extract the final embedding.
     * Default implementation assumes CLS token embedding from a [batch, seq_len, hidden_dim] tensor
     * or a [batch, hidden_dim] tensor, followed by L2 normalization.
     * Subclasses can override this for model-specific output handling.
     *
     * @param embeddingTensor The raw output tensor from the model.
     * @return The processed float[] embedding.
     */
    protected float[] processOutputTensor(INDArray embeddingTensor) {
        INDArray clsEmbedding;
        if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
            clsEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all()); // CLS token
        } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
            clsEmbedding = embeddingTensor.getRow(0); // Already [1, hidden_size] or taking the first if batch > 1
        } else if (embeddingTensor.rank() == 1) { // If model directly outputs [hidden_size]
            clsEmbedding = embeddingTensor;
        }
        else {
            // System.err.println("Unexpected embedding tensor shape: " + Arrays.toString(embeddingTensor.shape()) + ". Cannot process.");
            return null;
        }

        clsEmbedding = clsEmbedding.reshape(1, -1); // Ensure [1, hidden_size] for normalization

        INDArray norm = clsEmbedding.norm2(true, 1);
        if(norm.isScalar() && norm.getDouble(0) == 0.0) {
            norm = Nd4j.scalar(clsEmbedding.dataType(),1e-12);
        } else if (!norm.isScalar()) {
            norm.addi(1e-12);
        }
        INDArray normalizedEmbedding = clsEmbedding.divi(norm);

        return normalizedEmbedding.toFloatVector();
    }
}