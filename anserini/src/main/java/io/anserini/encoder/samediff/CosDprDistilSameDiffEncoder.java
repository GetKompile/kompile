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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CosDprDistilSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(CosDprDistilSameDiffEncoder.class);

    // Define specific tensor names for this model
    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "pooler_output";

    // Default tokenizer settings specific to CosDprDistil
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    public CosDprDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                       @NotNull String kompileManagedModelPath,
                                       @NotNull String kompileManagedVocabPath,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                Collections.singletonList(OUTPUT_EMBEDDING_TENSOR_NAME),
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        LOG.info("[{}] CosDprDistilSameDiffEncoder initialized.", modelIdentifier);
    }

    // Simplified constructor using defaults
    public CosDprDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                       @NotNull String kompileManagedModelPath,
                                       @NotNull String kompileManagedVocabPath) throws IOException {
        this(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS);
    }


    @Override
    public float[] encode(@NotNull String query) {
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
            INDArray embeddingTensor = outputMap.get(this.outputTensorNamesFromModel.get(0));

            if (embeddingTensor == null) {
                LOG.error("[{}] Output tensor '{}' not found for CosDPR. Query: '{}'", this.modelIdentifier, this.outputTensorNamesFromModel.get(0), query);
                return null;
            }

            if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
                return embeddingTensor.toFloatVector();
            } else if (embeddingTensor.rank() == 1) {
                return embeddingTensor.toFloatVector();
            } else {
                LOG.warn("[{}] Unexpected CosDPR embedding tensor shape: {}. Query: '{}'", this.modelIdentifier, Arrays.toString(embeddingTensor.shape()), query);
                return embeddingTensor.reshape(1, -1).toFloatVector();
            }

        } catch (Exception e) {
            LOG.error("[{}] Error during CosDPR encoding for query: '{}'", this.modelIdentifier, query, e);
            return null;
        }
    }
}