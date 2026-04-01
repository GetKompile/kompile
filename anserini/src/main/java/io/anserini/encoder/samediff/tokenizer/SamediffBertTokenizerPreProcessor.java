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
import org.eclipse.deeplearning4j.llm.tokenizer.Encoding;
import org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Getter
public class SamediffBertTokenizerPreProcessor implements AutoCloseable {
    private final HuggingFaceTokenizer tokenizer;
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
        this.tokenizer = HuggingFaceTokenizer.fromJson(tokenizerConfig);
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
        this.tokenizer = HuggingFaceTokenizer.fromFile(modelPath);
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
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.fromJson(jsonConfig);
        return new SamediffBertTokenizerPreProcessor(tokenizer, addSpecialTokens, maxLength);
    }

    /**
     * Create from an existing tokenizer instance
     */
    private SamediffBertTokenizerPreProcessor(HuggingFaceTokenizer tokenizer,
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
        private final HuggingFaceTokenizer tokenizer;
        private final Map<String, Integer> tokenToIdCache;
        private final Map<Integer, String> idToTokenCache;
        private final long vocabSize;

        // Maximum cache size to prevent unbounded memory growth during long indexing jobs
        private static final int MAX_CACHE_SIZE = 10000;

        public TokenizerVocabularyWrapper(HuggingFaceTokenizer tokenizer) {
            super(); // Call parent constructor
            this.tokenizer = tokenizer;
            this.vocabSize = (long) tokenizer.getVocabSize();

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
                    int[] encoded = tokenizer.encode(token, false).getIds();
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
                int[] encoded = tokenizer.encode(token, false).getIds();
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
                int[] encoded = tokenizer.encode(token, false).getIds();
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
        public HuggingFaceTokenizer getUnderlyingTokenizer() {
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

        // Getters for compatibility with code expecting method access
        public long[] inputIds() { return inputIds; }
        public long[] attentionMask() { return attentionMask; }
        public long[] tokenTypeIds() { return tokenTypeIds; }

        // Helper methods for ND4J compatibility (expects int[])
        public int[] inputIdsAsInt() {
            int[] result = new int[inputIds.length];
            for (int i = 0; i < inputIds.length; i++) {
                result[i] = (int) inputIds[i];
            }
            return result;
        }

        public int[] attentionMaskAsInt() {
            int[] result = new int[attentionMask.length];
            for (int i = 0; i < attentionMask.length; i++) {
                result[i] = (int) attentionMask[i];
            }
            return result;
        }

        public int[] tokenTypeIdsAsInt() {
            int[] result = new int[tokenTypeIds.length];
            for (int i = 0; i < tokenTypeIds.length; i++) {
                result[i] = (int) tokenTypeIds[i];
            }
            return result;
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
        int[] tokenIds = tokenizer.encode(text, addSpecialTokens).getIds();

        // DIAGNOSTIC: Log actual token IDs from Rust tokenizer
        if (tokenIds.length > 0 && tokenIds.length <= 20) {
            StringBuilder sb = new StringBuilder("[TokenizerDiag] Text='").append(text).append("' -> TokenIDs=[");
            for (int i = 0; i < tokenIds.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(tokenIds[i]);
                // Look up token name if we have vocabulary
                if (vocabulary != null) {
                    String tokenName = vocabulary.getToken(tokenIds[i]);
                    if (tokenName != null) {
                        sb.append("('").append(tokenName).append("')");
                    }
                }
            }
            sb.append("]");
            System.err.println(sb.toString());
            System.err.flush();
        } else if (tokenIds.length > 20) {
            System.err.println("[TokenizerDiag] Text='" + text + "' -> " + tokenIds.length + " tokens, first 5: " +
                tokenIds[0] + ", " + tokenIds[1] + ", " + tokenIds[2] + ", " + tokenIds[3] + ", " + tokenIds[4]);
            System.err.flush();
        }

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
    public Encoding encodeWithDetails(String text) {
        return tokenizer.encode(text, addSpecialTokens);
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
     * Encode a query-document pair for cross-encoder models.
     * Format: [CLS] query [SEP] document [SEP]
     *
     * @param query The query text
     * @param document The document text
     * @return BertEncoding with token type IDs distinguishing query (0) from document (1)
     */
    public BertEncoding encodePair(String query, String document) {
        if (query == null) query = "";
        if (document == null) document = "";

        // Encode query with [CLS] at start
        int[] queryTokens = tokenizer.encode(query, addSpecialTokens).getIds();

        // Encode document (no CLS, will add SEP manually)
        int[] docTokens = tokenizer.encode(document, false).getIds();

        // Get special token IDs
        int sepId = vocabulary.getTokenId(SEP_TOKEN);

        // Calculate total length
        int totalLength = queryTokens.length + 1 + docTokens.length; // +1 for second SEP

        // Truncate if necessary (prioritize query, then truncate document)
        int actualLength = maxLength > 0 ? maxLength : totalLength;
        if (totalLength > actualLength && maxLength > 0) {
            // Calculate how much space is available for document
            int availableForDoc = actualLength - queryTokens.length - 1; // Reserve 1 for final SEP
            if (availableForDoc < 0) {
                // Query is too long, truncate query
                int newQueryLength = actualLength - 2; // Reserve for SEP tokens
                int[] truncatedQuery = new int[newQueryLength];
                System.arraycopy(queryTokens, 0, truncatedQuery, 0, newQueryLength);
                queryTokens = truncatedQuery;
                docTokens = new int[0];
            } else if (availableForDoc < docTokens.length) {
                // Truncate document
                int[] truncatedDoc = new int[availableForDoc];
                System.arraycopy(docTokens, 0, truncatedDoc, 0, availableForDoc);
                docTokens = truncatedDoc;
            }
        }

        // Build combined sequence
        int combinedLength = maxLength > 0 ? maxLength : (queryTokens.length + 1 + docTokens.length);
        long[] inputIds = new long[combinedLength];
        long[] attentionMask = new long[combinedLength];
        long[] tokenTypeIds = new long[combinedLength];

        int pos = 0;

        // Copy query tokens (token type = 0)
        for (int i = 0; i < queryTokens.length && pos < combinedLength; i++, pos++) {
            inputIds[pos] = queryTokens[i];
            attentionMask[pos] = 1L;
            tokenTypeIds[pos] = 0L;
        }

        // Add SEP after query if space available
        if (pos < combinedLength) {
            inputIds[pos] = sepId;
            attentionMask[pos] = 1L;
            tokenTypeIds[pos] = 0L;
            pos++;
        }

        // Copy document tokens (token type = 1)
        for (int i = 0; i < docTokens.length && pos < combinedLength; i++, pos++) {
            inputIds[pos] = docTokens[i];
            attentionMask[pos] = 1L;
            tokenTypeIds[pos] = 1L;
        }

        // Add final SEP if space available
        if (pos < combinedLength) {
            inputIds[pos] = sepId;
            attentionMask[pos] = 1L;
            tokenTypeIds[pos] = 1L;
            pos++;
        }

        // Fill remaining with PAD tokens
        if (maxLength > 0) {
            long padId = vocabulary.getTokenId(PAD_TOKEN);
            for (; pos < combinedLength; pos++) {
                inputIds[pos] = padId;
                attentionMask[pos] = 0L;
                tokenTypeIds[pos] = 0L;
            }
        }

        return new BertEncoding(inputIds, attentionMask, tokenTypeIds);
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
        // No-op: DL4J HuggingFaceTokenizer handles cleanup in close()
    }

    /**
     * Check if the tokenizer is currently shutting down.
     * @return true if shutdown has been initiated
     */
    public boolean isShuttingDown() {
        return false;
    }

    /**
     * Get the number of active operations on the tokenizer.
     * @return count of active operations, or 0 if tokenizer is null
     */
    public int getActiveOperationCount() {
        return 0;
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
        return HuggingFaceTokenizer.getNativeVersion();
    }

    /**
     * Get build information from the underlying tokenizer
     * @return Build info string
     */
    public static String getBuildInfo() {
        return HuggingFaceTokenizer.getNativeVersion();
    }

    /**
     * Check if a model file is valid
     * @param modelPath Path to model file
     * @return true if valid, false otherwise
     */
    public static boolean isValidModelFile(String modelPath) {
        try {
            HuggingFaceTokenizer t = HuggingFaceTokenizer.fromFile(modelPath);
            boolean valid = t.isValid();
            t.close();
            return valid;
        } catch (Exception e) {
            return false;
        }
    }
}
