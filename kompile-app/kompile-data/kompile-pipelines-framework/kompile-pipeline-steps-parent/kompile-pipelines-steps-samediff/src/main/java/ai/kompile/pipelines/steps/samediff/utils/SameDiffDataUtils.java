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

package ai.kompile.pipelines.steps.samediff.utils;

import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.framework.api.data.NDArrayType;
import ai.kompile.pipelines.framework.core.utils.NDArrayTypeConverter;
import org.nd4j.common.util.ArrayUtil;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.serde.json.JsonMappers;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * Utility methods for converting between Kompile Pipelines NDArray and ND4J INDArray.
 */

public class SameDiffDataUtils {

    private SameDiffDataUtils() {}

    /**
     * Converts a Kompile Pipelines NDArray to an ND4J INDArray.
     * Handles potential native INDArray wrapping and manual conversion from ByteBuffer.
     */
    public static INDArray convertToINDArray(NDArray kompileNDArray, String name) {
        Objects.requireNonNull(kompileNDArray, "Kompile NDArray to convert cannot be null for name: " + name);

        // Check if the Kompile NDArray already wraps an INDArray
        Object nativeObj = kompileNDArray.getNative();
        if (nativeObj instanceof INDArray) {
            return (INDArray) nativeObj;
        }

        ByteBuffer bb = kompileNDArray.buffer();
        if (bb == null) {
            throw new IllegalArgumentException("Kompile NDArray buffer cannot be null for conversion. Name: " + name);
        }
        // Ensure buffer is direct and has native byte order for ND4J
        bb = bb.order(ByteOrder.nativeOrder());
        if (!bb.isDirect()) {
            ByteBuffer directBb = ByteBuffer.allocateDirect(bb.remaining()).order(ByteOrder.nativeOrder());
            directBb.put(bb.slice());
            directBb.flip();
            bb = directBb;
        }

        DataType nd4jDataType = mapKompileToNd4jType(kompileNDArray.type(), name);
        long[] shape = kompileNDArray.shape();

        // Handle scalar and empty cases carefully
        if (shape == null || shape.length == 0) {
            if (kompileNDArray.length() == 1) {
                shape = new long[]{1, 1}; // ND4J often prefers 2D for scalars from buffers
            } else if (kompileNDArray.length() == 0) {
                return Nd4j.empty(nd4jDataType);
            } else {
                throw new IllegalArgumentException("Cannot determine INDArray shape for Kompile NDArray '" + name +
                        "' with null/empty shape and length > 1");
            }
        }

        long bufferLength = kompileNDArray.length();
        long expectedLength = ArrayUtil.prod(shape);

        if (bufferLength != expectedLength) {
            throw new IllegalArgumentException(String.format(
                    "Buffer length (%d) of Kompile NDArray '%s' does not match the product of its shape %s (%d).",
                    bufferLength, name, Arrays.toString(shape), expectedLength));
        }

        DataBuffer dataBuffer = Nd4j.createBuffer(bb, nd4jDataType, (int) bufferLength); // Use bufferLength here
        // Assuming 'c' order from Kompile NDArray buffer representation
        return Nd4j.create(dataBuffer, shape, Nd4j.getStrides(shape, 'c'), 0L, 'c', nd4jDataType);
    }

    /**
     * Converts an ND4J INDArray to a Kompile Pipelines NDArray.
     * Creates a Kompile NDArray implementation wrapping the INDArray.
     */
    public static NDArray convertFromINDArray(INDArray indArray, String name) {
        Objects.requireNonNull(indArray, "INDArray to convert cannot be null for name: " + name);
        final NDArrayType kompileType = mapNd4jToKompileType(indArray.dataType(), name);

        // Ensure the INDArray is C-contiguous for reliable buffer access
        // Use isView() check to avoid unnecessary duplication if already contiguous
        INDArray contiguousIndArray = (indArray.isView() || indArray.ordering() != 'c') ? indArray.dup('c') : indArray;

        // Get a direct ByteBuffer view or copy
        ByteBuffer bb = contiguousIndArray.data().asNio();
        ByteBuffer ownedBuffer;
        if (bb.isDirect()) {
            // Create a view that shares the memory but has independent position/limit/mark
            ownedBuffer = bb.slice().order(ByteOrder.nativeOrder());
        } else {
            // If buffer isn't direct (less common for native backends), copy to a direct buffer
            ownedBuffer = ByteBuffer.allocateDirect(bb.remaining()).order(ByteOrder.nativeOrder());
            ownedBuffer.put(bb.slice());
            ownedBuffer.flip();
        }


        final String finalName = name;
        final long[] finalShape = indArray.shape().clone(); // Clone shape array
        final ByteBuffer finalBuffer = ownedBuffer.asReadOnlyBuffer(); // Read-only view for safety
        final INDArray nativeRef = indArray; // Keep reference to original for getNative()

        // Create an anonymous inner class implementing the Kompile NDArray interface
        return new NDArray() {
            @Override public String name() { return finalName; }
            @Override public long[] shape() { return finalShape; }
            @Override public NDArrayType type() { return kompileType; }
            @Override public ByteBuffer buffer() {
                // Return a duplicate to prevent modification of position/limit by consumers
                return finalBuffer.duplicate();
            }
            @SuppressWarnings("unchecked")
            @Override public <T> T getNative() {
                // Return the original INDArray reference
                return (T) nativeRef;
            }
            // Optional: Implement efficient length() and bufferSizeInBytes()
            @Override public long length() { return ArrayUtil.prod(finalShape); }
            @Override public int bufferSizeInBytes() { return finalBuffer.remaining(); }
            // Optional: Implement equals/hashCode/toString based on content if needed
        };
    }

    private static DataType mapKompileToNd4jType(NDArrayType kompileType, String name) {
        return NDArrayTypeConverter.toNd4jDataType(kompileType);
    }

    private static NDArrayType mapNd4jToKompileType(DataType nd4jType, String name) {
        return NDArrayTypeConverter.fromNd4jDataType(nd4jType);
    }

    // Helper to deserialize IUpdater/ISchedule config from Data
    public static <T> T configFromJson(ai.kompile.pipelines.framework.api.data.Data configData, Class<T> clazz) {
        if (configData == null || configData.isEmpty()) {
            return null;
        }
        try {
            // Convert Kompile Data to JSON string, then parse with ND4J's mapper
            String json = configData.toJson();
            return JsonMappers.getMapper().readValue(json, clazz);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid configuration provided for " + clazz.getSimpleName(), e);
        }
    }
}