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

// import io.anserini.encoder.Encoder;
import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.jetbrains.annotations.NotNull;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;


import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// public class ArcticEmbedSameDiffEncoder extends SameDiffEncoder<float[]> implements Encoder {
public class ArcticEmbedSameDiffEncoder extends SameDiffEncoder<float[]> {

    public static final String DEFAULT_MODEL_NAME = "nenglish-arctic-embed-l.sd";
    public static final String DEFAULT_MODEL_URL = "YOUR_MODEL_REPO_URL/nenglish-arctic-embed-l.sd"; // Replace
    public static final String DEFAULT_VOCAB_NAME = "arctic-embed-l-vocab.txt"; // Or its actual vocab name
    // Find the actual vocab URL, this is a placeholder from a similar model family
    public static final String DEFAULT_VOCAB_URL = "https://huggingface.co/sentence-transformers/bert-base-nli-mean-tokens/resolve/main/vocab.txt";


    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "last_hidden_state"; // Common default

    public static final int MAX_SEQUENCE_LENGTH = 256; // Typical for Arctic, but verify
    public static final boolean DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final boolean ADD_SPECIAL_TOKENS = true;

    public ArcticEmbedSameDiffEncoder() throws IOException, URISyntaxException {
        super(DEFAULT_MODEL_NAME, DEFAULT_MODEL_URL,
                DEFAULT_VOCAB_NAME, DEFAULT_VOCAB_URL,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                Collections.singletonList(OUTPUT_EMBEDDING_TENSOR_NAME),
                DO_LOWERCASE_AND_STRIP_ACCENTS,
                MAX_SEQUENCE_LENGTH,
                ADD_SPECIAL_TOKENS);
    }

    @Override
    public float[] encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        if (this.inputTensorNamesForModel.size() > 1 && this.inputTensorNamesForModel.contains(ATTENTION_MASK_TENSOR_NAME)) {
            placeholderMap.put(ATTENTION_MASK_TENSOR_NAME, attentionMaskArr);
        }
        if (this.inputTensorNamesForModel.size() > 2 && this.inputTensorNamesForModel.contains(TOKEN_TYPE_IDS_TENSOR_NAME)) {
            placeholderMap.put(TOKEN_TYPE_IDS_TENSOR_NAME, tokenTypeIdsArr);
        }

        try {
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray hiddenStates = outputMap.get(this.outputTensorNamesFromModel.get(0)); // e.g. last_hidden_state

            if (hiddenStates == null) {
                return null;
            }

            // Arctic and many sentence-transformers use mean pooling of last hidden state,
            // masked by attention_mask.
            if (hiddenStates.rank() != 3 || hiddenStates.shape()[0] != 1) { // Expect [1, seq_len, hidden_dim]
                // System.err.println("Unexpected hiddenStates tensor rank or batch size: " + hiddenStates.rank());
                return null;
            }

            // Expand attention_mask to be broadcastable with hiddenStates for masking
            // hiddenStates: [1, seq_len, hidden_dim]
            // attentionMaskArr: [1, seq_len] -> expand to [1, seq_len, 1]
            INDArray expandedAttentionMask = attentionMaskArr.reshape(1, MAX_SEQUENCE_LENGTH, 1).castTo(hiddenStates.dataType());

            // Mask hidden states
            INDArray maskedHiddenStates = hiddenStates.mul(expandedAttentionMask);

            // Sum masked hidden states along sequence dimension
            INDArray sumHiddenStates = maskedHiddenStates.sum(true, 1); // sum along axis 1 (seq_len) -> [1, 1, hidden_dim]

            // Sum attention mask to get count of actual tokens
            INDArray sumAttentionMask = expandedAttentionMask.sum(true, 1); // sum along axis 1 -> [1, 1, 1]
            sumAttentionMask = Transforms.max(sumAttentionMask, 1e-9); // Avoid division by zero, ensure it's at least a small number

            // Mean pooling
            INDArray meanPooled = sumHiddenStates.divi(sumAttentionMask); // [1,1,hidden_dim]

            INDArray finalEmbedding = meanPooled.reshape(1, -1); // [1, hidden_dim]

            // L2 Normalize
            INDArray norm = finalEmbedding.norm2(true, 1);
            if(norm.isScalar() && norm.getDouble(0) == 0.0) {
                norm = Nd4j.scalar(finalEmbedding.dataType(), 1e-12);
            } else if (!norm.isScalar()) {
                norm.addi(1e-12);
            }
            INDArray normalizedEmbedding = finalEmbedding.divi(norm);

            return normalizedEmbedding.toFloatVector();

        } catch (Exception e) {
            // throw new RuntimeException("Error during SameDiff encoding for query: " + query, e);
            return null;
        }
    }
}