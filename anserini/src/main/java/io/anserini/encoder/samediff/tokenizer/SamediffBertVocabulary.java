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

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SamediffBertVocabulary {

    private final Map<String, Integer> tokenToId;
    private final Map<Integer, String> idToToken;
    private final String unknownTokenValue;
    private final int unknownTokenId;

    public static final String DEFAULT_UNKNOWN_TOKEN = "[UNK]";
    public static final String PAD_TOKEN = "[PAD]";
    public static final String CLS_TOKEN = "[CLS]";
    public static final String SEP_TOKEN = "[SEP]";
    public static final String MASK_TOKEN = "[MASK]";

    /**
     * Default constructor for subclasses (used by TokenizerVocabularyWrapper)
     */
    protected SamediffBertVocabulary() {
        this.tokenToId = new HashMap<>();
        this.idToToken = new HashMap<>();
        this.unknownTokenValue = DEFAULT_UNKNOWN_TOKEN;
        this.unknownTokenId = 100; // Default BERT [UNK] token ID
    }

    public SamediffBertVocabulary(File vocabFile, String unknownTokenValue) throws IOException {
        this.tokenToId = new HashMap<>();
        this.idToToken = new HashMap<>();
        this.unknownTokenValue = unknownTokenValue != null ? unknownTokenValue : DEFAULT_UNKNOWN_TOKEN;

        int index = 0;
        try (InputStream is = new FileInputStream(vocabFile)) {
            List<String> lines = IOUtils.readLines(is, StandardCharsets.UTF_8);
            for (String line : lines) {
                String token = line.trim();
                if (!token.isEmpty()) {
                    tokenToId.put(token, index);
                    idToToken.put(index, token);
                    index++;
                }
            }
        }

        if (tokenToId.containsKey(this.unknownTokenValue)) {
            this.unknownTokenId = tokenToId.get(this.unknownTokenValue);
        } else {
            Integer padId = tokenToId.get(PAD_TOKEN);
            if (padId != null) {
                this.unknownTokenId = padId;
            } else if (!idToToken.isEmpty()){
                this.unknownTokenId = 0; // Default to 0 if UNK and PAD are missing
            } else {
                this.unknownTokenId = 0; // Should not happen with a valid vocab
            }
        }
    }

    public int getTokenId(String token) {
        return tokenToId.getOrDefault(token, unknownTokenId);
    }

    public String getToken(int id) {
        return idToToken.getOrDefault(id, unknownTokenValue);
    }

    public boolean containsToken(String token) {
        return tokenToId.containsKey(token);
    }

    public int getVocabSize() {
        return tokenToId.size();
    }

    public String getUnknownTokenValue() {
        return unknownTokenValue;
    }

    public int getUnknownTokenId() {
        return unknownTokenId;
    }

    public Set<String> getKnownTokensSet() {
        return tokenToId.keySet();
    }
}
