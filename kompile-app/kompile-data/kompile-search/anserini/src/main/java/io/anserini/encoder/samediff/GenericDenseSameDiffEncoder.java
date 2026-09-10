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
import ai.kompile.utils.inference.InferenceBatchPlanner;
import org.nd4j.common.config.ND4JSystemProperties;
import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.BooleanIndexing;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.indexing.conditions.Conditions;
import org.nd4j.linalg.ops.transforms.Transforms;

import org.bytedeco.javacpp.Pointer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class GenericDenseSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(GenericDenseSameDiffEncoder.class);
    // Must remain representable in float16; a 1e-12 scalar underflows to zero before division.
    private static final double MIN_L2_NORM = 1e-6;

    public enum PoolingStrategy {
        AUTO,
        CLS,
        MEAN
    }

    /**
     * Listener notified when the encoder shrinks a sub-batch due to native-memory pressure.
     * Implemented by the subprocess main class to forward decisions over the stdout protocol.
     */
    @FunctionalInterface
    public interface BatchResizeListener {
        /**
         * @param oldBatch        batch size before shrink
         * @param newBatch        batch size after shrink
         * @param reason          human-readable reason
         * @param physicalBytes   {@code org.bytedeco.javacpp.Pointer.physicalBytes()} at decision time
         * @param maxPhysicalBytes {@code org.bytedeco.javacpp.Pointer.maxPhysicalBytes()} at decision time
         */
        void onBatchResize(int oldBatch, int newBatch, String reason, long physicalBytes, long maxPhysicalBytes);
    }

    /** Optional listener set by the subprocess to forward decisions over the protocol. */
    private volatile BatchResizeListener batchResizeListener;

    /**
     * Register a listener for native-memory-pressure batch-resize decisions.
     * Called by {@code EmbeddingSubprocessMain} after the encoder is created.
     */
    public void setBatchResizeListener(BatchResizeListener listener) {
        this.batchResizeListener = listener;
    }

    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;
    public static final boolean DEFAULT_NORMALIZE = true;

    private final boolean normalizeOutput;
    private final PoolingStrategy poolingStrategy;
    private final String inputPrefix;

    // ========== INFERENCE BENCHMARKING STATS ==========
    // These track cumulative timing to identify bottlenecks
    private final AtomicLong totalTokenizeTimeNanos = new AtomicLong(0);
    private final AtomicLong totalInferenceTimeNanos = new AtomicLong(0);
    private final AtomicLong totalPostProcessTimeNanos = new AtomicLong(0);
    private final AtomicLong totalArrayCreationTimeNanos = new AtomicLong(0);
    private final AtomicLong totalEncodeCalls = new AtomicLong(0);
    private final AtomicLong totalTokensProcessed = new AtomicLong(0);
    private final AtomicLong slowestInferenceNanos = new AtomicLong(0);
    private final AtomicInteger slowestInferenceTokens = new AtomicInteger(0);

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
        this(modelIdentifier, doLowerCaseAndStripAccents, maxSequenceLength,
                addSpecialTokens, normalizeOutput, PoolingStrategy.AUTO);
    }

    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens,
                                       boolean normalizeOutput,
                                       PoolingStrategy poolingStrategy)
            throws IOException {
        this(modelIdentifier, doLowerCaseAndStripAccents, maxSequenceLength,
                addSpecialTokens, normalizeOutput, poolingStrategy, "");
    }

    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens,
                                       boolean normalizeOutput,
                                       PoolingStrategy poolingStrategy,
                                       String inputPrefix)
            throws IOException {
        super(modelIdentifier, doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        this.normalizeOutput = normalizeOutput;
        this.poolingStrategy = poolingStrategy == null ? PoolingStrategy.AUTO : poolingStrategy;
        this.inputPrefix = inputPrefix == null ? "" : inputPrefix;
        initBatchSizeDefaults();
        LOG.info("[{}] GenericDenseSameDiffEncoder initialized. Normalize output: {}, pooling: {}, inputPrefix: {}, batchSize: optimal={}, max={}",
                this.modelIdentifier, this.normalizeOutput, this.poolingStrategy, this.inputPrefix,
                this.instanceOptimalBatchSize, this.instanceMaxBatchSize);
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
        this(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                inputTensorNamesForModel, outputTensorNameFromModel,
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
                normalizeOutput, PoolingStrategy.AUTO);
    }

    @Deprecated
    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier,
                                       @NotNull String kompileManagedOnnxModelPath,
                                       @NotNull String kompileManagedVocabPath,
                                       List<String> inputTensorNamesForModel,
                                       String outputTensorNameFromModel,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens,
                                       boolean normalizeOutput,
                                       PoolingStrategy poolingStrategy)
            throws IOException {
        this(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                inputTensorNamesForModel, outputTensorNameFromModel,
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
                normalizeOutput, poolingStrategy, "");
    }

    @Deprecated
    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier,
                                       @NotNull String kompileManagedOnnxModelPath,
                                       @NotNull String kompileManagedVocabPath,
                                       List<String> inputTensorNamesForModel,
                                       String outputTensorNameFromModel,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens,
                                       boolean normalizeOutput,
                                       PoolingStrategy poolingStrategy,
                                       String inputPrefix)
            throws IOException {
        this(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                inputTensorNamesForModel, outputTensorNameFromModel,
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens,
                normalizeOutput, poolingStrategy, inputPrefix, null);
    }

    @Deprecated
    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier,
                                       @NotNull String kompileManagedOnnxModelPath,
                                       @NotNull String kompileManagedVocabPath,
                                       List<String> inputTensorNamesForModel,
                                       String outputTensorNameFromModel,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens,
                                       boolean normalizeOutput,
                                       PoolingStrategy poolingStrategy,
                                       String inputPrefix,
                                       Integer embeddingDimension)
            throws IOException {
        // Handle null tensor names by passing empty lists - the parent will auto-detect from the model
        super(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                inputTensorNamesForModel != null ? inputTensorNamesForModel : List.of(),
                outputTensorNameFromModel != null ? List.of(outputTensorNameFromModel) : List.of(),
                doLowerCaseAndStripAccents,
                maxSequenceLength,
                addSpecialTokens);

        this.normalizeOutput = normalizeOutput;
        this.poolingStrategy = poolingStrategy == null ? PoolingStrategy.AUTO : poolingStrategy;
        this.inputPrefix = inputPrefix == null ? "" : inputPrefix;
        if (embeddingDimension != null && embeddingDimension > 0) {
            this.cachedEmbeddingDimension = embeddingDimension;
        }
        initBatchSizeDefaults();
        LOG.info("[{}] GenericDenseSameDiffEncoder initialized (legacy). Normalize output: {}, pooling: {}, inputPrefix: {}, batchSize: optimal={}, max={}",
                this.modelIdentifier, this.normalizeOutput, this.poolingStrategy, this.inputPrefix,
                this.instanceOptimalBatchSize, this.instanceMaxBatchSize);
    }

    @Override
    protected String getInstructionPrefix() {
        return inputPrefix;
    }

    private String prefixed(String text) {
        return applyInputPrefix(inputPrefix, text);
    }

    static String applyInputPrefix(String prefix, String text) {
        String effectivePrefix = prefix == null ? "" : prefix;
        return effectivePrefix.isEmpty() || text.startsWith(effectivePrefix)
                ? text : effectivePrefix + text;
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
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(prefixed(query));
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
                    // Use Nd4j.create (not createFromArray) to avoid the constant-buffer cache path.
                    // createFromArray routes through the ND4J constant allocator whose buffers are
                    // not freed by arr.close() if the InferenceSession retains them in
                    // externalPlaceholderBuffers. Nd4j.create produces regular heap-backed buffers
                    // that are correctly freed when closed.
                    INDArray inputIds = Nd4j.create(new long[][]{encoding.inputIds});
                    placeholderMap.put(inputName, inputIds);
                    LOG.info("[{}] SHAPE CREATE: input_ids '{}' -> shape={}, dtype={}, first5={}",
                            this.modelIdentifier, inputName,
                            Arrays.toString(inputIds.shape()), inputIds.dataType(),
                            Arrays.toString(Arrays.copyOf(encoding.inputIds, Math.min(5, encoding.inputIds.length))));
                } else if (isAttentionMaskInput(inputName)) {
                    INDArray attentionMask = Nd4j.create(new long[][]{encoding.attentionMask});
                    placeholderMap.put(inputName, attentionMask);
                    LOG.info("[{}] SHAPE CREATE: attention_mask '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName,
                            Arrays.toString(attentionMask.shape()), attentionMask.dataType());
                } else if (isTokenTypeIdsInput(inputName)) {
                    INDArray tokenTypeIds = Nd4j.create(new long[][]{encoding.tokenTypeIds});
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
            float[] result = processOutputTensor(embeddingTensor, encoding.attentionMask);
            long postProcessEnd = System.nanoTime();
            long postProcessTimeNanos = postProcessEnd - postProcessStart;
            // Null check for AtomicLong fields - they may be null during super() constructor validation
            if (totalPostProcessTimeNanos != null) totalPostProcessTimeNanos.addAndGet(postProcessTimeNanos);

            // MEMORY FIX: Clear InferenceSession.nodeValueOutputs AFTER extraction.
            // processOutputTensor() copies all values to a heap float[] via toFloatVector()
            // so no INDArray created during this forward pass is still needed.
            // Clearing HERE (not before extraction) avoids the "closed before call" error
            // that occurred when the session clear freed activation buffers that
            // processOutputTensor still needed to read.
            clearSessionCaches();

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
            // Propagate the exception so callers (especially validation) can see the root cause
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Encoding failed for model " + this.modelIdentifier + ": " + e.getMessage(), e);
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

    protected float[] processOutputTensor(INDArray embeddingTensor, long[] attentionMask) {
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
                if (poolingStrategy == PoolingStrategy.MEAN) {
                    clsEmbedding = maskedMeanPool(embeddingTensor, attentionMask);
                    LOG.info("[{}] SHAPE PROCESS: Applied masked mean pooling -> embedding shape={}",
                            this.modelIdentifier, Arrays.toString(clsEmbedding.shape()));
                } else {
                    // AUTO preserves the legacy generic-encoder contract: first-token pooling.
                    clsEmbedding = embeddingTensor.get(
                            NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
                    LOG.info("[{}] SHAPE PROCESS: Extracted CLS from 3D tensor -> clsEmbedding shape={}",
                            this.modelIdentifier, Arrays.toString(clsEmbedding.shape()));
                }
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
                // SANITIZE: Replace NaN and ±Inf with 0 BEFORE normalization (two vectorized in-place ops:
                // isNan() pass + isInfinite() pass).  FP16 forward passes occasionally produce non-finite
                // values; the DENOMINATOR-only epsilon band-aid (sumVal=0 → normVal=1e-6) leaves NaN in
                // the numerator, which propagates through div() → NaN output → non-indexable dead-letter.
                // Correcting the numerator first makes finite components survive as a valid unit vector.
                // A fully-NaN row becomes all-zeros here; the subsequent sumVal==0 path logs a warning
                // and uses the epsilon floor — the caller's isIndexableEmbedding() will still reject it,
                // which is the correct behaviour for a completely-broken forward pass.
                sanitizeNonFinite(reshapedEmbedding);
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
                    // Clamp the norm itself, matching vectorized and sequential batch paths.
                    double normVal = l2Denominator(sumVal);
                    norm = Nd4j.scalar(sumOfSquares.dataType(), normVal);
                } finally {
                    if (squared != null) squared.close();
                    if (sumOfSquares != null) sumOfSquares.close();
                }

                // With clamp already applied, norm is guaranteed to be >= 1e-6.
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

    static INDArray maskedMeanPool(INDArray embeddingTensor, long[] attentionMask) {
        if (attentionMask == null || attentionMask.length != embeddingTensor.size(1)) {
            throw new IllegalArgumentException("Masked mean pooling requires one attention value per token");
        }
        INDArray maskIds = null;
        INDArray castMask = null;
        INDArray mask = null;
        INDArray weighted = null;
        INDArray sum = null;
        INDArray pooled = null;
        try {
            maskIds = Nd4j.createFromArray(new long[][]{attentionMask});
            castMask = maskIds.castTo(embeddingTensor.dataType());
            mask = castMask.reshape(1, attentionMask.length, 1).dup();
            weighted = embeddingTensor.mul(mask);
            sum = weighted.sum(1);
            double count = 0.0;
            for (long value : attentionMask) {
                count += value;
            }
            pooled = sum.div(Math.max(count, 1.0e-12));
            return pooled.reshape(-1).dup();
        } finally {
            if (pooled != null) pooled.close();
            if (sum != null) sum.close();
            if (weighted != null) weighted.close();
            if (mask != null) mask.close();
            if (castMask != null) castMask.close();
            if (maskIds != null) maskIds.close();
        }
    }

    static INDArray maskedMeanPoolBatch(
            INDArray embeddingTensor, long[][] attentionMasks, int logicalRows) {
        if (embeddingTensor == null || embeddingTensor.rank() != 3
                || attentionMasks == null || attentionMasks.length < logicalRows
                || logicalRows < 1 || embeddingTensor.size(0) < logicalRows) {
            throw new IllegalArgumentException(
                    "Masked batch mean pooling requires aligned [batch,seq,hidden] output and masks");
        }
        int sequenceLength = Math.toIntExact(embeddingTensor.size(1));
        for (int row = 0; row < logicalRows; row++) {
            if (attentionMasks[row] == null || attentionMasks[row].length != sequenceLength) {
                throw new IllegalArgumentException(
                        "Masked batch mean pooling requires one attention value per token");
            }
        }

        INDArray logicalOutput = null;
        INDArray maskIds = null;
        INDArray logicalMaskIds = null;
        INDArray castMask = null;
        INDArray mask = null;
        INDArray weighted = null;
        INDArray sum = null;
        INDArray rawCounts = null;
        INDArray counts = null;
        INDArray boundedCounts = null;
        INDArray pooled = null;
        try {
            logicalOutput = embeddingTensor.get(
                    NDArrayIndex.interval(0, logicalRows),
                    NDArrayIndex.all(), NDArrayIndex.all());
            maskIds = Nd4j.create(attentionMasks);
            logicalMaskIds = maskIds.get(
                    NDArrayIndex.interval(0, logicalRows), NDArrayIndex.all());
            castMask = logicalMaskIds.castTo(embeddingTensor.dataType());
            mask = castMask.reshape(logicalRows, sequenceLength, 1).dup();
            weighted = logicalOutput.mul(mask);
            sum = weighted.sum(1);
            rawCounts = mask.sum(1);
            counts = rawCounts.reshape(logicalRows, 1).dup();
            INDArray epsilon = Nd4j.scalar(1.0f);
            try {
                boundedCounts = Transforms.max(counts, epsilon, false);
            } finally {
                epsilon.close();
            }
            pooled = sum.div(boundedCounts);
            return pooled.dup();
        } finally {
            if (pooled != null) pooled.close();
            if (boundedCounts != null && boundedCounts != counts) boundedCounts.close();
            if (counts != null) counts.close();
            if (rawCounts != null) rawCounts.close();
            if (sum != null) sum.close();
            if (weighted != null) weighted.close();
            if (mask != null) mask.close();
            if (castMask != null) castMask.close();
            if (logicalMaskIds != null) logicalMaskIds.close();
            if (maskIds != null) maskIds.close();
            if (logicalOutput != null) logicalOutput.close();
        }
    }

    /**
     * Replace every non-finite element (NaN, +Inf, -Inf) with 0.0 IN PLACE using vectorized ND4J ops.
     *
     * <p>Two {@link BooleanIndexing#replaceWhere} calls are used — one for NaN ({@link Conditions#isNan()})
     * and one for ±Inf ({@link Conditions#isInfinite()}).  Each issues a single {@code CompareAndSet}
     * native kernel call, so the total cost is 2 vectorized passes over the buffer rather than
     * O(n) scalar JNI round-trips.  The array is modified in place.
     *
     * <p>Note: {@link Conditions#notFinite()} has condition-mode 15 on the native side but its Java
     * {@code apply()} method only checks {@code isInfinite}, not NaN — so it cannot be relied upon
     * to replace NaN.  Using explicit {@code isNan()} + {@code isInfinite()} is correct and sufficient.
     *
     * <p>After sanitization a partially-non-finite row retains all its finite components and can be
     * L2-normalized to a valid unit vector.  A row that was <em>entirely</em> non-finite becomes
     * all-zero; the downstream epsilon clamp keeps it numerically stable, but its magnitude will
     * remain near zero and {@code isIndexableEmbedding()} will correctly reject it.
     *
     * @param arr the array to sanitize; modified in place
     */
    static void sanitizeNonFinite(INDArray arr) {
        // Pass 1: replace NaN with 0.  Conditions.isNan() → condition mode IS_NAN (9) → CompareAndSet.
        BooleanIndexing.replaceWhere(arr, 0.0, Conditions.isNan());
        // Pass 2: replace ±Inf with 0.  Conditions.isInfinite() → IS_INFINITE (8) → CompareAndSet.
        BooleanIndexing.replaceWhere(arr, 0.0, Conditions.isInfinite());
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
     * Bulk-extract an INDArray to a heap {@code float[]} with a SINGLE device→host copy,
     * catching native-pointer exceptions.
     *
     * <p><b>Why not {@code INDArray.toFloatVector()}:</b> toFloatVector loops {@code getFloat(i)},
     * and on a CUDA {@code DataBuffer} every {@code getFloat} triggers
     * {@code AtomicAllocator.synchronizeHostData → CudaExecutioner.commit} — i.e. O(n) GPU→host
     * syncs (~24k per [32×768] batch). Thread dumps showed this per-element commit loop, not the
     * forward pass, dominated embedding wall-clock (~28s of host-side dispatch between batches).
     *
     * <p>{@code DataBuffer.asFloat()} instead does ONE {@code synchronizeHostData()} for the whole
     * buffer, then a sync-free host read ({@code getFloatUnsynced}) — the established kompile bulk
     * idiom (AnseriniVectorStoreImpl, VlmExecutionService, TransEModel, INDArrayConverter, …).
     * Because {@code data()} exposes the raw backing buffer, a view / Fortran-order array is first
     * compacted with {@code dup('c')} so the buffer holds exactly the array's elements in row-major
     * order; contiguous c-order arrays skip the copy. (dl4j's toFloatVector should itself delegate
     * to asFloat — noted in docs/dl4j-handoff-dsp-embedding-slowness.md.)
     */
    protected float[] safeToFloatVector(INDArray array) {
        if (array == null) {
            LOG.error("[{}] Cannot convert null array to float vector", this.modelIdentifier);
            return null;
        }
        INDArray compact = null;
        boolean compacted = false;
        try {
            // Compact only when the backing buffer would not already equal the array's elements in
            // row-major order (a view or Fortran-order array). Fresh c-order op outputs skip the copy.
            if (array.isView() || array.ordering() != 'c') {
                compact = array.dup('c');
                compacted = true;
            } else {
                compact = array;
            }
            float[] flat = compact.data().asFloat();   // ONE device→host sync + bulk host read
            int len = (int) compact.length();
            // Defensive: if the backing buffer is larger than the logical array, trim to length.
            return flat.length == len ? flat : java.util.Arrays.copyOf(flat, len);
        } catch (NullPointerException e) {
            // This catches JavaCPP "Pointer address of argument X is NULL" errors
            LOG.error("[{}] Native pointer is null during bulk float extraction - array may have been closed or corrupted: {}",
                    this.modelIdentifier, e.getMessage());
            return null;
        } catch (IllegalStateException e) {
            // This catches "DataBuffer was already released" errors
            LOG.error("[{}] DataBuffer was released during bulk float extraction: {}",
                    this.modelIdentifier, e.getMessage());
            return null;
        } catch (Exception e) {
            LOG.error("[{}] Unexpected error during bulk float extraction: {}",
                    this.modelIdentifier, e.getMessage(), e);
            return null;
        } finally {
            if (compacted) closeArraySafely(compact);
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

    /**
     * Maximum fraction of JavaCPP {@code maxPhysicalBytes} that may be in use
     * before a sub-batch is considered unsafe to run at the current size.
     * If native pressure exceeds this fraction the sub-batch is halved and retried.
     * Read from system property {@code kompile.encoder.nativeMemSafetyFraction}; default 0.75.
     */
    private static final double NATIVE_MEM_SAFETY_FRACTION;

    static {
        double fraction;
        try {
            fraction = Double.parseDouble(
                    System.getProperty("kompile.encoder.nativeMemSafetyFraction", "0.75"));
            if (fraction <= 0.0 || fraction >= 1.0) fraction = 0.75;
        } catch (NumberFormatException e) {
            fraction = 0.75;
        }
        NATIVE_MEM_SAFETY_FRACTION = fraction;
    }

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

    // When > 0, every forward pass is padded up to this fixed row count (at the seq hard cap) so EXACTLY
    // ONE [maxRows x seqHardCap] DSP plan/workspace is ever compiled and retained — smaller inputs run on
    // the same max shape (extra rows discarded), instead of each distinct row count compiling+retaining its
    // own plan. Set per encodeBatch from the budget's maxRows; transiently zeroed during OOM-split retry so
    // the reactive backstop can still shrink. Inference is serial in the subprocess, so a field is safe.
    private volatile int fixedForwardRows = 0;

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
            return new ArrayList<>();
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
        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                LOG.debug("[{}] Sequential encoding interrupted at {}/{}", modelIdentifier, i, texts.size());
                return null;
            }
            String text = texts.get(i);
            SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(prefixed(text));
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
     * Encode a caller batch as logical micro-batches. In fixed-shape mode each logical batch still
     * runs through the one physical SameDiff shape {@code [maxRows x seqHardCap]}.
     */
    private List<float[]> encodeBatchWithDynamicSizing(List<String> texts) {
        long totalStartTime = System.currentTimeMillis();
        int numTexts = texts.size();

        // NOTE: seq-length bucketing (variable padToMaxLength) was reverted — it produced variable
        // SameDiff input shapes, which churned DSP plans and triggered a C++ "stale buffer"
        // use-after-free in DynamicShapePlanExecutor (encodeFromTokenized). The tokenizer keeps its
        // default fixed-maxLength padding (one stable shape) so DSP plans/buffers stay valid; the
        // shared planner below still batches ROWS for throughput, just at a stable seq length.
        if (this.tokenizerPreProcessor != null && !this.tokenizerPreProcessor.isPadToMaxLength()) {
            this.tokenizerPreProcessor.setPadToMaxLength(true);
        }

        // Step 1: Tokenize all texts and track sequence lengths with original indices
        List<IndexedEncoding> indexedEncodings = new ArrayList<>(numTexts);
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
            SamediffBertTokenizerPreProcessor.BertEncoding enc = this.tokenizerPreProcessor.encode(prefixed(text));
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

        // Plan logical row groups. With fixed-shape mode on, the budget has a single seq bucket
        // (seqHardCap), and each logical group is physically padded to [maxRows x seqHardCap] below.
        // That keeps exactly one DSP plan while still bounding caller-visible batch sizes by memory.
        int[] tokenLengths = new int[numTexts];
        SamediffBertTokenizerPreProcessor.BertEncoding[] encByIndex =
                new SamediffBertTokenizerPreProcessor.BertEncoding[numTexts];
        for (IndexedEncoding ie : indexedEncodings) {
            tokenLengths[ie.originalIndex] = ie.seqLength;
            encByIndex[ie.originalIndex] = ie.encoding;
        }

        InferenceBatchPlanner.Budget budget = buildEmbeddingBatchBudget();
        boolean fixedShape = fixedShapeEnabled();
        // Pin every forward pass to the single max shape [budget.maxRows x seqHardCap] so only ONE DSP
        // plan/workspace is ever compiled and retained (config kompile.encoder.fixedShape, default true).
        this.fixedForwardRows = fixedShape ? budget.maxRows() : 0;
        List<InferenceBatchPlanner.Batch> plannedBatches = InferenceBatchPlanner.plan(tokenLengths, budget);

        float[][] reorderedResults = new float[numTexts][];
        long embeddingStartTime = System.currentTimeMillis();
        int batchNum = 0;
        for (InferenceBatchPlanner.Batch batch : plannedBatches) {
            if (Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Batch processing interrupted at batch {}/{}",
                        modelIdentifier, batchNum, plannedBatches.size());
                return null;
            }
            int[] idxs = batch.itemIndices();
            int logicalSeq = fixedShape ? budget.seqHardCap() : batch.seqBucket();
            List<String> subTexts = new ArrayList<>(idxs.length);
            List<SamediffBertTokenizerPreProcessor.BertEncoding> subEncodings =
                    new ArrayList<>(idxs.length);
            for (int idx : idxs) {
                subTexts.add(texts.get(idx));
                subEncodings.add(encByIndex[idx]);
            }
            batchNum++;

            LOG.debug("[{}] Batch {}/{}: {} logical rows x {} seq (fixedShape={}, plannerTokens={})",
                    modelIdentifier, batchNum, plannedBatches.size(), idxs.length, logicalSeq, fixedShape, batch.tokens());

            List<float[]> subResults = encodeSingleInferenceBatchFromEncodings(subTexts, subEncodings, logicalSeq);

            if (subResults == null) {
                if (Thread.currentThread().isInterrupted()) return null;

                if (SameDiffEncoder.isFailFastOnError()) {
                    String errMsg = String.format("Batch %d failed", batchNum);
                    LOG.error("[{}] {}", modelIdentifier, errMsg);
                    throw new SameDiffEncoder.EncodingException(modelIdentifier, "batch encoding", errMsg, null);
                }

                LOG.warn("[{}] Batch {} failed - using sequential", modelIdentifier, batchNum);
                subResults = encodeSequentialFromEncodings(subTexts, subEncodings);
                if (subResults == null) return null;
            }

            for (int i = 0; i < idxs.length; i++) {
                reorderedResults[idxs[i]] = subResults.get(i);
            }
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;
        long embeddingTime = System.currentTimeMillis() - embeddingStartTime;
        LOG.info("[{}] {} token-budgeted batches: {} texts in {}ms (tokenize={}ms, embed={}ms, {} texts/sec, budget={} tok)",
                modelIdentifier, plannedBatches.size(), numTexts, totalTime, tokenizeTime, embeddingTime,
                String.format("%.1f", numTexts * 1000.0 / Math.max(1, totalTime)), budget.maxBatchTokens());

        return java.util.Arrays.asList(reorderedResults);
    }

    /**
     * Build the shared {@link InferenceBatchPlanner.Budget} for embedding batches from this model's
     * geometry and the subprocess native-memory ceiling. Every knob is configurable via system
     * properties — nothing is hardcoded; reactive OOM handling in the inference path remains the
     * backstop if the estimate proves optimistic.
     */
    private InferenceBatchPlanner.Budget buildEmbeddingBatchBudget() {
        int seqHardCap = resolveSeqHardCap();
        boolean fixedShape = fixedShapeEnabled();
        int[] buckets = fixedShape
                ? new int[]{seqHardCap}
                : parseSeqBuckets(System.getProperty("kompile.encoder.seqBuckets", "64,128,256,512"), seqHardCap);
        int hidden = Math.max(1, getEmbeddingDimension());
        double safety = parseDoubleProp("kompile.encoder.nativeMemSafetyFraction", 0.6);
        double linFactor = parseDoubleProp("kompile.encoder.activationFactor", 320.0);
        double attnFactor = parseDoubleProp("kompile.encoder.attentionFactor", 600.0);
        long memCeil = readNativeMemoryCeilingBytes();

        // THE dynamic default. A full-attention forward pass costs linear (hidden*seq) PLUS quadratic
        // (seq^2) native memory; the quadratic attention term dominates at 512 seq, so a flat token
        // budget under-counts it and a 64-row batch balloons past the subprocess RSS watchdog. Sizing
        // the row cap at the worst-case sequence length is what lets fixed-shape mode use one hard-cap
        // bucket safely. An explicitly configured absolute-max can only TIGHTEN this, never raise it
        // past the memory-safe estimate. Default factors are calibrated to the observed 64x512 -> ~1 GB/row.
        int memMaxRows = InferenceBatchPlanner.estimateMaxRowsForSeq(
                memCeil, hidden, 4, safety, linFactor, attnFactor, seqHardCap);
        int configMaxRows = this.instanceAbsoluteMaxBatchSize > 0
                ? this.instanceAbsoluteMaxBatchSize : Integer.MAX_VALUE;
        int maxRows = Math.max(1, Math.min(memMaxRows, configMaxRows));

        // Token budget is consistent with the memory-derived row cap at the hard cap. In fixed-shape
        // mode that is the only sequence bucket, so the planner cannot produce another physical shape.
        long maxBatchTokens = Long.getLong("kompile.encoder.batch.maxTokens", 0L);
        if (maxBatchTokens <= 0L) {
            maxBatchTokens = (long) maxRows * seqHardCap;
        }
        LOG.info("[{}] Embedding batch budget: maxRows={} (mem-derived={}, config-cap={}), seqHardCap={}, "
                        + "fixedShape={}, hidden={}, memCeil={}MB, safety={}, linFactor={}, attnFactor={}, maxBatchTokens={}",
                modelIdentifier, maxRows, memMaxRows,
                this.instanceAbsoluteMaxBatchSize > 0 ? this.instanceAbsoluteMaxBatchSize : -1,
                seqHardCap, fixedShape, hidden, memCeil >> 20, safety, linFactor, attnFactor, maxBatchTokens);
        return InferenceBatchPlanner.Budget.builder()
                .seqHardCap(seqHardCap)
                .seqBuckets(buckets)
                .maxBatchTokens(maxBatchTokens)
                .maxRows(maxRows)
                .build();
    }

    /**
     * Memory-derived maximum rows per batch under the current native-memory budget. DSP warmup combines
     * this with {@link #getMaxSequenceLength()} to pre-compile the single fixed physical shape used by
     * real inference: {@code [plannedMaxRows() x getMaxSequenceLength()]}.
     * Returns at least 1.
     */
    public int plannedMaxRows() {
        return buildEmbeddingBatchBudget().maxRows();
    }

    /** Whether to pin every forward pass to a single [maxRows x seqHardCap] shape (one retained DSP plan). Default on. */
    private static boolean fixedShapeEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("kompile.encoder.fixedShape", "true"));
    }

    /** Maximum sequence length this model runs at (the seq hard cap) — the single retained plan's seq dimension. */
    public int getMaxSequenceLength() {
        return resolveSeqHardCap();
    }

    private int resolveSeqHardCap() {
        if (this.tokenizerPreProcessor != null && this.tokenizerPreProcessor.getMaxLength() > 0) {
            return this.tokenizerPreProcessor.getMaxLength();
        }
        return DEFAULT_MAX_SEQUENCE_LENGTH;
    }

    private static double parseDoubleProp(String key, double def) {
        try {
            String v = System.getProperty(key);
            return v != null ? Double.parseDouble(v.trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int[] parseSeqBuckets(String csv, int seqHardCap) {
        if (csv == null || csv.isBlank()) {
            return new int[]{seqHardCap};
        }
        String[] parts = csv.split(",");
        List<Integer> vals = new ArrayList<>(parts.length);
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v > 0) {
                    vals.add(v);
                }
            } catch (NumberFormatException ignored) {
                // skip malformed bucket entries
            }
        }
        if (vals.isEmpty()) {
            return new int[]{seqHardCap};
        }
        int[] out = new int[vals.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = vals.get(i);
        }
        return out;
    }

    /**
     * Per-process native off-heap ceiling for THIS subprocess, read from the JavaCPP
     * {@code -Dorg.bytedeco.javacpp.maxbytes} the launcher sets; falls back to a heap-derived figure
     * when unset.
     *
     * <p>Deliberately does NOT read {@code maxphysicalbytes}: that property is a near-machine-TOTAL
     * system-wide guard (≈95% of host RAM) the launcher sets so sibling subprocesses don't false-trip
     * each other's OOM restart. Using it as this process's budget made the planner size batches
     * against all host RAM (e.g. 119 GB) — so a "reasonable" 64-row × 512-seq batch ballooned past
     * the parent RSS watchdog (≈50% of RAM) and got SIGKILLed. {@code maxbytes} is the real
     * per-process lever.</p>
     */
    private static long readNativeMemoryCeilingBytes() {
        long v = InferenceBatchPlanner.parseByteSize(
                System.getProperty(ND4JSystemProperties.JAVACPP_MEMORY_MAX_BYTES));
        if (v <= 0) {
            v = Math.max(2L << 30, Runtime.getRuntime().maxMemory());
        }
        return v;
    }

    /**
     * Encode texts using pre-computed encodings (avoids re-tokenization).
     */
    private List<float[]> encodeSequentialFromEncodings(List<String> texts,
                                                         List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings) {
        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) return null;
            results.add(encodeFromTokenized(texts.get(i), encodings.get(i)));
        }
        return results;
    }

    /**
     * Check whether the current native-memory usage is above the safety threshold.
     *
     * @return {@code true} if {@code Pointer.physicalBytes() / Pointer.maxPhysicalBytes() >= NATIVE_MEM_SAFETY_FRACTION}
     *         and maxPhysicalBytes is positive.  Returns {@code false} if the JVM has no cap set.
     */
    private boolean isNativeMemoryPressureHigh() {
        try {
            long maxPhysBytes = Pointer.maxPhysicalBytes();
            if (maxPhysBytes <= 0) return false;  // No cap set — cannot detect pressure
            long physBytes = Pointer.physicalBytes();
            return physBytes >= (long) (maxPhysBytes * NATIVE_MEM_SAFETY_FRACTION);
        } catch (Exception e) {
            LOG.debug("[{}] Could not read JavaCPP physical bytes: {}", modelIdentifier, e.getMessage());
            return false;
        }
    }

    /** True if a throwable (or its cause chain) is a native out-of-memory we should free + split + retry on. */
    private static boolean isNativeOomException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OutOfMemoryError) {
                return true;
            }
            String m = t.getMessage();
            if (m != null) {
                String lm = m.toLowerCase(Locale.ROOT);
                if (lm.contains("physical memory") || lm.contains("cannot allocate")
                        || lm.contains("maxphysicalbytes") || lm.contains("out of memory")
                        || lm.contains("bad_alloc")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Notify the listener (if registered) and log the resize decision.
     */
    private void notifyBatchResize(int oldBatch, int newBatch, String reason) {
        long physBytes = 0, maxPhysBytes = 0;
        try {
            physBytes = Pointer.physicalBytes();
            maxPhysBytes = Pointer.maxPhysicalBytes();
        } catch (Exception ignored) {}
        LOG.warn("[{}] EMBED_DECISION batchResize old={} new={} reason={} physicalBytes={} maxPhysicalBytes={}",
                modelIdentifier, oldBatch, newBatch, reason, physBytes, maxPhysBytes);
        BatchResizeListener listener = this.batchResizeListener;
        if (listener != null) {
            try {
                listener.onBatchResize(oldBatch, newBatch, reason, physBytes, maxPhysBytes);
            } catch (Exception e) {
                LOG.debug("[{}] BatchResizeListener threw: {}", modelIdentifier, e.getMessage());
            }
        }
    }

    /**
     * Encode a batch using pre-computed tokenizations.
     * Avoids re-tokenizing when dynamic batching has already tokenized the texts.
     *
     * <p>Includes a native-memory-pressure backoff guard: before each forward pass
     * {@link Pointer#physicalBytes()} is checked against {@link Pointer#maxPhysicalBytes()}.
     * If usage exceeds {@link #NATIVE_MEM_SAFETY_FRACTION} the sub-batch is halved and retried
     * down to size 1.  At size 1, if pressure is still above the threshold a recoverable
     * {@link OutOfMemoryError} is thrown rather than crashing the host.
     *
     * @param texts Original texts (for logging)
     * @param encodings Pre-computed tokenizations
     * @param maxSeqLength Logical maximum sequence length; fixed-shape mode physically pads to seqHardCap
     * @return List of embeddings, or null on failure
     */
    private List<float[]> encodeSingleInferenceBatchFromEncodings(
            List<String> texts,
            List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings,
            int maxSeqLength) {

        if (encodings.isEmpty()) return new ArrayList<>();

        int batchSize = encodings.size();

        // Native-memory safety is handled REACTIVELY (see the catch/retry at the end of this method):
        // we attempt the batch and, only if the forward pass actually hits the JavaCPP native cap, do we
        // free + split + retry down to size 1. No predictive soft-threshold rejection — the model's
        // resident footprint dominates physicalBytes(), so an absolute-fraction check falsely trips and
        // would reject even a single irreducible chunk (which is exactly what broke embedding before).

        // Single-plan shaping: pad the forward pass up to a FIXED [allocRows x allocSeq] shape so only ONE
        // DSP plan/workspace is ever compiled (smaller inputs reuse the max shape; the extra rows are
        // discarded after extraction). When fixedForwardRows==0 (disabled, or during OOM-split retry) this
        // is a no-op and the batch runs at its natural size.
        int fixedRows = this.fixedForwardRows;
        int allocRows = (fixedRows >= batchSize) ? fixedRows : batchSize;
        int allocSeq = (fixedRows > 0) ? Math.max(maxSeqLength, resolveSeqHardCap()) : maxSeqLength;

        // Create padded arrays at the (possibly row/seq-pinned) shape
        long[][] batchInputIds = new long[allocRows][allocSeq];
        long[][] batchAttentionMask = new long[allocRows][allocSeq];
        long[][] batchTokenTypeIds = new long[allocRows][allocSeq];

        int totalTokens = 0;
        int[] passageTokenCounts = new int[batchSize];
        for (int i = 0; i < batchSize; i++) {
            SamediffBertTokenizerPreProcessor.BertEncoding enc = encodings.get(i);
            // Clamp to the allocated seq width: the planner guarantees bucket >= real length, but this
            // keeps the copy in-bounds even if an oversized encoding ever slips through.
            int seqLen = Math.min(enc.inputIds.length, allocSeq);
            totalTokens += seqLen;
            passageTokenCounts[i] = seqLen;
            System.arraycopy(enc.inputIds, 0, batchInputIds[i], 0, seqLen);
            System.arraycopy(enc.attentionMask, 0, batchAttentionMask[i], 0, seqLen);
            if (enc.tokenTypeIds != null) {
                System.arraycopy(enc.tokenTypeIds, 0, batchTokenTypeIds[i], 0, seqLen);
            }
        }
        // Pad the row dimension up to allocRows by replicating row 0 (a real, valid input) so the forward
        // pass is well-formed; these rows' outputs are never read (extractAllEmbeddings takes only batchSize).
        for (int i = batchSize; i < allocRows; i++) {
            System.arraycopy(batchInputIds[0], 0, batchInputIds[i], 0, allocSeq);
            System.arraycopy(batchAttentionMask[0], 0, batchAttentionMask[i], 0, allocSeq);
            System.arraycopy(batchTokenTypeIds[0], 0, batchTokenTypeIds[i], 0, allocSeq);
        }
        if (allocRows != batchSize || allocSeq != maxSeqLength) {
            LOG.debug("[{}] Fixed-shape pad: {} real rows -> [{} x {}] (1-plan mode)",
                    modelIdentifier, batchSize, allocRows, allocSeq);
        }

        // Update BatchInfo so pipeline can read actual shapes
        long batchStartMs = System.currentTimeMillis();
        updateBatchInfo(batchSize, allocSeq, getEmbeddingDimension(), totalTokens,
                new long[]{allocRows, allocSeq}, new long[]{batchSize, getEmbeddingDimension()},
                "FORWARD_PASS", passageTokenCounts);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        Map<String, INDArray> outputMap = null;
        List<float[]> results = new ArrayList<>(batchSize);
        boolean nativeOom = false;

        try {
            // ========== SHAPE LOGGING: Batch Input Array Creation ==========
            LOG.info("[{}] SHAPE BATCH CREATE: Creating fixed-shape arrays for {} logical texts as [{} x {}], totalTokens={}",
                    this.modelIdentifier, batchSize, allocRows, allocSeq, totalTokens);

            // MEMORY FIX (1/2): Use Nd4j.create (not createFromArray) for per-batch input arrays.
            // createFromArray routes through the constant-buffer allocator; those buffers are
            // retained by InferenceSession.externalPlaceholderBuffers even after arr.close().
            // Nd4j.create produces regular native buffers that close() correctly frees.
            for (String inputName : this.inputTensorNamesForModel) {
                if (isInputIdsInput(inputName)) {
                    INDArray arr = Nd4j.create(batchInputIds);
                    placeholderMap.put(inputName, arr);
                    LOG.info("[{}] SHAPE BATCH CREATE: input_ids '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName, Arrays.toString(arr.shape()), arr.dataType());
                } else if (isAttentionMaskInput(inputName)) {
                    INDArray arr = Nd4j.create(batchAttentionMask);
                    placeholderMap.put(inputName, arr);
                    LOG.info("[{}] SHAPE BATCH CREATE: attention_mask '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName, Arrays.toString(arr.shape()), arr.dataType());
                } else if (isTokenTypeIdsInput(inputName)) {
                    INDArray arr = Nd4j.create(batchTokenTypeIds);
                    placeholderMap.put(inputName, arr);
                    LOG.info("[{}] SHAPE BATCH CREATE: token_type_ids '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName, Arrays.toString(arr.shape()), arr.dataType());
                }
            }

            if (Thread.currentThread().isInterrupted()) return null;

            // (native-memory safety handled reactively in the catch/retry below — no predictive throw)
            String outputTensorName = this.outputTensorNamesFromModel.get(0);

            // ========== SHAPE LOGGING: Pre-Inference Summary ==========
            LOG.info("[{}] SHAPE BATCH INFERENCE INPUT: Calling model.output() for {} logical rows using physical shape [{} x {}]",
                    this.modelIdentifier, batchSize, allocRows, allocSeq);
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

            // Extract embeddings to heap float[] before clearing session caches.
            // extractAllEmbeddings copies all values via getFloat(i,j) / System.arraycopy
            // into new float[] arrays (plain Java heap). These do NOT reference native
            // memory and are therefore safe after clearSessionCaches() runs.
            List<float[]> extracted = extractAllEmbeddings(
                    batchOutput, batchSize, batchAttentionMask);

            if (extracted == null) {
                LOG.warn("[{}] Vectorized extraction failed, falling back to sequential", modelIdentifier);
                for (int i = 0; i < batchSize; i++) {
                    try {
                        results.add(extractSingleEmbedding(
                                batchOutput, i, batchAttentionMask[i]));
                    } catch (Exception e) {
                        LOG.warn("[{}] Failed to extract embedding {}: {}", modelIdentifier, i, e.getMessage());
                        results.add(null);
                    }
                }
                extracted = results;
            }

            // MEMORY FIX: Clear InferenceSession.nodeValueOutputs AFTER extraction.
            // At this point all embeddings are in heap float[] arrays. Clearing the session
            // releases the ~47 MB of intermediate activation SDValues accumulated per forward
            // pass without touching any buffer that extraction still needed.
            clearSessionCaches();

            // Update BatchInfo with timing after inference completes
            long totalTimeMs = System.currentTimeMillis() - batchStartMs;
            double tokensPerSec = totalTimeMs > 0 ? (totalTokens * 1000.0 / totalTimeMs) : 0;
            double chunksPerSec = totalTimeMs > 0 ? (batchSize * 1000.0 / totalTimeMs) : 0;
            updateBatchInfoWithTiming(batchSize, allocSeq, getEmbeddingDimension(), totalTokens,
                    new long[]{allocRows, allocSeq}, new long[]{batchSize, getEmbeddingDimension()},
                    "COMPLETE", batchStartMs, 0, 0, 0, totalTimeMs, 0, totalTimeMs, tokensPerSec, chunksPerSec);

            return extracted;

        } catch (OutOfMemoryError oomErr) {
            nativeOom = true;
            LOG.warn("[{}] Native OOM during forward pass at batchSize={} — will free, split and retry. ({})",
                    modelIdentifier, batchSize, oomErr.getMessage());
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) return null;
            if (isNativeOomException(e)) {
                nativeOom = true;
                LOG.warn("[{}] Native memory limit hit during forward pass at batchSize={} — will free, split and retry. ({})",
                        modelIdentifier, batchSize, e.getMessage());
            } else {
                LOG.error("[{}] Error in batch from encodings: {}", modelIdentifier, e.getMessage(), e);
                if (SameDiffEncoder.isFailFastOnError()) {
                    throw new SameDiffEncoder.EncodingException(modelIdentifier, "batch from encodings", e.getMessage(), e);
                }
                return null;
            }
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

        // ===== REACTIVE NATIVE-OOM RECOVERY =====
        // The per-batch arrays were freed by the finally above. Free native memory, then split the batch
        // and retry down to size 1. Only reached when the forward pass ACTUALLY exhausted the native cap —
        // never a host crash, never a predictive pre-rejection.
        if (nativeOom) {
            try { Nd4j.getMemoryManager().invokeGc(); } catch (Throwable ignored) {}
            if (batchSize > 1) {
                int half = Math.max(1, batchSize / 2);
                notifyBatchResize(batchSize, half, "native OOM — split and retry");
                // Disable fixed-shape padding for the retry so the split ACTUALLY shrinks native memory —
                // otherwise each chunk would pad back up to the same max shape and OOM again. The retry may
                // transiently compile a smaller-shape plan; acceptable, because under OOM surviving beats
                // keeping exactly one plan. Restored after the retry completes.
                int savedFixedRows = this.fixedForwardRows;
                this.fixedForwardRows = 0;
                try {
                    List<float[]> combined = new ArrayList<>(batchSize);
                    int offset = 0;
                    while (offset < batchSize) {
                        int chunk = Math.min(half, batchSize - offset);
                        List<String> chunkTexts = texts.subList(offset, offset + chunk);
                        List<SamediffBertTokenizerPreProcessor.BertEncoding> chunkEncodings = encodings.subList(offset, offset + chunk);
                        int chunkMaxSeq = 0;
                        for (SamediffBertTokenizerPreProcessor.BertEncoding e : chunkEncodings) {
                            chunkMaxSeq = Math.max(chunkMaxSeq, e.inputIds.length);
                        }
                        List<float[]> chunkResults = encodeSingleInferenceBatchFromEncodings(chunkTexts, chunkEncodings, chunkMaxSeq);
                        if (chunkResults == null) return null;
                        combined.addAll(chunkResults);
                        offset += chunk;
                    }
                    return combined;
                } finally {
                    this.fixedForwardRows = savedFixedRows;
                }
            }
            LOG.error("[{}] Native OOM even at batchSize=1 — the model's resident size plus one sample exceed the "
                    + "native cap. Skipping this chunk. Raise kompile.embedding.anserini.subprocessMaxPhysicalMb.",
                    modelIdentifier);
            return null;
        }
        return results; // unreachable: every try path returns; OOM paths handled above
    }

    /**
     * Encode a single inference batch (up to MAX_INFERENCE_BATCH_SIZE texts).
     * Includes detailed timing instrumentation for performance analysis.
     */
    private List<float[]> encodeSingleInferenceBatch(List<String> texts) {
        if (texts.isEmpty()) return new ArrayList<>();

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
        List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings = new ArrayList<>(texts.size());
        int maxLen = 0;
        int totalTokens = 0;
        int[] passageTokenCounts = new int[texts.size()];

        for (int i = 0; i < texts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) return null;
            SamediffBertTokenizerPreProcessor.BertEncoding enc = this.tokenizerPreProcessor.encode(prefixed(texts.get(i)));
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
        List<float[]> results = new ArrayList<>(batchSize);

        try {
            // ========== STEP 3: TENSOR CREATION ==========
            updateBatchInfoWithTiming(batchSize, maxLen, getEmbeddingDimension(), totalTokens,
                    new long[]{batchSize, maxLen}, new long[]{batchSize, getEmbeddingDimension()},
                    "TENSOR_CREATION", batchStartMs, tokenizeTimeMs, paddingTimeMs, 0, 0, 0, 0, 0, 0, passageTokenCounts);

            long tensorStart = System.nanoTime();

            // ========== SHAPE LOGGING: Single Batch Input Array Creation ==========
            LOG.info("[{}] SHAPE SINGLE_BATCH CREATE: Creating tensors for {} texts, maxLen={}, totalTokens={}",
                    this.modelIdentifier, batchSize, maxLen, totalTokens);

            // MEMORY FIX: Use Nd4j.create (not createFromArray) to avoid constant-buffer path.
            for (String inputName : this.inputTensorNamesForModel) {
                if (isInputIdsInput(inputName)) {
                    INDArray arr = Nd4j.create(batchInputIds);
                    placeholderMap.put(inputName, arr);
                    LOG.info("[{}] SHAPE SINGLE_BATCH CREATE: input_ids '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName, Arrays.toString(arr.shape()), arr.dataType());
                } else if (isAttentionMaskInput(inputName)) {
                    INDArray arr = Nd4j.create(batchAttentionMask);
                    placeholderMap.put(inputName, arr);
                    LOG.info("[{}] SHAPE SINGLE_BATCH CREATE: attention_mask '{}' -> shape={}, dtype={}",
                            this.modelIdentifier, inputName, Arrays.toString(arr.shape()), arr.dataType());
                } else if (isTokenTypeIdsInput(inputName)) {
                    INDArray arr = Nd4j.create(batchTokenTypeIds);
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

            // Extract embeddings to heap float[] BEFORE clearing session caches.
            // extractAllEmbeddings copies all values into new float[] (plain Java heap).
            // These survive clearSessionCaches() because they hold no native pointers.
            List<float[]> extracted = extractAllEmbeddings(
                    batchOutput, batchSize, batchAttentionMask);
            if (extracted == null) {
                LOG.warn("[{}] Vectorized extraction failed, falling back to sequential", modelIdentifier);
                for (int i = 0; i < batchSize; i++) {
                    try {
                        results.add(extractSingleEmbedding(
                                batchOutput, i, batchAttentionMask[i]));
                    } catch (Exception e) {
                        LOG.warn("[{}] Failed to extract embedding {}: {}", modelIdentifier, i, e.getMessage());
                        results.add(null);
                    }
                }
            } else {
                results = extracted;
            }

            long extractEnd = System.nanoTime();

            // MEMORY FIX: Clear InferenceSession.nodeValueOutputs AFTER extraction.
            // All embeddings are now in heap float[] arrays. Clearing the session releases
            // the ~47 MB of intermediate activation SDValues per batch without touching
            // any buffer that extraction still needed.
            clearSessionCaches();

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
    private List<float[]> extractAllEmbeddings(
            INDArray batchOutput, int batchSize, long[][] attentionMasks) {
        if (batchOutput == null || batchOutput.isEmpty()) {
            LOG.error("[{}] Cannot extract embeddings from null/empty batch output", modelIdentifier);
            return null;
        }

        long[] shape = batchOutput.shape();
        LOG.info("[{}] VECTORIZED EXTRACT: Extracting {} embeddings from batchOutput shape={}, rank={}",
                modelIdentifier, batchSize, Arrays.toString(shape), batchOutput.rank());

        INDArray clsEmbeddings = null;
        INDArray normalized = null;
        INDArray logicalEmbeddings = null;
        boolean logicalEmbeddingsIsView = false;
        INDArray rawNorms = null;
        INDArray boundedNorms = null;
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
                if (poolingStrategy == PoolingStrategy.MEAN) {
                    clsEmbeddings = maskedMeanPoolBatch(
                            batchOutput, attentionMasks, batchSize);
                    LOG.info("[{}] VECTORIZED EXTRACT: Applied masked mean pooling to {} rows -> shape={}",
                            modelIdentifier, batchSize, Arrays.toString(clsEmbeddings.shape()));
                } else {
                    // AUTO preserves the generic encoder's legacy first-token contract.
                    clsEmbeddings = batchOutput.get(
                            NDArrayIndex.all(), NDArrayIndex.point(0), NDArrayIndex.all());
                    LOG.info("[{}] VECTORIZED EXTRACT: From 3D tensor [all][0][all] -> clsEmbeddings shape={}",
                            modelIdentifier, Arrays.toString(clsEmbeddings.shape()));
                }
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

            // Ensure we have 2D [physicalRows, hidden] shape; fixed-shape mode may have more
            // physical rows than the logical batchSize, and those extra rows are sliced below.
            if (clsEmbeddings.rank() != 2) {
                long physicalRowsForReshape = clsEmbeddings.shape().length > 0
                        ? Math.max(batchSize, clsEmbeddings.shape()[0]) : batchSize;
                clsEmbeddings = clsEmbeddings.reshape(physicalRowsForReshape, -1);
            }

            // In fixed-shape mode the physical DSP output can have padded rows
            // (for example [18, hidden]) while this logical batch only requested
            // fewer rows. Keep the single physical plan, but return only the
            // logical rows that correspond to caller inputs.
            long physicalRows = clsEmbeddings.shape()[0];
            if (physicalRows < batchSize) {
                LOG.error("[{}] Batch size mismatch: expected at least {} rows but got shape {}",
                        modelIdentifier, batchSize, Arrays.toString(clsEmbeddings.shape()));
                return null;
            }
            if (physicalRows > batchSize) {
                logicalEmbeddings = clsEmbeddings.get(
                        NDArrayIndex.interval(0, batchSize),
                        NDArrayIndex.all());
                logicalEmbeddingsIsView = true;
                LOG.debug("[{}] VECTORIZED EXTRACT: using first {} logical rows from fixed-shape output {}",
                        modelIdentifier, batchSize, Arrays.toString(clsEmbeddings.shape()));
            } else {
                logicalEmbeddings = clsEmbeddings;
            }

            int embeddingDim = (int) logicalEmbeddings.shape()[1];

            if (this.normalizeOutput) {
                // SANITIZE: Replace NaN and ±Inf with 0 BEFORE computing norms (two vectorized in-place
                // ops: isNan() pass + isInfinite() pass).  A NaN in any element makes norm2() return NaN
                // for that row, which Transforms.max PROPAGATES (max(NaN, epsilon) == NaN on most
                // platforms), cascading into NaN after div.  Zeroing non-finite values preserves all
                // finite components and produces a valid unit vector after normalization.  A row that was
                // entirely NaN becomes all-zero; norm2 returns 0 for that row; the epsilon clamp raises
                // it to the float16-safe norm floor; division yields a finite vector; isIndexableEmbedding
                // correctly rejects it (completely broken FP16 forward pass).
                sanitizeNonFinite(logicalEmbeddings);
                // VECTORIZED L2 normalization using ND4J's native norm2 operation
                // norm2(1) computes L2 norm along dimension 1 (hidden dim) for each row
                rawNorms = logicalEmbeddings.norm2(1);  // [batch] - L2 norm per row

                // Clamp norms to avoid division by zero, then reshape for broadcast
                // Use Transforms.max for vectorized clamping with epsilon
                INDArray epsilon = Nd4j.scalar((float) MIN_L2_NORM);
                try {
                    boundedNorms = Transforms.max(rawNorms, epsilon, false);
                } finally {
                    epsilon.close();
                }
                norms = boundedNorms.reshape(batchSize, 1).dup();

                // Broadcast divide: [batch, hidden] / [batch, 1] -> [batch, hidden]
                normalized = logicalEmbeddings.div(norms);

                LOG.info("[{}] VECTORIZED EXTRACT: Normalized {} embeddings using norm2(), output shape={}",
                        modelIdentifier, batchSize, Arrays.toString(normalized.shape()));
            } else {
                normalized = logicalEmbeddings;
            }

            // BULK EXTRACTION: ONE device→host copy, then split into per-row arrays.
            // safeToFloatVector() does a SINGLE synchronizeHostData() for the whole buffer via
            // DataBuffer.asFloat() (sync-free getFloatUnsynced host read). It deliberately does NOT use
            // INDArray.toFloatVector(): toFloatVector loops getFloat(i), and on a CUDA DataBuffer every
            // getFloat triggers synchronizeHostData → CudaExecutioner.commit — ~(batch*dim ≈ 24k) GPU→host
            // syncs PER forward pass. Thread dumps showed that per-element commit loop, not the forward
            // pass, dominated embedding wall-clock (~28s of host-side dispatch between batches). This is
            // the mandate's "batch the retrieval, never getFloat per element to avoid JNI/GPU round-trips."
            float[] flat = safeToFloatVector(normalized);
            if (flat == null) {
                LOG.error("[{}] Bulk float extraction returned null for batch of {}", modelIdentifier, batchSize);
                return null;
            }
            List<float[]> results = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                float[] embedding = new float[embeddingDim];
                System.arraycopy(flat, i * embeddingDim, embedding, 0, embeddingDim);
                results.add(embedding);
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
            if (boundedNorms != null && boundedNorms != rawNorms) {
                try { boundedNorms.close(); } catch (Exception ignored) {}
            }
            if (rawNorms != null) try { rawNorms.close(); } catch (Exception ignored) {}
            // Only close normalized if it's a new array (not the same as clsEmbeddings)
            if (normalized != null && normalized != clsEmbeddings && this.normalizeOutput) {
                try { normalized.close(); } catch (Exception ignored) {}
            }
            if (logicalEmbeddingsIsView && logicalEmbeddings != null) {
                try { logicalEmbeddings.close(); } catch (Exception ignored) {}
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
    private float[] extractSingleEmbedding(
            INDArray batchOutput, int batchIndex, long[] attentionMask) {
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
                if (poolingStrategy == PoolingStrategy.MEAN) {
                    singleOutput = batchOutput.get(
                            NDArrayIndex.interval(batchIndex, batchIndex + 1),
                            NDArrayIndex.all(), NDArrayIndex.all());
                    clsEmbedding = maskedMeanPool(singleOutput, attentionMask);
                    LOG.info("[{}] SHAPE EXTRACT: Applied masked mean pooling to row {} -> shape={}",
                            modelIdentifier, batchIndex, Arrays.toString(clsEmbedding.shape()));
                } else {
                    singleOutput = batchOutput.get(
                            NDArrayIndex.point(batchIndex), NDArrayIndex.point(0), NDArrayIndex.all());
                    clsEmbedding = singleOutput;
                    LOG.info("[{}] SHAPE EXTRACT: From 3D tensor [{}][0][*] -> clsEmbedding shape={}",
                            modelIdentifier, batchIndex, Arrays.toString(clsEmbedding.shape()));
                }
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
                sanitizeNonFinite(reshapedEmbedding);
                // L2 normalization
                squared = reshapedEmbedding.mul(reshapedEmbedding);
                sumOfSquares = squared.sum(true, 1);
                double sumVal = sumOfSquares.getDouble(0);
                if (Double.isNaN(sumVal) || sumVal < 0) {
                    sumVal = 0.0;
                }
                double normVal = l2Denominator(sumVal);
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

    static double l2Denominator(double sumOfSquares) {
        if (!Double.isFinite(sumOfSquares) || sumOfSquares <= 0.0) {
            return MIN_L2_NORM;
        }
        return Math.max(Math.sqrt(sumOfSquares), MIN_L2_NORM);
    }
}
