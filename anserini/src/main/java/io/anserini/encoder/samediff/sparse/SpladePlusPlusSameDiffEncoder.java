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
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for SPLADE++ style encoders using SameDiff (via ONNX import).
 * Refactored for Kompile Model Management. Model and vocabulary paths are provided.
 * Concrete subclasses will define specific model IDs and potentially specialized post-processing.
 */
public abstract class SpladePlusPlusSameDiffEncoder extends SameDiffSparseEncoder {
    private static final Logger LOG = LogManager.getLogger(SpladePlusPlusSameDiffEncoder.class);

    // Tensor names must match the specific SPLADE ONNX model being imported.
    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids"; // Often all zeros for SPLADE
    public static final String OUTPUT_LOGITS_TENSOR_NAME = "logits"; // Default output name for SPLADE logits

    // Default tokenizer settings
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 256;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    // Default quantization parameters from parent
    public static final int DEFAULT_SPLADE_WEIGHT_RANGE = 10;
    public static final int DEFAULT_SPLADE_QUANT_RANGE = 256;

    protected final SamediffBertVocabulary anseriniVocabulary;

    /**
     * Constructor for SpladePlusPlusSameDiffEncoder.
     *
     * @param modelIdentifier             Unique identifier for this model instance.
     * @param kompileManagedOnnxModelPath Absolute path to the Kompile-managed ONNX model file.
     * @param kompileManagedVocabPath     Absolute path to the Kompile-managed vocabulary file.
     * @param inputTensorNames            Expected input tensor names for the ONNX model.
     * @param outputTensorNames           Expected output tensor names (typically one, the logits tensor).
     * @param doLowerCaseAndStripAccents  Tokenizer option.
     * @param maxSequenceLength           Tokenizer option.
     * @param addSpecialTokens            Tokenizer option.
     * @param weightRange                 Quantization parameter.
     * @param quantRange                  Quantization parameter.
     * @throws IOException If model or vocabulary loading fails.
     */
    protected SpladePlusPlusSameDiffEncoder(
            @NotNull String modelIdentifier,
            @NotNull String kompileManagedOnnxModelPath,
            @NotNull String kompileManagedVocabPath,
            @NotNull List<String> inputTensorNames,
            @NotNull List<String> outputTensorNames,
            boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens,
            int weightRange, int quantRange)
            throws IOException {
        super(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                inputTensorNames, outputTensorNames,
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
                weightRange, quantRange);
        this.anseriniVocabulary = this.tokenizerPreProcessor.getVocabulary();
        LOG.info("[{}] SPLADE++ Type Encoder initialized. Max Seq Length: {}", modelIdentifier, maxSequenceLength);
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
        if (this.inputTensorNamesForModel.size() > 2) { // If TOKEN_TYPE_IDS is an expected input
            placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);
        }

        try {
            String logitsOutputName = this.outputTensorNamesFromModel.get(0);
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, logitsOutputName);
            INDArray logits = outputMap.get(logitsOutputName); // Shape: [1, seq_len, vocab_size]

            if (logits == null) {
                LOG.warn("[{}] Logits tensor '{}' is null for SPLADE++ for query: '{}'", this.modelIdentifier, logitsOutputName, query);
                return Collections.emptyMap();
            }

            if (logits.rank() != 3 || logits.shape()[0] != 1 || logits.shape()[2] != this.anseriniVocabulary.getVocabSize()) {
                LOG.warn("[{}] Unexpected logits tensor shape for SPLADE++: {}. Expected [1, {}, {}], Got: {}. Query: '{}'",
                        this.modelIdentifier, Arrays.toString(logits.shape()), encoding.inputIds.length, this.anseriniVocabulary.getVocabSize(),
                        Arrays.toString(logits.shape()), query);
                // return Collections.emptyMap(); // Or attempt to proceed if shape[2] is vocab size
            }

            // SPLADE post-processing: log_softmax -> relu -> max_pool_over_sequence
            // Assuming logits are raw and need log_softmax
            INDArray processedLogits = Nd4j.nn().logSoftmax(logits,2); // log_softmax over vocab_dim (dim 2)
            INDArray reluOutput = Transforms.relu(processedLogits, true); // Apply ReLU
            INDArray maxPooledWeights = reluOutput.max(true, 1); // Max over sequence_length dimension (dim 1)
            maxPooledWeights = maxPooledWeights.reshape(this.anseriniVocabulary.getVocabSize()); // Reshape to [vocab_size]

            Map<String, Float> tokenFloatWeights = new LinkedHashMap<>();
            for (int i = 0; i < maxPooledWeights.length(); i++) {
                float weight = maxPooledWeights.getFloat(i);
                if (weight > 1e-5) { // Threshold from original file was 0.0, using a small epsilon
                    String token = this.anseriniVocabulary.getToken(i);
                    // Filter out special tokens and subword pieces (original file's behavior)
                    if (token.equals(SamediffBertVocabulary.CLS_TOKEN) ||
                            token.equals(SamediffBertVocabulary.SEP_TOKEN) ||
                            token.equals(SamediffBertVocabulary.PAD_TOKEN) ||
                            // Use getUnknownTokenValue() from the instance of the vocabulary
                            token.equals(this.anseriniVocabulary.getUnknownTokenValue()) ||
                            token.startsWith("##")) {
                        continue;
                    }
                    tokenFloatWeights.put(token, weight);
                }
            }
            return tokenFloatWeights;

        } catch (Exception e) {
            LOG.error("[{}] Error during SPLADE++ SameDiff encoding for query: '{}'", this.modelIdentifier, query, e);
            return Collections.emptyMap();
        }
    }
}