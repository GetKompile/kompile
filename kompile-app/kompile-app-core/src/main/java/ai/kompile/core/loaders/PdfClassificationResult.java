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

import java.util.List;

/**
 * Result of classifying a PDF's content to determine the optimal processing pipeline.
 *
 * <p>The classifier inspects each page's resources to detect embedded images
 * (XObject images, inline images) without rendering, enabling fast routing
 * decisions before committing to expensive VLM processing.</p>
 */
public record PdfClassificationResult(
        /** The classification category for routing */
        PdfContentType contentType,

        /** Total number of pages in the PDF */
        int pageCount,

        /** Number of pages that contain embedded images */
        int imagePagesCount,

        /** Zero-based indices of pages that contain images */
        List<Integer> imagePageIndices,

        /** Total characters extracted via fast text strip (0 if skipped) */
        long textCharCount,

        /** Whether any page has embedded images (XObject or inline) */
        boolean hasImages,

        /** Whether the PDF appears to be a scanned document (images but minimal text) */
        boolean hasScannedPages,

        /** Time taken for classification in milliseconds */
        long classificationTimeMs,

        /** Source file path */
        String sourcePath
) {
    /**
     * Content classification that drives pipeline routing.
     */
    public enum PdfContentType {
        /** PDF contains only extractable text - use standard text extraction + Tabula for tables */
        TEXT_ONLY,
        /** PDF contains embedded images on some/all pages - requires VLM pipeline */
        IMAGE_BASED,
        /** PDF has mix of text-heavy and image pages - can split processing */
        MIXED,
        /** Classification failed or file is not a valid PDF */
        UNKNOWN
    }

    /** Whether this PDF can be fully handled by the cheap text extraction pipeline */
    public boolean isTextOnly() {
        return contentType == PdfContentType.TEXT_ONLY;
    }

    /** Whether any part of this PDF requires VLM processing */
    public boolean requiresVlm() {
        return contentType == PdfContentType.IMAGE_BASED || contentType == PdfContentType.MIXED;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PdfContentType contentType = PdfContentType.UNKNOWN;
        private int pageCount;
        private int imagePagesCount;
        private List<Integer> imagePageIndices = List.of();
        private long textCharCount;
        private boolean hasImages;
        private boolean hasScannedPages;
        private long classificationTimeMs;
        private String sourcePath;

        public Builder contentType(PdfContentType contentType) { this.contentType = contentType; return this; }
        public Builder pageCount(int pageCount) { this.pageCount = pageCount; return this; }
        public Builder imagePagesCount(int imagePagesCount) { this.imagePagesCount = imagePagesCount; return this; }
        public Builder imagePageIndices(List<Integer> imagePageIndices) { this.imagePageIndices = imagePageIndices; return this; }
        public Builder textCharCount(long textCharCount) { this.textCharCount = textCharCount; return this; }
        public Builder hasImages(boolean hasImages) { this.hasImages = hasImages; return this; }
        public Builder hasScannedPages(boolean hasScannedPages) { this.hasScannedPages = hasScannedPages; return this; }
        public Builder classificationTimeMs(long classificationTimeMs) { this.classificationTimeMs = classificationTimeMs; return this; }
        public Builder sourcePath(String sourcePath) { this.sourcePath = sourcePath; return this; }

        public PdfClassificationResult build() {
            return new PdfClassificationResult(contentType, pageCount, imagePagesCount,
                    imagePageIndices, textCharCount, hasImages, hasScannedPages,
                    classificationTimeMs, sourcePath);
        }
    }
}
