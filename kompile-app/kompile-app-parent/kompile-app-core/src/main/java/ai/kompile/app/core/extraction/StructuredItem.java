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

import java.util.*;

/**
 * Represents a structured item extracted from a document.
 *
 * <p>This is a unified container for different types of extracted content:</p>
 * <ul>
 *   <li><b>ENTITY</b> - Named entities (people, places, organizations, etc.)</li>
 *   <li><b>RELATIONSHIP</b> - Relationships between entities</li>
 *   <li><b>TABLE</b> - Structured table data</li>
 *   <li><b>CONCEPT</b> - Key concepts and themes</li>
 *   <li><b>CODE_BLOCK</b> - Code snippets with language info</li>
 *   <li><b>CITATION</b> - References and citations</li>
 *   <li><b>FACT</b> - Extracted fact statements</li>
 *   <li><b>SUMMARY</b> - Summary information</li>
 * </ul>
 */
public class StructuredItem {

    /**
     * Type of structured item.
     */
    public enum ItemType {
        ENTITY,
        RELATIONSHIP,
        TABLE,
        CONCEPT,
        CODE_BLOCK,
        CITATION,
        FACT,
        SUMMARY,
        KEY_VALUE,
        LIST,
        FORMULA,
        IMAGE_DESCRIPTION,
        CUSTOM
    }

    private final String id;
    private final ItemType type;
    private final String sourceDocumentId;
    private final String sourceChunkId;

    // Common fields
    private final String title;
    private final String content;
    private final double confidence;
    private final int startOffset;
    private final int endOffset;

    // Type-specific fields stored in properties
    private final Map<String, Object> properties;

    // Metadata
    private final Map<String, Object> metadata;

    private StructuredItem(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.type = builder.type;
        this.sourceDocumentId = builder.sourceDocumentId;
        this.sourceChunkId = builder.sourceChunkId;
        this.title = builder.title;
        this.content = builder.content;
        this.confidence = builder.confidence;
        this.startOffset = builder.startOffset;
        this.endOffset = builder.endOffset;
        this.properties = builder.properties != null ? new HashMap<>(builder.properties) : new HashMap<>();
        this.metadata = builder.metadata != null ? new HashMap<>(builder.metadata) : new HashMap<>();
    }

    // Factory methods for common types

    /**
     * Creates an entity item.
     */
    public static StructuredItem entity(
            String name,
            String entityType,
            String description,
            double confidence,
            String sourceDocId) {
        return builder()
                .type(ItemType.ENTITY)
                .title(name)
                .content(description)
                .confidence(confidence)
                .sourceDocumentId(sourceDocId)
                .property("entityType", entityType)
                .build();
    }

    /**
     * Creates a relationship item.
     */
    public static StructuredItem relationship(
            String sourceEntity,
            String targetEntity,
            String relationshipType,
            String description,
            double confidence,
            String sourceDocId) {
        return builder()
                .type(ItemType.RELATIONSHIP)
                .title(relationshipType)
                .content(description)
                .confidence(confidence)
                .sourceDocumentId(sourceDocId)
                .property("sourceEntity", sourceEntity)
                .property("targetEntity", targetEntity)
                .property("relationshipType", relationshipType)
                .build();
    }

    /**
     * Creates a table item.
     */
    public static StructuredItem table(
            String tableId,
            String markdownContent,
            List<String> headers,
            int rowCount,
            int colCount,
            String sourceDocId) {
        return builder()
                .id(tableId)
                .type(ItemType.TABLE)
                .content(markdownContent)
                .sourceDocumentId(sourceDocId)
                .property("headers", headers)
                .property("rowCount", rowCount)
                .property("columnCount", colCount)
                .build();
    }

    /**
     * Creates a concept item.
     */
    public static StructuredItem concept(
            String conceptName,
            String category,
            double confidence,
            int frequency,
            String sourceDocId) {
        return builder()
                .type(ItemType.CONCEPT)
                .title(conceptName)
                .confidence(confidence)
                .sourceDocumentId(sourceDocId)
                .property("category", category)
                .property("frequency", frequency)
                .build();
    }

    /**
     * Creates a code block item.
     */
    public static StructuredItem codeBlock(
            String code,
            String language,
            String description,
            String sourceDocId,
            int startOffset,
            int endOffset) {
        return builder()
                .type(ItemType.CODE_BLOCK)
                .title(language != null ? language : "unknown")
                .content(code)
                .sourceDocumentId(sourceDocId)
                .startOffset(startOffset)
                .endOffset(endOffset)
                .property("language", language)
                .property("description", description)
                .build();
    }

    /**
     * Creates a fact item.
     */
    public static StructuredItem fact(
            String factStatement,
            String subject,
            String predicate,
            String object,
            double confidence,
            String sourceDocId) {
        return builder()
                .type(ItemType.FACT)
                .content(factStatement)
                .confidence(confidence)
                .sourceDocumentId(sourceDocId)
                .property("subject", subject)
                .property("predicate", predicate)
                .property("object", object)
                .build();
    }

    /**
     * Creates a citation item.
     */
    public static StructuredItem citation(
            String citationText,
            String referenceId,
            String authors,
            String title,
            String year,
            String sourceDocId) {
        return builder()
                .type(ItemType.CITATION)
                .title(title)
                .content(citationText)
                .sourceDocumentId(sourceDocId)
                .property("referenceId", referenceId)
                .property("authors", authors)
                .property("year", year)
                .build();
    }

    // Getters

    public String getId() {
        return id;
    }

    public ItemType getType() {
        return type;
    }

    public String getSourceDocumentId() {
        return sourceDocumentId;
    }

    public String getSourceChunkId() {
        return sourceChunkId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public double getConfidence() {
        return confidence;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @SuppressWarnings("unchecked")
    public <T> T getProperty(String key) {
        return (T) properties.get(key);
    }

    public <T> T getProperty(String key, T defaultValue) {
        T value = getProperty(key);
        return value != null ? value : defaultValue;
    }

    // Entity-specific getters

    public String getEntityType() {
        return getProperty("entityType");
    }

    // Relationship-specific getters

    public String getSourceEntity() {
        return getProperty("sourceEntity");
    }

    public String getTargetEntity() {
        return getProperty("targetEntity");
    }

    public String getRelationshipType() {
        return getProperty("relationshipType");
    }

    // Table-specific getters

    @SuppressWarnings("unchecked")
    public List<String> getTableHeaders() {
        return getProperty("headers", List.of());
    }

    public int getRowCount() {
        return getProperty("rowCount", 0);
    }

    public int getColumnCount() {
        return getProperty("columnCount", 0);
    }

    // Code block-specific getters

    public String getCodeLanguage() {
        return getProperty("language");
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for StructuredItem.
     */
    public static class Builder {
        private String id;
        private ItemType type;
        private String sourceDocumentId;
        private String sourceChunkId;
        private String title;
        private String content;
        private double confidence = 1.0;
        private int startOffset = -1;
        private int endOffset = -1;
        private Map<String, Object> properties;
        private Map<String, Object> metadata;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder type(ItemType type) {
            this.type = type;
            return this;
        }

        public Builder sourceDocumentId(String sourceDocumentId) {
            this.sourceDocumentId = sourceDocumentId;
            return this;
        }

        public Builder sourceChunkId(String sourceChunkId) {
            this.sourceChunkId = sourceChunkId;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder startOffset(int startOffset) {
            this.startOffset = startOffset;
            return this;
        }

        public Builder endOffset(int endOffset) {
            this.endOffset = endOffset;
            return this;
        }

        public Builder properties(Map<String, Object> properties) {
            this.properties = properties;
            return this;
        }

        public Builder property(String key, Object value) {
            if (this.properties == null) {
                this.properties = new HashMap<>();
            }
            this.properties.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        public StructuredItem build() {
            Objects.requireNonNull(type, "Type is required");
            return new StructuredItem(this);
        }
    }

    @Override
    public String toString() {
        return String.format("StructuredItem{id=%s, type=%s, title=%s, confidence=%.2f}",
                id, type, title, confidence);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StructuredItem that = (StructuredItem) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
