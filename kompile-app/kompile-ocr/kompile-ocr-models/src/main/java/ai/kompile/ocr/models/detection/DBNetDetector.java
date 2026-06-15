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

package ai.kompile.ocr.models.detection;

import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.ocr.BoundingBox;
import ai.kompile.ocr.OcrModelType;
import ai.kompile.ocr.TextDetectionModel;
import ai.kompile.ocr.models.AbstractSameDiffOcrModel;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DBNet (Differentiable Binarization Network) text detector.
 * This is a popular architecture used in PaddleOCR and DocTR.
 *
 * <p>DBNet uses a learnable threshold for binarization, making it more
 * robust than traditional methods.</p>
 */
public class DBNetDetector extends AbstractSameDiffOcrModel implements TextDetectionModel {

    private static final String MODEL_ID = "dbnet-v2";
    private static final String MODEL_NAME = "DBNet Text Detector";
    private static final String MODEL_DESC = "Differentiable Binarization Network for text detection";

    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private final KompileModelManager modelManager;

    private double confidenceThreshold = 0.5;
    private double nmsThreshold = 0.3;

    public DBNetDetector(KompileModelManager modelManager) {
        super(MODEL_ID, MODEL_NAME, MODEL_DESC, OcrModelType.OCR_DETECTION);
        this.modelManager = modelManager;

        setCapabilities(ModelCapabilities.builder()
                .type(OcrModelType.OCR_DETECTION)
                .supportsBatch(true)
                .supportsHandwriting(false)
                .supportedLanguages(List.of("en", "zh", "ja", "ko"))
                .maxBatchSize(8)
                .inputHeight(960)
                .inputWidth(-1)  // Variable width
                .averageAccuracy(0.92)
                .build());
    }

    @Override
    protected File getModelFile() throws Exception {
        // Model manager will download if not present
        return modelManager.getModelFile(MODEL_ID);
    }

    @Override
    public List<DetectedRegion> detect(INDArray image) {
        if (!isLoaded()) {
            try {
                load();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load model: " + e.getMessage(), e);
            }
        }

        // Get original dimensions for coordinate mapping
        long[] shape = image.shape();
        int originalHeight = (int) shape[2];
        int originalWidth = (int) shape[3];

        // Preprocess image
        INDArray preprocessed = preprocessImage(image, MEAN, STD);

        // Resize if needed (maintain aspect ratio, max height 960)
        int targetHeight = Math.min(originalHeight, 960);
        float scale = (float) targetHeight / originalHeight;
        int targetWidth = Math.round(originalWidth * scale);
        // Make width divisible by 32
        targetWidth = (targetWidth / 32) * 32;
        if (targetWidth == 0) targetWidth = 32;

        preprocessed = resizeImage(preprocessed, targetHeight, targetWidth);

        // Run inference
        Map<String, INDArray> inputs = new HashMap<>();
        inputs.put(getInputNames().isEmpty() ? "input" : getInputNames().get(0), preprocessed);

        Map<String, INDArray> outputs = runInference(inputs);

        // Get probability map (usually first output)
        INDArray probMap = outputs.values().iterator().next();

        // Post-process to get bounding boxes
        List<DetectedRegion> regions = postProcess(probMap, originalHeight, originalWidth,
                targetHeight, targetWidth);

        // Clean up
        preprocessed.close();

        return regions;
    }

    @Override
    public List<List<DetectedRegion>> detectBatch(INDArray images) {
        List<List<DetectedRegion>> results = new ArrayList<>();
        long batchSize = images.size(0);

        for (int i = 0; i < batchSize; i++) {
            INDArray single = images.get(org.nd4j.linalg.indexing.NDArrayIndex.point(i),
                    org.nd4j.linalg.indexing.NDArrayIndex.all(),
                    org.nd4j.linalg.indexing.NDArrayIndex.all(),
                    org.nd4j.linalg.indexing.NDArrayIndex.all())
                    .reshape(1, images.size(1), images.size(2), images.size(3));
            results.add(detect(single));
        }

        return results;
    }

    @Override
    public InputFormat getInputFormat() {
        return InputFormat.defaultFormat();
    }

    /**
     * Post-processes the probability map to extract bounding boxes.
     */
    private List<DetectedRegion> postProcess(INDArray probMap, int origHeight, int origWidth,
                                             int procHeight, int procWidth) {
        List<DetectedRegion> regions = new ArrayList<>();

        // Binarize probability map
        INDArray binary = probMap.gt(confidenceThreshold);

        // Find connected components (simplified - use contour detection in real impl)
        // This is a placeholder for the actual connected component analysis
        List<int[][]> contours = findContours(binary);

        float scaleX = (float) origWidth / procWidth;
        float scaleY = (float) origHeight / procHeight;

        for (int[][] contour : contours) {
            // Get bounding box from contour
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

            for (int[] point : contour) {
                minX = Math.min(minX, point[0]);
                minY = Math.min(minY, point[1]);
                maxX = Math.max(maxX, point[0]);
                maxY = Math.max(maxY, point[1]);
            }

            // Scale back to original coordinates
            int x = Math.round(minX * scaleX);
            int y = Math.round(minY * scaleY);
            int w = Math.round((maxX - minX) * scaleX);
            int h = Math.round((maxY - minY) * scaleY);

            // Calculate confidence as average probability in region
            double confidence = calculateRegionConfidence(probMap, minX, minY, maxX, maxY);

            if (confidence >= confidenceThreshold) {
                BoundingBox bbox = BoundingBox.of(x, y, w, h);

                // Convert contour to polygon points
                List<int[]> polygon = new ArrayList<>();
                for (int[] point : contour) {
                    polygon.add(new int[]{
                            Math.round(point[0] * scaleX),
                            Math.round(point[1] * scaleY)
                    });
                }

                regions.add(new DetectedRegion(bbox, confidence, polygon));
            }
        }

        // Apply NMS to remove overlapping detections
        regions = applyNMS(regions, nmsThreshold);

        return regions;
    }

    /**
     * Finds contours in binary image (simplified placeholder).
     */
    private List<int[][]> findContours(INDArray binary) {
        List<int[][]> contours = new ArrayList<>();

        // This is a simplified placeholder
        // Real implementation would use OpenCV or custom contour detection
        int height = (int) binary.size(2);
        int width = (int) binary.size(3);

        // Simple horizontal scanning for text regions
        boolean inRegion = false;
        int regionStart = 0;

        for (int y = 0; y < height; y += 10) {
            for (int x = 0; x < width; x++) {
                float val = binary.getFloat(0, 0, y, x);
                if (val > 0.5 && !inRegion) {
                    inRegion = true;
                    regionStart = x;
                } else if (val <= 0.5 && inRegion) {
                    inRegion = false;
                    // Create contour for this region
                    int[][] contour = new int[][]{
                            {regionStart, y},
                            {x, y},
                            {x, y + 20},
                            {regionStart, y + 20}
                    };
                    contours.add(contour);
                }
            }
            if (inRegion) {
                inRegion = false;
            }
        }

        return contours;
    }

    /**
     * Calculates average confidence for a region.
     */
    private double calculateRegionConfidence(INDArray probMap, int x1, int y1, int x2, int y2) {
        int height = (int) probMap.size(2);
        int width = (int) probMap.size(3);

        x1 = Math.max(0, Math.min(x1, width - 1));
        x2 = Math.max(0, Math.min(x2, width - 1));
        y1 = Math.max(0, Math.min(y1, height - 1));
        y2 = Math.max(0, Math.min(y2, height - 1));

        if (x2 <= x1 || y2 <= y1) {
            return 0.0;
        }

        INDArray region = probMap.get(
                org.nd4j.linalg.indexing.NDArrayIndex.point(0),
                org.nd4j.linalg.indexing.NDArrayIndex.point(0),
                org.nd4j.linalg.indexing.NDArrayIndex.interval(y1, y2),
                org.nd4j.linalg.indexing.NDArrayIndex.interval(x1, x2)
        );

        return region.meanNumber().doubleValue();
    }

    /**
     * Applies Non-Maximum Suppression to remove overlapping detections.
     */
    private List<DetectedRegion> applyNMS(List<DetectedRegion> regions, double threshold) {
        if (regions.isEmpty()) {
            return regions;
        }

        // Sort by confidence descending
        regions.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));

        List<DetectedRegion> result = new ArrayList<>();
        boolean[] suppressed = new boolean[regions.size()];

        for (int i = 0; i < regions.size(); i++) {
            if (suppressed[i]) continue;

            result.add(regions.get(i));

            for (int j = i + 1; j < regions.size(); j++) {
                if (suppressed[j]) continue;

                double iou = calculateIoU(regions.get(i).bbox(), regions.get(j).bbox());
                if (iou > threshold) {
                    suppressed[j] = true;
                }
            }
        }

        return result;
    }

    /**
     * Calculates Intersection over Union for two bounding boxes.
     */
    private double calculateIoU(BoundingBox a, BoundingBox b) {
        int x1 = Math.max(a.getX(), b.getX());
        int y1 = Math.max(a.getY(), b.getY());
        int x2 = Math.min(a.getX() + a.getWidth(), b.getX() + b.getWidth());
        int y2 = Math.min(a.getY() + a.getHeight(), b.getY() + b.getHeight());

        if (x2 <= x1 || y2 <= y1) {
            return 0.0;
        }

        int intersection = (x2 - x1) * (y2 - y1);
        int areaA = a.getWidth() * a.getHeight();
        int areaB = b.getWidth() * b.getHeight();
        int union = areaA + areaB - intersection;

        return union > 0 ? (double) intersection / union : 0.0;
    }

    // Configuration methods
    public void setConfidenceThreshold(double threshold) {
        this.confidenceThreshold = threshold;
    }

    public void setNmsThreshold(double threshold) {
        this.nmsThreshold = threshold;
    }
}
