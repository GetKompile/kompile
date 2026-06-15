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

package ai.kompile.ocr.document;

import ai.kompile.ocr.OcrResult;
import ai.kompile.ocr.audit.AuditTrail;
import ai.kompile.ocr.structured.ExtractedField;
import ai.kompile.ocr.structured.StructuredTable;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Final result of OCR processing on a document page.
 * Contains all extracted text, tables, fields, and full audit trail.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ParsedDocument {

    /**
     * Unique identifier for this parsed document.
     */
    private String id;

    /**
     * Source document identifier (file path, URL, etc).
     */
    private String sourceId;

    /**
     * Page number in the source document (1-indexed).
     */
    @Builder.Default
    private int pageNumber = 1;

    /**
     * Total pages in the source document.
     */
    private int totalPages;

    /**
     * Document type classification.
     */
    private DocumentType documentType;

    /**
     * Document complexity classification.
     */
    private DocumentComplexity complexity;

    /**
     * Full text content from OCR.
     */
    private String text;

    /**
     * Raw OCR result with all regions.
     */
    private OcrResult ocrResult;

    /**
     * Extracted structured tables.
     */
    private List<StructuredTable> tables;

    /**
     * Extracted semantic fields.
     */
    private List<ExtractedField> fields;

    /**
     * Full audit trail for traceability.
     */
    private AuditTrail auditTrail;

    /**
     * Whether processing was successful.
     */
    @Builder.Default
    private boolean success = true;

    /**
     * Error message if processing failed.
     */
    private String errorMessage;

    /**
     * Total processing time in milliseconds.
     */
    private long processingTimeMs;

    /**
     * Timestamp when processing completed.
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Additional metadata.
     */
    private Map<String, Object> metadata;

    /**
     * Creates a failed parsed document.
     */
    public static ParsedDocument failed(String sourceId, int pageNumber, String errorMessage) {
        return ParsedDocument.builder()
                .sourceId(sourceId)
                .pageNumber(pageNumber)
                .success(false)
                .errorMessage(errorMessage)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Gets the number of extracted tables.
     */
    public int getTableCount() {
        return tables == null ? 0 : tables.size();
    }

    /**
     * Gets the number of extracted fields.
     */
    public int getFieldCount() {
        return fields == null ? 0 : fields.size();
    }

    /**
     * Gets the overall OCR confidence.
     */
    public double getOverallConfidence() {
        if (ocrResult == null) {
            return 0.0;
        }
        return ocrResult.getOverallConfidence();
    }

    /**
     * Checks if the document has tables.
     */
    public boolean hasTables() {
        return tables != null && !tables.isEmpty();
    }

    /**
     * Checks if the document has extracted fields.
     */
    public boolean hasFields() {
        return fields != null && !fields.isEmpty();
    }

    /**
     * Gets all table content as markdown.
     */
    public String getTablesAsMarkdown() {
        if (tables == null || tables.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append(tables.get(i).toMarkdown());
        }
        return sb.toString();
    }

    /**
     * Converts to a Spring AI Document.
     */
    public org.springframework.ai.document.Document toSpringDocument() {
        StringBuilder content = new StringBuilder();
        if (text != null) {
            content.append(text);
        }
        if (hasTables()) {
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append(getTablesAsMarkdown());
        }

        org.springframework.ai.document.Document doc =
                new org.springframework.ai.document.Document(content.toString());

        doc.getMetadata().put("source", sourceId);
        doc.getMetadata().put("pageNumber", pageNumber);
        doc.getMetadata().put("totalPages", totalPages);
        doc.getMetadata().put("documentType", documentType != null ? documentType.name() : null);
        doc.getMetadata().put("complexity", complexity != null ? complexity.name() : null);
        doc.getMetadata().put("tableCount", getTableCount());
        doc.getMetadata().put("fieldCount", getFieldCount());
        doc.getMetadata().put("ocrConfidence", getOverallConfidence());
        doc.getMetadata().put("processingTimeMs", processingTimeMs);

        return doc;
    }
}
