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

// File: getkompile/kompile/kompile-ag_new_kompile_cli/anserini/encoder/samediff/sparse/SpladePlusPlusEnsembleDistilSameDiffEncoder.java
package io.anserini.encoder.samediff.sparse;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;

// Assuming this class is similar to other SPLADE encoders, but with a specific model.
// The actual encoding logic (tensor names, post-processing) might differ based on the "EnsembleDistil" variant.
public class SpladePlusPlusEnsembleDistilSameDiffEncoder extends SpladePlusPlusSameDiffEncoder {
    private static final Logger LOG = LogManager.getLogger(SpladePlusPlusEnsembleDistilSameDiffEncoder.class);

    // --- Constants specific to SpladePlusPlusEnsembleDistil ---
    // Users should provide actual paths/URLs or these should be proper defaults
    public static final String DEFAULT_MODEL_NAME_ENSEMBLE_DISTIL = "splade-pp-ensemble-distil.sd";
    public static final String DEFAULT_MODEL_URL_ENSEMBLE_DISTIL = "https://PLACEHOLDER_URL/splade-pp-ensemble-distil.sd"; // REPLACE
    public static final String DEFAULT_VOCAB_NAME_ENSEMBLE_DISTIL = "splade-pp-ensemble-distil-vocab.txt";
    public static final String DEFAULT_VOCAB_URL_ENSEMBLE_DISTIL = "https://PLACEHOLDER_URL/splade-pp-ensemble-distil-vocab.txt"; // REPLACE


    // Constructor to be used when only paths are provided
    public SpladePlusPlusEnsembleDistilSameDiffEncoder(@Nullable String modelPath, @Nullable String vocabPath)
            throws IOException, URISyntaxException {
        this(DEFAULT_MODEL_NAME_ENSEMBLE_DISTIL, modelPath == null ? DEFAULT_MODEL_URL_ENSEMBLE_DISTIL : null,
                DEFAULT_VOCAB_NAME_ENSEMBLE_DISTIL, vocabPath == null ? DEFAULT_VOCAB_URL_ENSEMBLE_DISTIL : null,
                modelPath, vocabPath,
                SpladePlusPlusSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                SpladePlusPlusSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH,
                SpladePlusPlusSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS,
                SpladePlusPlusSameDiffEncoder.DEFAULT_WEIGHT_RANGE, // Or specific values for this model
                SpladePlusPlusSameDiffEncoder.DEFAULT_QUANT_RANGE); // Or specific values for this model
        LOG.info("SpladePlusPlusEnsembleDistilSameDiffEncoder initialized with modelPath: {}, vocabPath: {}", modelPath, vocabPath);
    }

    // Full constructor allowing override of all parameters
    public SpladePlusPlusEnsembleDistilSameDiffEncoder(
            @NotNull String modelName, @Nullable String modelUrl,
            @NotNull String vocabName, @Nullable String vocabUrl,
            @Nullable String providedModelPath, @Nullable String providedVocabPath,
            boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens,
            int weightRange, int quantRange)
            throws IOException, URISyntaxException {
        super(modelName, modelUrl, vocabName, vocabUrl,
                providedModelPath, providedVocabPath,
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
                weightRange, quantRange);
        LOG.info("SpladePlusPlusEnsembleDistilSameDiffEncoder fully initialized: {}", modelName);
    }

    // The encode method is inherited from SpladePlusPlusSameDiffEncoder.
    // If EnsembleDistil requires different tensor names or post-processing,
    // those would be overridden here or managed by different constants in the superclass
    // if the superclass constructor is made more flexible.
}