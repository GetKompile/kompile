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

package ai.kompile.core.loaders;

import java.util.Map;

/**
 * Information about a document for determining if it qualifies as a "large document"
 * that should be processed via streaming rather than loading entirely into memory.
 *
 * <p>Large documents are processed page-by-page to reduce memory footprint and enable
 * better batching of chunks for embedding operations.</p>
 *
 * <h2>Threshold Criteria</h2>
 * A document is considered "large" if EITHER:
 * <ul>
 *   <li>File size exceeds {@link #SIZE_THRESHOLD_BYTES} (default: 10MB)</li>
 *   <li>Page/section count exceeds {@link #PAGE_THRESHOLD} (default: 50 pages)</li>
 * </ul>
 *
 * @param sourceId       Unique identifier for the document source
 * @param fileName       Original filename
 * @param documentType   Document type (PDF, DOCX, XLSX, PPTX, etc.)
 * @param fileSizeBytes  File size in bytes
 * @param totalPages     Total number of pages/sections (-1 if unknown)
 * @param isLargeDocument Whether this document qualifies as "large" based on thresholds
 * @param metadata       Additional metadata from the document
 */
public record LargeDocumentInfo(
    String sourceId,
    String fileName,
    String documentType,
    long fileSizeBytes,
    int totalPages,
    boolean isLargeDocument,
    Map<String, Object> metadata
) {
    /**
     * Default size threshold for large document processing (10MB).
     * Documents larger than this will be streamed page-by-page.
     */
    public static final long SIZE_THRESHOLD_BYTES = 10 * 1024 * 1024; // 10MB

    /**
     * Default page/section threshold for large document processing.
     * Documents with more pages than this will be streamed.
     */
    public static final int PAGE_THRESHOLD = 50;

    /**
     * Creates a LargeDocumentInfo with automatic large document detection.
     *
     * @param sourceId      Unique identifier for the source
     * @param fileName      Original filename
     * @param documentType  Document type (PDF, DOCX, etc.)
     * @param fileSizeBytes File size in bytes
     * @param totalPages    Total pages (-1 if unknown)
     * @param metadata      Additional metadata
     * @return A new LargeDocumentInfo with isLargeDocument computed automatically
     */
    public static LargeDocumentInfo of(
            String sourceId,
            String fileName,
            String documentType,
            long fileSizeBytes,
            int totalPages,
            Map<String, Object> metadata) {
        boolean isLarge = fileSizeBytes > SIZE_THRESHOLD_BYTES ||
                          (totalPages > 0 && totalPages > PAGE_THRESHOLD);
        return new LargeDocumentInfo(sourceId, fileName, documentType,
                                     fileSizeBytes, totalPages, isLarge, metadata);
    }

    /**
     * Creates a LargeDocumentInfo with custom thresholds.
     *
     * @param sourceId        Unique identifier for the source
     * @param fileName        Original filename
     * @param documentType    Document type
     * @param fileSizeBytes   File size in bytes
     * @param totalPages      Total pages (-1 if unknown)
     * @param metadata        Additional metadata
     * @param sizeThreshold   Custom size threshold in bytes
     * @param pageThreshold   Custom page threshold
     * @return A new LargeDocumentInfo with isLargeDocument computed using custom thresholds
     */
    public static LargeDocumentInfo ofWithThresholds(
            String sourceId,
            String fileName,
            String documentType,
            long fileSizeBytes,
            int totalPages,
            Map<String, Object> metadata,
            long sizeThreshold,
            int pageThreshold) {
        boolean isLarge = fileSizeBytes > sizeThreshold ||
                          (totalPages > 0 && totalPages > pageThreshold);
        return new LargeDocumentInfo(sourceId, fileName, documentType,
                                     fileSizeBytes, totalPages, isLarge, metadata);
    }

    /**
     * Creates a LargeDocumentInfo from a DocumentSourceDescriptor.
     *
     * @param source       The document source descriptor
     * @param fileSizeBytes File size in bytes
     * @param totalPages   Total pages (-1 if unknown)
     * @param documentType Document type
     * @return A new LargeDocumentInfo
     */
    public static LargeDocumentInfo from(
            DocumentSourceDescriptor source,
            long fileSizeBytes,
            int totalPages,
            String documentType) {
        String sourceId = source.getSourceId() != null ?
                          source.getSourceId() : source.getOriginalFileName();
        return of(sourceId, source.getOriginalFileName(), documentType,
                  fileSizeBytes, totalPages,
                  source.getMetadata() != null ? source.getMetadata() : Map.of());
    }

    /**
     * Returns file size in megabytes.
     */
    public double fileSizeMB() {
        return fileSizeBytes / (1024.0 * 1024.0);
    }

    /**
     * Returns a human-readable description of why this document is/isn't large.
     */
    public String getLargeDocumentReason() {
        if (!isLargeDocument) {
            return "Document is within normal size limits";
        }

        StringBuilder reason = new StringBuilder("Document exceeds threshold: ");
        if (fileSizeBytes > SIZE_THRESHOLD_BYTES) {
            reason.append(String.format("size=%.1fMB (>%.0fMB)",
                    fileSizeMB(), SIZE_THRESHOLD_BYTES / (1024.0 * 1024.0)));
        }
        if (totalPages > PAGE_THRESHOLD) {
            if (fileSizeBytes > SIZE_THRESHOLD_BYTES) {
                reason.append(", ");
            }
            reason.append(String.format("pages=%d (>%d)", totalPages, PAGE_THRESHOLD));
        }
        return reason.toString();
    }
}
