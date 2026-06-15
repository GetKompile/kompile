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

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents a table extracted from a document.
 * First-class searchable entity with structured data.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableEntity extends DocumentEntity {

    /**
     * Table rows as list of cells.
     */
    private List<List<TableCell>> rows;

    /**
     * Column headers (if detected).
     */
    private List<String> headers;

    /**
     * HTML representation of the table.
     */
    private String htmlContent;

    /**
     * Markdown representation of the table.
     */
    private String markdownContent;

    /**
     * Semantic description of what the table contains.
     */
    private String description;

    /**
     * Gets the number of rows.
     */
    public int getRowCount() {
        return rows != null ? rows.size() : 0;
    }

    /**
     * Gets the number of columns.
     */
    public int getColumnCount() {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return rows.get(0).size();
    }

    /**
     * Gets a specific cell value.
     */
    public String getCellValue(int row, int col) {
        if (rows == null || row >= rows.size()) {
            return null;
        }
        List<TableCell> rowCells = rows.get(row);
        if (col >= rowCells.size()) {
            return null;
        }
        return rowCells.get(col).getText();
    }

    /**
     * Gets a row as list of cell values.
     */
    public List<String> getRowValues(int row) {
        if (rows == null || row >= rows.size()) {
            return List.of();
        }
        return rows.get(row).stream()
                .map(TableCell::getText)
                .collect(Collectors.toList());
    }

    @Override
    public Document toSearchDocument() {
        // Build semantic summary for embedding
        String summary = buildSummary();
        Document doc = new Document(summary);
        doc.getMetadata().putAll(getBaseMetadata());
        doc.getMetadata().put("index_mode", "semantic");
        doc.getMetadata().put("row_count", getRowCount());
        doc.getMetadata().put("column_count", getColumnCount());
        doc.getMetadata().put("headers", headers != null ? String.join(",", headers) : "");
        doc.getMetadata().put("full_content", toMarkdown());
        if (htmlContent != null) {
            doc.getMetadata().put("html_content", htmlContent);
        }
        return doc;
    }

    @Override
    public Document toFullDocument() {
        Document doc = new Document(toMarkdown());
        doc.getMetadata().putAll(getBaseMetadata());
        doc.getMetadata().put("index_mode", "content");
        doc.getMetadata().put("row_count", getRowCount());
        doc.getMetadata().put("column_count", getColumnCount());
        return doc;
    }

    @Override
    public String toMarkdown() {
        if (markdownContent != null) {
            return markdownContent;
        }
        return generateMarkdown();
    }

    /**
     * Generates HTML representation.
     */
    public String toHtml() {
        if (htmlContent != null) {
            return htmlContent;
        }
        return generateHtml();
    }

    private String buildSummary() {
        StringBuilder sb = new StringBuilder();

        if (description != null && !description.isEmpty()) {
            sb.append(description).append(" ");
        }

        sb.append("Table with ").append(getRowCount()).append(" rows and ")
                .append(getColumnCount()).append(" columns.");

        if (headers != null && !headers.isEmpty()) {
            sb.append(" Columns: ").append(String.join(", ", headers)).append(".");
        }

        // Add sample from first data row
        if (rows != null && rows.size() > 1) {
            List<String> sample = getRowValues(1).stream()
                    .limit(3)
                    .collect(Collectors.toList());
            if (!sample.isEmpty()) {
                sb.append(" Sample values: ").append(String.join(", ", sample)).append(".");
            }
        }

        return sb.toString();
    }

    private String generateMarkdown() {
        if (rows == null || rows.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int cols = getColumnCount();

        // Header row
        List<String> headerRow = headers != null ? headers : getRowValues(0);
        sb.append("| ").append(String.join(" | ", headerRow)).append(" |\n");

        // Separator
        sb.append("|");
        for (int i = 0; i < cols; i++) {
            sb.append(" --- |");
        }
        sb.append("\n");

        // Data rows
        int startRow = headers != null ? 0 : 1;
        for (int i = startRow; i < rows.size(); i++) {
            List<String> rowValues = getRowValues(i);
            sb.append("| ").append(String.join(" | ", rowValues)).append(" |\n");
        }

        return sb.toString();
    }

    private String generateHtml() {
        if (rows == null || rows.isEmpty()) {
            return "<table></table>";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<table>\n");

        // Header
        if (headers != null && !headers.isEmpty()) {
            sb.append("  <thead>\n    <tr>\n");
            for (String header : headers) {
                sb.append("      <th>").append(escapeHtml(header)).append("</th>\n");
            }
            sb.append("    </tr>\n  </thead>\n");
        }

        // Body
        sb.append("  <tbody>\n");
        int startRow = headers != null ? 0 : 1;

        // First row as header if no explicit headers
        if (headers == null && !rows.isEmpty()) {
            sb.append("    <tr>\n");
            for (TableCell cell : rows.get(0)) {
                sb.append("      <th");
                if (cell.getColspan() > 1) sb.append(" colspan=\"").append(cell.getColspan()).append("\"");
                if (cell.getRowspan() > 1) sb.append(" rowspan=\"").append(cell.getRowspan()).append("\"");
                sb.append(">").append(escapeHtml(cell.getText())).append("</th>\n");
            }
            sb.append("    </tr>\n");
        }

        for (int i = startRow; i < rows.size(); i++) {
            sb.append("    <tr>\n");
            for (TableCell cell : rows.get(i)) {
                sb.append("      <td");
                if (cell.getColspan() > 1) sb.append(" colspan=\"").append(cell.getColspan()).append("\"");
                if (cell.getRowspan() > 1) sb.append(" rowspan=\"").append(cell.getRowspan()).append("\"");
                sb.append(">").append(escapeHtml(cell.getText())).append("</td>\n");
            }
            sb.append("    </tr>\n");
        }
        sb.append("  </tbody>\n</table>");

        return sb.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Represents a single table cell.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableCell {
        private String text;
        private int colspan = 1;
        private int rowspan = 1;
        private boolean isHeader;

        public TableCell(String text) {
            this.text = text;
        }

        public static TableCell of(String text) {
            return new TableCell(text);
        }

        public static TableCell spanning(String text, int colspan, int rowspan) {
            return new TableCell(text, colspan, rowspan, false);
        }
    }
}
