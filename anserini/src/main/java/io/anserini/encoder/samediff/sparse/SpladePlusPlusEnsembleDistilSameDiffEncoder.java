/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 */

package io.anserini.encoder.samediff.sparse;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;

public class SpladePlusPlusEnsembleDistilSameDiffEncoder extends SpladePlusPlusSameDiffEncoder {
    private static final Logger LOG = LogManager.getLogger(SpladePlusPlusEnsembleDistilSameDiffEncoder.class);

    // --- UPDATED CONSTANTS from original SpladePlusPlusEnsembleDistilEncoder ---
    public static final String DEFAULT_MODEL_NAME_ENSEMBLE_DISTIL = "splade-pp-ed-optimized.onnx";
    public static final String DEFAULT_MODEL_URL_ENSEMBLE_DISTIL = "https://rgw.cs.uwaterloo.ca/pyserini/data/splade-pp-ed-optimized.onnx";
    // Original VOCAB_NAME was "splade-pp-ed-vocab.txt", but URL pointed to generic "wordpiece-vocab.txt".
    // Using the specific name for clarity, but ensuring URL is correct.
    public static final String DEFAULT_VOCAB_NAME_ENSEMBLE_DISTIL = "splade-pp-ed-vocab.txt"; // More specific than generic wordpiece
    public static final String DEFAULT_VOCAB_URL_ENSEMBLE_DISTIL = "https://rgw.cs.uwaterloo.ca/pyserini/data/wordpiece-vocab.txt";
    // --- END UPDATED CONSTANTS ---


    public SpladePlusPlusEnsembleDistilSameDiffEncoder(@Nullable String modelPath, @Nullable String vocabPath)
            throws IOException, URISyntaxException {
        this(DEFAULT_MODEL_NAME_ENSEMBLE_DISTIL, modelPath == null ? DEFAULT_MODEL_URL_ENSEMBLE_DISTIL : null,
                DEFAULT_VOCAB_NAME_ENSEMBLE_DISTIL, vocabPath == null ? DEFAULT_VOCAB_URL_ENSEMBLE_DISTIL : null,
                modelPath, vocabPath,
                SpladePlusPlusSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                SpladePlusPlusSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH, // Original SpladePlusPlusEncoder uses 512
                SpladePlusPlusSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS,
                SpladePlusPlusSameDiffEncoder.DEFAULT_WEIGHT_RANGE,
                SpladePlusPlusSameDiffEncoder.DEFAULT_QUANT_RANGE);
        LOG.info("SpladePlusPlusEnsembleDistilSameDiffEncoder initialized with modelPath: {}, vocabPath: {}", modelPath, vocabPath);
    }

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
}