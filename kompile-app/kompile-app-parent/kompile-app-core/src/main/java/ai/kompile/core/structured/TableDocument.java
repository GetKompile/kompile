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

import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a table extracted from a document with both full content and summary.
 *
 * <p>The table is stored in markdown format for LLM compatibility. The summary
 * is used for embedding and semantic search, while the full content is passed
 * to the LLM for answer generation.</p>
 */
public class TableDocument {

    private final String id;
    private final String markdownContent;
    private final String summary;
    private final TableMetadata tableMetadata;
    private final Map<String, Object> sourceMetadata;

    private TableDocument(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id must not be null");
        this.markdownContent = Objects.requireNonNull(builder.markdownContent, "markdownContent must not be null");
        this.summary = builder.summary != null ? builder.summary : generateDefaultSummary(builder);
        this.tableMetadata = Objects.requireNonNull(builder.tableMetadata, "tableMetadata must not be null");
        this.sourceMetadata = builder.sourceMetadata != null
            ? new HashMap<>(builder.sourceMetadata)
            : new HashMap<>();
    }

    private String generateDefaultSummary(Builder builder) {
        if (builder.tableMetadata == null) {
            return "Table";
        }
        TableMetadata meta = builder.tableMetadata;
        StringBuilder sb = new StringBuilder();
        sb.append("Table with ").append(meta.rowCount()).append(" rows and ")
          .append(meta.columnCount()).append(" columns.");
        if (!meta.columnHeaders().isEmpty()) {
            sb.append(" Columns: ").append(meta.getHeadersAsString()).append(".");
        }
        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public String getMarkdownContent() {
        return markdownContent;
    }

    public String getSummary() {
        return summary;
    }

    public TableMetadata getTableMetadata() {
        return tableMetadata;
    }

    public Map<String, Object> getSourceMetadata() {
        return new HashMap<>(sourceMetadata);
    }

    /**
     * Converts this table to a Spring AI Document for vector store indexing.
     * The document text is the summary (for embedding), and the full table
     * content is stored in metadata.
     *
     * @return Spring AI Document suitable for vector store
     */
    public Document toSearchDocument() {
        Document doc = new Document(summary);
        doc.getMetadata().putAll(createTableMetadataMap());
        doc.getMetadata().putAll(sourceMetadata);
        return doc;
    }

    /**
     * Converts this table to a Spring AI Document with full content.
     * Used when the full table content should be embedded (not just summary).
     *
     * @return Spring AI Document with full table content
     */
    public Document toFullContentDocument() {
        Document doc = new Document(markdownContent);
        doc.getMetadata().putAll(createTableMetadataMap());
        doc.getMetadata().putAll(sourceMetadata);
        return doc;
    }

    private Map<String, Object> createTableMetadataMap() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("content_type", "table");
        metadata.put("table_id", id);
        metadata.put("full_table_content", markdownContent);
        metadata.put("table_summary", summary);
        metadata.put("table_row_count", tableMetadata.rowCount());
        metadata.put("table_column_count", tableMetadata.columnCount());
        metadata.put("table_headers", tableMetadata.getHeadersAsString());
        metadata.put("table_page_number", tableMetadata.pageNumber());
        metadata.put("table_index", tableMetadata.tableIndex());
        metadata.put("table_extraction_method", tableMetadata.extractionMethod());
        return metadata;
    }

    /**
     * Builder for TableDocument.
     */
    public static class Builder {
        private String id;
        private String markdownContent;
        private String summary;
        private TableMetadata tableMetadata;
        private Map<String, Object> sourceMetadata;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder markdownContent(String markdownContent) {
            this.markdownContent = markdownContent;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder tableMetadata(TableMetadata tableMetadata) {
            this.tableMetadata = tableMetadata;
            return this;
        }

        public Builder sourceMetadata(Map<String, Object> sourceMetadata) {
            this.sourceMetadata = sourceMetadata;
            return this;
        }

        public Builder sourcePath(String path) {
            if (this.sourceMetadata == null) {
                this.sourceMetadata = new HashMap<>();
            }
            this.sourceMetadata.put("source_path", path);
            return this;
        }

        public Builder sourceFilename(String filename) {
            if (this.sourceMetadata == null) {
                this.sourceMetadata = new HashMap<>();
            }
            this.sourceMetadata.put("source_filename", filename);
            return this;
        }

        public TableDocument build() {
            return new TableDocument(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TableDocument that = (TableDocument) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TableDocument{" +
                "id='" + id + '\'' +
                ", rows=" + tableMetadata.rowCount() +
                ", cols=" + tableMetadata.columnCount() +
                ", page=" + tableMetadata.pageNumber() +
                '}';
    }
}
