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

// File: getkompile/kompile/kompile-ag_new_kompile_cli/anserini/src/main/java/io/anserini/encoder/samediff/sparse/UniCoilSameDiffEncoder.java
package io.anserini.encoder.samediff.sparse;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import io.anserini.encoder.samediff.tokenizer.SamediffBertVocabulary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;

public class UniCoilSameDiffEncoder extends SameDiffSparseEncoder {
    private static final Logger LOG = LogManager.getLogger(UniCoilSameDiffEncoder.class);

    public static final String DEFAULT_MODEL_NAME = "unicoil.sd";
    public static final String DEFAULT_MODEL_URL = "https://PLACEHOLDER_URL/unicoil.sd"; // Replace
    public static final String DEFAULT_VOCAB_NAME = "unicoil-vocab.txt";
    public static final String DEFAULT_VOCAB_URL = "https://rgw.cs.uwaterloo.ca/pyserini/data/wordpiece-vocab.txt";

    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    public static final String OUTPUT_TOKEN_WEIGHTS_TENSOR_NAME = "output_token_weights";

    // Default tokenizer settings for this specific encoder
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;
    public static final int DEFAULT_WEIGHT_RANGE = 5; // Typical for UniCOIL
    public static final int DEFAULT_QUANT_RANGE = 255; // Typical for UniCOIL

    public UniCoilSameDiffEncoder() throws IOException, URISyntaxException {
        this(null, null);
    }

    public UniCoilSameDiffEncoder(@Nullable String modelPath, @Nullable String vocabPath) throws IOException, URISyntaxException {
        this(DEFAULT_MODEL_NAME, modelPath == null ? DEFAULT_MODEL_URL : null,
                DEFAULT_VOCAB_NAME, vocabPath == null ? DEFAULT_VOCAB_URL : null,
                modelPath, vocabPath,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS, DEFAULT_MAX_SEQUENCE_LENGTH, DEFAULT_ADD_SPECIAL_TOKENS,
                DEFAULT_WEIGHT_RANGE, DEFAULT_QUANT_RANGE);
    }

    public UniCoilSameDiffEncoder(@NotNull String modelName, @Nullable String modelUrl,
                                  @NotNull String vocabName, @Nullable String vocabUrl,
                                  @Nullable String providedModelPath, @Nullable String providedVocabPath,
                                  boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens,
                                  int weightRange, int quantRange)
            throws IOException, URISyntaxException {
        super(modelName, modelUrl, vocabName, vocabUrl,
                providedModelPath, providedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                Collections.singletonList(OUTPUT_TOKEN_WEIGHTS_TENSOR_NAME),
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
                weightRange, quantRange);
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
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray computedLogits = outputMap.get(this.outputTensorNamesFromModel.get(0));

            if (computedLogits == null) {
                LOG.warn("Output tensor '{}' not found for UniCOIL for query: {}", this.outputTensorNamesFromModel.get(0), query);
                return Collections.emptyMap();
            }

            INDArray tokenActivations = computedLogits;
            if (tokenActivations.rank() == 3 && tokenActivations.shape()[2] == 1) {
                tokenActivations = tokenActivations.reshape(tokenActivations.shape()[0], tokenActivations.shape()[1]);
            } else if (tokenActivations.rank() != 2 || tokenActivations.shape()[0] != 1) {
                LOG.warn("Unexpected output tensor shape for UniCoil: {}", Arrays.toString(tokenActivations.shape()));
                return Collections.emptyMap();
            }

            INDArray reluOutput = Transforms.relu(tokenActivations, true);

            Map<String, Float> tokenWeightMap = new LinkedHashMap<>();
            SamediffBertVocabulary vocab = this.tokenizerPreProcessor.getVocabulary();

            int actualLength = 0;
            for(long id : encoding.inputIds){
                if(id != vocab.getTokenId(SamediffBertVocabulary.PAD_TOKEN)) actualLength++; else break;
            }

            for (int i = 0; i < actualLength; ++i) {
                if (i >= reluOutput.columns()) break;

                String token = vocab.getToken((int)encoding.inputIds[i]);
                if (token.equals(SamediffBertVocabulary.CLS_TOKEN) ||
                        token.equals(SamediffBertVocabulary.SEP_TOKEN) ||
                        token.equals(SamediffBertVocabulary.PAD_TOKEN)) {
                    continue;
                }

                float weight = reluOutput.getFloat(0, i);
                if (weight > 0) {
                    tokenWeightMap.merge(token, weight, Float::sum);
                }
            }
            return tokenWeightMap;

        } catch (Exception e) {
            LOG.error("Error during UniCoil SameDiff encoding for query: " + query, e);
            return Collections.emptyMap();
        }
    }
}