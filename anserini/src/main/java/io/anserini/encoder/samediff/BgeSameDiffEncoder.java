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
import org.jetbrains.annotations.Nullable; // For optional instruction
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nd4j.linalg.ops.transforms.Transforms;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BgeSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(BgeSameDiffEncoder.class);

    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "last_hidden_state";

    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;
    public static final boolean DEFAULT_NORMALIZE = true; // BGE typically normalizes

    private final String instruction;
    private final boolean normalizeEmbeddings;

    public BgeSameDiffEncoder(@NotNull String modelIdentifier,
                              @NotNull String kompileManagedOnnxModelPath,
                              @NotNull String kompileManagedVocabPath,
                              @Nullable String instruction, // instruction can be null or empty
                              boolean normalizeEmbeddings,
                              boolean doLowerCaseAndStripAccents,
                              int maxSequenceLength,
                              boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME), // Use defined constants
                Collections.singletonList(OUTPUT_EMBEDDING_TENSOR_NAME), // Use defined constant
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        this.instruction = (instruction == null || instruction.trim().isEmpty()) ? "" : instruction.trim() + " ";
        this.normalizeEmbeddings = normalizeEmbeddings;
        LOG.info("[{}] BGE Encoder initialized. Instruction prefix: '{}', Normalize: {}",
                modelIdentifier, this.instruction.isEmpty() ? "none" : this.instruction.trim(), this.normalizeEmbeddings);
    }

    // Simplified constructor using BGE defaults
    public BgeSameDiffEncoder(@NotNull String modelIdentifier,
                              @NotNull String kompileManagedOnnxModelPath,
                              @NotNull String kompileManagedVocabPath,
                              @Nullable String instruction,
                              boolean normalizeEmbeddings) throws IOException {
        this(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                instruction, normalizeEmbeddings,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS);
    }


    @Override
    public float[] encode(@NotNull String query) {
        String textToEncode = this.instruction + query;
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(textToEncode);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);

        try {
            String outputName = this.outputTensorNamesFromModel.get(0);
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, outputName);
            INDArray embeddingTensor = outputMap.get(outputName);

            if (embeddingTensor == null) {
                LOG.error("[{}] Output tensor '{}' not found for BGE. Query: '{}'", this.modelIdentifier, outputName, query);
                return null;
            }

            INDArray clsEmbedding;
            if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
                clsEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
            } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
                clsEmbedding = embeddingTensor.getRow(0);
            } else {
                LOG.warn("[{}] Unexpected BGE embedding tensor shape: {}. Query: '{}'", this.modelIdentifier, Arrays.toString(embeddingTensor.shape()), query);
                if (embeddingTensor.rank() == 1) clsEmbedding = embeddingTensor;
                else if (embeddingTensor.rank() == 2) clsEmbedding = embeddingTensor.getRow(0);
                else return null;
            }
            clsEmbedding = clsEmbedding.reshape(1, -1);

            if (this.normalizeEmbeddings) {
                INDArray norm = clsEmbedding.norm2(true, 1);
                INDArray epsilon = Nd4j.scalar(clsEmbedding.dataType(), 1e-12f);
                norm = Transforms.max(norm, epsilon);
                INDArray normalizedEmbedding = clsEmbedding.divi(norm);
                return normalizedEmbedding.toFloatVector();
            } else {
                return clsEmbedding.toFloatVector();
            }
        } catch (Exception e) {
            LOG.error("[{}] Error during BGE encoding for query: '{}'", this.modelIdentifier, query, e);
            return null;
        }
    }
}