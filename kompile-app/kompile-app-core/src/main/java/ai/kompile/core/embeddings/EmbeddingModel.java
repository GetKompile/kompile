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

package ai.kompile.core.embeddings;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Interface for a component that generates vector embeddings for given texts.
 * Implements AutoCloseable to ensure native resources can be properly released.
 */
public interface EmbeddingModel extends AutoCloseable {

    /**
     * Generates a single vector embedding for a given text.
     *
     * @param text The text to embed.
     * @return A list of floats representing the vector embedding.
     */
    INDArray embed(String text);

    /**
     * Generates vector embeddings for a list of texts.
     *
     * @param texts The list of texts to embed.
     * @return A list of vector embeddings, where each embedding is a list of floats.
     */
    INDArray embed(List<String> texts);

    /**
     * Generates embeddings for a list of Spring AI Document objects.
     * This is useful if you want to embed documents directly.
     * The implementation would extract the content from the documents.
     *
     * @param documents The list of Spring AI Document objects.
     * @return A list of vector embeddings.
     */
    INDArray embedDocuments(List<Document> documents);

    /**
     * Generates embeddings for a batch of texts and returns individual float arrays.
     * This is optimized for pipelines that need to associate embeddings with specific documents.
     *
     * <p>The default implementation extracts rows from the batch matrix. Implementations
     * may override this for more efficient batch processing.</p>
     *
     * @param texts The list of texts to embed.
     * @return A list of float arrays, one per input text. Empty array for failed embeddings.
     */
    default List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        INDArray matrix = embed(texts);
        if (matrix == null || matrix.isEmpty()) {
            // Return empty arrays for each text
            List<float[]> results = new java.util.ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                results.add(new float[0]);
            }
            return results;
        }

        int numRows = (int) matrix.rows();
        int numCols = (int) matrix.columns();
        List<float[]> results = new java.util.ArrayList<>(numRows);

        try {
            // Fast path: contiguous row-major data - single bulk read + arraycopy
            if (matrix.ordering() == 'c' && !matrix.isView()
                    && matrix.stride(0) == numCols && matrix.stride(1) == 1) {
                float[] flat = matrix.data().getFloatsAt(matrix.offset(), numRows * numCols);
                for (int i = 0; i < numRows; i++) {
                    float[] embedding = new float[numCols];
                    System.arraycopy(flat, i * numCols, embedding, 0, numCols);
                    results.add(embedding);
                }
            } else {
                // General path: direct element access avoids intermediate INDArray allocations
                for (int i = 0; i < numRows; i++) {
                    float[] embedding = new float[numCols];
                    for (int j = 0; j < numCols; j++) {
                        embedding[j] = matrix.getFloat(i, j);
                    }
                    results.add(embedding);
                }
            }
        } finally {
            // Clean up original matrix
            if (!matrix.wasClosed()) {
                try { matrix.close(); } catch (Exception ignored) {}
            }
        }

        return results;
    }

    /**
     * Returns the optimal batch size for this embedding model.
     * Models can override this based on their memory requirements and GPU capabilities.
     *
     * @return The recommended batch size for optimal throughput
     */
    default int getOptimalBatchSize() {
        return 32; // Default conservative batch size
    }

    /**
     * Returns the maximum batch size this model can handle.
     * Beyond this size, the model may run out of memory or become inefficient.
     *
     * @return The maximum safe batch size
     */
    default int getMaxBatchSize() {
        return 128; // Default maximum
    }

    /**
     * Gets the dimensionality of the embeddings produced by this model.
     * @return The dimension of the vectors.
     */
    int dimensions();

    /**
     * Gets the name/identifier of this embedding model.
     * Default implementation returns the simple class name.
     * @return The model name.
     */
    default String getModelName() {
        return getClass().getSimpleName();
    }

    /**
     * Gets the model identifier (e.g., "bge-base-en-v1.5").
     * Default implementation returns the model name.
     * @return The model identifier.
     */
    default String getModelIdentifier() {
        return getModelName();
    }

    /**
     * Information about the current batch being processed.
     * Provides visibility into the actual tensor shapes AND timing during inference.
     *
     * This is THE authoritative source for batch timing - it shows exactly what's
     * happening inside the NDArray creation and forward pass.
     */
    record BatchInfo(
            int numChunks,           // Number of text chunks in the batch
            int maxSeqLength,        // Actual max sequence length after tokenization (with padding)
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

        /**
         * Returns a formatted string showing the input tensor shape.
         */
        public String inputShapeString() {
            if (inputShape == null || inputShape.length == 0) {
                return "[unknown]";
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < inputShape.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(inputShape[i]);
            }
            sb.append("]");
            return sb.toString();
        }

        /**
         * Returns a formatted string showing the output tensor shape.
         */
        public String outputShapeString() {
            if (outputShape == null || outputShape.length == 0) {
                return "[unknown]";
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < outputShape.length; i++) {
                if (i > 0) sb.append(", ");
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
     * Gets information about the current batch being processed.
     * This provides visibility into actual tensor shapes during inference.
     *
     * @return BatchInfo with current batch details, or BatchInfo.empty() if no batch is processing
     */
    default BatchInfo getCurrentBatchInfo() {
        return BatchInfo.empty();
    }

    /**
     * Gets the dimensionality of the embeddings produced by this model.
     * This is an alias for dimensions() for compatibility.
     * @return The dimension of the vectors.
     */
    default int getDimensions() {
        return dimensions();
    }

    /**
     * Gets the dimensionality of the embeddings produced by this model.
     * Another alias for dimensions() for compatibility.
     * @return The dimension of the vectors.
     */
    default int getEmbeddingDimension() {
        return dimensions();
    }

    /**
     * Gets the initialization error message if the model failed to load.
     * This allows graceful handling of model unavailability in the UI.
     *
     * @return The error message, or null if no error occurred
     */
    default String getInitializationError() {
        return null;
    }

    /**
     * Checks if the embedding model is initialized and ready for use.
     * This allows the UI to show appropriate status information.
     *
     * @return true if the model is loaded and ready, false otherwise
     */
    default boolean isInitialized() {
        return dimensions() > 0;
    }

    /**
     * Checks if the embedding model is currently loading/initializing.
     * This allows the UI to show a loading indicator.
     *
     * @return true if the model is being loaded, false otherwise
     */
    default boolean isLoading() {
        return false;
    }

    /**
     * Gets the current loading phase name.
     * Phases typically include: IDLE, STARTING, LOOKING_UP_REGISTRY,
     * LOADING_MODEL_FILES, CREATING_ENCODER, TESTING_ENCODER, COMPLETE, FAILED
     *
     * @return The loading phase name, or "IDLE" if not loading
     */
    default String getLoadingPhase() {
        return "IDLE";
    }

    /**
     * Gets a human-readable message about the current loading progress.
     * This is useful for displaying in the UI during model initialization.
     *
     * @return The loading message, or null if not loading
     */
    default String getLoadingMessage() {
        return null;
    }

    /**
     * Gets the elapsed time in milliseconds since loading started.
     * This is useful for displaying loading progress in the UI.
     *
     * @return The elapsed time in milliseconds, or 0 if not loading
     */
    default long getLoadingElapsedMs() {
        return 0;
    }

    /**
     * Closes this embedding model and releases any native resources.
     * Default implementation does nothing - override in implementations
     * that hold native resources (like SameDiff models).
     */
    @Override
    default void close() throws Exception {
        // Default no-op implementation
    }
}