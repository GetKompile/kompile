/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.pipelines.framework.api.data;

import ai.kompile.pipelines.framework.api.Configuration;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Represents a bounding box, typically used in object detection tasks.
 * It stores the coordinates (x1, y1, x2, y2), an optional label, and an optional probability.
 * Coordinates are typically normalized (0.0 to 1.0) or absolute pixel values,
 * depending on the context of use. (x1,y1) is usually top-left and (x2,y2) is bottom-right.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(fluent = true)
public class BoundingBox implements Configuration { // Implements Configuration

    @JsonProperty("x1")
    private double x1;
    @JsonProperty("y1")
    private double y1;
    @JsonProperty("x2")
    private double x2;
    @JsonProperty("y2")
    private double y2;

    @JsonProperty("label")
    private String label;

    @JsonProperty("probability")
    private Double probability;

    /**
     * Creates a new BoundingBox.
     * @param x1 Top-left x-coordinate.
     * @param y1 Top-left y-coordinate.
     * @param x2 Bottom-right x-coordinate.
     * @param y2 Bottom-right y-coordinate.
     * @return A new BoundingBox instance.
     */
    public static BoundingBox newBox(double x1, double y1, double x2, double y2) {
        return BoundingBox.builder().x1(x1).y1(y1).x2(x2).y2(y2).build();
    }

    /**
     * Creates a new BoundingBox with a label.
     * @param x1 Top-left x-coordinate.
     * @param y1 Top-left y-coordinate.
     * @param x2 Bottom-right x-coordinate.
     * @param y2 Bottom-right y-coordinate.
     * @param label The label for the bounding box.
     * @return A new BoundingBox instance.
     */
    public static BoundingBox newBox(double x1, double y1, double x2, double y2, String label) {
        return BoundingBox.builder().x1(x1).y1(y1).x2(x2).y2(y2).label(label).build();
    }

    /**
     * Creates a new BoundingBox with a label and probability.
     * @param x1 Top-left x-coordinate.
     * @param y1 Top-left y-coordinate.
     * @param x2 Bottom-right x-coordinate.
     * @param y2 Bottom-right y-coordinate.
     * @param label The label for the bounding box.
     * @param probability The probability or confidence score.
     * @return A new BoundingBox instance.
     */
    @JsonCreator
    public static BoundingBox newBox(
            @JsonProperty("x1") double x1, @JsonProperty("y1") double y1,
            @JsonProperty("x2") double x2, @JsonProperty("y2") double y2,
            @JsonProperty("label") String label, @JsonProperty("probability") Double probability) {
        return BoundingBox.builder()
                .x1(x1).y1(y1)
                .x2(x2).y2(y2)
                .label(label)
                .probability(probability)
                .build();
    }

    public double cx() {
        return (x1 + x2) / 2.0;
    }

    public double cy() {
        return (y1 + y2) / 2.0;
    }

    public double width() {
        return Math.abs(x2 - x1);
    }

    public double height() {
        return Math.abs(y2 - y1);
    }
}