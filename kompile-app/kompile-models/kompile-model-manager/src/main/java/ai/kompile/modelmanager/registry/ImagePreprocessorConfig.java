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

package ai.kompile.modelmanager.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Image preprocessor configuration for VLM models, persisted in the model registry.
 * Maps to DL4J's {@code PreprocessorConfig} fields.
 *
 * <p>Controls how input images are transformed before being fed to the vision encoder:
 * resize, rescale, normalize, crop, pad.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImagePreprocessorConfig {

    /**
     * Processor type identifier (e.g., "DoImageProcessor", "CLIPImageProcessor").
     */
    @JsonProperty("image_processor_type")
    private String imageProcessorType;

    // ==================== Resize ====================

    @JsonProperty("do_resize")
    @Builder.Default
    private boolean doResize = true;

    /**
     * Target height for resizing. If null, uses shortestEdge/longestEdge logic.
     */
    @JsonProperty("size_height")
    private Integer sizeHeight;

    /**
     * Target width for resizing. If null, uses shortestEdge/longestEdge logic.
     */
    @JsonProperty("size_width")
    private Integer sizeWidth;

    /**
     * Resize so shortest edge matches this value (aspect-ratio preserving).
     */
    @JsonProperty("size_shortest_edge")
    private Integer sizeShortestEdge;

    /**
     * Resize so longest edge matches this value (aspect-ratio preserving).
     */
    @JsonProperty("size_longest_edge")
    private Integer sizeLongestEdge;

    /**
     * Resampling method (PIL codes: 0=nearest, 1=lanczos, 2=bilinear, 3=bicubic).
     */
    @JsonProperty("resample")
    private Integer resample;

    // ==================== Rescale ====================

    @JsonProperty("do_rescale")
    @Builder.Default
    private boolean doRescale = true;

    /**
     * Rescale factor applied to pixel values (default: 1/255 to normalize 0-255 to 0-1).
     */
    @JsonProperty("rescale_factor")
    @Builder.Default
    private double rescaleFactor = 1.0 / 255.0;

    // ==================== Normalize ====================

    @JsonProperty("do_normalize")
    @Builder.Default
    private boolean doNormalize = true;

    /**
     * Per-channel mean for normalization (e.g., CLIP: [0.48145466, 0.4578275, 0.40821073]).
     */
    @JsonProperty("image_mean")
    private double[] imageMean;

    /**
     * Per-channel std for normalization (e.g., CLIP: [0.26862954, 0.26130258, 0.27577711]).
     */
    @JsonProperty("image_std")
    private double[] imageStd;

    // ==================== Color ====================

    @JsonProperty("do_convert_rgb")
    @Builder.Default
    private boolean doConvertRgb = true;

    // ==================== Center Crop ====================

    @JsonProperty("do_center_crop")
    @Builder.Default
    private boolean doCenterCrop = false;

    @JsonProperty("crop_size_height")
    private Integer cropSizeHeight;

    @JsonProperty("crop_size_width")
    private Integer cropSizeWidth;

    // ==================== Padding ====================

    @JsonProperty("do_pad")
    @Builder.Default
    private boolean doPad = false;

    @JsonProperty("pad_size_height")
    private Integer padSizeHeight;

    @JsonProperty("pad_size_width")
    private Integer padSizeWidth;

    // ==================== Patch (ViT-specific) ====================

    /**
     * Patch size for ViT-style models (e.g., 16 for ViT-B/16).
     */
    @JsonProperty("patch_size")
    private Integer patchSize;

    /**
     * Number of input channels (default: 3 for RGB).
     */
    @JsonProperty("num_channels")
    @Builder.Default
    private int numChannels = 3;

    // ==================== Factory Methods ====================

    /**
     * CLIP-style defaults: 224x224, CLIP normalization.
     */
    public static ImagePreprocessorConfig clip() {
        return ImagePreprocessorConfig.builder()
                .imageProcessorType("CLIPImageProcessor")
                .doResize(true)
                .sizeHeight(224)
                .sizeWidth(224)
                .doRescale(true)
                .rescaleFactor(1.0 / 255.0)
                .doNormalize(true)
                .imageMean(new double[]{0.48145466, 0.4578275, 0.40821073})
                .imageStd(new double[]{0.26862954, 0.26130258, 0.27577711})
                .doCenterCrop(true)
                .cropSizeHeight(224)
                .cropSizeWidth(224)
                .build();
    }

    /**
     * SmolDocling defaults: 512x512, CLIP normalization.
     */
    public static ImagePreprocessorConfig smolDocling() {
        return ImagePreprocessorConfig.builder()
                .imageProcessorType("DoImageProcessor")
                .doResize(true)
                .sizeHeight(512)
                .sizeWidth(512)
                .doRescale(true)
                .rescaleFactor(1.0 / 255.0)
                .doNormalize(true)
                .imageMean(new double[]{0.48145466, 0.4578275, 0.40821073})
                .imageStd(new double[]{0.26862954, 0.26130258, 0.27577711})
                .build();
    }

    /**
     * ImageNet/ViT defaults: 224x224, ImageNet normalization.
     */
    public static ImagePreprocessorConfig imageNet() {
        return ImagePreprocessorConfig.builder()
                .imageProcessorType("ViTImageProcessor")
                .doResize(true)
                .sizeHeight(224)
                .sizeWidth(224)
                .doRescale(true)
                .rescaleFactor(1.0 / 255.0)
                .doNormalize(true)
                .imageMean(new double[]{0.485, 0.456, 0.406})
                .imageStd(new double[]{0.229, 0.224, 0.225})
                .build();
    }

    /**
     * Returns effective target height (from explicit size or default 224).
     */
    public int getEffectiveHeight() {
        if (sizeHeight != null) return sizeHeight;
        return 224;
    }

    /**
     * Returns effective target width (from explicit size or default 224).
     */
    public int getEffectiveWidth() {
        if (sizeWidth != null) return sizeWidth;
        return 224;
    }

    /**
     * Returns effective mean (from config or ImageNet defaults).
     */
    public double[] getEffectiveMean() {
        return imageMean != null ? imageMean : new double[]{0.485, 0.456, 0.406};
    }

    /**
     * Returns effective std (from config or ImageNet defaults).
     */
    public double[] getEffectiveStd() {
        return imageStd != null ? imageStd : new double[]{0.229, 0.224, 0.225};
    }
}
