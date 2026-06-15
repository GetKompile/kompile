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

package ai.kompile.app.services.pipeline.stages;

import ai.kompile.app.services.pipeline.PipelineStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tokenization stage: Pre-tokenizes document text for efficient chunk boundary detection.
 *
 * <p>This stage provides:</p>
 * <ul>
 *   <li>Token offset mapping for precise chunk boundaries</li>
 *   <li>Token count calculation for token-aware chunking</li>
 *   <li>Optional: can be skipped for simple character-based chunking</li>
 * </ul>
 *
 * <p>Input: {@link ExtractionStage.ExtractionOutput} containing loaded documents</p>
 * <p>Output: {@link TokenizationOutput} containing tokenized documents</p>
 */
public class TokenizationStage implements PipelineStage<ExtractionStage.ExtractionOutput, TokenizationStage.TokenizationOutput> {

    private static final Logger logger = LoggerFactory.getLogger(TokenizationStage.class);

    private final StageMetrics metrics = new StageMetrics();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // Configuration
    private boolean enabled = true;
    private int maxTokenLength = 512;
    private String tokenizerModel = "default";  // Could be "bert-base-uncased", etc.

    @Override
    public String getName() {
        return "tokenization";
    }

    @Override
    public TokenizationOutput process(ExtractionStage.ExtractionOutput input) throws Exception {
        if (cancelled.get()) {
            throw new InterruptedException("Tokenization stage cancelled");
        }

        long startNanos = System.nanoTime();
        List<TokenizedDocument> tokenizedDocs = new ArrayList<>();
        long totalBytes = 0;

        try {
            for (Document doc : input.documents()) {
                if (cancelled.get()) {
                    throw new InterruptedException("Tokenization cancelled during processing");
                }

                String text = doc.getText();
                if (text == null) text = "";
                totalBytes += text.length() * 2; // Approximate byte count for string

                TokenizedDocument tokenizedDoc;
                if (enabled) {
                    tokenizedDoc = tokenizeDocument(doc);
                } else {
                    // Passthrough mode - just wrap the document without tokenization
                    tokenizedDoc = new TokenizedDocument(
                            doc,
                            List.of(),  // No token offsets
                            0,          // No token count
                            false       // Not tokenized
                    );
                }
                tokenizedDocs.add(tokenizedDoc);
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            metrics.recordSuccess(elapsedNanos, totalBytes, tokenizedDocs.size());

            int totalTokens = tokenizedDocs.stream()
                    .mapToInt(TokenizedDocument::tokenCount)
                    .sum();

            logger.debug("Tokenized {} documents ({} tokens) in {}ms",
                    tokenizedDocs.size(), totalTokens, elapsedNanos / 1_000_000);

            return new TokenizationOutput(
                    tokenizedDocs,
                    input.loaderUsed(),
                    elapsedNanos / 1_000_000,
                    totalTokens,
                    enabled,
                    input.taskId(),
                    input.metadata()
            );

        } catch (Exception e) {
            metrics.recordFailure();
            throw e;
        }
    }

    /**
     * Tokenizes a single document, computing token offsets for chunk boundary detection.
     *
     * <p>This is a simplified tokenization that splits on whitespace and punctuation.
     * For production use with specific models, this would integrate with the
     * Rust tokenizer bindings or a specific tokenizer implementation.</p>
     */
    private TokenizedDocument tokenizeDocument(Document doc) {
        String text = doc.getText();
        if (text == null || text.isEmpty()) {
            return new TokenizedDocument(doc, List.of(), 0, true);
        }

        List<TokenOffset> offsets = new ArrayList<>();
        int tokenCount = 0;

        // Simple whitespace/punctuation tokenization
        // In production, this would use the actual tokenizer (BERT, etc.)
        int start = 0;
        boolean inToken = false;

        for (int i = 0; i <= text.length(); i++) {
            boolean isDelimiter = (i == text.length()) || isTokenDelimiter(text.charAt(i));

            if (inToken && isDelimiter) {
                // End of token
                if (i > start) {
                    offsets.add(new TokenOffset(start, i, tokenCount));
                    tokenCount++;

                    // Limit tokens if configured
                    if (maxTokenLength > 0 && tokenCount >= maxTokenLength) {
                        break;
                    }
                }
                inToken = false;
            } else if (!inToken && !isDelimiter) {
                // Start of token
                start = i;
                inToken = true;
            }
        }

        return new TokenizedDocument(doc, offsets, tokenCount, true);
    }

    private boolean isTokenDelimiter(char c) {
        return Character.isWhitespace(c) ||
                c == '.' || c == ',' || c == ';' || c == ':' ||
                c == '!' || c == '?' || c == '"' || c == '\'' ||
                c == '(' || c == ')' || c == '[' || c == ']' ||
                c == '{' || c == '}' || c == '<' || c == '>';
    }

    @Override
    public void configure(Map<String, Object> options) {
        if (options == null) return;

        if (options.containsKey("enabled")) {
            this.enabled = (Boolean) options.get("enabled");
        }
        if (options.containsKey("enablePreTokenization")) {
            this.enabled = (Boolean) options.get("enablePreTokenization");
        }
        if (options.containsKey("maxTokenLength")) {
            this.maxTokenLength = ((Number) options.get("maxTokenLength")).intValue();
        }
        if (options.containsKey("tokenizerModel")) {
            this.tokenizerModel = (String) options.get("tokenizerModel");
        }
    }

    @Override
    public StageMetrics getMetrics() {
        return metrics;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void reset() {
        cancelled.set(false);
        metrics.reset();
    }

    /**
     * Returns whether tokenization is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether tokenization is enabled.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Token offset information for chunk boundary detection.
     */
    public record TokenOffset(
            int startChar,
            int endChar,
            int tokenIndex
    ) {
        public int length() {
            return endChar - startChar;
        }
    }

    /**
     * A document with tokenization information.
     */
    public record TokenizedDocument(
            Document document,
            List<TokenOffset> tokenOffsets,
            int tokenCount,
            boolean wasTokenized
    ) {
        public String getText() {
            return document.getText();
        }

        public String getId() {
            return document.getId();
        }

        public Map<String, Object> getMetadata() {
            return document.getMetadata();
        }

        /**
         * Finds the best character offset to split at, respecting token boundaries.
         *
         * @param targetOffset The desired character offset
         * @return The adjusted offset at a token boundary
         */
        public int findTokenBoundary(int targetOffset) {
            if (!wasTokenized || tokenOffsets.isEmpty()) {
                return targetOffset;
            }

            // Binary search for the token containing or nearest to targetOffset
            int left = 0;
            int right = tokenOffsets.size() - 1;

            while (left < right) {
                int mid = (left + right) / 2;
                TokenOffset token = tokenOffsets.get(mid);

                if (token.endChar <= targetOffset) {
                    left = mid + 1;
                } else {
                    right = mid;
                }
            }

            if (left < tokenOffsets.size()) {
                TokenOffset token = tokenOffsets.get(left);
                // Return the start of the next token or end of current token
                if (targetOffset >= token.startChar && targetOffset < token.endChar) {
                    // Target is within a token - return end of token
                    return token.endChar;
                }
                return token.startChar;
            }

            return targetOffset;
        }
    }

    /**
     * Output from the tokenization stage.
     */
    public record TokenizationOutput(
            List<TokenizedDocument> documents,
            String loaderUsed,
            long tokenizationTimeMs,
            int totalTokens,
            boolean tokenizationEnabled,
            String taskId,
            Map<String, Object> metadata
    ) {
        public int documentCount() {
            return documents != null ? documents.size() : 0;
        }
    }
}
