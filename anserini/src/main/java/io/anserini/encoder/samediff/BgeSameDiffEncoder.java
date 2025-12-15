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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.nd4j.linalg.profiler.ProfilerConfig;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BgeSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(BgeSameDiffEncoder.class);

    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;
    public static final boolean DEFAULT_NORMALIZE = true;

    private final String instruction;
    private final boolean normalizeEmbeddings;

    /**
     * Lock for encoding to prevent concurrent access to the SameDiff model.
     * SameDiff models are NOT thread-safe - concurrent calls to output() cause
     * undefined behavior, hangs, or crashes.
     * <p>
     * This lock is used by both encode() and encodeBatch() to ensure only one
     * thread can access the model at a time.
     */
    private final java.util.concurrent.locks.ReentrantLock batchEncodeLock = new java.util.concurrent.locks.ReentrantLock();

    /**
     * Override to provide the instruction prefix for pipelined bulk encoding.
     * This ensures parallel tokenization in bulkEncodeOptimized() produces
     * the same results as single-text encode().
     */
    @Override
    protected String getInstructionPrefix() {
        return instruction;
    }

    public BgeSameDiffEncoder(@NotNull String modelIdentifier,
                              @Nullable String instruction,
                              boolean normalizeEmbeddings,
                              boolean doLowerCaseAndStripAccents,
                              int maxSequenceLength,
                              boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        this.instruction = (instruction == null || instruction.trim().isEmpty()) ? "" : instruction.trim() + " ";
        this.normalizeEmbeddings = normalizeEmbeddings;
        LOG.info("[{}] BGE Encoder initialized. Instruction prefix: '{}', Normalize: {}",
                modelIdentifier, this.instruction.isEmpty() ? "none" : this.instruction.trim(), this.normalizeEmbeddings);
        // NOTE: NAN checking disabled for production - causes massive overhead
    }

    // Simplified constructor using BGE defaults
    public BgeSameDiffEncoder(@NotNull String modelIdentifier,
                              @Nullable String instruction,
                              boolean normalizeEmbeddings) throws IOException {
        this(modelIdentifier, instruction, normalizeEmbeddings,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS);
    }

    // Legacy constructor for backward compatibility
    @Deprecated
    public BgeSameDiffEncoder(@NotNull String modelIdentifier,
                              @NotNull String kompileManagedOnnxModelPath,
                              @NotNull String kompileManagedVocabPath,
                              @Nullable String instruction,
                              boolean normalizeEmbeddings,
                              boolean doLowerCaseAndStripAccents,
                              int maxSequenceLength,
                              boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                null, null, // Will be dynamically determined
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        this.instruction = (instruction == null || instruction.trim().isEmpty()) ? "" : instruction.trim() + " ";
        this.normalizeEmbeddings = normalizeEmbeddings;
        LOG.info("[{}] BGE Encoder initialized (legacy). Instruction prefix: '{}', Normalize: {}",
                modelIdentifier, this.instruction.isEmpty() ? "none" : this.instruction.trim(), this.normalizeEmbeddings);
        // NOTE: NAN checking disabled for production - causes massive overhead
    }

    @Override
    public float[] encode(@NotNull String query) {
        // Check for interrupt before starting encoding
        if (Thread.currentThread().isInterrupted()) {
            LOG.info("[{}] Encoding interrupted before starting for query: '{}'",
                    modelIdentifier, query.substring(0, Math.min(50, query.length())));
            return null;
        }

        // Check for shutdown before encoding
        if (!shouldProceedWithEncoding()) {
            LOG.debug("[{}] encode() rejected - shutdown in progress", modelIdentifier);
            return null;
        }

        String textToEncode = this.instruction + query;
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(textToEncode);

        // Check for interrupt after tokenization (before inference)
        if (Thread.currentThread().isInterrupted()) {
            LOG.info("[{}] Encoding interrupted after tokenization", modelIdentifier);
            return null;
        }

        // Acquire lock for inference - SameDiff models are NOT thread-safe
        // This prevents concurrent access from warmup service and pipeline
        batchEncodeLock.lock();
        try {
            // Delegate to encodeFromTokenized for actual inference
            return encodeFromTokenized(query, encoding);
        } finally {
            batchEncodeLock.unlock();
        }
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

        // Get what the model actually expects
        List<String> modelInputs = this.inputTensorNamesForModel;
        Map<String, INDArray> placeholderMap = new HashMap<>();
        Map<String, INDArray> outputMap = null;
        INDArray embeddingTensor = null;

        // Wrap all native operations in try-catch to handle JavaCPP pointer errors
        try {
            LOG.debug("[{}] Model expects {} inputs: {}", this.modelIdentifier, modelInputs.size(), modelInputs);

            // Test for existence of each standard input in the model and provide if needed
            for (String inputName : modelInputs) {
                if (isInputIdsInput(inputName)) {
                    // CRITICAL: Create 2D array directly to avoid any intermediate array leaks
                    // Nd4j.createFromArray(long[][]) creates INT64 2D array directly - no castTo or reshape needed
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
            // Some BGE checkpoints expose only a single output tensor (already pooled),
            // while others expose both the token embeddings and a pooled output.
            // Prefer the second output when available (matching prior behavior), but
            // gracefully fall back to the first output to avoid IndexOutOfBounds for
            // single-output models.
            String outputName;
            if (this.outputTensorNamesFromModel.size() > 1) {
                outputName = this.outputTensorNamesFromModel.get(1);
            } else if (!this.outputTensorNamesFromModel.isEmpty()) {
                outputName = this.outputTensorNamesFromModel.get(0);
                LOG.debug("[{}] Only one output tensor exposed by model; using '{}'",
                        this.modelIdentifier, outputName);
            } else {
                LOG.error("[{}] No output tensors exposed by model - cannot run inference", this.modelIdentifier);
                return null;
            }

            // Execute inference - this is a blocking native call that cannot be interrupted
            System.err.println("[BgeEncoder] Starting sameDiffModel.output() for outputName=" + outputName);
            System.err.flush();
            long inferenceStart = System.currentTimeMillis();
            outputMap = this.sameDiffModel.output(placeholderMap, outputName);
            long inferenceTime = System.currentTimeMillis() - inferenceStart;
            System.err.println("[BgeEncoder] sameDiffModel.output() completed in " + inferenceTime + "ms");
            System.err.flush();

            // Check for interrupt immediately after inference completes
            if (Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Encoding interrupted after inference completed", modelIdentifier);
                return null;
            }

            embeddingTensor = outputMap.get(outputName);

            if (embeddingTensor == null) {
                LOG.error("[{}] Output tensor '{}' not found. Available outputs: {}",
                        this.modelIdentifier, outputName, outputMap.keySet());
                return null;
            }

            LOG.debug("[{}] Got output tensor '{}' with shape: {}",
                    this.modelIdentifier, outputName, Arrays.toString(embeddingTensor.shape()));

            return processOutput(embeddingTensor);

        } catch (Exception e) {
            // Check if exception is due to interrupt
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Encoding interrupted during processing", modelIdentifier);
                Thread.currentThread().interrupt(); // Restore interrupt flag
                return null;
            }
            LOG.error("[{}] Error during BGE encoding for query: '{}'", this.modelIdentifier, query, e);
            return null;
        } finally {
            // CRITICAL: Always close input arrays to prevent native memory leaks
            // Input arrays are created fresh on each encode() call and must be released
            // NOTE: Do NOT check closeable() - it returns false for workspace-attached arrays
            // but we still need to try closing them. close() handles already-closed arrays gracefully.
            for (INDArray input : placeholderMap.values()) {
                if (input != null) {
                    try {
                        input.close();
                    } catch (Exception e) {
                        LOG.debug("[{}] Error closing input array: {}", modelIdentifier, e.getMessage());
                    }
                }
            }
            placeholderMap.clear();

            // CRITICAL: Close ALL output arrays in the outputMap, not just the one we used
            // The outputMap may contain multiple tensors from intermediate computations
            // NOTE: Do NOT check closeable() - workspace-attached arrays still need cleanup attempt
            if (outputMap != null) {
                for (Map.Entry<String, INDArray> entry : outputMap.entrySet()) {
                    INDArray arr = entry.getValue();
                    if (arr != null) {
                        try {
                            arr.close();
                        } catch (Exception e) {
                            LOG.debug("[{}] Error closing output array '{}': {}",
                                    modelIdentifier, entry.getKey(), e.getMessage());
                        }
                    }
                }
            }
            // Note: embeddingTensor is already closed above as part of outputMap iteration

            // CRITICAL: Trigger periodic workspace cleanup for single-encode path
            // This prevents workspace fragment accumulation during document-by-document indexing
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

        // Track all arrays that need cleanup
        INDArray clsEmbedding = null;
        INDArray reshapedEmbedding = null;
        INDArray norm = null;
        INDArray normClamped = null;
        INDArray normalizedEmbedding = null;

        try {
            if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
                // 3D tensor [batch, sequence, hidden] - extract CLS token (first token)
                clsEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
            } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
                // 2D tensor [batch, hidden] - already pooled
                clsEmbedding = embeddingTensor.getRow(0);
            } else if (embeddingTensor.rank() == 1) {
                // 1D tensor [hidden] - already a vector
                clsEmbedding = embeddingTensor;
            } else if (embeddingTensor.rank() == 4 && embeddingTensor.size(0) == 1 && embeddingTensor.size(1) > 0) {
                clsEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
            } else {
                LOG.warn("[{}] Unexpected BGE embedding tensor shape: {}. Attempting to flatten and use.",
                        this.modelIdentifier, Arrays.toString(embeddingTensor.shape()));
                clsEmbedding = embeddingTensor.reshape(-1);
            }

            // Validate extracted embedding has valid data buffer
            if (clsEmbedding == null || clsEmbedding.data() == null) {
                LOG.error("[{}] Invalid CLS embedding - null or null data buffer", this.modelIdentifier);
                return null;
            }

            reshapedEmbedding = clsEmbedding.reshape(1, -1);

            // Validate after reshape
            if (reshapedEmbedding.data() == null) {
                LOG.error("[{}] Invalid embedding after reshape - null data buffer", this.modelIdentifier);
                return null;
            }

            if (this.normalizeEmbeddings) {
                // Compute L2 norm manually: L2 norm = sqrt(sum(x^2))
                // Using manual computation as reduce_norm2 has had issues with native ops
                INDArray squared = null;
                INDArray sumOfSquares = null;
                try {
                    squared = reshapedEmbedding.mul(reshapedEmbedding);
                    sumOfSquares = squared.sum(true, 1);
                    // CRITICAL FIX: Use Java-side clamping with an explicit epsilon before sqrt to
                    // avoid divide-by-zero/NaN during normalization when the embedding is all zeros
                    // or when numerical noise produces a tiny negative accumulator.
                    double sumVal = sumOfSquares.getDouble(0);
                    if (!Double.isFinite(sumVal) || sumVal < 0) {
                        LOG.warn("[{}] Sum of squares returned invalid value: {}. Using epsilon.", this.modelIdentifier, sumVal);
                        sumVal = 0.0;
                    }
                    // Always add a tiny epsilon to keep the denominator strictly positive
                    double normVal = Math.sqrt(sumVal + 1e-12);
                    norm = Nd4j.scalar(sumOfSquares.dataType(), normVal);
                } finally {
                    if (squared != null) squared.close();
                    if (sumOfSquares != null) sumOfSquares.close();
                }

                // With clamp already applied, norm is guaranteed to be >= sqrt(1e-12) ≈ 1e-6
                normClamped = norm;
                // Use div instead of divi to create a new array and avoid modifying reshapedEmbedding
                normalizedEmbedding = reshapedEmbedding.div(normClamped);

                // Validate normalized embedding before toFloatVector
                if (normalizedEmbedding == null || normalizedEmbedding.data() == null) {
                    LOG.error("[{}] Invalid normalized embedding - null or null data buffer", this.modelIdentifier);
                    return null;
                }

                return safeToFloatVector(normalizedEmbedding);
            } else {
                return safeToFloatVector(reshapedEmbedding);
            }
        } finally {
            // CRITICAL: Close all intermediate arrays to prevent native memory leaks
            closeArraySafely(clsEmbedding, embeddingTensor);
            closeArraySafely(reshapedEmbedding, clsEmbedding);
            closeArraySafely(norm);
            closeArraySafely(normClamped, norm);
            closeArraySafely(normalizedEmbedding, reshapedEmbedding);
        }
    }

    private void closeArraySafely(INDArray arr) {
        closeArraySafely(arr, null);
    }

    private void closeArraySafely(INDArray arr, INDArray parent) {
        // Don't close if arr is the same object as parent (it's a reference, not a new array)
        if (arr != null && arr != parent) {
            try {
                arr.close();
            } catch (Exception e) {
                LOG.debug("[{}] Error closing array: {}", modelIdentifier, e.getMessage());
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

    // ========== ADAPTIVE INFERENCE BATCH SIZE ==========
    // Dynamically calculated based on sequence length and available resources.
    //
    // Key insight: Transformer attention is O(n²) in sequence length, so batch size
    // must scale inversely with sequence length to maintain reasonable latency.
    //
    // CPU Inference Characteristics:
    // - Memory bandwidth limited, not compute limited
    // - Large batches cause cache thrashing
    // - OpenMP parallelization has synchronization overhead
    //
    // Recommended batch sizes for CPU inference:
    // | Sequence Length | Optimal Batch | Max Batch | Expected Time |
    // |-----------------|---------------|-----------|---------------|
    // | 64 tokens       | 64            | 128       | ~1s           |
    // | 128 tokens      | 16            | 32        | ~2s           |
    // | 256 tokens      | 4             | 8         | ~5s           |
    // | 512 tokens      | 4             | 8         | ~10s          |

    // Base constants for dynamic calculation
    // VERY CONSERVATIVE for CPU inference - transformer attention is O(n²)
    // A batch of 4 texts × 512 tokens = ~1.3M attention ops per layer × 12 layers = ~15M ops
    // This should complete in ~5-10 seconds on a modern CPU
    private static final int BASE_OPTIMAL_BATCH_SIZE = 4;   // For 512-token sequences
    private static final int BASE_MAX_BATCH_SIZE = 8;       // For 512-token sequences
    private static final int REFERENCE_SEQ_LENGTH = 512;    // Base sequence length for calculations

    // Absolute bounds
    private static final int ABSOLUTE_MIN_BATCH_SIZE = 1;
    private static final int ABSOLUTE_MAX_BATCH_SIZE = 64;  // Reduced from 128 for CPU

    // Memory-based scaling factor (calculated once at startup)
    private static final double MEMORY_SCALE_FACTOR;

    static {
        // Calculate memory scaling factor based on available heap
        long maxHeapMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        // Scale batch sizes based on available memory
        // Base assumption: 8GB heap = scale factor 1.0
        // Less memory = smaller batches, more memory = slightly larger batches (capped)
        if (maxHeapMB < 4096) {
            MEMORY_SCALE_FACTOR = 0.5;  // 4GB or less: halve batch sizes
        } else if (maxHeapMB < 8192) {
            MEMORY_SCALE_FACTOR = 0.75; // 4-8GB: reduce batch sizes
        } else if (maxHeapMB < 16384) {
            MEMORY_SCALE_FACTOR = 1.0;  // 8-16GB: use base batch sizes
        } else {
            MEMORY_SCALE_FACTOR = 1.5;  // 16GB+: can use larger batches
        }

        LogManager.getLogger(BgeSameDiffEncoder.class).info(
                "Dynamic batch sizing initialized: heap={}MB, memoryScale={}, " +
                "base optimal/max batch for 512 tokens: {}/{}",
                maxHeapMB, MEMORY_SCALE_FACTOR, BASE_OPTIMAL_BATCH_SIZE, BASE_MAX_BATCH_SIZE);
    }

    /**
     * Calculate optimal batch size based on the maximum sequence length in the batch.
     *
     * <p>The batch size scales inversely with sequence length because:
     * <ul>
     *   <li>Attention complexity is O(n²) in sequence length</li>
     *   <li>Memory usage scales linearly with batch_size × seq_len²</li>
     *   <li>CPU cache efficiency degrades with larger working sets</li>
     * </ul>
     *
     * @param maxSeqLength The maximum sequence length in the batch
     * @return Optimal batch size for this sequence length
     */
    public static int calculateOptimalBatchSize(int maxSeqLength) {
        if (maxSeqLength <= 0) {
            maxSeqLength = REFERENCE_SEQ_LENGTH;
        }

        // Scale inversely with sequence length squared (attention complexity)
        // For seq_len=256 (half of 512), we can use 4x the batch size
        // For seq_len=128 (quarter of 512), we can use 16x the batch size
        double seqLengthRatio = (double) REFERENCE_SEQ_LENGTH / maxSeqLength;
        double scaleFactor = seqLengthRatio * seqLengthRatio; // Square because attention is O(n²)

        // Apply memory scale factor
        scaleFactor *= MEMORY_SCALE_FACTOR;

        // Calculate and clamp
        int optimalBatch = (int) Math.round(BASE_OPTIMAL_BATCH_SIZE * scaleFactor);
        return Math.max(ABSOLUTE_MIN_BATCH_SIZE, Math.min(optimalBatch, ABSOLUTE_MAX_BATCH_SIZE));
    }

    /**
     * Calculate maximum batch size based on the maximum sequence length.
     *
     * @param maxSeqLength The maximum sequence length in the batch
     * @return Maximum batch size for this sequence length
     */
    public static int calculateMaxBatchSize(int maxSeqLength) {
        if (maxSeqLength <= 0) {
            maxSeqLength = REFERENCE_SEQ_LENGTH;
        }

        double seqLengthRatio = (double) REFERENCE_SEQ_LENGTH / maxSeqLength;
        double scaleFactor = seqLengthRatio * seqLengthRatio * MEMORY_SCALE_FACTOR;

        int maxBatch = (int) Math.round(BASE_MAX_BATCH_SIZE * scaleFactor);
        return Math.max(ABSOLUTE_MIN_BATCH_SIZE, Math.min(maxBatch, ABSOLUTE_MAX_BATCH_SIZE));
    }

    /**
     * Estimate the computational cost of a batch.
     * Used for logging and adaptive decisions.
     *
     * @param batchSize Number of texts in batch
     * @param maxSeqLength Maximum sequence length
     * @return Estimated cost in arbitrary units (higher = more expensive)
     */
    public static long estimateBatchCost(int batchSize, int maxSeqLength) {
        // Cost ≈ batch_size × seq_len² × num_layers
        // Using 12 layers as typical for BERT-base
        return (long) batchSize * maxSeqLength * maxSeqLength * 12;
    }

    // Legacy static values for backward compatibility (used if caller doesn't specify)
    private static final int OPTIMAL_INFERENCE_BATCH_SIZE = calculateOptimalBatchSize(REFERENCE_SEQ_LENGTH);
    private static final int MAX_INFERENCE_BATCH_SIZE = calculateMaxBatchSize(REFERENCE_SEQ_LENGTH);

    // Batch inference is always supported for SameDiff models with proper padding
    // The encodeSingleInferenceBatch() method handles dynamic batching correctly
    private volatile boolean batchInferenceSupported = true;

    /**
     * TRUE BATCH ENCODING - Process multiple texts with DYNAMIC batch sizing.
     *
     * <p>This method now uses sequence-length-aware dynamic batching:
     * <ol>
     *   <li>Tokenize all texts first to determine actual sequence lengths</li>
     *   <li>Calculate optimal batch size based on max sequence length</li>
     *   <li>Split into appropriately-sized sub-batches</li>
     *   <li>Process each sub-batch with a single forward pass</li>
     * </ol>
     *
     * <p>This prevents the "apparent deadlock" issue where large batches with
     * long sequences cause extremely long computation times on CPU.</p>
     *
     * @param texts List of texts to encode
     * @return List of embeddings, one per input text. Null entries for failed encodings.
     */
    @Override
    public List<float[]> encodeBatch(@NotNull List<String> texts) {
        System.err.println("[BgeEncoder] encodeBatch called with " + texts.size() + " texts, acquiring lock...");
        System.err.flush();

        if (texts.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        // Check for shutdown before acquiring lock
        if (!shouldProceedWithEncoding()) {
            System.err.println("[BgeEncoder] encodeBatch rejected - shutdown in progress");
            System.err.flush();
            return null;
        }

        // Acquire lock to prevent concurrent SameDiff model access
        // This is CRITICAL - the warmup service may be calling encode() concurrently
        batchEncodeLock.lock();
        try {
            System.err.println("[BgeEncoder] Lock acquired, processing " + texts.size() + " texts");
            System.err.flush();

            // For single text, tokenize and encode directly (we already have the lock)
            if (texts.size() == 1) {
                System.err.println("[BgeEncoder] Single text - encoding directly...");
                System.err.flush();
                long start = System.currentTimeMillis();
                String text = texts.get(0);
                String textToEncode = this.instruction + text;
                SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(textToEncode);
                float[] result = encodeFromTokenized(text, encoding);
                System.err.println("[BgeEncoder] Single text encoded: " + (result != null ? result.length + " dims" : "null") + " in " + (System.currentTimeMillis() - start) + "ms");
                System.err.flush();
                List<float[]> results = new java.util.ArrayList<>(1);
                results.add(result);
                return results;
            }

            // If batch inference is not supported, fall back to sequential
            if (!batchInferenceSupported) {
                return encodeSequential(texts);
            }

            // ========== DYNAMIC BATCH SIZING ==========
            // First, tokenize ALL texts to determine actual sequence lengths
            // This allows us to calculate the optimal batch size
            return encodeBatchWithDynamicSizing(texts);

        } finally {
            batchEncodeLock.unlock();
            System.err.println("[BgeEncoder] Lock released");
            System.err.flush();
        }
    }

    /**
     * Encode batch with dynamic sizing based on actual sequence lengths.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Tokenize all texts upfront</li>
     *   <li>Find max sequence length across all texts</li>
     *   <li>Calculate optimal batch size for this sequence length</li>
     *   <li>Split into sub-batches and process</li>
     * </ol>
     */
    private List<float[]> encodeBatchWithDynamicSizing(List<String> texts) {
        long totalStartTime = System.currentTimeMillis();
        int numTexts = texts.size();

        // Step 1: Tokenize all texts and track sequence lengths
        System.err.println("[DYNAMIC-BATCH] Step 1: Tokenizing " + numTexts + " texts to determine sequence lengths...");
        System.err.flush();

        List<SamediffBertTokenizerPreProcessor.BertEncoding> allEncodings = new java.util.ArrayList<>(numTexts);
        int maxSeqLength = 0;
        int totalTokens = 0;
        int minSeqLength = Integer.MAX_VALUE;

        long tokenizeStart = System.currentTimeMillis();
        for (int i = 0; i < numTexts; i++) {
            if (Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Tokenization interrupted at {}/{}", modelIdentifier, i, numTexts);
                return null;
            }

            String text = texts.get(i);
            String textToEncode = (instruction != null && !instruction.isEmpty())
                    ? instruction + text : text;
            SamediffBertTokenizerPreProcessor.BertEncoding enc = this.tokenizerPreProcessor.encode(textToEncode);
            allEncodings.add(enc);

            int seqLen = enc.inputIds.length;
            totalTokens += seqLen;
            if (seqLen > maxSeqLength) maxSeqLength = seqLen;
            if (seqLen < minSeqLength) minSeqLength = seqLen;
        }
        long tokenizeTime = System.currentTimeMillis() - tokenizeStart;

        double avgSeqLength = numTexts > 0 ? (double) totalTokens / numTexts : 0;
        System.err.println("[DYNAMIC-BATCH] Tokenization complete: " + tokenizeTime + "ms, " +
                "minSeq=" + minSeqLength + ", maxSeq=" + maxSeqLength + ", avgSeq=" + String.format("%.1f", avgSeqLength));
        System.err.flush();

        // Step 2: Calculate optimal batch size based on max sequence length
        int optimalBatch = calculateOptimalBatchSize(maxSeqLength);
        int maxBatch = calculateMaxBatchSize(maxSeqLength);
        long estimatedCost = estimateBatchCost(numTexts, maxSeqLength);

        System.err.println("[DYNAMIC-BATCH] For maxSeq=" + maxSeqLength + ": optimalBatch=" + optimalBatch +
                ", maxBatch=" + maxBatch + ", estimatedCost=" + estimatedCost);
        System.err.flush();

        // Step 3: Determine if we need to sub-batch
        if (numTexts <= optimalBatch) {
            // Process all in one batch
            System.err.println("[DYNAMIC-BATCH] Processing all " + numTexts + " texts in single batch");
            System.err.flush();

            List<float[]> results = encodeSingleInferenceBatchFromEncodings(texts, allEncodings, maxSeqLength);

            if (results == null && batchInferenceSupported) {
                LOG.warn("[{}] Batch inference failed - falling back to sequential", modelIdentifier);
                batchInferenceSupported = false;
                return encodeSequentialFromEncodings(texts, allEncodings);
            }

            long totalTime = System.currentTimeMillis() - totalStartTime;
            System.err.println("[DYNAMIC-BATCH] Complete: " + numTexts + " texts in " + totalTime + "ms " +
                    "(" + String.format("%.2f", (double)totalTime / numTexts) + " ms/text)");
            System.err.flush();

            return results;
        }

        // Step 4: Sub-batch processing
        int numSubBatches = (numTexts + optimalBatch - 1) / optimalBatch;
        System.err.println("[DYNAMIC-BATCH] Splitting into " + numSubBatches + " sub-batches of ~" + optimalBatch + " texts");
        System.err.flush();

        List<float[]> allResults = new java.util.ArrayList<>(numTexts);
        long embeddingStartTime = System.currentTimeMillis();

        for (int batchIdx = 0; batchIdx < numSubBatches; batchIdx++) {
            if (Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Sub-batch processing interrupted at batch {}/{}", modelIdentifier, batchIdx + 1, numSubBatches);
                return null;
            }

            int startIdx = batchIdx * optimalBatch;
            int endIdx = Math.min(startIdx + optimalBatch, numTexts);
            int subBatchSize = endIdx - startIdx;

            // Get sub-lists for this batch
            List<String> subTexts = texts.subList(startIdx, endIdx);
            List<SamediffBertTokenizerPreProcessor.BertEncoding> subEncodings = allEncodings.subList(startIdx, endIdx);

            // Find max sequence length for this sub-batch (may be shorter than global max)
            int subBatchMaxSeq = 0;
            for (SamediffBertTokenizerPreProcessor.BertEncoding enc : subEncodings) {
                if (enc.inputIds.length > subBatchMaxSeq) {
                    subBatchMaxSeq = enc.inputIds.length;
                }
            }

            System.err.println("[DYNAMIC-BATCH] Sub-batch " + (batchIdx + 1) + "/" + numSubBatches +
                    ": " + subBatchSize + " texts, maxSeq=" + subBatchMaxSeq);
            System.err.flush();

            long subBatchStart = System.currentTimeMillis();
            List<float[]> subResults = encodeSingleInferenceBatchFromEncodings(subTexts, subEncodings, subBatchMaxSeq);
            long subBatchTime = System.currentTimeMillis() - subBatchStart;

            if (subResults == null) {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                // Fall back to sequential for this sub-batch
                LOG.warn("[{}] Sub-batch {} failed - using sequential encoding", modelIdentifier, batchIdx + 1);
                subResults = encodeSequentialFromEncodings(subTexts, subEncodings);
                if (subResults == null) {
                    return null;
                }
            }

            allResults.addAll(subResults);

            double throughput = subBatchTime > 0 ? (subBatchSize * 1000.0 / subBatchTime) : 0;
            System.err.println("[DYNAMIC-BATCH] Sub-batch " + (batchIdx + 1) + " complete: " +
                    subBatchTime + "ms (" + String.format("%.1f", throughput) + " texts/sec)");
            System.err.flush();
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;
        long embeddingTime = System.currentTimeMillis() - embeddingStartTime;
        double overallThroughput = totalTime > 0 ? (numTexts * 1000.0 / totalTime) : 0;

        System.err.println("[DYNAMIC-BATCH] All sub-batches complete: " + numTexts + " texts in " + totalTime + "ms " +
                "(tokenize=" + tokenizeTime + "ms, embed=" + embeddingTime + "ms, " +
                String.format("%.1f", overallThroughput) + " texts/sec)");
        System.err.flush();

        return allResults;
    }

    /**
     * Encode texts using pre-computed encodings (avoids re-tokenization).
     */
    private List<float[]> encodeSequentialFromEncodings(List<String> texts,
                                                         List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings) {
        List<float[]> results = new java.util.ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            results.add(encodeFromTokenized(texts.get(i), encodings.get(i)));
        }
        return results;
    }

    /**
     * Sequential encoding for models that don't support batch inference.
     * Still efficient because DAG caching amortizes rebuild cost after first execution.
     * NOTE: This method is called while holding the lock, so we call encodeFromTokenized directly.
     */
    private List<float[]> encodeSequential(List<String> texts) {
        long startTime = System.currentTimeMillis();
        List<float[]> results = new java.util.ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                LOG.debug("[{}] Sequential encoding interrupted at {}/{}", modelIdentifier, i, texts.size());
                return null;
            }
            String text = texts.get(i);
            String textToEncode = this.instruction + text;
            SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(textToEncode);
            results.add(encodeFromTokenized(text, encoding));
        }
        long elapsed = System.currentTimeMillis() - startTime;
        if (texts.size() > 10) {
            LOG.debug("[{}] Sequential encoding: {} texts in {}ms ({:.1f} ms/text)",
                    modelIdentifier, texts.size(), elapsed, (double) elapsed / texts.size());
        }
        return results;
    }

    /**
     * Process large batches by sub-batching to control memory usage.
     * Falls back to sequential encoding if batch inference fails.
     *
     * @param texts All texts to encode
     * @return Combined results from all sub-batches
     */
    private List<float[]> encodeBatchWithSubBatching(List<String> texts) {
        int numTexts = texts.size();
        int numSubBatches = (numTexts + OPTIMAL_INFERENCE_BATCH_SIZE - 1) / OPTIMAL_INFERENCE_BATCH_SIZE;

        LOG.info("[{}] Large batch ({} texts) - splitting into {} sub-batches of ~{} texts each",
                modelIdentifier, numTexts, numSubBatches, OPTIMAL_INFERENCE_BATCH_SIZE);

        List<float[]> allResults = new java.util.ArrayList<>(numTexts);
        long totalStartTime = System.currentTimeMillis();

        for (int i = 0; i < numSubBatches; i++) {
            if (Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Sub-batch encoding interrupted", modelIdentifier);
                return null;
            }

            int startIdx = i * OPTIMAL_INFERENCE_BATCH_SIZE;
            int endIdx = Math.min(startIdx + OPTIMAL_INFERENCE_BATCH_SIZE, numTexts);
            List<String> subBatch = texts.subList(startIdx, endIdx);

            // Process this sub-batch
            List<float[]> subResults = encodeSingleInferenceBatch(subBatch);

            if (subResults == null) {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                // Batch inference failed - fall back to sequential for remaining texts
                if (batchInferenceSupported) {
                    LOG.warn("[{}] Sub-batch {} failed - falling back to sequential for remaining {} texts",
                            modelIdentifier, i + 1, numTexts - startIdx);
                    batchInferenceSupported = false;
                }
                // Encode remaining texts sequentially
                List<String> remaining = texts.subList(startIdx, numTexts);
                List<float[]> seqResults = encodeSequential(remaining);
                if (seqResults != null) {
                    allResults.addAll(seqResults);
                }
                return allResults.isEmpty() ? null : allResults;
            }

            allResults.addAll(subResults);

            LOG.debug("[{}] Sub-batch {}/{} complete: {} texts embedded",
                    modelIdentifier, i + 1, numSubBatches, subBatch.size());
        }

        long totalElapsed = System.currentTimeMillis() - totalStartTime;
        LOG.info("[{}] All {} sub-batches complete: {} texts in {}ms ({:.1f} ms/text)",
                modelIdentifier, numSubBatches, numTexts, totalElapsed,
                (double) totalElapsed / numTexts);

        return allResults;
    }

    /**
     * Encode a single inference batch (up to MAX_INFERENCE_BATCH_SIZE texts).
     * This is the core method that creates NDArrays and calls sameDiffModel.output().
     */
    private List<float[]> encodeSingleInferenceBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        if (texts.size() == 1) {
            float[] result = encode(texts.get(0));
            List<float[]> results = new java.util.ArrayList<>(1);
            results.add(result);
            return results;
        }

        long startTime = System.currentTimeMillis();
        long stepStart;

        // ========== STEP 1: TOKENIZATION ==========
        stepStart = System.currentTimeMillis();
        System.err.println("[INFERENCE-TIMING] Step 1: Tokenizing " + texts.size() + " texts...");
        System.err.flush();

        List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings = new java.util.ArrayList<>(texts.size());
        int maxLen = 0;
        int totalTokens = 0;

        for (String text : texts) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            String textToEncode = (instruction != null && !instruction.isEmpty())
                    ? instruction + text : text;
            SamediffBertTokenizerPreProcessor.BertEncoding enc = this.tokenizerPreProcessor.encode(textToEncode);
            encodings.add(enc);
            totalTokens += enc.inputIds.length;
            if (enc.inputIds.length > maxLen) {
                maxLen = enc.inputIds.length;
            }
        }
        long tokenizeTime = System.currentTimeMillis() - stepStart;
        System.err.println("[INFERENCE-TIMING] Step 1 DONE: tokenization=" + tokenizeTime + "ms, " +
                "totalTokens=" + totalTokens + ", maxSeqLen=" + maxLen + ", avgSeqLen=" + (totalTokens / texts.size()));
        System.err.flush();

        // ========== STEP 2: CREATE PADDED TENSORS ==========
        stepStart = System.currentTimeMillis();
        System.err.println("[INFERENCE-TIMING] Step 2: Creating padded tensors [" + texts.size() + " x " + maxLen + "]...");
        System.err.flush();

        int batchSize = texts.size();
        long[][] batchInputIds = new long[batchSize][maxLen];
        long[][] batchAttentionMask = new long[batchSize][maxLen];
        long[][] batchTokenTypeIds = new long[batchSize][maxLen];

        for (int i = 0; i < batchSize; i++) {
            SamediffBertTokenizerPreProcessor.BertEncoding enc = encodings.get(i);
            int seqLen = enc.inputIds.length;
            System.arraycopy(enc.inputIds, 0, batchInputIds[i], 0, seqLen);
            System.arraycopy(enc.attentionMask, 0, batchAttentionMask[i], 0, seqLen);
            if (enc.tokenTypeIds != null) {
                System.arraycopy(enc.tokenTypeIds, 0, batchTokenTypeIds[i], 0, seqLen);
            }
        }
        long paddingTime = System.currentTimeMillis() - stepStart;
        System.err.println("[INFERENCE-TIMING] Step 2 DONE: padding=" + paddingTime + "ms");
        System.err.flush();

        // ========== STEP 3: CREATE ND4J TENSORS ==========
        stepStart = System.currentTimeMillis();
        System.err.println("[INFERENCE-TIMING] Step 3: Creating ND4J tensors...");
        System.err.flush();

        Map<String, INDArray> placeholderMap = new HashMap<>();
        Map<String, INDArray> outputMap = null;
        List<float[]> results = new java.util.ArrayList<>(batchSize);
        long tensorCreateTime = 0;

        try {
            for (String inputName : this.inputTensorNamesForModel) {
                if (isInputIdsInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchInputIds));
                } else if (isAttentionMaskInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchAttentionMask));
                } else if (isTokenTypeIdsInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchTokenTypeIds));
                }
            }
            tensorCreateTime = System.currentTimeMillis() - stepStart;
            System.err.println("[INFERENCE-TIMING] Step 3 DONE: tensorCreate=" + tensorCreateTime + "ms, " +
                    "inputs=" + placeholderMap.keySet());
            System.err.flush();

            // CRITICAL FIX: For batch size > 1, use the first output (token embeddings) instead of
            // the pooler output (index 1). The pooler's Gemm operation fails with batch > 1 because
            // the Gather operation produces [batch, 1, 1, hidden] shape which xw_plus_b can't handle.
            String outputName;
            boolean useTokenEmbeddings = batchSize > 1 && this.outputTensorNamesFromModel.size() > 1;

            if (useTokenEmbeddings) {
                // Use first output (token embeddings) for batch inference
                outputName = this.outputTensorNamesFromModel.get(0);
                LOG.debug("[{}] Batch size {} > 1: using token embeddings output '{}' to avoid pooler Gemm issue",
                        modelIdentifier, batchSize, outputName);
            } else if (this.outputTensorNamesFromModel.size() > 1) {
                outputName = this.outputTensorNamesFromModel.get(1);
            } else if (!this.outputTensorNamesFromModel.isEmpty()) {
                outputName = this.outputTensorNamesFromModel.get(0);
            } else {
                LOG.error("[{}] No output tensors", this.modelIdentifier);
                return null;
            }

            // ========== STEP 4: FORWARD PASS (THE CRITICAL STEP) ==========
            stepStart = System.currentTimeMillis();
            System.err.println("[INFERENCE-TIMING] Step 4: STARTING model.output() for batch=" + batchSize +
                    ", seqLen=" + maxLen + ", output=" + outputName +
                    (useTokenEmbeddings ? " (token embeddings)" : " (pooler)"));
            System.err.flush();

            outputMap = this.sameDiffModel.output(placeholderMap, outputName);

            long forwardTime = System.currentTimeMillis() - stepStart;
            System.err.println("[INFERENCE-TIMING] Step 4 DONE: forwardPass=" + forwardTime + "ms (" +
                    String.format("%.2f", forwardTime / 1000.0) + "s), " +
                    "throughput=" + String.format("%.1f", batchSize * 1000.0 / Math.max(1, forwardTime)) + " texts/s");
            System.err.flush();

            if (Thread.currentThread().isInterrupted()) {
                return null;
            }

            INDArray batchOutput = outputMap.get(outputName);
            if (batchOutput == null) {
                LOG.error("[{}] Output tensor not found", modelIdentifier);
                return null;
            }

            // ========== STEP 5: EXTRACT EMBEDDINGS ==========
            stepStart = System.currentTimeMillis();
            long[] outputShape = batchOutput.shape();
            System.err.println("[INFERENCE-TIMING] Step 5: Extracting embeddings from output shape=" +
                    java.util.Arrays.toString(outputShape));
            System.err.flush();

            for (int i = 0; i < batchSize; i++) {
                try {
                    results.add(extractSingleEmbedding(batchOutput, i));
                } catch (Exception e) {
                    LOG.warn("[{}] Failed to extract embedding {}: {}", modelIdentifier, i, e.getMessage());
                    results.add(null);
                }
            }
            long extractTime = System.currentTimeMillis() - stepStart;
            System.err.println("[INFERENCE-TIMING] Step 5 DONE: extraction=" + extractTime + "ms for " + batchSize + " embeddings");
            System.err.flush();

            long elapsed = System.currentTimeMillis() - startTime;
            System.err.println("[INFERENCE-TIMING] TOTAL: " + elapsed + "ms for batch of " + batchSize +
                    " (tokenize=" + tokenizeTime + "ms, padding=" + paddingTime + "ms, " +
                    "tensorCreate=" + tensorCreateTime + "ms, forward=" + (elapsed - tokenizeTime - paddingTime - tensorCreateTime - extractTime) + "ms, extract=" + extractTime + "ms)");
            System.err.flush();

            LOG.debug("[{}] Inference batch: {} texts in {}ms ({:.1f} ms/text)",
                    modelIdentifier, batchSize, elapsed, (double) elapsed / batchSize);

            return results;

        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            LOG.error("[{}] Error in inference batch: {}", modelIdentifier, e.getMessage(), e);
            return null;
        } finally {
            for (INDArray arr : placeholderMap.values()) {
                if (arr != null) try { arr.close(); } catch (Exception ignored) {}
            }
            if (outputMap != null) {
                for (INDArray arr : outputMap.values()) {
                    if (arr != null) try { arr.close(); } catch (Exception ignored) {}
                }
            }
            maybeCleanupWorkspaces();
        }
    }

    /**
     * Encode a batch using pre-computed tokenizations.
     * This avoids re-tokenizing when dynamic batching has already tokenized the texts.
     *
     * @param texts Original texts (for logging/debugging)
     * @param encodings Pre-computed tokenizations
     * @param maxSeqLength Maximum sequence length in the batch (for padding)
     * @return List of embeddings, or null on failure
     */
    private List<float[]> encodeSingleInferenceBatchFromEncodings(
            List<String> texts,
            List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings,
            int maxSeqLength) {

        if (encodings.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        if (encodings.size() == 1) {
            float[] result = encodeFromTokenized(texts.get(0), encodings.get(0));
            List<float[]> results = new java.util.ArrayList<>(1);
            results.add(result);
            return results;
        }

        long startTime = System.currentTimeMillis();
        long stepStart;
        int batchSize = encodings.size();

        // ========== STEP 1: CREATE PADDED TENSORS ==========
        stepStart = System.currentTimeMillis();
        System.err.println("[INFERENCE-FROM-ENCODINGS] Creating padded tensors [" + batchSize + " x " + maxSeqLength + "]...");
        System.err.flush();

        // Calculate total tokens for batch info
        int totalTokens = 0;
        for (SamediffBertTokenizerPreProcessor.BertEncoding enc : encodings) {
            totalTokens += enc.inputIds.length;
        }

        // Update batch info - PADDING step
        int embDim = this.normalizeEmbeddings ? 768 : 768; // Default BERT dimension, will be updated after output
        updateBatchInfo(batchSize, maxSeqLength, embDim, totalTokens,
                new long[]{batchSize, maxSeqLength}, new long[]{batchSize, embDim}, "PADDING");

        long[][] batchInputIds = new long[batchSize][maxSeqLength];
        long[][] batchAttentionMask = new long[batchSize][maxSeqLength];
        long[][] batchTokenTypeIds = new long[batchSize][maxSeqLength];

        for (int i = 0; i < batchSize; i++) {
            SamediffBertTokenizerPreProcessor.BertEncoding enc = encodings.get(i);
            int seqLen = enc.inputIds.length;
            System.arraycopy(enc.inputIds, 0, batchInputIds[i], 0, seqLen);
            System.arraycopy(enc.attentionMask, 0, batchAttentionMask[i], 0, seqLen);
            if (enc.tokenTypeIds != null) {
                System.arraycopy(enc.tokenTypeIds, 0, batchTokenTypeIds[i], 0, seqLen);
            }
        }
        long paddingTime = System.currentTimeMillis() - stepStart;
        System.err.println("[INFERENCE-FROM-ENCODINGS] Padding done: " + paddingTime + "ms");
        System.err.flush();

        // ========== STEP 2: CREATE ND4J TENSORS ==========
        stepStart = System.currentTimeMillis();

        Map<String, INDArray> placeholderMap = new HashMap<>();
        Map<String, INDArray> outputMap = null;
        List<float[]> results = new java.util.ArrayList<>(batchSize);

        try {
            for (String inputName : this.inputTensorNamesForModel) {
                if (isInputIdsInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchInputIds));
                } else if (isAttentionMaskInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchAttentionMask));
                } else if (isTokenTypeIdsInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchTokenTypeIds));
                }
            }
            long tensorCreateTime = System.currentTimeMillis() - stepStart;

            // CRITICAL FIX: For batch size > 1, use the first output (token embeddings) instead of
            // the pooler output (index 1). The pooler's Gemm operation fails with batch > 1 because
            // the Gather operation produces [batch, 1, 1, hidden] shape which xw_plus_b can't handle.
            // By using token embeddings, we extract CLS ourselves in extractSingleEmbedding().
            String outputName;
            boolean useTokenEmbeddings = batchSize > 1 && this.outputTensorNamesFromModel.size() > 1;

            if (useTokenEmbeddings) {
                // Use first output (token embeddings) for batch inference
                outputName = this.outputTensorNamesFromModel.get(0);
                LOG.debug("[{}] Batch size {} > 1: using token embeddings output '{}' to avoid pooler Gemm issue",
                        modelIdentifier, batchSize, outputName);
            } else if (this.outputTensorNamesFromModel.size() > 1) {
                outputName = this.outputTensorNamesFromModel.get(1);
            } else if (!this.outputTensorNamesFromModel.isEmpty()) {
                outputName = this.outputTensorNamesFromModel.get(0);
            } else {
                LOG.error("[{}] No output tensors", this.modelIdentifier);
                return null;
            }

            // ========== STEP 3: FORWARD PASS ==========
            stepStart = System.currentTimeMillis();
            long estimatedCost = estimateBatchCost(batchSize, maxSeqLength);
            System.err.println("[INFERENCE-FROM-ENCODINGS] Forward pass: batch=" + batchSize +
                    ", seqLen=" + maxSeqLength + ", output=" + outputName +
                    (useTokenEmbeddings ? " (token embeddings)" : " (pooler)") +
                    ", estimatedCost=" + estimatedCost);
            System.err.flush();

            // Update batch info - FORWARD_PASS step (this is where the work happens)
            updateBatchInfo(batchSize, maxSeqLength, embDim, totalTokens,
                    new long[]{batchSize, maxSeqLength}, new long[]{batchSize, embDim}, "FORWARD_PASS");

            outputMap = this.sameDiffModel.output(placeholderMap, outputName);

            long forwardTime = System.currentTimeMillis() - stepStart;
            double throughput = batchSize * 1000.0 / Math.max(1, forwardTime);
            System.err.println("[INFERENCE-FROM-ENCODINGS] Forward done: " + forwardTime + "ms (" +
                    String.format("%.1f", throughput) + " texts/sec)");
            System.err.flush();

            if (Thread.currentThread().isInterrupted()) {
                return null;
            }

            INDArray batchOutput = outputMap.get(outputName);
            if (batchOutput == null) {
                LOG.error("[{}] Output tensor not found", modelIdentifier);
                return null;
            }

            // Update batch info with actual output shape
            long[] actualOutputShape = batchOutput.shape();
            int actualEmbDim = actualOutputShape.length > 1 ? (int) actualOutputShape[actualOutputShape.length - 1] : embDim;
            updateBatchInfo(batchSize, maxSeqLength, actualEmbDim, totalTokens,
                    new long[]{batchSize, maxSeqLength}, actualOutputShape, "EXTRACTING");

            // ========== STEP 4: EXTRACT EMBEDDINGS ==========
            stepStart = System.currentTimeMillis();
            for (int i = 0; i < batchSize; i++) {
                try {
                    results.add(extractSingleEmbedding(batchOutput, i));
                } catch (Exception e) {
                    LOG.warn("[{}] Failed to extract embedding {}: {}", modelIdentifier, i, e.getMessage());
                    results.add(null);
                }
            }
            long extractTime = System.currentTimeMillis() - stepStart;

            long elapsed = System.currentTimeMillis() - startTime;
            System.err.println("[INFERENCE-FROM-ENCODINGS] Complete: " + batchSize + " texts in " + elapsed + "ms " +
                    "(pad=" + paddingTime + "ms, tensor=" + tensorCreateTime + "ms, forward=" + forwardTime + "ms, extract=" + extractTime + "ms)");
            System.err.flush();

            return results;

        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            LOG.error("[{}] Error in inference batch: {}", modelIdentifier, e.getMessage(), e);
            return null;
        } finally {
            // Clear batch info now that we're done
            clearBatchInfo();

            for (INDArray arr : placeholderMap.values()) {
                if (arr != null) try { arr.close(); } catch (Exception ignored) {}
            }
            if (outputMap != null) {
                for (INDArray arr : outputMap.values()) {
                    if (arr != null) try { arr.close(); } catch (Exception ignored) {}
                }
            }
            maybeCleanupWorkspaces();
        }
    }

    /**
     * Extract a single embedding from the batch output tensor.
     * Handles various output shapes:
     * - [batch, hidden] - already pooled
     * - [batch, seq, hidden] - token embeddings, extract CLS (index 0)
     * - [batch, 1, hidden] - pooler output with extra dim
     * - [batch, 1, 1, hidden] - pooler output with two extra dims
     */
    private float[] extractSingleEmbedding(INDArray batchOutput, int batchIndex) {
        INDArray singleOutput = null;
        INDArray clsEmbedding = null;
        INDArray reshapedEmbedding = null;
        // MEMORY LEAK FIX: Track normalization arrays for cleanup in finally block
        INDArray squared = null;
        INDArray sumOfSquares = null;
        INDArray normalized = null;

        try {
            long[] shape = batchOutput.shape();

            if (shape.length == 4) {
                // [batch, 1, 1, hidden] or [batch, seq, 1, hidden] - extract from 4D tensor
                // This handles the case where pooler Gather produces extra dimensions
                if (shape[1] == 1 && shape[2] == 1) {
                    // [batch, 1, 1, hidden] - already has CLS, just remove extra dims
                    singleOutput = batchOutput.get(NDArrayIndex.point(batchIndex),
                            NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
                } else {
                    // [batch, seq, ?, hidden] - get first token (CLS) from first position
                    singleOutput = batchOutput.get(NDArrayIndex.point(batchIndex),
                            NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
                }
                clsEmbedding = singleOutput;
                LOG.debug("[{}] Extracted from 4D tensor shape {} at batch index {}",
                        modelIdentifier, Arrays.toString(shape), batchIndex);
            } else if (shape.length == 3) {
                // [batch, seq_len, hidden] - extract CLS token (index 0 of sequence)
                singleOutput = batchOutput.get(NDArrayIndex.point(batchIndex), NDArrayIndex.point(0), NDArrayIndex.all());
                clsEmbedding = singleOutput;
            } else if (shape.length == 2) {
                // [batch, hidden] - already pooled
                singleOutput = batchOutput.getRow(batchIndex);
                clsEmbedding = singleOutput;
            } else if (shape.length == 1) {
                // [hidden] - single vector, likely batch_size=1 already extracted
                clsEmbedding = batchOutput;
            } else {
                LOG.warn("[{}] Unexpected batch output shape: {}", modelIdentifier, Arrays.toString(shape));
                return null;
            }

            reshapedEmbedding = clsEmbedding.reshape(1, -1);

            if (this.normalizeEmbeddings) {
                // L2 normalization
                squared = reshapedEmbedding.mul(reshapedEmbedding);
                sumOfSquares = squared.sum(true, 1);
                double sumVal = sumOfSquares.getDouble(0);
                if (!Double.isFinite(sumVal) || sumVal < 0) {
                    sumVal = 0.0;
                }
                double normVal = Math.sqrt(sumVal + 1e-12);
                normalized = reshapedEmbedding.div(normVal);

                return safeToFloatVector(normalized);
            } else {
                return safeToFloatVector(reshapedEmbedding);
            }
        } finally {
            // MEMORY LEAK FIX: Ensure ALL intermediate arrays are closed
            // Close normalization arrays if they were created
            if (squared != null) {
                try { squared.close(); } catch (Exception ignored) {}
            }
            if (sumOfSquares != null) {
                try { sumOfSquares.close(); } catch (Exception ignored) {}
            }
            if (normalized != null) {
                try { normalized.close(); } catch (Exception ignored) {}
            }
            // Close reshapedEmbedding if it's a different object from clsEmbedding
            if (reshapedEmbedding != null && reshapedEmbedding != clsEmbedding) {
                try { reshapedEmbedding.close(); } catch (Exception ignored) {}
            }
            // Close view arrays (singleOutput/clsEmbedding are views but closing releases wrapper objects)
            // Don't close clsEmbedding if it's the same as batchOutput (rank 1 case)
            if (singleOutput != null && singleOutput != batchOutput) {
                try { singleOutput.close(); } catch (Exception ignored) {}
            }
        }
    }
}
