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

package io.anserini.encoder.samediff.sparse;

import io.anserini.encoder.samediff.SameDiffEncoder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for SameDiff-based sparse encoders.
 * The main {@link #encode(String)} method is implemented by subclasses to return a map of term float weights.
 * This class provides utility methods for quantization and flattening.
 */
public abstract class SameDiffSparseEncoder extends SameDiffEncoder<Map<String, Float>> {

    protected final int weightRange; // For quantization, e.g., 5 for UniCOIL
    protected final int quantRange;  // For quantization, e.g., 256 for UniCOIL

    public SameDiffSparseEncoder(@NotNull String modelName, @Nullable String modelUrl,
                                 @NotNull String vocabName, @Nullable String vocabUrl,
                                 @Nullable String providedModelPath, @Nullable String providedVocabPath,
                                 @NotNull List<String> inputTensorNamesForModel,
                                 @NotNull List<String> outputTensorNamesFromModel,
                                 boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens,
                                 int weightRange, int quantRange) // quantization params
            throws IOException, URISyntaxException {
        super(modelName, modelUrl, vocabName, vocabUrl,
                providedModelPath, providedVocabPath,
                inputTensorNamesForModel, outputTensorNamesFromModel,
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        this.weightRange = weightRange;
        this.quantRange = quantRange;
    }


    /**
     * Quantizes float token weights to integer weights.
     * Uses the {@code weightRange} and {@code quantRange} fields of this encoder.
     *
     * @param tokenFloatWeights A map of tokens to their float weights.
     * @return A map of tokens to their quantized integer weights.
     */
    public Map<String, Integer> quantizeToIntegerWeights(Map<String, Float> tokenFloatWeights) {
        Map<String, Integer> tokenIntWeights = new HashMap<>();
        if (tokenFloatWeights == null) {
            return tokenIntWeights;
        }
        // Avoid division by zero if weightRange is 0 or less.
        float scalingFactor = (this.weightRange > 0) ? (float) this.quantRange / this.weightRange : 0.0f;
        tokenFloatWeights.forEach((token, weight) -> {
            int quantizedWeight = 0;
            if (scalingFactor > 0) {
                quantizedWeight = Math.round(weight * scalingFactor);
            } else if (weight > 0) { // If no valid scaling, map positive weights to 1, others to 0.
                quantizedWeight = 1;
            }
            // Ensure quantized weight is at least 0 if it was positive,
            // and cap at quantRange if needed, though typical BERT sparse outputs are positive.
            quantizedWeight = Math.max(0, quantizedWeight);
            // if (quantRange > 0) quantizedWeight = Math.min(quantizedWeight, quantRange); // Optional upper cap

            if (quantizedWeight > 0) { // Only include terms with positive quantized weight
                tokenIntWeights.put(token, quantizedWeight);
            }
        });
        return tokenIntWeights;
    }

    /**
     * Flattens a map of token integer weights into a space-separated string where each token
     * is repeated according to its weight. This is compatible with Anserini's
     * original SparseEncoder.flatten method.
     *
     * @param tokenIntWeights A map of tokens to their integer weights.
     * @return A flattened string representation.
     */
    public static String flatten(Map<String, Integer> tokenIntWeights) {
        if (tokenIntWeights == null || tokenIntWeights.isEmpty()) {
            return "";
        }
        List<String> tokens = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : tokenIntWeights.entrySet()) {
            String token = entry.getKey();
            int weight = entry.getValue();
            for (int i = 0; i < weight; i++) {
                tokens.add(token);
            }
        }
        return String.join(" ", tokens);
    }

    /**
     * Primary encoding method to be implemented by concrete sparse encoders.
     * This method should compute and return the raw float weights for tokens.
     *
     * @param query The input string to encode.
     * @return A map where keys are tokens (String) and values are their computed float weights.
     * Returns null or empty map if encoding fails.
     */
    @Override // This should now be correct
    public abstract Map<String, Float> encode(@NotNull String query);

}