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

package io.anserini.encoder.samediff;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.jetbrains.annotations.NotNull;
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

/**
 * BGE (Baidu General Embedding) Encoder using SameDiff (via ONNX import).
 * Refactored for Kompile Model Management. Model and vocabulary paths are provided.
 */
public class BgeSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(BgeSameDiffEncoder.class);

    // Logical identifier for this type of encoder
    public static final String ENCODER_TYPE_ID = "bge-samediff";

    // Default model and vocab IDs (as defined in ModelConstants.java)
    // These are used if no specific IDs are provided by config, but config should provide them.
    public static final String DEFAULT_BGE_MODEL_ID = "anserini_encoder_bge-base-en-v1.5-onnx"; // From ModelConstants
    // For vocabulary, if it's a separate managed file:
    // public static final String DEFAULT_BGE_VOCAB_ID = "anserini_vocab_bge-base-en-v1.5";
    // Or assume vocab files are co-located/standard for the tokenizer used by SamediffBertTokenizerPreProcessor

    // Input and Output tensor names for the BGE ONNX model (must match the imported ONNX graph)
    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids"; // BGE typically uses these
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "last_hidden_state"; // Common output name

    // Default tokenizer settings (can be overridden by specific configurations)
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    // Optional instruction for BGE, if needed by the specific variant/usage
    private final String instruction; // e.g., "Represent this sentence for searching relevant passages: "
    private final boolean normalizeEmbeddings; // Whether to L2 normalize the final embedding

    /**
     * Constructor for BgeSameDiffEncoder.
     *
     * @param modelIdentifier             Unique identifier for this specific model instance (for logging/config).
     * @param kompileManagedOnnxModelPath Path to the ONNX model file.
     * @param kompileManagedVocabPath     Path to the vocabulary file for the tokenizer.
     * @param instruction                 Optional instruction to prepend to queries (can be null or empty).
     * @param normalizeEmbeddings         Whether to L2 normalize the output embeddings.
     * @param doLowerCaseAndStripAccents  Tokenizer: lowercase and strip accents.
     * @param maxSequenceLength           Tokenizer: max sequence length.
     * @param addSpecialTokens            Tokenizer: add special [CLS], [SEP] tokens.
     * @throws IOException If model or vocabulary loading fails.
     */
    public BgeSameDiffEncoder(@NotNull String modelIdentifier,
                              @NotNull String kompileManagedOnnxModelPath,
                              @NotNull String kompileManagedVocabPath,
                              @NotNull List<String> inputTensorNames, // Should include INPUT_IDS, ATTENTION_MASK, TOKEN_TYPE_IDS
                              @NotNull List<String> outputTensorNames, // Should include OUTPUT_EMBEDDING_TENSOR_NAME
                               String instruction,
                              boolean normalizeEmbeddings,
                              boolean doLowerCaseAndStripAccents,
                              int maxSequenceLength,
                              boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                inputTensorNames, outputTensorNames,
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        this.instruction = (instruction == null || instruction.trim().isEmpty()) ? "" : instruction.trim() + " ";
        this.normalizeEmbeddings = normalizeEmbeddings;

        LOG.info("[{}] BGE Encoder initialized. Instruction prefix: '{}', Normalize: {}",
                modelIdentifier, this.instruction.isEmpty() ? "none" : this.instruction.trim(), this.normalizeEmbeddings);
    }

    /**
     * Simplified constructor using default tensor names and some BGE-specific defaults.
     */
    public BgeSameDiffEncoder(@NotNull String modelIdentifier,
                              @NotNull String kompileManagedOnnxModelPath,
                              @NotNull String kompileManagedVocabPath,
                              String instruction, // e.g., "Represent this sentence for searching relevant passages: "
                              boolean normalizeEmbeddings) throws IOException {
        this(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                Collections.singletonList(OUTPUT_EMBEDDING_TENSOR_NAME),
                instruction, normalizeEmbeddings,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS, DEFAULT_MAX_SEQUENCE_LENGTH, DEFAULT_ADD_SPECIAL_TOKENS);
    }


    @Override
    public float[] encode(@NotNull String query) {
        String textToEncode = this.instruction + query;
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(textToEncode);

        // Ensure input arrays are correctly shaped for the model (batch_size, sequence_length)
        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);       // e.g., "input_ids"
        placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);  // e.g., "attention_mask"
        placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);   // e.g., "token_type_ids"

        try {
            // Use the first output tensor name specified in the constructor for BGE
            String outputName = this.outputTensorNamesFromModel.get(0);
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, outputName);
            INDArray embeddingTensor = outputMap.get(outputName);

            if (embeddingTensor == null) {
                LOG.error("[{}] Output tensor '{}' not found in SameDiff model output for query: {}",
                        this.modelIdentifier, outputName, query);
                return null;
            }

            // BGE models typically use the embedding of the [CLS] token (first token)
            // from the last_hidden_state. Output shape: [batch_size, sequence_length, hidden_size]
            INDArray clsEmbedding;
            if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
                // Get the embedding of the first token ([CLS])
                clsEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
            } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
                // If output is already [1, hidden_dim] (e.g. model does pooling)
                clsEmbedding = embeddingTensor.getRow(0);
            }
            else {
                LOG.warn("[{}] Unexpected embedding tensor shape for BGE: {}. Expected [1, seq_len, hidden_dim] or [1, hidden_dim]. Attempting to use first row/vector.",
                        this.modelIdentifier, Arrays.toString(embeddingTensor.shape()));
                // Attempt to recover if shape is just [hidden_dim] or [seq_len, hidden_dim] without batch
                if (embeddingTensor.rank() == 1) clsEmbedding = embeddingTensor;
                else if (embeddingTensor.rank() == 2) clsEmbedding = embeddingTensor.getRow(0);
                else return null; // Cannot determine CLS token
            }

            clsEmbedding = clsEmbedding.reshape(1, -1); // Ensure [1, hidden_dim] for normalization

            if (this.normalizeEmbeddings) {
                INDArray norm = clsEmbedding.norm2(true, 1); // Calculate L2 norm along dimension 1
                INDArray epsilon = Nd4j.scalar(clsEmbedding.dataType(), 1e-12f); // Small epsilon to prevent div by zero
                norm = Transforms.max(norm, epsilon); // norm = max(norm, epsilon)
                INDArray normalizedEmbedding = clsEmbedding.divi(norm); // Element-wise division
                return normalizedEmbedding.toFloatVector();
            } else {
                return clsEmbedding.toFloatVector();
            }

        } catch (Exception e) {
            LOG.error("[{}] Error during SameDiff BGE encoding for query: '{}'", this.modelIdentifier, query, e);
            return null;
        }
    }
}