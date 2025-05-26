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
import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;

public class SpladePlusPlusSelfDistilSameDiffEncoder extends SpladePlusPlusSameDiffEncoder {
    private static final Logger LOG = LogManager.getLogger(SpladePlusPlusSelfDistilSameDiffEncoder.class);

    // --- UPDATED CONSTANTS from original SpladePlusPlusSelfDistilEncoder ---
    public static final String DEFAULT_MODEL_NAME_SELF_DISTIL = "splade-pp-sd-optimized.onnx";
    public static final String DEFAULT_MODEL_URL_SELF_DISTIL = "https://rgw.cs.uwaterloo.ca/pyserini/data/splade-pp-sd-optimized.onnx";
    // Original VOCAB_NAME was "splade-pp-sd-vocab.txt", but URL pointed to generic "wordpiece-vocab.txt".
    public static final String DEFAULT_VOCAB_NAME_SELF_DISTIL = "splade-pp-sd-vocab.txt";
    public static final String DEFAULT_VOCAB_URL_SELF_DISTIL = "https://rgw.cs.uwaterloo.ca/pyserini/data/wordpiece-vocab.txt";
    // --- END UPDATED CONSTANTS ---

    public SpladePlusPlusSelfDistilSameDiffEncoder(@Nullable String modelPath, @Nullable String vocabPath)
            throws IOException, URISyntaxException {
        this(DEFAULT_MODEL_NAME_SELF_DISTIL, modelPath == null ? DEFAULT_MODEL_URL_SELF_DISTIL : null,
                DEFAULT_VOCAB_NAME_SELF_DISTIL, vocabPath == null ? DEFAULT_VOCAB_URL_SELF_DISTIL : null,
                modelPath, vocabPath,
                SpladePlusPlusSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                SpladePlusPlusSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH,
                SpladePlusPlusSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS,
                SpladePlusPlusSameDiffEncoder.DEFAULT_WEIGHT_RANGE,
                SpladePlusPlusSameDiffEncoder.DEFAULT_QUANT_RANGE);
        LOG.info("SpladePlusPlusSelfDistilSameDiffEncoder initialized with modelPath: {}, vocabPath: {}", modelPath, vocabPath);
    }

    public SpladePlusPlusSelfDistilSameDiffEncoder(
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
        LOG.info("SpladePlusPlusSelfDistilSameDiffEncoder fully initialized: {}", modelName);
    }
    // The encode method is inherited from SpladePlusPlusSameDiffEncoder.
}