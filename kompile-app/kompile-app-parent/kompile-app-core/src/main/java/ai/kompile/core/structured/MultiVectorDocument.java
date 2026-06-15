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

package ai.kompile.core.structured;

import ai.kompile.core.retrievers.RetrievedDoc;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A document with multiple text representations for different purposes.
 *
 * <p>This class supports the multi-vector retrieval pattern where:</p>
 * <ul>
 *   <li>The summary text is embedded and used for semantic search</li>
 *   <li>The full content is stored in metadata and used for LLM context</li>
 * </ul>
 *
 * <p>Common use cases:</p>
 * <ul>
 *   <li>Tables: Summary describes the table, full content is the markdown table</li>
 *   <li>Images: Summary is the caption, full content could be OCR text</li>
 *   <li>Code: Summary describes functionality, full content is the code</li>
 * </ul>
 */
public class MultiVectorDocument {

    /**
     * Content types for multi-vector documents.
     */
    public enum ContentType {
        TABLE("table"),
        TEXT("text"),
        IMAGE("image"),
        CODE("code"),
        CHART("chart");

        private final String value;

        ContentType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private final String id;
    private final String summaryText;
    private final String fullContent;
    private final ContentType contentType;
    private final Map<String, Object> metadata;

    private MultiVectorDocument(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id must not be null");
        this.summaryText = Objects.requireNonNull(builder.summaryText, "summaryText must not be null");
        this.fullContent = Objects.requireNonNull(builder.fullContent, "fullContent must not be null");
        this.contentType = builder.contentType != null ? builder.contentType : ContentType.TEXT;
        this.metadata = builder.metadata != null ? new HashMap<>(builder.metadata) : new HashMap<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a MultiVectorDocument from a TableDocument.
     */
    public static MultiVectorDocument fromTable(TableDocument table) {
        return builder()
            .id(table.getId())
            .summaryText(table.getSummary())
            .fullContent(table.getMarkdownContent())
            .contentType(ContentType.TABLE)
            .metadata(table.getSourceMetadata())
            .metadata("table_row_count", table.getTableMetadata().rowCount())
            .metadata("table_column_count", table.getTableMetadata().columnCount())
            .metadata("table_headers", table.getTableMetadata().getHeadersAsString())
            .metadata("table_page_number", table.getTableMetadata().pageNumber())
            .build();
    }

    public String getId() {
        return id;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public String getFullContent() {
        return fullContent;
    }

    public ContentType getContentType() {
        return contentType;
    }

    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }

    /**
     * Converts to a Spring AI Document for vector store indexing.
     * Uses the summary text for embedding, stores full content in metadata.
     *
     * @return Document with summary as text, full content in metadata
     */
    public Document toSearchDocument() {
        Document doc = new Document(summaryText);
        doc.getMetadata().put("id", id);
        doc.getMetadata().put("content_type", contentType.getValue());
        doc.getMetadata().put("full_content", fullContent);
        doc.getMetadata().putAll(metadata);
        return doc;
    }

    /**
     * Converts to a Spring AI Document with full content as text.
     * Used when the full content should be embedded.
     *
     * @return Document with full content as text
     */
    public Document toFullContentDocument() {
        Document doc = new Document(fullContent);
        doc.getMetadata().put("id", id);
        doc.getMetadata().put("content_type", contentType.getValue());
        doc.getMetadata().put("summary", summaryText);
        doc.getMetadata().putAll(metadata);
        return doc;
    }

    /**
     * Extracts the full content from a retrieved document's metadata.
     * Used after retrieval to get the full content for LLM context.
     *
     * @param doc The retrieved document (Spring AI Document)
     * @return The full content, or the document text if full_content not in metadata
     */
    public static String extractFullContent(Document doc) {
        Object fullContent = doc.getMetadata().get("full_content");
        if (fullContent instanceof String) {
            return (String) fullContent;
        }
        // Fallback: check for table-specific field
        Object tableContent = doc.getMetadata().get("full_table_content");
        if (tableContent instanceof String) {
            return (String) tableContent;
        }
        // Final fallback: return the document text
        return doc.getText();
    }

    /**
     * Extracts the full content from a RetrievedDoc's metadata.
     * Used after retrieval to get the full content for LLM context.
     *
     * @param doc The retrieved document (Kompile RetrievedDoc)
     * @return The full content, or null if not found in metadata
     */
    public static String extractFullContent(RetrievedDoc doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        Map<String, Object> metadata = doc.getMetadata();

        // Check for multi-vector full_content field
        Object fullContent = metadata.get("full_content");
        if (fullContent instanceof String && !((String) fullContent).isEmpty()) {
            return (String) fullContent;
        }

        // Check for table-specific field
        Object tableContent = metadata.get("full_table_content");
        if (tableContent instanceof String && !((String) tableContent).isEmpty()) {
            return (String) tableContent;
        }

        // Return null to indicate no multi-vector content found
        return null;
    }

    /**
     * Checks if a document is a multi-vector document (has full_content in metadata).
     */
    public static boolean isMultiVectorDocument(Document doc) {
        return doc.getMetadata().containsKey("full_content")
            || doc.getMetadata().containsKey("full_table_content");
    }

    /**
     * Checks if a RetrievedDoc is a multi-vector document.
     */
    public static boolean isMultiVectorDocument(RetrievedDoc doc) {
        if (doc == null || doc.getMetadata() == null) {
            return false;
        }
        return doc.getMetadata().containsKey("full_content")
            || doc.getMetadata().containsKey("full_table_content");
    }

    /**
     * Builder for MultiVectorDocument.
     */
    public static class Builder {
        private String id;
        private String summaryText;
        private String fullContent;
        private ContentType contentType;
        private Map<String, Object> metadata = new HashMap<>();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder summaryText(String summaryText) {
            this.summaryText = summaryText;
            return this;
        }

        public Builder fullContent(String fullContent) {
            this.fullContent = fullContent;
            return this;
        }

        public Builder contentType(ContentType contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder sourcePath(String path) {
            this.metadata.put("source_path", path);
            return this;
        }

        public Builder sourceFilename(String filename) {
            this.metadata.put("source_filename", filename);
            return this;
        }

        public Builder pageNumber(int pageNumber) {
            this.metadata.put("page_number", pageNumber);
            return this;
        }

        public MultiVectorDocument build() {
            return new MultiVectorDocument(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MultiVectorDocument that = (MultiVectorDocument) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MultiVectorDocument{" +
                "id='" + id + '\'' +
                ", contentType=" + contentType +
                ", summaryLength=" + summaryText.length() +
                ", fullContentLength=" + fullContent.length() +
                '}';
    }
}
