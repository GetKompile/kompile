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

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CosDprDistilSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(CosDprDistilSameDiffEncoder.class);

    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    public CosDprDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        LOG.info("[{}] CosDprDistilSameDiffEncoder initialized.", modelIdentifier);
    }

    // Simplified constructor using defaults
    public CosDprDistilSameDiffEncoder(@NotNull String modelIdentifier) throws IOException {
        this(modelIdentifier,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS);
    }

    // Legacy constructor for backward compatibility
    @Deprecated
    public CosDprDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                       @NotNull String kompileManagedModelPath,
                                       @NotNull String kompileManagedVocabPath,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
                null, null, // Will be dynamically determined
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        LOG.info("[{}] CosDprDistilSameDiffEncoder initialized (legacy).", modelIdentifier);
    }

    @Override
    public float[] encode(@NotNull String query) {
        // Check for interrupt before starting encoding
        if (Thread.currentThread().isInterrupted()) {
            LOG.info("[{}] Encoding interrupted before starting for query: '{}'",
                    modelIdentifier, query.substring(0, Math.min(50, query.length())));
            return null;
        }

        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

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
     * <p>CosDPR encoder has no instruction prefix, so the pipelined approach
     * in the base class will work fully - parallel tokenization feeds directly
     * into sequential inference without re-tokenization.</p>
     *
     * @param query The original text (for logging purposes)
     * @param encoding The pre-tokenized BertEncoding
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
        INDArray embeddingTensor = null;

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

            embeddingTensor = outputMap.get(outputTensorName);

            if (embeddingTensor == null) {
                LOG.error("[{}] Output tensor '{}' not found. Available outputs: {}",
                        this.modelIdentifier, outputTensorName, outputMap.keySet());
                return null;
            }

            LOG.debug("[{}] Got output tensor '{}' with shape: {}",
                    this.modelIdentifier, outputTensorName, Arrays.toString(embeddingTensor.shape()));

            return processOutput(embeddingTensor);

        } catch (Exception e) {
            // Check if exception is due to interrupt
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Encoding interrupted during processing", modelIdentifier);
                Thread.currentThread().interrupt(); // Restore interrupt flag
                return null;
            }
            LOG.error("[{}] Error during CosDPR encoding for query: '{}'", this.modelIdentifier, query, e);
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
            // Note: embeddingTensor is already closed above as part of outputMap iteration

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
    
    private float[] processOutput(INDArray embeddingTensor) {
        // Validate input tensor
        if (embeddingTensor == null || embeddingTensor.isEmpty() || embeddingTensor.length() == 0) {
            LOG.error("[{}] Invalid embedding tensor - null or empty", this.modelIdentifier);
            return null;
        }

        // Additional validation: check if data buffer is valid
        if (embeddingTensor.data() == null) {
            LOG.error("[{}] Invalid embedding tensor - null data buffer", this.modelIdentifier);
            return null;
        }

        if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
            // 2D tensor [batch, hidden] - already pooled
            return safeToFloatVector(embeddingTensor);
        } else if (embeddingTensor.rank() == 1) {
            // 1D tensor [hidden] - already a vector
            return safeToFloatVector(embeddingTensor);
        } else if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1) {
            // 3D tensor [batch, sequence, hidden] - flatten to vector
            LOG.warn("[{}] Got 3D tensor, flattening to vector for CosDPR. Shape: {}",
                    this.modelIdentifier, Arrays.toString(embeddingTensor.shape()));
            INDArray reshaped = null;
            try {
                reshaped = embeddingTensor.reshape(1, -1);
                if (reshaped == null || reshaped.data() == null) {
                    LOG.error("[{}] Invalid embedding after reshape - null or null data buffer", this.modelIdentifier);
                    return null;
                }
                return safeToFloatVector(reshaped);
            } finally {
                // CRITICAL: Close reshaped array to prevent native memory leak
                if (reshaped != null) {
                    try {
                        reshaped.close();
                    } catch (Exception e) {
                        LOG.debug("[{}] Error closing reshaped array: {}", this.modelIdentifier, e.getMessage());
                    }
                }
            }
        } else {
            LOG.warn("[{}] Unexpected CosDPR embedding tensor shape: {}. Attempting to flatten and use.",
                    this.modelIdentifier, Arrays.toString(embeddingTensor.shape()));
            INDArray reshaped = null;
            try {
                reshaped = embeddingTensor.reshape(1, -1);
                if (reshaped == null || reshaped.data() == null) {
                    LOG.error("[{}] Invalid embedding after reshape - null or null data buffer", this.modelIdentifier);
                    return null;
                }
                return safeToFloatVector(reshaped);
            } finally {
                // CRITICAL: Close reshaped array to prevent native memory leak
                if (reshaped != null) {
                    try {
                        reshaped.close();
                    } catch (Exception e) {
                        LOG.debug("[{}] Error closing reshaped array: {}", this.modelIdentifier, e.getMessage());
                    }
                }
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
