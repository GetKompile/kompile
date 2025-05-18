/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 */

package io.anserini.encoder.samediff;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
// Removed NDArrayIndex as it might not be needed if pooler_output is directly used
// import org.nd4j.linalg.indexing.NDArrayIndex;
// import org.nd4j.linalg.ops.transforms.Transforms; // Normalization not done in original

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CosDprDistilSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(CosDprDistilSameDiffEncoder.class);

    // --- UPDATED CONSTANTS from original CosDprDistilEncoder ---
    public static final String DEFAULT_MODEL_NAME = "cosdpr-distil-optimized.onnx";
    public static final String DEFAULT_MODEL_URL = "https://rgw.cs.uwaterloo.ca/pyserini/data/cosdpr-distil-optimized.onnx";
    public static final String DEFAULT_VOCAB_NAME = "cosdpr-distil-vocab.txt";
    public static final String DEFAULT_VOCAB_URL = "https://rgw.cs.uwaterloo.ca/pyserini/data/cosdpr-distil-vocab.txt";
    // --- END UPDATED CONSTANTS ---

    // Input and Output tensor names for the ONNX model
    public static final String INPUT_IDS_TENSOR_NAME = "input_ids"; // Matches original CosDprDistilEncoder
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask"; // Typically used by BERT models
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids"; // Typically used by BERT models
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "pooler_output"; // Matches original CosDprDistilEncoder

    // Default tokenizer settings
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512; // Original example did not specify, 512 is common
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    public CosDprDistilSameDiffEncoder() throws IOException, URISyntaxException {
        this(null, null);
    }

    public CosDprDistilSameDiffEncoder(@Nullable String modelPath, @Nullable String vocabPath) throws IOException, URISyntaxException {
        this(DEFAULT_MODEL_NAME, modelPath == null ? DEFAULT_MODEL_URL : null,
                DEFAULT_VOCAB_NAME, vocabPath == null ? DEFAULT_VOCAB_URL : null,
                modelPath, vocabPath,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS);
    }

    public CosDprDistilSameDiffEncoder(@NotNull String modelName, @Nullable String modelUrl,
                                       @NotNull String vocabName, @Nullable String vocabUrl,
                                       @Nullable String providedModelPath, @Nullable String providedVocabPath,
                                       boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens)
            throws IOException, URISyntaxException {
        super(modelName, modelUrl, vocabName, vocabUrl,
                providedModelPath, providedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                Collections.singletonList(OUTPUT_EMBEDDING_TENSOR_NAME),
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
    }

    @Override
    public float[] encode(@NotNull String query) {
        // Original CosDprDistilEncoder does not prepend an instruction
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);

        try {
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray embeddingTensor = outputMap.get(this.outputTensorNamesFromModel.get(0)); // Should be "pooler_output"

            if (embeddingTensor == null) {
                LOG.error("Output tensor '{}' not found in SameDiff model output for query: {}", this.outputTensorNamesFromModel.get(0), query);
                return null;
            }

            // Original CosDprDistilEncoder (ONNX Runtime) returns ((float[][]) results.get(MODEL_POOLER_OUTPUT).get().getValue())[0];
            // This implies the pooler_output is already [batch_size, hidden_dim] and takes the first item of the batch.
            // No normalization is applied in the original.
            if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
                return embeddingTensor.toFloatVector(); // Assumes [1, hidden_dim]
            } else if (embeddingTensor.rank() == 1) { // If output is already [hidden_dim]
                return embeddingTensor.toFloatVector();
            } else {
                LOG.warn("Unexpected embedding tensor shape for CosDPR: {}. Expected [1, hidden_dim] or [hidden_dim]. Attempting to flatten.", Arrays.toString(embeddingTensor.shape()));
                return embeddingTensor.reshape(1, -1).toFloatVector(); // Attempt to flatten and return
            }

        } catch (Exception e) {
            LOG.error("Error during SameDiff CosDPR encoding for query: " + query, e);
            return null;
        }
    }
}