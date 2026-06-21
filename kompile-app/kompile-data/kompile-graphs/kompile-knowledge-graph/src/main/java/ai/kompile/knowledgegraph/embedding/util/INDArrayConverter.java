/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.knowledgegraph.embedding.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * JPA AttributeConverter for INDArray to byte[] conversion.
 *
 * <p>Stores INDArray as a compact binary format:
 * <ul>
 *   <li>4 bytes: number of dimensions</li>
 *   <li>4 bytes per dimension: shape</li>
 *   <li>remaining: float data (little-endian)</li>
 * </ul>
 */
@Converter
public class INDArrayConverter implements AttributeConverter<INDArray, byte[]> {

    private static final Logger log = LoggerFactory.getLogger(INDArrayConverter.class);

    @Override
    public byte[] convertToDatabaseColumn(INDArray array) {
        if (array == null) {
            return null;
        }

        try {
            long[] shape = array.shape();
            int numElements = (int) array.length();

            // Calculate size: 4 (num dims) + 4 * dims + 4 * elements
            int size = 4 + (shape.length * 4) + (numElements * 4);
            ByteBuffer buffer = ByteBuffer.allocate(size);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            // Write number of dimensions
            buffer.putInt(shape.length);

            // Write shape
            for (long dim : shape) {
                buffer.putInt((int) dim);
            }

            // Write data as floats
            float[] data = array.data().asFloat();
            for (float f : data) {
                buffer.putFloat(f);
            }

            return buffer.array();
        } catch (Exception e) {
            log.error("Failed to convert INDArray to bytes", e);
            return null;
        }
    }

    @Override
    public INDArray convertToEntityAttribute(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            // Read number of dimensions
            int numDims = buffer.getInt();

            // Read shape
            long[] shape = new long[numDims];
            for (int i = 0; i < numDims; i++) {
                shape[i] = buffer.getInt();
            }

            // Calculate number of elements
            int numElements = 1;
            for (long dim : shape) {
                numElements *= (int) dim;
            }

            // Read data
            float[] data = new float[numElements];
            for (int i = 0; i < numElements; i++) {
                data[i] = buffer.getFloat();
            }

            return Nd4j.create(data, shape);
        } catch (Exception e) {
            log.error("Failed to convert bytes to INDArray", e);
            return null;
        }
    }

    /**
     * Converts an INDArray to a double array for interop with databases that do not support
     * BLOB well (e.g. Neo4j property arrays).
     *
     * <p><b>DEPRECATED — lossy interop path only.</b> This method upcasts float32→float64 via
     * {@link INDArray#toDoubleVector()}.  The resulting {@code double[]} cannot be round-tripped
     * back to a FLOAT32 INDArray without an explicit cast.  For all JPA persistence use
     * {@link #convertToDatabaseColumn}/{@link #convertToEntityAttribute} instead, which preserve
     * the original float32 representation exactly.
     *
     * @deprecated Use {@link #convertToDatabaseColumn}/{@link #convertToEntityAttribute} for
     *             persistence. Reserve this method only for transient interop layers (e.g. passing
     *             embeddings to a graph-reasoning library that requires {@code double[]}).
     */
    @Deprecated
    public static double[] toDoubleArray(INDArray array) {
        if (array == null) {
            return null;
        }
        return array.toDoubleVector();
    }

    /**
     * Converts a double array back to a <b>FLOAT32</b> INDArray.
     *
     * <p><b>DEPRECATED — lossy interop path only.</b> The input {@code double[]} was typically
     * produced by {@link #toDoubleArray}, which performs a float32→float64 upcast.  This method
     * converts back to FLOAT32 (via an intermediate FLOAT64 array cast to {@link DataType#FLOAT})
     * so that the reconstituted INDArray matches the dtype used by models trained on FLOAT32 data.
     * Precision beyond float32 is silently lost.  For all JPA persistence use
     * {@link #convertToDatabaseColumn}/{@link #convertToEntityAttribute} instead.
     *
     * @deprecated Use {@link #convertToDatabaseColumn}/{@link #convertToEntityAttribute} for
     *             persistence. Reserve this method only for transient interop layers.
     */
    @Deprecated
    public static INDArray fromDoubleArray(double[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        return Nd4j.create(data).castTo(DataType.FLOAT);
    }

    /**
     * Converts a double array back to a <b>FLOAT32</b> INDArray with the specified shape.
     *
     * <p><b>DEPRECATED — lossy interop path only.</b> See {@link #fromDoubleArray(double[])} for
     * the full deprecation rationale. This overload additionally reshapes the result.
     *
     * @deprecated Use {@link #convertToDatabaseColumn}/{@link #convertToEntityAttribute} for
     *             persistence. Reserve this method only for transient interop layers.
     */
    @Deprecated
    public static INDArray fromDoubleArray(double[] data, long... shape) {
        if (data == null || data.length == 0) {
            return null;
        }
        return Nd4j.create(data, shape).castTo(DataType.FLOAT);
    }
}
