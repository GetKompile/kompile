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

// Assuming Anserini might have a common Encoder interface, else remove 'implements Encoder'
// import io.anserini.encoder.Encoder;
import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.jetbrains.annotations.NotNull;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// public class CosDprDistilSameDiffEncoder extends SameDiffEncoder<float[]> implements Encoder {
public class CosDprDistilSameDiffEncoder extends SameDiffEncoder<float[]> {

    // Model details from the original CosDprDistilEncoder
    // IMPORTANT: These URLs must be updated to point to your converted SameDiff models (.sd files)
    // and ensure the vocab.txt is compatible.
    public static final String DEFAULT_MODEL_NAME = "cos-dpr-distil.sd"; // Hypothetical .sd model name
    public static final String DEFAULT_MODEL_URL = "YOUR_MODEL_REPO_URL/cos-dpr-distil.sd"; // Replace with actual URL
    public static final String DEFAULT_VOCAB_NAME = "cos-dpr-distil-vocab.txt"; // Or standard bert-base-uncased vocab
    public static final String DEFAULT_VOCAB_URL = "https://huggingface.co/sentence-transformers/cos-dpr-distil/resolve/main/vocab.txt";


    // These tensor names must match the converted SameDiff model's graph.
    // Common names for BERT-like models:
    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids"; // Often not critical for single sentences
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "last_hidden_state"; // Or "pooler_output" or specific embedding name

    public static final int MAX_SEQUENCE_LENGTH = 512; // Common for many BERT models
    public static final boolean DO_LOWERCASE_AND_STRIP_ACCENTS = true; // Most sentence-transformers are uncased
    public static final boolean ADD_SPECIAL_TOKENS = true; // [CLS], [SEP]

    public CosDprDistilSameDiffEncoder() throws IOException, URISyntaxException {
        super(DEFAULT_MODEL_NAME, DEFAULT_MODEL_URL,
                DEFAULT_VOCAB_NAME, DEFAULT_VOCAB_URL,
                List.of(INPUT_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME),
                Collections.singletonList(OUTPUT_EMBEDDING_TENSOR_NAME),
                DO_LOWERCASE_AND_STRIP_ACCENTS,
                MAX_SEQUENCE_LENGTH,
                ADD_SPECIAL_TOKENS);
    }

    @Override
    public Map<String, Integer> encode(@NotNull String query) {
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
            INDArray embeddingTensor = outputMap.get(this.outputTensorNamesFromModel.get(0));

            if (embeddingTensor == null) {
                // Consider throwing a specific runtime exception
                return null;
            }

            // Sentence Transformers often produce a pooled output or use the CLS token.
            // If output is [batch, seq_len, hidden_dim], take CLS.
            // If output is [batch, hidden_dim], it's likely already pooled.
            INDArray finalEmbedding;
            if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1 && embeddingTensor.shape()[1] > 0) {
                // Assuming CLS token pooling (index 0 of sequence_length)
                finalEmbedding = embeddingTensor.get(NDArrayIndex.point(0), NDArrayIndex.point(0), NDArrayIndex.all());
            } else if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
                finalEmbedding = embeddingTensor; // Assumes output is already [1, hidden_size]
            } else {
                // System.err.println("Unexpected embedding tensor shape: " + Arrays.toString(embeddingTensor.shape()));
                return null; // Or throw exception
            }

            finalEmbedding = finalEmbedding.reshape(1, -1); // Ensure [1, hidden_size]

            // L2 Normalize the embedding
            INDArray norm = finalEmbedding.norm2(true, 1); // Norm along dimension 1
            if(norm.isScalar() && norm.getDouble(0) == 0.0) {
                norm = Nd4j.scalar(finalEmbedding.dataType(), 1e-12);
            } else if (!norm.isScalar()) {
                norm.addi(1e-12);
            }
            INDArray normalizedEmbedding = finalEmbedding.divi(norm);

            return normalizedEmbedding.toFloatVector();

        } catch (Exception e) {
            // Consider re-throwing as a RuntimeException or a custom unchecked exception
            // e.printStackTrace(); 
            throw new RuntimeException("Error during SameDiff encoding for query: " + query, e);
        }
    }
}