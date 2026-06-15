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

package ai.kompile.ocr.datapipeline.preprocess;

import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.List;
import java.util.Map;

/**
 * Result of preprocessing an image for model input.
 *
 * @param image Preprocessed image tensor [1, C, H, W] or [1, H, W, C]
 * @param regions Tokenized text regions with aligned bounding boxes (for LayoutLM-style models)
 * @param crops Named image crops for ensemble pipelines (e.g., table crops, figure crops)
 * @param originalWidth Original image width before preprocessing
 * @param originalHeight Original image height before preprocessing
 * @param processedWidth Processed image width
 * @param processedHeight Processed image height
 */
public record PreprocessedInput(
        INDArray image,
        List<TokenizedRegion> regions,
        Map<String, INDArray> crops,
        int originalWidth,
        int originalHeight,
        int processedWidth,
        int processedHeight
) {
    /**
     * Creates a simple preprocessed input with just an image.
     */
    public static PreprocessedInput imageOnly(INDArray image, int origW, int origH, int procW, int procH) {
        return new PreprocessedInput(image, null, null, origW, origH, procW, procH);
    }

    /**
     * Creates a preprocessed input with OCR regions (for LayoutLM).
     */
    public static PreprocessedInput withRegions(INDArray image, List<TokenizedRegion> regions,
                                                 int origW, int origH, int procW, int procH) {
        return new PreprocessedInput(image, regions, null, origW, origH, procW, procH);
    }

    /**
     * Creates a preprocessed input with crops (for ensemble pipelines).
     */
    public static PreprocessedInput withCrops(INDArray image, Map<String, INDArray> crops,
                                               int origW, int origH, int procW, int procH) {
        return new PreprocessedInput(image, null, crops, origW, origH, procW, procH);
    }

    /**
     * Checks if this input has OCR regions.
     */
    public boolean hasRegions() {
        return regions != null && !regions.isEmpty();
    }

    /**
     * Checks if this input has crops.
     */
    public boolean hasCrops() {
        return crops != null && !crops.isEmpty();
    }

    /**
     * Gets the scale factor from original to processed dimensions.
     */
    public double getScaleFactor() {
        return (double) processedWidth / originalWidth;
    }
}
