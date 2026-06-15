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

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Represents a bounding box for detected text or regions.
 * Supports both axis-aligned rectangles and rotated polygons.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BoundingBox {

    /**
     * Top-left X coordinate (pixels).
     */
    private int x;

    /**
     * Top-left Y coordinate (pixels).
     */
    private int y;

    /**
     * Width of the bounding box (pixels).
     */
    private int width;

    /**
     * Height of the bounding box (pixels).
     */
    private int height;

    /**
     * Optional: Polygon points for rotated text.
     * List of [x, y] coordinate pairs.
     */
    private List<int[]> polygon;

    /**
     * Optional: Rotation angle in degrees (for rotated boxes).
     */
    private Double rotation;

    /**
     * Page number this box belongs to (1-indexed).
     */
    @Builder.Default
    private int pageNumber = 1;

    /**
     * Creates a simple axis-aligned bounding box.
     */
    public static BoundingBox of(int x, int y, int width, int height) {
        return BoundingBox.builder()
                .x(x)
                .y(y)
                .width(width)
                .height(height)
                .build();
    }

    /**
     * Creates a bounding box with page number.
     */
    public static BoundingBox of(int x, int y, int width, int height, int pageNumber) {
        return BoundingBox.builder()
                .x(x)
                .y(y)
                .width(width)
                .height(height)
                .pageNumber(pageNumber)
                .build();
    }

    /**
     * Creates a bounding box from polygon points.
     * The axis-aligned bounds are computed from the polygon.
     */
    public static BoundingBox fromPolygon(List<int[]> polygon, int pageNumber) {
        if (polygon == null || polygon.isEmpty()) {
            throw new IllegalArgumentException("Polygon cannot be null or empty");
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        for (int[] point : polygon) {
            minX = Math.min(minX, point[0]);
            minY = Math.min(minY, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxY = Math.max(maxY, point[1]);
        }

        return BoundingBox.builder()
                .x(minX)
                .y(minY)
                .width(maxX - minX)
                .height(maxY - minY)
                .polygon(polygon)
                .pageNumber(pageNumber)
                .build();
    }

    /**
     * Gets the center X coordinate.
     */
    public int getCenterX() {
        return x + width / 2;
    }

    /**
     * Gets the center Y coordinate.
     */
    public int getCenterY() {
        return y + height / 2;
    }

    /**
     * Gets the area in pixels.
     */
    public int getArea() {
        return width * height;
    }

    /**
     * Checks if this box intersects with another box.
     */
    public boolean intersects(BoundingBox other) {
        if (other == null || this.pageNumber != other.pageNumber) {
            return false;
        }
        return x < other.x + other.width &&
               x + width > other.x &&
               y < other.y + other.height &&
               y + height > other.y;
    }

    /**
     * Checks if this box contains a point.
     */
    public boolean contains(int px, int py) {
        return px >= x && px < x + width && py >= y && py < y + height;
    }

    /**
     * Expands this box by a margin on all sides.
     */
    public BoundingBox expand(int margin) {
        return BoundingBox.builder()
                .x(x - margin)
                .y(y - margin)
                .width(width + 2 * margin)
                .height(height + 2 * margin)
                .pageNumber(pageNumber)
                .polygon(polygon)
                .rotation(rotation)
                .build();
    }

    /**
     * Computes the union of this box with another.
     */
    public BoundingBox union(BoundingBox other) {
        if (other == null) {
            return this;
        }
        int newX = Math.min(this.x, other.x);
        int newY = Math.min(this.y, other.y);
        int newMaxX = Math.max(this.x + this.width, other.x + other.width);
        int newMaxY = Math.max(this.y + this.height, other.y + other.height);

        return BoundingBox.builder()
                .x(newX)
                .y(newY)
                .width(newMaxX - newX)
                .height(newMaxY - newY)
                .pageNumber(this.pageNumber)
                .build();
    }

    /**
     * Converts to a normalized format (0-1 range) based on image dimensions.
     */
    public BoundingBox normalize(int imageWidth, int imageHeight) {
        return BoundingBox.builder()
                .x((int)(x * 1000.0 / imageWidth))
                .y((int)(y * 1000.0 / imageHeight))
                .width((int)(width * 1000.0 / imageWidth))
                .height((int)(height * 1000.0 / imageHeight))
                .pageNumber(pageNumber)
                .build();
    }
}
