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
import org.nd4j.linalg.ops.transforms.Transforms;
import org.nd4j.linalg.indexing.NDArrayIndex;


import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ArcticEmbedSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(ArcticEmbedSameDiffEncoder.class);

    // --- UPDATED CONSTANTS from original ArcticEmbedLEncoder ---
    public static final String DEFAULT_MODEL_NAME = "snowflake-arctic-embed-l-official.onnx";
    public static final String DEFAULT_MODEL_URL = "https://rgw.cs.uwaterloo.ca/pyserini/data/arctic-embed-l-official.onnx";
    public static final String DEFAULT_VOCAB_NAME = "snowflake-arctic-embed-l-vocab.txt";
    public static final String DEFAULT_VOCAB_URL = "https://rgw.cs.uwaterloo.ca/pyserini/data/arctic-embed-l-official-vocab.txt";
    private static final String INSTRUCTION = "Represent this sentence for searching relevant passages: ";
    // --- END UPDATED CONSTANTS ---

    // Input and Output tensor names for the ONNX model.
    // These must match the names in the ONNX graph or how OnnxFrameworkImporter maps them.
    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids";
    // Original ArcticEmbedLEncoder (ONNX Runtime) gets output by index: results.get(0).getValue()
    // OnnxFrameworkImporter might name it "output_0", "last_hidden_state" or similar.
    // This needs to be verified. For now, using "last_hidden_state" as a common convention,
    // but this is a critical point to check for your specific ONNX model.
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "last_hidden_state";


    // Default tokenizer settings
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true; // Common for BERT, verify if Arctic differs
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512; // Original ArcticEmbedLEncoder uses 512
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true; // CLS/SEP

    /**
     * Default constructor, uses default model/vocab names and URLs.
     * @throws IOException If model or vocab loading fails.
     * @throws URISyntaxException If URLs are malformed.
     */
    public ArcticEmbedSameDiffEncoder() throws IOException, URISyntaxException {
        this(null, null);
    }

    /**
     * Constructor allowing specification of local model and vocabulary paths.
     *
     * @param modelPath Local path to the ONNX model. If null, uses default URL.
     * @param vocabPath Local path to the vocabulary. If null, uses default URL.
     * @throws IOException        If model or vocab loading fails.
     * @throws URISyntaxException If URLs are malformed.
     */
    public ArcticEmbedSameDiffEncoder(@Nullable String modelPath, @Nullable String vocabPath) throws IOException, URISyntaxException {
        this(DEFAULT_MODEL_NAME, modelPath == null ? DEFAULT_MODEL_URL : null,
                DEFAULT_VOCAB_NAME, vocabPath == null ? DEFAULT_VOCAB_URL : null,
                modelPath, vocabPath,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS);
    }

    /**
     * Full constructor allowing override of all parameters including model/vocab details and tokenizer settings.
     */
    public ArcticEmbedSameDiffEncoder(@NotNull String modelName, @Nullable String modelUrl,
                                      @NotNull String vocabName, @Nullable String vocabUrl,
                                      @Nullable String providedModelPath, @Nullable String providedVocabPath,
                                      boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens)
            throws IOException, URISyntaxException {
        super(modelName, modelUrl, vocabName, vocabUrl,
                providedModelPath, providedVocabPath,
                List.of(INPUT_IDS_TENSOR_NAME, TOKEN_TYPE_IDS_TENSOR_NAME, ATTENTION_MASK_TENSOR_NAME), // Ensure this order matches ONNX model if it's strict
                Collections.singletonList(OUTPUT_EMBEDDING_TENSOR_NAME),
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
    }

    @Override
    public float[] encode(@NotNull String query) {
        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(INSTRUCTION + query);

        INDArray inputIdsArr = Nd4j.create(new long[][]{encoding.inputIds}).castTo(DataType.INT64);
        INDArray tokenTypeIdsArr = Nd4j.create(new long[][]{encoding.tokenTypeIds}).castTo(DataType.INT64);
        INDArray attentionMaskArr = Nd4j.create(new long[][]{encoding.attentionMask}).castTo(DataType.INT64);


        Map<String, INDArray> placeholderMap = new HashMap<>();
        // Ensure these names match the inputTensorNamesForModel list order and the ONNX graph's expectations
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);       // input_ids
        placeholderMap.put(this.inputTensorNamesForModel.get(1), tokenTypeIdsArr);  // token_type_ids
        placeholderMap.put(this.inputTensorNamesForModel.get(2), attentionMaskArr); // attention_mask


        try {
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray hiddenStates = outputMap.get(this.outputTensorNamesFromModel.get(0));

            if (hiddenStates == null) {
                LOG.error("Output tensor '{}' not found in SameDiff model output for query: {}", this.outputTensorNamesFromModel.get(0), query);
                return null;
            }

            // Arctic models often use mean pooling of the last hidden state, masked by attention_mask.
            // This specific logic is retained from your original ArcticEmbedSameDiffEncoder.
            // It assumes 'hiddenStates' is [batch, seq_len, hidden_dim].
            if (hiddenStates.rank() != 3 || hiddenStates.shape()[0] != 1) {
                LOG.warn("Unexpected hiddenStates tensor rank or batch size: {}. Expected [1, seq_len, hidden_dim], got {}. Attempting to adapt.",
                        hiddenStates.rank(), Arrays.toString(hiddenStates.shape()));
                // Attempt to adapt if possible, e.g. if it's [seq_len, hidden_dim] for a single item
                if(hiddenStates.rank() == 2 && hiddenStates.shape()[0] == inputIdsArr.shape()[1]) {
                    hiddenStates = hiddenStates.reshape(1, hiddenStates.shape()[0], hiddenStates.shape()[1]);
                } else if (hiddenStates.rank() == 2 && hiddenStates.shape()[0] == 1) { // Directly [1, hidden_dim] - means pooling might be done
                    LOG.info("Hidden states tensor is [1, hidden_dim], assuming pooling is already done by the model.");
                    INDArray finalEmbedding = hiddenStates; // Already [1, hidden_dim]
                    INDArray norm = finalEmbedding.norm2(true, 1);
                    INDArray epsilon = Nd4j.scalar(finalEmbedding.dataType(), 1e-12f);
                    norm = Transforms.max(norm, epsilon);
                    INDArray normalizedEmbedding = finalEmbedding.divi(norm);
                    return normalizedEmbedding.toFloatVector();
                }
                else {
                    LOG.error("Cannot adapt hiddenStates tensor shape for Arctic pooling.");
                    return null;
                }
            }

            long currentSequenceLength = attentionMaskArr.shape()[1];
            INDArray expandedAttentionMask = attentionMaskArr.reshape(1, currentSequenceLength, 1).castTo(hiddenStates.dataType());
            INDArray maskedHiddenStates = hiddenStates.mul(expandedAttentionMask);
            INDArray sumHiddenStates = maskedHiddenStates.sum(true, 1);
            INDArray sumAttentionMask = expandedAttentionMask.sum(true, 1);
            sumAttentionMask = Transforms.max(sumAttentionMask, Nd4j.scalar(sumAttentionMask.dataType(), 1e-9));
            INDArray meanPooled = sumHiddenStates.divi(sumAttentionMask);
            INDArray finalEmbedding = meanPooled.reshape(1, -1);

            // L2 Normalize (as in original ONNX Runtime ArcticEmbedLEncoder)
            INDArray norm = finalEmbedding.norm2(true, 1);
            INDArray epsilon = Nd4j.scalar(finalEmbedding.dataType(), 1e-12f);
            norm = Transforms.max(norm, epsilon); // Avoid division by zero

            INDArray normalizedEmbedding = finalEmbedding.divi(norm);
            return normalizedEmbedding.toFloatVector();

        } catch (Exception e) {
            LOG.error("Error during SameDiff Arctic encoding for query: " + query, e);
            return null;
        }
    }
}