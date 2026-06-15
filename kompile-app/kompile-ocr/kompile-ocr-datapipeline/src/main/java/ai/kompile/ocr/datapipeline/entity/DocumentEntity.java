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

package ai.kompile.ocr.datapipeline.entity;

import ai.kompile.ocr.BoundingBox;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Base class for all extractable document entities.
 * Entities are first-class searchable objects extracted from documents.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "entityType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TableEntity.class, name = "table"),
        @JsonSubTypes.Type(value = FigureEntity.class, name = "figure"),
        @JsonSubTypes.Type(value = FormulaEntity.class, name = "formula"),
        @JsonSubTypes.Type(value = CodeEntity.class, name = "code"),
        @JsonSubTypes.Type(value = ListEntity.class, name = "list"),
        @JsonSubTypes.Type(value = HeadingEntity.class, name = "heading")
})
public abstract class DocumentEntity {

    /**
     * Unique identifier for this entity.
     */
    private String id;

    /**
     * Type of entity.
     */
    private EntityType type;

    /**
     * Bounding box location in the source document.
     */
    private BoundingBox bounds;

    /**
     * Page number where this entity appears (1-indexed).
     */
    private int pageNumber;

    /**
     * Confidence score for extraction (0-1).
     */
    private double confidence;

    /**
     * Additional metadata.
     */
    private Map<String, Object> metadata;

    /**
     * Generates a unique ID if not set.
     */
    public String getId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    /**
     * Creates a Spring AI Document for semantic search.
     * Uses a summary/description for embedding.
     */
    public abstract Document toSearchDocument();

    /**
     * Creates a Spring AI Document with full content.
     * Used for retrieval augmentation.
     */
    public abstract Document toFullDocument();

    /**
     * Converts entity to Markdown representation.
     */
    public abstract String toMarkdown();

    /**
     * Gets entity-specific metadata for indexing.
     */
    protected Map<String, Object> getBaseMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("entity_type", type != null ? type.name().toLowerCase() : "unknown");
        meta.put("entity_id", getId());
        meta.put("page_number", pageNumber);
        meta.put("confidence", confidence);
        if (metadata != null) {
            meta.putAll(metadata);
        }
        return meta;
    }

    /**
     * Adds a metadata value.
     */
    public void addMetadata(String key, Object value) {
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put(key, value);
    }

    /**
     * Types of document entities.
     */
    public enum EntityType {
        TABLE,
        FIGURE,
        CHART,
        FORMULA,
        CODE,
        LIST,
        HEADING,
        PARAGRAPH,
        CAPTION,
        HEADER,
        FOOTER
    }
}
