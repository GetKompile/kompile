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

package ai.kompile.core.retrievers;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A retrieved document container for content and metadata with a unique ID.
 * 
 * A RetrievedDoc can hold either text content or media content, but not both.
 * It is designed to work within retrieval systems and RAG pipelines.
 *
 * <p>
 * Example of creating a text document:
 * <pre>{@code
 * // Using constructor
 * RetrievedDoc textDoc = new RetrievedDoc("Sample text content", Map.of("source", "user-input"));
 *
 * // Using builder
 * RetrievedDoc textDoc = RetrievedDoc.builder()
 *     .text("Sample text content")
 *     .metadata("source", "user-input")
 *     .build();
 * }</pre>
 *
 * <p>
 * Example of creating a media document:
 * <pre>{@code
 * // Using constructor
 * Media imageContent = new Media(MediaType.IMAGE_PNG, new byte[] {...});
 * RetrievedDoc mediaDoc = new RetrievedDoc(imageContent, Map.of("filename", "sample.png"));
 *
 * // Using builder
 * RetrievedDoc mediaDoc = RetrievedDoc.builder()
 *     .media(new Media(MediaType.IMAGE_PNG, new byte[] {...}))
 *     .metadata("filename", "sample.png")
 *     .build();
 * }</pre>
 *
 * <p>
 * Example of checking content type and accessing content:
 * <pre>{@code
 * if (document.isText()) {
 *     String textContent = document.getText();
 *     // Process text content
 * } else {
 *     Media mediaContent = document.getMedia();
 *     // Process media content
 * }
 * }</pre>
 */
@JsonIgnoreProperties({"contentFormatter", "embedding"})
@EqualsAndHashCode
@ToString
public class RetrievedDoc {

    public static final ContentFormatter DEFAULT_CONTENT_FORMATTER = DefaultContentFormatter.defaultConfig();

    /**
     * Unique ID
     */
    private final String id;

    /**
     * Document string content.
     */
    private final String text;

    /**
     * Document media content
     */
    private final Media media;

    /**
     * Metadata for the document. It should not be nested and values should be restricted
     * to string, int, float, boolean for simple use with Vector Dbs.
     */
    private final Map<String, Object> metadata;

    /**
     * A numeric score associated with this document that can represent various types of
     * relevance measures.
     * <p>
     * Common uses include:
     * <ul>
     * <li>Measure of similarity between the embedding value of the document's text/media
     * and a query vector, where higher scores indicate greater similarity (opposite of
     * distance measure)
     * <li>Text relevancy rankings from retrieval systems
     * <li>Custom relevancy metrics from RAG patterns
     * </ul>
     * <p>
     * Higher values typically indicate greater relevance or similarity.
     */
    private final Double score;

    /**
     * Mutable, ephemeral, content to text formatter. Defaults to Document text.
     */
    @JsonIgnore
    private ContentFormatter contentFormatter = DEFAULT_CONTENT_FORMATTER;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public RetrievedDoc(@JsonProperty("content") String content) {
        this(content, new HashMap<>());
    }

    public RetrievedDoc(String text, Map<String, Object> metadata) {
        this(generateRandomId(), text, null, metadata, null);
    }

    public RetrievedDoc(String id, String text, Map<String, Object> metadata) {
        this(id, text, null, metadata, null);
    }

    public RetrievedDoc(Media media, Map<String, Object> metadata) {
        this(generateRandomId(), null, media, metadata, null);
    }

    public RetrievedDoc(String id, Media media, Map<String, Object> metadata) {
        this(id, null, media, metadata, null);
    }

    public RetrievedDoc(String text, Map<String, Object> metadata, double score) {
        this(generateRandomId(), text, null, metadata, score);
    }

    public RetrievedDoc(String id, String text, Map<String, Object> metadata, double score) {
        this(id, text, null, metadata, score);
    }

    private RetrievedDoc(String id, String text, Media media, Map<String, Object> metadata, Double score) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("id cannot be null or empty");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("metadata cannot be null");
        }
        if (metadata.keySet().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("metadata cannot have null keys");
        }
        if (metadata.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("metadata cannot have null values");
        }
        if ((text != null && media != null) || (text == null && media == null)) {
            throw new IllegalArgumentException("exactly one of text or media must be specified");
        }

        this.id = id;
        this.text = text;
        this.media = media;
        this.metadata = new HashMap<>(metadata);
        this.score = score;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String generateRandomId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Returns the unique identifier for this document.
     * <p>
     * This ID is either explicitly provided during document creation or generated using
     * a random UUID generator.
     * @return the unique identifier of this document
     */
    public String getId() {
        return this.id;
    }

    /**
     * Returns the document's text content, if any.
     * @return the text content if {@link #isText()} is true, null otherwise
     * @see #isText()
     * @see #getMedia()
     */
    public String getText() {
        return this.text;
    }

    /**
     * Determines whether this document contains text or media content.
     * @return true if this document contains text content (accessible via
     * {@link #getText()}), false if it contains media content (accessible via
     * {@link #getMedia()})
     */
    public boolean isText() {
        return this.text != null;
    }

    /**
     * Returns the document's media content, if any.
     * @return the media content if {@link #isText()} is false, null otherwise
     * @see #isText()
     * @see #getText()
     */
    public Media getMedia() {
        return this.media;
    }

    @JsonIgnore
    public String getFormattedContent() {
        return this.getFormattedContent(MetadataMode.ALL);
    }

    public String getFormattedContent(MetadataMode metadataMode) {
        if (metadataMode == null) {
            throw new IllegalArgumentException("Metadata mode must not be null");
        }
        return this.contentFormatter.format(this, metadataMode);
    }

    /**
     * Helper content extractor that uses an external {@link ContentFormatter}.
     */
    public String getFormattedContent(ContentFormatter formatter, MetadataMode metadataMode) {
        if (formatter == null) {
            throw new IllegalArgumentException("formatter must not be null");
        }
        if (metadataMode == null) {
            throw new IllegalArgumentException("Metadata mode must not be null");
        }
        return formatter.format(this, metadataMode);
    }

    /**
     * Returns the metadata associated with this document.
     * <p>
     * The metadata values are restricted to simple types (string, int, float, boolean)
     * for compatibility with Vector Databases.
     * @return the metadata map
     */
    public Map<String, Object> getMetadata() {
        return this.metadata;
    }

    public Double getScore() {
        return this.score;
    }

    /**
     * Returns the content formatter associated with this document.
     * @return the current ContentFormatter instance used for formatting the document
     * content.
     */
    public ContentFormatter getContentFormatter() {
        return this.contentFormatter;
    }

    /**
     * Replace the document's {@link ContentFormatter}.
     * @param contentFormatter new formatter to use.
     */
    public void setContentFormatter(ContentFormatter contentFormatter) {
        this.contentFormatter = contentFormatter;
    }

    public Builder mutate() {
        return new Builder().id(this.id).text(this.text).media(this.media).metadata(this.metadata).score(this.score);
    }

    // Legacy method for backward compatibility
    @Deprecated
    public String getContent() {
        return this.text;
    }

    public static class Builder {

        private String id;
        private String text;
        private Media media;
        private Map<String, Object> metadata = new HashMap<>();
        private Double score;
        private IdGenerator idGenerator = new RandomIdGenerator();

        public Builder idGenerator(IdGenerator idGenerator) {
            if (idGenerator == null) {
                throw new IllegalArgumentException("idGenerator cannot be null");
            }
            this.idGenerator = idGenerator;
            return this;
        }

        public Builder id(String id) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("id cannot be null or empty");
            }
            this.id = id;
            return this;
        }

        /**
         * Sets the text content of the document.
         * <p>
         * Either text or media content must be set before building the document, but not
         * both.
         * @param text the text content
         * @return the builder instance
         * @see #media(Media)
         */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /**
         * Sets the media content of the document.
         * <p>
         * Either text or media content must be set before building the document, but not
         * both.
         * @param media the media content
         * @return the builder instance
         * @see #text(String)
         */
        public Builder media(Media media) {
            this.media = media;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            if (metadata == null) {
                throw new IllegalArgumentException("metadata cannot be null");
            }
            this.metadata = metadata;
            return this;
        }

        public Builder metadata(String key, Object value) {
            if (key == null) {
                throw new IllegalArgumentException("metadata key cannot be null");
            }
            if (value == null) {
                throw new IllegalArgumentException("metadata value cannot be null");
            }
            this.metadata.put(key, value);
            return this;
        }

        /**
         * Sets a score value for this document.
         * <p>
         * Common uses include:
         * <ul>
         * <li>Measure of similarity between the embedding value of the document's
         * text/media and a query vector, where higher scores indicate greater similarity
         * (opposite of distance measure)
         * <li>Text relevancy rankings from retrieval systems
         * <li>Custom relevancy metrics from RAG patterns
         * </ul>
         * <p>
         * Higher values typically indicate greater relevance or similarity.
         * @param score the document score, may be null
         * @return the builder instance
         */
        public Builder score(Double score) {
            this.score = score;
            return this;
        }

        // Legacy method for backward compatibility
        @Deprecated
        public Builder content(String content) {
            return this.text(content);
        }

        public RetrievedDoc build() {
            if (this.id == null || this.id.trim().isEmpty()) {
                this.id = this.idGenerator.generateId(this.text, this.metadata);
            }
            return new RetrievedDoc(this.id, this.text, this.media, this.metadata, this.score);
        }
    }
}
