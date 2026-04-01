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

package ai.kompile.ocr.datapipeline.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for image preprocessing before model input.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PreprocessConfig {

    /**
     * DPI for rendering PDF pages to images.
     */
    @Builder.Default
    private int dpi = 150;

    /**
     * Maximum image height (resized if larger).
     */
    @Builder.Default
    private int maxImageHeight = 2048;

    /**
     * Maximum image width (resized if larger).
     */
    @Builder.Default
    private int maxImageWidth = 2048;

    /**
     * Target image height for fixed-size models (0 = no resize).
     */
    @Builder.Default
    private int targetHeight = 0;

    /**
     * Target image width for fixed-size models (0 = no resize).
     */
    @Builder.Default
    private int targetWidth = 0;

    /**
     * Whether to maintain aspect ratio when resizing.
     */
    @Builder.Default
    private boolean maintainAspectRatio = true;

    /**
     * Image normalization settings.
     */
    @Builder.Default
    private ImageNormalization normalization = ImageNormalization.zeroToOne();

    /**
     * Bounding box configuration.
     */
    @Builder.Default
    private BBoxConfig bbox = BBoxConfig.pixel();

    /**
     * Whether to enable layout-based region cropping.
     */
    @Builder.Default
    private boolean enableLayoutCropping = false;

    /**
     * Whether external OCR is required before processing (e.g., LayoutLM).
     */
    @Builder.Default
    private boolean requiresExternalOcr = false;

    /**
     * Padding to add around cropped regions (pixels).
     */
    @Builder.Default
    private int cropPadding = 0;

    /**
     * Color mode for output tensor.
     */
    @Builder.Default
    private ColorMode colorMode = ColorMode.RGB;

    /**
     * Channel order for output tensor.
     */
    @Builder.Default
    private ChannelOrder channelOrder = ChannelOrder.CHW;

    /**
     * Creates default configuration.
     */
    public static PreprocessConfig defaults() {
        return PreprocessConfig.builder().build();
    }

    /**
     * Creates configuration for DeepSeek-OCR.
     */
    public static PreprocessConfig forDeepSeek() {
        return PreprocessConfig.builder()
                .dpi(300)
                .maxImageHeight(2048)
                .maxImageWidth(2048)
                .normalization(ImageNormalization.zeroToOne())
                .bbox(BBoxConfig.pixel())
                .enableLayoutCropping(false)
                .requiresExternalOcr(false)
                .build();
    }

    /**
     * Creates configuration for PaddleOCR PP-Structure.
     */
    public static PreprocessConfig forPaddleOcr() {
        return PreprocessConfig.builder()
                .dpi(150)
                .maxImageHeight(960)
                .maxImageWidth(960)
                .normalization(ImageNormalization.paddleOcr())
                .bbox(BBoxConfig.pixel())
                .enableLayoutCropping(true)
                .requiresExternalOcr(false)
                .build();
    }

    /**
     * Creates configuration for LayoutLM v3.
     */
    public static PreprocessConfig forLayoutLM() {
        return PreprocessConfig.builder()
                .dpi(150)
                .targetHeight(224)
                .targetWidth(224)
                .normalization(ImageNormalization.imagenet())
                .bbox(BBoxConfig.normalized1000())
                .enableLayoutCropping(false)
                .requiresExternalOcr(true)
                .build();
    }

    /**
     * Creates configuration for Docling/TableFormer.
     */
    public static PreprocessConfig forDocling() {
        return PreprocessConfig.builder()
                .dpi(72)
                .maxImageHeight(1024)
                .maxImageWidth(1024)
                .normalization(ImageNormalization.zeroToOne())
                .bbox(BBoxConfig.pixel())
                .enableLayoutCropping(true)
                .requiresExternalOcr(false)
                .build();
    }

    /**
     * Color mode for image processing.
     */
    public enum ColorMode {
        RGB,
        BGR,
        GRAYSCALE
    }

    /**
     * Channel order for tensor output.
     */
    public enum ChannelOrder {
        CHW,  // Channel first: [C, H, W]
        HWC   // Channel last: [H, W, C]
    }
}
