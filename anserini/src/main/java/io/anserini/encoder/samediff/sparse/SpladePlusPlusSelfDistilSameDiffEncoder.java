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

import org.jetbrains.annotations.NotNull;
// Removed: @Nullable for path parameters, now mandatory via Kompile.
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
// Removed: import java.net.URISyntaxException; // URLs no longer handled here
import java.util.Collections;
import java.util.List; // For tensor names

/**
 * SPLADE++ Self-Distil variant using SameDiff (via ONNX import).
 * Refactored for Kompile Model Management. Model and vocabulary paths are provided externally.
 */
public class SpladePlusPlusSelfDistilSameDiffEncoder extends SpladePlusPlusSameDiffEncoder {
    private static final Logger LOG = LogManager.getLogger(SpladePlusPlusSelfDistilSameDiffEncoder.class);

    // Logical identifier for this specific type of SPLADE++ model.
    // This ID will be used by RagPomGenerator and ModelConstants to fetch the correct ModelDescriptor.
    public static final String MODEL_ID_SPLADE_PP_SELF_DISTIL = "anserini_encoder_splade-pp-sd-onnx"; // Example Kompile ID

    // Removed: DEFAULT_MODEL_NAME_SELF_DISTIL, DEFAULT_MODEL_URL_SELF_DISTIL,
    // DEFAULT_VOCAB_NAME_SELF_DISTIL, DEFAULT_VOCAB_URL_SELF_DISTIL.
    // This information is now managed by ModelDescriptor in ModelConstants.java,
    // identified by MODEL_ID_SPLADE_PP_SELF_DISTIL.

    /**
     * Constructor for SpladePlusPlusSelfDistilSameDiffEncoder, using Kompile-managed paths.
     *
     * @param kompileManagedOnnxModelPath Path to the ONNX model file for SPLADE++ Self-Distil.
     * @param kompileManagedVocabPath     Path to the vocabulary file.
     * @param inputTensorNames            List of input tensor names for the ONNX model.
     * @param outputTensorNames           List of output tensor names from the ONNX model.
     * @param doLowerCaseAndStripAccents  Tokenizer option.
     * @param maxSequenceLength           Tokenizer option.
     * @param addSpecialTokens            Tokenizer option.
     * @param weightRange                 Quantization parameter for sparse output.
     * @param quantRange                  Quantization parameter for sparse output.
     * @throws IOException If model or vocabulary loading fails.
     */
    public SpladePlusPlusSelfDistilSameDiffEncoder(
            @NotNull String kompileManagedOnnxModelPath,
            @NotNull String kompileManagedVocabPath,
            @NotNull List<String> inputTensorNames, // e.g., SpladePlusPlusSameDiffEncoder.INPUT_IDS_TENSOR_NAME, ...
            @NotNull List<String> outputTensorNames, // e.g., SpladePlusPlusSameDiffEncoder.OUTPUT_LOGITS_TENSOR_NAME
            boolean doLowerCaseAndStripAccents,
            int maxSequenceLength,
            boolean addSpecialTokens,
            int weightRange,
            int quantRange)
            throws IOException {
        super(MODEL_ID_SPLADE_PP_SELF_DISTIL, // Pass the logical model identifier
                kompileManagedOnnxModelPath, kompileManagedVocabPath,
                inputTensorNames, outputTensorNames,
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
                weightRange, quantRange); // These quantization params are from the parent abstract class
        LOG.info("[{}] SpladePlusPlusSelfDistilSameDiffEncoder initialized. Model path: {}, Vocab path: {}",
                MODEL_ID_SPLADE_PP_SELF_DISTIL, kompileManagedOnnxModelPath, kompileManagedVocabPath);
    }

    /**
     * Simplified constructor using default tensor names and tokenizer/quantization settings
     * defined in the parent {@link SpladePlusPlusSameDiffEncoder}.
     *
     * @param kompileManagedOnnxModelPath Path to the ONNX model file.
     * @param kompileManagedVocabPath     Path to the vocabulary file.
     * @throws IOException If model or vocabulary loading fails.
     */
    public SpladePlusPlusSelfDistilSameDiffEncoder(
            @NotNull String kompileManagedOnnxModelPath,
            @NotNull String kompileManagedVocabPath) throws IOException {
        this(kompileManagedOnnxModelPath, kompileManagedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                Collections.singletonList(OUTPUT_LOGITS_TENSOR_NAME), // Assuming "logits" is the output
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS,
                DEFAULT_SPLADE_WEIGHT_RANGE, // Use defaults from parent or define specific ones here
                DEFAULT_SPLADE_QUANT_RANGE);
    }

    // The encode method is inherited from SpladePlusPlusSameDiffEncoder.
    // No specific override needed here unless SelfDistil has unique post-processing
    // not covered by the abstract parent's encode method.
}