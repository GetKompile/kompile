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
import org.tokenizers.bindings.Tokenizer;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Getter
public class SamediffBertTokenizerPreProcessor implements AutoCloseable {
    private final Tokenizer tokenizer;
    private final boolean addSpecialTokens;
    private final int maxLength;
    private final SamediffBertVocabulary vocabulary; // Maintain vocabulary access

    public static final String CLS_TOKEN = "[CLS]";
    public static final String SEP_TOKEN = "[SEP]";
    public static final String PAD_TOKEN = "[PAD]";

    /**
     * Create a tokenizer preprocessor from a tokenizer model file
     * @param modelPath Path to the tokenizer model file (e.g., tokenizer.json)
     * @param addSpecialTokens Whether to add special tokens ([CLS], [SEP])
     * @param maxLength Maximum sequence length (0 for no limit)
     * @throws IOException if the model file cannot be loaded
     */
    public SamediffBertTokenizerPreProcessor(String modelPath,
                                             boolean addSpecialTokens,
                                             int maxLength) throws IOException {
        this.tokenizer = new Tokenizer(modelPath);
        this.addSpecialTokens = addSpecialTokens;
        this.maxLength = maxLength;
        this.vocabulary = createVocabularyFromTokenizer();
    }

    /**
     * Create a tokenizer preprocessor from JSON configuration
     * @param jsonConfig JSON configuration string
     * @param addSpecialTokens Whether to add special tokens ([CLS], [SEP])
     * @param maxLength Maximum sequence length (0 for no limit)
     * @throws IOException if the JSON configuration is invalid
     */
    public static SamediffBertTokenizerPreProcessor fromJson(String jsonConfig,
                                                             boolean addSpecialTokens,
                                                             int maxLength) throws IOException {
        Tokenizer tokenizer = Tokenizer.fromJson(jsonConfig);
        return new SamediffBertTokenizerPreProcessor(tokenizer, addSpecialTokens, maxLength);
    }

    /**
     * Create from an existing tokenizer instance
     */
    private SamediffBertTokenizerPreProcessor(Tokenizer tokenizer,
                                              boolean addSpecialTokens,
                                              int maxLength) {
        this.tokenizer = tokenizer;
        this.addSpecialTokens = addSpecialTokens;
        this.maxLength = maxLength;
        this.vocabulary = createVocabularyFromTokenizer();
    }

    /**
     * Create a vocabulary wrapper from the tokenizer
     * This maintains compatibility with existing code that expects vocabulary access
     */
    private SamediffBertVocabulary createVocabularyFromTokenizer() {
        return new TokenizerVocabularyWrapper(this.tokenizer);
    }

    /**
     * Vocabulary wrapper that provides SamediffBertVocabulary interface
     * backed by the Rust tokenizer
     */
    private static class TokenizerVocabularyWrapper extends SamediffBertVocabulary {
        private final Tokenizer tokenizer;
        private final Map<String, Integer> tokenToIdCache;
        private final Map<Integer, String> idToTokenCache;
        private final long vocabSize;

        public TokenizerVocabularyWrapper(Tokenizer tokenizer) {
            super(); // Call parent constructor
            this.tokenizer = tokenizer;
            this.vocabSize = tokenizer.getVocabSize();
            this.tokenToIdCache = new HashMap<>();
            this.idToTokenCache = new HashMap<>();
            
            // Pre-populate common tokens
            cacheCommonTokens();
        }

        private void cacheCommonTokens() {
            String[] commonTokens = {
                "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"
            };
            
            for (String token : commonTokens) {
                try {
                    // Use tokenizer to get the actual token ID
                    int[] encoded = tokenizer.encode(token, false);
                    if (encoded.length > 0) {
                        int tokenId = encoded[0];
                        tokenToIdCache.put(token, tokenId);
                        idToTokenCache.put(tokenId, token);
                    }
                } catch (Exception e) {
                    // Token might not exist in vocabulary, skip silently
                }
            }
        }

        @Override
        public int getTokenId(String token) {
            // Check cache first
            Integer cached = tokenToIdCache.get(token);
            if (cached != null) {
                return cached;
            }

            try {
                // Use tokenizer to encode the token
                int[] encoded = tokenizer.encode(token, false);
                if (encoded.length > 0) {
                    int tokenId = encoded[0];
                    tokenToIdCache.put(token, tokenId);
                    return tokenId;
                }
            } catch (Exception e) {
                // Fall through to unknown token
            }

            // Return unknown token ID (typically token ID for "[UNK]")
            return getUnknownTokenId();
        }

        @Override
        public String getToken(int id) {
            // Check cache first
            String cached = idToTokenCache.get(id);
            if (cached != null) {
                return cached;
            }

            try {
                // Use tokenizer to decode the ID
                String decoded = tokenizer.decode(new int[]{id}, false);
                if (decoded != null && !decoded.isEmpty()) {
                    idToTokenCache.put(id, decoded);
                    return decoded;
                }
            } catch (Exception e) {
                // Fall through to unknown token
            }

            return getUnknownTokenValue();
        }

        @Override
        public boolean containsToken(String token) {
            try {
                int[] encoded = tokenizer.encode(token, false);
                if (encoded.length > 0) {
                    // Verify by decoding back
                    String decoded = tokenizer.decode(encoded, false);
                    return token.equals(decoded.trim());
                }
            } catch (Exception e) {
                // Token doesn't exist
            }
            return false;
        }

        @Override
        public int getVocabSize() {
            return (int) vocabSize;
        }

        @Override
        public String getUnknownTokenValue() {
            return "[UNK]"; // Standard BERT unknown token
        }

        @Override
        public int getUnknownTokenId() {
            // Try to get the actual [UNK] token ID
            Integer unkId = tokenToIdCache.get("[UNK]");
            if (unkId != null) {
                return unkId;
            }
            
            // Default fallback
            return 100; // Typical BERT [UNK] token ID
        }

        // Additional method to access the underlying tokenizer if needed
        public Tokenizer getUnderlyingTokenizer() {
            return tokenizer;
        }
    }

    /**
     * BERT-style encoding result containing input IDs, attention mask, and token type IDs
     */
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

    /**
     * Encode text into BERT-style format with padding and attention masks
     * @param text Input text to encode
     * @return BertEncoding containing input IDs, attention mask, and token type IDs
     */
    public BertEncoding encode(String text) {
        if (text == null || text.trim().isEmpty()) {
            return createEmptyEncoding();
        }

        // Use the Rust tokenizer to encode the text
        int[] tokenIds = tokenizer.encode(text, addSpecialTokens);
        
        // Handle max length truncation and padding
        int actualLength = maxLength > 0 ? maxLength : tokenIds.length;
        long[] inputIds = new long[actualLength];
        long[] attentionMask = new long[actualLength];
        long[] tokenTypeIds = new long[actualLength];

        // Copy token IDs up to max length
        int copyLength = Math.min(tokenIds.length, actualLength);
        for (int i = 0; i < copyLength; i++) {
            inputIds[i] = tokenIds[i];
            attentionMask[i] = 1L;
            tokenTypeIds[i] = 0L;
        }

        // If we have max length and need to ensure proper BERT format
        if (maxLength > 0 && copyLength < actualLength) {
            // Get PAD token ID from vocabulary
            long padTokenId = vocabulary.getTokenId(PAD_TOKEN);
            
            // Fill remaining positions with PAD tokens
            for (int i = copyLength; i < actualLength; i++) {
                inputIds[i] = padTokenId;
                attentionMask[i] = 0L;
                tokenTypeIds[i] = 0L;
            }
        }

        return new BertEncoding(inputIds, attentionMask, tokenTypeIds);
    }

    /**
     * Encode text and return detailed encoding information including tokens and offsets
     * @param text Input text to encode
     * @return Detailed encoding result
     */
    public Tokenizer.EncodingResult encodeWithDetails(String text) {
        return tokenizer.encodeWithDetails(text, addSpecialTokens);
    }

    /**
     * Decode token IDs back to text
     * @param tokenIds Array of token IDs
     * @return Decoded text
     */
    public String decode(int[] tokenIds) {
        return tokenizer.decode(tokenIds, true); // Skip special tokens by default
    }

    /**
     * Decode token IDs back to text
     * @param tokenIds Array of token IDs
     * @param skipSpecialTokens Whether to skip special tokens in output
     * @return Decoded text
     */
    public String decode(int[] tokenIds, boolean skipSpecialTokens) {
        return tokenizer.decode(tokenIds, skipSpecialTokens);
    }

    /**
     * Get the vocabulary size of the tokenizer
     * @return Vocabulary size
     */
    public long getVocabSize() {
        return tokenizer.getVocabSize();
    }

    /**
     * Check if the tokenizer is valid
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        return tokenizer.isValid();
    }

    /**
     * Create an empty encoding for null/empty input
     */
    private BertEncoding createEmptyEncoding() {
        if (maxLength <= 0) {
            return new BertEncoding(new long[0], new long[0], new long[0]);
        }

        long[] inputIds = new long[maxLength];
        long[] attentionMask = new long[maxLength];
        long[] tokenTypeIds = new long[maxLength];

        if (addSpecialTokens && maxLength >= 2) {
            // For empty input with special tokens: [CLS] [SEP] [PAD]...
            inputIds[0] = vocabulary.getTokenId(CLS_TOKEN);
            inputIds[1] = vocabulary.getTokenId(SEP_TOKEN);
            attentionMask[0] = 1L;
            attentionMask[1] = 1L;
            // Rest remain 0 (PAD tokens with no attention)
        }

        return new BertEncoding(inputIds, attentionMask, tokenTypeIds);
    }

    @Override
    public void close() {
        if (tokenizer != null) {
            tokenizer.close();
        }
    }

    /**
     * Get version information from the underlying tokenizer
     * @return Version string
     */
    public static String getVersion() {
        return Tokenizer.getVersion();
    }

    /**
     * Get build information from the underlying tokenizer
     * @return Build info string
     */
    public static String getBuildInfo() {
        return Tokenizer.getBuildInfo();
    }

    /**
     * Check if a model file is valid
     * @param modelPath Path to model file
     * @return true if valid, false otherwise
     */
    public static boolean isValidModelFile(String modelPath) {
        return Tokenizer.isValidModelFile(modelPath);
    }
}
