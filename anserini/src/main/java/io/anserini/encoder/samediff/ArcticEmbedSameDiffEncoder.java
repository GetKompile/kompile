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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

public class ArcticEmbedSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(ArcticEmbedSameDiffEncoder.class);

    public static final String DEFAULT_MODEL_NAME = "nenglish-arctic-embed-l.sd";
    // IMPORTANT: Replace with actual URL if this model is to be downloadable
    public static final String DEFAULT_MODEL_URL = "https://PLACEHOLDER_MODEL_URL/nenglish-arctic-embed-l.sd";
    public static final String DEFAULT_VOCAB_NAME = "arctic-embed-l-vocab.txt"; // Or its actual vocab name
    // IMPORTANT: Replace with actual URL if this vocab is to be downloadable
    public static final String DEFAULT_VOCAB_URL = "https://PLACEHOLDER_VOCAB_URL/arctic-embed-l-vocab.txt";

    public static final String INPUT_IDS_TENSOR_NAME = "input_ids";
    public static final String ATTENTION_MASK_TENSOR_NAME = "attention_mask";
    public static final String TOKEN_TYPE_IDS_TENSOR_NAME = "token_type_ids"; // Or "segment_ids" if model expects that
    public static final String OUTPUT_EMBEDDING_TENSOR_NAME = "last_hidden_state"; // Common default for hidden states

    // Default tokenizer settings, verify these against the specific Arctic model requirements
    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 256;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    /**
     * Default constructor, uses default model/vocab names and URLs.
     * @throws IOException If model or vocab loading fails.
     * @throws URISyntaxException If URLs are malformed.
     */
    public ArcticEmbedSameDiffEncoder() throws IOException, URISyntaxException {
        this(null, null); // Use default model/vocab paths which will trigger download if URLs are valid
    }

    /**
     * Constructor allowing specification of local model and vocabulary paths.
     *
     * @param modelPath Local path to the SameDiff model. If null, uses default URL.
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
     *
     * @param modelName                  Filename of the SameDiff model.
     * @param modelUrl                   URL to download the SameDiff model (can be null if providedModelPath is set).
     * @param vocabName                  Filename of the vocabulary.
     * @param vocabUrl                   URL to download the vocabulary (can be null if providedVocabPath is set).
     * @param providedModelPath          Local path to the SameDiff model.
     * @param providedVocabPath          Local path to the vocabulary.
     * @param doLowerCaseAndStripAccents Whether to lowercase text and strip accents.
     * @param maxSequenceLength          Maximum sequence length for tokenization.
     * @param addSpecialTokens           Whether to add [CLS] and [SEP] tokens.
     * @throws IOException        If model or vocab loading fails.
     * @throws URISyntaxException If URLs are malformed.
     */
    public ArcticEmbedSameDiffEncoder(@NotNull String modelName, @Nullable String modelUrl,
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
        // Assumes inputTensorNamesForModel was set in the constructor in the order: ids, mask, type_ids
        placeholderMap.put(this.inputTensorNamesForModel.get(0), inputIdsArr);
        if (this.inputTensorNamesForModel.size() > 1) {
            placeholderMap.put(this.inputTensorNamesForModel.get(1), attentionMaskArr);
        }
        if (this.inputTensorNamesForModel.size() > 2) {
            placeholderMap.put(this.inputTensorNamesForModel.get(2), tokenTypeIdsArr);
        }

        try {
            Map<String, INDArray> outputMap = this.sameDiffModel.output(placeholderMap, this.outputTensorNamesFromModel.get(0));
            INDArray hiddenStates = outputMap.get(this.outputTensorNamesFromModel.get(0)); // e.g. last_hidden_state

            if (hiddenStates == null) {
                LOG.error("Output tensor '{}' not found in SameDiff model output for query: {}", this.outputTensorNamesFromModel.get(0), query);
                return null;
            }

            // Arctic and many sentence-transformers use mean pooling of last hidden state,
            // masked by attention_mask.
            if (hiddenStates.rank() != 3 || hiddenStates.shape()[0] != 1) { // Expect [1, seq_len, hidden_dim]
                LOG.warn("Unexpected hiddenStates tensor rank or batch size: {}. Expected [1, seq_len, hidden_dim], got {}.",
                        hiddenStates.rank(), Arrays.toString(hiddenStates.shape()));
                return null;
            }

            // Expand attention_mask to be broadcastable with hiddenStates for masking
            // hiddenStates: [1, seq_len, hidden_dim]
            // attentionMaskArr: [1, seq_len] -> expand to [1, seq_len, 1]
            // Use the actual sequence length from the attention mask for reshaping.
            long currentSequenceLength = attentionMaskArr.shape()[1];
            INDArray expandedAttentionMask = attentionMaskArr.reshape(1, currentSequenceLength, 1).castTo(hiddenStates.dataType());

            // Mask hidden states
            INDArray maskedHiddenStates = hiddenStates.mul(expandedAttentionMask);

            // Sum masked hidden states along sequence dimension
            INDArray sumHiddenStates = maskedHiddenStates.sum(true, 1); // sum along axis 1 (seq_len) -> [1, 1, hidden_dim]

            // Sum attention mask to get count of actual tokens
            INDArray sumAttentionMask = expandedAttentionMask.sum(true, 1); // sum along axis 1 -> [1, 1, 1]
            sumAttentionMask = Transforms.max(sumAttentionMask, Nd4j.scalar(sumAttentionMask.dataType(), 1e-9)); // Avoid division by zero

            // Mean pooling
            INDArray meanPooled = sumHiddenStates.divi(sumAttentionMask); // [1,1,hidden_dim]

            INDArray finalEmbedding = meanPooled.reshape(1, -1); // [1, hidden_dim]

            // L2 Normalize
            INDArray norm = finalEmbedding.norm2(true, 1);
            INDArray epsilon = Nd4j.scalar(finalEmbedding.dataType(), 1e-12);
            norm = norm.add(epsilon); // Add epsilon to prevent division by zero

            INDArray normalizedEmbedding = finalEmbedding.divi(norm);

            return normalizedEmbedding.toFloatVector();

        } catch (Exception e) {
            LOG.error("Error during SameDiff Arctic encoding for query: " + query, e);
            return null;
        }
    }
}