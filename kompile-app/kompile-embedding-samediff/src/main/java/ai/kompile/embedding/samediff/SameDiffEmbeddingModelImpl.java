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
import ai.kompile.pipelines.util.URIUtils; // Assuming this utility for loading
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.common.base.Preconditions;
import org.springframework.ai.document.Document;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
    public List<List<Float>> embed(List<String> texts) {
        if (this.sameDiff == null) {
            log.warn("SameDiff model is not loaded. Cannot generate embeddings.");
            return texts.stream().map(text -> Collections.<Float>emptyList()).collect(Collectors.toList());
        }
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        // For batch processing, SameDiff models might expect a single batch INDArray.
        // This example processes one by one for simplicity, but batching is preferred for performance.
        List<List<Float>> batchEmbeddings = new ArrayList<>(texts.size());
        for (String text : texts) {
            batchEmbeddings.add(embed(text));
        }
        return batchEmbeddings;
    }

    @Override
    public List<List<Float>> embedDocuments(List<Document> documents) {
        return embed(documents.stream().map(input -> input.getText()).collect(Collectors.toList()));
    }

    @Override
    public int dimensions() {
        return 0;
    }

    @Override
    public List<Float> embed(String text) {
        if (this.sameDiff == null) {
            log.warn("SameDiff model is not loaded. Cannot generate embedding for text: '{}'", text);
            return Collections.emptyList();
        }
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

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
            INDArray inputArray = createSimpleInputArrayFromString(text, 128); // Assuming max length 128
            if (inputArray == null) return Collections.emptyList();

            // Associate the input NDArray with the input placeholder/variable name
            this.sameDiff.associateArrayWithVariable(inputArray, this.inputTensorName);
            // If your model uses sd.setArray(placeholderName, array), use that.

            // Execute the graph to get the specified output tensor
            Map<String, INDArray> outputMap = this.sameDiff.output(Collections.emptyMap(), this.outputTensorName);
            INDArray embeddingArray = outputMap.get(this.outputTensorName);

            if (embeddingArray == null) {
                log.warn("Output tensor '{}' not found in SameDiff model output for text: '{}'", this.outputTensorName, text);
                return Collections.emptyList();
            }

            // --- Placeholder for INDArray-to-List<Float> Conversion ---
            return convertINDArrayToListFloat(embeddingArray);

        } catch (Exception e) {
            log.error("Error during SameDiff embedding generation for text: '" + text + "'", e);
            return Collections.emptyList();
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
        // Assuming the model expects input shape like [1, maxLength] for a single sample
        return Nd4j.create(floatArray).reshape(1, maxLength);
    }

    /**
     * Converts an INDArray (expected to be a 1D vector or a 2D array with one row) to a List<Float>.
     * @param array The INDArray containing the embedding.
     * @return A List<Float> representation of the embedding.
     */
    private List<Float> convertINDArrayToListFloat(INDArray array) {
        if (array == null) {
            return Collections.emptyList();
        }
        // Ensure the array is a vector (or can be treated as one)
        INDArray vector;
        if (array.isRowVector() || array.isColumnVector()) {
            vector = array.reshape(-1); // Flatten to 1D
        } else if (array.rank() == 2 && array.rows() == 1) {
            vector = array.getRow(0);
        } else if (array.rank() == 1) {
            vector = array;
        }
        else {
            log.warn("Embedding INDArray is not a recognized vector format. Shape: {}. Will attempt to flatten.", array.shape());
            vector = array.reshape(-1); // Best effort: flatten
        }


        List<Float> floatList = new ArrayList<>((int) vector.length());
        for (long i = 0; i < vector.length(); i++) {
            floatList.add(vector.getFloat(i));
        }
        return floatList;
    }

}