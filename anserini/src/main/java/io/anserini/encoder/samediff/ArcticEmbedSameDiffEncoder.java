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
import org.nd4j.linalg.ops.transforms.Transforms;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ArcticEmbedSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(ArcticEmbedSameDiffEncoder.class);

    private static final String INSTRUCTION = "Represent this sentence for searching relevant passages: ";

    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "last_hidden_state";

    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    public ArcticEmbedSameDiffEncoder(@NotNull String modelIdentifier,
                                      @NotNull String kompileManagedModelPath,
                                      @NotNull String kompileManagedVocabPath,
                                      boolean doLowerCaseAndStripAccents,
                                      int maxSequenceLength,
                                      boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME),
                Collections.singletonList(OUTPUT_EMBEDDING_TENSOR_NAME),
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        LOG.info("[{}] ArcticEmbedSameDiffEncoder initialized.", modelIdentifier);
    }

    // Simplified constructor using defaults
    public ArcticEmbedSameDiffEncoder(@NotNull String modelIdentifier,
                                      @NotNull String kompileManagedModelPath,
                                      @NotNull String kompileManagedVocabPath) throws IOException {
        this(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS);
    }

    @Override
    public float[] encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(INSTRUCTION + query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(1), tokenTypeIdsArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(2), attentionMaskArr);

        try {
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray hiddenStates = outputMap.get(this.outputTensorNamesFromModel.get(0));

            if (hiddenStates == null) {
                LOG.error("[{}] Output tensor '{}' not found for ArcticEmbed. Query: '{}'", this.modelIdentifier, this.outputTensorNamesFromModel.get(0), query);
                return null;
            }

            if (hiddenStates.rank() != 3 || hiddenStates.shape()[0] != 1) {
                LOG.warn("[{}] Unexpected ArcticEmbed hiddenStates tensor shape: {}. Query: '{}'", this.modelIdentifier, Arrays.toString(hiddenStates.shape()), query);
                if(hiddenStates.rank() == 2 && hiddenStates.shape()[0] == inputIdsArr.shape()[1]) {
                    hiddenStates = hiddenStates.reshape(1, hiddenStates.shape()[0], hiddenStates.shape()[1]);
                } else if (hiddenStates.rank() == 2 && hiddenStates.shape()[0] == 1) {
                    INDArray finalEmbedding = hiddenStates;
                    INDArray norm = finalEmbedding.norm2(true, 1);
                    INDArray epsilon = Nd4j.scalar(finalEmbedding.dataType(), 1e-12f);
                    norm = Transforms.max(norm, epsilon);
                    INDArray normalizedEmbedding = finalEmbedding.divi(norm);
                    return normalizedEmbedding.toFloatVector();
                }
                else { return null; }
            }

            long currentSequenceLength = attentionMaskArr.shape()[1];
            INDArray expandedAttentionMask = attentionMaskArr.reshape(1, currentSequenceLength, 1).castTo(hiddenStates.dataType());
            INDArray maskedHiddenStates = hiddenStates.mul(expandedAttentionMask);
            INDArray sumHiddenStates = maskedHiddenStates.sum(true, 1);
            INDArray sumAttentionMask = expandedAttentionMask.sum(true, 1);
            sumAttentionMask = Transforms.max(sumAttentionMask, Nd4j.scalar(sumAttentionMask.dataType(), 1e-9));
            INDArray meanPooled = sumHiddenStates.divi(sumAttentionMask);
            INDArray finalEmbedding = meanPooled.reshape(1, -1);

            INDArray norm = finalEmbedding.norm2(true, 1);
            INDArray epsilon = Nd4j.scalar(finalEmbedding.dataType(), 1e-12f);
            norm = Transforms.max(norm, epsilon);

            INDArray normalizedEmbedding = finalEmbedding.divi(norm);
            return normalizedEmbedding.toFloatVector();

        } catch (Exception e) {
            LOG.error("[{}] Error during ArcticEmbed encoding for query: '{}'", this.modelIdentifier, query, e);
            return null;
        }
    }
}