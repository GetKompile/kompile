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

package ai.kompile.ocr.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Represents a confidence score with its source for audit purposes.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfidenceScore {

    /**
     * The confidence value (0.0 to 1.0).
     */
    private double value;

    /**
     * Type of confidence being measured.
     */
    private ConfidenceType type;

    /**
     * Source model or component that produced this score.
     */
    private String source;

    /**
     * Description of what this confidence represents.
     */
    private String description;

    /**
     * Types of confidence scores.
     */
    public enum ConfidenceType {
        DETECTION("Text detection confidence"),
        RECOGNITION("Text recognition confidence"),
        TABLE_DETECTION("Table detection confidence"),
        TABLE_STRUCTURE("Table structure confidence"),
        LAYOUT("Layout analysis confidence"),
        FIELD_EXTRACTION("Field extraction confidence"),
        VALIDATION("Validation confidence"),
        OVERALL("Overall combined confidence");

        private final String description;

        ConfidenceType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Creates a detection confidence score.
     */
    public static ConfidenceScore detection(double value, String modelId) {
        return ConfidenceScore.builder()
                .value(value)
                .type(ConfidenceType.DETECTION)
                .source(modelId)
                .build();
    }

    /**
     * Creates a recognition confidence score.
     */
    public static ConfidenceScore recognition(double value, String modelId) {
        return ConfidenceScore.builder()
                .value(value)
                .type(ConfidenceType.RECOGNITION)
                .source(modelId)
                .build();
    }

    /**
     * Creates a table extraction confidence score.
     */
    public static ConfidenceScore tableExtraction(double value, String modelId) {
        return ConfidenceScore.builder()
                .value(value)
                .type(ConfidenceType.TABLE_STRUCTURE)
                .source(modelId)
                .build();
    }

    /**
     * Creates a layout confidence score.
     */
    public static ConfidenceScore layout(double value, String modelId) {
        return ConfidenceScore.builder()
                .value(value)
                .type(ConfidenceType.LAYOUT)
                .source(modelId)
                .build();
    }

    /**
     * Creates an overall combined confidence score.
     */
    public static ConfidenceScore overall(double value) {
        return ConfidenceScore.builder()
                .value(value)
                .type(ConfidenceType.OVERALL)
                .source("combined")
                .build();
    }

    /**
     * Checks if this is a high confidence score (>= 0.9).
     */
    public boolean isHighConfidence() {
        return value >= 0.9;
    }

    /**
     * Checks if this is a low confidence score (< 0.5).
     */
    public boolean isLowConfidence() {
        return value < 0.5;
    }

    /**
     * Gets confidence as a percentage string.
     */
    public String asPercentage() {
        return String.format("%.1f%%", value * 100);
    }
}
