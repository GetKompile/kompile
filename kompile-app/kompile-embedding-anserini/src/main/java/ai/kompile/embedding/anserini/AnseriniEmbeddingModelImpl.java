package ai.kompile.embedding.anserini;

import ai.kompile.core.embeddings.EmbeddingModel;
import io.anserini.encoder.samediff.ArcticEmbedSameDiffEncoder;
import io.anserini.encoder.samediff.BgeSameDiffEncoder;
import io.anserini.encoder.samediff.CosDprDistilSameDiffEncoder;
import io.anserini.encoder.samediff.GenericDenseSameDiffEncoder;
import io.anserini.encoder.samediff.SameDiffEncoder;
import jakarta.annotation.PreDestroy;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service("anseriniEmbeddingModelImpl")
@ConditionalOnProperty(value = "kompile.embedding.anserini.enabled", havingValue = "true", matchIfMissing = true)
@org.springframework.context.annotation.Lazy  // Defer initialization until first use - allows UI to load faster
public class AnseriniEmbeddingModelImpl implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(AnseriniEmbeddingModelImpl.class);

    private static final String DEFAULT_MODEL_IDENTIFIER = "bge-base-en-v1.5";

    // NOTE: Parallel embedding is NOT used for OpenMP-based transformers.
    // OpenMP already parallelizes matrix operations across all CPU cores.
    // Using multiple Java threads would only cause contention and DAG rebuild overhead.
    // All batching is now handled directly via encoder.encodeBatch() which does
    // TRUE batch inference (single forward pass for all texts).

    private final SameDiffEncoder<float[]> encoder;
    private final String modelIdentifier;
    private final int embeddingDimensions;
    private final AnseriniEncoderFactory.EncoderType encoderType;

    /** No-arg for Spring AOT */
    public AnseriniEmbeddingModelImpl() {
        this(DEFAULT_MODEL_IDENTIFIER);
    }

    /** Main constructor: model-identifier injection */
    @Autowired
    public AnseriniEmbeddingModelImpl(
            @Value("${kompile.embedding.anserini.model-identifier:" + DEFAULT_MODEL_IDENTIFIER + "}")
            String modelIdentifier) {

        this.modelIdentifier = (modelIdentifier != null && !modelIdentifier.isBlank())
                ? modelIdentifier : DEFAULT_MODEL_IDENTIFIER;

        this.encoderType = AnseriniEncoderFactory.getEncoderTypeFromModelId(this.modelIdentifier);

        log.info("Initializing AnseriniEmbeddingModel with model: {} (type: {})",
                this.modelIdentifier, encoderType);
        logModelInfo();

        try {
            // pick the correct factory overload
            if (AnseriniEncoderFactory.usesAutoModelManagement(this.modelIdentifier)) {
                this.encoder = AnseriniEncoderFactory.createEncoder(this.modelIdentifier);
            } else {
                this.encoder = AnseriniEncoderFactory.createEncoder(encoderType, this.modelIdentifier);
            }


            float[] testEmbedding = this.encoder.encode("test");
            this.embeddingDimensions = testEmbedding != null ? testEmbedding.length : -1;

            if (this.embeddingDimensions <= 0) {
                throw new IOException("Could not determine embedding dimensions for model: "
                        + this.modelIdentifier);
            }

            log.info("Successfully initialized. Model: {}, Type: {}, Dimensions: {}",
                    this.modelIdentifier, encoderType, embeddingDimensions);

        } catch (IOException e) {
            log.error("Failed to initialize AnseriniEmbeddingModel with model: {}",
                    this.modelIdentifier, e);
            throw new RuntimeException("Could not initialize Anserini embedding model: " + e.getMessage(), e);
        }
    }

    /**
     * Advanced, explicit-path constructor.
     * Directly instantiates the right SameDiffEncoder subtype based on encoderType.
     */
    public AnseriniEmbeddingModelImpl(String modelIdentifier,
                                      String modelPath,
                                      String vocabPath,
                                      List<String> inputTensorNames,
                                      String outputTensorName,
                                      boolean doLowerCase,
                                      int maxSequenceLength,
                                      boolean addSpecialTokens,
                                      boolean normalizeOutput) throws IOException {

        this.modelIdentifier = modelIdentifier;
        this.encoderType     = AnseriniEncoderFactory.getEncoderTypeFromModelId(modelIdentifier);

        log.info("Advanced init: model={} type={}", modelIdentifier, encoderType);

        // pick correct explicit-path constructor
        switch (encoderType) {
            case BGE:
                // instruction=null, normalizeOutput on first arg, then lowercase, seqLen, specialTokens
                this.encoder = new BgeSameDiffEncoder(
                        modelIdentifier,
                        modelPath,
                        vocabPath,
                        /*instruction=*/null,
                        normalizeOutput,
                        doLowerCase,
                        maxSequenceLength,
                        addSpecialTokens
                );
                break;

            case ARCTIC_EMBED:
                this.encoder = new ArcticEmbedSameDiffEncoder(
                        modelIdentifier,
                        modelPath,
                        vocabPath,
                        doLowerCase,
                        maxSequenceLength,
                        addSpecialTokens
                );
                break;

            case COS_DPR_DISTIL:
                this.encoder = new CosDprDistilSameDiffEncoder(
                        modelIdentifier,
                        modelPath,
                        vocabPath,
                        doLowerCase,
                        maxSequenceLength,
                        addSpecialTokens
                );
                break;

            case SPLADE_PP_ED:
            case SPLADE_PP_SD:
            case GENERIC_DENSE:
            default:
                this.encoder = new GenericDenseSameDiffEncoder(
                        modelIdentifier,
                        modelPath,
                        vocabPath,
                        inputTensorNames,
                        outputTensorName,
                        doLowerCase,
                        maxSequenceLength,
                        addSpecialTokens,
                        normalizeOutput
                );
                break;
        }

        // determine dimensions
        float[] testEmbedding = this.encoder.encode("test");
        this.embeddingDimensions = testEmbedding != null ? testEmbedding.length : -1;
        if (this.embeddingDimensions <= 0) {
            throw new IOException("Could not determine embedding dimensions for model: " + modelIdentifier);
        }
        log.info("Advanced init complete. Dimensions: {}", embeddingDimensions);
    }

    @Override
    public INDArray embed(String text) {
        if (text == null || text.isBlank()) return Nd4j.empty(DataType.FLOAT);

        // Check for interrupt or shutdown before encoding
        if (Thread.currentThread().isInterrupted() || encoder.isShuttingDown()) {
            log.debug("Embed operation rejected - interrupt or shutdown in progress");
            return Nd4j.empty(DataType.FLOAT);
        }

        try {
            float[] emb = encoder.encode(text);
            if (emb == null || emb.length == 0) {
                log.debug("Encoder returned null or empty result, likely due to interrupt or shutdown");
                return Nd4j.empty(DataType.FLOAT);
            }
            return Nd4j.create(emb);
        } catch (IllegalStateException e) {
            // Tokenizer shutdown - expected during graceful shutdown
            log.debug("Embed operation rejected - encoder shutting down: {}", e.getMessage());
            return Nd4j.empty(DataType.FLOAT);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || encoder.isShuttingDown()) {
                log.debug("Embed operation interrupted/shutdown during encoding");
                return Nd4j.empty(DataType.FLOAT);
            }
            log.error("Error embedding text", e);
            return Nd4j.empty(DataType.FLOAT);
        }
    }

    @Override
    public INDArray embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return Nd4j.empty(DataType.FLOAT);

        // Check for interrupt or shutdown before bulk encoding
        if (Thread.currentThread().isInterrupted() || encoder.isShuttingDown()) {
            log.debug("Bulk embed operation rejected - interrupt or shutdown in progress");
            return Nd4j.empty(DataType.FLOAT);
        }

        // Use true batch encoding via encodeBatch, then assemble into matrix
        try {
            List<float[]> batchResults = encoder.encodeBatch(texts);
            if (batchResults == null || batchResults.isEmpty()) {
                return Nd4j.empty(DataType.FLOAT);
            }
            return assembleEmbeddingMatrixFromList(texts, batchResults);
        } catch (IllegalStateException e) {
            // Tokenizer shutdown - expected during graceful shutdown
            log.debug("Bulk embed operation rejected - encoder shutting down: {}", e.getMessage());
            return Nd4j.empty(DataType.FLOAT);
        }
    }

    /**
     * DIRECT batch embedding - bypasses INDArray creation entirely.
     * This is the most efficient path for pipeline embedding.
     *
     * <p>Calls encoder.encodeBatch() directly which performs TRUE batch inference
     * (single forward pass for all texts) in BgeSameDiffEncoder.</p>
     *
     * <p><b>IMPORTANT:</b> This method returns an empty list (not null) on interrupt/shutdown
     * to prevent downstream null pointer exceptions and pipeline inconsistency.</p>
     */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            log.debug("embedBatch called with null/empty texts, returning empty list");
            return List.of();
        }

        // Check for interrupt or shutdown before batch encoding
        if (Thread.currentThread().isInterrupted() || encoder.isShuttingDown()) {
            log.info("embedBatch rejected - interrupt or shutdown in progress (texts={})", texts.size());
            // Return empty list instead of null to prevent NPE in pipeline
            return List.of();
        }

        try {
            // Calculate character and approximate token stats for the batch
            int totalChars = 0;
            int minChars = Integer.MAX_VALUE;
            int maxChars = 0;
            for (String text : texts) {
                int len = text.length();
                totalChars += len;
                if (len < minChars) minChars = len;
                if (len > maxChars) maxChars = len;
            }
            double avgChars = texts.isEmpty() ? 0 : (double) totalChars / texts.size();

            log.info("EMBED_BATCH_START: batch_size={}, total_chars={}, avg_chars={:.1f}, min_chars={}, max_chars={}, encoder={}",
                    texts.size(), totalChars, avgChars, minChars == Integer.MAX_VALUE ? 0 : minChars, maxChars,
                    encoder.getClass().getSimpleName());

            // Direct call to encoder's batch method - no INDArray overhead
            long start = System.currentTimeMillis();
            List<float[]> result = encoder.encodeBatch(texts);
            long elapsed = System.currentTimeMillis() - start;

            if (result == null) {
                log.warn("EMBED_BATCH_FAIL: encoder.encodeBatch returned null for {} texts after {}ms",
                        texts.size(), elapsed);
                return List.of();
            }

            // Calculate throughput metrics
            double textsPerSec = elapsed > 0 ? (texts.size() * 1000.0 / elapsed) : 0;
            double charsPerSec = elapsed > 0 ? (totalChars * 1000.0 / elapsed) : 0;
            double msPerText = texts.size() > 0 ? (double) elapsed / texts.size() : 0;

            log.info("EMBED_BATCH_DONE: batch_size={}, returned={}, elapsed={}ms, {:.2f}ms/text, {:.1f} texts/sec, {:.0f} chars/sec",
                    texts.size(), result.size(), elapsed, msPerText, textsPerSec, charsPerSec);

            return result;
        } catch (IllegalStateException e) {
            // Tokenizer shutdown - expected during graceful shutdown
            log.info("embedBatch rejected - encoder shutting down: {}", e.getMessage());
            // Return empty list instead of null to prevent NPE in pipeline
            return List.of();
        } catch (Exception e) {
            // Catch any other exception to prevent pipeline crash
            log.error("embedBatch failed for {} texts: {}", texts.size(), e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Assembles the embedding matrix from a list of embeddings (from true batch encoding).
     */
    private INDArray assembleEmbeddingMatrixFromList(List<String> texts, List<float[]> embeddings) {
        int dims = dimensions();
        int numTexts = texts.size();

        // Pre-allocate the output buffer
        float[] flatBuffer = new float[numTexts * dims];
        int validRows = 0;

        for (int i = 0; i < Math.min(numTexts, embeddings.size()); i++) {
            float[] emb = embeddings.get(i);
            if (emb != null && emb.length > 0) {
                int copyLen = Math.min(emb.length, dims);
                System.arraycopy(emb, 0, flatBuffer, i * dims, copyLen);
                validRows++;
            }
        }

        if (validRows == 0) {
            return Nd4j.empty(DataType.FLOAT);
        }

        return Nd4j.create(flatBuffer, new int[]{numTexts, dims}, 'c');
    }

    /**
     * Assembles the embedding matrix from bulk encode results.
     * @deprecated Use assembleEmbeddingMatrixFromList instead
     */
    @Deprecated
    private INDArray assembleEmbeddingMatrix(List<String> texts, Map<String, float[]> bulk) {
        int dims = dimensions();
        int numTexts = texts.size();

        float[] flatBuffer = new float[numTexts * dims];
        int validRows = 0;

        for (int i = 0; i < numTexts; i++) {
            float[] result = bulk.get(texts.get(i));
            if (result != null && result.length == dims) {
                System.arraycopy(result, 0, flatBuffer, i * dims, dims);
                validRows++;
            } else if (result != null && result.length > 0) {
                int copyLen = Math.min(result.length, dims);
                System.arraycopy(result, 0, flatBuffer, i * dims, copyLen);
                validRows++;
            }
        }

        if (validRows == 0) {
            log.warn("No valid embeddings generated from {} texts", numTexts);
            return Nd4j.empty(DataType.FLOAT);
        }

        return Nd4j.create(flatBuffer, new int[]{numTexts, dims}, 'c');
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return Nd4j.empty(DataType.FLOAT);
        var contents = documents.stream()
                .map(Document::getText)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toList());
        return embed(contents);
    }

    @Override
    public int dimensions() {
        return embeddingDimensions;
    }

    // ========== DYNAMIC BATCH SIZE CONFIGURATION ==========
    // CPU inference is memory-bandwidth limited, not compute limited.
    // Transformer attention is O(n²) in sequence length.
    // For 512-token sequences on CPU, batch sizes should be VERY small.
    //
    // Base batch sizes for 512-token sequences:
    private static final int BASE_OPTIMAL_BATCH_512 = 4;   // Very conservative for CPU
    private static final int BASE_MAX_BATCH_512 = 8;       // Never exceed this for 512 tokens
    private static final int REFERENCE_SEQ_LENGTH = 512;

    /**
     * Returns optimal batch size for embedding operations.
     *
     * <p><b>IMPORTANT:</b> The actual batch size used depends on sequence length.
     * The encoder uses dynamic batch sizing based on the maximum sequence length
     * in each batch. This method returns a conservative default for 512-token sequences.</p>
     *
     * <p>For different sequence lengths (approximate):
     * <ul>
     *   <li>512 tokens: ~4 texts/batch</li>
     *   <li>256 tokens: ~16 texts/batch</li>
     *   <li>128 tokens: ~64 texts/batch</li>
     * </ul>
     *
     * @return Optimal batch size for 512-token sequences (conservative default)
     */
    @Override
    public int getOptimalBatchSize() {
        // Return very conservative default for CPU inference with long sequences
        return BASE_OPTIMAL_BATCH_512;
    }

    /**
     * Returns maximum batch size for embedding operations.
     *
     * <p>Like {@link #getOptimalBatchSize()}, the actual maximum depends on
     * sequence length. This returns a conservative default.</p>
     *
     * @return Maximum batch size for 512-token sequences
     */
    @Override
    public int getMaxBatchSize() {
        // Return conservative default for long sequences on CPU
        return BASE_MAX_BATCH_512;
    }

    /**
     * Calculate optimal batch size for a specific sequence length.
     *
     * <p>Use this method when you know the approximate sequence length
     * of your texts to get a more accurate batch size recommendation.</p>
     *
     * <p>Formula: batch = base_batch × (512/seqLen)² × memoryScale</p>
     *
     * @param maxSeqLength Maximum sequence length expected
     * @return Optimal batch size for that sequence length
     */
    public int getOptimalBatchSizeForSeqLength(int maxSeqLength) {
        return calculateBatchSizeForSeqLength(maxSeqLength, BASE_OPTIMAL_BATCH_512);
    }

    /**
     * Calculate maximum batch size for a specific sequence length.
     *
     * @param maxSeqLength Maximum sequence length expected
     * @return Maximum batch size for that sequence length
     */
    public int getMaxBatchSizeForSeqLength(int maxSeqLength) {
        return calculateBatchSizeForSeqLength(maxSeqLength, BASE_MAX_BATCH_512);
    }

    /**
     * Calculate batch size based on sequence length.
     * Scales inversely with sequence length squared (attention complexity).
     */
    private int calculateBatchSizeForSeqLength(int maxSeqLength, int baseBatch) {
        if (maxSeqLength <= 0) {
            maxSeqLength = REFERENCE_SEQ_LENGTH;
        }
        // Scale inversely with sequence length squared
        double ratio = (double) REFERENCE_SEQ_LENGTH / maxSeqLength;
        double scaleFactor = ratio * ratio;
        int batch = (int) Math.round(baseBatch * scaleFactor);
        return Math.max(1, Math.min(batch, 128)); // Clamp to [1, 128]
    }

    public String getModelIdentifier() { return modelIdentifier; }
    public AnseriniEncoderFactory.EncoderType getEncoderType() { return encoderType; }
    public String getModelType() { return AnseriniEncoderFactory.getModelType(modelIdentifier); }
    public Integer getEmbeddingDimension() { return AnseriniEncoderFactory.getEmbeddingDimension(modelIdentifier); }
    public boolean usesAutoModelManagement() { return AnseriniEncoderFactory.usesAutoModelManagement(modelIdentifier); }
    public String getModelInfo() { return AnseriniEncoderFactory.getModelInfo(modelIdentifier); }

    /**
     * Get the underlying SameDiffEncoder for debugging and inspection purposes.
     * @return The SameDiffEncoder instance
     */
    public SameDiffEncoder<float[]> getEncoder() {
        return encoder;
    }

    /**
     * Gets information about the current batch being processed by the encoder.
     * This provides visibility into actual tensor shapes during inference.
     *
     * @return BatchInfo with current batch details, or BatchInfo.empty() if no batch is processing
     */
    @Override
    public BatchInfo getCurrentBatchInfo() {
        if (encoder == null) {
            return BatchInfo.empty();
        }
        SameDiffEncoder.BatchInfo encoderInfo = encoder.getCurrentBatchInfo();
        if (encoderInfo == null || encoderInfo.numChunks() == 0) {
            return BatchInfo.empty();
        }
        // Convert from encoder's BatchInfo to EmbeddingModel's BatchInfo
        return new BatchInfo(
                encoderInfo.numChunks(),
                encoderInfo.maxSeqLength(),
                encoderInfo.embeddingDim(),
                encoderInfo.totalTokens(),
                encoderInfo.inputShape(),
                encoderInfo.outputShape(),
                encoderInfo.step(),
                encoderInfo.stepStartTimeMs()
        );
    }

    /**
     * Initiates graceful shutdown of this embedding model.
     * New encoding operations will be rejected, but existing operations
     * will be allowed to complete.
     * <p>
     * This is useful for coordinating shutdown across multiple components.
     */
    public void initiateShutdown() {
        log.info("Initiating graceful shutdown for model: {}", modelIdentifier);
        encoder.initiateShutdown();
    }

    /**
     * Check if this embedding model is currently shutting down.
     * @return true if shutdown has been initiated
     */
    public boolean isShuttingDown() {
        return encoder.isShuttingDown();
    }

    /**
     * Closes this embedding model and releases all native resources.
     * Implements AutoCloseable.close() for proper resource management.
     * <p>
     * This method performs graceful shutdown:
     * <ol>
     *   <li>Initiates shutdown (rejects new operations)</li>
     *   <li>Waits for active operations to complete</li>
     *   <li>Closes the encoder (frees native resources)</li>
     *   <li>Cleans up ND4J resources</li>
     * </ol>
     */
    @Override
    public void close() throws Exception {
        cleanup();
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up encoder for model: {}", modelIdentifier);

        // First, initiate graceful shutdown - this signals the encoder and tokenizer
        // to stop accepting new operations and waits for active operations to complete
        try {
            initiateShutdown();
        } catch (Exception e) {
            log.warn("Error initiating shutdown for model: {}", modelIdentifier, e);
        }

        // Now close the encoder - this will wait for active operations via the
        // graceful shutdown mechanism in Tokenizer.close()
        try {
            encoder.close();
            log.info("Encoder closed successfully for model: {}", modelIdentifier);
        } catch (IOException e) {
            log.warn("Error closing encoder", e);
        }

        // Clean up ND4J native resources
        try {
            log.info("Shutting down ND4J native resources");
            Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
            Nd4j.getMemoryManager().releaseCurrentContext();
            log.info("ND4J native resources shutdown complete");
        } catch (Exception e) {
            log.warn("Error during ND4J cleanup", e);
        }
    }

    private void logModelInfo() {
        log.info("Model Info: {}", getModelInfo());
        log.info("Auto-Managed: {}", usesAutoModelManagement());
    }
}
