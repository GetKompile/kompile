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

import org.jetbrains.annotations.NotNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * SPLADE++ Ensemble-Distil encoder implementation using SameDiff format.
 * This encoder extends the base SPLADE++ encoder for the ensemble-distilled variant.
 */
public class SpladePlusPlusEnsembleDistilSameDiffEncoder extends SpladePlusPlusSameDiffEncoder {
    private static final Logger LOG = LogManager.getLogger(SpladePlusPlusEnsembleDistilSameDiffEncoder.class);

    // Default constants specific to Ensemble-Distil variant
    public static final String DEFAULT_MODEL_NAME = "splade-pp-ed.sd";
    public static final String DEFAULT_VOCAB_NAME = "splade-pp-ed-vocab.txt";
    
    // Tensor names for Ensemble-Distil variant
    public static final String ED_INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ED_ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String ED_TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    public static final String ED_OUTPUT_LOGITS_TENSOR_NAME = "logits";

    // Ensemble-Distil specific settings
    public static final int DEFAULT_ED_MAX_SEQUENCE_LENGTH = 256;
    public static final int DEFAULT_ED_WEIGHT_RANGE = 10;
    public static final int DEFAULT_ED_QUANT_RANGE = 256;

    public SpladePlusPlusEnsembleDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                                       @NotNull String kompileManagedModelPath,
                                                       @NotNull String kompileManagedVocabPath,
                                                       boolean doLowerCaseAndStripAccents,
                                                       int maxSequenceLength,
                                                       boolean addSpecialTokens,
                                                       int weightRange,
                                                       int quantRange) throws IOException {
        super(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
              List.of(ED_INPUT_IDS_TENSOR_NAME, ED_ATTENTION_MASK_TENSOR_NAME, ED_TOKEN_TYPE_IDS_TENSOR_NAME),
              Collections.singletonList(ED_OUTPUT_LOGITS_TENSOR_NAME),
              doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
              weightRange, quantRange);
        LOG.info("[{}] SPLADE++ Ensemble-Distil Encoder initialized with max seq length: {}", 
                 modelIdentifier, maxSequenceLength);
    }

    // Simplified constructor using Ensemble-Distil defaults
    public SpladePlusPlusEnsembleDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                                       @NotNull String kompileManagedModelPath,
                                                       @NotNull String kompileManagedVocabPath) throws IOException {
        this(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
             DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
             DEFAULT_ED_MAX_SEQUENCE_LENGTH,
             DEFAULT_ADD_SPECIAL_TOKENS,
             DEFAULT_ED_WEIGHT_RANGE,
             DEFAULT_ED_QUANT_RANGE);
    }

    // Constructor with custom weight/quant ranges
    public SpladePlusPlusEnsembleDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                                       @NotNull String kompileManagedModelPath,
                                                       @NotNull String kompileManagedVocabPath,
                                                       int weightRange,
                                                       int quantRange) throws IOException {
        this(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
             DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
             DEFAULT_ED_MAX_SEQUENCE_LENGTH,
             DEFAULT_ADD_SPECIAL_TOKENS,
             weightRange, quantRange);
    }
}
