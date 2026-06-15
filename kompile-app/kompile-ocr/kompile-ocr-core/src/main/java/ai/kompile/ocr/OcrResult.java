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

package ai.kompile.ocr;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents the complete result of OCR processing on an image or page.
 * Contains all detected text regions with their bounding boxes and confidence scores.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OcrResult {

    /**
     * Unique identifier for this OCR result.
     */
    private String id;

    /**
     * Source document identifier.
     */
    private String sourceId;

    /**
     * Page number in the source document (1-indexed).
     */
    @Builder.Default
    private int pageNumber = 1;

    /**
     * Total number of pages in the source document.
     */
    private int totalPages;

    /**
     * Width of the processed image in pixels.
     */
    private int imageWidth;

    /**
     * Height of the processed image in pixels.
     */
    private int imageHeight;

    /**
     * List of detected text regions with recognized text.
     */
    private List<OcrRegion> regions;

    /**
     * Full text content concatenated from all regions.
     */
    private String fullText;

    /**
     * Overall confidence score for the entire page (0.0 to 1.0).
     */
    private double overallConfidence;

    /**
     * Model ID used for detection.
     */
    private String detectionModelId;

    /**
     * Model ID used for recognition.
     */
    private String recognitionModelId;

    /**
     * Processing time in milliseconds.
     */
    private long processingTimeMs;

    /**
     * Timestamp when OCR was performed.
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Additional metadata about the processing.
     */
    private Map<String, Object> metadata;

    /**
     * Error message if processing failed.
     */
    private String errorMessage;

    /**
     * Whether the processing was successful.
     */
    @Builder.Default
    private boolean success = true;

    /**
     * Creates a failed OCR result.
     */
    public static OcrResult failed(String sourceId, int pageNumber, String errorMessage) {
        return OcrResult.builder()
                .sourceId(sourceId)
                .pageNumber(pageNumber)
                .success(false)
                .errorMessage(errorMessage)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Computes and returns the full text from all regions if not already set.
     */
    public String computeFullText() {
        if (fullText != null) {
            return fullText;
        }
        if (regions == null || regions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (OcrRegion region : regions) {
            if (region.getText() != null) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(region.getText());
            }
        }
        return sb.toString();
    }

    /**
     * Gets the number of detected regions.
     */
    public int getRegionCount() {
        return regions == null ? 0 : regions.size();
    }
}
