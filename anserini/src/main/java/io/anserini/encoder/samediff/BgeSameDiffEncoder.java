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

// Assuming Anserini might have a common Encoder interface, else remove 'implements Encoder'
// import io.anserini.encoder.Encoder; 
import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.jetbrains.annotations.NotNull;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// public class BgeSameDiffEncoder extends SameDiffEncoder<float[]> implements Encoder { // If Encoder interface exists
public class BgeSameDiffEncoder extends SameDiffEncoder<float[]> {

    public static final String DEFAULT_MODEL_NAME = "bge-base-en-v1.5.sd";
    public static final String DEFAULT_MODEL_URL = "YOUR_MODEL_URL/bge-base-en-v1.5.sd"; // MUST BE REPLACED
    public static final String DEFAULT_VOCAB_NAME = "bge-base-en-v1.5-vocab.txt";
    public static final String DEFAULT_VOCAB_URL = "https://huggingface.co/BAAI/bge-base-en-v1.5/resolve/main/vocab.txt";

    // These names must match the expected input names for the SameDiff model graph
    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";

    // This name must match the output tensor from the SameDiff model graph
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "last_hidden_state";


    public BgeSameDiffEncoder(boolean doLowerCaseAndStripAccents) throws IOException, URISyntaxException {
        super(DEFAULT_MODEL_NAME, DEFAULT_MODEL_URL,
                DEFAULT_VOCAB_NAME, DEFAULT_VOCAB_URL,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                Collections.singletonList(OUTPUT_EMBEDDING_TENSOR_NAME),
                doLowerCaseAndStripAccents,
                512,
                true);
    }

    // Default constructor
    public BgeSameDiffEncoder() throws IOException, URISyntaxException {
        // BGE models are typically uncased, but some variants might be cased.
        // Set doLowerCaseAndStripAccents to true for uncased models.
        this(true);
    }

    @Override
    public float[] encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);


        Map<String, INDArray> placeholderMap = new HashMap<>();
        // Ensure the keys match what inputTensorNamesForModel was set to in the super constructor
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        if (this.inputTensorNamesForModel.size() > 1) {
            placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
        }
        if (this.inputTensorNamesForModel.size() > 2) { // If token_type_ids is expected
            placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);
        }


        try {
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray embeddingTensor = outputMap.get(this.outputTensorNamesFromModel.get(0));

            if (embeddingTensor == null) {
                // System.err.println("Output tensor '" + this.outputTensorNamesFromModel.get(0) + "' not found in SameDiff model output.");
                return null;
            }

            INDArray clsEmbedding;
            // BGE uses the embedding of the [CLS] token (first token)
            if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
                clsEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
            } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
                clsEmbedding = embeddingTensor; // Assumes output is already [1, hidden_size]
            } else {
                // System.err.println("Unexpected embedding tensor shape: " + Arrays.toString(embeddingTensor.shape()) + ". Attempting to use first row/vector.");
                clsEmbedding = embeddingTensor.reshape(1, -1).getRow(0); // Best effort
            }
            clsEmbedding = clsEmbedding.reshape(1, -1); // Ensure [1, hidden_size]


            INDArray norm = clsEmbedding.norm2(true, 1); // L2 norm along the feature dimension
            if(norm.isScalar() && norm.getDouble(0) == 0.0) { // scalar norm can be zero
                norm = Nd4j.scalar(DataType.FLOAT, 1e-12); // Use same datatype as clsEmbedding or float
            } else if (!norm.isScalar()) { // vector norm
                norm.addi(1e-12); // Add epsilon to all elements of norm to avoid division by zero
            }
            // Ensure 'norm' is broadcastable for division, diviColumnVector might be better if norm becomes a column vector for some reason
            INDArray normalizedEmbedding = clsEmbedding.divi(norm); // Element-wise division

            return normalizedEmbedding.toFloatVector();

        } catch (Exception e) {
            // e.printStackTrace(); // Removed logging
            // Consider how to handle errors without logging if they are critical
            // For now, returning null or throwing a RuntimeException might be options.
            // throw new RuntimeException("Error during SameDiff encoding for query: " + query, e);
            return null; // Or throw an unchecked exception
        }
    }
}