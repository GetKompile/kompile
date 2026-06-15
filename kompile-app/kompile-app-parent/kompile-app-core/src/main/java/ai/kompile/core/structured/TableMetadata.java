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

import java.util.List;

/**
 * Metadata describing a table's structure and origin.
 *
 * @param rowCount       Number of data rows (excluding header)
 * @param columnCount    Number of columns
 * @param columnHeaders  List of column header names
 * @param pageNumber     Page number in source document (1-indexed)
 * @param tableIndex     Table index on the page (0-indexed)
 * @param extractionMethod Method used to extract the table (e.g., "tabula-lattice", "tabula-stream", "poi")
 */
public record TableMetadata(
    int rowCount,
    int columnCount,
    List<String> columnHeaders,
    int pageNumber,
    int tableIndex,
    String extractionMethod
) {

    /**
     * Creates a builder for TableMetadata.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for TableMetadata.
     */
    public static class Builder {
        private int rowCount;
        private int columnCount;
        private List<String> columnHeaders = List.of();
        private int pageNumber = 1;
        private int tableIndex = 0;
        private String extractionMethod = "unknown";

        public Builder rowCount(int rowCount) {
            this.rowCount = rowCount;
            return this;
        }

        public Builder columnCount(int columnCount) {
            this.columnCount = columnCount;
            return this;
        }

        public Builder columnHeaders(List<String> columnHeaders) {
            this.columnHeaders = columnHeaders != null ? List.copyOf(columnHeaders) : List.of();
            return this;
        }

        public Builder pageNumber(int pageNumber) {
            this.pageNumber = pageNumber;
            return this;
        }

        public Builder tableIndex(int tableIndex) {
            this.tableIndex = tableIndex;
            return this;
        }

        public Builder extractionMethod(String extractionMethod) {
            this.extractionMethod = extractionMethod;
            return this;
        }

        public TableMetadata build() {
            return new TableMetadata(rowCount, columnCount, columnHeaders, pageNumber, tableIndex, extractionMethod);
        }
    }

    /**
     * Returns a formatted description of the table dimensions.
     */
    public String getDimensionsDescription() {
        return String.format("%d rows x %d columns", rowCount, columnCount);
    }

    /**
     * Returns the column headers as a comma-separated string.
     */
    public String getHeadersAsString() {
        return String.join(", ", columnHeaders);
    }
}
