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

package io.anserini.encoder.samediff.tokenizer;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Getter
public class SamediffBertTokenizerPreProcessor {
    private final SamediffBertWordPieceTokenizer wordPieceTokenizer;
    private final SamediffBertVocabulary vocabulary;
    private final boolean addSpecialTokens;
    private final int maxLength;

    public static final String CLS_TOKEN = SamediffBertVocabulary.CLS_TOKEN;
    public static final String SEP_TOKEN = SamediffBertVocabulary.SEP_TOKEN;
    public static final String PAD_TOKEN = SamediffBertVocabulary.PAD_TOKEN;


    public SamediffBertTokenizerPreProcessor(SamediffBertVocabulary vocabulary,
                                             boolean doLowerCaseAndStripAccents,
                                             boolean addSpecialTokens,
                                             int maxLength) {
        this.vocabulary = vocabulary;
        SamediffBasicTokenizer basicTokenizer = new SamediffBasicTokenizer(doLowerCaseAndStripAccents, doLowerCaseAndStripAccents);
        this.wordPieceTokenizer = new SamediffBertWordPieceTokenizer(this.vocabulary, basicTokenizer);
        this.addSpecialTokens = addSpecialTokens;
        this.maxLength = maxLength;
    }

    public static class BertEncoding {
        public final long[] inputIds;
        public final long[] attentionMask;
        public final long[] tokenTypeIds;

        public BertEncoding(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
            this.tokenTypeIds = tokenTypeIds;
        }
    }

    public BertEncoding encode(String text) {
        List<String> tokens = wordPieceTokenizer.tokenize(text);

        List<String> processedTokens = new ArrayList<>();
        if (addSpecialTokens) {
            processedTokens.add(CLS_TOKEN);
        }
        processedTokens.addAll(tokens);

        int maxTokensForContext = addSpecialTokens ? maxLength - 2 : maxLength;
        int currentContextLength = processedTokens.size() - (addSpecialTokens ? 1 : 0);

        if (currentContextLength > maxTokensForContext) {
            processedTokens = new ArrayList<>(processedTokens.subList(0, (addSpecialTokens ? 1 : 0) + maxTokensForContext));
        }

        if (addSpecialTokens) {
            if (processedTokens.size() < maxLength && maxLength > 0) {
                processedTokens.add(SEP_TOKEN);
            } else if (maxLength > 0 && processedTokens.size() == maxLength) {
                processedTokens.set(maxLength - 1, SEP_TOKEN);
            } else if (maxLength == 1 && processedTokens.size() == 1 && processedTokens.get(0).equals(CLS_TOKEN)) {
                // If max length is 1 and only CLS is present, replace CLS with SEP (BERT edge case for very short max_len)
                // or handle as an error/invalid state depending on desired behavior.
                // For now, let's assume maxLength is generally > 1 if special tokens are added.
                // If maxLength is 1 and addSpecialTokens is true, this could be just [SEP] or error.
                // Keeping it simple: if it's exactly at maxLength, the last token becomes SEP.
            }
        }

        if (processedTokens.size() > maxLength && maxLength > 0) {
            processedTokens = processedTokens.subList(0, maxLength);
            if (addSpecialTokens && processedTokens.size() == maxLength) { // Re-check SEP if truncation happened
                if (!processedTokens.get(processedTokens.size() -1).equals(SEP_TOKEN) && vocabulary.containsToken(SEP_TOKEN)) {
                    processedTokens.set(processedTokens.size() -1, SEP_TOKEN);
                }
            }
        } else if (processedTokens.isEmpty() && maxLength > 0 && addSpecialTokens) {
            // Handle empty input with special tokens: [CLS], [SEP], [PAD]...
            if (maxLength >= 2) {
                processedTokens.add(CLS_TOKEN);
                processedTokens.add(SEP_TOKEN);
            } else if (maxLength == 1) {
                processedTokens.add(CLS_TOKEN); // Or SEP_TOKEN, depending on convention for extremely short
            }
        }


        long[] inputIds = new long[maxLength];
        long[] attentionMask = new long[maxLength];
        long[] tokenTypeIds = new long[maxLength];

        long padTokenId = vocabulary.getTokenId(PAD_TOKEN);
        Arrays.fill(inputIds, padTokenId);
        Arrays.fill(attentionMask, 0L);
        Arrays.fill(tokenTypeIds, 0L);

        for (int i = 0; i < processedTokens.size(); i++) {
            if (i < maxLength) {
                inputIds[i] = vocabulary.getTokenId(processedTokens.get(i));
                attentionMask[i] = 1L;
            }
        }
        return new BertEncoding(inputIds, attentionMask, tokenTypeIds);
    }
}