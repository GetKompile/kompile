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

package ai.kompile.ocr.postprocess;

import ai.kompile.ocr.OcrResult;
import ai.kompile.ocr.document.ParsedDocument;
import ai.kompile.ocr.structured.ExtractedField;

import java.util.List;

/**
 * Interface for OCR post-processing.
 * Post-processors enhance OCR output through correction, normalization, and field extraction.
 */
public interface OcrPostProcessor {

    /**
     * Gets the processor name.
     */
    String getName();

    /**
     * Gets a description of what this processor does.
     */
    String getDescription();

    /**
     * Checks if this processor is available.
     */
    boolean isAvailable();

    /**
     * Processes OCR results to improve quality.
     *
     * @param ocrResult Raw OCR result
     * @param config Processing configuration
     * @return Enhanced OCR result
     */
    OcrResult process(OcrResult ocrResult, PostProcessConfig config);

    /**
     * Corrects text content.
     *
     * @param text Raw OCR text
     * @param context Context for correction (e.g., document type)
     * @return Corrected text
     */
    String correctText(String text, String context);

    /**
     * Extracts structured fields from OCR text.
     *
     * @param text OCR text
     * @param fieldTypes Types of fields to extract
     * @return Extracted fields
     */
    List<ExtractedField> extractFields(String text, List<String> fieldTypes);

    /**
     * Interprets handwritten content.
     *
     * @param text Raw handwriting OCR output
     * @param confidence OCR confidence
     * @return Interpreted text
     */
    String interpretHandwriting(String text, double confidence);

    /**
     * Post-processing configuration.
     */
    record PostProcessConfig(
        boolean enableCorrection,
        boolean enableFieldExtraction,
        boolean enableHandwritingInterpretation,
        List<String> fieldTypesToExtract,
        String documentContext,
        double minConfidenceThreshold
    ) {
        public static PostProcessConfig defaultConfig() {
            return new PostProcessConfig(
                true, true, true,
                List.of(), null, 0.5
            );
        }

        public static PostProcessConfig correctionOnly() {
            return new PostProcessConfig(
                true, false, false,
                List.of(), null, 0.0
            );
        }
    }
}
