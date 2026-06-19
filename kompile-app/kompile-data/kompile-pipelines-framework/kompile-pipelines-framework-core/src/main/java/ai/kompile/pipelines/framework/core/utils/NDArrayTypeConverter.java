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

package ai.kompile.pipelines.framework.core.utils;

import ai.kompile.pipelines.framework.api.data.NDArrayType;
import org.nd4j.linalg.api.buffer.DataType;

/**
 * Converts between Kompile {@link NDArrayType} and ND4J {@link DataType}.
 *
 * <p>Name differences between the two enums:</p>
 * <ul>
 *   <li>{@code NDArrayType.FLOAT16} ↔ {@code DataType.HALF}</li>
 *   <li>{@code NDArrayType.BOOLEAN} ↔ {@code DataType.BOOL}</li>
 *   <li>{@code NDArrayType.LONG} ↔ {@code DataType.INT64}</li>
 *   <li>{@code NDArrayType.BYTE} / {@code NDArrayType.INT8} ↔ {@code DataType.INT8}</li>
 *   <li>{@code NDArrayType.SHORT} / {@code NDArrayType.INT16} ↔ {@code DataType.INT16}</li>
 *   <li>{@code NDArrayType.INT} / {@code NDArrayType.INT32} ↔ {@code DataType.INT32}</li>
 *   <li>{@code NDArrayType.UINT8} ↔ {@code DataType.UINT8} (ND4J also calls this UBYTE)</li>
 * </ul>
 */
public final class NDArrayTypeConverter {

    private NDArrayTypeConverter() {}

    /**
     * Converts a Kompile {@link NDArrayType} to an ND4J {@link DataType}.
     *
     * @param type the Kompile array type
     * @return the corresponding ND4J data type
     * @throws UnsupportedOperationException if the type has no ND4J equivalent (e.g. UTF8)
     */
    public static DataType toNd4jDataType(NDArrayType type) {
        switch (type) {
            case FLOAT:    return DataType.FLOAT;
            case DOUBLE:   return DataType.DOUBLE;
            case INT:
            case INT32:    return DataType.INT32;
            case LONG:     return DataType.INT64;
            case BYTE:
            case INT8:     return DataType.INT8;
            case UINT8:    return DataType.UINT8;
            case SHORT:
            case INT16:    return DataType.INT16;
            case UINT16:   return DataType.UINT16;
            case UINT32:   return DataType.UINT32;
            case UINT64:   return DataType.UINT64;
            case BOOLEAN:  return DataType.BOOL;
            case FLOAT16:  return DataType.HALF;
            case BFLOAT16: return DataType.BFLOAT16;
            default:
                throw new UnsupportedOperationException(
                        "Unsupported NDArrayType for ND4J conversion: " + type);
        }
    }

    /**
     * Converts an ND4J {@link DataType} to a Kompile {@link NDArrayType}.
     *
     * @param type the ND4J data type
     * @return the corresponding Kompile array type
     * @throws UnsupportedOperationException if the type has no Kompile equivalent
     */
    public static NDArrayType fromNd4jDataType(DataType type) {
        switch (type) {
            case FLOAT:    return NDArrayType.FLOAT;
            case DOUBLE:   return NDArrayType.DOUBLE;
            case INT:      return NDArrayType.INT32;
            case LONG:     return NDArrayType.LONG;
            case BYTE:     return NDArrayType.INT8;
            case UBYTE:    return NDArrayType.UINT8;
            case SHORT:    return NDArrayType.INT16;
            case UINT16:   return NDArrayType.UINT16;
            case UINT32:   return NDArrayType.UINT32;
            case UINT64:   return NDArrayType.UINT64;
            case BOOL:     return NDArrayType.BOOLEAN;
            case HALF:     return NDArrayType.FLOAT16;
            case BFLOAT16: return NDArrayType.BFLOAT16;
            case UTF8:     return NDArrayType.UTF8;
            default:
                throw new UnsupportedOperationException(
                        "Unsupported ND4J DataType for Kompile conversion: " + type);
        }
    }
}
