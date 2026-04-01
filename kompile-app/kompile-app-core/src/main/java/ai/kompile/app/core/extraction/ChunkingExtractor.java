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

package ai.kompile.app.core.extraction;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.retrievers.RetrievedDoc;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Adapter that wraps a {@link TextChunker} as a {@link ContentExtractor}.
 *
 * <p>This allows chunking to be used alongside other extraction types in the
 * concurrent extraction orchestrator. Chunking extractors have the highest
 * priority by default since chunks are required for embedding.</p>
 */
public class ChunkingExtractor implements ContentExtractor {

    private final TextChunker chunker;
    private boolean enabled = true;
    private int priority = 100; // High priority - chunks are needed first

    public ChunkingExtractor(TextChunker chunker) {
        this.chunker = chunker;
    }

    @Override
    public String getName() {
        return "chunking-" + (chunker != null ? chunker.getName() : "none");
    }

    @Override
    public ExtractorType getType() {
        return ExtractorType.CHUNKING;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public boolean isEnabled() {
        return enabled && chunker != null;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    @Override
    public ExtractionResult extract(RetrievedDoc document, Map<String, Object> options) throws Exception {
        return extract(document, options, null);
    }

    @Override
    public ExtractionResult extract(
            RetrievedDoc document,
            Map<String, Object> options,
            Consumer<ExtractionProgress> progressCallback) throws Exception {

        if (chunker == null) {
            // No chunker - return document as single chunk
            return ExtractionResult.ofChunks(
                    document.getId(),
                    "none",
                    List.of(document),
                    0
            );
        }

        // Skip documents with null or empty text content
        String text = document.getText();
        if (text == null || text.trim().isEmpty()) {
            return ExtractionResult.ofChunks(
                    document.getId(),
                    chunker.getName(),
                    List.of(),
                    0
            );
        }

        long startTime = System.currentTimeMillis();

        // Use the chunker with progress callback
        List<RetrievedDoc> chunks = chunker.chunk(document, options, progress -> {
            if (progressCallback != null) {
                progressCallback.accept(new ExtractionProgress(
                        getName(),
                        ExtractorType.CHUNKING,
                        progress.phase(),
                        progress.progressPercent(),
                        progress.chunksCreated(),
                        progress.totalChars(),
                        progress.message()
                ));
            }
        });

        long elapsedTime = System.currentTimeMillis() - startTime;

        return ExtractionResult.ofChunks(
                document.getId(),
                chunker.getName(),
                chunks != null ? chunks : List.of(document),
                elapsedTime
        );
    }

    @Override
    public List<ExtractionResult> extractBatch(
            List<RetrievedDoc> documents,
            Map<String, Object> options) throws Exception {

        // For chunking, it's more efficient to process documents individually
        // since the chunker may have internal parallelism
        return ContentExtractor.super.extractBatch(documents, options);
    }

    @Override
    public void configure(Map<String, Object> options) {
        if (options == null) return;

        if (options.containsKey("enabled")) {
            this.enabled = (Boolean) options.get("enabled");
        }
        if (options.containsKey("priority")) {
            this.priority = ((Number) options.get("priority")).intValue();
        }
    }

    @Override
    public List<String> getSupportedLanguages() {
        return chunker != null ? chunker.getSupportedLanguages() : List.of("*");
    }

    @Override
    public void reset() {
        // Chunkers are stateless, nothing to reset
    }

    /**
     * Returns the underlying chunker.
     */
    public TextChunker getChunker() {
        return chunker;
    }
}
