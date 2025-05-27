/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anserini.encoder.samediff.sparse;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import io.anserini.encoder.samediff.tokenizer.SamediffBertVocabulary;
import org.jetbrains.annotations.NotNull;
// Removed @Nullable for path parameters
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
// Removed URISyntaxException
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UniCOIL Encoder using SameDiff (via ONNX import).
 * Refactored for Kompile Model Management. Model and vocabulary paths are provided externally.
 */
public class UniCoilSameDiffEncoder extends SameDiffSparseEncoder {
    private static final Logger LOG = LogManager.getLogger(UniCoilSameDiffEncoder.class);

    public static final String ENCODER_TYPE_ID = "unicoil-samediff"; // Logical ID

    // Tensor names for UniCOIL ONNX model (verify with your specific model)
    public static final String INPUT_IDS_TENSOR_NAME = "input_ids"; // Often "input_ids" for BERT-based
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    public static final String OUTPUT_TOKEN_LOGITS_TENSOR_NAME = "logits"; // Or "output_0", verify this

    // Default tokenizer and quantization settings
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;
    public static final int DEFAULT_WEIGHT_RANGE = 5;
    public static final int DEFAULT_QUANT_RANGE = 256;

    /**
     * Main constructor for UniCoilSameDiffEncoder.
     *
     * @param modelIdentifier             Unique identifier for this model instance.
     * @param kompileManagedOnnxModelPath Absolute path to the Kompile-managed ONNX model file.
     * @param kompileManagedVocabPath     Absolute path to the Kompile-managed vocabulary file.
     * @param doLowerCaseAndStripAccents  Tokenizer option.
     * @param maxSequenceLength           Tokenizer option.
     * @param addSpecialTokens            Tokenizer option.
     * @param weightRange                 Quantization parameter.
     * @param quantRange                  Quantization parameter.
     * @throws IOException If model or vocabulary loading fails.
     */
    public UniCoilSameDiffEncoder(@NotNull String modelIdentifier,
                                  @NotNull String kompileManagedOnnxModelPath,
                                  @NotNull String kompileManagedVocabPath,
                                  boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens,
                                  int weightRange, int quantRange)
            throws IOException {
        super(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                Collections.singletonList(OUTPUT_TOKEN_LOGITS_TENSOR_NAME),
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
                weightRange, quantRange);
        LOG.info("[{}] UniCOIL Encoder initialized. Max Seq Length: {}, Weight Range: {}, Quant Range: {}",
                modelIdentifier, maxSequenceLength, weightRange, quantRange);
    }

    /**
     * Simplified constructor using default tokenizer and quantization parameters.
     *
     * @param modelIdentifier             Identifier for the model.
     * @param kompileManagedOnnxModelPath Path to ONNX model from Kompile cache.
     * @param kompileManagedVocabPath     Path to vocabulary file from Kompile cache.
     * @throws IOException If loading fails.
     */
    public UniCoilSameDiffEncoder(@NotNull String modelIdentifier,
                                  @NotNull String kompileManagedOnnxModelPath,
                                  @NotNull String kompileManagedVocabPath) throws IOException {
        this(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS, DEFAULT_MAX_SEQUENCE_LENGTH, DEFAULT_ADD_SPECIAL_TOKENS,
                DEFAULT_WEIGHT_RANGE, DEFAULT_QUANT_RANGE);
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
            String outputName = this.outputTensorNamesFromModel.get(0);
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, outputName);
            INDArray computedLogits = outputMap.get(outputName);

            if (computedLogits == null) {
                LOG.warn("[{}] Output tensor '{}' not found for UniCOIL for query: '{}'", this.modelIdentifier, outputName, query);
                return Collections.emptyMap();
            }

            INDArray tokenActivations = computedLogits;
            if (tokenActivations.rank() == 3 && tokenActivations.shape()[0] == 1 && tokenActivations.shape()[2] == 1) {
                tokenActivations = tokenActivations.reshape(tokenActivations.shape()[0], tokenActivations.shape()[1]);
            } else if (tokenActivations.rank() == 2 && tokenActivations.shape()[0] == 1) {
                // Correct shape
            } else if (tokenActivations.rank() == 1 && tokenActivations.shape()[0] == encoding.inputIds.length) {
                tokenActivations = tokenActivations.reshape(1, tokenActivations.shape()[0]);
            } else {
                LOG.warn("[{}] Unexpected output tensor shape for UniCOIL: {}. Expected [1, seq_len] or [1, seq_len, 1]. Query: '{}'",
                        this.modelIdentifier, Arrays.toString(tokenActivations.shape()), query);
                return Collections.emptyMap();
            }

            INDArray reluOutput = Transforms.relu(tokenActivations, true);

            Map<String, Float> tokenWeightMap = new LinkedHashMap<>();
            SamediffBertVocabulary vocab = this.tokenizerPreProcessor.getVocabulary();

            int actualLength = 0;
            for (long id : encoding.inputIds) {
                if (id == vocab.getTokenId(SamediffBertVocabulary.PAD_TOKEN)) break;
                actualLength++;
            }

            int iterLength = Math.min(actualLength, (int)reluOutput.columns());

            for (int i = 0; i < iterLength; ++i) {
                String token = vocab.getToken((int) encoding.inputIds[i]);
                if (token.equals(SamediffBertVocabulary.CLS_TOKEN) ||
                        token.equals(SamediffBertVocabulary.SEP_TOKEN) ||
                        token.equals(SamediffBertVocabulary.PAD_TOKEN) ||
                        // Use the instance's actual unknown token value for comparison
                        token.equals(vocab.getUnknownTokenValue())) {
                    continue;
                }

                float weight = reluOutput.getFloat(0, i);
                if (weight > 1e-5) {
                    tokenWeightMap.merge(token, weight, Float::sum);
                }
            }
            return tokenWeightMap;

        } catch (Exception e) {
            LOG.error("[{}] Error during UniCOIL SameDiff encoding for query: '{}'", this.modelIdentifier, query, e);
            return Collections.emptyMap();
        }
    }
}