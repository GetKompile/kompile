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

package io.anserini.encoder.samediff;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ArcticEmbedSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(ArcticEmbedSameDiffEncoder.class);

    private static final String INSTRUCTION = "Represent this sentence for searching relevant passages: ";

    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    /**
     * Override to provide the instruction prefix for pipelined bulk encoding.
     * This ensures parallel tokenization in bulkEncodeOptimized() produces
     * the same results as single-text encode().
     */
    @Override
    protected String getInstructionPrefix() {
        return INSTRUCTION;
    }

    public ArcticEmbedSameDiffEncoder(@NotNull String modelIdentifier,
                                      boolean doLowerCaseAndStripAccents,
                                      int maxSequenceLength,
                                      boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        LOG.info("[{}] ArcticEmbedSameDiffEncoder initialized.", modelIdentifier);
    }

    // Simplified constructor using defaults
    public ArcticEmbedSameDiffEncoder(@NotNull String modelIdentifier) throws IOException {
        this(modelIdentifier,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS);
    }

    // Legacy constructor for backward compatibility
    @Deprecated
    public ArcticEmbedSameDiffEncoder(@NotNull String modelIdentifier,
                                      @NotNull String kompileManagedModelPath,
                                      @NotNull String kompileManagedVocabPath,
                                      boolean doLowerCaseAndStripAccents,
                                      int maxSequenceLength,
                                      boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
                null, null, // Will be dynamically determined
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        LOG.info("[{}] ArcticEmbedSameDiffEncoder initialized (legacy).", modelIdentifier);
    }

    @Override
    public float[] encode(@NotNull String query) {
        // Check for interrupt before starting encoding
        if (Thread.currentThread().isInterrupted()) {
            LOG.info("[{}] Encoding interrupted before starting for query: '{}'",
                    modelIdentifier, query.substring(0, Math.min(50, query.length())));
            return null;
        }

        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(INSTRUCTION + query);

        // Check for interrupt after tokenization (before inference)
        if (Thread.currentThread().isInterrupted()) {
            LOG.info("[{}] Encoding interrupted after tokenization", modelIdentifier);
            return null;
        }

        // Delegate to encodeFromTokenized for actual inference
        return encodeFromTokenized(query, encoding);
    }

    /**
     * Override from SameDiffEncoder to use pre-tokenized encoding directly.
     * This avoids re-tokenization in the pipelined bulk encoding path.
     *
     * <p>Combined with {@link #getInstructionPrefix()}, this enables full pipelined
     * optimization: the base class applies the instruction prefix during parallel
     * tokenization, then calls this method with the pre-tokenized result.</p>
     *
     * @param query The original text (for logging purposes)
     * @param encoding The pre-tokenized BertEncoding (already includes instruction prefix)
     * @return The embedding as a float array, or null on error
     */
    @Override
    protected float[] encodeFromTokenized(String query, SamediffBertTokenizerPreProcessor.BertEncoding encoding) {
        if (encoding == null) {
            LOG.warn("[{}] Null encoding provided for query", modelIdentifier);
            return null;
        }

        // Track all arrays that need cleanup to prevent off-heap memory leaks
        Map<String, INDArray> placeholderMap = new HashMap<>();
        Map<String, INDArray> outputMap = null;
        INDArray hiddenStates = null;

        try {
            // Get what the model actually expects
            List<String> modelInputs = this.inputTensorNamesForModel;

            LOG.debug("[{}] Model expects {} inputs: {}", this.modelIdentifier, modelInputs.size(), modelInputs);

            // Test for existence of each standard input in the model and provide if needed
            for (String inputName : modelInputs) {
                if (isInputIdsInput(inputName)) {
                    // CRITICAL: Create 2D array directly to avoid intermediate array leak from castTo()
                    INDArray inputIds = Nd4j.createFromArray(new long[][]{encoding.inputIds});
                    placeholderMap.put(inputName, inputIds);
                    LOG.debug("[{}] Providing input_ids as '{}'", this.modelIdentifier, inputName);
                } else if (isAttentionMaskInput(inputName)) {
                    INDArray attentionMask = Nd4j.createFromArray(new long[][]{encoding.attentionMask});
                    placeholderMap.put(inputName, attentionMask);
                    LOG.debug("[{}] Providing attention_mask as '{}'", this.modelIdentifier, inputName);
                } else if (isTokenTypeIdsInput(inputName)) {
                    INDArray tokenTypeIds = Nd4j.createFromArray(new long[][]{encoding.tokenTypeIds});
                    placeholderMap.put(inputName, tokenTypeIds);
                    LOG.debug("[{}] Providing token_type_ids as '{}'", this.modelIdentifier, inputName);
                } else {
                    LOG.warn("[{}] Unknown model input '{}' - cannot map to tokenizer output", this.modelIdentifier, inputName);
                }
            }
            String outputTensorName = this.outputTensorNamesFromModel.get(0);

            // Execute inference - this is a blocking native call that cannot be interrupted
            outputMap = this.sameDiffModel.output(placeholderMap, outputTensorName);

            // Check for interrupt immediately after inference completes
            if (Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Encoding interrupted after inference completed", modelIdentifier);
                return null;
            }

            hiddenStates = outputMap.get(outputTensorName);

            if (hiddenStates == null) {
                LOG.error("[{}] Output tensor '{}' not found. Available outputs: {}",
                        this.modelIdentifier, outputTensorName, outputMap.keySet());
                return null;
            }

            LOG.debug("[{}] Got output tensor '{}' with shape: {}",
                    this.modelIdentifier, outputTensorName, Arrays.toString(hiddenStates.shape()));

            return processOutput(hiddenStates, placeholderMap);

        } catch (Exception e) {
            // Check if exception is due to interrupt
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Encoding interrupted during processing", modelIdentifier);
                Thread.currentThread().interrupt(); // Restore interrupt flag
                return null;
            }
            LOG.error("[{}] Error during encoding for query: '{}'", this.modelIdentifier, query, e);
            return null;
        } finally {
            // CRITICAL: Close all input arrays to prevent off-heap memory leaks
            // These are created fresh on every encode() call and must be released
            for (INDArray arr : placeholderMap.values()) {
                if (arr != null) {
                    try {
                        arr.close();
                    } catch (Exception e) {
                        LOG.debug("[{}] Error closing input array: {}", this.modelIdentifier, e.getMessage());
                    }
                }
            }
            // CRITICAL: Close ALL output arrays in the outputMap, not just the one we used
            // The outputMap may contain multiple tensors from intermediate computations
            if (outputMap != null) {
                for (Map.Entry<String, INDArray> entry : outputMap.entrySet()) {
                    INDArray arr = entry.getValue();
                    if (arr != null) {
                        try {
                            arr.close();
                        } catch (Exception e) {
                            LOG.debug("[{}] Error closing output array '{}': {}",
                                    this.modelIdentifier, entry.getKey(), e.getMessage());
                        }
                    }
                }
            }
            // Note: hiddenStates is already closed above as part of outputMap iteration

            // CRITICAL: Trigger periodic workspace cleanup for single-encode path
            maybeCleanupWorkspaces();
        }
    }

    private boolean isInputIdsInput(String inputName) {
        String lower = inputName.toLowerCase();
        return lower.contains("input") && lower.contains("id") || 
               lower.equals("input_ids") || 
               lower.equals("inputids") ||
               lower.equals("input");
    }
    
    private boolean isAttentionMaskInput(String inputName) {
        String lower = inputName.toLowerCase();
        return (lower.contains("attention") && lower.contains("mask")) ||
               lower.equals("attention_mask") ||
               lower.equals("attentionmask") ||
               lower.equals("mask");
    }
    
    private boolean isTokenTypeIdsInput(String inputName) {
        String lower = inputName.toLowerCase();
        return (lower.contains("token") && lower.contains("type")) ||
               (lower.contains("type") && lower.contains("id")) ||
               lower.equals("token_type_ids") ||
               lower.equals("tokentypeids") ||
               lower.equals("segment_ids") ||
               lower.equals("segmentids");
    }
    
    private float[] processOutput(INDArray hiddenStates, Map<String, INDArray> inputs) {
        if (hiddenStates.rank() == 3 && hiddenStates.shape()[0] == 1) {
            // 3D output [batch, sequence, hidden] - do mean pooling if attention mask available
            INDArray attentionMask = findAttentionMask(inputs);
            if (attentionMask != null) {
                return meanPoolWithMask(hiddenStates, attentionMask);
            } else {
                return meanPoolWithoutMask(hiddenStates);
            }
        } else if (hiddenStates.rank() == 2 && hiddenStates.shape()[0] == 1) {
            // Already pooled to [batch, hidden]
            return normalizeAndReturn(hiddenStates);
        } else if (hiddenStates.rank() == 1) {
            // Already a vector - need to reshape and track for cleanup
            INDArray reshaped = null;
            try {
                reshaped = hiddenStates.reshape(1, -1);
                return normalizeAndReturn(reshaped);
            } finally {
                // CRITICAL: Close reshaped array to prevent native memory leak
                closeArraySafely(reshaped);
            }
        } else {
            LOG.warn("[{}] Unexpected output shape: {}. Flattening.",
                    this.modelIdentifier, Arrays.toString(hiddenStates.shape()));
            INDArray reshaped = null;
            try {
                reshaped = hiddenStates.reshape(1, -1);
                return normalizeAndReturn(reshaped);
            } finally {
                // CRITICAL: Close reshaped array to prevent native memory leak
                closeArraySafely(reshaped);
            }
        }
    }
    
    private INDArray findAttentionMask(Map<String, INDArray> inputs) {
        // Find attention mask by looking for the input that was mapped from attention mask
        for (Map.Entry<String, INDArray> entry : inputs.entrySet()) {
            if (isAttentionMaskInput(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
    
    private float[] meanPoolWithMask(INDArray hiddenStates, INDArray attentionMask) {
        INDArray reshapedMask = null;  // CRITICAL: Track intermediate from reshape()
        INDArray expandedMask = null;
        INDArray maskedStates = null;
        INDArray sumStates = null;
        INDArray sumMask = null;
        INDArray sumMaskClamped = null;
        INDArray pooled = null;
        INDArray pooledReshaped = null;

        try {
            long seqLen = attentionMask.shape()[1];
            // CRITICAL FIX: Split reshape().castTo() chain to track BOTH arrays
            // Previously the intermediate from reshape() was leaked every call
            reshapedMask = attentionMask.reshape(1, seqLen, 1);
            expandedMask = reshapedMask.castTo(hiddenStates.dataType());
            maskedStates = hiddenStates.mul(expandedMask);
            sumStates = maskedStates.sum(true, 1);
            sumMask = expandedMask.sum(true, 1);
            // CRITICAL FIX: Use scalar double overload of max() which correctly clamps
            // all elements to be >= the specified value. The INDArray overload has
            // broadcasting issues when shapes don't match exactly.
            sumMaskClamped = Transforms.max(sumMask, 1e-9);
            pooled = sumStates.div(sumMaskClamped);
            pooledReshaped = pooled.reshape(1, -1);
            return normalizeAndReturn(pooledReshaped);
        } finally {
            // Close all intermediate arrays to prevent off-heap memory leaks
            // CRITICAL: Close reshapedMask BEFORE expandedMask (it's the parent)
            closeArraySafely(reshapedMask);
            closeArraySafely(expandedMask);
            closeArraySafely(maskedStates);
            closeArraySafely(sumStates);
            closeArraySafely(sumMask);
            closeArraySafely(sumMaskClamped);
            closeArraySafely(pooled);
            closeArraySafely(pooledReshaped);
        }
    }

    private float[] meanPoolWithoutMask(INDArray hiddenStates) {
        INDArray pooled = null;
        INDArray pooledReshaped = null;

        try {
            pooled = hiddenStates.mean(1);
            pooledReshaped = pooled.reshape(1, -1);
            return normalizeAndReturn(pooledReshaped);
        } finally {
            closeArraySafely(pooled);
            closeArraySafely(pooledReshaped);
        }
    }

    private float[] normalizeAndReturn(INDArray embedding) {
        // Validate input embedding
        if (embedding == null || embedding.isEmpty() || embedding.length() == 0) {
            LOG.error("[{}] Invalid embedding - null or empty", this.modelIdentifier);
            return null;
        }

        // Additional validation: check if data buffer is valid
        if (embedding.data() == null) {
            LOG.error("[{}] Invalid embedding - null data buffer", this.modelIdentifier);
            return null;
        }

        INDArray norm = null;
        INDArray normClamped = null;
        INDArray normalized = null;

        try {
            // Compute L2 norm manually: L2 norm = sqrt(sum(x^2))
            // Using manual computation as reduce_norm2 has had issues with native ops
            INDArray squared = null;
            INDArray sumOfSquares = null;
            try {
                squared = embedding.mul(embedding);
                sumOfSquares = squared.sum(true, 1);
                // CRITICAL FIX: Use Java's Math.max directly instead of Transforms.max.
                // Transforms.max uses ND4J scalar operations which can have workspace/buffer
                // invalidation issues causing the scalar value to be read incorrectly.
                // By extracting to Java double and using Math.max, we guarantee correct clamping.
                // The sum of squares should always be non-negative, but floating-point errors
                // in native sum reduction can produce tiny negative values (e.g., -2.64e-5).
                double sumVal = sumOfSquares.getDouble(0);
                // Handle case where native sum returns NaN or negative due to numerical issues
                if (Double.isNaN(sumVal) || sumVal < 0) {
                    LOG.warn("[{}] Sum of squares returned invalid value: {}. Using epsilon.", this.modelIdentifier, sumVal);
                    sumVal = 0.0;
                }
                double clampedVal = Math.max(sumVal, 1e-12);
                // Compute sqrt in Java as well to avoid any ND4J scalar issues
                double normVal = Math.sqrt(clampedVal);
                norm = Nd4j.scalar(sumOfSquares.dataType(), normVal);
            } finally {
                if (squared != null) squared.close();
                if (sumOfSquares != null) sumOfSquares.close();
            }

            // With clamp already applied, norm is guaranteed to be >= sqrt(1e-12) ≈ 1e-6
            normClamped = norm;
            normalized = embedding.div(normClamped);

            // Validate normalized embedding before toFloatVector
            if (normalized == null || normalized.data() == null) {
                LOG.error("[{}] Invalid normalized embedding - null or null data buffer", this.modelIdentifier);
                return null;
            }

            return safeToFloatVector(normalized);
        } finally {
            closeArraySafely(norm);
            closeArraySafely(normClamped);
            closeArraySafely(normalized);
        }
    }

    private void closeArraySafely(INDArray arr) {
        if (arr != null) {
            try {
                arr.close();
            } catch (Exception e) {
                LOG.debug("[{}] Error closing array: {}", this.modelIdentifier, e.getMessage());
            }
        }
    }

    /**
     * Safely converts an INDArray to a float[] vector, catching any native pointer exceptions.
     * This handles cases where the data buffer exists but the underlying native pointer is null.
     */
    private float[] safeToFloatVector(INDArray array) {
        if (array == null) {
            LOG.error("[{}] Cannot convert null array to float vector", this.modelIdentifier);
            return null;
        }
        try {
            return array.toFloatVector();
        } catch (NullPointerException e) {
            // This catches JavaCPP "Pointer address of argument X is NULL" errors
            LOG.error("[{}] Native pointer is null during toFloatVector - array may have been closed or corrupted: {}",
                    this.modelIdentifier, e.getMessage());
            return null;
        } catch (IllegalStateException e) {
            // This catches "DataBuffer was already released" errors
            LOG.error("[{}] DataBuffer was released during toFloatVector: {}",
                    this.modelIdentifier, e.getMessage());
            return null;
        } catch (Exception e) {
            LOG.error("[{}] Unexpected error during toFloatVector: {}",
                    this.modelIdentifier, e.getMessage(), e);
            return null;
        }
    }
}
