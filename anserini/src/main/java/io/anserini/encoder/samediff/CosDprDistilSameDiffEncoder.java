/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

// File: getkompile/kompile/kompile-ag_new_kompile_cli/anserini/src/main/java/io/anserini/encoder/samediff/CosDprDistilSameDiffEncoder.java
package io.anserini.encoder.samediff;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CosDprDistilSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(CosDprDistilSameDiffEncoder.class);

    public static final String DEFAULT_MODEL_NAME = "cos-dpr-distil.sd";
    public static final String DEFAULT_MODEL_URL = "https://PLACEHOLDER_URL/cos-dpr-distil.sd"; // Replace with actual URL
    public static final String DEFAULT_VOCAB_NAME = "cos-dpr-distil-vocab.txt";
    public static final String DEFAULT_VOCAB_URL = "https://huggingface.co/sentence-transformers/cos-dpr-distil/resolve/main/vocab.txt";

    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "last_hidden_state";

    // Default tokenizer settings for this specific encoder
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    public CosDprDistilSameDiffEncoder() throws IOException, URISyntaxException {
        this(null, null);
    }

    public CosDprDistilSameDiffEncoder(@Nullable String modelPath, @Nullable String vocabPath) throws IOException, URISyntaxException {
        this(DEFAULT_MODEL_NAME, modelPath == null ? DEFAULT_MODEL_URL : null,
                DEFAULT_VOCAB_NAME, vocabPath == null ? DEFAULT_VOCAB_URL : null,
                modelPath, vocabPath,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS);
    }

    public CosDprDistilSameDiffEncoder(@NotNull String modelName, @Nullable String modelUrl,
                                       @NotNull String vocabName, @Nullable String vocabUrl,
                                       @Nullable String providedModelPath, @Nullable String providedVocabPath,
                                       boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens)
            throws IOException, URISyntaxException {
        super(modelName, modelUrl, vocabName, vocabUrl,
                providedModelPath, providedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                Collections.singletonList(OUTPUT_EMBEDDING_TENSOR_NAME),
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
    }

    @Override
    public float[] encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
        placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);

        try {
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray embeddingTensor = outputMap.get(this.outputTensorNamesFromModel.get(0));

            if (embeddingTensor == null) {
                LOG.error("Output tensor '{}' not found in SameDiff model output for query: {}", this.outputTensorNamesFromModel.get(0), query);
                return null;
            }

            INDArray finalEmbedding;
            if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
                finalEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
            } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
                finalEmbedding = embeddingTensor;
            } else {
                LOG.warn("Unexpected embedding tensor shape: {}", Arrays.toString(embeddingTensor.shape()));
                return null;
            }
            finalEmbedding = finalEmbedding.reshape(1, -1);

            INDArray norm = finalEmbedding.norm2(true, 1);
            INDArray epsilon = Nd4j.scalar(finalEmbedding.dataType(), 1e-12);
            norm = norm.add(epsilon);
            INDArray normalizedEmbedding = finalEmbedding.divi(norm);

            return normalizedEmbedding.toFloatVector();

        } catch (Exception e) {
            LOG.error("Error during SameDiff CosDPR encoding for query: " + query, e);
            throw new RuntimeException("Error during SameDiff encoding for query: " + query, e);
        }
    }
}