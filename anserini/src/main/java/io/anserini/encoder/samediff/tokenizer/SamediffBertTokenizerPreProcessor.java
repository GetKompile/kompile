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
import ai.kompile.bindings.Tokenizer;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
     * Legacy constructor that accepts a SamediffBertVocabulary (for backward compatibility)
     * This constructor migrates the vocabulary to the new tokenizer format
     * @param vocabulary Existing SamediffBertVocabulary instance
     * @param doLowerCaseAndStripAccents Whether to apply lowercase and strip accents
     * @param addSpecialTokens Whether to add special tokens ([CLS], [SEP])
     * @param maxLength Maximum sequence length (0 for no limit)
     * @throws IOException if tokenizer creation fails
     */
    public SamediffBertTokenizerPreProcessor(SamediffBertVocabulary vocabulary,
                                             boolean doLowerCaseAndStripAccents,
                                             boolean addSpecialTokens,
                                             int maxLength) throws IOException {
        this.addSpecialTokens = addSpecialTokens;
        this.maxLength = maxLength;
        this.vocabulary = vocabulary;

        // Create tokenizer configuration from the existing vocabulary
        String tokenizerConfig = createConfigFromVocabulary(vocabulary, doLowerCaseAndStripAccents);
        this.tokenizer = Tokenizer.fromJson(tokenizerConfig);
    }

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
     * Create a tokenizer configuration JSON from an existing SamediffBertVocabulary
     */
    private String createConfigFromVocabulary(SamediffBertVocabulary vocab, boolean doLowerCase) {
        StringBuilder configBuilder = new StringBuilder();
        configBuilder.append("{\n");
        configBuilder.append("  \"version\": \"1.0\",\n");
        
        // Model configuration
        configBuilder.append("  \"model\": {\n");
        configBuilder.append("    \"type\": \"WordPiece\",\n");
        configBuilder.append("    \"unk_token\": \"").append(vocab.getUnknownTokenValue()).append("\",\n");
        configBuilder.append("    \"continuing_subword_prefix\": \"##\",\n");
        configBuilder.append("    \"max_input_chars_per_word\": 100,\n");
        configBuilder.append("    \"vocab\": {\n");
        
        // Build vocabulary from the existing vocabulary
        Set<String> knownTokens = vocab.getKnownTokensSet();
        boolean first = true;
        for (String token : knownTokens) {
            if (!first) {
                configBuilder.append(",\n");
            }
            int tokenId = vocab.getTokenId(token);
            configBuilder.append("      \"").append(escapeJson(token)).append("\": ").append(tokenId);
            first = false;
        }
        
        configBuilder.append("\n    }\n");
        configBuilder.append("  }");
        
        // Add normalizer if needed
        if (doLowerCase) {
            configBuilder.append(",\n");
            configBuilder.append("  \"normalizer\": {\n");
            configBuilder.append("    \"type\": \"Sequence\",\n");
            configBuilder.append("    \"normalizers\": [\n");
            configBuilder.append("      {\"type\": \"NFD\"},\n");
            configBuilder.append("      {\"type\": \"StripAccents\"},\n");
            configBuilder.append("      {\"type\": \"Lowercase\"},\n");
            configBuilder.append("      {\"type\": \"NFC\"}\n");
            configBuilder.append("    ]\n");
            configBuilder.append("  }");
        }
        
        // Add pre-tokenizer
        configBuilder.append(",\n");
        configBuilder.append("  \"pre_tokenizer\": {\n");
        configBuilder.append("    \"type\": \"BertPreTokenizer\"\n");
        configBuilder.append("  }");
        
        // Add post-processor
        configBuilder.append(",\n");
        configBuilder.append("  \"post_processor\": {\n");
        configBuilder.append("    \"type\": \"BertProcessing\",\n");
        configBuilder.append("    \"sep\": [\"[SEP]\", ").append(vocab.getTokenId("[SEP]")).append("],\n");
        configBuilder.append("    \"cls\": [\"[CLS]\", ").append(vocab.getTokenId("[CLS]")).append("]\n");
        configBuilder.append("  }");
        
        // Add decoder
        configBuilder.append(",\n");
        configBuilder.append("  \"decoder\": {\n");
        configBuilder.append("    \"type\": \"WordPiece\",\n");
        configBuilder.append("    \"prefix\": \"##\",\n");
        configBuilder.append("    \"cleanup\": true\n");
        configBuilder.append("  }\n");
        
        configBuilder.append("}");
        
        return configBuilder.toString();
    }

    /**
     * Escape JSON strings properly
     */
    private String escapeJson(String str) {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
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

        // Maximum cache size to prevent unbounded memory growth during long indexing jobs
        private static final int MAX_CACHE_SIZE = 10000;

        public TokenizerVocabularyWrapper(Tokenizer tokenizer) {
            super(); // Call parent constructor
            this.tokenizer = tokenizer;
            this.vocabSize = tokenizer.getVocabSize();

            // Use LRU cache with bounded size to prevent memory leaks during long jobs
            this.tokenToIdCache = Collections.synchronizedMap(
                new LinkedHashMap<String, Integer>(MAX_CACHE_SIZE + 1, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                        return size() > MAX_CACHE_SIZE;
                    }
                });
            this.idToTokenCache = Collections.synchronizedMap(
                new LinkedHashMap<Integer, String>(MAX_CACHE_SIZE + 1, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                        return size() > MAX_CACHE_SIZE;
                    }
                });

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

        @Override
        public Set<String> getKnownTokensSet() {
            // This is expensive to compute for large vocabularies, but needed for compatibility
            // In practice, you might want to cache this or provide a lazy implementation
            Set<String> tokens = new java.util.HashSet<>();
            
            // Add cached tokens
            tokens.addAll(tokenToIdCache.keySet());
            
            // Note: For a complete implementation, you'd need to iterate through all token IDs
            // This is a simplified version that includes common tokens
            return tokens;
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

    /**
     * Initiates shutdown of the tokenizer, rejecting new operations.
     * Existing operations will be allowed to complete.
     * <p>
     * This is useful for graceful shutdown scenarios where you want to
     * stop accepting new work before fully closing the tokenizer.
     */
    public void initiateShutdown() {
        if (tokenizer != null) {
            tokenizer.initiateShutdown();
        }
    }

    /**
     * Check if the tokenizer is currently shutting down.
     * @return true if shutdown has been initiated
     */
    public boolean isShuttingDown() {
        return tokenizer != null && tokenizer.isShuttingDown();
    }

    /**
     * Get the number of active operations on the tokenizer.
     * Useful for monitoring shutdown progress.
     * @return count of active operations, or 0 if tokenizer is null
     */
    public int getActiveOperationCount() {
        return tokenizer != null ? tokenizer.getActiveOperationCount() : 0;
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
