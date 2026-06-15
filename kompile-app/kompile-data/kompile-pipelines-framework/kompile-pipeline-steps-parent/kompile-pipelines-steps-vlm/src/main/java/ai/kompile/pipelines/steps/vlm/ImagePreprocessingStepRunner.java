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

package ai.kompile.pipelines.steps.vlm;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.Image;
import ai.kompile.pipelines.steps.vlm.util.VLMSameDiffUtils;
import org.eclipse.deeplearning4j.vlm.preprocessing.ImageTiler;
import org.eclipse.deeplearning4j.vlm.preprocessing.VLMImagePreprocessor;
import org.eclipse.deeplearning4j.vlm.model.VisionEncoderUtils;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * Preprocesses images for VLM input.
 *
 * Handles tiling, normalization, and conversion to tensor format.
 * Follows the Idefics3 image preprocessing pattern:
 * 1. Tile image into sub-images + global thumbnail
 * 2. Rescale pixel values (0-255 to 0-1)
 * 3. Normalize with mean/std
 * 4. Create pixel_attention_mask
 *
 * Input Data keys:
 *   - image: Image type (java.awt.image.BufferedImage backed)
 *
 * Output Data keys:
 *   - pixel_values: NDArray [1, num_tiles, 3, tile_h, tile_w]
 *   - pixel_attention_mask: NDArray [1, num_tiles, tile_h, tile_w]
 *
 * Config parameters:
 *   - tileSize: Tile dimension (default: 364)
 *   - maxTiles: Maximum number of tiles (default: 5)
 *   - imageMean: Normalization mean [R,G,B] (default: [0.5, 0.5, 0.5])
 *   - imageStd: Normalization std [R,G,B] (default: [0.5, 0.5, 0.5])
 *   - rescaleFactor: Pixel rescale factor (default: 1/255.0)
 */
public class ImagePreprocessingStepRunner implements PipelineStepRunner {

    private static final Logger log = LoggerFactory.getLogger(ImagePreprocessingStepRunner.class);

    private int tileSize = 364;
    private int maxTiles = 5;
    private float[] imageMean = {0.5f, 0.5f, 0.5f};
    private float[] imageStd = {0.5f, 0.5f, 0.5f};
    private float rescaleFactor = 1.0f / 255.0f;
    private boolean initialized = false;
    private VLMImagePreprocessor vlmPreprocessor;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        Data params = stepConfig.getParameters();
        this.tileSize = params.getInt32(VLMConstants.PARAM_TILE_SIZE, 364);
        this.maxTiles = params.getInt32(VLMConstants.PARAM_MAX_TILES, 5);

        // Parse mean/std arrays if provided as overrides
        List<Double> meanList = params.getList(VLMConstants.PARAM_IMAGE_MEAN,
                ai.kompile.pipelines.framework.api.data.ValueType.DOUBLE);
        if (meanList != null && meanList.size() == 3) {
            for (int i = 0; i < 3; i++) {
                imageMean[i] = meanList.get(i).floatValue();
            }
        }

        List<Double> stdList = params.getList(VLMConstants.PARAM_IMAGE_STD,
                ai.kompile.pipelines.framework.api.data.ValueType.DOUBLE);
        if (stdList != null && stdList.size() == 3) {
            for (int i = 0; i < 3; i++) {
                imageStd[i] = stdList.get(i).floatValue();
            }
        }

        Double rescale = params.getDouble(VLMConstants.PARAM_RESCALE_FACTOR);
        if (rescale != null) {
            this.rescaleFactor = rescale.floatValue();
        }

        // Try to load VLMImagePreprocessor from preprocessor_config.json if model directory is available
        String modelUri = params.getString(VLMConstants.PARAM_MODEL_URI);
        if (modelUri != null) {
            try {
                File modelDir = new File(modelUri).getParentFile();
                if (modelDir == null) {
                    modelDir = new File(modelUri);
                }
                File preprocessorConfig = new File(modelDir, "preprocessor_config.json");
                if (preprocessorConfig.exists()) {
                    vlmPreprocessor = VLMImagePreprocessor.fromConfig(preprocessorConfig);
                    log.info("Loaded VLMImagePreprocessor from {}", preprocessorConfig.getAbsolutePath());
                }
            } catch (Exception e) {
                log.debug("Could not load preprocessor_config.json, using manual params: {}", e.getMessage());
            }
        }

        this.initialized = true;
        log.info("ImagePreprocessingStepRunner initialized: tileSize={}, maxTiles={}, useVlmPreprocessor={}",
                tileSize, maxTiles, vlmPreprocessor != null);
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("ImagePreprocessingStepRunner not initialized");
        }

        Image image = input.getImage("image");
        if (image == null) {
            throw new IllegalArgumentException("Input Data must contain 'image' key");
        }

        // Get BufferedImage from Image wrapper
        Object nativeImage = image.getNative();
        if (!(nativeImage instanceof BufferedImage)) {
            throw new IllegalArgumentException("Image must be backed by BufferedImage");
        }
        BufferedImage bufferedImage = (BufferedImage) nativeImage;

        // Use DL4J's ImageTiler for aspect-ratio-aware tiling
        ImageTiler.SplitImageResult splitResult = ImageTiler.splitImageForVLM(
                bufferedImage, tileSize, maxTiles);

        List<BufferedImage> frames = splitResult.frames;
        int numTiles = frames.size();
        log.debug("ImageTiler split image ({}x{}) into {} frames ({}x{} grid + thumbnail)",
                bufferedImage.getWidth(), bufferedImage.getHeight(),
                numTiles, splitResult.numCols, splitResult.numRows);

        INDArray pixelValues;

        if (vlmPreprocessor != null) {
            // Use DL4J's VisionEncoderUtils for batch frame preprocessing
            // This handles rescaling, normalization, and tensor conversion per preprocessor_config.json
            pixelValues = VisionEncoderUtils.preprocessFrames(frames, vlmPreprocessor, maxTiles);
        } else {
            // Manual preprocessing with config-provided mean/std/rescale overrides
            pixelValues = preprocessFramesManual(frames);
        }

        // Create pixel attention mask using DL4J's ImageTiler
        INDArray pixelAttentionMask = ImageTiler.createPixelAttentionMask(
                numTiles, tileSize, tileSize);

        // Update mask for tiles that have padding (content regions smaller than tile size)
        List<ImageTiler.ContentRegion> contentRegions = splitResult.contentRegions;
        if (contentRegions != null) {
            for (int i = 0; i < contentRegions.size() && i < numTiles; i++) {
                ImageTiler.ContentRegion region = contentRegions.get(i);
                if (region.width < tileSize || region.height < tileSize) {
                    // Zero out mask for padded regions
                    for (int y = region.height; y < tileSize; y++) {
                        for (int x = 0; x < tileSize; x++) {
                            pixelAttentionMask.putScalar(new long[]{0, i, y, x}, 0);
                        }
                    }
                    for (int y = 0; y < tileSize; y++) {
                        for (int x = region.width; x < tileSize; x++) {
                            pixelAttentionMask.putScalar(new long[]{0, i, y, x}, 0);
                        }
                    }
                }
            }
        }

        Data result = Data.empty();
        result.put(VLMConstants.KEY_PIXEL_VALUES,
                VLMSameDiffUtils.fromINDArray(pixelValues, VLMConstants.KEY_PIXEL_VALUES));
        result.put(VLMConstants.KEY_PIXEL_ATTENTION_MASK,
                VLMSameDiffUtils.fromINDArray(pixelAttentionMask, VLMConstants.KEY_PIXEL_ATTENTION_MASK));

        return result;
    }

    /**
     * Manual frame preprocessing using config-provided normalization params.
     * Used as fallback when no preprocessor_config.json is available.
     */
    private INDArray preprocessFramesManual(List<BufferedImage> frames) {
        int numTiles = frames.size();
        float[] pixelData = new float[numTiles * 3 * tileSize * tileSize];

        for (int tileIdx = 0; tileIdx < numTiles; tileIdx++) {
            BufferedImage frame = frames.get(tileIdx);
            int frameW = frame.getWidth();
            int frameH = frame.getHeight();
            int tilePixelOffset = tileIdx * 3 * tileSize * tileSize;

            // Extract pixels in bulk
            int[] pixels = frame.getRGB(0, 0, frameW, frameH, null, 0, frameW);
            int tileSizeSq = tileSize * tileSize;

            for (int y = 0; y < tileSize; y++) {
                for (int x = 0; x < tileSize; x++) {
                    int pixelPos = y * tileSize + x;

                    if (x < frameW && y < frameH) {
                        int rgb = pixels[y * frameW + x];
                        float r = ((rgb >> 16) & 0xFF) * rescaleFactor;
                        float g = ((rgb >> 8) & 0xFF) * rescaleFactor;
                        float b = (rgb & 0xFF) * rescaleFactor;

                        // Normalize
                        r = (r - imageMean[0]) / imageStd[0];
                        g = (g - imageMean[1]) / imageStd[1];
                        b = (b - imageMean[2]) / imageStd[2];

                        pixelData[tilePixelOffset + pixelPos] = r;
                        pixelData[tilePixelOffset + tileSizeSq + pixelPos] = g;
                        pixelData[tilePixelOffset + 2 * tileSizeSq + pixelPos] = b;
                    }
                    // Padded pixels remain 0.0f
                }
            }
        }

        return Nd4j.create(pixelData,
                new long[]{1, numTiles, 3, tileSize, tileSize}, 'c').castTo(DataType.FLOAT);
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        if (vlmPreprocessor != null) {
            try {
                vlmPreprocessor.shutdown();
            } catch (Exception e) {
                log.debug("Error shutting down VLMImagePreprocessor: {}", e.getMessage());
            }
            vlmPreprocessor = null;
        }
        initialized = false;
    }
}
