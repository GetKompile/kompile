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

import lombok.Getter;

/**
 * Classification of document source type based on image characteristics.
 */
@Getter
public enum DocumentType {
    /**
     * Pure scanned document - image-only, requires full OCR.
     */
    SCANNED("Scanned document requiring full OCR"),

    /**
     * Digital PDF with embedded text - may only need verification.
     */
    DIGITAL("Digital document with embedded text"),

    /**
     * Mixed document - some pages scanned, some digital.
     */
    MIXED("Mixed scanned and digital content"),

    /**
     * Photograph of document - may have perspective distortion.
     */
    PHOTOGRAPH("Photographed document"),

    /**
     * Form document - structured with fillable fields.
     */
    FORM("Structured form document"),

    /**
     * Unknown document type.
     */
    UNKNOWN("Unknown document type");

    private final String description;

    DocumentType(String description) {
        this.description = description;
    }

}
