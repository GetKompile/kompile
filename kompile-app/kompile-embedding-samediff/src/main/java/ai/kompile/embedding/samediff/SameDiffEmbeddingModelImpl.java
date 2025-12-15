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

package ai.kompile.embedding.samediff;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.embedding.samediff.config.SameDiffEmbeddingProperties;
import ai.kompile.pipelines.util.URIUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.common.base.Preconditions;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class SameDiffEmbeddingModelImpl implements EmbeddingModel {

    private final SameDiffEmbeddingProperties properties;
    private SameDiff sameDiff;
    private final String inputTensorName;
    private final String outputTensorName;
    private int dimensions = -1;

    public SameDiffEmbeddingModelImpl(@NotNull SameDiffEmbeddingProperties properties) {
        Preconditions.checkNotNull(properties, "SameDiffEmbeddingProperties cannot be null");
        this.properties = properties;
        this.inputTensorName = properties.getInputTensorName();
        this.outputTensorName = properties.getOutputTensorName();

        if (properties.getModelUri() == null || properties.getModelUri().isEmpty()) {
            log.warn("SameDiff model URI is not configured. SameDiffEmbeddingModelImpl will not be functional.");
            this.sameDiff = null; // Or throw an InitializationException
            return;
        }

        try {
            log.info("Loading SameDiff embedding model from URI: {}", properties.getModelUri());
            File modelFile = URIUtils.getFileFromUriOrPath(properties.getModelUri());
            if (modelFile != null && modelFile.exists()) {
                this.sameDiff = SameDiff.load(modelFile, true);
                log.info("Successfully loaded SameDiff model. Inputs: {}, Outputs: {}",
                        this.sameDiff.inputs(), this.sameDiff.outputs());

                // Validate that configured input/output names exist in the model
                if (!this.sameDiff.getVariables().containsKey(this.inputTensorName) && !this.sameDiff.getVariables().containsKey(this.inputTensorName)) {
                    log.warn("Configured input tensor name '{}' not found in the loaded SameDiff model's variables or placeholders. Available: variables={}, placeholders={}",
                            this.inputTensorName, this.sameDiff.getVariables().keySet(), this.sameDiff.getVariables());
                    // Consider this a fatal error for embedding
                }
                if (!this.sameDiff.getVariables().containsKey(this.outputTensorName)) {
                    log.warn("Configured output tensor name '{}' not found in the loaded SameDiff model's variables. Available: {}",
                            this.outputTensorName, this.sameDiff.getVariables().keySet());
                    // Consider this a fatal error for embedding
                }


            } else {
                log.error("SameDiff model file not found or could not be accessed at URI: {}", properties.getModelUri());
                this.sameDiff = null;
            }
        } catch (Exception e) {
            log.error("Failed to load SameDiff model from URI: " + properties.getModelUri(), e);
            this.sameDiff = null;
        }
    }

    @Override
    public INDArray embed(List<String> texts) {
        if (this.sameDiff == null) {
            log.warn("SameDiff model is not loaded. Cannot generate embeddings.");
            return Nd4j.empty(DataType.FLOAT);
        }
        if (texts == null || texts.isEmpty()) {
            return Nd4j.empty(DataType.FLOAT);
        }
        // For batch processing, SameDiff models might expect a single batch INDArray.
        // This example processes one by one for simplicity, but batching is preferred for performance.
        INDArray arrs = Nd4j.create(texts.size(),this.dimensions());
        for(int i = 0; i < arrs.rows(); i++) {
            // CRITICAL: Close the temporary INDArray after putRow to prevent memory leak
            INDArray rowEmbedding = embed(texts.get(i));
            try {
                arrs.putRow(i, rowEmbedding);
            } finally {
                if (rowEmbedding != null && !rowEmbedding.wasClosed()) {
                    try {
                        rowEmbedding.close();
                    } catch (Exception e) {
                        log.trace("Error closing row embedding: {}", e.getMessage());
                    }
                }
            }
        }

        return arrs;
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        return embed(documents.stream().map(input -> input.getText()).collect(Collectors.toList()));
    }

    @Override
    public int dimensions() {
        if(dimensions < 0) {
            // Evaluate to get dimensions, then close the array to prevent memory leak
            INDArray evalArray = this.sameDiff.getVariable(outputTensorName).eval();
            try {
                this.dimensions = (int) evalArray.length();
            } finally {
                if (evalArray != null && !evalArray.wasClosed()) {
                    try {
                        evalArray.close();
                    } catch (Exception e) {
                        log.trace("Error closing eval array: {}", e.getMessage());
                    }
                }
            }
        }
        return dimensions;
    }

    @Override
    public INDArray embed(String text) {
        if (this.sameDiff == null) {
            log.warn("SameDiff model is not loaded. Cannot generate embedding for text: '{}'", text);
            return Nd4j.empty(DataType.FLOAT);
        }
        if (text == null || text.isEmpty()) {
            return Nd4j.empty(DataType.FLOAT);
        }

        INDArray inputArray = null;
        Map<String, INDArray> outputMap = null;
        INDArray result = null;
        try {
            // --- Placeholder for Text-to-INDArray Conversion ---
            // This is highly model-specific.
            // 1. Tokenization: Use a tokenizer compatible with your model (e.g., WordPiece, SentencePiece, or a custom one).
            //    The tokenizer might come from kompile-pipelines-steps-samediff/src/main/java/ai/kompile/pipelines/steps/samediff/nlp/
            //    (e.g., SameDiffWordPieceTokenizer if suitable)
            // 2. Token ID to INDArray: Convert token IDs into an INDArray with the shape expected by the model.
            //    (e.g., [1, sequenceLength] or [batchSize, sequenceLength])

            // Example: Assuming a very simple model that takes a fixed-size array of character bytes
            // THIS IS A SIMPLISTIC PLACEHOLDER - REPLACE WITH ACTUAL PREPROCESSING FOR YOUR MODEL
            inputArray = createSimpleInputArrayFromString(text, 128); // Assuming max length 128
            if (inputArray == null) return Nd4j.empty(DataType.FLOAT);

            // Associate the input NDArray with the input placeholder/variable name
            this.sameDiff.associateArrayWithVariable(inputArray, this.inputTensorName);
            // If your model uses sd.setArray(placeholderName, array), use that.

            // Execute the graph to get the specified output tensor
            outputMap = this.sameDiff.output(Collections.emptyMap(), this.outputTensorName);
            INDArray embeddingArray = outputMap.get(this.outputTensorName);

            if (embeddingArray == null) {
                log.warn("Output tensor '{}' not found in SameDiff model output for text: '{}'", this.outputTensorName, text);
                return Nd4j.empty(DataType.FLOAT);
            }

            // CRITICAL MEMORY FIX: Clone the embedding array before the finally block closes it
            // The outputMap will be closed in finally, so we must return a detached copy
            // dup() creates a completely independent copy with its own native memory buffer
            result = embeddingArray.dup();
            return result;

        } catch (Exception e) {
            log.error("Error during SameDiff embedding generation for text: '" + text + "'", e);
            // Close result if we created it before the exception
            if (result != null && !result.wasClosed()) {
                try {
                    result.close();
                } catch (Exception closeEx) {
                    log.trace("Error closing result array: {}", closeEx.getMessage());
                }
            }
            return Nd4j.empty(DataType.FLOAT);
        } finally {
            // CRITICAL: Close the input array to release native memory
            if (inputArray != null && !inputArray.wasClosed()) {
                try {
                    inputArray.close();
                } catch (Exception e) {
                    log.trace("Error closing input array: {}", e.getMessage());
                }
            }
            // CRITICAL: Close ALL output arrays in the outputMap
            // The result has been dup()'d so it's safe to close these now
            if (outputMap != null) {
                for (Map.Entry<String, INDArray> entry : outputMap.entrySet()) {
                    INDArray arr = entry.getValue();
                    if (arr != null && !arr.wasClosed()) {
                        try {
                            arr.close();
                        } catch (Exception e) {
                            log.trace("Error closing output array '{}': {}", entry.getKey(), e.getMessage());
                        }
                    }
                }
            }
        }
    }

    /**
     * PLACEHOLDER: Converts a String to an INDArray.
     * This needs to be replaced with actual preprocessing logic suitable for the specific SameDiff embedding model.
     * For example, tokenization to IDs, padding/truncation, etc.
     *
     * @param text The input text.
     * @param maxLength The maximum sequence length.
     * @return An INDArray representation of the text.
     */
    private INDArray createSimpleInputArrayFromString(String text, int maxLength) {
        // This is a naive example. Real models require sophisticated tokenization.
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        float[] floatArray = new float[maxLength];
        for (int i = 0; i < maxLength; i++) {
            if (i < textBytes.length) {
                floatArray[i] = textBytes[i];
            } else {
                floatArray[i] = 0.0f; // Padding
            }
        }
        // Create array with correct shape directly to avoid memory leak from reshape views.
        // Previously: Nd4j.create(floatArray).reshape(1, maxLength) would create a temp array
        // that never got explicitly closed, causing the underlying buffer to leak.
        return Nd4j.create(floatArray, new long[]{1, maxLength});
    }

    /**
     * Closes this embedding model and releases all native resources.
     * Implements AutoCloseable.close() for proper resource management.
     */
    @Override
    public void close() throws Exception {
        cleanup();
    }

    /**
     * Cleanup method called by Spring when this bean is destroyed.
     * Properly closes the SameDiff model and all native resources.
     *
     * CRITICAL: Cleanup order matters for proper memory release:
     * 1. First close SameDiff model (releases OpContext caches and InferenceSessions)
     * 2. Then destroy workspaces (releases workspace memory that was used by model)
     * 3. Finally release memory manager context
     *
     * The order is important: SameDiff InferenceSessions cache OpContexts that hold
     * references to workspace memory. If we destroy workspaces first, those references
     * become dangling and may not be properly cleaned up.
     */
    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up SameDiffEmbeddingModelImpl");

        // Step 1: Close SameDiff model FIRST
        // This releases all OpContexts cached in InferenceSessions.
        // InferenceSessions hold references to workspace buffers, so we must
        // close them before destroying the workspaces they reference.
        if (this.sameDiff != null) {
            try {
                log.debug("Step 1: Closing SameDiff model (releases OpContext caches)");
                // Use reflection to call close() for compatibility with different nd4j-api versions
                java.lang.reflect.Method closeMethod = this.sameDiff.getClass().getMethod("close");
                closeMethod.invoke(this.sameDiff);
                log.info("Closed SameDiff model and all cached native resources");
            } catch (NoSuchMethodException e) {
                log.debug("SameDiff.close() not available in this nd4j-api version - skipping cleanup");
            } catch (Exception e) {
                log.warn("Error during SameDiff cleanup", e);
            }
            this.sameDiff = null;
        }

        // Step 2: Now destroy workspaces - safe because SameDiff no longer references them
        try {
            log.debug("Step 2: Destroying ND4J workspaces");
            Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
        } catch (Exception e) {
            log.debug("Could not destroy workspaces (may already be destroyed): {}", e.getMessage());
        }

        // Step 3: Release memory manager context
        try {
            log.debug("Step 3: Releasing ND4J memory manager context");
            Nd4j.getMemoryManager().releaseCurrentContext();
        } catch (Exception e) {
            log.debug("Could not release memory context: {}", e.getMessage());
        }

        // Hint GC to clean up any orphaned native references
        System.gc();
        log.info("SameDiffEmbeddingModelImpl cleanup complete");
    }
}