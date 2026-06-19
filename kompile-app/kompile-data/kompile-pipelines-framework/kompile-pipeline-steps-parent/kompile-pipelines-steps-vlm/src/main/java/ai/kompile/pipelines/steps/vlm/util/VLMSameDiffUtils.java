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

package ai.kompile.pipelines.steps.vlm.util;

import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.framework.api.data.NDArrayType;
import ai.kompile.pipelines.framework.core.utils.NDArrayTypeConverter;
import org.nd4j.common.util.ArrayUtil;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * Utility methods for converting between Kompile NDArray and ND4J INDArray,
 * shared across VLM step runners.
 */
public final class VLMSameDiffUtils {

    private VLMSameDiffUtils() {}

    /**
     * Converts a Kompile NDArray to an ND4J INDArray.
     */
    public static INDArray toINDArray(NDArray kompileNDArray) {
        Object nativeObj = kompileNDArray.getNative();
        if (nativeObj instanceof INDArray) {
            return (INDArray) nativeObj;
        }

        ByteBuffer bb = kompileNDArray.buffer();
        if (bb == null) {
            throw new IllegalArgumentException("NDArray buffer is null");
        }
        bb = bb.order(ByteOrder.nativeOrder());
        if (!bb.isDirect()) {
            ByteBuffer directBb = ByteBuffer.allocateDirect(bb.remaining()).order(ByteOrder.nativeOrder());
            directBb.put(bb.slice());
            directBb.flip();
            bb = directBb;
        }

        DataType dt = mapToNd4jType(kompileNDArray.type());
        long[] shape = kompileNDArray.shape();
        if (shape == null || shape.length == 0) {
            shape = new long[]{1};
        }

        DataBuffer dataBuffer = Nd4j.createBuffer(bb, dt, (int) kompileNDArray.length());
        return Nd4j.create(dataBuffer, shape, Nd4j.getStrides(shape, 'c'), 0L, 'c', dt);
    }

    /**
     * Converts an ND4J INDArray to a Kompile NDArray.
     */
    public static NDArray fromINDArray(INDArray indArray, String name) {
        final NDArrayType kompileType = mapFromNd4jType(indArray.dataType());
        INDArray contiguous = (indArray.isView() || indArray.ordering() != 'c') ? indArray.dup('c') : indArray;

        final long[] shape = indArray.shape().clone();
        final INDArray ref = contiguous;

        return new NDArray() {
            @Override public String name() { return name; }
            @Override public long[] shape() { return shape; }
            @Override public NDArrayType type() { return kompileType; }
            @Override public ByteBuffer buffer() {
                return ref.data().asNio().order(ByteOrder.nativeOrder());
            }
            @SuppressWarnings("unchecked")
            @Override public <T> T getNative() { return (T) ref; }
            @Override public long length() { return ArrayUtil.prod(shape); }
            @Override public int bufferSizeInBytes() { return (int)(length() * ref.dataType().width()); }
        };
    }

    /**
     * Closes all INDArrays in a map, ignoring any errors.
     */
    public static void closeAll(Map<String, INDArray> arrays) {
        if (arrays == null) return;
        for (INDArray arr : arrays.values()) {
            if (arr != null) {
                try { arr.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static DataType mapToNd4jType(NDArrayType type) {
        return NDArrayTypeConverter.toNd4jDataType(type);
    }

    public static NDArrayType mapFromNd4jType(DataType type) {
        return NDArrayTypeConverter.fromNd4jDataType(type);
    }
}
