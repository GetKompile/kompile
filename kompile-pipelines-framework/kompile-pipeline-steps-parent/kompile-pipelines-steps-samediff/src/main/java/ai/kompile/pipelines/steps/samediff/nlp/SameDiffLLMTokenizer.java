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

// Location: kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-samediff/src/main/java/ai/kompile/pipelines/steps/samediff/nlp/
package ai.kompile.pipelines.steps.samediff.nlp;

import org.nd4j.linalg.api.ndarray.INDArray;
import java.util.List;
import java.util.Map;

/**
 * Interface for tokenizers to be used with SameDiff-based Language Model steps.
 * This interface is independent of any Deeplearning4j-specific tokenizer implementations.
 */
public interface SameDiffLLMTokenizer {

    /**
     * Initializes the tokenizer.
     * @param vocabUri URI to the vocabulary file. Can be null if the tokenizer is self-contained or for testing.
     * @param config Tokenizer-specific configuration map (e.g., special token strings, subword prefix).
     * @throws Exception If initialization fails.
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
     * @param addSpecialTokens Whether to add special tokens (BOS/EOS) as per tokenizer's configuration.
     * @return Map containing "input_ids" (INDArray [batchSize, sequenceLength])
     * and "attention_mask" (INDArray [batchSize, sequenceLength]).
     */
    Map<String, INDArray> batchEncode(List<String> texts, boolean addSpecialTokens);

    /**
     * Decodes a single sequence of token IDs back into a text string.
     * @param tokenIds Array of token IDs.
     * @param skipSpecialTokens Whether to skip special tokens in the decoded string.
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

    int getVocabSize();
    long getEosTokenId();
    long getBosTokenId();
    long getPadTokenId();
    long getUnkTokenId();
    String getEosToken();
    String getBosToken();
    String getPadToken();
    String getUnkToken();
}
