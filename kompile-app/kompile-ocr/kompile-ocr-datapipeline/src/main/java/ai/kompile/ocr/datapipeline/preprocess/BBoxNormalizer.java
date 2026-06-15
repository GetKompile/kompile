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

import ai.kompile.ocr.BoundingBox;
import ai.kompile.ocr.datapipeline.config.BBoxConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes bounding boxes according to pipeline configuration.
 * Handles various coordinate formats required by different models.
 */
public class BBoxNormalizer {

    /**
     * Normalizes a list of bounding boxes.
     *
     * @param boxes List of bounding boxes in pixel coordinates
     * @param imageWidth Image width
     * @param imageHeight Image height
     * @param config Bounding box configuration
     * @return List of normalized bounding boxes as int arrays
     */
    public List<int[]> normalize(List<BoundingBox> boxes, int imageWidth, int imageHeight, BBoxConfig config) {
        List<int[]> normalized = new ArrayList<>(boxes.size());
        for (BoundingBox box : boxes) {
            normalized.add(normalizeBox(box, imageWidth, imageHeight, config));
        }
        return normalized;
    }

    /**
     * Normalizes a single bounding box.
     *
     * @param box Bounding box in pixel coordinates
     * @param imgW Image width
     * @param imgH Image height
     * @param config Bounding box configuration
     * @return Normalized bounding box as int array
     */
    public int[] normalizeBox(BoundingBox box, int imgW, int imgH, BBoxConfig config) {
        // Get coordinates based on format
        int x1 = box.getX();
        int y1 = box.getY();
        int x2 = box.getX() + box.getWidth();
        int y2 = box.getY() + box.getHeight();

        // Apply normalization
        int[] result = switch (config.getFormat()) {
            case PIXEL -> toCoordinateFormat(x1, y1, x2, y2, box.getWidth(), box.getHeight(), config);

            case NORMALIZED -> {
                int range = config.getNormalizeRange();
                int nx1 = (int) ((double) x1 * range / imgW);
                int ny1 = (int) ((double) y1 * range / imgH);
                int nx2 = (int) ((double) x2 * range / imgW);
                int ny2 = (int) ((double) y2 * range / imgH);
                int nw = nx2 - nx1;
                int nh = ny2 - ny1;
                yield toCoordinateFormat(nx1, ny1, nx2, ny2, nw, nh, config);
            }

            case RELATIVE -> {
                // Relative uses float precision, store as fixed-point int (multiply by 10000)
                int nx1 = (int) ((double) x1 / imgW * 10000);
                int ny1 = (int) ((double) y1 / imgH * 10000);
                int nx2 = (int) ((double) x2 / imgW * 10000);
                int ny2 = (int) ((double) y2 / imgH * 10000);
                int nw = nx2 - nx1;
                int nh = ny2 - ny1;
                yield toCoordinateFormat(nx1, ny1, nx2, ny2, nw, nh, config);
            }
        };

        return result;
    }

    /**
     * Converts coordinates to the specified format.
     */
    private int[] toCoordinateFormat(int x1, int y1, int x2, int y2, int w, int h, BBoxConfig config) {
        return switch (config.getCoordinateFormat()) {
            case XYXY -> new int[]{x1, y1, x2, y2};
            case XYWH -> new int[]{x1, y1, w, h};
            case CXCYWH -> {
                int cx = x1 + w / 2;
                int cy = y1 + h / 2;
                yield new int[]{cx, cy, w, h};
            }
        };
    }

    /**
     * Denormalizes a bounding box back to pixel coordinates.
     *
     * @param normalizedBox Normalized bounding box
     * @param imgW Image width
     * @param imgH Image height
     * @param config Bounding box configuration used for normalization
     * @return BoundingBox in pixel coordinates
     */
    public BoundingBox denormalize(int[] normalizedBox, int imgW, int imgH, BBoxConfig config) {
        // Convert from coordinate format to x1, y1, w, h
        int x1, y1, w, h;

        switch (config.getCoordinateFormat()) {
            case XYXY:
                x1 = normalizedBox[0];
                y1 = normalizedBox[1];
                w = normalizedBox[2] - normalizedBox[0];
                h = normalizedBox[3] - normalizedBox[1];
                break;
            case XYWH:
                x1 = normalizedBox[0];
                y1 = normalizedBox[1];
                w = normalizedBox[2];
                h = normalizedBox[3];
                break;
            case CXCYWH:
                w = normalizedBox[2];
                h = normalizedBox[3];
                x1 = normalizedBox[0] - w / 2;
                y1 = normalizedBox[1] - h / 2;
                break;
            default:
                throw new IllegalStateException("Unknown coordinate format");
        }

        // Denormalize based on format
        switch (config.getFormat()) {
            case PIXEL:
                // Already in pixel coordinates
                break;
            case NORMALIZED:
                int range = config.getNormalizeRange();
                x1 = x1 * imgW / range;
                y1 = y1 * imgH / range;
                w = w * imgW / range;
                h = h * imgH / range;
                break;
            case RELATIVE:
                // Convert from fixed-point
                x1 = x1 * imgW / 10000;
                y1 = y1 * imgH / 10000;
                w = w * imgW / 10000;
                h = h * imgH / 10000;
                break;
        }

        return BoundingBox.of(x1, y1, w, h);
    }

    /**
     * Scales bounding boxes when image is resized.
     *
     * @param boxes Original bounding boxes
     * @param originalWidth Original image width
     * @param originalHeight Original image height
     * @param newWidth New image width
     * @param newHeight New image height
     * @return Scaled bounding boxes
     */
    public List<BoundingBox> scaleBoxes(List<BoundingBox> boxes, int originalWidth, int originalHeight,
                                         int newWidth, int newHeight) {
        double scaleX = (double) newWidth / originalWidth;
        double scaleY = (double) newHeight / originalHeight;

        List<BoundingBox> scaled = new ArrayList<>(boxes.size());
        for (BoundingBox box : boxes) {
            scaled.add(BoundingBox.of(
                    (int) (box.getX() * scaleX),
                    (int) (box.getY() * scaleY),
                    (int) (box.getWidth() * scaleX),
                    (int) (box.getHeight() * scaleY)
            ));
        }
        return scaled;
    }
}
