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

package ai.kompile.ocr.validation;

import ai.kompile.ocr.audit.ValidationResult;
import ai.kompile.ocr.structured.ExtractedField;
import ai.kompile.ocr.structured.FieldType;

/**
 * Interface for validating OCR extracted values.
 * Validators check format, range, and consistency of extracted data.
 */
public interface OcrValidator {

    /**
     * Gets the unique identifier for this validator.
     */
    String getValidatorId();

    /**
     * Gets a human-readable name.
     */
    String getName();

    /**
     * Checks if this validator can handle the given field type.
     */
    boolean supports(FieldType fieldType);

    /**
     * Validates an extracted field.
     *
     * @param field The field to validate
     * @return Validation result
     */
    ValidationResult validate(ExtractedField field);

    /**
     * Validates a raw value with optional context.
     *
     * @param value The value to validate
     * @param fieldType The type of field
     * @param context Optional context for validation
     * @return Validation result
     */
    ValidationResult validate(String value, FieldType fieldType, ValidationContext context);

    /**
     * Attempts to correct an invalid value.
     *
     * @param value The invalid value
     * @param fieldType The type of field
     * @return Corrected value or null if correction not possible
     */
    default String attemptCorrection(String value, FieldType fieldType) {
        return null;
    }

    /**
     * Context for validation.
     */
    record ValidationContext(
        String sourceId,
        int pageNumber,
        String fieldLabel,
        java.util.Map<String, Object> additionalContext
    ) {
        public static ValidationContext empty() {
            return new ValidationContext(null, 0, null, null);
        }

        public static ValidationContext forField(String sourceId, int pageNumber, String label) {
            return new ValidationContext(sourceId, pageNumber, label, null);
        }
    }
}
