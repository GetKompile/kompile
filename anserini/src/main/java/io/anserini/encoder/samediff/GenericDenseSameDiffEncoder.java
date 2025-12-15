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
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.ops.transforms.Transforms;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GenericDenseSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(GenericDenseSameDiffEncoder.class);

    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;
    public static final boolean DEFAULT_NORMALIZE = true;

    private final boolean normalizeOutput;

    // ========== INFERENCE BENCHMARKING STATS ==========
    // These track cumulative timing to identify bottlenecks
    private final java.util.concurrent.atomic.AtomicLong totalTokenizeTimeNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalInferenceTimeNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalPostProcessTimeNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalArrayCreationTimeNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalEncodeCalls = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalTokensProcessed = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong slowestInferenceNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicInteger slowestInferenceTokens = new java.util.concurrent.atomic.AtomicInteger(0);

    // Stats reporting interval
    private static final int STATS_REPORT_INTERVAL = 10;

    /**
     * Lock for encoding to prevent concurrent access to the SameDiff model.
     * SameDiff models are NOT thread-safe - concurrent calls to output() cause
     * undefined behavior, hangs, or crashes.
     * <p>
     * This lock is used by both encode() and encodeBatch() to ensure only one
     * thread can access the model at a time.
     */
    private final java.util.concurrent.locks.ReentrantLock encodeLock = new java.util.concurrent.locks.ReentrantLock();

    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens,
                                       boolean normalizeOutput)
            throws IOException {
        super(modelIdentifier, doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        this.normalizeOutput = normalizeOutput;
        LOG.info("[{}] GenericDenseSameDiffEncoder initialized. Normalize output: {}.",
                this.modelIdentifier, this.normalizeOutput);
    }

    // Simplified constructor using all defaults
    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier) throws IOException {
        this(modelIdentifier,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS, 
                DEFAULT_MAX_SEQUENCE_LENGTH, 
                DEFAULT_ADD_SPECIAL_TOKENS,
                DEFAULT_NORMALIZE);
    }

    // Legacy constructor for backward compatibility
    @Deprecated
    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier,
                                       @NotNull String kompileManagedOnnxModelPath,
                                       @NotNull String kompileManagedVocabPath,
                                       @NotNull List<String> inputTensorNamesForModel,
                                       @NotNull String outputTensorNameFromModel,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens,
                                       boolean normalizeOutput)
            throws IOException {
        super(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                inputTensorNamesForModel,
                List.of(outputTensorNameFromModel),
                doLowerCaseAndStripAccents,
                maxSequenceLength,
                addSpecialTokens);

        this.normalizeOutput = normalizeOutput;
        LOG.info("[{}] GenericDenseSameDiffEncoder initialized (legacy). Normalize output: {}.",
                this.modelIdentifier, this.normalizeOutput);
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

        // ========== BENCHMARK: Tokenization ==========
        long tokenizeStart = System.nanoTime();
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);
        long tokenizeEnd = System.nanoTime();
        long tokenizeTimeNanos = tokenizeEnd - tokenizeStart;
        totalTokenizeTimeNanos.addAndGet(tokenizeTimeNanos);

        int tokenCount = encoding != null ? encoding.inputIds.length : 0;
        int charCount = query.length();

        // Log detailed stats for this chunk
        LOG.info("[{}] CHUNK STATS: chars={}, tokens={}, tokenize_time={:.2f}ms, text_preview='{}'",
                modelIdentifier, charCount, tokenCount, tokenizeTimeNanos / 1_000_000.0,
                query.substring(0, Math.min(60, query.length())).replace("\n", " "));

        // Check for interrupt after tokenization (before inference)
        if (Thread.currentThread().isInterrupted()) {
            LOG.info("[{}] Encoding interrupted after tokenization", modelIdentifier);
            return null;
        }

        // Acquire lock for inference - SameDiff models are NOT thread-safe
        // This prevents concurrent access from warmup service and pipeline
        encodeLock.lock();
        try {
            // Delegate to encodeFromTokenized for actual inference
            return encodeFromTokenized(query, encoding);
        } finally {
            encodeLock.unlock();
        }
    }

    /**
     * Override from SameDiffEncoder to use pre-tokenized encoding directly.
     * This avoids re-tokenization in the pipelined bulk encoding path.
     *
     * <p>The pipelined encoder in SameDiffEncoder tokenizes texts in parallel,
     * then queues them for sequential inference. By overriding this method,
     * we use the pre-tokenized encoding directly instead of re-tokenizing.</p>
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

        long encodeStartNanos = System.nanoTime();
        int tokenCount = encoding.inputIds.length;

        // Track all arrays that need cleanup to prevent off-heap memory leaks
        Map<String, INDArray> placeholderMap = new HashMap<>();
        Map<String, INDArray> outputMap = null;
        INDArray embeddingTensor = null;

        try {
            // Get what the model actually expects
            List<String> modelInputs = this.inputTensorNamesForModel;

            LOG.debug("[{}] Model expects {} inputs: {}", this.modelIdentifier, modelInputs.size(), modelInputs);

            // ========== BENCHMARK: Array Creation ==========
            long arrayCreateStart = System.nanoTime();

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

            long arrayCreateEnd = System.nanoTime();
            long arrayCreateTimeNanos = arrayCreateEnd - arrayCreateStart;
            totalArrayCreationTimeNanos.addAndGet(arrayCreateTimeNanos);

            String outputTensorName = this.outputTensorNamesFromModel.get(0);

            // ========== BENCHMARK: Inference ==========
            long inferenceStart = System.nanoTime();

            // Execute inference - this is a blocking native call that cannot be interrupted
            outputMap = this.sameDiffModel.output(placeholderMap, outputTensorName);

            long inferenceEnd = System.nanoTime();
            long inferenceTimeNanos = inferenceEnd - inferenceStart;
            totalInferenceTimeNanos.addAndGet(inferenceTimeNanos);
            totalTokensProcessed.addAndGet(tokenCount);

            // Track slowest inference for analysis
            if (inferenceTimeNanos > slowestInferenceNanos.get()) {
                slowestInferenceNanos.set(inferenceTimeNanos);
                slowestInferenceTokens.set(tokenCount);
            }

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

            // ========== BENCHMARK: Post-processing ==========
            long postProcessStart = System.nanoTime();
            float[] result = processOutputTensor(embeddingTensor);
            long postProcessEnd = System.nanoTime();
            long postProcessTimeNanos = postProcessEnd - postProcessStart;
            totalPostProcessTimeNanos.addAndGet(postProcessTimeNanos);

            // Increment total calls and maybe report stats
            long callCount = totalEncodeCalls.incrementAndGet();
            long totalTimeNanos = System.nanoTime() - encodeStartNanos;

            // Log timing breakdown for each chunk
            LOG.info("[{}] INFERENCE TIMING: tokens={}, array_create={:.2f}ms, inference={:.2f}ms, postprocess={:.2f}ms, total={:.2f}ms, throughput={:.1f} tok/sec",
                    modelIdentifier, tokenCount,
                    arrayCreateTimeNanos / 1_000_000.0,
                    inferenceTimeNanos / 1_000_000.0,
                    postProcessTimeNanos / 1_000_000.0,
                    totalTimeNanos / 1_000_000.0,
                    tokenCount > 0 ? (tokenCount * 1_000_000_000.0 / inferenceTimeNanos) : 0);

            // Periodic cumulative stats report
            if (callCount % STATS_REPORT_INTERVAL == 0) {
                reportCumulativeStats();
            }

            return result;
        } catch (Exception e) {
            // Check if exception is due to interrupt
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Encoding interrupted during processing", modelIdentifier);
                Thread.currentThread().interrupt(); // Restore interrupt flag
                return null;
            }
            LOG.error("[{}] Error during GenericDense encoding for query: '{}'", this.modelIdentifier, query, e);
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

    protected float[] processOutputTensor(INDArray embeddingTensor) {
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

        INDArray clsEmbedding = null;
        INDArray reshapedEmbedding = null;
        INDArray norm = null;
        INDArray normMax = null;

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
            } else {
                LOG.warn("[{}] Unexpected GenericDense embedding tensor shape: {}. Attempting to flatten and use.",
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

            if (this.normalizeOutput) {
                // Compute L2 norm manually: L2 norm = sqrt(sum(x^2))
                // Using manual computation as reduce_norm2 has had issues with native ops
                INDArray squared = null;
                INDArray sumOfSquares = null;
                try {
                    squared = reshapedEmbedding.mul(reshapedEmbedding);
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
                normMax = norm;
                // Use div instead of divi to avoid modifying reshapedEmbedding in place
                // since we need to close it properly
                INDArray normalizedEmbedding = reshapedEmbedding.div(normMax);

                // Validate normalized embedding before toFloatVector
                if (normalizedEmbedding == null || normalizedEmbedding.data() == null) {
                    LOG.error("[{}] Invalid normalized embedding - null or null data buffer", this.modelIdentifier);
                    return null;
                }

                try {
                    return safeToFloatVector(normalizedEmbedding);
                } finally {
                    // CRITICAL: Close the normalized embedding (created by div, not divi)
                    closeArraySafely(normalizedEmbedding, reshapedEmbedding);
                }
            } else {
                return safeToFloatVector(reshapedEmbedding);
            }
        } finally {
            // CRITICAL: Close all intermediate arrays to prevent off-heap memory leaks
            // Use helper method to safely close arrays while avoiding double-close on same references
            closeArraySafely(clsEmbedding, embeddingTensor);
            closeArraySafely(reshapedEmbedding, clsEmbedding);
            closeArraySafely(norm);
            closeArraySafely(normMax, norm);
        }
    }

    /**
     * Safely close an INDArray, checking for null.
     */
    private void closeArraySafely(INDArray arr) {
        closeArraySafely(arr, null);
    }

    /**
     * Safely close an INDArray if it's not the same reference as parent.
     * This prevents double-closing when one array is just a reference to another.
     */
    private void closeArraySafely(INDArray arr, INDArray parent) {
        if (arr != null && arr != parent) {
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
    protected float[] safeToFloatVector(INDArray array) {
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

    /**
     * Report cumulative performance statistics for analysis.
     * This helps identify where time is being spent across all encoding operations.
     */
    private void reportCumulativeStats() {
        long calls = totalEncodeCalls.get();
        long totalTokens = totalTokensProcessed.get();

        double avgTokenizeMs = calls > 0 ? (totalTokenizeTimeNanos.get() / 1_000_000.0) / calls : 0;
        double avgArrayMs = calls > 0 ? (totalArrayCreationTimeNanos.get() / 1_000_000.0) / calls : 0;
        double avgInferenceMs = calls > 0 ? (totalInferenceTimeNanos.get() / 1_000_000.0) / calls : 0;
        double avgPostProcessMs = calls > 0 ? (totalPostProcessTimeNanos.get() / 1_000_000.0) / calls : 0;

        double totalMs = avgTokenizeMs + avgArrayMs + avgInferenceMs + avgPostProcessMs;
        double avgTokensPerCall = calls > 0 ? (double) totalTokens / calls : 0;
        double overallTokensPerSec = totalInferenceTimeNanos.get() > 0
                ? (totalTokens * 1_000_000_000.0 / totalInferenceTimeNanos.get()) : 0;

        // Calculate percentage breakdown
        double tokenizePct = totalMs > 0 ? (avgTokenizeMs / totalMs) * 100 : 0;
        double arrayPct = totalMs > 0 ? (avgArrayMs / totalMs) * 100 : 0;
        double inferencePct = totalMs > 0 ? (avgInferenceMs / totalMs) * 100 : 0;
        double postPct = totalMs > 0 ? (avgPostProcessMs / totalMs) * 100 : 0;

        LOG.info("==============================================================");
        LOG.info("[{}] CUMULATIVE PERFORMANCE STATS (after {} encode calls)", modelIdentifier, calls);
        LOG.info("==============================================================");
        LOG.info("[{}] Total tokens processed: {}, avg tokens/call: {:.1f}",
                modelIdentifier, totalTokens, avgTokensPerCall);
        LOG.info("[{}] AVERAGE TIME BREAKDOWN:", modelIdentifier);
        LOG.info("[{}]   Tokenization:    {:.2f}ms ({:.1f}%)", modelIdentifier, avgTokenizeMs, tokenizePct);
        LOG.info("[{}]   Array Creation:  {:.2f}ms ({:.1f}%)", modelIdentifier, avgArrayMs, arrayPct);
        LOG.info("[{}]   Inference:       {:.2f}ms ({:.1f}%)", modelIdentifier, avgInferenceMs, inferencePct);
        LOG.info("[{}]   Post-processing: {:.2f}ms ({:.1f}%)", modelIdentifier, avgPostProcessMs, postPct);
        LOG.info("[{}]   Total avg time:  {:.2f}ms", modelIdentifier, totalMs);
        LOG.info("[{}] Overall throughput: {:.1f} tokens/sec", modelIdentifier, overallTokensPerSec);
        LOG.info("[{}] Slowest single inference: {:.2f}ms with {} tokens",
                modelIdentifier, slowestInferenceNanos.get() / 1_000_000.0, slowestInferenceTokens.get());
        LOG.info("==============================================================");
    }

    /**
     * Get a summary of the current performance statistics.
     * Can be called externally for debugging.
     */
    public String getPerformanceStats() {
        long calls = totalEncodeCalls.get();
        long totalTokens = totalTokensProcessed.get();

        double avgInferenceMs = calls > 0 ? (totalInferenceTimeNanos.get() / 1_000_000.0) / calls : 0;
        double avgTokensPerCall = calls > 0 ? (double) totalTokens / calls : 0;
        double overallTokensPerSec = totalInferenceTimeNanos.get() > 0
                ? (totalTokens * 1_000_000_000.0 / totalInferenceTimeNanos.get()) : 0;

        return String.format(
                "Encoder[%s]: %d calls, %d total tokens, avg %.1f tokens/call, avg inference %.2fms, throughput %.1f tok/sec",
                modelIdentifier, calls, totalTokens, avgTokensPerCall, avgInferenceMs, overallTokensPerSec);
    }

    /**
     * Reset performance statistics. Useful for benchmarking different configurations.
     */
    public void resetPerformanceStats() {
        totalTokenizeTimeNanos.set(0);
        totalInferenceTimeNanos.set(0);
        totalPostProcessTimeNanos.set(0);
        totalArrayCreationTimeNanos.set(0);
        totalEncodeCalls.set(0);
        totalTokensProcessed.set(0);
        slowestInferenceNanos.set(0);
        slowestInferenceTokens.set(0);
        LOG.info("[{}] Performance statistics reset", modelIdentifier);
    }

    // ========== ADAPTIVE INFERENCE BATCH SIZE ==========
    // Auto-configured based on available heap memory at startup.
    // Larger batches = fewer sameDiffModel.output() calls = better throughput.
    // Memory budget: ~25% of max heap for embedding batches.
    // Each sample uses ~2MB for padded attention matrices (seq_len=512, hidden=768).
    private static final int OPTIMAL_INFERENCE_BATCH_SIZE;
    private static final int MAX_INFERENCE_BATCH_SIZE;

    static {
        // Calculate batch sizes based on available heap memory
        long maxHeapMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        // Use ~25% of heap for embedding batches
        long memoryBudgetMB = maxHeapMB / 4;

        // Estimate ~2MB per sample (conservative for 512 seq length)
        long memoryPerSampleMB = 2;
        int calculatedBatch = (int) (memoryBudgetMB / memoryPerSampleMB);

        // Clamp to reasonable range: 64 (minimum for efficiency) to 2048 (practical max)
        OPTIMAL_INFERENCE_BATCH_SIZE = Math.max(64, Math.min(calculatedBatch, 2048));
        MAX_INFERENCE_BATCH_SIZE = Math.min(OPTIMAL_INFERENCE_BATCH_SIZE * 2, 4096);

        LogManager.getLogger(GenericDenseSameDiffEncoder.class).info(
                "Auto-configured inference batch sizes: optimal={}, max={} (heap={}MB, budget={}MB)",
                OPTIMAL_INFERENCE_BATCH_SIZE, MAX_INFERENCE_BATCH_SIZE, maxHeapMB, memoryBudgetMB);
    }

    // Batch inference is always supported for SameDiff models with proper padding
    // The encodeSingleInferenceBatch() method handles dynamic batching correctly
    private volatile boolean batchInferenceSupported = true;

    /**
     * TRUE BATCH ENCODING - Process multiple texts in optimally-sized inference batches.
     *
     * <p>Batches multiple texts together with padding and processes them in one forward pass.
     * If the model doesn't support batch inference, falls back to sequential encoding.</p>
     *
     * @param texts List of texts to encode in a single batch
     * @return List of embeddings, one per input text. Null entries for failed encodings.
     */
    @Override
    public List<float[]> encodeBatch(@NotNull List<String> texts) {
        if (texts.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        // Check for shutdown before acquiring lock
        if (!shouldProceedWithEncoding()) {
            LOG.debug("[{}] encodeBatch rejected - shutdown in progress", modelIdentifier);
            return null;
        }

        // Acquire lock to prevent concurrent SameDiff model access
        // This is CRITICAL - the warmup service may be calling encode() concurrently
        encodeLock.lock();
        try {
            // For single text, tokenize and encode directly (we already have the lock)
            if (texts.size() == 1) {
                String text = texts.get(0);
                SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(text);
                float[] result = encodeFromTokenized(text, encoding);
                List<float[]> results = new java.util.ArrayList<>(1);
                results.add(result);
                return results;
            }

            // If batch inference is not supported, fall back to sequential
            if (!batchInferenceSupported) {
                return encodeSequential(texts);
            }

            // If texts exceed max inference batch size, sub-batch to control memory
            if (texts.size() > MAX_INFERENCE_BATCH_SIZE) {
                return encodeBatchWithSubBatching(texts);
            }

            // Try batch inference - fall back to sequential if it fails
            List<float[]> result = encodeSingleInferenceBatch(texts);
            if (result == null && batchInferenceSupported) {
                // First batch failure - disable and retry sequentially
                LOG.warn("[{}] Batch inference failed - falling back to sequential encoding", modelIdentifier);
                batchInferenceSupported = false;
                return encodeSequential(texts);
            }
            return result;
        } finally {
            encodeLock.unlock();
        }
    }

    /**
     * Sequential encoding for models that don't support batch inference.
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
            SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(text);
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
     */
    private List<float[]> encodeBatchWithSubBatching(List<String> texts) {
        int numTexts = texts.size();
        int numSubBatches = (numTexts + OPTIMAL_INFERENCE_BATCH_SIZE - 1) / OPTIMAL_INFERENCE_BATCH_SIZE;

        LOG.info("[{}] Large batch ({} texts) - splitting into {} sub-batches of ~{}",
                modelIdentifier, numTexts, numSubBatches, OPTIMAL_INFERENCE_BATCH_SIZE);

        List<float[]> allResults = new java.util.ArrayList<>(numTexts);

        for (int i = 0; i < numSubBatches; i++) {
            if (Thread.currentThread().isInterrupted()) return null;

            int startIdx = i * OPTIMAL_INFERENCE_BATCH_SIZE;
            int endIdx = Math.min(startIdx + OPTIMAL_INFERENCE_BATCH_SIZE, numTexts);
            List<String> subBatch = texts.subList(startIdx, endIdx);

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
        }

        return allResults;
    }

    /**
     * Encode a single inference batch (up to MAX_INFERENCE_BATCH_SIZE texts).
     * Includes detailed timing instrumentation for performance analysis.
     */
    private List<float[]> encodeSingleInferenceBatch(List<String> texts) {
        if (texts.isEmpty()) return new java.util.ArrayList<>();

        if (texts.size() == 1) {
            float[] result = encode(texts.get(0));
            List<float[]> results = new java.util.ArrayList<>(1);
            results.add(result);
            return results;
        }

        long batchStartNanos = System.nanoTime();

        // ========== BENCHMARK: Batch Tokenization ==========
        long tokenizeStart = System.nanoTime();
        List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings = new java.util.ArrayList<>(texts.size());
        int maxLen = 0;
        int totalTokens = 0;

        for (String text : texts) {
            if (Thread.currentThread().isInterrupted()) return null;
            SamediffBertTokenizerPreProcessor.BertEncoding enc = this.tokenizerPreProcessor.encode(text);
            encodings.add(enc);
            if (enc.inputIds.length > maxLen) maxLen = enc.inputIds.length;
            totalTokens += enc.inputIds.length;
        }
        long tokenizeEnd = System.nanoTime();
        long batchTokenizeTimeNanos = tokenizeEnd - tokenizeStart;

        // ========== BENCHMARK: Batch Padding/Array Creation ==========
        long paddingStart = System.nanoTime();

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
        long paddingEnd = System.nanoTime();
        long paddingTimeNanos = paddingEnd - paddingStart;

        // Create ND4J tensors and run single forward pass
        Map<String, INDArray> placeholderMap = new HashMap<>();
        Map<String, INDArray> outputMap = null;
        List<float[]> results = new java.util.ArrayList<>(batchSize);

        try {
            // ========== BENCHMARK: ND4J Tensor Creation ==========
            long tensorStart = System.nanoTime();

            for (String inputName : this.inputTensorNamesForModel) {
                if (isInputIdsInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchInputIds));
                } else if (isAttentionMaskInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchAttentionMask));
                } else if (isTokenTypeIdsInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchTokenTypeIds));
                }
            }
            long tensorEnd = System.nanoTime();
            long tensorTimeNanos = tensorEnd - tensorStart;

            String outputTensorName = this.outputTensorNamesFromModel.get(0);

            // ========== BENCHMARK: BATCH INFERENCE ==========
            long inferenceStart = System.nanoTime();

            // SINGLE forward pass for the entire batch
            outputMap = this.sameDiffModel.output(placeholderMap, outputTensorName);

            long inferenceEnd = System.nanoTime();
            long batchInferenceTimeNanos = inferenceEnd - inferenceStart;

            // Update cumulative stats
            totalTokensProcessed.addAndGet(totalTokens);

            if (Thread.currentThread().isInterrupted()) return null;

            INDArray batchOutput = outputMap.get(outputTensorName);
            if (batchOutput == null) {
                LOG.error("[{}] Output tensor not found", modelIdentifier);
                return null;
            }

            // ========== BENCHMARK: Embedding Extraction ==========
            long extractStart = System.nanoTime();

            // Extract individual embeddings
            for (int i = 0; i < batchSize; i++) {
                try {
                    results.add(extractSingleEmbedding(batchOutput, i));
                } catch (Exception e) {
                    LOG.warn("[{}] Failed to extract embedding {}: {}", modelIdentifier, i, e.getMessage());
                    results.add(null);
                }
            }

            long extractEnd = System.nanoTime();
            long extractTimeNanos = extractEnd - extractStart;

            // Calculate totals and per-sample metrics
            long totalBatchTimeNanos = System.nanoTime() - batchStartNanos;
            double perSampleInferenceMs = batchSize > 0 ? (batchInferenceTimeNanos / 1_000_000.0) / batchSize : 0;
            double tokensPerSecond = batchInferenceTimeNanos > 0 ? (totalTokens * 1_000_000_000.0 / batchInferenceTimeNanos) : 0;

            // Log detailed batch timing
            LOG.info("[{}] BATCH INFERENCE TIMING: batch_size={}, total_tokens={}, max_seq_len={}",
                    modelIdentifier, batchSize, totalTokens, maxLen);
            LOG.info("[{}]   Tokenization:    {:.2f}ms ({:.2f}ms/sample)",
                    modelIdentifier, batchTokenizeTimeNanos / 1_000_000.0, batchTokenizeTimeNanos / 1_000_000.0 / batchSize);
            LOG.info("[{}]   Padding:         {:.2f}ms ({:.2f}ms/sample)",
                    modelIdentifier, paddingTimeNanos / 1_000_000.0, paddingTimeNanos / 1_000_000.0 / batchSize);
            LOG.info("[{}]   Tensor creation: {:.2f}ms",
                    modelIdentifier, tensorTimeNanos / 1_000_000.0);
            LOG.info("[{}]   BATCH INFERENCE: {:.2f}ms ({:.2f}ms/sample)",
                    modelIdentifier, batchInferenceTimeNanos / 1_000_000.0, perSampleInferenceMs);
            LOG.info("[{}]   Extraction:      {:.2f}ms ({:.2f}ms/sample)",
                    modelIdentifier, extractTimeNanos / 1_000_000.0, extractTimeNanos / 1_000_000.0 / batchSize);
            LOG.info("[{}]   TOTAL:           {:.2f}ms ({:.2f}ms/sample), throughput: {:.1f} tok/sec",
                    modelIdentifier, totalBatchTimeNanos / 1_000_000.0, totalBatchTimeNanos / 1_000_000.0 / batchSize, tokensPerSecond);

            return results;

        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) return null;
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

            if (this.normalizeOutput) {
                // L2 normalization
                squared = reshapedEmbedding.mul(reshapedEmbedding);
                sumOfSquares = squared.sum(true, 1);
                double sumVal = sumOfSquares.getDouble(0);
                if (Double.isNaN(sumVal) || sumVal < 0) {
                    sumVal = 0.0;
                }
                double normVal = Math.sqrt(Math.max(sumVal, 1e-12));
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
