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
import org.jetbrains.annotations.Nullable;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A generic dense encoder using SameDiff, assuming BERT-like input and CLS token embedding output.
 * Subclasses can override post-processing if needed.
 */
public class GenericDenseSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(GenericDenseSameDiffEncoder.class);

    // Default tensor names, can be used by callers when instantiating the encoder
    // if their model follows these common conventions.
    public static final String DEFAULT_INPUT_IDS_NAME = "input_ids";
    public static final String DEFAULT_ATTENTION_MASK_NAME = "attention_mask";
    public static final String DEFAULT_TOKEN_TYPE_IDS_NAME = "token_type_ids";
    public static final String DEFAULT_OUTPUT_NAME = "last_hidden_state"; // Common output for BERT hidden states

    /**
     * Constructor for the generic dense encoder.
     *
     * @param modelName                  Filename of the SameDiff model.
     * @param modelUrl                   URL to download the SameDiff model (can be null if providedModelPath is set).
     * @param vocabName                  Filename of the vocabulary (e.g., "vocab.txt").
     * @param vocabUrl                   URL to download the vocabulary (can be null if providedVocabPath is set).
     * @param providedModelPath          Local path to the SameDiff model (can be null if modelUrl is set).
     * @param providedVocabPath          Local path to the vocabulary (can be null if vocabUrl is set).
     * @param inputTensorNamesForModel   List of input tensor names for the SameDiff model.
     * Order matters: expected to be [input_ids_name, attention_mask_name, token_type_ids_name]
     * if the model uses all three. If fewer, provide only the used ones in the correct order.
     * @param outputTensorNameFromModel  Name of the output tensor to fetch (e.g., "last_hidden_state").
     * @param doLowerCaseAndStripAccents Whether to lowercase text and strip accents during tokenization.
     * @param maxSequenceLength          Maximum sequence length for tokenization.
     * @param addSpecialTokens           Whether to add [CLS] and [SEP] tokens during tokenization.
     * @throws IOException        If model or vocab loading fails.
     * @throws URISyntaxException If URLs are malformed.
     */
    public GenericDenseSameDiffEncoder(@NotNull String modelName, @Nullable String modelUrl,
                                       @NotNull String vocabName, @Nullable String vocabUrl,
                                       @Nullable String providedModelPath, @Nullable String providedVocabPath,
                                       @NotNull List<String> inputTensorNamesForModel,
                                       @NotNull String outputTensorNameFromModel,
                                       boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens)
            throws IOException, URISyntaxException {
        super(modelName, modelUrl,
                vocabName, vocabUrl,
                providedModelPath, providedVocabPath,
                inputTensorNamesForModel, // User-provided list of input tensor names
                Collections.singletonList(outputTensorNameFromModel), // Assumes one primary output for embedding
                doLowerCaseAndStripAccents,
                maxSequenceLength,
                addSpecialTokens);
        if (inputTensorNamesForModel.isEmpty()) {
            throw new IllegalArgumentException("Input tensor names for model cannot be empty.");
        }
    }


    @Override
    public float[] encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();

        if (this.inputTensorNamesForModel == null || this.inputTensorNamesForModel.isEmpty()) {
            LOG.error("Input tensor names for the model are not defined. Cannot proceed with encoding.");
            return null;
        }

        // Map generated inputs to the tensor names provided in the constructor.
        // It's assumed the user provides the tensor names in the order corresponding to:
        // 1. input_ids, 2. attention_mask (if model uses it), 3. token_type_ids (if model uses it).
        // The number of entries in inputTensorNamesForModel dictates how many inputs are mapped.
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        if (this.inputTensorNamesForModel.size() > 1) {
            placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
        }
        if (this.inputTensorNamesForModel.size() > 2) {
            placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);
        }
        // Note: If a model uses fewer than 3 inputs (e.g., only input_ids and attention_mask),
        // the inputTensorNamesForModel list should only contain the names for those inputs,
        // and tokenTypeIdsArr will be created but not used in the placeholderMap if size < 3.

        try {
            // outputTensorNamesFromModel is a list (set in constructor), get(0) for the primary output.
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray embeddingTensor = outputMap.get(this.outputTensorNamesFromModel.get(0));

            if (embeddingTensor == null) {
                LOG.error("Output tensor '{}' not found in SameDiff model output for query: {}", this.outputTensorNamesFromModel.get(0), query);
                return null;
            }

            return processOutputTensor(embeddingTensor);

        } catch (Exception e) {
            LOG.error("Error during SameDiff encoding for query: " + query, e);
            return null;
        }
    }

    /**
     * Processes the raw output tensor from the SameDiff model to extract the final embedding.
     * Default implementation assumes CLS token embedding from a [batch, seq_len, hidden_dim] tensor
     * or a [batch, hidden_dim] tensor, followed by L2 normalization.
     * Subclasses can override this for model-specific output handling.
     *
     * @param embeddingTensor The raw output tensor from the model.
     * @return The processed float[] embedding, or null if processing fails.
     */
    protected float[] processOutputTensor(INDArray embeddingTensor) {
        INDArray clsEmbedding;
        if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
            clsEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all()); // CLS token
        } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
            clsEmbedding = embeddingTensor.getRow(0); // Already [1, hidden_size] or taking the first if batch > 1
        } else if (embeddingTensor.rank() == 1) { // If model directly outputs [hidden_size]
            clsEmbedding = embeddingTensor;
        } else {
            LOG.warn("Unexpected embedding tensor shape: {}. Cannot extract CLS embedding.", Arrays.toString(embeddingTensor.shape()));
            return null;
        }

        clsEmbedding = clsEmbedding.reshape(1, -1); // Ensure [1, hidden_size] for normalization

        INDArray norm = clsEmbedding.norm2(true, 1);
        // Add small epsilon to prevent division by zero for zero vectors
        if(norm.isScalar() && norm.getDouble(0) == 0.0) {
            // if norm is exactly 0, replace with a small value to avoid NaN, but keep embedding as zero vector (effectively)
            norm = Nd4j.scalar(clsEmbedding.dataType(),1e-12);
        } else if (!norm.isScalar()) { // Should be scalar after norm2 along axis 1 with keepDims=true or false
            // This case might indicate an issue or an unexpected tensor shape from norm2.
            // However, if it's a vector of norms (e.g. if keepDims was false and multiple rows),
            // we add epsilon to each element.
            norm.addi(Nd4j.scalar(clsEmbedding.dataType(),1e-12)); // Add epsilon to each norm element
        } else {
            norm.addi(Nd4j.scalar(clsEmbedding.dataType(),1e-12)); // Add epsilon to the scalar norm
        }

        INDArray normalizedEmbedding = clsEmbedding.divi(norm);

        return normalizedEmbedding.toFloatVector();
    }
}