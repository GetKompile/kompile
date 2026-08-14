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

package ai.kompile.pipelines.framework.core.data;

import ai.kompile.pipelines.framework.api.data.BoundingBox;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.Image;
import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.framework.api.data.Point;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.api.kvcache.KVCache;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Helper class to infer ValueType from a Java Object.
 * This would be used by JData.put(String, Object).
 */
class JDataValueInferer {
    static ValueType inferValueType(Object value) {
        if (value == null) return null; // Or a specific "NULL_TYPE" if JData wants to store typed nulls
        if (value instanceof String) return ValueType.STRING;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) return ValueType.INT64;
        if (value instanceof Double || value instanceof Float) return ValueType.DOUBLE;
        if (value instanceof Boolean) return ValueType.BOOLEAN;
        if (value instanceof byte[]) return ValueType.BYTES;
        if (value instanceof ByteBuffer) return ValueType.BYTES; // Will be converted to byte[] by JData
        if (value instanceof NDArray) return ValueType.NDARRAY;
        if (value instanceof Image) return ValueType.IMAGE;
        if (value instanceof Point) return ValueType.POINT;
        if (value instanceof BoundingBox) return ValueType.BOUNDING_BOX;
        if (value instanceof KVCache) return ValueType.KV_CACHE;
        if (value instanceof Data) return ValueType.DATA;
        if (value instanceof List) return ValueType.LIST;
        // Add more specific inferences if needed
        return null; // Or throw IllegalArgumentException
    }
}
