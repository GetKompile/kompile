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

import ai.kompile.ocr.structured.ExtractedField;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.List;
import java.util.Map;

/**
 * Interface for layout understanding models.
 * These models map text and spatial information to semantic fields.
 *
 * <p>Examples: LayoutLM, LayoutLMv2, LayoutLMv3, DiT, Donut</p>
 */
public interface LayoutModel extends OcrModel {

    /**
     * Analyzes document layout and extracts semantic fields.
     *
     * @param image Page image as INDArray [1, C, H, W]
     * @param ocrResult OCR result with text and bounding boxes
     * @return Extracted fields with semantic labels
     */
    List<ExtractedField> extractFields(INDArray image, OcrResult ocrResult);

    /**
     * Classifies the document type.
     *
     * @param image Page image
     * @param ocrResult OCR result
     * @return Document type classification
     */
    DocumentClassification classifyDocument(INDArray image, OcrResult ocrResult);

    /**
     * Performs question answering on the document.
     *
     * @param image Page image
     * @param ocrResult OCR result
     * @param question Question to answer
     * @return Answer with confidence and source location
     */
    FieldAnswer answerQuestion(INDArray image, OcrResult ocrResult, String question);

    /**
     * Extracts specific field types from the document.
     *
     * @param image Page image
     * @param ocrResult OCR result
     * @param fieldTypes Types of fields to extract
     * @return Map of field type to extracted values
     */
    Map<String, List<ExtractedField>> extractFieldTypes(INDArray image, OcrResult ocrResult,
                                                        List<String> fieldTypes);

    /**
     * Document type classification result.
     */
    record DocumentClassification(
        String documentType,
        double confidence,
        List<TypeScore> allScores
    ) {
        public record TypeScore(String type, double score) {}

        public static DocumentClassification of(String type, double confidence) {
            return new DocumentClassification(type, confidence, null);
        }
    }

    /**
     * Answer to a document question.
     */
    record FieldAnswer(
        String answer,
        double confidence,
        BoundingBox sourceLocation,
        String sourceText
    ) {
        public static FieldAnswer of(String answer, double confidence) {
            return new FieldAnswer(answer, confidence, null, null);
        }

        public static FieldAnswer withSource(String answer, double confidence,
                                             BoundingBox location, String sourceText) {
            return new FieldAnswer(answer, confidence, location, sourceText);
        }
    }

    /**
     * Layout model input format.
     */
    record LayoutInputFormat(
        boolean requiresImage,          // whether image is required (some models text-only)
        boolean requiresOcr,            // whether pre-computed OCR is required
        int maxTokens,                  // maximum text tokens
        int maxBoxes,                   // maximum bounding boxes
        int imageHeight,                // expected image height
        int imageWidth                  // expected image width
    ) {
        public static LayoutInputFormat layoutLMv3() {
            return new LayoutInputFormat(true, true, 512, 512, 224, 224);
        }

        public static LayoutInputFormat donut() {
            return new LayoutInputFormat(true, false, 2048, 0, 960, 720);
        }
    }

    /**
     * Gets the expected input format.
     */
    LayoutInputFormat getInputFormat();

    /**
     * Layout extraction configuration.
     */
    record LayoutConfig(
        double confidenceThreshold,
        int maxFields,
        boolean includeSourceText
    ) {
        public static LayoutConfig defaultConfig() {
            return new LayoutConfig(0.5, 100, true);
        }
    }
}
