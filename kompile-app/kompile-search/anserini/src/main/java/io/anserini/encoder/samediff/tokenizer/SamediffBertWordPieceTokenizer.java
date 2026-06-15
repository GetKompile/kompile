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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SamediffBertWordPieceTokenizer {
    private final SamediffBertVocabulary vocab;
    private final SamediffBasicTokenizer basicTokenizer;

    public SamediffBertWordPieceTokenizer(SamediffBertVocabulary vocabulary, SamediffBasicTokenizer basicTokenizer) {
        this.vocab = vocabulary;
        this.basicTokenizer = basicTokenizer;
    }

    public List<String> tokenize(String text) {
        if (text == null) {
            return Collections.emptyList();
        }
        List<String> basicTokens = basicTokenizer.tokenize(text);
        List<String> wordPieceTokens = new ArrayList<>();
        for (String token : basicTokens) {
            wordPieceTokens.addAll(performWordPieceTokenization(token));
        }
        return wordPieceTokens;
    }

    private List<String> performWordPieceTokenization(String token) {
        List<String> outputTokens = new ArrayList<>();
        String currentSegment = token;

        if (vocab.containsToken(currentSegment)) {
            outputTokens.add(currentSegment);
            return outputTokens;
        }

        int start = 0;
        while (start < currentSegment.length()) {
            int end = currentSegment.length();
            String bestSubsegmentFound = null;

            while (end > start) {
                String subsegmentToTest = currentSegment.substring(start, end);
                if (start > 0) {
                    subsegmentToTest = "##" + subsegmentToTest;
                }

                if (vocab.containsToken(subsegmentToTest)) {
                    bestSubsegmentFound = subsegmentToTest;
                    break;
                }
                end--;
            }

            if (bestSubsegmentFound == null) {
                outputTokens.add(vocab.getUnknownTokenValue());
                break;
            }

            outputTokens.add(bestSubsegmentFound);
            start = end;
        }
        return outputTokens;
    }
}