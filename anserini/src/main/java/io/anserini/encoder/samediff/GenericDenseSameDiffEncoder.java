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

import ai.kompile.modelmanager.ModelConstants;
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

    // Cached embedding dimension (looked up from ModelConstants on first access)
    private volatile Integer cachedEmbeddingDimension = null;

    /**
     * Gets the embedding dimension for this model.
     * Uses ModelConstants to look up the dimension based on modelIdentifier.
     * @return The embedding dimension, or 0 if unknown
     */
    protected int getEmbeddingDimension() {
        if (cachedEmbeddingDimension == null) {
            Integer dim = ModelConstants.getEmbeddingDimension(modelIdentifier);
            cachedEmbeddingDimension = (dim != null) ? dim : 0;
        }
        return cachedEmbeddingDimension;
    }

    // NOTE: This class uses the parent's encodeLock via getEncoderLock() for ALL model access.
    // DO NOT add a separate lock here - it causes deadlocks with the parent class's lock.
    // CRITICAL: A previous version had a shadowing `encodeLock` field which caused deadlocks
    // because parent's bulkEncodeOptimized() used parent's lock while encode()/encodeBatch()
    // used the subclass's lock, allowing concurrent SameDiff access.

    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens,
                                       boolean normalizeOutput)
            throws IOException {
        super(modelIdentifier, doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        this.normalizeOutput = normalizeOutput;
        initBatchSizeDefaults();
        LOG.info("[{}] GenericDenseSameDiffEncoder initialized. Normalize output: {}, batchSize: optimal={}, max={}",
                this.modelIdentifier, this.normalizeOutput, this.instanceOptimalBatchSize, this.instanceMaxBatchSize);
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
                                       List<String> inputTensorNamesForModel,
                                       String outputTensorNameFromModel,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens,
                                       boolean normalizeOutput)
            throws IOException {
        // Handle null tensor names by passing empty lists - the parent will auto-detect from the model
        super(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                inputTensorNamesForModel != null ? inputTensorNamesForModel : List.of(),
                outputTensorNameFromModel != null ? List.of(outputTensorNameFromModel) : List.of(),
                doLowerCaseAndStripAccents,
                maxSequenceLength,
                addSpecialTokens);

        this.normalizeOutput = normalizeOutput;
        initBatchSizeDefaults();
        LOG.info("[{}] GenericDenseSameDiffEncoder initialized (legacy). Normalize output: {}, batchSize: optimal={}, max={}",
                this.modelIdentifier, this.normalizeOutput, this.instanceOptimalBatchSize, this.instanceMaxBatchSize);
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
        // Null check for AtomicLong fields - they may be null during super() constructor validation
        if (totalTokenizeTimeNanos != null) totalTokenizeTimeNanos.addAndGet(tokenizeTimeNanos);

        int tokenCount = encoding != null ? encoding.inputIds.length : 0;
        int charCount = query.length();

        // Log detailed stats for this chunk
        LOG.info("[{}] CHUNK STATS: chars={}, tokens={}, tokenize_time={}ms, text_preview='{}'",
                modelIdentifier, charCount, tokenCount, String.format("%.2f", tokenizeTimeNanos / 1_000_000.0),
                query.substring(0, Math.min(60, query.length())).replace("\n", " "));

        // Check for interrupt after tokenization (before inference)
        if (Thread.currentThread().isInterrupted()) {
            LOG.info("[{}] Encoding interrupted after tokenization", modelIdentifier);
            return null;
        }

        // Acquire lock for inference - SameDiff models are NOT thread-safe
        // This prevents concurrent access from warmup service and pipeline
        // CRITICAL: Use parent's lock to prevent deadlocks with encodeSafe() and encodeBatch()
        getEncoderLock().lock();
        try {
            // Delegate to encodeFromTokenized for actual inference
            return encodeFromTokenized(query, encoding);
        } finally {
            getEncoderLock().unlock();
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

        // ========== TOKENIZATION DIAGNOSTIC ==========
        // Check for potential tokenization issues that could cause zero-magnitude vectors
        int nonPadTokens = 0;
        int unkTokenCount = 0;
        long unkTokenId = 100; // Default BERT [UNK] token ID

        for (long tokenId : encoding.inputIds) {
            if (tokenId != 0) nonPadTokens++; // PAD is typically 0
            if (tokenId == unkTokenId) unkTokenCount++;
        }

        // Log warnings for potential issues
        if (nonPadTokens <= 2) { // Only [CLS] and [SEP]
            LOG.warn("[{}] TOKENIZATION WARNING: Input '{}' produced only {} non-padding tokens. " +
                    "This may result in poor embeddings. Token IDs: {}",
                    modelIdentifier,
                    query.length() > 50 ? query.substring(0, 50) + "..." : query,
                    nonPadTokens,
                    Arrays.toString(Arrays.copyOf(encoding.inputIds, Math.min(20, encoding.inputIds.length))));
        }

        if (unkTokenCount > 0 && unkTokenCount >= (nonPadTokens - 2)) {
            LOG.warn("[{}] TOKENIZATION WARNING: Input '{}' produced {} [UNK] tokens out of {} tokens. " +
                    "Most or all words are out-of-vocabulary. Token IDs: {}",
                    modelIdentifier,
                    query.length() > 50 ? query.substring(0, 50) + "..." : query,
                    unkTokenCount, nonPadTokens,
                    Arrays.toString(Arrays.copyOf(encoding.inputIds, Math.min(20, encoding.inputIds.length))));
        }

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

            // ========== SHAPE LOGGING: Input Array Creation ==========
            LOG.info("[{}] SHAPE CREATE: Creating input arrays for {} tokens, {} model inputs expected",
                    this.modelIdentifier, encoding.inputIds.length, modelInputs.size());

            // Test for existence of each standard input in the model and provide if needed
            for (String inputName : modelInputs) {
                if (isInputIdsInput(inputName)) {
                    // CRITICAL: Create 2D array directly to avoid intermediate array leak from castTo()
                    INDArray inputIds = Nd4j.createFromArray(new long[][]{encoding.inputIds});
                    placeholderMap.put(inputName, inputIds);
                    LOG.info("[{}] SHAPE CREATE: input_ids '{}' -> shape={}, dtype={}, first5={}",
                            this.modelIdentifier, inputName,
                            Arrays.toString(inputIds.shape()), inputIds.dataType(),
                            Arrays.toString(Arrays.copyOf(encoding.inputIds, Math.min(5, encoding.inputIds.length))));
                } else if (isAttentionMaskInput(inputName)) {
                    INDArray attentionMask = Nd4j.createFromArray(new long[][]{encoding.attentionMask});
                    placeholderMap.put(inputName, attentionMask);
                    LOG.info("[{}] SHAPE CREATE: attention_mask '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName,
                            Arrays.toString(attentionMask.shape()), attentionMask.dataType());
                } else if (isTokenTypeIdsInput(inputName)) {
                    INDArray tokenTypeIds = Nd4j.createFromArray(new long[][]{encoding.tokenTypeIds});
                    placeholderMap.put(inputName, tokenTypeIds);
                    LOG.info("[{}] SHAPE CREATE: token_type_ids '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName,
                            Arrays.toString(tokenTypeIds.shape()), tokenTypeIds.dataType());
                } else {
                    LOG.warn("[{}] Unknown model input '{}' - cannot map to tokenizer output", this.modelIdentifier, inputName);
                }
            }

            long arrayCreateEnd = System.nanoTime();
            long arrayCreateTimeNanos = arrayCreateEnd - arrayCreateStart;
            // Null check for AtomicLong fields - they may be null during super() constructor validation
            if (totalArrayCreationTimeNanos != null) totalArrayCreationTimeNanos.addAndGet(arrayCreateTimeNanos);

            String outputTensorName = this.outputTensorNamesFromModel.get(0);

            // ========== INPUT TENSOR DIAGNOSTIC ==========
            // Log input tensor stats to diagnose potential issues
            for (Map.Entry<String, INDArray> entry : placeholderMap.entrySet()) {
                INDArray tensor = entry.getValue();
                if (tensor != null && tensor.length() > 0) {
                    double min = tensor.minNumber().doubleValue();
                    double max = tensor.maxNumber().doubleValue();
                    double mean = tensor.meanNumber().doubleValue();
                    LOG.debug("[{}] Input tensor '{}': shape={}, min={}, max={}, mean={}",
                            this.modelIdentifier, entry.getKey(),
                            Arrays.toString(tensor.shape()), min, max, mean);

                    // Warn if input_ids looks suspicious
                    if (entry.getKey().toLowerCase().contains("input") && min == max) {
                        LOG.warn("[{}] SUSPICIOUS INPUT: tensor '{}' has constant value {}. All token IDs are the same!",
                                this.modelIdentifier, entry.getKey(), min);
                    }
                }
            }

            // ========== BENCHMARK: Inference ==========
            long inferenceStart = System.nanoTime();

            // ========== SHAPE LOGGING: Pre-Inference Summary ==========
            LOG.info("[{}] SHAPE INFERENCE INPUT: Calling model.output() with {} placeholders, output tensor '{}'",
                    this.modelIdentifier, placeholderMap.size(), outputTensorName);
            for (Map.Entry<String, INDArray> entry : placeholderMap.entrySet()) {
                INDArray arr = entry.getValue();
                LOG.info("[{}] SHAPE INFERENCE INPUT:   {} -> shape={}, dtype={}, rank={}",
                        this.modelIdentifier, entry.getKey(),
                        Arrays.toString(arr.shape()), arr.dataType(), arr.rank());
            }

            // Execute inference - this is a blocking native call that cannot be interrupted
            outputMap = this.sameDiffModel.output(placeholderMap, outputTensorName);

            // ========== SHAPE LOGGING: Post-Inference Output ==========
            LOG.info("[{}] SHAPE INFERENCE OUTPUT: model.output() returned {} output tensors",
                    this.modelIdentifier, outputMap.size());
            for (Map.Entry<String, INDArray> entry : outputMap.entrySet()) {
                INDArray arr = entry.getValue();
                if (arr != null) {
                    LOG.info("[{}] SHAPE INFERENCE OUTPUT:   {} -> shape={}, dtype={}, rank={}",
                            this.modelIdentifier, entry.getKey(),
                            Arrays.toString(arr.shape()), arr.dataType(), arr.rank());
                }
            }

            long inferenceEnd = System.nanoTime();
            long inferenceTimeNanos = inferenceEnd - inferenceStart;
            // Null check for AtomicLong fields - they may be null during super() constructor validation
            if (totalInferenceTimeNanos != null) totalInferenceTimeNanos.addAndGet(inferenceTimeNanos);
            if (totalTokensProcessed != null) totalTokensProcessed.addAndGet(tokenCount);

            // Track slowest inference for analysis
            if (slowestInferenceNanos != null && inferenceTimeNanos > slowestInferenceNanos.get()) {
                slowestInferenceNanos.set(inferenceTimeNanos);
                if (slowestInferenceTokens != null) slowestInferenceTokens.set(tokenCount);
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

            // ========== OUTPUT TENSOR DIAGNOSTIC ==========
            // Log output tensor stats to diagnose zero-magnitude issues
            double outMin = embeddingTensor.minNumber().doubleValue();
            double outMax = embeddingTensor.maxNumber().doubleValue();
            double outMean = embeddingTensor.meanNumber().doubleValue();

            LOG.debug("[{}] Output tensor '{}': shape={}, min={}, max={}, mean={}",
                    this.modelIdentifier, outputTensorName,
                    Arrays.toString(embeddingTensor.shape()), outMin, outMax, outMean);

            // Warn if output is all zeros or near-zero BEFORE any processing
            if (outMin == 0.0 && outMax == 0.0) {
                LOG.error("[{}] OUTPUT TENSOR IS ALL ZEROS! Model '{}' produced garbage output. " +
                        "This could be due to: (1) Incompatible model file format, (2) Model was trained with different tokenization, " +
                        "(3) Model expects different input tensor names/shapes, (4) DAG execution error in SameDiff. " +
                        "Input query: '{}', Output shape: {}",
                        this.modelIdentifier, outputTensorName,
                        query.length() > 100 ? query.substring(0, 100) + "..." : query,
                        Arrays.toString(embeddingTensor.shape()));

                // Log all available outputs for debugging
                LOG.error("[{}] Available output tensors in model: {}", this.modelIdentifier, outputMap.keySet());
                for (Map.Entry<String, INDArray> outEntry : outputMap.entrySet()) {
                    INDArray outArr = outEntry.getValue();
                    if (outArr != null) {
                        LOG.error("[{}]   Output '{}': shape={}, min={}, max={}, mean={}",
                                this.modelIdentifier, outEntry.getKey(),
                                Arrays.toString(outArr.shape()),
                                outArr.minNumber().doubleValue(),
                                outArr.maxNumber().doubleValue(),
                                outArr.meanNumber().doubleValue());
                    }
                }
            }

            // ========== BENCHMARK: Post-processing ==========
            long postProcessStart = System.nanoTime();
            float[] result = processOutputTensor(embeddingTensor);
            long postProcessEnd = System.nanoTime();
            long postProcessTimeNanos = postProcessEnd - postProcessStart;
            // Null check for AtomicLong fields - they may be null during super() constructor validation
            if (totalPostProcessTimeNanos != null) totalPostProcessTimeNanos.addAndGet(postProcessTimeNanos);

            // Increment total calls and maybe report stats
            long callCount = totalEncodeCalls != null ? totalEncodeCalls.incrementAndGet() : 0;
            long totalTimeNanos = System.nanoTime() - encodeStartNanos;

            // Log timing breakdown for each chunk
            LOG.info("[{}] INFERENCE TIMING: tokens={}, array_create={}ms, inference={}ms, postprocess={}ms, total={}ms, throughput={} tok/sec",
                    modelIdentifier, tokenCount,
                    String.format("%.2f", arrayCreateTimeNanos / 1_000_000.0),
                    String.format("%.2f", inferenceTimeNanos / 1_000_000.0),
                    String.format("%.2f", postProcessTimeNanos / 1_000_000.0),
                    String.format("%.2f", totalTimeNanos / 1_000_000.0),
                    String.format("%.1f", tokenCount > 0 ? (tokenCount * 1_000_000_000.0 / inferenceTimeNanos) : 0));

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

        // ========== SHAPE LOGGING: Process Output Tensor ==========
        LOG.info("[{}] SHAPE PROCESS: Processing output tensor shape={}, rank={}, dtype={}",
                this.modelIdentifier, Arrays.toString(embeddingTensor.shape()),
                embeddingTensor.rank(), embeddingTensor.dataType());

        INDArray clsEmbedding = null;
        INDArray reshapedEmbedding = null;
        INDArray norm = null;
        INDArray normMax = null;

        try {
            if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
                // 3D tensor [batch, sequence, hidden] - extract CLS token (first token)
                clsEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
                LOG.info("[{}] SHAPE PROCESS: Extracted CLS from 3D tensor -> clsEmbedding shape={}",
                        this.modelIdentifier, Arrays.toString(clsEmbedding.shape()));
            } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
                // 2D tensor [batch, hidden] - already pooled
                clsEmbedding = embeddingTensor.getRow(0);
                LOG.info("[{}] SHAPE PROCESS: Got row 0 from 2D tensor -> clsEmbedding shape={}",
                        this.modelIdentifier, Arrays.toString(clsEmbedding.shape()));
            } else if (embeddingTensor.rank() == 1) {
                // 1D tensor [hidden] - already a vector
                clsEmbedding = embeddingTensor;
                LOG.info("[{}] SHAPE PROCESS: Using 1D tensor directly -> clsEmbedding shape={}",
                        this.modelIdentifier, Arrays.toString(clsEmbedding.shape()));
            } else {
                LOG.warn("[{}] Unexpected GenericDense embedding tensor shape: {}. Attempting to flatten and use.",
                        this.modelIdentifier, Arrays.toString(embeddingTensor.shape()));
                clsEmbedding = embeddingTensor.reshape(-1);
                LOG.info("[{}] SHAPE PROCESS: Flattened tensor -> clsEmbedding shape={}",
                        this.modelIdentifier, Arrays.toString(clsEmbedding.shape()));
            }

            // Validate extracted embedding has valid data buffer
            if (clsEmbedding == null || clsEmbedding.data() == null) {
                LOG.error("[{}] Invalid CLS embedding - null or null data buffer", this.modelIdentifier);
                return null;
            }

            reshapedEmbedding = clsEmbedding.reshape(1, -1);

            // ========== SHAPE LOGGING: After reshape ==========
            LOG.info("[{}] SHAPE PROCESS: After reshape -> shape={}, will normalize={}",
                    this.modelIdentifier, Arrays.toString(reshapedEmbedding.shape()), this.normalizeOutput);

            // Validate after reshape
            if (reshapedEmbedding.data() == null) {
                LOG.error("[{}] Invalid embedding after reshape - null data buffer", this.modelIdentifier);
                return null;
            }

            // DEBUG: Check raw embedding statistics BEFORE normalization
            // This helps diagnose zero-magnitude vector issues
            double rawMin = reshapedEmbedding.minNumber().doubleValue();
            double rawMax = reshapedEmbedding.maxNumber().doubleValue();
            double rawMean = reshapedEmbedding.meanNumber().doubleValue();
            if (rawMin == 0.0 && rawMax == 0.0) {
                LOG.error("[{}] ZERO-MAGNITUDE VECTOR DETECTED: Raw embedding is all zeros BEFORE normalization. " +
                        "Shape: {}, This indicates the SameDiff model forward pass produced garbage output. " +
                        "Check: (1) Model file integrity, (2) Input tensor shapes match model expectations, " +
                        "(3) Tokenizer output is valid, (4) Model was trained for this input format.",
                        this.modelIdentifier, Arrays.toString(reshapedEmbedding.shape()));
            } else if (LOG.isDebugEnabled()) {
                LOG.debug("[{}] Raw embedding stats: min={}, max={}, mean={}, shape={}",
                        this.modelIdentifier, rawMin, rawMax, rawMean, Arrays.toString(reshapedEmbedding.shape()));
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
                    // ========== SHAPE LOGGING: Final normalized output ==========
                    LOG.info("[{}] SHAPE PROCESS FINAL: Normalized output shape={}, returning float[{}]",
                            this.modelIdentifier, Arrays.toString(normalizedEmbedding.shape()),
                            normalizedEmbedding.length());
                    return safeToFloatVector(normalizedEmbedding);
                } finally {
                    // CRITICAL: Close the normalized embedding (created by div, not divi)
                    closeArraySafely(normalizedEmbedding, reshapedEmbedding);
                }
            } else {
                // ========== SHAPE LOGGING: Final unnormalized output ==========
                LOG.info("[{}] SHAPE PROCESS FINAL: Unnormalized output shape={}, returning float[{}]",
                        this.modelIdentifier, Arrays.toString(reshapedEmbedding.shape()),
                        reshapedEmbedding.length());
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
        // Skip stats reporting if AtomicLong fields are null (during super() constructor validation)
        if (totalEncodeCalls == null || totalTokensProcessed == null) {
            return;
        }

        long calls = totalEncodeCalls.get();
        long totalTokens = totalTokensProcessed.get();

        double avgTokenizeMs = calls > 0 && totalTokenizeTimeNanos != null ? (totalTokenizeTimeNanos.get() / 1_000_000.0) / calls : 0;
        double avgArrayMs = calls > 0 && totalArrayCreationTimeNanos != null ? (totalArrayCreationTimeNanos.get() / 1_000_000.0) / calls : 0;
        double avgInferenceMs = calls > 0 && totalInferenceTimeNanos != null ? (totalInferenceTimeNanos.get() / 1_000_000.0) / calls : 0;
        double avgPostProcessMs = calls > 0 && totalPostProcessTimeNanos != null ? (totalPostProcessTimeNanos.get() / 1_000_000.0) / calls : 0;

        double totalMs = avgTokenizeMs + avgArrayMs + avgInferenceMs + avgPostProcessMs;
        double avgTokensPerCall = calls > 0 ? (double) totalTokens / calls : 0;
        double overallTokensPerSec = totalInferenceTimeNanos != null && totalInferenceTimeNanos.get() > 0
                ? (totalTokens * 1_000_000_000.0 / totalInferenceTimeNanos.get()) : 0;

        // Calculate percentage breakdown
        double tokenizePct = totalMs > 0 ? (avgTokenizeMs / totalMs) * 100 : 0;
        double arrayPct = totalMs > 0 ? (avgArrayMs / totalMs) * 100 : 0;
        double inferencePct = totalMs > 0 ? (avgInferenceMs / totalMs) * 100 : 0;
        double postPct = totalMs > 0 ? (avgPostProcessMs / totalMs) * 100 : 0;

        LOG.info("==============================================================");
        LOG.info("[{}] CUMULATIVE PERFORMANCE STATS (after {} encode calls)", modelIdentifier, calls);
        LOG.info("==============================================================");
        LOG.info("[{}] Total tokens processed: {}, avg tokens/call: {}",
                modelIdentifier, totalTokens, String.format("%.1f", avgTokensPerCall));
        LOG.info("[{}] AVERAGE TIME BREAKDOWN:", modelIdentifier);
        LOG.info("[{}]   Tokenization:    {}ms ({}%)", modelIdentifier, String.format("%.2f", avgTokenizeMs), String.format("%.1f", tokenizePct));
        LOG.info("[{}]   Array Creation:  {}ms ({}%)", modelIdentifier, String.format("%.2f", avgArrayMs), String.format("%.1f", arrayPct));
        LOG.info("[{}]   Inference:       {}ms ({}%)", modelIdentifier, String.format("%.2f", avgInferenceMs), String.format("%.1f", inferencePct));
        LOG.info("[{}]   Post-processing: {}ms ({}%)", modelIdentifier, String.format("%.2f", avgPostProcessMs), String.format("%.1f", postPct));
        LOG.info("[{}]   Total avg time:  {}ms", modelIdentifier, String.format("%.2f", totalMs));
        LOG.info("[{}] Overall throughput: {} tokens/sec", modelIdentifier, String.format("%.1f", overallTokensPerSec));
        if (slowestInferenceNanos != null && slowestInferenceTokens != null) {
            LOG.info("[{}] Slowest single inference: {}ms with {} tokens",
                    modelIdentifier, String.format("%.2f", slowestInferenceNanos.get() / 1_000_000.0), slowestInferenceTokens.get());
        }
        LOG.info("==============================================================");
    }

    /**
     * Get a summary of the current performance statistics.
     * Can be called externally for debugging.
     */
    public String getPerformanceStats() {
        // Return empty stats if fields are null (during super() constructor validation)
        if (totalEncodeCalls == null || totalTokensProcessed == null || totalInferenceTimeNanos == null) {
            return String.format("Encoder[%s]: stats not yet initialized", modelIdentifier);
        }

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
        // Skip reset if fields are null (during super() constructor validation)
        if (totalTokenizeTimeNanos != null) totalTokenizeTimeNanos.set(0);
        if (totalInferenceTimeNanos != null) totalInferenceTimeNanos.set(0);
        if (totalPostProcessTimeNanos != null) totalPostProcessTimeNanos.set(0);
        if (totalArrayCreationTimeNanos != null) totalArrayCreationTimeNanos.set(0);
        if (totalEncodeCalls != null) totalEncodeCalls.set(0);
        if (totalTokensProcessed != null) totalTokensProcessed.set(0);
        if (slowestInferenceNanos != null) slowestInferenceNanos.set(0);
        if (slowestInferenceTokens != null) slowestInferenceTokens.set(0);
        LOG.info("[{}] Performance statistics reset", modelIdentifier);
    }

    // ========== DYNAMIC BATCH SIZE CONFIGURATION ==========
    // Batch sizes are calculated dynamically based on sequence length.
    // Shorter sequences allow larger batches (attention is O(n²) in sequence length).
    // Sorting by sequence length groups similar-length items together to minimize padding waste.
    //
    // TUNING GUIDE:
    // - Larger batches = better throughput (less overhead per batch, better memory coalescing)
    // - Larger batches = more memory usage (O(batch * seq² * hidden) for attention)
    // - The "optimal" size balances these factors for typical workloads
    //
    // Batch size configuration - can be set via:
    // 1. UI configuration (preferred) - calls setOptimalBatchSize()/setMaxBatchSize()
    // 2. System properties (fallback) - -Dkompile.encoder.batch.optimal=N
    //
    // Base constants for dynamic calculation (for 512-token sequences)
    private static final int REFERENCE_SEQ_LENGTH = 512;
    private static final int ABSOLUTE_MIN_BATCH_SIZE = 1;

    // Static defaults (used as initial values, can be overridden per-instance)
    private static final int DEFAULT_BASE_OPTIMAL_BATCH_SIZE;
    private static final int DEFAULT_BASE_MAX_BATCH_SIZE;
    private static final int DEFAULT_ABSOLUTE_MAX_BATCH_SIZE;
    private static final double DEFAULT_MEMORY_SCALE_FACTOR;

    // Instance-level batch size configuration (can be updated at runtime via setters)
    private volatile int instanceOptimalBatchSize;
    private volatile int instanceMaxBatchSize;
    private volatile int instanceAbsoluteMaxBatchSize;
    private volatile double instanceMemoryScaleFactor;
    private volatile boolean batchSizeConfigured = false;

    // Legacy static batch sizes for backward compatibility
    private static final int OPTIMAL_INFERENCE_BATCH_SIZE;
    private static final int MAX_INFERENCE_BATCH_SIZE;

    static {
        // Read configurable batch sizes from system properties (fallback if UI config not set)
        // Default values are conservative for memory safety
        int configOptimal = Integer.getInteger("kompile.encoder.batch.optimal", 4);
        int configMax = Integer.getInteger("kompile.encoder.batch.max", 8);
        int configAbsoluteMax = Integer.getInteger("kompile.encoder.batch.absolute.max", 64);

        DEFAULT_BASE_OPTIMAL_BATCH_SIZE = Math.max(1, configOptimal);
        DEFAULT_BASE_MAX_BATCH_SIZE = Math.max(DEFAULT_BASE_OPTIMAL_BATCH_SIZE, configMax);
        DEFAULT_ABSOLUTE_MAX_BATCH_SIZE = Math.max(DEFAULT_BASE_MAX_BATCH_SIZE, configAbsoluteMax);

        // Calculate memory scaling factor based on available heap
        long maxHeapMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        if (maxHeapMB < 4096) {
            DEFAULT_MEMORY_SCALE_FACTOR = 0.5;  // 4GB or less
        } else if (maxHeapMB < 8192) {
            DEFAULT_MEMORY_SCALE_FACTOR = 0.75; // 4-8GB
        } else if (maxHeapMB < 16384) {
            DEFAULT_MEMORY_SCALE_FACTOR = 1.0;  // 8-16GB
        } else {
            DEFAULT_MEMORY_SCALE_FACTOR = 1.5;  // 16GB+
        }

        // Legacy batch sizes (used as fallback)
        long memoryBudgetMB = maxHeapMB / 4;
        long memoryPerSampleMB = 2;
        int calculatedBatch = (int) (memoryBudgetMB / memoryPerSampleMB);
        OPTIMAL_INFERENCE_BATCH_SIZE = Math.max(64, Math.min(calculatedBatch, 2048));
        MAX_INFERENCE_BATCH_SIZE = Math.min(OPTIMAL_INFERENCE_BATCH_SIZE * 2, 4096);

        // Calculate effective batch sizes for 512-token sequences
        int effectiveOptimal = (int) Math.round(DEFAULT_BASE_OPTIMAL_BATCH_SIZE * DEFAULT_MEMORY_SCALE_FACTOR);
        int effectiveMax = (int) Math.round(DEFAULT_BASE_MAX_BATCH_SIZE * DEFAULT_MEMORY_SCALE_FACTOR);

        LogManager.getLogger(GenericDenseSameDiffEncoder.class).info(
                "Dynamic batch sizing defaults: memoryScale={}, base optimal/max for 512 tokens: {}/{}, " +
                "effective (with scale): {}/{}, absolute max: {}, heap={}MB",
                DEFAULT_MEMORY_SCALE_FACTOR, DEFAULT_BASE_OPTIMAL_BATCH_SIZE, DEFAULT_BASE_MAX_BATCH_SIZE,
                effectiveOptimal, effectiveMax, DEFAULT_ABSOLUTE_MAX_BATCH_SIZE, maxHeapMB);
        LogManager.getLogger(GenericDenseSameDiffEncoder.class).info(
                "Batch sizes can be configured via UI or -Dkompile.encoder.batch.optimal=N");
    }

    /**
     * Initialize instance batch size fields from static defaults.
     * Called from constructors.
     */
    private void initBatchSizeDefaults() {
        this.instanceOptimalBatchSize = DEFAULT_BASE_OPTIMAL_BATCH_SIZE;
        this.instanceMaxBatchSize = DEFAULT_BASE_MAX_BATCH_SIZE;
        this.instanceAbsoluteMaxBatchSize = DEFAULT_ABSOLUTE_MAX_BATCH_SIZE;
        this.instanceMemoryScaleFactor = DEFAULT_MEMORY_SCALE_FACTOR;
    }

    /**
     * Configure batch sizes from external configuration (e.g., UI settings).
     * This overrides the static defaults for this encoder instance.
     *
     * @param optimalBatchSize Base optimal batch size for 512-token sequences
     * @param maxBatchSize Base maximum batch size for 512-token sequences
     */
    public void configureBatchSize(int optimalBatchSize, int maxBatchSize) {
        configureBatchSize(optimalBatchSize, maxBatchSize, DEFAULT_ABSOLUTE_MAX_BATCH_SIZE, -1.0);
    }

    /**
     * Configure batch sizes with full control over all parameters.
     *
     * @param optimalBatchSize Base optimal batch size for 512-token sequences
     * @param maxBatchSize Base maximum batch size for 512-token sequences
     * @param absoluteMaxBatchSize Absolute maximum regardless of sequence length
     * @param memoryScaleFactor Memory scale factor (-1 for auto-detect based on heap)
     */
    public void configureBatchSize(int optimalBatchSize, int maxBatchSize, int absoluteMaxBatchSize, double memoryScaleFactor) {
        this.instanceOptimalBatchSize = Math.max(1, optimalBatchSize);
        this.instanceMaxBatchSize = Math.max(this.instanceOptimalBatchSize, maxBatchSize);
        this.instanceAbsoluteMaxBatchSize = Math.max(this.instanceMaxBatchSize, absoluteMaxBatchSize);

        // Auto-detect memory scale if -1
        if (memoryScaleFactor < 0) {
            this.instanceMemoryScaleFactor = DEFAULT_MEMORY_SCALE_FACTOR;
        } else {
            this.instanceMemoryScaleFactor = memoryScaleFactor;
        }

        this.batchSizeConfigured = true;

        int effectiveOptimal = (int) Math.round(this.instanceOptimalBatchSize * this.instanceMemoryScaleFactor);
        int effectiveMax = (int) Math.round(this.instanceMaxBatchSize * this.instanceMemoryScaleFactor);

        LOG.info("[{}] Batch size configured from UI: optimal={}, max={}, absoluteMax={}, memoryScale={}, " +
                "effective for 512 tokens: optimal={}, max={}",
                this.modelIdentifier, this.instanceOptimalBatchSize, this.instanceMaxBatchSize,
                this.instanceAbsoluteMaxBatchSize, this.instanceMemoryScaleFactor,
                effectiveOptimal, effectiveMax);
    }

    /**
     * Get the current optimal batch size configuration.
     */
    public int getConfiguredOptimalBatchSize() {
        return instanceOptimalBatchSize;
    }

    /**
     * Get the current max batch size configuration.
     */
    public int getConfiguredMaxBatchSize() {
        return instanceMaxBatchSize;
    }

    /**
     * Check if batch size has been explicitly configured (vs using defaults).
     */
    public boolean isBatchSizeConfigured() {
        return batchSizeConfigured;
    }

    /**
     * Calculate optimal batch size based on the maximum sequence length in the batch.
     * Batch size scales inversely with sequence length squared (attention is O(n²)).
     * Uses instance-level configuration if set, otherwise falls back to static defaults.
     */
    private int calculateOptimalBatchSize(int maxSeqLength) {
        if (maxSeqLength <= 0) maxSeqLength = REFERENCE_SEQ_LENGTH;
        double seqLengthRatio = (double) REFERENCE_SEQ_LENGTH / maxSeqLength;
        double scaleFactor = seqLengthRatio * seqLengthRatio * instanceMemoryScaleFactor;
        int optimalBatch = (int) Math.round(instanceOptimalBatchSize * scaleFactor);
        return Math.max(ABSOLUTE_MIN_BATCH_SIZE, Math.min(optimalBatch, instanceAbsoluteMaxBatchSize));
    }

    /**
     * Calculate maximum batch size based on the maximum sequence length.
     * Uses instance-level configuration if set, otherwise falls back to static defaults.
     */
    private int calculateMaxBatchSize(int maxSeqLength) {
        if (maxSeqLength <= 0) maxSeqLength = REFERENCE_SEQ_LENGTH;
        double seqLengthRatio = (double) REFERENCE_SEQ_LENGTH / maxSeqLength;
        double scaleFactor = seqLengthRatio * seqLengthRatio * instanceMemoryScaleFactor;
        int maxBatch = (int) Math.round(instanceMaxBatchSize * scaleFactor);
        return Math.max(ABSOLUTE_MIN_BATCH_SIZE, Math.min(maxBatch, instanceAbsoluteMaxBatchSize));
    }

    /**
     * Helper record to track original index during sorting.
     */
    private record IndexedEncoding(
            int originalIndex,
            String text,
            SamediffBertTokenizerPreProcessor.BertEncoding encoding,
            int seqLength
    ) {}

    // Batch inference is always supported for SameDiff models with proper padding
    // The encodeSingleInferenceBatch() method handles dynamic batching correctly
    private volatile boolean batchInferenceSupported = true;

    /**
     * TRUE BATCH ENCODING - Process multiple texts with dynamic sizing and sorting.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Tokenize all texts upfront</li>
     *   <li>Sort by sequence length to minimize padding waste</li>
     *   <li>Calculate optimal batch size for each sub-batch</li>
     *   <li>Process sub-batches and reorder results</li>
     * </ol>
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
        getEncoderLock().lock();
        try {
            // If batch inference is not supported, fall back to sequential
            if (!batchInferenceSupported) {
                return encodeSequential(texts);
            }

            // Use dynamic sizing with sorting for optimal batching
            return encodeBatchWithDynamicSizing(texts);
        } finally {
            getEncoderLock().unlock();
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
            LOG.debug("[{}] Sequential encoding: {} texts in {}ms ({} ms/text)",
                    modelIdentifier, texts.size(), elapsed, String.format("%.1f", (double) elapsed / texts.size()));
        }
        return results;
    }

    /**
     * Encode batch with dynamic sizing based on actual sequence lengths.
     * Sorts by sequence length to minimize padding waste, then groups into optimal sub-batches.
     */
    private List<float[]> encodeBatchWithDynamicSizing(List<String> texts) {
        long totalStartTime = System.currentTimeMillis();
        int numTexts = texts.size();

        // Step 1: Tokenize all texts and track sequence lengths with original indices
        List<IndexedEncoding> indexedEncodings = new java.util.ArrayList<>(numTexts);
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
            SamediffBertTokenizerPreProcessor.BertEncoding enc = this.tokenizerPreProcessor.encode(text);
            int seqLen = enc.inputIds.length;
            indexedEncodings.add(new IndexedEncoding(i, text, enc, seqLen));

            totalTokens += seqLen;
            if (seqLen > maxSeqLength) maxSeqLength = seqLen;
            if (seqLen < minSeqLength) minSeqLength = seqLen;
        }
        long tokenizeTime = System.currentTimeMillis() - tokenizeStart;

        double avgSeqLength = numTexts > 0 ? (double) totalTokens / numTexts : 0;
        LOG.debug("[{}] Tokenization: {}ms, minSeq={}, maxSeq={}, avgSeq={}",
                modelIdentifier, tokenizeTime, minSeqLength, maxSeqLength, String.format("%.1f", avgSeqLength));

        // Step 2: Sort by sequence length to minimize padding waste
        indexedEncodings.sort((a, b) -> Integer.compare(a.seqLength, b.seqLength));

        // Calculate padding cost for metrics
        int unsortedPaddingCost = numTexts * maxSeqLength;
        int sortedPaddingCost = 0;

        // Step 3: Calculate optimal batch size based on average sequence length
        int avgBasedOptimal = calculateOptimalBatchSize((int) avgSeqLength);

        // Step 4: Check if single batch is sufficient
        if (numTexts <= avgBasedOptimal) {
            int actualMaxSeq = indexedEncodings.get(numTexts - 1).seqLength;

            List<String> sortedTexts = new java.util.ArrayList<>(numTexts);
            List<SamediffBertTokenizerPreProcessor.BertEncoding> sortedEncodings = new java.util.ArrayList<>(numTexts);
            for (IndexedEncoding ie : indexedEncodings) {
                sortedTexts.add(ie.text);
                sortedEncodings.add(ie.encoding);
            }

            List<float[]> sortedResults = encodeSingleInferenceBatchFromEncodings(sortedTexts, sortedEncodings, actualMaxSeq);

            if (sortedResults == null && batchInferenceSupported) {
                LOG.warn("[{}] Batch inference failed - falling back to sequential", modelIdentifier);
                batchInferenceSupported = false;
                sortedResults = encodeSequentialFromEncodings(sortedTexts, sortedEncodings);
            }

            if (sortedResults == null) return null;

            // Reorder results back to original order
            float[][] reorderedResults = new float[numTexts][];
            for (int i = 0; i < numTexts; i++) {
                reorderedResults[indexedEncodings.get(i).originalIndex] = sortedResults.get(i);
            }

            long totalTime = System.currentTimeMillis() - totalStartTime;
            LOG.debug("[{}] Single batch: {} texts in {}ms ({} ms/text)",
                    modelIdentifier, numTexts, totalTime, String.format("%.2f", (double)totalTime / numTexts));

            return java.util.Arrays.asList(reorderedResults);
        }

        // Step 5: Adaptive sub-batch processing with length-aware grouping
        float[][] reorderedResults = new float[numTexts][];
        long embeddingStartTime = System.currentTimeMillis();
        int processedCount = 0;
        int subBatchNum = 0;

        while (processedCount < numTexts) {
            if (Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Sub-batch processing interrupted at {}/{}", modelIdentifier, processedCount, numTexts);
                return null;
            }

            int remaining = numTexts - processedCount;

            // Sample max sequence length and calculate optimal batch size
            int sampleEnd = Math.min(processedCount + 32, numTexts);
            int sampleMaxSeq = indexedEncodings.get(sampleEnd - 1).seqLength;
            int optimalForSample = calculateOptimalBatchSize(sampleMaxSeq);

            int subBatchSize = Math.min(optimalForSample, remaining);
            int subBatchMaxSeq = indexedEncodings.get(processedCount + subBatchSize - 1).seqLength;
            int refinedOptimal = calculateOptimalBatchSize(subBatchMaxSeq);

            // Expand batch if more items fit at this sequence length
            while (subBatchSize < remaining && subBatchSize < refinedOptimal) {
                int nextIdx = processedCount + subBatchSize;
                int nextSeqLen = indexedEncodings.get(nextIdx).seqLength;
                int newOptimal = calculateOptimalBatchSize(nextSeqLen);
                if (subBatchSize + 1 <= newOptimal) {
                    subBatchSize++;
                    subBatchMaxSeq = nextSeqLen;
                    refinedOptimal = newOptimal;
                } else {
                    break;
                }
            }

            sortedPaddingCost += subBatchSize * subBatchMaxSeq;
            subBatchNum++;

            // Extract sub-batch
            List<String> subTexts = new java.util.ArrayList<>(subBatchSize);
            List<SamediffBertTokenizerPreProcessor.BertEncoding> subEncodings = new java.util.ArrayList<>(subBatchSize);
            List<Integer> subOriginalIndices = new java.util.ArrayList<>(subBatchSize);

            for (int i = 0; i < subBatchSize; i++) {
                IndexedEncoding ie = indexedEncodings.get(processedCount + i);
                subTexts.add(ie.text);
                subEncodings.add(ie.encoding);
                subOriginalIndices.add(ie.originalIndex);
            }

            LOG.debug("[{}] Sub-batch {}: {} texts, seqRange=[{}-{}]",
                    modelIdentifier, subBatchNum, subBatchSize,
                    indexedEncodings.get(processedCount).seqLength, subBatchMaxSeq);

            List<float[]> subResults = encodeSingleInferenceBatchFromEncodings(subTexts, subEncodings, subBatchMaxSeq);

            if (subResults == null) {
                if (Thread.currentThread().isInterrupted()) return null;

                if (SameDiffEncoder.isFailFastOnError()) {
                    String errMsg = String.format("Sub-batch %d failed", subBatchNum);
                    LOG.error("[{}] {}", modelIdentifier, errMsg);
                    throw new SameDiffEncoder.EncodingException(modelIdentifier, "sub-batch encoding", errMsg, null);
                }

                LOG.warn("[{}] Sub-batch {} failed - using sequential", modelIdentifier, subBatchNum);
                subResults = encodeSequentialFromEncodings(subTexts, subEncodings);
                if (subResults == null) return null;
            }

            for (int i = 0; i < subBatchSize; i++) {
                reorderedResults[subOriginalIndices.get(i)] = subResults.get(i);
            }

            processedCount += subBatchSize;
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;
        long embeddingTime = System.currentTimeMillis() - embeddingStartTime;
        double paddingSavings = unsortedPaddingCost > 0
                ? (1.0 - (double) sortedPaddingCost / unsortedPaddingCost) * 100.0 : 0.0;

        LOG.info("[{}] {} sub-batches: {} texts in {}ms (tokenize={}ms, embed={}ms, {} texts/sec, padding saved: {}%)",
                modelIdentifier, subBatchNum, numTexts, totalTime, tokenizeTime, embeddingTime,
                String.format("%.1f", numTexts * 1000.0 / totalTime), String.format("%.1f", paddingSavings));

        return java.util.Arrays.asList(reorderedResults);
    }

    /**
     * Encode texts using pre-computed encodings (avoids re-tokenization).
     */
    private List<float[]> encodeSequentialFromEncodings(List<String> texts,
                                                         List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings) {
        List<float[]> results = new java.util.ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) return null;
            results.add(encodeFromTokenized(texts.get(i), encodings.get(i)));
        }
        return results;
    }

    /**
     * Encode a batch using pre-computed tokenizations.
     * Avoids re-tokenizing when dynamic batching has already tokenized the texts.
     *
     * @param texts Original texts (for logging)
     * @param encodings Pre-computed tokenizations
     * @param maxSeqLength Maximum sequence length (for padding)
     * @return List of embeddings, or null on failure
     */
    private List<float[]> encodeSingleInferenceBatchFromEncodings(
            List<String> texts,
            List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings,
            int maxSeqLength) {

        if (encodings.isEmpty()) return new java.util.ArrayList<>();

        int batchSize = encodings.size();

        // Create padded arrays
        long[][] batchInputIds = new long[batchSize][maxSeqLength];
        long[][] batchAttentionMask = new long[batchSize][maxSeqLength];
        long[][] batchTokenTypeIds = new long[batchSize][maxSeqLength];

        int totalTokens = 0;
        int[] passageTokenCounts = new int[batchSize];
        for (int i = 0; i < batchSize; i++) {
            SamediffBertTokenizerPreProcessor.BertEncoding enc = encodings.get(i);
            int seqLen = enc.inputIds.length;
            totalTokens += seqLen;
            passageTokenCounts[i] = seqLen;
            System.arraycopy(enc.inputIds, 0, batchInputIds[i], 0, seqLen);
            System.arraycopy(enc.attentionMask, 0, batchAttentionMask[i], 0, seqLen);
            if (enc.tokenTypeIds != null) {
                System.arraycopy(enc.tokenTypeIds, 0, batchTokenTypeIds[i], 0, seqLen);
            }
        }

        // Update BatchInfo so pipeline can read actual shapes
        long batchStartMs = System.currentTimeMillis();
        updateBatchInfo(batchSize, maxSeqLength, getEmbeddingDimension(), totalTokens,
                new long[]{batchSize, maxSeqLength}, new long[]{batchSize, getEmbeddingDimension()},
                "FORWARD_PASS", passageTokenCounts);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        Map<String, INDArray> outputMap = null;
        List<float[]> results = new java.util.ArrayList<>(batchSize);

        try {
            // ========== SHAPE LOGGING: Batch Input Array Creation ==========
            LOG.info("[{}] SHAPE BATCH CREATE: Creating batch arrays for {} texts, maxSeqLen={}, totalTokens={}",
                    this.modelIdentifier, batchSize, maxSeqLength, totalTokens);

            for (String inputName : this.inputTensorNamesForModel) {
                if (isInputIdsInput(inputName)) {
                    INDArray arr = Nd4j.createFromArray(batchInputIds);
                    placeholderMap.put(inputName, arr);
                    LOG.info("[{}] SHAPE BATCH CREATE: input_ids '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName, Arrays.toString(arr.shape()), arr.dataType());
                } else if (isAttentionMaskInput(inputName)) {
                    INDArray arr = Nd4j.createFromArray(batchAttentionMask);
                    placeholderMap.put(inputName, arr);
                    LOG.info("[{}] SHAPE BATCH CREATE: attention_mask '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName, Arrays.toString(arr.shape()), arr.dataType());
                } else if (isTokenTypeIdsInput(inputName)) {
                    INDArray arr = Nd4j.createFromArray(batchTokenTypeIds);
                    placeholderMap.put(inputName, arr);
                    LOG.info("[{}] SHAPE BATCH CREATE: token_type_ids '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName, Arrays.toString(arr.shape()), arr.dataType());
                }
            }

            if (Thread.currentThread().isInterrupted()) return null;

            String outputTensorName = this.outputTensorNamesFromModel.get(0);

            // ========== SHAPE LOGGING: Pre-Inference Summary ==========
            LOG.info("[{}] SHAPE BATCH INFERENCE INPUT: Calling model.output() for batch of {} texts",
                    this.modelIdentifier, batchSize);
            for (Map.Entry<String, INDArray> entry : placeholderMap.entrySet()) {
                INDArray arr = entry.getValue();
                LOG.info("[{}] SHAPE BATCH INFERENCE INPUT:   {} -> shape={}, dtype={}, rank={}",
                        this.modelIdentifier, entry.getKey(),
                        Arrays.toString(arr.shape()), arr.dataType(), arr.rank());
            }

            outputMap = this.sameDiffModel.output(placeholderMap, outputTensorName);

            // ========== SHAPE LOGGING: Post-Inference Output ==========
            LOG.info("[{}] SHAPE BATCH INFERENCE OUTPUT: model.output() returned {} output tensors",
                    this.modelIdentifier, outputMap.size());
            for (Map.Entry<String, INDArray> entry : outputMap.entrySet()) {
                INDArray arr = entry.getValue();
                if (arr != null) {
                    LOG.info("[{}] SHAPE BATCH INFERENCE OUTPUT:   {} -> shape={}, dtype={}, rank={}",
                            this.modelIdentifier, entry.getKey(),
                            Arrays.toString(arr.shape()), arr.dataType(), arr.rank());
                }
            }

            totalTokensProcessed.addAndGet(totalTokens);

            if (Thread.currentThread().isInterrupted()) return null;

            INDArray batchOutput = outputMap.get(outputTensorName);
            if (batchOutput == null) {
                LOG.error("[{}] Output tensor not found", modelIdentifier);
                return null;
            }

            // Use vectorized extraction for all embeddings at once
            List<float[]> extracted = extractAllEmbeddings(batchOutput, batchSize);

            // Update BatchInfo with timing after inference completes
            long totalTimeMs = System.currentTimeMillis() - batchStartMs;
            double tokensPerSec = totalTimeMs > 0 ? (totalTokens * 1000.0 / totalTimeMs) : 0;
            double chunksPerSec = totalTimeMs > 0 ? (batchSize * 1000.0 / totalTimeMs) : 0;
            updateBatchInfoWithTiming(batchSize, maxSeqLength, getEmbeddingDimension(), totalTokens,
                    new long[]{batchSize, maxSeqLength}, new long[]{batchSize, getEmbeddingDimension()},
                    "COMPLETE", batchStartMs, 0, 0, 0, totalTimeMs, 0, totalTimeMs, tokensPerSec, chunksPerSec);

            if (extracted == null) {
                LOG.warn("[{}] Vectorized extraction failed, falling back to sequential", modelIdentifier);
                // Fallback to sequential extraction
                for (int i = 0; i < batchSize; i++) {
                    try {
                        results.add(extractSingleEmbedding(batchOutput, i));
                    } catch (Exception e) {
                        LOG.warn("[{}] Failed to extract embedding {}: {}", modelIdentifier, i, e.getMessage());
                        results.add(null);
                    }
                }
                return results;
            }
            return extracted;

        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) return null;
            LOG.error("[{}] Error in batch from encodings: {}", modelIdentifier, e.getMessage(), e);
            if (SameDiffEncoder.isFailFastOnError()) {
                throw new SameDiffEncoder.EncodingException(modelIdentifier, "batch from encodings", e.getMessage(), e);
            }
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
        }
    }

    /**
     * Encode a single inference batch (up to MAX_INFERENCE_BATCH_SIZE texts).
     * Includes detailed timing instrumentation for performance analysis.
     */
    private List<float[]> encodeSingleInferenceBatch(List<String> texts) {
        if (texts.isEmpty()) return new java.util.ArrayList<>();

        final int batchSize = texts.size();
        final long batchStartMs = System.currentTimeMillis();
        long batchStartNanos = System.nanoTime();

        // ========== STEP 1: TOKENIZATION ==========
        // Update batch info to show we're tokenizing
        // NOTE: Keep previous shapes during tokenization (maxSeqLength not yet known)
        // Empty shape arrays signal "use previous" to avoid showing [batch, 0]
        updateBatchInfo(batchSize, 0, getEmbeddingDimension(), 0,
                new long[0], new long[0], "TOKENIZING");

        long tokenizeStart = System.nanoTime();
        List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings = new java.util.ArrayList<>(texts.size());
        int maxLen = 0;
        int totalTokens = 0;
        int[] passageTokenCounts = new int[texts.size()];

        for (int i = 0; i < texts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) return null;
            SamediffBertTokenizerPreProcessor.BertEncoding enc = this.tokenizerPreProcessor.encode(texts.get(i));
            encodings.add(enc);
            passageTokenCounts[i] = enc.inputIds.length;
            if (enc.inputIds.length > maxLen) maxLen = enc.inputIds.length;
            totalTokens += enc.inputIds.length;
        }
        long tokenizeEnd = System.nanoTime();
        long tokenizeTimeMs = (tokenizeEnd - tokenizeStart) / 1_000_000;

        // ========== STEP 2: PADDING ==========
        updateBatchInfoWithTiming(batchSize, maxLen, getEmbeddingDimension(), totalTokens,
                new long[]{batchSize, maxLen}, new long[]{batchSize, getEmbeddingDimension()},
                "PADDING", batchStartMs, tokenizeTimeMs, 0, 0, 0, 0, 0, 0, 0, passageTokenCounts);

        long paddingStart = System.nanoTime();
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
        long paddingTimeMs = (paddingEnd - paddingStart) / 1_000_000;

        // Create ND4J tensors and run single forward pass
        Map<String, INDArray> placeholderMap = new HashMap<>();
        Map<String, INDArray> outputMap = null;
        List<float[]> results = new java.util.ArrayList<>(batchSize);

        try {
            // ========== STEP 3: TENSOR CREATION ==========
            updateBatchInfoWithTiming(batchSize, maxLen, getEmbeddingDimension(), totalTokens,
                    new long[]{batchSize, maxLen}, new long[]{batchSize, getEmbeddingDimension()},
                    "TENSOR_CREATION", batchStartMs, tokenizeTimeMs, paddingTimeMs, 0, 0, 0, 0, 0, 0, passageTokenCounts);

            long tensorStart = System.nanoTime();

            // ========== SHAPE LOGGING: Single Batch Input Array Creation ==========
            LOG.info("[{}] SHAPE SINGLE_BATCH CREATE: Creating tensors for {} texts, maxLen={}, totalTokens={}",
                    this.modelIdentifier, batchSize, maxLen, totalTokens);

            for (String inputName : this.inputTensorNamesForModel) {
                if (isInputIdsInput(inputName)) {
                    INDArray arr = Nd4j.createFromArray(batchInputIds);
                    placeholderMap.put(inputName, arr);
                    LOG.info("[{}] SHAPE SINGLE_BATCH CREATE: input_ids '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName, Arrays.toString(arr.shape()), arr.dataType());
                } else if (isAttentionMaskInput(inputName)) {
                    INDArray arr = Nd4j.createFromArray(batchAttentionMask);
                    placeholderMap.put(inputName, arr);
                    LOG.info("[{}] SHAPE SINGLE_BATCH CREATE: attention_mask '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName, Arrays.toString(arr.shape()), arr.dataType());
                } else if (isTokenTypeIdsInput(inputName)) {
                    INDArray arr = Nd4j.createFromArray(batchTokenTypeIds);
                    placeholderMap.put(inputName, arr);
                    LOG.info("[{}] SHAPE SINGLE_BATCH CREATE: token_type_ids '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName, Arrays.toString(arr.shape()), arr.dataType());
                }
            }
            long tensorEnd = System.nanoTime();
            long tensorTimeMs = (tensorEnd - tensorStart) / 1_000_000;

            String outputTensorName = this.outputTensorNamesFromModel.get(0);

            // ========== STEP 4: FORWARD PASS (THE KEY METRIC) ==========
            updateBatchInfoWithTiming(batchSize, maxLen, getEmbeddingDimension(), totalTokens,
                    new long[]{batchSize, maxLen}, new long[]{batchSize, getEmbeddingDimension()},
                    "FORWARD_PASS", batchStartMs, tokenizeTimeMs, paddingTimeMs, tensorTimeMs, 0, 0, 0, 0, 0, passageTokenCounts);

            long inferenceStart = System.nanoTime();

            // ========== SHAPE LOGGING: Pre-Inference Summary ==========
            LOG.info("[{}] SHAPE SINGLE_BATCH INFERENCE INPUT: Calling model.output() for batch of {} texts",
                    this.modelIdentifier, batchSize);
            for (Map.Entry<String, INDArray> entry : placeholderMap.entrySet()) {
                INDArray arr = entry.getValue();
                LOG.info("[{}] SHAPE SINGLE_BATCH INFERENCE INPUT:   {} -> shape={}, dtype={}, rank={}",
                        this.modelIdentifier, entry.getKey(),
                        Arrays.toString(arr.shape()), arr.dataType(), arr.rank());
            }

            // SINGLE forward pass for the entire batch - THIS IS WHERE TIME IS SPENT
            outputMap = this.sameDiffModel.output(placeholderMap, outputTensorName);

            // ========== SHAPE LOGGING: Post-Inference Output ==========
            LOG.info("[{}] SHAPE SINGLE_BATCH INFERENCE OUTPUT: model.output() returned {} output tensors",
                    this.modelIdentifier, outputMap.size());
            for (Map.Entry<String, INDArray> entry : outputMap.entrySet()) {
                INDArray arr = entry.getValue();
                if (arr != null) {
                    LOG.info("[{}] SHAPE SINGLE_BATCH INFERENCE OUTPUT:   {} -> shape={}, dtype={}, rank={}",
                            this.modelIdentifier, entry.getKey(),
                            Arrays.toString(arr.shape()), arr.dataType(), arr.rank());
                }
            }

            long inferenceEnd = System.nanoTime();
            long forwardPassTimeMs = (inferenceEnd - inferenceStart) / 1_000_000;

            // Update cumulative stats
            totalTokensProcessed.addAndGet(totalTokens);

            if (Thread.currentThread().isInterrupted()) return null;

            INDArray batchOutput = outputMap.get(outputTensorName);
            if (batchOutput == null) {
                LOG.error("[{}] Output tensor not found", modelIdentifier);
                return null;
            }

            // ========== STEP 5: EXTRACTION ==========
            updateBatchInfoWithTiming(batchSize, maxLen, getEmbeddingDimension(), totalTokens,
                    new long[]{batchSize, maxLen}, new long[]{batchSize, getEmbeddingDimension()},
                    "EXTRACTING", batchStartMs, tokenizeTimeMs, paddingTimeMs, tensorTimeMs, forwardPassTimeMs, 0, 0, 0, 0, passageTokenCounts);

            long extractStart = System.nanoTime();

            // Use vectorized extraction for all embeddings at once
            List<float[]> extracted = extractAllEmbeddings(batchOutput, batchSize);
            if (extracted == null) {
                LOG.warn("[{}] Vectorized extraction failed, falling back to sequential", modelIdentifier);
                // Fallback to sequential extraction
                for (int i = 0; i < batchSize; i++) {
                    try {
                        results.add(extractSingleEmbedding(batchOutput, i));
                    } catch (Exception e) {
                        LOG.warn("[{}] Failed to extract embedding {}: {}", modelIdentifier, i, e.getMessage());
                        results.add(null);
                    }
                }
            } else {
                results = extracted;
            }

            long extractEnd = System.nanoTime();
            long extractTimeMs = (extractEnd - extractStart) / 1_000_000;

            // Calculate totals and throughput
            long totalBatchTimeMs = (System.nanoTime() - batchStartNanos) / 1_000_000;
            double tokensPerSecond = forwardPassTimeMs > 0 ? (totalTokens * 1000.0 / forwardPassTimeMs) : 0;
            double chunksPerSecond = totalBatchTimeMs > 0 ? (batchSize * 1000.0 / totalBatchTimeMs) : 0;

            // ========== COMPLETE: Update with final timing ==========
            updateBatchInfoWithTiming(batchSize, maxLen, getEmbeddingDimension(), totalTokens,
                    new long[]{batchSize, maxLen}, new long[]{batchSize, getEmbeddingDimension()},
                    "COMPLETE", batchStartMs, tokenizeTimeMs, paddingTimeMs, tensorTimeMs,
                    forwardPassTimeMs, extractTimeMs, totalBatchTimeMs, tokensPerSecond, chunksPerSecond, passageTokenCounts);

            // Log detailed batch timing (also visible via getCurrentBatchInfo())
            LOG.info("[{}] BATCH COMPLETE: {} chunks, {} tokens, seq_len={}",
                    modelIdentifier, batchSize, totalTokens, maxLen);
            LOG.info("[{}]   Tokenize: {}ms | Pad: {}ms | Tensor: {}ms | FORWARD: {}ms | Extract: {}ms | TOTAL: {}ms",
                    modelIdentifier, tokenizeTimeMs, paddingTimeMs, tensorTimeMs,
                    forwardPassTimeMs, extractTimeMs, totalBatchTimeMs);
            LOG.info("[{}]   Throughput: {} tok/sec, {} chunks/sec",
                    modelIdentifier, String.format("%.1f", tokensPerSecond), String.format("%.2f", chunksPerSecond));

            return results;

        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) return null;
            LOG.error("[{}] Error in inference batch: {}", modelIdentifier, e.getMessage(), e);
            // FAIL-FAST: Throw exception instead of silently returning null
            if (SameDiffEncoder.isFailFastOnError()) {
                throw new SameDiffEncoder.EncodingException(modelIdentifier, "inference batch", e.getMessage(), e);
            }
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
     * VECTORIZED BATCH EXTRACTION - Extract ALL embeddings from batch output at once.
     *
     * This is more efficient than calling extractSingleEmbedding() in a loop because:
     * 1. Single slice operation instead of N slice operations
     * 2. Single L2 normalization across all rows at once
     * 3. Better memory access patterns (contiguous reads)
     *
     * @param batchOutput The output tensor from model inference
     * @param batchSize The number of embeddings to extract
     * @return List of float[] embeddings, or null on error
     */
    private List<float[]> extractAllEmbeddings(INDArray batchOutput, int batchSize) {
        if (batchOutput == null || batchOutput.isEmpty()) {
            LOG.error("[{}] Cannot extract embeddings from null/empty batch output", modelIdentifier);
            return null;
        }

        long[] shape = batchOutput.shape();
        LOG.info("[{}] VECTORIZED EXTRACT: Extracting {} embeddings from batchOutput shape={}, rank={}",
                modelIdentifier, batchSize, Arrays.toString(shape), batchOutput.rank());

        INDArray clsEmbeddings = null;
        INDArray normalized = null;
        INDArray norms = null;

        try {
            // Extract all CLS tokens at once based on tensor shape
            if (shape.length == 4) {
                // [batch, 1, 1, hidden] or [batch, seq, ?, hidden]
                if (shape[1] == 1 && shape[2] == 1) {
                    // [batch, 1, 1, hidden] - squeeze out middle dims
                    clsEmbeddings = batchOutput.get(NDArrayIndex.all(), NDArrayIndex.point(0),
                            NDArrayIndex.point(0), NDArrayIndex.all());
                } else {
                    // [batch, seq, ?, hidden] - get first token position
                    clsEmbeddings = batchOutput.get(NDArrayIndex.all(), NDArrayIndex.point(0),
                            NDArrayIndex.point(0), NDArrayIndex.all());
                }
                LOG.info("[{}] VECTORIZED EXTRACT: From 4D tensor -> clsEmbeddings shape={}",
                        modelIdentifier, Arrays.toString(clsEmbeddings.shape()));
            } else if (shape.length == 3) {
                // [batch, seq_len, hidden] - extract CLS token (position 0) for all batches
                clsEmbeddings = batchOutput.get(NDArrayIndex.all(), NDArrayIndex.point(0), NDArrayIndex.all());
                LOG.info("[{}] VECTORIZED EXTRACT: From 3D tensor [all][0][all] -> clsEmbeddings shape={}",
                        modelIdentifier, Arrays.toString(clsEmbeddings.shape()));
            } else if (shape.length == 2) {
                // [batch, hidden] - already pooled, use directly
                clsEmbeddings = batchOutput;
                LOG.info("[{}] VECTORIZED EXTRACT: Using 2D tensor directly -> shape={}",
                        modelIdentifier, Arrays.toString(clsEmbeddings.shape()));
            } else if (shape.length == 1 && batchSize == 1) {
                // [hidden] - single vector for batch_size=1
                clsEmbeddings = batchOutput.reshape(1, -1);
                LOG.info("[{}] VECTORIZED EXTRACT: Reshaped 1D to 2D -> shape={}",
                        modelIdentifier, Arrays.toString(clsEmbeddings.shape()));
            } else {
                LOG.error("[{}] Unexpected batch output shape: {} for batchSize={}",
                        modelIdentifier, Arrays.toString(shape), batchSize);
                return null;
            }

            // Ensure we have 2D [batch, hidden] shape
            if (clsEmbeddings.rank() != 2) {
                clsEmbeddings = clsEmbeddings.reshape(batchSize, -1);
            }

            // Verify batch dimension matches
            if (clsEmbeddings.shape()[0] != batchSize) {
                LOG.error("[{}] Batch size mismatch: expected {} but got shape {}",
                        modelIdentifier, batchSize, Arrays.toString(clsEmbeddings.shape()));
                return null;
            }

            int embeddingDim = (int) clsEmbeddings.shape()[1];

            if (this.normalizeOutput) {
                // VECTORIZED L2 normalization using ND4J's native norm2 operation
                // norm2(1) computes L2 norm along dimension 1 (hidden dim) for each row
                norms = clsEmbeddings.norm2(1);  // [batch] - L2 norm per row

                // Clamp norms to avoid division by zero, then reshape for broadcast
                // Use Transforms.max for vectorized clamping with epsilon
                INDArray epsilon = Nd4j.scalar(1e-12f);
                try {
                    norms = Transforms.max(norms, epsilon, false);  // in-place max with epsilon
                } finally {
                    epsilon.close();
                }
                norms = norms.reshape(batchSize, 1);  // [batch, 1] for broadcast

                // Broadcast divide: [batch, hidden] / [batch, 1] -> [batch, hidden]
                normalized = clsEmbeddings.div(norms);

                LOG.info("[{}] VECTORIZED EXTRACT: Normalized {} embeddings using norm2(), output shape={}",
                        modelIdentifier, batchSize, Arrays.toString(normalized.shape()));
            } else {
                normalized = clsEmbeddings;
            }

            // OPTIMIZED EXTRACTION: Use single bulk read + System.arraycopy
            // Avoids toFloatMatrix() which creates intermediate getRow().dup() allocations per row
            List<float[]> results = new java.util.ArrayList<>(batchSize);

            // Fast path: contiguous row-major data - single bulk read + arraycopy
            if (normalized.ordering() == 'c' && !normalized.isView()
                    && normalized.stride(0) == embeddingDim && normalized.stride(1) == 1) {
                float[] flat = normalized.data().getFloatsAt(normalized.offset(), batchSize * embeddingDim);
                for (int i = 0; i < batchSize; i++) {
                    float[] embedding = new float[embeddingDim];
                    System.arraycopy(flat, i * embeddingDim, embedding, 0, embeddingDim);
                    results.add(embedding);
                }
            } else {
                // General path: direct element access avoids intermediate INDArray allocations
                // This handles views, non-contiguous data, and Fortran-order arrays
                for (int i = 0; i < batchSize; i++) {
                    float[] embedding = new float[embeddingDim];
                    for (int j = 0; j < embeddingDim; j++) {
                        embedding[j] = normalized.getFloat(i, j);
                    }
                    results.add(embedding);
                }
            }

            LOG.info("[{}] VECTORIZED EXTRACT COMPLETE: Returned {} embeddings of dim {}",
                    modelIdentifier, results.size(), embeddingDim);
            return results;

        } catch (Exception e) {
            LOG.error("[{}] Error in vectorized extraction: {}", modelIdentifier, e.getMessage(), e);
            return null;
        } finally {
            // Clean up intermediate arrays (but not the input batchOutput)
            if (norms != null) try { norms.close(); } catch (Exception ignored) {}
            // Only close normalized if it's a new array (not the same as clsEmbeddings)
            if (normalized != null && normalized != clsEmbeddings && this.normalizeOutput) {
                try { normalized.close(); } catch (Exception ignored) {}
            }
            // Only close clsEmbeddings if it's not the same as batchOutput
            if (clsEmbeddings != null && clsEmbeddings != batchOutput) {
                try { clsEmbeddings.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Extract a single embedding from the batch output tensor.
     * Handles various output shapes:
     * - [batch, hidden] - already pooled
     * - [batch, seq, hidden] - token embeddings, extract CLS (index 0)
     * - [batch, 1, hidden] - pooler output with extra dim
     * - [batch, 1, 1, hidden] - pooler output with two extra dims
     *
     * @deprecated Use extractAllEmbeddings() for batch extraction - more efficient
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

            // ========== SHAPE LOGGING: Extract Single Embedding ==========
            LOG.info("[{}] SHAPE EXTRACT: Extracting embedding {} from batchOutput shape={}, rank={}",
                    this.modelIdentifier, batchIndex, Arrays.toString(shape), batchOutput.rank());

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
                LOG.info("[{}] SHAPE EXTRACT: From 4D tensor [{}][0][0][*] -> clsEmbedding shape={}",
                        modelIdentifier, batchIndex, Arrays.toString(clsEmbedding.shape()));
            } else if (shape.length == 3) {
                // [batch, seq_len, hidden] - extract CLS token (index 0 of sequence)
                singleOutput = batchOutput.get(NDArrayIndex.point(batchIndex), NDArrayIndex.point(0), NDArrayIndex.all());
                clsEmbedding = singleOutput;
                LOG.info("[{}] SHAPE EXTRACT: From 3D tensor [{}][0][*] -> clsEmbedding shape={}",
                        modelIdentifier, batchIndex, Arrays.toString(clsEmbedding.shape()));
            } else if (shape.length == 2) {
                // [batch, hidden] - already pooled
                singleOutput = batchOutput.getRow(batchIndex);
                clsEmbedding = singleOutput;
                LOG.info("[{}] SHAPE EXTRACT: From 2D tensor [{}][*] -> clsEmbedding shape={}",
                        modelIdentifier, batchIndex, Arrays.toString(clsEmbedding.shape()));
            } else if (shape.length == 1) {
                // [hidden] - single vector, likely batch_size=1 already extracted
                clsEmbedding = batchOutput;
                LOG.info("[{}] SHAPE EXTRACT: Using 1D tensor directly -> clsEmbedding shape={}",
                        modelIdentifier, Arrays.toString(clsEmbedding.shape()));
            } else {
                LOG.warn("[{}] Unexpected batch output shape: {}", modelIdentifier, Arrays.toString(shape));
                return null;
            }

            reshapedEmbedding = clsEmbedding.reshape(1, -1);
            LOG.info("[{}] SHAPE EXTRACT: After reshape -> shape={}",
                    modelIdentifier, Arrays.toString(reshapedEmbedding.shape()));

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

                LOG.info("[{}] SHAPE EXTRACT FINAL: Normalized output shape={}, returning float[{}]",
                        modelIdentifier, Arrays.toString(normalized.shape()), normalized.length());
                return safeToFloatVector(normalized);
            } else {
                LOG.info("[{}] SHAPE EXTRACT FINAL: Unnormalized output shape={}, returning float[{}]",
                        modelIdentifier, Arrays.toString(reshapedEmbedding.shape()), reshapedEmbedding.length());
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
