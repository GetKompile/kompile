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

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.services.pipeline.PipelineStage;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chunking stage: Splits documents into smaller chunks for embedding and retrieval.
 *
 * <p>This stage provides:</p>
 * <ul>
 *   <li>Token-aware chunking using pre-tokenized input</li>
 *   <li>Configurable chunk size and overlap</li>
 *   <li>Multiple chunking strategies (recursive, sentence, token)</li>
 *   <li>Parallel processing of multiple documents</li>
 * </ul>
 *
 * <p>Input: {@link TokenizationStage.TokenizationOutput} containing tokenized documents</p>
 * <p>Output: {@link ChunkingOutput} containing document chunks</p>
 */
public class ChunkingStage implements PipelineStage<TokenizationStage.TokenizationOutput, ChunkingStage.ChunkingOutput> {

    private static final Logger logger = LoggerFactory.getLogger(ChunkingStage.class);

    private final TextChunker chunker;
    private final StageMetrics metrics = new StageMetrics();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // Configuration
    private int chunkSize = 1000;
    private int chunkOverlap = 200;
    private boolean preserveParagraphs = true;
    private boolean useTokenBoundaries = true;

    public ChunkingStage(TextChunker chunker) {
        this.chunker = chunker;
    }

    @Override
    public String getName() {
        return "chunking";
    }

    @Override
    public ChunkingOutput process(TokenizationStage.TokenizationOutput input) throws Exception {
        if (cancelled.get()) {
            throw new InterruptedException("Chunking stage cancelled");
        }

        long startNanos = System.nanoTime();
        List<RetrievedDoc> allChunks = new ArrayList<>();
        long totalBytes = 0;
        int documentsChunked = 0;

        try {
            Map<String, Object> options = buildChunkingOptions();

            for (TokenizationStage.TokenizedDocument tokenizedDoc : input.documents()) {
                if (cancelled.get()) {
                    throw new InterruptedException("Chunking cancelled during processing");
                }

                String text = tokenizedDoc.getText();
                if (text != null) {
                    totalBytes += text.length() * 2;
                }

                List<RetrievedDoc> chunks = chunkDocument(tokenizedDoc, options);
                allChunks.addAll(chunks);
                documentsChunked++;

                if (documentsChunked % 10 == 0) {
                    logger.debug("Chunked {}/{} documents, {} chunks so far",
                            documentsChunked, input.documentCount(), allChunks.size());
                }
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            metrics.recordSuccess(elapsedNanos, totalBytes, allChunks.size());

            logger.debug("Chunked {} documents into {} chunks in {}ms (avg {} chunks/doc)",
                    documentsChunked, allChunks.size(), elapsedNanos / 1_000_000,
                    documentsChunked > 0 ? (double) allChunks.size() / documentsChunked : 0);

            return new ChunkingOutput(
                    allChunks,
                    documentsChunked,
                    elapsedNanos / 1_000_000,
                    chunker != null ? chunker.getName() : "none",
                    input.loaderUsed(),
                    input.taskId(),
                    input.metadata()
            );

        } catch (Exception e) {
            metrics.recordFailure();
            throw e;
        }
    }

    private List<RetrievedDoc> chunkDocument(TokenizationStage.TokenizedDocument tokenizedDoc,
                                              Map<String, Object> options) {
        // Convert to RetrievedDoc for the chunker
        RetrievedDoc retrievedDoc = RetrievedDoc.builder()
                .id(tokenizedDoc.getId())
                .text(tokenizedDoc.getText())
                .metadata(tokenizedDoc.getMetadata())
                .build();

        if (chunker == null) {
            // No chunker - return document as single chunk
            return List.of(retrievedDoc);
        }

        // Skip documents with null or empty text content
        String text = tokenizedDoc.getText();
        if (text == null || text.trim().isEmpty()) {
            logger.debug("Skipping chunking for document {} - no text content", tokenizedDoc.getId());
            return List.of(); // Return empty list for empty documents
        }

        try {
            // Use token boundaries if available and enabled
            if (useTokenBoundaries && tokenizedDoc.wasTokenized() && !tokenizedDoc.tokenOffsets().isEmpty()) {
                // Adjust chunk boundaries to token boundaries
                options.put("tokenBoundaryFinder", (java.util.function.IntUnaryOperator)
                        tokenizedDoc::findTokenBoundary);
            }

            List<RetrievedDoc> chunks = chunker.chunk(retrievedDoc, options, progress -> {
                // Progress callback - can be used for fine-grained tracking
            });

            return chunks != null ? chunks : List.of(retrievedDoc);

        } catch (Exception e) {
            logger.warn("Chunking failed for document {}, using whole document: {}",
                    tokenizedDoc.getId(), e.getMessage());
            return List.of(retrievedDoc);
        }
    }

    private Map<String, Object> buildChunkingOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("chunkSize", chunkSize);
        options.put("overlap", chunkOverlap);
        options.put("preserveParagraphs", preserveParagraphs);
        return options;
    }

    @Override
    public void configure(Map<String, Object> options) {
        if (options == null) return;

        if (options.containsKey("chunkSize")) {
            this.chunkSize = ((Number) options.get("chunkSize")).intValue();
        }
        if (options.containsKey("overlap")) {
            this.chunkOverlap = ((Number) options.get("overlap")).intValue();
        }
        if (options.containsKey("chunkOverlap")) {
            this.chunkOverlap = ((Number) options.get("chunkOverlap")).intValue();
        }
        if (options.containsKey("preserveParagraphs")) {
            this.preserveParagraphs = (Boolean) options.get("preserveParagraphs");
        }
        if (options.containsKey("useTokenBoundaries")) {
            this.useTokenBoundaries = (Boolean) options.get("useTokenBoundaries");
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
     * Returns the chunker name.
     */
    public String getChunkerName() {
        return chunker != null ? chunker.getName() : "none";
    }

    /**
     * Output from the chunking stage.
     */
    public record ChunkingOutput(
            List<RetrievedDoc> chunks,
            int documentsChunked,
            long chunkingTimeMs,
            String chunkerUsed,
            String loaderUsed,
            String taskId,
            Map<String, Object> metadata
    ) {
        public int chunkCount() {
            return chunks != null ? chunks.size() : 0;
        }

        public double averageChunksPerDocument() {
            return documentsChunked > 0 ? (double) chunkCount() / documentsChunked : 0;
        }
    }
}
