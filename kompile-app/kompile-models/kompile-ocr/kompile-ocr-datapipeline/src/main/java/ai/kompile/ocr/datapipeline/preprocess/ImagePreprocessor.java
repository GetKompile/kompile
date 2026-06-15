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

import ai.kompile.ocr.datapipeline.config.ImageNormalization;
import ai.kompile.ocr.datapipeline.config.PreprocessConfig;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Preprocesses images according to pipeline configuration.
 * Handles resizing, normalization, and tensor conversion.
 *
 * <p>This class serves generic OCR preprocessing (detection, recognition).
 * For VLM-specific preprocessing (tiling, attention masks, frame batching),
 * use DL4J's {@code org.eclipse.deeplearning4j.vlm.preprocessing.VLMImagePreprocessor}
 * which handles HuggingFace-compatible image preprocessing including
 * {@code preprocessor_config.json} loading.</p>
 */
public class ImagePreprocessor {

    private static final Logger log = LoggerFactory.getLogger(ImagePreprocessor.class);

    /**
     * Preprocesses a BufferedImage according to the configuration.
     *
     * @param image Input image
     * @param config Preprocessing configuration
     * @return Preprocessed input ready for model consumption
     */
    public PreprocessedInput process(BufferedImage image, PreprocessConfig config) {
        int origWidth = image.getWidth();
        int origHeight = image.getHeight();

        // Resize if needed
        BufferedImage processed = resizeIfNeeded(image, config);
        int procWidth = processed.getWidth();
        int procHeight = processed.getHeight();

        // Convert to tensor
        INDArray tensor = imageToTensor(processed, config);

        // Apply normalization
        tensor = normalize(tensor, config.getNormalization());

        return PreprocessedInput.imageOnly(tensor, origWidth, origHeight, procWidth, procHeight);
    }

    /**
     * Resizes image if it exceeds maximum dimensions or needs fixed size.
     */
    private BufferedImage resizeIfNeeded(BufferedImage image, PreprocessConfig config) {
        int width = image.getWidth();
        int height = image.getHeight();

        int targetWidth = width;
        int targetHeight = height;

        // Check if fixed size is required
        if (config.getTargetWidth() > 0 && config.getTargetHeight() > 0) {
            targetWidth = config.getTargetWidth();
            targetHeight = config.getTargetHeight();
        } else {
            // Apply max dimension constraints
            if (width > config.getMaxImageWidth() || height > config.getMaxImageHeight()) {
                if (config.isMaintainAspectRatio()) {
                    double scale = Math.min(
                            (double) config.getMaxImageWidth() / width,
                            (double) config.getMaxImageHeight() / height
                    );
                    targetWidth = (int) (width * scale);
                    targetHeight = (int) (height * scale);
                } else {
                    targetWidth = Math.min(width, config.getMaxImageWidth());
                    targetHeight = Math.min(height, config.getMaxImageHeight());
                }
            }
        }

        if (targetWidth != width || targetHeight != height) {
            log.debug("Resizing image from {}x{} to {}x{}", width, height, targetWidth, targetHeight);
            return resize(image, targetWidth, targetHeight);
        }

        return image;
    }

    /**
     * Resizes image to specified dimensions.
     */
    private BufferedImage resize(BufferedImage original, int targetWidth, int targetHeight) {
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return resized;
    }

    /**
     * Converts BufferedImage to INDArray tensor.
     * Uses bulk array operations to avoid per-pixel JNI overhead.
     */
    private INDArray imageToTensor(BufferedImage image, PreprocessConfig config) {
        int height = image.getHeight();
        int width = image.getWidth();
        int channels = config.getColorMode() == PreprocessConfig.ColorMode.GRAYSCALE ? 1 : 3;
        boolean isCHW = config.getChannelOrder() == PreprocessConfig.ChannelOrder.CHW;
        boolean isBGR = config.getColorMode() == PreprocessConfig.ColorMode.BGR;
        boolean isGray = config.getColorMode() == PreprocessConfig.ColorMode.GRAYSCALE;

        // Bulk extract all pixels at once (single call vs width*height calls)
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

        // Pre-allocate flat array for tensor data
        float[] data = new float[channels * height * width];

        // Single pass: separate channels in correct order
        // Pure Java loop - no JNI calls
        int hwSize = height * width;
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            if (isGray) {
                // Grayscale: single channel
                data[i] = 0.299f * r + 0.587f * g + 0.114f * b;
            } else if (isCHW) {
                // CHW layout: data[c * H * W + pixel_index]
                if (isBGR) {
                    data[i] = b;
                    data[hwSize + i] = g;
                    data[2 * hwSize + i] = r;
                } else {
                    data[i] = r;
                    data[hwSize + i] = g;
                    data[2 * hwSize + i] = b;
                }
            } else {
                // HWC layout: data[pixel_index * 3 + channel]
                int base = i * 3;
                if (isBGR) {
                    data[base] = b;
                    data[base + 1] = g;
                    data[base + 2] = r;
                } else {
                    data[base] = r;
                    data[base + 1] = g;
                    data[base + 2] = b;
                }
            }
        }

        // Single Nd4j.create call (1 JNI call vs millions of putScalar calls)
        long[] shape = isCHW ? new long[]{1, channels, height, width}
                             : new long[]{1, height, width, channels};
        return Nd4j.create(data, shape);
    }

    /**
     * Applies normalization to tensor.
     */
    private INDArray normalize(INDArray tensor, ImageNormalization norm) {
        if (norm == null || norm.getType() == ImageNormalization.NormalizationType.NONE) {
            return tensor;
        }

        switch (norm.getType()) {
            case ZERO_TO_ONE:
                return tensor.div(norm.getScale());

            case MINUS_ONE_TO_ONE:
                return tensor.sub(norm.getScale()).div(norm.getScale());

            case MEAN_STD:
                // First scale to 0-1
                INDArray scaled = tensor.div(norm.getScale());

                // Then apply per-channel mean/std normalization
                double[] mean = norm.getMean();
                double[] std = norm.getStd();

                if (mean != null && std != null) {
                    for (int c = 0; c < mean.length; c++) {
                        INDArray channel = scaled.get(
                                NDArrayIndex.all(),
                                NDArrayIndex.point(c),
                                NDArrayIndex.all(),
                                NDArrayIndex.all()
                        );
                        channel.subi(mean[c]).divi(std[c]);
                    }
                }
                return scaled;

            default:
                return tensor;
        }
    }

    /**
     * Crops a region from the preprocessed image.
     *
     * @param image Full image tensor
     * @param x Left coordinate
     * @param y Top coordinate
     * @param width Crop width
     * @param height Crop height
     * @param config Preprocessing config
     * @return Cropped tensor
     */
    public INDArray cropRegion(INDArray image, int x, int y, int width, int height, PreprocessConfig config) {
        int padding = config.getCropPadding();

        // Apply padding
        int x1 = Math.max(0, x - padding);
        int y1 = Math.max(0, y - padding);
        int x2 = x + width + padding;
        int y2 = y + height + padding;

        // Get image dimensions
        int imgHeight, imgWidth;
        if (config.getChannelOrder() == PreprocessConfig.ChannelOrder.CHW) {
            imgHeight = (int) image.shape()[2];
            imgWidth = (int) image.shape()[3];
        } else {
            imgHeight = (int) image.shape()[1];
            imgWidth = (int) image.shape()[2];
        }

        // Clamp to image bounds
        x2 = Math.min(x2, imgWidth);
        y2 = Math.min(y2, imgHeight);

        // Crop
        if (config.getChannelOrder() == PreprocessConfig.ChannelOrder.CHW) {
            return image.get(
                    NDArrayIndex.all(),
                    NDArrayIndex.all(),
                    NDArrayIndex.interval(y1, y2),
                    NDArrayIndex.interval(x1, x2)
            );
        } else {
            return image.get(
                    NDArrayIndex.all(),
                    NDArrayIndex.interval(y1, y2),
                    NDArrayIndex.interval(x1, x2),
                    NDArrayIndex.all()
            );
        }
    }
}
