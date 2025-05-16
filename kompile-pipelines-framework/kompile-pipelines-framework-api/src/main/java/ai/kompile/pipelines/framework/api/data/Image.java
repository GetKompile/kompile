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
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;

/**
 * Represents an image.
 * This is an abstraction and can be backed by various underlying image representations.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public interface Image extends Configuration { // Implements Configuration

    /**
     * Gets the width of the image in pixels.
     * @return The image width.
     */
    int width();

    /**
     * Gets the height of the image in pixels.
     * @return The image height.
     */
    int height();

    /**
     * Gets the number of channels in the image (e.g., 1 for grayscale, 3 for RGB, 4 for RGBA).
     * @return The number of channels.
     */
    int channels();

    /**
     * Returns a name or identifier for the image, if available (e.g., filename).
     * @return The image name or null.
     */
    String name();

    /**
     * Returns the underlying platform-specific image object.
     * This allows pipeline steps to access the native image representation
     * if they are compatible with the specific Image implementation.
     * Use with caution and appropriate type checking/casting.
     *
     * @param <T> The expected type of the underlying native image object.
     * @return The native image object, or null if not applicable/available.
     */
    <T> T getNative();

    /**
     * Returns the image data as an NDArray, if convertible.
     * The format (e.g., CHW, HWC, channel order BGR/RGB) should be
     * defined by the implementation or conversion parameters.
     * @return An NDArray representation of the image, or null if not supported.
     */
    NDArray getAsNdArray();

    /**
     * Returns the image data as raw bytes in a specific encoding (e.g., "JPEG", "PNG").
     * @param format The desired image format string (e.g., "JPEG", "PNG").
     * @return Byte array of the encoded image, or null if conversion is not supported or format unknown.
     */
    byte[] getAsBytes(String format);

    /**
     * Returns the image data as raw bytes, attempting to use its original format
     * or a default lossless format like PNG if the original is unknown/unavailable.
     * @return Byte array of the image.
     */
    byte[] getAsBytes();
}