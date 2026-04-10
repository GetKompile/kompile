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

package ai.kompile.pipelines.steps.samediff.llm.util;

import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.framework.api.data.NDArrayType;
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
 * shared across LLM step runners.
 *
 * <p>Analogous to {@code ai.kompile.pipelines.steps.vlm.util.VLMSameDiffUtils}.</p>
 */
public final class LLMSameDiffUtils {

    private LLMSameDiffUtils() {}

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
        switch (type) {
            case FLOAT: return DataType.FLOAT;
            case DOUBLE: return DataType.DOUBLE;
            case INT: case INT32: return DataType.INT32;
            case LONG: return DataType.INT64;
            case BYTE: case INT8: return DataType.INT8;
            case UINT8: return DataType.UINT8;
            case SHORT: case INT16: return DataType.INT16;
            case UINT16: return DataType.UINT16;
            case UINT32: return DataType.UINT32;
            case UINT64: return DataType.UINT64;
            case BOOLEAN: return DataType.BOOL;
            case FLOAT16: return DataType.HALF;
            case BFLOAT16: return DataType.BFLOAT16;
            default: throw new UnsupportedOperationException("Unsupported NDArrayType: " + type);
        }
    }

    public static NDArrayType mapFromNd4jType(DataType type) {
        switch (type) {
            case FLOAT: return NDArrayType.FLOAT;
            case DOUBLE: return NDArrayType.DOUBLE;
            case INT: return NDArrayType.INT32;
            case LONG: return NDArrayType.LONG;
            case BYTE: return NDArrayType.INT8;
            case UBYTE: return NDArrayType.UINT8;
            case SHORT: return NDArrayType.INT16;
            case UINT16: return NDArrayType.UINT16;
            case UINT32: return NDArrayType.UINT32;
            case UINT64: return NDArrayType.UINT64;
            case BOOL: return NDArrayType.BOOLEAN;
            case HALF: return NDArrayType.FLOAT16;
            case BFLOAT16: return NDArrayType.BFLOAT16;
            default: throw new UnsupportedOperationException("Unsupported DataType: " + type);
        }
    }
}
