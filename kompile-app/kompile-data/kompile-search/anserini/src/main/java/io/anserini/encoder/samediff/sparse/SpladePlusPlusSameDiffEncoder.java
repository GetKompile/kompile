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

package io.anserini.encoder.samediff.sparse;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import io.anserini.encoder.samediff.tokenizer.SamediffBertVocabulary;
import org.jetbrains.annotations.NotNull;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;

import java.io.IOException;
import java.util.Arrays;
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
    public static final String OUTPUT_LOGITS_TENSOR_NAME = "logits";

    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 256;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    public static final int DEFAULT_SPLADE_WEIGHT_RANGE = 10; // From original SearchCollection
    public static final int DEFAULT_SPLADE_QUANT_RANGE = 256; // From original SearchCollection

    protected final SamediffBertVocabulary anseriniVocabulary;

    protected SpladePlusPlusSameDiffEncoder(
            @NotNull String modelIdentifier,
            @NotNull String kompileManagedOnnxModelPath,
            @NotNull String kompileManagedVocabPath,
            // Subclasses will provide their specific default tensor names here
            @NotNull List<String> inputTensorNames,
            @NotNull List<String> outputTensorNames,
            boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens,
            int weightRange, int quantRange)
            throws IOException {
        super(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                inputTensorNames, outputTensorNames,
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
                weightRange, quantRange);
        this.anseriniVocabulary = this.tokenizerPreProcessor.getVocabulary(); // Get vocab from initialized preprocessor
        LOG.info("[{}] SPLADE++ Type Encoder initialized. Max Seq Length: {}", modelIdentifier, maxSequenceLength);
    }

    @Override
    public Map<String, Float> encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        // Track all arrays that need cleanup to prevent off-heap memory leaks
        INDArray inputIdsArr = null;
        INDArray attentionMaskArr = null;
        INDArray tokenTypeIdsArr = null;
        Map<String, INDArray> outputMap = null;
        INDArray logits = null;

        try {
            // CRITICAL: Create 2D array directly to avoid intermediate array leak from castTo()
            inputIdsArr = Nd4j.createFromArray(new long[][]{encoding.inputIds});
            attentionMaskArr = Nd4j.createFromArray(new long[][]{encoding.attentionMask});
            tokenTypeIdsArr = Nd4j.createFromArray(new long[][]{encoding.tokenTypeIds});

            Map<String, INDArray> placeholderMap = new HashMap<>();
            placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
            placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
            if (this.inputTensorNamesForModel.size() > 2) {
                placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);
            }

            String logitsOutputName = this.outputTensorNamesFromModel.get(0);
            outputMap = this.sameDiffModel.output(placeholderMap, logitsOutputName);
            logits = outputMap.get(logitsOutputName);

            if (logits == null) {
                LOG.warn("[{}] Logits tensor '{}' is null for SPLADE++ for query: '{}'", this.modelIdentifier, logitsOutputName, query);
                return Collections.emptyMap();
            }

            return processLogits(logits, encoding, query);
        } catch (Exception e) {
            LOG.error("[{}] Error during SPLADE++ encoding for query: '{}'", this.modelIdentifier, query, e);
            return Collections.emptyMap();
        } finally {
            // CRITICAL: Close all input arrays to prevent off-heap memory leaks
            // These are created fresh on every encode() call and must be released
            if (inputIdsArr != null) {
                try {
                    inputIdsArr.close();
                } catch (Exception e) {
                    LOG.debug("[{}] Error closing inputIdsArr: {}", this.modelIdentifier, e.getMessage());
                }
            }
            if (attentionMaskArr != null) {
                try {
                    attentionMaskArr.close();
                } catch (Exception e) {
                    LOG.debug("[{}] Error closing attentionMaskArr: {}", this.modelIdentifier, e.getMessage());
                }
            }
            if (tokenTypeIdsArr != null) {
                try {
                    tokenTypeIdsArr.close();
                } catch (Exception e) {
                    LOG.debug("[{}] Error closing tokenTypeIdsArr: {}", this.modelIdentifier, e.getMessage());
                }
            }
            // CRITICAL: Close ALL output arrays in the outputMap, not just the one we used
            // The outputMap may contain multiple tensors from intermediate computations
            if (outputMap != null) {
                for (Map.Entry<String, INDArray> entry : outputMap.entrySet()) {
                    INDArray arr = entry.getValue();
                    if (arr != null) {
                        try {
                            arr.close();
                        } catch (Exception e) {
                            LOG.debug("[{}] Error closing output array '{}': {}",
                                    this.modelIdentifier, entry.getKey(), e.getMessage());
                        }
                    }
                }
            }
            // Note: logits is already closed above as part of outputMap iteration

            // CRITICAL: Trigger periodic workspace cleanup for single-encode path
            maybeCleanupWorkspaces();
        }
    }

    private Map<String, Float> processLogits(INDArray logits, SamediffBertTokenizerPreProcessor.BertEncoding encoding, String query) {
        // Track all intermediate arrays to prevent off-heap memory leaks
        INDArray reshapedLogits = null;
        INDArray processedLogits = null;
        INDArray reluOutput = null;
        INDArray maxPooledWeights = null;
        INDArray reshapedMaxPooled = null;

        try {
            // Corrected shape check:
            INDArray workingLogits = logits;
            if (logits.rank() != 3 || logits.shape()[0] != 1) {
                LOG.warn("[{}] Unexpected logits tensor shape for SPLADE++: {}. Expected [1, seq_len, vocab_size]. Query: '{}'",
                        this.modelIdentifier, Arrays.toString(logits.shape()), query);
                if (logits.rank() == 2 && logits.shape()[0] == encoding.inputIds.length && logits.shape()[1] == this.anseriniVocabulary.getVocabSize()) {
                    reshapedLogits = logits.reshape(1, logits.shape()[0], logits.shape()[1]);
                    workingLogits = reshapedLogits;
                } else {
                    return Collections.emptyMap();
                }
            }
            if (workingLogits.shape()[2] != this.anseriniVocabulary.getVocabSize()) {
                LOG.warn("[{}] Logits vocab dimension {} does not match Anserini vocab size {}. Query: '{}'",
                        this.modelIdentifier, workingLogits.shape()[2], this.anseriniVocabulary.getVocabSize(), query);
                return Collections.emptyMap();
            }

            processedLogits = Nd4j.nn().logSoftmax(workingLogits, 2);
            reluOutput = Transforms.relu(processedLogits, true);
            maxPooledWeights = reluOutput.max(true, 1);
            reshapedMaxPooled = maxPooledWeights.reshape(this.anseriniVocabulary.getVocabSize());

            Map<String, Float> tokenFloatWeights = new LinkedHashMap<>();
            for (int i = 0; i < reshapedMaxPooled.length(); i++) {
                float weight = reshapedMaxPooled.getFloat(i);
                if (weight > 1e-5) {
                    String token = this.anseriniVocabulary.getToken(i);
                    if (token.equals(SamediffBertVocabulary.CLS_TOKEN) ||
                            token.equals(SamediffBertVocabulary.SEP_TOKEN) ||
                            token.equals(SamediffBertVocabulary.PAD_TOKEN) ||
                            token.equals(this.anseriniVocabulary.getUnknownTokenValue()) ||
                            token.startsWith("##")) {
                        continue;
                    }
                    tokenFloatWeights.put(token, weight);
                }
            }
            return tokenFloatWeights;
        } catch (Exception e) {
            LOG.error("[{}] Error processing SPLADE++ logits for query: '{}'", this.modelIdentifier, query, e);
            return Collections.emptyMap();
        } finally {
            // CRITICAL: Close all intermediate arrays to prevent off-heap memory leaks
            closeArraySafely(reshapedLogits);
            closeArraySafely(processedLogits);
            closeArraySafely(reluOutput);
            closeArraySafely(maxPooledWeights);
            closeArraySafely(reshapedMaxPooled);
        }
    }

    private void closeArraySafely(INDArray arr) {
        if (arr != null) {
            try {
                arr.close();
            } catch (Exception e) {
                LOG.debug("[{}] Error closing array: {}", this.modelIdentifier, e.getMessage());
            }
        }
    }
}