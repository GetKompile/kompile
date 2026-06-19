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

import lombok.Getter;

/**
 * Enumeration of OCR model types.
 * Each type represents a different capability in the OCR pipeline.
 */
@Getter
public enum OcrModelType {
    /**
     * Text detection - finds text regions in images.
     * Examples: DBNet, EAST, CRAFT
     */
    OCR_DETECTION("ocr_detection", "Text Detection"),

    /**
     * Text recognition - converts text region images to strings.
     * Examples: CRNN, SVTR, ViTSTR, TrOCR
     */
    OCR_RECOGNITION("ocr_recognition", "Text Recognition"),

    /**
     * Table extraction - identifies and structures tables.
     * Examples: TableFormer, PubLayNet, Docling TableStructure
     */
    OCR_TABLE("ocr_table", "Table Extraction"),

    /**
     * Layout understanding - maps text to semantic fields.
     * Examples: LayoutLM, LayoutLMv2, LayoutLMv3, DiT, Donut
     */
    LAYOUT_MODEL("layout_model", "Layout Understanding"),

    /**
     * End-to-end pipeline (detection + recognition combined).
     * Examples: PaddleOCR pipeline, DocTR pipeline
     */
    OCR_PIPELINE("ocr_pipeline", "OCR Pipeline"),

    /**
     * Document classification - classifies document types.
     * Examples: Invoice detector, receipt classifier
     */
    DOCUMENT_CLASSIFIER("document_classifier", "Document Classifier");

    private final String registryType;
    private final String displayName;

    OcrModelType(String registryType, String displayName) {
        this.registryType = registryType;
        this.displayName = displayName;
    }

    /**
     * Finds OcrModelType by registry type string.
     */
    public static OcrModelType fromRegistryType(String type) {
        for (OcrModelType t : values()) {
            if (t.registryType.equalsIgnoreCase(type)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown OCR model type: " + type);
    }
}
