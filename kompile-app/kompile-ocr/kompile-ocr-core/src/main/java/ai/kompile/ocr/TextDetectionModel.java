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
 * Interface for text detection models.
 * These models find text regions in images without recognizing the text.
 *
 * <p>Examples: DBNet, EAST, CRAFT, PaddleOCR detection</p>
 */
public interface TextDetectionModel extends OcrModel {

    /**
     * Detects text regions in an image.
     *
     * @param image Input image as INDArray [1, C, H, W] or [1, H, W, C] depending on model
     * @return List of detected text regions with bounding boxes
     */
    List<DetectedRegion> detect(INDArray image);

    /**
     * Batch detection for multiple images.
     *
     * @param images Input images as INDArray [N, C, H, W]
     * @return List of detected regions for each image
     */
    List<List<DetectedRegion>> detectBatch(INDArray images);

    /**
     * Gets the expected input format for this model.
     *
     * @return the input format descriptor
     */
    InputFormat getInputFormat();

    /**
     * Detected text region from detection model.
     */
    record DetectedRegion(
        BoundingBox bbox,
        double confidence,
        List<int[]> polygon
    ) {
        /**
         * Creates a region with just a bounding box.
         */
        public static DetectedRegion of(BoundingBox bbox, double confidence) {
            return new DetectedRegion(bbox, confidence, null);
        }

        /**
         * Creates a region with polygon points.
         */
        public static DetectedRegion withPolygon(List<int[]> polygon, double confidence, int pageNumber) {
            BoundingBox bbox = BoundingBox.fromPolygon(polygon, pageNumber);
            return new DetectedRegion(bbox, confidence, polygon);
        }
    }

    /**
     * Input format descriptor for detection models.
     */
    record InputFormat(
        boolean channelsFirst,      // true = [N,C,H,W], false = [N,H,W,C]
        int channels,               // typically 3 for RGB
        int height,                 // expected height (-1 for dynamic)
        int width,                  // expected width (-1 for dynamic)
        float[] mean,               // normalization mean per channel
        float[] std                 // normalization std per channel
    ) {
        /**
         * Default format for most OCR models.
         */
        public static InputFormat defaultFormat() {
            return new InputFormat(
                true,
                3,
                -1,
                -1,
                new float[]{0.485f, 0.456f, 0.406f},
                new float[]{0.229f, 0.224f, 0.225f}
            );
        }

        /**
         * Format for PaddleOCR models.
         */
        public static InputFormat paddleOcr() {
            return new InputFormat(
                true,
                3,
                960,
                -1,
                new float[]{0.485f, 0.456f, 0.406f},
                new float[]{0.229f, 0.224f, 0.225f}
            );
        }
    }

    /**
     * Configuration for detection.
     */
    record DetectionConfig(
        double confidenceThreshold,     // minimum confidence to keep (default 0.5)
        double nmsThreshold,            // NMS IoU threshold (default 0.3)
        int maxDetections,              // maximum detections to return (-1 for unlimited)
        boolean detectRotated           // whether to detect rotated text
    ) {
        public static DetectionConfig defaultConfig() {
            return new DetectionConfig(0.5, 0.3, -1, false);
        }
    }

    /**
     * Detects with custom configuration.
     */
    default List<DetectedRegion> detect(INDArray image, DetectionConfig config) {
        return detect(image);
    }
}
