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

import ai.kompile.core.retrievers.RetrievedDoc;

import java.util.*;

/**
 * Result of a content extraction operation.
 *
 * <p>This class holds the output from any type of extractor, supporting:</p>
 * <ul>
 *   <li><b>Chunks</b> - Document fragments for embedding (from chunking extractors)</li>
 *   <li><b>Structured Items</b> - Entities, relationships, tables, concepts, etc.</li>
 *   <li><b>Metadata</b> - Additional information about the extraction</li>
 * </ul>
 *
 * <p>Results can be merged from multiple extractors using {@link #merge(List)}.</p>
 */
public class ExtractionResult {

    private final String sourceDocumentId;
    private final ContentExtractor.ExtractorType extractorType;
    private final String extractorName;
    private final boolean success;
    private final String errorMessage;

    // Chunks produced (for chunking extractors)
    private final List<RetrievedDoc> chunks;

    // Structured items produced (entities, relationships, tables, etc.)
    private final List<StructuredItem> structuredItems;

    // Metadata about the extraction
    private final Map<String, Object> metadata;

    // Timing information
    private final long extractionTimeMs;

    private ExtractionResult(Builder builder) {
        this.sourceDocumentId = builder.sourceDocumentId;
        this.extractorType = builder.extractorType;
        this.extractorName = builder.extractorName;
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
        this.chunks = builder.chunks != null ? new ArrayList<>(builder.chunks) : new ArrayList<>();
        this.structuredItems = builder.structuredItems != null ? new ArrayList<>(builder.structuredItems) : new ArrayList<>();
        this.metadata = builder.metadata != null ? new HashMap<>(builder.metadata) : new HashMap<>();
        this.extractionTimeMs = builder.extractionTimeMs;
    }

    /**
     * Creates a failed extraction result.
     */
    public static ExtractionResult failed(String sourceDocId, String errorMessage) {
        return builder()
                .sourceDocumentId(sourceDocId)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Creates a successful chunking result.
     */
    public static ExtractionResult ofChunks(
            String sourceDocId,
            String extractorName,
            List<RetrievedDoc> chunks,
            long extractionTimeMs) {
        return builder()
                .sourceDocumentId(sourceDocId)
                .extractorType(ContentExtractor.ExtractorType.CHUNKING)
                .extractorName(extractorName)
                .chunks(chunks)
                .extractionTimeMs(extractionTimeMs)
                .success(true)
                .build();
    }

    /**
     * Creates a successful structured item extraction result.
     */
    public static ExtractionResult ofStructuredItems(
            String sourceDocId,
            ContentExtractor.ExtractorType type,
            String extractorName,
            List<StructuredItem> items,
            long extractionTimeMs) {
        return builder()
                .sourceDocumentId(sourceDocId)
                .extractorType(type)
                .extractorName(extractorName)
                .structuredItems(items)
                .extractionTimeMs(extractionTimeMs)
                .success(true)
                .build();
    }

    /**
     * Merges multiple extraction results into a single combined result.
     * Chunks and structured items are combined.
     */
    public static ExtractionResult merge(List<ExtractionResult> results) {
        if (results == null || results.isEmpty()) {
            return builder().success(true).build();
        }

        List<RetrievedDoc> allChunks = new ArrayList<>();
        List<StructuredItem> allItems = new ArrayList<>();
        Map<String, Object> mergedMetadata = new HashMap<>();
        long totalTime = 0;
        String sourceDocId = null;
        boolean anySuccess = false;
        List<String> errors = new ArrayList<>();

        for (ExtractionResult result : results) {
            if (result.sourceDocumentId != null && sourceDocId == null) {
                sourceDocId = result.sourceDocumentId;
            }
            allChunks.addAll(result.chunks);
            allItems.addAll(result.structuredItems);
            mergedMetadata.putAll(result.metadata);
            totalTime += result.extractionTimeMs;

            if (result.success) {
                anySuccess = true;
            } else if (result.errorMessage != null) {
                errors.add(result.extractorName + ": " + result.errorMessage);
            }
        }

        Builder builder = builder()
                .sourceDocumentId(sourceDocId)
                .chunks(allChunks)
                .structuredItems(allItems)
                .metadata(mergedMetadata)
                .extractionTimeMs(totalTime)
                .success(anySuccess);

        if (!errors.isEmpty()) {
            builder.errorMessage(String.join("; ", errors));
        }

        return builder.build();
    }

    // Getters

    public String getSourceDocumentId() {
        return sourceDocumentId;
    }

    public ContentExtractor.ExtractorType getExtractorType() {
        return extractorType;
    }

    public String getExtractorName() {
        return extractorName;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<RetrievedDoc> getChunks() {
        return Collections.unmodifiableList(chunks);
    }

    public List<StructuredItem> getStructuredItems() {
        return Collections.unmodifiableList(structuredItems);
    }

    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public long getExtractionTimeMs() {
        return extractionTimeMs;
    }

    public int getChunkCount() {
        return chunks.size();
    }

    public int getStructuredItemCount() {
        return structuredItems.size();
    }

    public boolean hasChunks() {
        return !chunks.isEmpty();
    }

    public boolean hasStructuredItems() {
        return !structuredItems.isEmpty();
    }

    /**
     * Gets structured items of a specific type.
     */
    public List<StructuredItem> getStructuredItemsOfType(StructuredItem.ItemType type) {
        return structuredItems.stream()
                .filter(item -> item.getType() == type)
                .toList();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ExtractionResult.
     */
    public static class Builder {
        private String sourceDocumentId;
        private ContentExtractor.ExtractorType extractorType;
        private String extractorName;
        private boolean success = true;
        private String errorMessage;
        private List<RetrievedDoc> chunks;
        private List<StructuredItem> structuredItems;
        private Map<String, Object> metadata;
        private long extractionTimeMs;

        public Builder sourceDocumentId(String sourceDocumentId) {
            this.sourceDocumentId = sourceDocumentId;
            return this;
        }

        public Builder extractorType(ContentExtractor.ExtractorType extractorType) {
            this.extractorType = extractorType;
            return this;
        }

        public Builder extractorName(String extractorName) {
            this.extractorName = extractorName;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder chunks(List<RetrievedDoc> chunks) {
            this.chunks = chunks;
            return this;
        }

        public Builder structuredItems(List<StructuredItem> structuredItems) {
            this.structuredItems = structuredItems;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder extractionTimeMs(long extractionTimeMs) {
            this.extractionTimeMs = extractionTimeMs;
            return this;
        }

        public Builder addChunk(RetrievedDoc chunk) {
            if (this.chunks == null) {
                this.chunks = new ArrayList<>();
            }
            this.chunks.add(chunk);
            return this;
        }

        public Builder addStructuredItem(StructuredItem item) {
            if (this.structuredItems == null) {
                this.structuredItems = new ArrayList<>();
            }
            this.structuredItems.add(item);
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        public ExtractionResult build() {
            return new ExtractionResult(this);
        }
    }

    @Override
    public String toString() {
        return String.format("ExtractionResult{source=%s, extractor=%s, type=%s, success=%s, chunks=%d, items=%d, timeMs=%d}",
                sourceDocumentId, extractorName, extractorType, success, chunks.size(), structuredItems.size(), extractionTimeMs);
    }
}
