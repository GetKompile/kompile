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

import java.util.List;

/**
 * Represents a detected text region with its bounding box and recognized text.
 * Each region corresponds to a line or block of text found in the image.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OcrRegion {

    /**
     * Index of this region in the page (0-indexed).
     */
    private int index;

    /**
     * Recognized text content.
     */
    private String text;

    /**
     * Bounding box for this text region.
     */
    private BoundingBox boundingBox;

    /**
     * Detection confidence (0.0 to 1.0).
     */
    private double detectionConfidence;

    /**
     * Recognition confidence (0.0 to 1.0).
     */
    private double recognitionConfidence;

    /**
     * Combined confidence (detection * recognition).
     */
    public double getCombinedConfidence() {
        return detectionConfidence * recognitionConfidence;
    }

    /**
     * Per-character confidence scores.
     */
    private List<CharConfidence> charConfidences;

    /**
     * Detected language (ISO 639-1 code).
     */
    private String language;

    /**
     * Type of text region (line, word, paragraph, etc).
     */
    @Builder.Default
    private RegionType regionType = RegionType.LINE;

    /**
     * Whether this region contains handwriting.
     */
    @Builder.Default
    private boolean handwriting = false;

    /**
     * Optional: Reading order index for multi-column layouts.
     */
    private Integer readingOrder;

    /**
     * Optional: Parent region index for hierarchical structures.
     */
    private Integer parentIndex;

    /**
     * Types of text regions.
     */
    public enum RegionType {
        WORD,
        LINE,
        PARAGRAPH,
        BLOCK,
        TABLE_CELL,
        HEADER,
        FOOTER
    }

    /**
     * Per-character confidence.
     */
    @Data
    @Builder
    public static class CharConfidence {
        private char character;
        private double confidence;

        public static CharConfidence of(char c, double confidence) {
            return CharConfidence.builder()
                    .character(c)
                    .confidence(confidence)
                    .build();
        }
    }

    /**
     * Creates a simple region with text and bounding box.
     */
    public static OcrRegion of(int index, String text, BoundingBox box,
                               double detectionConf, double recognitionConf) {
        return OcrRegion.builder()
                .index(index)
                .text(text)
                .boundingBox(box)
                .detectionConfidence(detectionConf)
                .recognitionConfidence(recognitionConf)
                .build();
    }

    /**
     * Returns true if this region has low confidence (below threshold).
     */
    public boolean isLowConfidence(double threshold) {
        return getCombinedConfidence() < threshold;
    }

    /**
     * Returns true if the recognized text is empty or null.
     */
    public boolean isEmpty() {
        return text == null || text.trim().isEmpty();
    }
}
