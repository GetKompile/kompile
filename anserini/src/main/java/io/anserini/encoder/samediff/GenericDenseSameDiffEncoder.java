/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anserini.encoder.samediff;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
// Removed @Nullable for path parameters as they are now mandatory
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.ops.transforms.Transforms; // Added for normalization

import java.io.IOException;
// Removed URISyntaxException
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A generic dense encoder using SameDiff (via ONNX import), assuming BERT-like input.
 * The default output processing extracts the CLS token embedding and normalizes it.
 * This class is refactored for Kompile Model Management, expecting model and vocabulary
 * paths to be provided externally.
 */
public class GenericDenseSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(GenericDenseSameDiffEncoder.class);

    // Default tensor names, can be used by callers when instantiating the encoder
    // if their model follows these common conventions.
    public static final String DEFAULT_INPUT_IDS_NAME = "input_ids";
    public static final String DEFAULT_ATTENTION_MASK_NAME = "attention_mask";
    public static final String DEFAULT_TOKEN_TYPE_IDS_NAME = "token_type_ids";
    public static final String DEFAULT_OUTPUT_NAME = "last_hidden_state";

    // Default tokenizer settings
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    private final boolean normalizeOutput;

    /**
     * Constructor for the generic dense encoder, using Kompile-managed paths.
     *
     * @param modelIdentifier             A unique identifier for this model (e.g., "my_generic_encoder_onnx").
     * @param kompileManagedOnnxModelPath Absolute path to the Kompile-managed ONNX model file.
     * @param kompileManagedVocabPath     Absolute path to the Kompile-managed vocabulary file.
     * @param inputTensorNamesForModel    List of input tensor names for the ONNX model.
     * Order matters: expected to correspond to input_ids, attention_mask, token_type_ids.
     * @param outputTensorNameFromModel   Name of the output tensor from the ONNX model (e.g., "last_hidden_state").
     * @param doLowerCaseAndStripAccents  Tokenizer option.
     * @param maxSequenceLength           Tokenizer option.
     * @param addSpecialTokens            Tokenizer option.
     * @param normalizeOutput             Whether to L2 normalize the final embedding.
     * @throws IOException If model or vocabulary loading fails from the provided paths.
     */
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
                Collections.singletonList(outputTensorNameFromModel), // Parent expects a list of output names
                doLowerCaseAndStripAccents,
                maxSequenceLength,
                addSpecialTokens);

        if (inputTensorNamesForModel.isEmpty()) {
            throw new IllegalArgumentException(String.format("[%s] Input tensor names for model cannot be empty.", modelIdentifier));
        }
        this.normalizeOutput = normalizeOutput;
        LOG.info("[{}] GenericDenseSameDiffEncoder initialized. Normalize output: {}. Model path: {}, Vocab path: {}",
                this.modelIdentifier, this.normalizeOutput, kompileManagedOnnxModelPath, kompileManagedVocabPath);
    }

    /**
     * Simplified constructor using default tensor names and tokenizer settings.
     *
     * @param modelIdentifier             Identifier for this model instance.
     * @param kompileManagedOnnxModelPath Path to the Kompile-managed ONNX model.
     * @param kompileManagedVocabPath     Path to the Kompile-managed vocabulary.
     * @param normalizeOutput             Whether to L2 normalize the final output.
     * @throws IOException If loading fails.
     */
    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier,
                                       @NotNull String kompileManagedOnnxModelPath,
                                       @NotNull String kompileManagedVocabPath,
                                       boolean normalizeOutput) throws IOException {
        this(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                List.of(DEFAULT_INPUT_IDS_NAME, DEFAULT_ATTENTION_MASK_NAME, DEFAULT_TOKEN_TYPE_IDS_NAME),
                DEFAULT_OUTPUT_NAME,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS, DEFAULT_MAX_SEQUENCE_LENGTH, DEFAULT_ADD_SPECIAL_TOKENS,
                normalizeOutput);
    }


    @Override
    public float[] encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();

        // Map inputs based on the provided inputTensorNamesForModel list
        // This assumes the list order corresponds to how inputs should be fed
        // i.e., name at index 0 is for inputIdsArr, index 1 for attentionMaskArr, etc.
        if (this.inputTensorNamesForModel.size() >= 1) {
            placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        }
        if (this.inputTensorNamesForModel.size() >= 2) {
            placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
        }
        if (this.inputTensorNamesForModel.size() >= 3) {
            placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);
        } else if (this.inputTensorNamesForModel.size() < 1) {
            LOG.error("[{}] No input tensor names defined. Cannot encode.", this.modelIdentifier);
            return null;
        }

        try {
            String outputTensorName = this.outputTensorNamesFromModel.get(0); // Assumes one primary output
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, outputTensorName);
            INDArray embeddingTensor = outputMap.get(outputTensorName);

            if (embeddingTensor == null) {
                LOG.error("[{}] Output tensor '{}' not found in SameDiff model output for query: '{}'",
                        this.modelIdentifier, outputTensorName, query);
                return null;
            }

            return processOutputTensor(embeddingTensor);

        } catch (Exception e) {
            LOG.error("[{}] Error during SameDiff encoding for query: '{}'", this.modelIdentifier, query, e);
            return null;
        }
    }

    /**
     * Processes the raw output tensor from the SameDiff model to extract the final embedding.
     * Default implementation assumes CLS token embedding from a [batch, seq_len, hidden_dim] tensor
     * or a [batch, hidden_dim] tensor. L2 normalization is applied if configured.
     *
     * @param embeddingTensor The raw output tensor from the model.
     * @return The processed float[] embedding, or null if processing fails.
     */
    protected float[] processOutputTensor(INDArray embeddingTensor) {
        INDArray clsEmbedding;
        // Assuming batch size is 1 for typical inference query
        if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
            // Shape [1, seq_len, hidden_dim], take [CLS] token (index 0 of seq_len)
            clsEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
        } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
            // Shape [1, hidden_dim], model might have already done pooling
            clsEmbedding = embeddingTensor.getRow(0);
        } else if (embeddingTensor.rank() == 1) {
            // Shape [hidden_dim], model output might be squeezed
            clsEmbedding = embeddingTensor;
        }
        else {
            LOG.warn("[{}] Unexpected embedding tensor output shape: {}. Cannot reliably extract CLS embedding. Expected rank 1, 2 (with batch_size=1), or 3 (with batch_size=1).",
                    this.modelIdentifier, Arrays.toString(embeddingTensor.shape()));
            return null;
        }

        clsEmbedding = clsEmbedding.reshape(1, -1); // Ensure [1, hidden_size] for normalization

        if (this.normalizeOutput) {
            INDArray norm = clsEmbedding.norm2(true, 1); // L2 norm along dimension 1 (hidden_size)
            INDArray epsilon = Nd4j.scalar(clsEmbedding.dataType(),1e-12f); // Prevent division by zero
            norm = Transforms.max(norm, epsilon); // norm = max(norm, epsilon)
            INDArray normalizedEmbedding = clsEmbedding.divi(norm);
            return normalizedEmbedding.toFloatVector();
        } else {
            return clsEmbedding.toFloatVector();
        }
    }
}