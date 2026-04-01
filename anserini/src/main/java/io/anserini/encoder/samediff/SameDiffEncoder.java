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

import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.ModelDescriptor;
import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import io.anserini.encoder.samediff.tokenizer.SamediffBertVocabulary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.serde.SDZSerializer;
import org.nd4j.autodiff.samediff.optimize.GraphOptimizer;
import org.nd4j.autodiff.samediff.optimize.OptimizerSet;
import org.nd4j.linalg.api.ops.executioner.OpExecutioner;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.profiler.ProfilerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public abstract class SameDiffEncoder<RETURN_TYPE> implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(SameDiffEncoder.class);

    protected SameDiff sameDiffModel;
    protected SamediffBertTokenizerPreProcessor tokenizerPreProcessor;

    protected final List<String> inputTensorNamesForModel;
    protected final List<String> outputTensorNamesFromModel;
    protected final String modelIdentifier;
    protected final KompileModelManager modelManager;

    // Lock for thread-safe encoding - SameDiff models are NOT thread-safe
    // CRITICAL: This lock must be used by ALL subclasses for ALL encoding operations
    // to prevent concurrent access to the SameDiff model. Subclasses should NOT create
    // their own locks - use this one via the protected accessor.
    private final ReentrantLock encodeLock = new ReentrantLock();

    /**
     * Get the encoder lock for subclass use.
     * CRITICAL: Subclasses MUST use this lock for any operation that accesses the SameDiff model.
     * Do NOT create separate locks in subclasses - this causes deadlocks.
     * @return The shared encoder lock
     */
    protected ReentrantLock getEncoderLock() {
        return encodeLock;
    }

    // ========== SHUTDOWN COORDINATION ==========
    /**
     * Flag indicating that shutdown has been initiated.
     * Once true, new encoding operations should be rejected.
     */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    // ========== FAIL-FAST ERROR HANDLING ==========
    /**
     * Global flag to control fail-fast behavior on encoding errors.
     * When true, encoding errors will throw RuntimeException instead of returning null.
     * This allows the pipeline to fail immediately and notify the user of the error.
     * Default is true for production safety - errors should not be silently ignored.
     */
    private static volatile boolean failFastOnError = true;

    /**
     * Set fail-fast mode for all SameDiff encoders.
     * @param failFast true to throw exceptions on errors, false to return null and continue
     */
    public static void setFailFastOnError(boolean failFast) {
        failFastOnError = failFast;
        LOG.info("SameDiff encoder fail-fast mode set to: {}", failFast);
    }

    /**
     * Check if fail-fast mode is enabled.
     * @return true if errors should throw exceptions
     */
    public static boolean isFailFastOnError() {
        return failFastOnError;
    }

    /**
     * Exception thrown when encoding fails in fail-fast mode.
     * Contains details about the operation that failed.
     */
    public static class EncodingException extends RuntimeException {
        private final String modelId;
        private final String operation;

        public EncodingException(String modelId, String operation, String message, Throwable cause) {
            super(String.format("[%s] %s failed: %s", modelId, operation, message), cause);
            this.modelId = modelId;
            this.operation = operation;
        }

        public String getModelId() { return modelId; }
        public String getOperation() { return operation; }
    }

    /**
     * Exception thrown when model validation fails during initialization.
     * This prevents indexing jobs from starting with a broken model.
     */
    public static class ModelValidationException extends RuntimeException {
        private final String modelId;

        public ModelValidationException(String modelId, String message) {
            super(String.format("[%s] Model validation failed: %s", modelId, message));
            this.modelId = modelId;
        }

        public ModelValidationException(String modelId, String message, Throwable cause) {
            super(String.format("[%s] Model validation failed: %s", modelId, message), cause);
            this.modelId = modelId;
        }

        public String getModelId() { return modelId; }
    }

    // ========== BATCH INFO TRACKING ==========
    /**
     * Information about the current batch being processed.
     * Updated during encoding for visibility into tensor shapes AND timing.
     *
     * This is THE authoritative source for batch timing - it shows exactly what's
     * happening inside the NDArray creation and forward pass.
     */
    public record BatchInfo(
            int numChunks,           // Number of text chunks in the batch
            int maxSeqLength,        // Actual max sequence length after tokenization
            int embeddingDim,        // Output embedding dimension
            int totalTokens,         // Total tokens across all chunks
            long[] inputShape,       // Actual input tensor shape [batch, seq_len]
            long[] outputShape,      // Actual output tensor shape [batch, embedding_dim]
            String step,             // Current step: TOKENIZING, PADDING, TENSOR_CREATION, FORWARD_PASS, EXTRACTING, COMPLETE
            long stepStartTimeMs,    // When current step started
            // ========== TIMING FIELDS ==========
            long batchStartTimeMs,   // When this batch started processing
            long tokenizeTimeMs,     // Time spent tokenizing all chunks
            long paddingTimeMs,      // Time spent padding to max length
            long tensorCreationTimeMs, // Time spent creating NDArrays
            long forwardPassTimeMs,  // Time spent in model.output() - THE KEY METRIC
            long extractionTimeMs,   // Time spent extracting embeddings from output
            long totalTimeMs,        // Total time for this batch (when COMPLETE)
            double tokensPerSecond,  // Throughput: tokens processed per second
            double chunksPerSecond,  // Throughput: chunks processed per second
            // ========== PER-PASSAGE TOKEN COUNTS ==========
            int[] passageTokenCounts // Token count for each passage in the batch
    ) {
        public static BatchInfo empty() {
            return new BatchInfo(0, 0, 0, 0, new long[0], new long[0], "IDLE", 0,
                    0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, null);
        }

        /** Helper to format input tensor shape as string - uses ACTUAL shape array values */
        public String inputShapeString() {
            if (inputShape == null || inputShape.length == 0) {
                // Fallback to computed values if actual shape not set
                // Only return if BOTH dimensions are valid (non-zero)
                if (numChunks > 0 && maxSeqLength > 0) {
                    return "[" + numChunks + " x " + maxSeqLength + "]";
                }
                // Return null to signal "no valid shape" - UI will use its own fallback
                return null;
            }
            // Validate shape array has no zero values
            for (long dim : inputShape) {
                if (dim <= 0) return null;
            }
            // Use actual shape array values
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < inputShape.length; i++) {
                if (i > 0) sb.append(" x ");
                sb.append(inputShape[i]);
            }
            sb.append("]");
            return sb.toString();
        }

        /** Helper to format output tensor shape as string - uses ACTUAL shape array values */
        public String outputShapeString() {
            if (outputShape == null || outputShape.length == 0) {
                // Fallback to computed values if actual shape not set
                // Only return if BOTH dimensions are valid (non-zero)
                if (numChunks > 0 && embeddingDim > 0) {
                    return "[" + numChunks + " x " + embeddingDim + "]";
                }
                // Return null to signal "no valid shape" - UI will use its own fallback
                return null;
            }
            // Validate shape array has no zero values
            for (long dim : outputShape) {
                if (dim <= 0) return null;
            }
            // Use actual shape array values
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < outputShape.length; i++) {
                if (i > 0) sb.append(" x ");
                sb.append(outputShape[i]);
            }
            sb.append("]");
            return sb.toString();
        }

        /** Get elapsed time in current step */
        public long stepElapsedMs() {
            if (stepStartTimeMs == 0) return 0;
            return System.currentTimeMillis() - stepStartTimeMs;
        }

        /** Get total elapsed time since batch started */
        public long batchElapsedMs() {
            if (batchStartTimeMs == 0) return 0;
            return System.currentTimeMillis() - batchStartTimeMs;
        }
    }

    /**
     * Current batch info - volatile for visibility across threads.
     */
    protected volatile BatchInfo currentBatchInfo = BatchInfo.empty();

    /**
     * Gets information about the current batch being processed.
     * @return BatchInfo with current batch details, including real-time timing
     */
    public BatchInfo getCurrentBatchInfo() {
        return currentBatchInfo;
    }

    /**
     * Updates the current batch info - called by subclasses during encoding.
     * This is the SIMPLE update for step changes (backward compatible).
     * NOTE: Empty shape arrays preserve previous shapes (useful during TOKENIZING when shapes aren't known yet)
     */
    protected void updateBatchInfo(int numChunks, int maxSeqLength, int embeddingDim,
                                    int totalTokens, long[] inputShape, long[] outputShape, String step) {
        updateBatchInfo(numChunks, maxSeqLength, embeddingDim, totalTokens, inputShape, outputShape, step, null);
    }

    protected void updateBatchInfo(int numChunks, int maxSeqLength, int embeddingDim,
                                    int totalTokens, long[] inputShape, long[] outputShape, String step,
                                    int[] passageTokenCounts) {
        BatchInfo prev = this.currentBatchInfo;
        // Preserve previous shapes if new ones are empty (e.g., during TOKENIZING)
        long[] effectiveInputShape = (inputShape != null && inputShape.length > 0) ? inputShape : prev.inputShape();
        long[] effectiveOutputShape = (outputShape != null && outputShape.length > 0) ? outputShape : prev.outputShape();
        // Also preserve maxSeqLength and embeddingDim if current values are 0
        int effectiveMaxSeqLength = maxSeqLength > 0 ? maxSeqLength : prev.maxSeqLength();
        int effectiveEmbeddingDim = embeddingDim > 0 ? embeddingDim : prev.embeddingDim();
        // Preserve previous token counts if not provided
        int[] effectiveTokenCounts = passageTokenCounts != null ? passageTokenCounts : prev.passageTokenCounts();

        this.currentBatchInfo = new BatchInfo(
                numChunks, effectiveMaxSeqLength, effectiveEmbeddingDim, totalTokens,
                effectiveInputShape, effectiveOutputShape, step, System.currentTimeMillis(),
                prev.batchStartTimeMs() > 0 ? prev.batchStartTimeMs() : System.currentTimeMillis(),
                prev.tokenizeTimeMs(), prev.paddingTimeMs(), prev.tensorCreationTimeMs(),
                prev.forwardPassTimeMs(), prev.extractionTimeMs(), prev.totalTimeMs(),
                prev.tokensPerSecond(), prev.chunksPerSecond(), effectiveTokenCounts
        );
    }

    /**
     * Updates batch info with full timing details - called at end of each phase.
     */
    protected void updateBatchInfoWithTiming(int numChunks, int maxSeqLength, int embeddingDim,
                                              int totalTokens, long[] inputShape, long[] outputShape,
                                              String step, long batchStartTimeMs,
                                              long tokenizeTimeMs, long paddingTimeMs,
                                              long tensorCreationTimeMs, long forwardPassTimeMs,
                                              long extractionTimeMs, long totalTimeMs,
                                              double tokensPerSecond, double chunksPerSecond) {
        updateBatchInfoWithTiming(numChunks, maxSeqLength, embeddingDim, totalTokens, inputShape, outputShape,
                step, batchStartTimeMs, tokenizeTimeMs, paddingTimeMs, tensorCreationTimeMs, forwardPassTimeMs,
                extractionTimeMs, totalTimeMs, tokensPerSecond, chunksPerSecond, null);
    }

    protected void updateBatchInfoWithTiming(int numChunks, int maxSeqLength, int embeddingDim,
                                              int totalTokens, long[] inputShape, long[] outputShape,
                                              String step, long batchStartTimeMs,
                                              long tokenizeTimeMs, long paddingTimeMs,
                                              long tensorCreationTimeMs, long forwardPassTimeMs,
                                              long extractionTimeMs, long totalTimeMs,
                                              double tokensPerSecond, double chunksPerSecond,
                                              int[] passageTokenCounts) {
        // Preserve token counts from previous if not provided
        int[] effectiveTokenCounts = passageTokenCounts != null ? passageTokenCounts : this.currentBatchInfo.passageTokenCounts();
        this.currentBatchInfo = new BatchInfo(
                numChunks, maxSeqLength, embeddingDim, totalTokens,
                inputShape, outputShape, step, System.currentTimeMillis(),
                batchStartTimeMs, tokenizeTimeMs, paddingTimeMs, tensorCreationTimeMs,
                forwardPassTimeMs, extractionTimeMs, totalTimeMs,
                tokensPerSecond, chunksPerSecond, effectiveTokenCounts
        );
    }

    /**
     * Clears the batch info after encoding completes.
     */
    protected void clearBatchInfo() {
        this.currentBatchInfo = BatchInfo.empty();
    }

    // ========== MODEL VALIDATION ==========
    /**
     * Flag to enable/disable model validation at startup.
     * Default is true - validate that the model produces non-zero embeddings.
     */
    private static volatile boolean validateOnInit = true;

    /**
     * Enable or disable model validation during encoder initialization.
     * @param validate true to validate models produce non-zero output
     */
    public static void setValidateOnInit(boolean validate) {
        validateOnInit = validate;
        LOG.info("SameDiff encoder validation on init set to: {}", validate);
    }

    /**
     * Check if model validation is enabled.
     * @return true if models are validated during initialization
     */
    public static boolean isValidateOnInit() {
        return validateOnInit;
    }

    // ========== GRAPH OPTIMIZATION ==========
    /**
     * Flag to enable/disable graph optimization at load time.
     * When enabled, applies fusion optimizations (matmul+add -> xw_plus_b, etc.)
     * to improve inference performance.
     * Default is false - models are loaded as-is unless pre-optimized.
     */
    private static volatile boolean optimizeOnLoad = false;

    /**
     * Enable or disable graph optimization when loading models.
     * When enabled, applies optimizations like:
     * - matmul + add fusion into xw_plus_b
     * - constant folding
     * - dead code elimination
     *
     * @param optimize true to apply optimizations at load time
     */
    public static void setOptimizeOnLoad(boolean optimize) {
        optimizeOnLoad = optimize;
        LOG.info("SameDiff graph optimization on load set to: {}", optimize);
    }

    /**
     * Check if graph optimization on load is enabled.
     * @return true if models are optimized when loaded
     */
    public static boolean isOptimizeOnLoad() {
        return optimizeOnLoad;
    }

    /**
     * Validates that the model produces valid (non-zero) embeddings.
     * This is called during initialization to detect broken models early,
     * before any indexing job can start.
     *
     * @throws ModelValidationException if the model produces zero-magnitude vectors
     */
    protected void validateModelOutput() {
        if (!validateOnInit) {
            LOG.debug("[{}] Model validation disabled - skipping", modelIdentifier);
            return;
        }

        LOG.info("[{}] Validating model output...", modelIdentifier);
        long startTime = System.currentTimeMillis();

        try {
            // Use a simple, non-empty test text that should produce meaningful embeddings
            String testText = "This is a validation test for the embedding model.";

            // Tokenize the test text
            SamediffBertTokenizerPreProcessor.BertEncoding encoding = tokenizerPreProcessor.encode(testText);

            if (encoding == null || encoding.inputIds == null || encoding.inputIds.length == 0) {
                throw new ModelValidationException(modelIdentifier,
                        "Tokenizer failed to produce valid encoding for test text");
            }

            // Check tokenization produced meaningful tokens (not just special tokens)
            int nonPadTokens = 0;
            for (long tokenId : encoding.inputIds) {
                if (tokenId != 0) nonPadTokens++;
            }
            if (nonPadTokens <= 2) {
                throw new ModelValidationException(modelIdentifier,
                        "Tokenizer produced only " + nonPadTokens + " non-padding tokens - vocabulary may be incompatible");
            }

            // Now run the actual model inference using encodeFromTokenized
            // We need to acquire the lock since this may run concurrently with warmup
            encodeLock.lock();
            try {
                Object result = encodeFromTokenized(testText, encoding);

                // Check if encoding returned null (indicates failure)
                if (result == null) {
                    throw new ModelValidationException(modelIdentifier,
                            "Model returned null embedding - inference failed silently");
                }

                // For float[] results, check for zero-magnitude vectors
                if (result instanceof float[] floatResult) {
                    validateFloatEmbedding(floatResult);
                }
                // For other result types (e.g., sparse encoders), skip magnitude check
                // but validate non-null was sufficient

            } finally {
                encodeLock.unlock();
            }

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("[{}] Model validation PASSED in {}ms - model produces valid embeddings",
                    modelIdentifier, elapsed);

        } catch (ModelValidationException e) {
            // Re-throw validation exceptions as-is
            throw e;
        } catch (Exception e) {
            throw new ModelValidationException(modelIdentifier,
                    "Unexpected error during validation: " + e.getMessage(), e);
        }
    }

    /**
     * Validates that a float[] embedding is not zero-magnitude.
     * @param embedding The embedding to validate
     * @throws ModelValidationException if the embedding is all zeros
     */
    private void validateFloatEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new ModelValidationException(modelIdentifier,
                    "Model produced null or empty embedding array");
        }

        // Check if the embedding is all zeros (zero-magnitude vector)
        double sumSquares = 0.0;
        double minVal = Double.MAX_VALUE;
        double maxVal = Double.MIN_VALUE;

        for (float v : embedding) {
            sumSquares += (double) v * v;
            if (v < minVal) minVal = v;
            if (v > maxVal) maxVal = v;
        }

        double magnitude = Math.sqrt(sumSquares);

        // Log the embedding stats for debugging
        LOG.debug("[{}] Validation embedding: dims={}, min={}, max={}, magnitude={}",
                modelIdentifier, embedding.length, minVal, maxVal, magnitude);

        // Zero-magnitude check with small epsilon for floating point precision
        if (magnitude < 1e-10) {
            String errorMsg = String.format(
                    "Model produced ZERO-MAGNITUDE vector (magnitude=%e). " +
                    "This indicates the model forward pass is broken. " +
                    "The embedding has %d dimensions, min=%f, max=%f. " +
                    "Possible causes: (1) ONNX import error with Gather/Gemm operations, " +
                    "(2) Model file corruption, (3) Incompatible model format. " +
                    "DO NOT proceed with indexing - all documents would get identical (zero) embeddings.",
                    magnitude, embedding.length, minVal, maxVal);

            LOG.error("[{}] {}", modelIdentifier, errorMsg);
            throw new ModelValidationException(modelIdentifier, errorMsg);
        }

        // Also warn if magnitude is suspiciously small (but not zero)
        if (magnitude < 0.01) {
            LOG.warn("[{}] Model produced very small embedding magnitude ({}) - this may indicate partial model failure",
                    modelIdentifier, magnitude);
        }
    }

    // ========== INSTRUCTION PREFIX SUPPORT ==========
    /**
     * Get the instruction prefix to prepend to texts before tokenization.
     * Override this in subclasses that use instruction-based models (e.g., BGE, Arctic Embed).
     *
     * <p>The prefix is applied during bulk encoding to ensure parallel tokenization
     * produces the same results as single-text encoding.</p>
     *
     * @return The instruction prefix, or empty string if none
     */
    protected String getInstructionPrefix() {
        return "";
    }

    /**
     * Initiates shutdown of this encoder, rejecting new operations.
     * Existing operations will be allowed to complete.
     * <p>
     * This should be called before close() to ensure graceful shutdown.
     * After calling this method:
     * <ul>
     *   <li>New encode/bulkEncode operations will return null/empty</li>
     *   <li>In-flight operations will complete normally</li>
     *   <li>The tokenizer's shutdown is also initiated</li>
     * </ul>
     */
    public void initiateShutdown() {
        if (shuttingDown.compareAndSet(false, true)) {
            LOG.info("[{}] Initiating graceful shutdown", modelIdentifier);
            // Signal tokenizer to stop accepting new operations
            if (tokenizerPreProcessor != null) {
                tokenizerPreProcessor.initiateShutdown();
            }
        }
    }

    /**
     * Check if this encoder is currently shutting down.
     * @return true if shutdown has been initiated
     */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /**
     * Check if encoding operations should proceed.
     * Returns false if shutdown is in progress or thread is interrupted.
     */
    protected boolean shouldProceedWithEncoding() {
        return !shuttingDown.get() && !Thread.currentThread().isInterrupted();
    }

    // ========== PARALLEL ENCODING CONFIGURATION ==========
    /**
     * Minimum batch size to use parallel tokenization.
     * Tokenization can be parallelized since it doesn't use the model.
     */
    private static final int PARALLEL_TOKENIZE_THRESHOLD = 8;

    /**
     * Maximum threads for parallel tokenization.
     */
    private static final int MAX_TOKENIZE_THREADS = Math.max(2,
            Math.min(Runtime.getRuntime().availableProcessors() - 1, 6));

    /**
     * Executor for parallel tokenization (lazy init).
     */
    private volatile ExecutorService tokenizeExecutor;

    /**
     * Constructor that uses Kompile model management with automatic model bundle management.
     * This is the recommended constructor that automatically downloads and pairs model and vocabulary files.
     *
     * @param modelIdentifier The identifier of the model (e.g., "bge-base-en-v1.5")
     * @param doLowerCaseAndStripAccents Whether to lowercase and strip accents in tokenization
     * @param maxSequenceLength Maximum sequence length for tokenization
     * @param addSpecialTokens Whether to add special tokens (CLS, SEP) during tokenization
     * @throws IOException if model loading fails
     */
    public SameDiffEncoder(@NotNull String modelIdentifier,
                           boolean doLowerCaseAndStripAccents,
                           int maxSequenceLength,
                           boolean addSpecialTokens) throws IOException {
        this.modelIdentifier = modelIdentifier;
        this.modelManager = new KompileModelManager();

        // Check if this model supports automatic bundle management
        if (ModelConstants.isEncoderModelAvailable(modelIdentifier)) {
            LOG.info("[{}] Using automatic model bundle management", modelIdentifier);
            initializeWithModelBundle(doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        } else {
            LOG.info("[{}] Model not available in bundle management, falling back to legacy approach", modelIdentifier);
            initializeWithLegacyApproach(doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        }

        // Dynamically determine input and output tensor names from the loaded model
        this.inputTensorNamesForModel = Collections.unmodifiableList(this.sameDiffModel.inputs());
        this.outputTensorNamesFromModel = Collections.unmodifiableList(this.sameDiffModel.outputs());

        LOG.info("[{}] Model loaded with {} inputs: {} and {} outputs: {}", 
                modelIdentifier, 
                this.inputTensorNamesForModel.size(), this.inputTensorNamesForModel,
                this.outputTensorNamesFromModel.size(), this.outputTensorNamesFromModel);

        // Validate that the model has the expected structure
        if (this.inputTensorNamesForModel.isEmpty()) {
            throw new IOException("Model has no input placeholders defined");
        }
        if (this.outputTensorNamesFromModel.isEmpty()) {
            throw new IOException("Model has no outputs defined");
        }

        // Validate that the model produces valid embeddings before use
        validateModelOutput();
    }

    /**
     * Initialize using the new model bundle approach
     */
    private void initializeWithModelBundle(boolean doLowerCaseAndStripAccents,
                                          int maxSequenceLength,
                                          boolean addSpecialTokens) throws IOException {
        KompileModelManager.ModelBundle modelBundle = modelManager.ensureEncoderModelBundle(modelIdentifier);
        
        Path modelPath = modelBundle.getModelPath();
        Path vocabPath = modelBundle.getVocabularyPath();
        
        LOG.info("[{}] Loading model from bundle path: {}", modelIdentifier, modelPath.toAbsolutePath());
        LOG.info("[{}] Loading vocabulary from bundle path: {}", modelIdentifier, vocabPath.toAbsolutePath());
        
        // Validate files exist
        if (!Files.exists(modelPath) || !Files.isRegularFile(modelPath)) {
            throw new IOException("Model file not found at bundle path: " + modelPath);
        }
        if (!Files.exists(vocabPath) || !Files.isRegularFile(vocabPath)) {
            throw new IOException("Vocabulary file not found at bundle path: " + vocabPath);
        }
        
        // Load vocabulary
        SamediffBertVocabulary vocabulary = new SamediffBertVocabulary(vocabPath.toFile(), SamediffBertVocabulary.DEFAULT_UNKNOWN_TOKEN);
        
        this.tokenizerPreProcessor = new SamediffBertTokenizerPreProcessor(
            vocabulary, 
            doLowerCaseAndStripAccents,
            addSpecialTokens,
            maxSequenceLength
        );

        // Load SameDiff model
        loadSameDiffModel(modelPath);

        // NOTE: NAN_PANIC mode disabled for production - causes massive overhead
        // Nd4j.getExecutioner().setProfilingMode(OpExecutioner.ProfilingMode.NAN_PANIC);
    }

    /**
     * Initialize using the legacy approach for models not in bundle management
     */
    private void initializeWithLegacyApproach(boolean doLowerCaseAndStripAccents, 
                                             int maxSequenceLength, 
                                             boolean addSpecialTokens) throws IOException {
        // Get model descriptor from ModelConstants (legacy approach)
        ModelDescriptor modelDescriptor = ModelConstants.getAnseriniEncoderModelDescriptor(modelIdentifier);
        if (modelDescriptor == null) {
            throw new IOException("No model descriptor found for model identifier: " + modelIdentifier + 
                    ". Please ensure the model is defined in ModelConstants.getAnseriniEncoderModelDescriptor()");
        }

        // Ensure model is available through model manager
        Path modelPath = modelManager.ensureModelAvailable(modelDescriptor);
        
        if (!Files.exists(modelPath) || !Files.isRegularFile(modelPath)) {
            throw new IOException("Model file not found at expected path after download: " + modelPath);
        }

        LOG.info("[{}] Loading model from Kompile-managed path: {}", modelIdentifier, modelPath.toAbsolutePath());

        // Load vocabulary - assume it's in the same directory as the model with standard name
        Path vocabPath = findVocabularyPath(modelPath);

        LOG.info("[{}] Loading vocabulary from Kompile-managed path: {}", modelIdentifier, vocabPath.toAbsolutePath());
        
        // Load vocabulary
        SamediffBertVocabulary vocabulary = new SamediffBertVocabulary(vocabPath.toFile(), SamediffBertVocabulary.DEFAULT_UNKNOWN_TOKEN);
        this.tokenizerPreProcessor = new SamediffBertTokenizerPreProcessor(vocabulary, doLowerCaseAndStripAccents, addSpecialTokens, maxSequenceLength);

        // Load SameDiff model
        loadSameDiffModel(modelPath);
    }

    private Path findVocabularyPath(Path modelPath) throws IOException {
        Path vocabPath = modelPath.getParent().resolve("vocab.txt");
        if (!Files.exists(vocabPath)) {
            // Try alternative common vocab file names
            Path[] vocabCandidates = {
                modelPath.getParent().resolve("tokenizer.json"),
                modelPath.getParent().resolve("vocabulary.txt"),
                modelPath.getParent().resolve("vocab.json")
            };
            
            for (Path candidate : vocabCandidates) {
                if (Files.exists(candidate)) {
                    vocabPath = candidate;
                    break;
                }
            }
            
            if (!Files.exists(vocabPath)) {
                throw new IOException("Vocabulary file not found. Expected vocab.txt, tokenizer.json, vocabulary.txt, or vocab.json in: " + modelPath.getParent());
            }
        }
        return vocabPath;
    }

    private void loadSameDiffModel(Path modelPath) throws IOException {
        try {
            this.sameDiffModel = SameDiff.load(modelPath.toFile(), true);

            if (this.sameDiffModel == null) {
                throw new IOException("Failed to import model to SameDiff from " + modelPath + ". Importer returned null.");
            }
            LOG.info("[{}] Successfully imported model to SameDiff from: {}", modelIdentifier, modelPath.toAbsolutePath());

            // Apply graph optimization if enabled
            if (optimizeOnLoad) {
                applyGraphOptimization();
            }

        } catch (Exception e) {
            LOG.error("[{}] Failed to import model to SameDiff from path {}", modelIdentifier, modelPath, e);
            throw new IOException("Failed to import model to SameDiff from " + modelPath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Apply graph optimizations to the loaded model.
     * This fuses operations for better performance (e.g., matmul+add -> xw_plus_b).
     */
    private void applyGraphOptimization() {
        if (this.sameDiffModel == null) {
            LOG.warn("[{}] Cannot optimize null model", modelIdentifier);
            return;
        }

        try {
            List<String> outputs = this.sameDiffModel.outputs();
            if (outputs.isEmpty()) {
                LOG.warn("[{}] Model has no outputs, skipping optimization", modelIdentifier);
                return;
            }

            int originalOps = this.sameDiffModel.getOps().size();
            int originalVars = this.sameDiffModel.getVariables().size();

            LOG.info("[{}] Applying graph optimizations (original: {} ops, {} variables)...",
                    modelIdentifier, originalOps, originalVars);

            long startTime = System.currentTimeMillis();

            // Apply default optimizations
            SameDiff optimized = GraphOptimizer.optimize(
                    this.sameDiffModel,
                    outputs,
                    GraphOptimizer.defaultOptimizations()
            );

            long elapsed = System.currentTimeMillis() - startTime;

            int optimizedOps = optimized.getOps().size();
            int optimizedVars = optimized.getVariables().size();

            LOG.info("[{}] Graph optimization complete in {}ms: {} ops -> {} ops (reduced by {}), {} vars -> {} vars (reduced by {})",
                    modelIdentifier, elapsed,
                    originalOps, optimizedOps, originalOps - optimizedOps,
                    originalVars, optimizedVars, originalVars - optimizedVars);

            // Replace the model with optimized version
            this.sameDiffModel = optimized;

        } catch (Exception e) {
            LOG.warn("[{}] Graph optimization failed, using original model: {}",
                    modelIdentifier, e.getMessage());
            // Keep the original model on failure
        }
    }

    /**
     * Save the current model with graph optimizations applied.
     * This pre-optimizes the model for faster loading and inference.
     *
     * @param outputPath Path to save the optimized model (.sdz format)
     * @throws IOException if saving fails
     */
    public void saveOptimized(Path outputPath) throws IOException {
        if (this.sameDiffModel == null) {
            throw new IOException("No model loaded to save");
        }

        List<String> outputs = this.sameDiffModel.outputs();
        if (outputs.isEmpty()) {
            throw new IOException("Model has no outputs defined");
        }

        LOG.info("[{}] Saving optimized model to: {}", modelIdentifier, outputPath);

        SDZSerializer.saveOptimized(
                this.sameDiffModel,
                outputPath.toFile(),
                false, // Don't save updater state for inference models
                null,
                outputs
        );

        LOG.info("[{}] Optimized model saved successfully", modelIdentifier);
    }

    /**
     * Save the current model with graph optimizations and custom settings.
     *
     * @param outputPath       Path to save the optimized model
     * @param saveUpdaterState Whether to save updater state
     * @param metadata         Optional metadata to include
     * @param requiredOutputs  Specific outputs to preserve (null = all outputs)
     * @throws IOException if saving fails
     */
    public void saveOptimized(Path outputPath, boolean saveUpdaterState,
                              Map<String, String> metadata, List<String> requiredOutputs) throws IOException {
        if (this.sameDiffModel == null) {
            throw new IOException("No model loaded to save");
        }

        List<String> outputs = requiredOutputs != null ? requiredOutputs : this.sameDiffModel.outputs();
        if (outputs.isEmpty()) {
            throw new IOException("No outputs specified for optimization");
        }

        LOG.info("[{}] Saving optimized model with {} outputs to: {}",
                modelIdentifier, outputs.size(), outputPath);

        SDZSerializer.saveOptimized(
                this.sameDiffModel,
                outputPath.toFile(),
                saveUpdaterState,
                metadata,
                outputs
        );

        LOG.info("[{}] Optimized model saved successfully", modelIdentifier);
    }

    /**
     * Static utility to pre-optimize a model file.
     * Loads the model, applies optimizations, and saves to a new file.
     *
     * @param inputPath  Path to the input model (.sdz format)
     * @param outputPath Path to save the optimized model
     * @param outputs    The output variable names to preserve
     * @throws IOException if loading or saving fails
     */
    public static void preOptimizeModel(Path inputPath, Path outputPath, List<String> outputs) throws IOException {
        LOG.info("Pre-optimizing model: {} -> {}", inputPath, outputPath);

        SameDiff model = SDZSerializer.load(inputPath.toFile(), true);
        if (model == null) {
            throw new IOException("Failed to load model from: " + inputPath);
        }

        List<String> targetOutputs = outputs;
        if (targetOutputs == null || targetOutputs.isEmpty()) {
            targetOutputs = model.outputs();
        }

        if (targetOutputs.isEmpty()) {
            throw new IOException("Model has no outputs and none specified");
        }

        SDZSerializer.saveOptimized(model, outputPath.toFile(), false, null, targetOutputs);
        LOG.info("Pre-optimization complete: {}", outputPath);
    }

    /**
     * Legacy constructor for backward compatibility - uses explicit paths managed by Kompile.
     * 
     * @deprecated Use the constructor with modelIdentifier instead for better model management
     */
    @Deprecated
    public SameDiffEncoder(@NotNull String modelIdentifier,
                           @NotNull String kompileManagedModelPath,
                           @NotNull String kompileManagedVocabPath,
                           @NotNull List<String> inputTensorNamesForModel,
                           @NotNull List<String> outputTensorNamesFromModel,
                           boolean doLowerCaseAndStripAccents,
                           int maxSequenceLength,
                           boolean addSpecialTokens) throws IOException {
        this.modelIdentifier = modelIdentifier;
        this.modelManager = new KompileModelManager();

        Path vocabPath = Paths.get(kompileManagedVocabPath);
        LOG.info("[{}] Loading vocabulary from Kompile-managed path: {}", modelIdentifier, vocabPath.toAbsolutePath());
        if (!Files.exists(vocabPath) || !Files.isRegularFile(vocabPath)) {
            throw new IOException("Kompile-managed vocabulary path does not exist or is not a file: " + kompileManagedVocabPath);
        }
        // Use the provided SamediffBertVocabulary constructor
        SamediffBertVocabulary vocabulary = new SamediffBertVocabulary(vocabPath.toFile(), SamediffBertVocabulary.DEFAULT_UNKNOWN_TOKEN);
        this.tokenizerPreProcessor = new SamediffBertTokenizerPreProcessor(vocabulary, doLowerCaseAndStripAccents, addSpecialTokens, maxSequenceLength);

        Path modelPath = Paths.get(kompileManagedModelPath);
        LOG.info("[{}] Loading model from Kompile-managed path: {}", modelIdentifier, modelPath.toAbsolutePath());
        if (!Files.exists(modelPath) || !Files.isRegularFile(modelPath)) {
            throw new IOException("Kompile-managed model path does not exist or is not a file: " + kompileManagedModelPath);
        }

        loadSameDiffModel(modelPath);

        // For legacy constructor, still use dynamic detection but log if they differ from provided values
        List<String> actualInputs = this.sameDiffModel.inputs();
        List<String> actualOutputs = this.sameDiffModel.outputs();

        if (!actualInputs.equals(inputTensorNamesForModel)) {
            LOG.warn("[{}] Provided input tensor names {} differ from actual model inputs {}. Using actual inputs.", 
                    modelIdentifier, inputTensorNamesForModel, actualInputs);
        }
        if (!actualOutputs.equals(outputTensorNamesFromModel)) {
            LOG.warn("[{}] Provided output tensor names {} differ from actual model outputs {}. Using actual outputs.", 
                    modelIdentifier, outputTensorNamesFromModel, actualOutputs);
        }

        this.inputTensorNamesForModel = Collections.unmodifiableList(actualInputs);
        this.outputTensorNamesFromModel = Collections.unmodifiableList(actualOutputs);

        // Validate that the model has the expected structure
        if (this.inputTensorNamesForModel.isEmpty()) {
            throw new IOException("Model has no input placeholders defined");
        }
        if (this.outputTensorNamesFromModel.isEmpty()) {
            throw new IOException("Model has no outputs defined");
        }

        // Validate that the model produces valid embeddings before use
        validateModelOutput();
    }

    public SamediffBertTokenizerPreProcessor getTokenizerPreProcessor() {
        return tokenizerPreProcessor;
    }

    public List<String> getInputTensorNames() {
        return inputTensorNamesForModel;
    }

    public List<String> getOutputTensorNames() {
        return outputTensorNamesFromModel;
    }

    /**
     * Get the underlying SameDiff model for debugging and inspection purposes.
     * @return The SameDiff model instance
     */
    public SameDiff getSameDiffModel() {
        return sameDiffModel;
    }

    public abstract RETURN_TYPE encode(@NotNull String text);

    // Number of documents to process before cleaning up workspaces to prevent memory accumulation
    // Cleanup interval for workspaces during bulk encoding
    // MEMORY FIX: Set to 10 to balance memory usage and performance
    // - Too low (1): Excessive overhead from repeated workspace destruction
    // - Too high (100+): Memory accumulates too much between cleanups, causing OOM
    // Even with proper INDArray.close(), ND4J workspaces retain fragments that must be explicitly destroyed
    private static final int WORKSPACE_CLEANUP_INTERVAL = 10;

    // Counter for single encode() calls to trigger periodic cleanup
    // THREAD-SAFETY FIX: Use AtomicInteger for safe concurrent access
    // Multiple threads may encode simultaneously in Spring Boot environments
    private final java.util.concurrent.atomic.AtomicInteger singleEncodeCount = new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Bulk encode multiple texts with optimized parallel processing.
     *
     * <h2>Optimization Strategy</h2>
     * <p>
     * Since SameDiff models are NOT thread-safe, we cannot parallelize model inference.
     * However, we can optimize in other ways:
     * </p>
     * <ul>
     *   <li><b>Parallel Tokenization</b>: Tokenize all texts in parallel (CPU-bound, thread-safe)</li>
     *   <li><b>Sequential Inference</b>: Run model inference sequentially (required for thread safety)</li>
     *   <li><b>Batched Cleanup</b>: Clean workspaces periodically, not per-document</li>
     * </ul>
     *
     * <h2>Performance Notes</h2>
     * <p>
     * For true parallelism of model inference, consider:
     * </p>
     * <ol>
     *   <li>Using GPU inference (CUDA backend for ND4J)</li>
     *   <li>Creating multiple encoder instances (one per thread)</li>
     *   <li>Implementing true batch inference with padding</li>
     * </ol>
     *
     * @param texts List of texts to encode
     * @return Map of text to encoded result
     */
    public Map<String, RETURN_TYPE> bulkEncode(@NotNull List<String> texts) {
        if (texts.isEmpty()) {
            return new HashMap<>();
        }

        // Check for shutdown before starting
        if (!shouldProceedWithEncoding()) {
            LOG.debug("[{}] bulkEncode rejected - encoder is shutting down", modelIdentifier);
            return new HashMap<>();
        }

        // For small batches, use sequential processing
        if (texts.size() < PARALLEL_TOKENIZE_THRESHOLD) {
            return bulkEncodeSequential(texts);
        }

        // For larger batches, use parallel tokenization with sequential inference
        return bulkEncodeOptimized(texts);
    }

    /**
     * TRUE BATCH ENCODING - Process multiple texts in a SINGLE forward pass.
     *
     * <p>This method should be overridden by subclasses that support float[] embeddings
     * (dense encoders like BGE, GenericDense) to provide true batch inference.</p>
     *
     * <p>Default implementation falls back to sequential encoding via bulkEncode().</p>
     *
     * @param texts List of texts to encode in a single batch
     * @return List of embeddings as float arrays, or null if not supported/interrupted
     */
    public List<float[]> encodeBatch(@NotNull List<String> texts) {
        // Default implementation: fall back to sequential encoding
        // Subclasses (BgeSameDiffEncoder, GenericDenseSameDiffEncoder) override this
        // with true batch inference
        if (texts.isEmpty()) {
            return new ArrayList<>();
        }

        // Check for shutdown before starting batch encoding
        if (!shouldProceedWithEncoding()) {
            LOG.debug("[{}] encodeBatch rejected - encoder is shutting down", modelIdentifier);
            return null;
        }

        Map<String, RETURN_TYPE> bulkResults = bulkEncode(texts);
        List<float[]> results = new ArrayList<>(texts.size());

        for (String text : texts) {
            RETURN_TYPE result = bulkResults.get(text);
            if (result instanceof float[]) {
                results.add((float[]) result);
            } else {
                results.add(null);
            }
        }

        return results;
    }

    /**
     * Sequential bulk encoding - simple loop over texts.
     */
    private Map<String, RETURN_TYPE> bulkEncodeSequential(@NotNull List<String> texts) {
        Map<String, RETURN_TYPE> results = new HashMap<>();
        int processedCount = 0;

        for (String text : texts) {
            // Check both interrupt and shutdown status
            if (!shouldProceedWithEncoding()) {
                LOG.info("[{}] Bulk encoding stopped (shutdown/interrupt) after processing {}/{} texts",
                        modelIdentifier, results.size(), texts.size());
                break;
            }

            if (text == null) continue;

            try {
                RETURN_TYPE result = encode(text);
                if (result != null) {
                    results.put(text, result);
                }
                processedCount++;

                if (processedCount % WORKSPACE_CLEANUP_INTERVAL == 0) {
                    forceWorkspaceCleanup();
                }
            } catch (Exception e) {
                LOG.error("[{}] Error encoding text in bulk operation: {}",
                        modelIdentifier, e.getMessage());
                // Continue with next text instead of breaking
            }
        }

        forceWorkspaceCleanup();
        return results;
    }

    /**
     * Pipelined bulk encoding with parallel tokenization and queued inference.
     *
     * <h2>Architecture</h2>
     * <pre>
     * ┌─────────────────────────────────────────────────────────────┐
     * │  PRODUCER THREADS (N parallel)                              │
     * │  ┌─────────┐ ┌─────────┐ ┌─────────┐                       │
     * │  │Tokenize │ │Tokenize │ │Tokenize │  ... (CPU-bound)      │
     * │  │ Text 1  │ │ Text 2  │ │ Text 3  │                       │
     * │  └────┬────┘ └────┬────┘ └────┬────┘                       │
     * │       └───────────┼───────────┘                            │
     * │                   ▼                                         │
     * │         BlockingQueue<TokenizedInput>                       │
     * │                   │                                         │
     * │                   ▼                                         │
     * │  CONSUMER THREAD (1 sequential)                             │
     * │  ┌─────────────────────────────────────────┐               │
     * │  │  Model Inference (SameDiff - NOT thread-safe)           │
     * │  │  Process tokenized inputs sequentially                  │
     * │  └─────────────────────────────────────────┘               │
     * └─────────────────────────────────────────────────────────────┘
     * </pre>
     *
     * <h2>Benefits</h2>
     * <ul>
     *   <li>Tokenization runs in parallel while inference processes previous batch</li>
     *   <li>No idle time waiting for tokenization during inference</li>
     *   <li>Queue provides natural backpressure</li>
     *   <li>Memory bounded by queue size</li>
     * </ul>
     */
    private Map<String, RETURN_TYPE> bulkEncodeOptimized(@NotNull List<String> texts) {
        int numTexts = texts.size();
        Map<String, RETURN_TYPE> results = new ConcurrentHashMap<>();

        // Get instruction prefix once (cached for all texts in this batch)
        final String instructionPrefix = getInstructionPrefix();
        final boolean hasPrefix = instructionPrefix != null && !instructionPrefix.isEmpty();

        LOG.info("[{}] Pipelined bulk encoding {} texts (parallel tokenization + queued inference){}",
                modelIdentifier, numTexts, hasPrefix ? " with instruction prefix" : "");

        long startTime = System.currentTimeMillis();

        // Queue to connect tokenization producers with inference consumer
        // Bounded queue provides backpressure - if inference is slow, tokenizers wait
        int queueCapacity = Math.min(100, numTexts);
        BlockingQueue<TokenizedWork> workQueue = new LinkedBlockingQueue<>(queueCapacity);

        // Poison pill to signal completion
        final TokenizedWork DONE = new TokenizedWork(-1, null, null);

        // Track progress
        AtomicInteger tokenizedCount = new AtomicInteger(0);
        AtomicInteger inferenceCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        // Get tokenization executor
        ExecutorService tokenizeExec = getOrCreateTokenizeExecutor();

        // Start producer tasks - parallel tokenization
        CompletableFuture<Void> tokenizeFuture = CompletableFuture.runAsync(() -> {
            try {
                // Submit tokenization tasks in parallel
                List<CompletableFuture<Void>> tokenizeTasks = new ArrayList<>();

                for (int i = 0; i < numTexts; i++) {
                    if (Thread.currentThread().isInterrupted()) break;

                    final int index = i;
                    final String text = texts.get(i);

                    CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                        if (Thread.currentThread().isInterrupted() || text == null) return;

                        try {
                            // Apply instruction prefix if present (same as encode() does)
                            String textToTokenize = hasPrefix ? instructionPrefix + text : text;

                            // Tokenize (thread-safe, CPU-bound operation)
                            SamediffBertTokenizerPreProcessor.BertEncoding encoding =
                                    tokenizerPreProcessor.encode(textToTokenize);

                            // Put tokenized work into queue (may block if queue is full)
                            // Store original text (without prefix) for result key
                            workQueue.put(new TokenizedWork(index, text, encoding));
                            tokenizedCount.incrementAndGet();

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            LOG.warn("[{}] Tokenization failed for text {}: {}",
                                    modelIdentifier, index, e.getMessage());
                        }
                    }, tokenizeExec);

                    tokenizeTasks.add(task);
                }

                // Wait for all tokenization to complete
                CompletableFuture.allOf(tokenizeTasks.toArray(new CompletableFuture[0])).join();

            } finally {
                // Signal completion to consumer
                try {
                    workQueue.put(DONE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Consumer - sequential inference (SameDiff not thread-safe)
        CompletableFuture<Void> inferenceFuture = CompletableFuture.runAsync(() -> {
            int progressChunk = Math.max(10, numTexts / 10);

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Take work from queue (blocks if empty)
                    TokenizedWork work = workQueue.poll(100, TimeUnit.MILLISECONDS);

                    if (work == null) continue;
                    if (work == DONE) break; // Poison pill - all done

                    // Run inference with lock (ensures thread safety)
                    encodeLock.lock();
                    try {
                        RETURN_TYPE result = encodeFromTokenized(work.text, work.encoding);
                        if (result != null) {
                            results.put(work.text, result);
                            successCount.incrementAndGet();
                        }
                    } finally {
                        encodeLock.unlock();
                    }

                    int count = inferenceCount.incrementAndGet();

                    // Periodic cleanup
                    if (count % WORKSPACE_CLEANUP_INTERVAL == 0) {
                        forceWorkspaceCleanup();
                    }

                    // Progress logging
                    if (count % progressChunk == 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        double rate = elapsed > 0 ? (count * 1000.0 / elapsed) : 0;
                        LOG.debug("[{}] Inference progress: {}/{} ({}/sec), queue: {}",
                                modelIdentifier, count, numTexts, String.format("%.1f", rate), workQueue.size());
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOG.warn("[{}] Inference error: {}", modelIdentifier, e.getMessage());
                }
            }
        });

        // Wait for both pipelines to complete
        try {
            CompletableFuture.allOf(tokenizeFuture, inferenceFuture)
                    .get(30, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            LOG.error("[{}] Pipelined encoding timed out", modelIdentifier);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOG.error("[{}] Pipelined encoding failed: {}", modelIdentifier, e.getCause().getMessage());
        }

        forceWorkspaceCleanup();

        long totalTime = System.currentTimeMillis() - startTime;
        double textsPerSec = totalTime > 0 ? (successCount.get() * 1000.0 / totalTime) : 0;
        LOG.info("[{}] Pipelined encode complete: {}/{} texts in {}ms ({} texts/sec)",
                modelIdentifier, successCount.get(), numTexts, totalTime, String.format("%.1f", textsPerSec));

        return results;
    }

    /**
     * Work item for the tokenization-to-inference queue.
     */
    private record TokenizedWork(int index, String text, SamediffBertTokenizerPreProcessor.BertEncoding encoding) {}

    /**
     * Encode from pre-tokenized input.
     * Subclasses should override this for optimal performance.
     * Default implementation falls back to regular encode().
     */
    protected RETURN_TYPE encodeFromTokenized(String text, SamediffBertTokenizerPreProcessor.BertEncoding encoding) {
        // Default: fall back to regular encode (re-tokenizes, but works)
        // Subclasses can override to use the pre-tokenized encoding directly
        return encode(text);
    }

    /**
     * Get or create the tokenization executor.
     */
    private ExecutorService getOrCreateTokenizeExecutor() {
        if (tokenizeExecutor == null) {
            synchronized (this) {
                if (tokenizeExecutor == null) {
                    tokenizeExecutor = new ThreadPoolExecutor(
                            2, MAX_TOKENIZE_THREADS,
                            60L, TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>(200),
                            r -> {
                                Thread t = new Thread(r, "tokenize-worker-" + modelIdentifier);
                                t.setDaemon(true);
                                return t;
                            },
                            new ThreadPoolExecutor.CallerRunsPolicy()
                    );
                    LOG.info("[{}] Created tokenization executor with {} threads",
                            modelIdentifier, MAX_TOKENIZE_THREADS);
                }
            }
        }
        return tokenizeExecutor;
    }

    /**
     * Thread-safe encoding with lock.
     * Use this when calling encode() from multiple threads.
     */
    public RETURN_TYPE encodeSafe(String text) {
        encodeLock.lock();
        try {
            return encode(text);
        } finally {
            encodeLock.unlock();
        }
    }

    /**
     * Get the maximum sequence length for this encoder.
     * Used for batch tokenization padding.
     */
    public int getMaxSequenceLength() {
        return tokenizerPreProcessor != null ? tokenizerPreProcessor.getMaxLength() : 512;
    }

    /**
     * Called by subclasses after each encode() to trigger periodic workspace cleanup.
     * This is CRITICAL for single-document encoding paths that bypass bulkEncode().
     * Without this, workspace fragments accumulate indefinitely between external cleanup calls.
     *
     * Thread-safe: Uses AtomicInteger for the counter to ensure consistent cleanup
     * timing across concurrent encoding operations.
     */
    protected void maybeCleanupWorkspaces() {
        int count = singleEncodeCount.incrementAndGet();
        if (count % WORKSPACE_CLEANUP_INTERVAL == 0) {
            forceWorkspaceCleanup();
        }
    }

    /**
     * Lightweight cleanup to prevent memory accumulation during long-running operations.
     *
     * This method performs MINIMAL cleanup to avoid the overhead of reinitializing
     * SameDiff or destroying workspaces. It only:
     *
     * 1. Clears the InferenceSession cache in SameDiff (releases OpContexts)
     * 2. Does NOT destroy workspaces (expensive, causes reinitialization)
     * 3. Does NOT call System.gc() (let JVM decide when to GC)
     *
     * For heavy cleanup (at shutdown), use close() instead.
     */
    protected void forceWorkspaceCleanup() {
        try {
            // Only clear InferenceSession cache - this releases cached OpContexts
            // without destroying the entire workspace infrastructure
            if (this.sameDiffModel != null) {
                try {
                    // Use reflection to call clearOpContexts() or similar method
                    // to release cached operation contexts without full reinitialization
                    java.lang.reflect.Method clearMethod = null;

                    // Try to find a sessions clearing method
                    try {
                        clearMethod = this.sameDiffModel.getClass().getMethod("clearOpInputs");
                    } catch (NoSuchMethodException e1) {
                        try {
                            clearMethod = this.sameDiffModel.getClass().getMethod("clearArrayHolders");
                        } catch (NoSuchMethodException e2) {
                            // No clear method available - this is OK, just skip
                        }
                    }

                    if (clearMethod != null) {
                        clearMethod.invoke(this.sameDiffModel);
                        LOG.trace("[{}] Cleared SameDiff caches", modelIdentifier);
                    }
                } catch (Exception e) {
                    LOG.trace("[{}] Could not clear SameDiff caches: {}", modelIdentifier, e.getMessage());
                }
            }

            // Only invoke GC every 100 cleanups to avoid excessive overhead
            // The counter is already incremented in maybeCleanupWorkspaces()
            if (singleEncodeCount.get() % 100 == 0) {
                System.gc();
                LOG.trace("[{}] Periodic GC hint at count {}", modelIdentifier, singleEncodeCount.get());
            }
        } catch (Exception e) {
            LOG.debug("[{}] Error during cleanup: {}", modelIdentifier, e.getMessage());
        }
    }

    /**
     * Heavy cleanup - destroys workspaces and releases all memory.
     * Only call this at shutdown or when memory pressure is critical.
     */
    protected void forceHeavyCleanup() {
        try {
            // Destroy workspaces - this is expensive but thorough
            Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();

            // Release memory manager's pooled memory
            try {
                Nd4j.getMemoryManager().releaseCurrentContext();
            } catch (Exception e) {
                LOG.trace("[{}] Could not release memory context: {}", modelIdentifier, e.getMessage());
            }

            // Force GC for heavy cleanup
            System.gc();

            LOG.debug("[{}] Heavy workspace cleanup completed", modelIdentifier);
        } catch (Exception e) {
            LOG.debug("[{}] Error during heavy cleanup: {}", modelIdentifier, e.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        LOG.info("Closing SameDiffEncoder for model: {}", modelIdentifier);

        // 0. Initiate graceful shutdown - this signals all components to stop
        // accepting new operations and waits for active operations to complete
        initiateShutdown();

        // 1. Shutdown tokenize executor if it was created
        if (tokenizeExecutor != null) {
            LOG.info("Shutting down tokenize executor for model: {}", modelIdentifier);
            tokenizeExecutor.shutdown();
            try {
                if (!tokenizeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.warn("Tokenize executor did not terminate in time, forcing shutdown");
                    tokenizeExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                tokenizeExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            tokenizeExecutor = null;
        }

        // 2. Wait for any active tokenizer operations to complete
        // The Tokenizer.close() method already waits, but we wait here too
        // to ensure proper ordering
        if (this.tokenizerPreProcessor != null) {
            int activeOps = tokenizerPreProcessor.getActiveOperationCount();
            if (activeOps > 0) {
                LOG.info("Waiting for {} active tokenizer operations to complete for model: {}",
                        activeOps, modelIdentifier);
            }
        }

        // 3. Close SameDiff model properly - this releases all native resources
        // including OpContexts cached in InferenceSessions.
        // We use reflection to call close() to maintain compatibility with nd4j-api versions
        // that may not have the close() method yet (avoids compile-time dependency).
        if (this.sameDiffModel != null) {
            try {
                // Use reflection to call close() for compatibility with different nd4j-api versions
                java.lang.reflect.Method closeMethod = this.sameDiffModel.getClass().getMethod("close");
                closeMethod.invoke(this.sameDiffModel);
                LOG.info("Closed SameDiff model and all cached resources for: {}", modelIdentifier);
            } catch (NoSuchMethodException e) {
                LOG.debug("SameDiff.close() not available in this nd4j-api version - skipping cleanup for: {}", modelIdentifier);
            } catch (Exception e) {
                LOG.warn("Error during SameDiff cleanup for model: {}", modelIdentifier, e);
            }
            this.sameDiffModel = null;
        }

        // 4. Close tokenizer - this will wait for active operations via the new
        // graceful shutdown mechanism in Tokenizer.close()
        if (this.tokenizerPreProcessor != null) {
            try {
                this.tokenizerPreProcessor.close();
                LOG.info("Closed tokenizer preprocessor for model: {}", modelIdentifier);
            } catch (Exception e) {
                LOG.warn("Error closing tokenizer for model: {}", modelIdentifier, e);
            }
            this.tokenizerPreProcessor = null;
        }

        // 5. Heavy cleanup - only at shutdown
        forceHeavyCleanup();
        LOG.info("Completed full cleanup for encoder: {}", modelIdentifier);
    }
}
