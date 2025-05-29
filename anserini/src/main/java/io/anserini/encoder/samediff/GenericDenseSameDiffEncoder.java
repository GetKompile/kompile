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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GenericDenseSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(GenericDenseSameDiffEncoder.class);

    public static final String DEFAULT_INPUT_IDS_NAME = "input_ids";
    public static final String DEFAULT_ATTENTION_MASK_NAME = "attention_mask";
    public static final String DEFAULT_TOKEN_TYPE_IDS_NAME = "token_type_ids"; // Often optional, model dependent
    public static final List<String> DEFAULT_INPUT_TENSOR_NAMES = List.of(DEFAULT_INPUT_IDS_NAME, DEFAULT_ATTENTION_MASK_NAME, DEFAULT_TOKEN_TYPE_IDS_NAME);
    public static final String DEFAULT_OUTPUT_NAME = "last_hidden_state"; // Or "sentence_embedding", "pooler_output" depending on model

    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;
    public static final boolean DEFAULT_NORMALIZE = true;

    private final boolean normalizeOutput;

    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier,
                                       @NotNull String kompileManagedOnnxModelPath,
                                       @NotNull String kompileManagedVocabPath,
                                       @NotNull List<String> inputTensorNamesForModel,
                                       @NotNull String outputTensorNameFromModel, // Singular as per existing class
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens,
                                       boolean normalizeOutput)
            throws IOException {
        super(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                inputTensorNamesForModel,
                Collections.singletonList(outputTensorNameFromModel),
                doLowerCaseAndStripAccents,
                maxSequenceLength,
                addSpecialTokens);

        if (inputTensorNamesForModel.isEmpty()) {
            throw new IllegalArgumentException(String.format("[%s] Input tensor names for model cannot be empty.", modelIdentifier));
        }
        this.normalizeOutput = normalizeOutput;
        LOG.info("[{}] GenericDenseSameDiffEncoder initialized. Normalize output: {}.",
                this.modelIdentifier, this.normalizeOutput);
    }

    // Simplified constructor using all defaults
    public GenericDenseSameDiffEncoder(@NotNull String modelIdentifier,
                                       @NotNull String kompileManagedOnnxModelPath,
                                       @NotNull String kompileManagedVocabPath) throws IOException {
        this(modelIdentifier, kompileManagedOnnxModelPath, kompileManagedVocabPath,
                DEFAULT_INPUT_TENSOR_NAMES, DEFAULT_OUTPUT_NAME,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS, DEFAULT_MAX_SEQUENCE_LENGTH, DEFAULT_ADD_SPECIAL_TOKENS,
                DEFAULT_NORMALIZE);
    }


    @Override
    public float[] encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();

        // Robustly map based on the size of inputTensorNamesForModel
        if (this.inputTensorNamesForModel.size() >= 1) {
            placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        }
        if (this.inputTensorNamesForModel.size() >= 2) { // Typically input_ids and attention_mask
            placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
        }
        if (this.inputTensorNamesForModel.size() >= 3) { // Optional token_type_ids
            placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);
        }


        try {
            String outputTensorName = this.outputTensorNamesFromModel.get(0);
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, outputTensorName);
            INDArray embeddingTensor = outputMap.get(outputTensorName);

            if (embeddingTensor == null) {
                LOG.error("[{}] Output tensor '{}' not found for GenericDense. Query: '{}'",
                        this.modelIdentifier, outputTensorName, query);
                return null;
            }
            return processOutputTensor(embeddingTensor);
        } catch (Exception e) {
            LOG.error("[{}] Error during GenericDense encoding for query: '{}'", this.modelIdentifier, query, e);
            return null;
        }
    }

    protected float[] processOutputTensor(INDArray embeddingTensor) {
        INDArray clsEmbedding;
        if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
            clsEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
        } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
            clsEmbedding = embeddingTensor.getRow(0);
        } else if (embeddingTensor.rank() == 1) {
            clsEmbedding = embeddingTensor;
        } else {
            LOG.warn("[{}] Unexpected GenericDense embedding tensor shape: {}", this.modelIdentifier, Arrays.toString(embeddingTensor.shape()));
            return null;
        }
        clsEmbedding = clsEmbedding.reshape(1, -1);

        if (this.normalizeOutput) {
            INDArray norm = clsEmbedding.norm2(true, 1);
            INDArray epsilon = Nd4j.scalar(clsEmbedding.dataType(),1e-12f);
            norm = Transforms.max(norm, epsilon);
            INDArray normalizedEmbedding = clsEmbedding.divi(norm);
            return normalizedEmbedding.toFloatVector();
        } else {
            return clsEmbedding.toFloatVector();
        }
    }
}