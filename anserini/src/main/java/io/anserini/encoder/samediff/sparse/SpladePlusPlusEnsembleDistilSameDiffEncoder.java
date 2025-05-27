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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class SpladePlusPlusEnsembleDistilSameDiffEncoder extends SpladePlusPlusSameDiffEncoder {
    private static final Logger LOG = LogManager.getLogger(SpladePlusPlusEnsembleDistilSameDiffEncoder.class);

    // The modelIdentifier is now passed in the constructor
    // public static final String MODEL_ID_SPLADE_PP_ENSEMBLE_DISTIL = "anserini_encoder_splade-pp-ed-onnx";

    public SpladePlusPlusEnsembleDistilSameDiffEncoder(
            @NotNull String modelIdentifier, // Added modelIdentifier
            @NotNull String kompileManagedOnnxModelPath,
            @NotNull String kompileManagedVocabPath,
            boolean doLowerCaseAndStripAccents,
            int maxSequenceLength,
            boolean addSpecialTokens,
            int weightRange,
            int quantRange)
            throws IOException {
        super(modelIdentifier,
                kompileManagedOnnxModelPath, kompileManagedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                Collections.singletonList(OUTPUT_LOGITS_TENSOR_NAME),
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
                weightRange, quantRange);
        LOG.info("[{}] SpladePlusPlusEnsembleDistilSameDiffEncoder initialized.", modelIdentifier);
    }

    // Simplified constructor using all defaults from parent
    public SpladePlusPlusEnsembleDistilSameDiffEncoder(
            @NotNull String modelIdentifier,
            @NotNull String kompileManagedOnnxModelPath,
            @NotNull String kompileManagedVocabPath) throws IOException {
        this(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS, DEFAULT_MAX_SEQUENCE_LENGTH, DEFAULT_ADD_SPECIAL_TOKENS,
                DEFAULT_SPLADE_WEIGHT_RANGE, DEFAULT_SPLADE_QUANT_RANGE);
    }
}