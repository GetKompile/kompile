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

// Location: kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-deeplearning4j/src/main/java/ai/kompile/pipelines/steps/deeplearning4j/nlp/
package ai.kompile.pipelines.steps.deeplearning4j.nlp;

import org.nd4j.linalg.api.ndarray.INDArray;
import java.util.List;
import java.util.Map;

public interface DL4JLLMTokenizer {

    /**
     * Initializes the tokenizer with a vocabulary URI and optional configuration.
     * @param vocabUri URI to the vocabulary file (e.g., "file:///path/to/vocab.txt").
     * Can be null if the tokenizer (like a dummy one) doesn't require an external vocab file.
     * @param config Additional configuration for the tokenizer (e.g., unknown token, subword prefix).
     * @throws Exception if initialization fails.
     */
    void initialize(String vocabUri, Map<String, String> config) throws Exception;

    /**
     * Encodes a single text string into an INDArray of token IDs.
     * @param text The text to encode.
     * @return INDArray of shape [1, sequenceLength] containing token IDs.
     */
    INDArray encode(String text);

    /**
     * Encodes a batch of text strings, handling padding and attention masks.
     * @param texts List of texts to encode.
     * @param addSpecialTokens whether to add special tokens (BOS/EOS) if applicable by the tokenizer's configuration.
     * @return Map containing:
     * "input_ids" (INDArray of shape [batchSize, sequenceLength])
     * "attention_mask" (INDArray of shape [batchSize, sequenceLength])
     */
    Map<String, INDArray> batchEncode(List<String> texts, boolean addSpecialTokens);


    /**
     * Decodes a single sequence of token IDs back into a text string.
     * @param tokenIds Array of token IDs.
     * @param skipSpecialTokens Whether to skip special tokens (like BOS, EOS, PAD) in the decoded string.
     * @return Decoded text string.
     */
    String decode(long[] tokenIds, boolean skipSpecialTokens);

    /**
     * Decodes a batch of token ID sequences from an INDArray.
     * @param batchTokenIds INDArray of shape [batchSize, sequenceLength].
     * @param skipSpecialTokens Whether to skip special tokens in the decoded strings.
     * @return List of decoded text strings.
     */
    List<String> batchDecode(INDArray batchTokenIds, boolean skipSpecialTokens);

    /**
     * Gets the vocabulary size.
     * @return The size of the vocabulary.
     */
    int getVocabSize();

    /**
     * Gets the ID for the End-Of-Sequence (EOS) token.
     * @return EOS token ID.
     */
    long getEosTokenId();

    /**
     * Gets the ID for the Beginning-Of-Sequence (BOS) token.
     * @return BOS token ID.
     */
    long getBosTokenId();

    /**
     * Gets the ID for the Padding (PAD) token.
     * @return PAD token ID.
     */
    long getPadTokenId();

    /**
     * Gets the ID for the Unknown (UNK) token.
     * @return UNK token ID.
     */
    long getUnkTokenId();

    /**
     * Gets the actual string representation for the EOS token.
     * @return EOS token string.
     */
    String getEosToken();

    /**
     * Gets the actual string representation for the BOS token.
     * @return BOS token string.
     */
    String getBosToken();

    /**
     * Gets the actual string representation for the PAD token.
     * @return PAD token string.
     */
    String getPadToken();

    /**
     * Gets the actual string representation for the UNK token.
     * @return UNK token string.
     */
    String getUnkToken();
}