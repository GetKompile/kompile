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
 * Configuration for bounding box format and normalization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BBoxConfig {

    /**
     * Format for bounding box coordinates.
     */
    @Builder.Default
    private BBoxFormat format = BBoxFormat.PIXEL;

    /**
     * Normalization range (e.g., 1000 for LayoutLM).
     */
    @Builder.Default
    private int normalizeRange = 0;

    /**
     * Whether to include bounding boxes in output.
     */
    @Builder.Default
    private boolean includeInOutput = true;

    /**
     * Coordinate format.
     */
    @Builder.Default
    private CoordinateFormat coordinateFormat = CoordinateFormat.XYXY;

    /**
     * Creates pixel coordinate configuration.
     */
    public static BBoxConfig pixel() {
        return BBoxConfig.builder()
                .format(BBoxFormat.PIXEL)
                .normalizeRange(0)
                .build();
    }

    /**
     * Creates normalized [0, 1000] configuration (LayoutLM style).
     */
    public static BBoxConfig normalized1000() {
        return BBoxConfig.builder()
                .format(BBoxFormat.NORMALIZED)
                .normalizeRange(1000)
                .build();
    }

    /**
     * Creates normalized [0, 1] configuration.
     */
    public static BBoxConfig normalized01() {
        return BBoxConfig.builder()
                .format(BBoxFormat.NORMALIZED)
                .normalizeRange(1)
                .build();
    }

    /**
     * Creates relative (x, y, width, height) configuration.
     */
    public static BBoxConfig relative() {
        return BBoxConfig.builder()
                .format(BBoxFormat.RELATIVE)
                .coordinateFormat(CoordinateFormat.XYWH)
                .build();
    }

    /**
     * Bounding box format types.
     */
    public enum BBoxFormat {
        /**
         * Raw pixel coordinates.
         */
        PIXEL,

        /**
         * Normalized to specified range.
         */
        NORMALIZED,

        /**
         * Relative to image dimensions (0-1).
         */
        RELATIVE
    }

    /**
     * Coordinate format types.
     */
    public enum CoordinateFormat {
        /**
         * [x1, y1, x2, y2] - top-left and bottom-right corners.
         */
        XYXY,

        /**
         * [x, y, width, height] - top-left corner and dimensions.
         */
        XYWH,

        /**
         * [cx, cy, width, height] - center point and dimensions.
         */
        CXCYWH
    }
}
