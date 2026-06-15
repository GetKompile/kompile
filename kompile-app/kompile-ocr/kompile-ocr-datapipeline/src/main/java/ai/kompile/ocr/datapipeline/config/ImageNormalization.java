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
 * Configuration for image normalization during preprocessing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageNormalization {

    /**
     * Type of normalization to apply.
     */
    @Builder.Default
    private NormalizationType type = NormalizationType.ZERO_TO_ONE;

    /**
     * Per-channel mean values for normalization.
     * Applied as: (pixel - mean) / std
     */
    private double[] mean;

    /**
     * Per-channel standard deviation values.
     */
    private double[] std;

    /**
     * Scale factor (typically 255.0 to normalize 0-255 to 0-1).
     */
    @Builder.Default
    private double scale = 255.0;

    /**
     * Creates no normalization (raw pixel values).
     */
    public static ImageNormalization none() {
        return ImageNormalization.builder()
                .type(NormalizationType.NONE)
                .build();
    }

    /**
     * Creates 0-1 normalization (divide by 255).
     */
    public static ImageNormalization zeroToOne() {
        return ImageNormalization.builder()
                .type(NormalizationType.ZERO_TO_ONE)
                .scale(255.0)
                .build();
    }

    /**
     * Creates ImageNet normalization (used by LayoutLM, many vision models).
     */
    public static ImageNormalization imagenet() {
        return ImageNormalization.builder()
                .type(NormalizationType.MEAN_STD)
                .mean(new double[]{0.485, 0.456, 0.406})
                .std(new double[]{0.229, 0.224, 0.225})
                .scale(255.0)
                .build();
    }

    /**
     * Creates PaddleOCR normalization.
     */
    public static ImageNormalization paddleOcr() {
        return ImageNormalization.builder()
                .type(NormalizationType.MEAN_STD)
                .mean(new double[]{0.485, 0.456, 0.406})
                .std(new double[]{0.229, 0.224, 0.225})
                .scale(255.0)
                .build();
    }

    /**
     * Creates custom normalization with specified mean/std.
     */
    public static ImageNormalization custom(double[] mean, double[] std, double scale) {
        return ImageNormalization.builder()
                .type(NormalizationType.MEAN_STD)
                .mean(mean)
                .std(std)
                .scale(scale)
                .build();
    }

    /**
     * Creates -1 to 1 normalization.
     */
    public static ImageNormalization minusOneToOne() {
        return ImageNormalization.builder()
                .type(NormalizationType.MINUS_ONE_TO_ONE)
                .scale(127.5)
                .build();
    }

    /**
     * Types of normalization.
     */
    public enum NormalizationType {
        /**
         * No normalization - raw pixel values [0, 255].
         */
        NONE,

        /**
         * Scale to [0, 1] range: pixel / scale.
         */
        ZERO_TO_ONE,

        /**
         * Scale to [-1, 1] range: (pixel - scale) / scale.
         */
        MINUS_ONE_TO_ONE,

        /**
         * Apply mean/std normalization: (pixel/scale - mean) / std.
         */
        MEAN_STD
    }
}
