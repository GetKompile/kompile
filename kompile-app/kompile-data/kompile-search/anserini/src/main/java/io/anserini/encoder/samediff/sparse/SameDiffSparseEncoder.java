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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.anserini.encoder.samediff.SameDiffEncoder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Abstract base class for SameDiff-based sparse encoders.
 * The main {@link #encode(String)} method is implemented by subclasses to return a map of term float weights.
 * This class provides utility methods for quantization and flattening.
 * Assumes Kompile Model Management for model/vocabulary paths.
 */
public abstract class SameDiffSparseEncoder extends SameDiffEncoder<Map<String, Float>> {

    protected final int weightRange; // For quantization, e.g., 5 for UniCOIL
    protected final int quantRange;  // For quantization, e.g., 256 for UniCOIL

    /**
     * Constructor for SameDiffSparseEncoder.
     *
     * @param modelIdentifier             Unique identifier for the model (for logging).
     * @param kompileManagedModelPath     Absolute path to the ONNX model file.
     * @param kompileManagedVocabPath     Absolute path to the vocabulary file.
     * @param inputTensorNamesForModel    List of input tensor names for the ONNX model.
     * @param outputTensorNamesFromModel  List of output tensor names from the ONNX model.
     * @param doLowerCaseAndStripAccents  Tokenizer option.
     * @param maxSequenceLength           Tokenizer option.
     * @param addSpecialTokens            Tokenizer option.
     * @param weightRange                 Parameter for quantizing float weights.
     * @param quantRange                  Parameter for quantizing float weights.
     * @throws IOException if model or vocabulary loading fails.
     */
    public SameDiffSparseEncoder(@NotNull String modelIdentifier,
                                 @NotNull String kompileManagedModelPath,
                                 @NotNull String kompileManagedVocabPath,
                                 @NotNull List<String> inputTensorNamesForModel,
                                 @NotNull List<String> outputTensorNamesFromModel,
                                 boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens,
                                 int weightRange, int quantRange)
            throws IOException {
        super(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
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
        float scalingFactor = (this.weightRange > 0) ? (float) this.quantRange / this.weightRange : 0.0f;
        tokenFloatWeights.forEach((token, weight) -> {
            int quantizedWeight = 0;
            if (scalingFactor > 0) {
                quantizedWeight = Math.round(weight * scalingFactor);
            } else if (weight > 0) {
                quantizedWeight = 1;
            }
            quantizedWeight = Math.max(0, quantizedWeight);
            if (quantizedWeight > 0) {
                tokenIntWeights.put(token, quantizedWeight);
            }
        });
        return tokenIntWeights;
    }

    /**
     * Flattens a map of token integer weights into a space-separated string.
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
     * Converts the sparse vector (map of ID to weight) from the model into a Pyserini-compatible JSON string.
     * @param sparseVector map representing the sparse vector (term -> weight)
     * @return JSON string representation of the sparse vector
     */
    protected String outputToPyseriniJson(Map<String, Float> sparseVector) {
        // This method was in the original SpladePlusPlusSameDiffEncoder.java,
        // but it expects Map<Integer, Float> for Pyserini output.
        // The encode method here returns Map<String, Float>.
        // If Pyserini format is indeed Integer IDs, a vocabulary lookup is needed.
        // For now, assuming the Map<String, Float> is the desired sparse representation.
        // If integer IDs are needed for JSON:
        // Map<Integer, Float> pyseriniSparseVector = new HashMap<>();
        // SamediffBertVocabulary vocab = this.tokenizerPreProcessor.getVocabulary();
        // sparseVector.forEach((token, weight) -> pyseriniSparseVector.put(vocab.getTokenId(token), weight));
        // Then proceed with pyseriniSparseVector.

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode rootNode = mapper.createObjectNode();
        ObjectNode vectorNode = rootNode.putObject("vector");

        // Sort by token string for consistent output, or by ID if using integer IDs.
        List<String> sortedTokens = sparseVector.keySet().stream().sorted().collect(Collectors.toList());
        ArrayNode indicesNode = vectorNode.putArray("indices"); // Will store tokens if string, or IDs if int
        ArrayNode valuesNode = vectorNode.putArray("values");

        for (String token : sortedTokens) {
            indicesNode.add(token); // Storing token string itself as "index"
            valuesNode.add(sparseVector.get(token));
        }
        return rootNode.toString();
    }


    /**
     * Primary encoding method to be implemented by concrete sparse encoders.
     * This method should compute and return the raw float weights for tokens.
     *
     * @param query The input string to encode.
     * @return A map where keys are tokens (String) and values are their computed float weights.
     */
    @Override
    public abstract Map<String, Float> encode(@NotNull String query);
}