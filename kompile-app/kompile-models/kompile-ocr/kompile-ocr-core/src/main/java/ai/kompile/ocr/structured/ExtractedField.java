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

package ai.kompile.ocr.structured;

import ai.kompile.ocr.BoundingBox;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Represents a semantic field extracted from a document.
 * Fields are labeled pieces of information with their source locations.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExtractedField {

    /**
     * Unique identifier for this field.
     */
    private String id;

    /**
     * Field label/name (e.g., "Invoice Number", "Customer Name").
     */
    private String label;

    /**
     * Extracted field value.
     */
    private String value;

    /**
     * Normalized/cleaned value.
     */
    private String normalizedValue;

    /**
     * Semantic field type.
     */
    private FieldType fieldType;

    /**
     * Bounding box for the field value in the source image.
     */
    private BoundingBox valueBoundingBox;

    /**
     * Bounding box for the field label (if separate from value).
     */
    private BoundingBox labelBoundingBox;

    /**
     * Page number where the field was found (1-indexed).
     */
    private int pageNumber;

    /**
     * Confidence score for extraction (0.0 to 1.0).
     */
    @Builder.Default
    private double confidence = 1.0;

    /**
     * Model used to extract this field.
     */
    private String extractedBy;

    /**
     * Whether the value was validated.
     */
    @Builder.Default
    private boolean validated = false;

    /**
     * Validation result if validated.
     */
    private ValidationStatus validationStatus;

    /**
     * Validation message if applicable.
     */
    private String validationMessage;

    /**
     * Validation status values.
     */
    public enum ValidationStatus {
        VALID,
        INVALID,
        WARNING,
        NOT_VALIDATED
    }

    /**
     * Creates a simple extracted field.
     */
    public static ExtractedField of(String label, String value, FieldType type) {
        return ExtractedField.builder()
                .label(label)
                .value(value)
                .fieldType(type)
                .build();
    }

    /**
     * Creates a field with location.
     */
    public static ExtractedField withLocation(String label, String value, FieldType type,
                                              BoundingBox location, int pageNumber) {
        return ExtractedField.builder()
                .label(label)
                .value(value)
                .fieldType(type)
                .valueBoundingBox(location)
                .pageNumber(pageNumber)
                .build();
    }

    /**
     * Creates a field with confidence.
     */
    public static ExtractedField withConfidence(String label, String value, FieldType type,
                                                double confidence) {
        return ExtractedField.builder()
                .label(label)
                .value(value)
                .fieldType(type)
                .confidence(confidence)
                .build();
    }

    /**
     * Gets the effective value (normalized if available, otherwise raw).
     */
    public String getEffectiveValue() {
        return normalizedValue != null ? normalizedValue : value;
    }

    /**
     * Checks if this field contains sensitive information.
     */
    public boolean isSensitive() {
        return fieldType != null && fieldType.isSensitive();
    }

    /**
     * Checks if validation is required for this field type.
     */
    public boolean requiresValidation() {
        return fieldType != null && fieldType.requiresValidation();
    }

    /**
     * Returns true if the field is valid or not validated.
     */
    public boolean isValid() {
        return validationStatus == null ||
               validationStatus == ValidationStatus.VALID ||
               validationStatus == ValidationStatus.NOT_VALIDATED;
    }

    /**
     * Gets the combined bounding box (union of label and value boxes).
     */
    public BoundingBox getCombinedBoundingBox() {
        if (valueBoundingBox == null) {
            return labelBoundingBox;
        }
        if (labelBoundingBox == null) {
            return valueBoundingBox;
        }
        return valueBoundingBox.union(labelBoundingBox);
    }
}
