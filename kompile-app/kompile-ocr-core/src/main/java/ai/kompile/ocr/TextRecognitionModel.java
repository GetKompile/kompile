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

import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.List;

/**
 * Interface for text recognition models.
 * These models convert cropped text region images to strings.
 *
 * <p>Examples: CRNN, SVTR, ViTSTR, TrOCR</p>
 */
public interface TextRecognitionModel extends OcrModel {

    /**
     * Recognizes text in a cropped text region image.
     *
     * @param regionImage Cropped text region as INDArray [1, C, H, W]
     * @return Recognized text with confidence
     */
    RecognizedText recognize(INDArray regionImage);

    /**
     * Batch recognition for multiple regions.
     *
     * @param regionImages Cropped regions as INDArray [N, C, H, W]
     * @return List of recognized text for each region
     */
    List<RecognizedText> recognizeBatch(INDArray regionImages);

    /**
     * Gets the character vocabulary for this model.
     *
     * @return list of characters the model can recognize
     */
    List<String> getVocabulary();

    /**
     * Gets the expected input height for text images.
     * Most models expect fixed height (32 or 48 pixels).
     *
     * @return expected height in pixels
     */
    int getExpectedHeight();

    /**
     * Gets whether width should be variable or fixed.
     */
    default boolean isVariableWidth() {
        return true;
    }

    /**
     * Recognized text result.
     */
    record RecognizedText(
        String text,
        double confidence,
        List<CharPrediction> charPredictions
    ) {
        /**
         * Creates a simple recognized text result.
         */
        public static RecognizedText of(String text, double confidence) {
            return new RecognizedText(text, confidence, null);
        }

        /**
         * Creates a result with per-character predictions.
         */
        public static RecognizedText withChars(String text, double confidence,
                                               List<CharPrediction> chars) {
            return new RecognizedText(text, confidence, chars);
        }

        /**
         * Gets the length of recognized text.
         */
        public int length() {
            return text == null ? 0 : text.length();
        }

        /**
         * Checks if result is empty.
         */
        public boolean isEmpty() {
            return text == null || text.isEmpty();
        }
    }

    /**
     * Per-character prediction with alternatives.
     */
    record CharPrediction(
        char character,
        double confidence,
        List<CharAlternative> alternatives
    ) {
        public static CharPrediction of(char c, double confidence) {
            return new CharPrediction(c, confidence, null);
        }
    }

    /**
     * Alternative character prediction.
     */
    record CharAlternative(
        char character,
        double confidence
    ) {}

    /**
     * Recognition configuration.
     */
    record RecognitionConfig(
        int maxLength,              // maximum text length to recognize
        double confidenceThreshold, // minimum confidence to return
        boolean returnAlternatives  // whether to return alternative predictions
    ) {
        public static RecognitionConfig defaultConfig() {
            return new RecognitionConfig(100, 0.0, false);
        }
    }

    /**
     * Recognizes with custom configuration.
     */
    default RecognizedText recognize(INDArray regionImage, RecognitionConfig config) {
        return recognize(regionImage);
    }
}
