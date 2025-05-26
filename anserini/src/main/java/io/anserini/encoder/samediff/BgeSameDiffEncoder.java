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

package io.anserini.encoder.samediff;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nd4j.linalg.ops.transforms.Transforms;


import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BgeSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(BgeSameDiffEncoder.class);

    // --- UPDATED CONSTANTS from original BgeBaseEn15Encoder ---
    public static final String DEFAULT_MODEL_NAME = "bge-base-en-v1.5-optimized.onnx";
    public static final String DEFAULT_MODEL_URL = "https://rgw.cs.uwaterloo.ca/pyserini/data/bge-base-en-v1.5-optimized.onnx";
    public static final String DEFAULT_VOCAB_NAME = "bge-base-en-v1.5-vocab.txt";
    public static final String DEFAULT_VOCAB_URL = "https://rgw.cs.uwaterloo.ca/pyserini/data/bge-base-en-v1.5-vocab.txt";
    private static final String INSTRUCTION = "Represent this sentence for searching relevant passages: ";
    // --- END UPDATED CONSTANTS ---

    // Input and Output tensor names for the ONNX model
    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "last_hidden_state"; // Matches original BgeBaseEn15Encoder

    // Default tokenizer settings
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512; // Matches original BgeBaseEn15Encoder
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    public BgeSameDiffEncoder() throws IOException, URISyntaxException {
        this(null, null);
    }

    public BgeSameDiffEncoder(@Nullable String modelPath, @Nullable String vocabPath) throws IOException, URISyntaxException {
        this(DEFAULT_MODEL_NAME, modelPath == null ? DEFAULT_MODEL_URL : null,
                DEFAULT_VOCAB_NAME, vocabPath == null ? DEFAULT_VOCAB_URL : null,
                modelPath, vocabPath,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS);
    }

    public BgeSameDiffEncoder(@NotNull String modelName, @Nullable String modelUrl,
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
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(INSTRUCTION + query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);

        try {
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray embeddingTensor = outputMap.get(this.outputTensorNamesFromModel.get(0));

            if (embeddingTensor == null) {
                LOG.error("Output tensor '{}' not found in SameDiff model output for query: {}", this.outputTensorNamesFromModel.get(0), query);
                return null;
            }

            // BGE models use the embedding of the [CLS] token (first token) from the last_hidden_state.
            INDArray clsEmbedding;
            if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
                clsEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
            } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) { // If already [1, hidden_dim]
                clsEmbedding = embeddingTensor;
            } else {
                LOG.warn("Unexpected embedding tensor shape for BGE: {}. Expected [1, seq_len, hidden_dim] or [1, hidden_dim]. Attempting to use first row/vector.", Arrays.toString(embeddingTensor.shape()));
                clsEmbedding = embeddingTensor.reshape(1, -1).getRow(0); // Attempt to recover
            }
            clsEmbedding = clsEmbedding.reshape(1, -1); // Ensure [1, hidden_dim] for normalization

            // L2 Normalize (as done in original BgeBaseEn15Encoder)
            INDArray norm = clsEmbedding.norm2(true, 1);
            INDArray epsilon = Nd4j.scalar(clsEmbedding.dataType(), 1e-12f);
            norm = Transforms.max(norm, epsilon); // Add epsilon to prevent division by zero

            INDArray normalizedEmbedding = clsEmbedding.divi(norm);
            return normalizedEmbedding.toFloatVector();

        } catch (Exception e) {
            LOG.error("Error during SameDiff BGE encoding for query: " + query, e);
            return null;
        }
    }
}