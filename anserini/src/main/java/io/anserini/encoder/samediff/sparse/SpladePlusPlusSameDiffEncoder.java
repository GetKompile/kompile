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

// File: getkompile/kompile/kompile-ag_new_kompile_cli/anserini/src/main/java/io/anserini/encoder/samediff/sparse/SpladePlusPlusSameDiffEncoder.java
package io.anserini.encoder.samediff.sparse;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import io.anserini.encoder.samediff.tokenizer.SamediffBertVocabulary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class SpladePlusPlusSameDiffEncoder extends SameDiffSparseEncoder {
    private static final Logger LOG = LogManager.getLogger(SpladePlusPlusSameDiffEncoder.class);

    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    public static final String OUTPUT_IDX_TENSOR_NAME = "output_idx";
    public static final String OUTPUT_WEIGHTS_TENSOR_NAME = "output_weights";

    // Default tokenizer settings, can be overridden by specific SPLADE variants or CLI args
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;
    public static final int DEFAULT_WEIGHT_RANGE = 10; // Example, may need tuning
    public static final int DEFAULT_QUANT_RANGE = 256;  // Example, may need tuning


    protected final SamediffBertVocabulary anseriniVocabulary;

    protected SpladePlusPlusSameDiffEncoder(
            @NotNull String modelName, @Nullable String modelUrl,
            @NotNull String vocabName, @Nullable String vocabUrl,
            @Nullable String providedModelPath, @Nullable String providedVocabPath,
            boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens,
            int weightRange, int quantRange)
            throws IOException, URISyntaxException {
        super(modelName, modelUrl, vocabName, vocabUrl,
                providedModelPath, providedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                List.of(OUTPUT_IDX_TENSOR_NAME, OUTPUT_WEIGHTS_TENSOR_NAME),
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
                weightRange, quantRange);
        this.anseriniVocabulary = this.tokenizerPreProcessor.getVocabulary();
    }


    @Override
    public Map<String, Float> encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);

        try {
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel);

            INDArray indexesTensor = outputMap.get(OUTPUT_IDX_TENSOR_NAME);
            INDArray weightsTensor = outputMap.get(OUTPUT_WEIGHTS_TENSOR_NAME);

            if (indexesTensor == null || weightsTensor == null) {
                LOG.warn("Output tensors for SPLADE++ (indices or weights) are null for query: {}", query);
                return Collections.emptyMap();
            }

            long[] indexes = indexesTensor.toLongVector();
            float[] weights = weightsTensor.toFloatVector();

            if (indexes.length != weights.length) {
                LOG.error("Mismatch between length of index tensor ({}) and weight tensor ({}) for query: {}",
                        indexes.length, weights.length, query);
                return Collections.emptyMap();
            }

            Map<String, Float> tokenFloatWeights = new LinkedHashMap<>();
            for (int i = 0; i < indexes.length; i++) {
                String token = this.anseriniVocabulary.getToken((int) indexes[i]);
                if (token.equals(SamediffBertVocabulary.CLS_TOKEN) ||
                        token.equals(SamediffBertVocabulary.SEP_TOKEN) ||
                        token.equals(SamediffBertVocabulary.PAD_TOKEN)) {
                    continue;
                }
                tokenFloatWeights.merge(token, weights[i], Float::sum);
            }
            return tokenFloatWeights;

        } catch (Exception e) {
            LOG.error("Error during SPLADE SameDiff encoding for query: " + query, e);
            return Collections.emptyMap();
        }
    }
}