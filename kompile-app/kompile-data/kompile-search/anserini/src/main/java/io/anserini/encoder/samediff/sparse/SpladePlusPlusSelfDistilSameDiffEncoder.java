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
 * SPLADE++ Self-Distil encoder implementation using SameDiff format.
 * This encoder extends the base SPLADE++ encoder for the self-distilled variant.
 */
public class SpladePlusPlusSelfDistilSameDiffEncoder extends SpladePlusPlusSameDiffEncoder {
    private static final Logger LOG = LogManager.getLogger(SpladePlusPlusSelfDistilSameDiffEncoder.class);

    // Default constants specific to Self-Distil variant
    public static final String DEFAULT_MODEL_NAME = "splade-pp-sd.sd";
    public static final String DEFAULT_VOCAB_NAME = "splade-pp-sd-vocab.txt";
    
    // Tensor names for Self-Distil variant
    public static final String SD_INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String SD_ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String SD_TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    public static final String SD_OUTPUT_LOGITS_TENSOR_NAME = "logits";

    // Self-Distil specific settings - typically uses shorter sequences for efficiency
    public static final int DEFAULT_SD_MAX_SEQUENCE_LENGTH = 256;
    public static final int DEFAULT_SD_WEIGHT_RANGE = 10;
    public static final int DEFAULT_SD_QUANT_RANGE = 256;

    public SpladePlusPlusSelfDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                                   @NotNull String kompileManagedModelPath,
                                                   @NotNull String kompileManagedVocabPath,
                                                   boolean doLowerCaseAndStripAccents,
                                                   int maxSequenceLength,
                                                   boolean addSpecialTokens,
                                                   int weightRange,
                                                   int quantRange) throws IOException {
        super(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
              List.of(SD_INPUT_IDS_TENSOR_NAME, SD_ATTENTION_MASK_TENSOR_NAME, SD_TOKEN_TYPE_IDS_TENSOR_NAME),
              Collections.singletonList(SD_OUTPUT_LOGITS_TENSOR_NAME),
              doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
              weightRange, quantRange);
        LOG.info("[{}] SPLADE++ Self-Distil Encoder initialized with max seq length: {}", 
                 modelIdentifier, maxSequenceLength);
    }

    // Simplified constructor using Self-Distil defaults
    public SpladePlusPlusSelfDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                                   @NotNull String kompileManagedModelPath,
                                                   @NotNull String kompileManagedVocabPath) throws IOException {
        this(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
             DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
             DEFAULT_SD_MAX_SEQUENCE_LENGTH,
             DEFAULT_ADD_SPECIAL_TOKENS,
             DEFAULT_SD_WEIGHT_RANGE,
             DEFAULT_SD_QUANT_RANGE);
    }

    // Constructor with custom weight/quant ranges
    public SpladePlusPlusSelfDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                                   @NotNull String kompileManagedModelPath,
                                                   @NotNull String kompileManagedVocabPath,
                                                   int weightRange,
                                                   int quantRange) throws IOException {
        this(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
             DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
             DEFAULT_SD_MAX_SEQUENCE_LENGTH,
             DEFAULT_ADD_SPECIAL_TOKENS,
             weightRange, quantRange);
    }
}
