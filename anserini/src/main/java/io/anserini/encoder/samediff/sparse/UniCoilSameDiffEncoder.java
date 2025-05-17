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

package io.anserini.encoder.samediff.sparse;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import io.anserini.encoder.samediff.tokenizer.SamediffBertVocabulary;
import org.jetbrains.annotations.NotNull;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;


import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;

/**
 * SameDiff version of the UniCoil Encoder.
 * Assumes the SameDiff model graph mirrors the ONNX version's logic for producing token weights.
 */
public class UniCoilSameDiffEncoder extends SameDiffSparseEncoder {

    public static final String DEFAULT_MODEL_NAME = "unicoil.sd"; // Hypothetical .sd model name
    public static final String DEFAULT_MODEL_URL = "YOUR_MODEL_REPO_URL/unicoil.sd"; // Replace
    public static final String DEFAULT_VOCAB_NAME = "unicoil-vocab.txt"; // Often bert-base-uncased vocab
    public static final String DEFAULT_VOCAB_URL = "https://rgw.cs.uwaterloo.ca/pyserini/data/wordpiece-vocab.txt"; // From original

    // These must match the converted SameDiff model's graph
    public static final String INPUT_IDS_TENSOR_NAME = "input_ids"; // As per original UniCoilEncoder
    // UniCOIL ONNX model might only take input_ids. If attention_mask or token_type_ids are needed
    // for the SameDiff version, they should be added to inputTensorNamesForModel.
    // Let's assume only input_ids for now, similar to the ONNX version's direct input.
    public static final String OUTPUT_WEIGHTS_TENSOR_NAME = "output_weights"; // Hypothetical output name for token weights

    public static final int MAX_SEQUENCE_LENGTH = 512; // Default from many BERT models
    public static final boolean DO_LOWERCASE_AND_STRIP_ACCENTS = true; // Typically true for UniCOIL/SPLADE
    public static final boolean ADD_SPECIAL_TOKENS = true; // [CLS], [SEP] are used

    public UniCoilSameDiffEncoder() throws IOException, URISyntaxException {
        super(DEFAULT_MODEL_NAME, DEFAULT_MODEL_URL,
                DEFAULT_VOCAB_NAME, DEFAULT_VOCAB_URL,
                // UniCOIL ONNX model primarily uses 'input_ids'.
                // If your SameDiff conversion requires others (e.g. attention_mask for a full BERT backbone), add them.
                // For now, keeping it minimal. The tokenizerPreProcessor generates attention_mask anyway.
                List.of(INPUT_IDS_TENSOR_NAME, "attention_mask", "token_type_ids"), // Provide all standard BERT inputs
                Collections.singletonList(OUTPUT_WEIGHTS_TENSOR_NAME),
                DO_LOWERCASE_AND_STRIP_ACCENTS,
                MAX_SEQUENCE_LENGTH,
                ADD_SPECIAL_TOKENS);
    }

    @Override
    public Map<String, Integer> encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        // UniCOIL models often operate only on input_ids, but a full BERT backbone in SameDiff might use others.
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);

        Map<String, INDArray> placeholderMap = new HashMap<>();
        placeholderMap.put(INPUT_IDS_TENSOR_NAME, inputIdsArr);
        // Conditionally add other inputs if they are in inputTensorNamesForModel (defined in constructor)
        if (this.inputTensorNamesForModel.contains("attention_mask")) {
            placeholderMap.put("attention_mask", attentionMaskArr);
        }
        if (this.inputTensorNamesForModel.contains("token_type_ids")) {
            placeholderMap.put("token_type_ids", tokenTypeIdsArr);
        }

        try {
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray computedLogits = outputMap.get(this.outputTensorNamesFromModel.get(0)); // e.g., shape [1, seq_len, 1] or [1, seq_len, vocab_size]

            if (computedLogits == null) {
                return Collections.emptyMap();
            }

            // Post-processing for UniCOIL / SPLADE-like models:
            // 1. Apply ReLU and log(1+x) as per some SPLADE/UniCOIL variants.
            //    The original UniCoilEncoder doesn't show this explicitly for its ONNX output processing,
            //    it directly uses the output. We'll assume the SameDiff model output is "ready-to-use" weights
            //    per input token, or it's logits over vocab per token.
            //
            //    Let's assume output 'computedLogits' has shape [1, seq_len, vocab_size]
            //    And we need to get weights for the *input tokens*.
            //    Or, if it's simpler like the ONNX version [1, seq_len, 1], it's direct weights for input tokens.
            //    The original UniCoilEncoder ONNX output `results.get(0).getValue()` is `float[][][]` that is then flattened.
            //    This implies the output is likely token_activations of shape (batch_size, sequence_length, 1)

            INDArray tokenActivations = computedLogits; // Assume this is the direct output corresponding to input tokens
            if (tokenActivations.rank() == 3 && tokenActivations.shape()[2] == 1) { // [1, seq_len, 1]
                tokenActivations = tokenActivations.reshape(tokenActivations.shape()[0], tokenActivations.shape()[1]); // -> [1, seq_len]
            } else if (tokenActivations.rank() != 2 || tokenActivations.shape()[0] != 1) {
                // System.err.println("Unexpected output tensor shape for UniCoil: " + Arrays.toString(tokenActivations.shape()));
                return Collections.emptyMap();
            }

            // Apply ReLU
            INDArray reluOutput = Transforms.relu(tokenActivations, true);

            Map<String, Float> tokenWeightMap = new LinkedHashMap<>();
            // We need the original tokens that correspond to encoding.inputIds
            // The tokenizerPreProcessor.encode gives padded/truncated sequence.
            // We need to map weights from reluOutput back to the *original subword tokens*.

            // Get the actual tokens that were fed into the model (before padding)
            List<String> originalQueryTokens = new ArrayList<>();
            SamediffBertVocabulary vocab = this.tokenizerPreProcessor.getVocabulary(); // Need a getter for this
            if (ADD_SPECIAL_TOKENS) originalQueryTokens.add(SamediffBertVocabulary.CLS_TOKEN);
            originalQueryTokens.addAll(this.tokenizerPreProcessor.getWordPieceTokenizer().tokenize(query)); // Re-tokenize to get pre-special-token list
            if (ADD_SPECIAL_TOKENS) originalQueryTokens.add(SamediffBertVocabulary.SEP_TOKEN);

            // Truncate originalQueryTokens to match the actual input length to the model (before padding)
            int actualLength = 0;
            for(long id : encoding.inputIds){
                if(id != vocab.getTokenId(SamediffBertVocabulary.PAD_TOKEN)) actualLength++; else break;
            }
            if(originalQueryTokens.size() > actualLength) {
                originalQueryTokens = originalQueryTokens.subList(0, actualLength);
            }


            for (int i = 0; i < actualLength; ++i) {
                if (i >= reluOutput.columns()) break; // Should not happen if shapes align

                String token = vocab.getToken((int)encoding.inputIds[i]); // Get token string from ID
                // Filter out special tokens like [CLS], [SEP], [PAD] from the final output map
                if (token.equals(SamediffBertVocabulary.CLS_TOKEN) ||
                        token.equals(SamediffBertVocabulary.SEP_TOKEN) ||
                        token.equals(SamediffBertVocabulary.PAD_TOKEN)) {
                    continue;
                }

                float weight = reluOutput.getFloat(0, i);
                if (weight > 0) { // Only keep tokens with positive weight
                    // UniCOIL sums weights for identical tokens.
                    tokenWeightMap.put(token, tokenWeightMap.getOrDefault(token, 0.0f) + weight);
                }
            }
            return tokenWeightMap;

        } catch (Exception e) {
            // throw new RuntimeException("Error during UniCoil SameDiff encoding for query: " + query, e);
            return Collections.emptyMap();
        }
    }
}