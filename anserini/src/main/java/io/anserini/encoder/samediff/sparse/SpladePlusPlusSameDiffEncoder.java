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

package io.anserini.encoder.samediff;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import io.anserini.encoder.samediff.tokenizer.SamediffBertVocabulary; // For CLS, SEP, PAD constants
import org.jetbrains.annotations.NotNull;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms; // For ReLU

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class SpladePlusPlusSameDiffEncoder extends SameDiffEncoder<Map<String, Float>> {

    // Common tensor names based on original SpladePlusPlusEncoder ONNX version
    // These MUST match your converted SameDiff model's output tensor names
    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";

    // Option 1: If SameDiff model outputs final indices and weights directly (like ONNX version)
    public static final String OUTPUT_IDX_TENSOR_NAME = "output_idx";     // Tensor of vocabulary indices
    public static final String OUTPUT_WEIGHTS_TENSOR_NAME = "output_weights"; // Tensor of corresponding weights

    // Option 2: If SameDiff model outputs logits over vocabulary per token (more common for BERT backbone)
    // public static final String OUTPUT_LOGITS_TENSOR_NAME = "logits"; // e.g., shape [1, vocab_size] after pooling

    public static final int MAX_SEQUENCE_LENGTH = 512; // Default from original
    public static final boolean DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final boolean ADD_SPECIAL_TOKENS = true;


    protected final SamediffBertVocabulary anseriniVocabulary; // Needed to map IDs to Tokens

    protected SpladePlusPlusSameDiffEncoder(@NotNull String modelName, @NotNull String modelUrl,
                                            @NotNull String vocabName, @NotNull String vocabUrl)
            throws IOException, URISyntaxException {
        super(modelName, modelUrl,
                vocabName, vocabUrl, // This vocab is used for tokenizing input query
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                // Define outputs based on how your SameDiff SPLADE model is structured.
                // If it outputs indices and weights directly (like the ONNX version):
                List.of(OUTPUT_IDX_TENSOR_NAME, OUTPUT_WEIGHTS_TENSOR_NAME),
                // If it outputs full logits (more typical before top-k selection in SPLADE):
                // Collections.singletonList(OUTPUT_LOGITS_TENSOR_NAME),
                DO_LOWERCASE_AND_STRIP_ACCENTS,
                MAX_SEQUENCE_LENGTH,
                ADD_SPECIAL_TOKENS);
        // Store a reference to the vocabulary used by the tokenizer preprocessor
        this.anseriniVocabulary = this.tokenizerPreProcessor.getVocabulary();
    }


    @Override
    public Map<String, Float> encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        placeholderMap.put(INPUT_IDS_TENSOR_NAME, inputIdsArr);
        placeholderMap.put(ATTENTION_MASK_TENSOR_NAME, attentionMaskArr);
        placeholderMap.put(TOKEN_TYPE_IDS_TENSOR_NAME, tokenTypeIdsArr);

        try {
            // Assuming outputTensorNamesFromModel was set to [OUTPUT_IDX_TENSOR_NAME, OUTPUT_WEIGHTS_TENSOR_NAME] in constructor
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel);

            INDArray indexesTensor = outputMap.get(OUTPUT_IDX_TENSOR_NAME);
            INDArray weightsTensor = outputMap.get(OUTPUT_WEIGHTS_TENSOR_NAME);

            if (indexesTensor == null || weightsTensor == null) {
                return Collections.emptyMap();
            }

            // Assuming indexesTensor and weightsTensor are 1D arrays of the same length
            long[] indexes = indexesTensor.toLongVector();
            float[] weights = weightsTensor.toFloatVector();

            Map<String, Float> tokenFloatWeights = new LinkedHashMap<>();
            for (int i = 0; i < indexes.length; i++) {
                // Skip special tokens by their ID (CLS=101, SEP=102, PAD=0 in many BERT vocabs)
                // This relies on the vocabulary used by the SPLADE model itself (during its training/conversion)
                // which might be different or have different IDs than our query tokenizer's vocab if not careful.
                // For SPLADE, the `indexes` are IDs from its *own* vocabulary.
                if (indexes[i] == this.anseriniVocabulary.getTokenId(SamediffBertVocabulary.CLS_TOKEN) ||
                        indexes[i] == this.anseriniVocabulary.getTokenId(SamediffBertVocabulary.SEP_TOKEN) ||
                        indexes[i] == this.anseriniVocabulary.getTokenId(SamediffBertVocabulary.PAD_TOKEN)) {
                    continue;
                }
                // It's crucial that anseriniVocabulary correctly maps these output indices.
                tokenFloatWeights.put(this.anseriniVocabulary.getToken((int)indexes[i]), weights[i]);
            }
            return tokenFloatWeights;

        } catch (Exception e) {
            // throw new RuntimeException("Error during SPLADE SameDiff encoding for query: " + query, e);
            return Collections.emptyMap();
        }
    }
    // Helper method in SamediffBertTokenizerPreProcessor to expose vocabulary
    // Add this to SamediffBertTokenizerPreProcessor.java:
    // public SamediffBertVocabulary getVocabulary() { return this.vocabulary; }
    // public SamediffBertWordPieceTokenizer getWordPieceTokenizer() {return this.wordPieceTokenizer; }

}