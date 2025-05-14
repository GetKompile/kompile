/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.framework.api.data;

import ai.kompile.pipelines.framework.api.Configuration;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Represents an N-Dimensional Array (NDArray).
 * This is an abstraction and can be backed by various underlying NDArray libraries.
 * The primary data is exposed as a {@link java.nio.ByteBuffer}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public interface NDArray extends Configuration { // Implements Configuration for broader use

    /**
     * Gets the name of the NDArray, if one was assigned.
     * @return The name, or null if not set.
     */
    String name();

    /**
     * Gets the shape of the NDArray.
     * @return An array of long integers representing the dimensions.
     */
    long[] shape();

    /**
     * Gets the data type of the elements in the NDArray.
     * @return The {@link NDArrayType}.
     */
    NDArrayType type();

    /**
     * Provides access to the underlying data as a {@link java.nio.ByteBuffer}.
     * The buffer's byte order should match the native system's byte order
     * or be explicitly defined by the implementation (usually little-endian).
     * @return A ByteBuffer view of the NDArray data. The buffer should be ready for reading.
     */
    ByteBuffer buffer();

    /**
     * Returns the total number of elements in the array.
     * This is the product of its shape dimensions.
     * @return The total number of elements.
     */
    @JsonIgnore // Calculated property
    default long length() {
        long[] shape = shape();
        if (shape == null || shape.length == 0) {
            // Handle scalar case (shape might be empty or just contain a single 1)
            // Or if it's meant to be an empty array.
            // If shape is truly empty for a scalar, length is 1.
            // If shape represents no data, length is 0. This depends on convention.
            // Let's assume empty shape array means 0 elements for safety unless defined as scalar.
            return (shape != null && shape.length == 1 && shape[0] == 0) ? 0 :
                    (shape == null || shape.length == 0) ? 0 : // Or 1 if empty shape means scalar by convention
                            Arrays.stream(shape).reduce(1L, (a, b) -> a * b);
        }
        long length = 1;
        for (long dim : shape) {
            if (dim == 0) return 0; // If any dimension is 0, total length is 0
            length *= dim;
        }
        return length;
    }

    /**
     * Returns the size of the underlying buffer in bytes, as reported by the buffer itself.
     * This should reflect the actual data size accessible through the buffer.
     * @return Buffer size in bytes (typically buffer.remaining() or buffer.limit()).
     */
    @JsonIgnore
    default int bufferSizeInBytes() { // Changed to int to match ByteBuffer methods like remaining()
        ByteBuffer buffer = buffer();
        return buffer == null ? 0 : buffer.remaining(); // Use remaining for read-ready buffers
    }

    /**
     * Returns a platform-specific object representing the array.
     * This allows pipeline steps to access the underlying native array
     * if they are compatible with the specific NDArray implementation.
     * Use with caution and appropriate type checking/casting.
     *
     * @param <T> The expected type of the underlying native array object.
     * @return The native array object, or null if not applicable/available.
     */
    @JsonIgnore
    <T> T getNative();
}