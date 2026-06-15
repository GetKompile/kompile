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
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Jackson-based implementation of the {@link Data} interface.
 * Uses a Map to store key-value pairs, where values are wrapped in {@link JDataValueWrapper}
 * to maintain type information.
 */

public class JData implements Data {
    private static final long serialVersionUID = 1L;

    // Using LinkedHashMap to preserve insertion order for toMap() and keySet() consistency
    private final Map<String, JDataValueWrapper> values;
    // Stores the element type for lists. Key is the same as in 'values'.
    private final Map<String, ValueType> listElementTypes;


    public JData() {
        this.values = new LinkedHashMap<>();
        this.listElementTypes = new HashMap<>();
    }

    private JData(Map<String, JDataValueWrapper> values, Map<String, ValueType> listElementTypes) {
        this.values = new LinkedHashMap<>(values); // Create a copy
        this.listElementTypes = new HashMap<>(listElementTypes); // Create a copy
    }

    // --- Static Factory Methods ---
    public static JData empty() {
        return new JData();
    }

    public static JData fromJson(String jsonString) throws IOException {
        ObjectMapper mapper = ObjectMappers.getJsonMapper();
        // Deserialize into a Map<String, Object> first, then build JData
        // This leverages Jackson's general map deserialization and then we type it.
        Map<String, Object> rawMap = mapper.readValue(jsonString, LinkedHashMap.class);
        return fromMapInternal(rawMap);
    }

    public static JData fromJsonNode(JsonNode jsonNode) {
        ObjectMapper mapper = ObjectMappers.getJsonMapper();
        Map<String, Object> rawMap = mapper.convertValue(jsonNode, LinkedHashMap.class);
        return fromMapInternal(rawMap);
    }

    public static JData fromJson(File jsonFile) throws IOException {
        ObjectMapper mapper = ObjectMappers.getJsonMapper();
        Map<String, Object> rawMap = mapper.readValue(jsonFile, LinkedHashMap.class);
        return fromMapInternal(rawMap);
    }

    public static JData fromJson(InputStream inputStream) throws IOException {
        ObjectMapper mapper = ObjectMappers.getJsonMapper();
        Map<String, Object> rawMap = mapper.readValue(inputStream, LinkedHashMap.class);
        return fromMapInternal(rawMap);
    }

    private static JData fromMapInternal(Map<String, Object> map) {
        JData data = new JData();
        if (map != null) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                // The put(String, Object) method will infer types
                data.put(entry.getKey(), entry.getValue());
            }
        }
        return data;
    }


    public static JData fromMap(Map<String, Object> map) {
        return fromMapInternal(new LinkedHashMap<>(map)); // Use a copy
    }

    public static JData singleton(String key, Object value) {
        JData data = new JData();
        data.put(key, value);
        return data;
    }

    public static Data from(Map<String, Object> map) {
        return fromMapInternal(map);
    }

    // --- Interface Implementation ---

    @Override
    public ValueType type(String key) {
        JDataValueWrapper wrapper = values.get(key);
        return (wrapper != null) ? wrapper.getType() : null;
    }

    @Override
    public ValueType listType(String key) {
        if (type(key) == ValueType.LIST) {
            return listElementTypes.get(key);
        }
        return null;
    }

    @Override
    public Set<String> keySet() {
        return Collections.unmodifiableSet(values.keySet());
    }

    @Override
    public boolean has(String key) {
        return values.containsKey(key);
    }

    @Override
    public void put(String key, Object value) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null.");
        }
        if (value == null) {
            values.put(key, new JDataValueWrapper()); // Store as untyped null
            listElementTypes.remove(key);
            return;
        }

        if (value instanceof String) put(key, (String) value);
        else if (value instanceof Long) put(key, (Long) value);
        else if (value instanceof Integer) put(key, ((Integer)value).longValue());
        else if (value instanceof Double) put(key, (Double) value);
        else if (value instanceof Float) put(key, ((Float)value).doubleValue());
        else if (value instanceof Boolean) put(key, (Boolean) value);
        else if (value instanceof byte[]) put(key, (byte[]) value);
        else if (value instanceof ByteBuffer) put(key, (ByteBuffer) value);
        else if (value instanceof NDArray) put(key, (NDArray) value);
        else if (value instanceof Image) put(key, (Image) value);
        else if (value instanceof Point) put(key, (Point) value);
        else if (value instanceof BoundingBox) put(key, (BoundingBox) value);
        else if (value instanceof KVCache) putKVCache(key, (KVCache) value);
        else if (value instanceof Data) put(key, (Data) value);
        else if (value instanceof List) {
            // Infer list element type if possible, otherwise it's an untyped list (problematic)
            // For robust typing, putList should always be used for lists.
            // This generic put for List is a best-effort.
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                // Cannot infer type from empty list, store as LIST but with null elementType
                values.put(key, new JDataValueWrapper(ValueType.LIST, null, list)); // Using specific constructor
                listElementTypes.put(key, null); // Or a special "UNKNOWN" ValueType
            } else {
                Object firstElement = list.get(0);
                ValueType elementType = JDataValueInferer.inferValueType(firstElement); // Helper needed
                if (elementType == null || elementType == ValueType.LIST || elementType == ValueType.DATA) {
                    // Fallback: treat as list of generic objects or handle as error
                    // For simplicity, we'll store it as a list of generic objects, type will be ValueType.LIST
                    // but listElementType will be difficult to determine robustly.
                    values.put(key, new JDataValueWrapper(ValueType.LIST, null, new ArrayList<>(list))); // Store a copy
                    listElementTypes.put(key, null); // Mark as unknown or best guess
                } else {
                    // Check if all elements are of the same inferred type (optional, for stricter JData)
                    putList(key, (List<?>) list, elementType);
                }
            }
        } else {
            throw new IllegalArgumentException("Unsupported value type for key '" + key + "': " + value.getClass().getName());
        }
    }


    @Override
    public void put(String key, String value) {
        values.put(key, new JDataValueWrapper(ValueType.STRING, value));
        listElementTypes.remove(key);
    }

    @Override
    public void put(String key, Long value) {
        values.put(key, new JDataValueWrapper(ValueType.INT64, value));
        listElementTypes.remove(key);
    }

    @Override
    public void put(String key, Integer value) {
        put(key, value != null ? value.longValue() : null);
    }

    @Override
    public void put(String key, Double value) {
        values.put(key, new JDataValueWrapper(ValueType.DOUBLE, value));
        listElementTypes.remove(key);
    }

    @Override
    public void put(String key, Float value) {
        put(key, value != null ? value.doubleValue() : null);
    }


    @Override
    public void put(String key, Boolean value) {
        values.put(key, new JDataValueWrapper(ValueType.BOOLEAN, value));
        listElementTypes.remove(key);
    }

    @Override
    public void put(String key, byte[] value) {
        values.put(key, new JDataValueWrapper(ValueType.BYTES, value));
        listElementTypes.remove(key);
    }

    @Override
    public void put(String key, ByteBuffer value) {
        // Convert ByteBuffer to byte[] for consistent storage, or store ByteBuffer directly if JDataValueWrapper supports it
        if (value != null) {
            byte[] bytes = new byte[value.remaining()];
            value.get(bytes);
            put(key, bytes); // Store as byte[]
        } else {
            put(key, (byte[]) null);
        }
        listElementTypes.remove(key);
    }


    @Override
    public void put(String key, NDArray value) {
        values.put(key, new JDataValueWrapper(ValueType.NDARRAY, value));
        listElementTypes.remove(key);
    }

    @Override
    public void put(String key, Image value) {
        values.put(key, new JDataValueWrapper(ValueType.IMAGE, value));
        listElementTypes.remove(key);
    }

    @Override
    public void put(String key, Point value) {
        values.put(key, new JDataValueWrapper(ValueType.POINT, value));
        listElementTypes.remove(key);
    }

    @Override
    public void put(String key, BoundingBox value) {
        values.put(key, new JDataValueWrapper(ValueType.BOUNDING_BOX, value));
        listElementTypes.remove(key);
    }

    @Override
    public void put(String key, Data value) {
        values.put(key, new JDataValueWrapper(ValueType.DATA, value));
        listElementTypes.remove(key);
    }

    @Override
    public void put(String key, KVCache value) {
        putKVCache(key, value);
    }

    private void putKVCache(String key, KVCache value) {
        values.put(key, new JDataValueWrapper(ValueType.KV_CACHE, value));
        listElementTypes.remove(key);
    }

    @Override
    public KVCache getKVCache(String key) { return get(key); }

    @Override
    public KVCache getKVCache(String key, KVCache defaultValue) { return get(key, defaultValue); }

    @Override
    public <T> void putList(String key, List<T> value, ValueType listElementType) {
        if (value == null) {
            values.put(key, new JDataValueWrapper()); // Store as untyped null
            listElementTypes.remove(key);
            return;
        }
        Objects.requireNonNull(listElementType, "listElementType cannot be null for non-null list");
        // Optionally validate that all elements in 'value' match 'listElementType'
        values.put(key, new JDataValueWrapper(ValueType.LIST, listElementType, new ArrayList<>(value))); // Store a copy
        listElementTypes.put(key, listElementType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        JDataValueWrapper wrapper = values.get(key);
        return (wrapper != null) ? (T) wrapper.getValue() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        JDataValueWrapper wrapper = values.get(key);
        if (wrapper == null || wrapper.getValue() == null) { // Check wrapper OR its value for null
            return defaultValue;
        }
        try {
            return (T) wrapper.getValue();
        } catch (ClassCastException e) {
            // This case should be rare if puts are typed correctly,
            // but could happen if default value's type doesn't match stored type.
            return defaultValue;
        }
    }


    @Override
    public String getString(String key) { return get(key); }
    @Override
    public String getString(String key, String defaultValue) { return get(key, defaultValue); }

    @Override
    public Long getInt64(String key) { return get(key); }
    @Override
    public Long getInt64(String key, Long defaultValue) { return get(key, defaultValue); }

    @Override
    public Integer getInt32(String key) {
        Long lVal = getInt64(key);
        if (lVal == null) return null;
        if (lVal > Integer.MAX_VALUE || lVal < Integer.MIN_VALUE) {
            throw new ClassCastException("Value for key '" + key + "' (" + lVal + ") is too large for Integer.");
        }
        return lVal.intValue();
    }
    @Override
    public Integer getInt32(String key, Integer defaultValue) {
        Long lVal = getInt64(key); // Get potential Long value first
        if (lVal == null) return defaultValue;
        if (lVal > Integer.MAX_VALUE || lVal < Integer.MIN_VALUE) {
            return defaultValue;
        }
        return lVal.intValue();
    }


    @Override
    public Double getDouble(String key) { return get(key); }
    @Override
    public Double getDouble(String key, Double defaultValue) { return get(key, defaultValue); }

    @Override
    public Float getFloat(String key) {
        Double dVal = getDouble(key);
        return (dVal != null) ? dVal.floatValue() : null;
    }
    @Override
    public Float getFloat(String key, Float defaultValue) {
        Double dVal = getDouble(key); // Get potential Double value
        if (dVal == null) return defaultValue;
        // Check for precision loss if important, though standard cast is direct
        return dVal.floatValue();
    }

    @Override
    public Boolean getBoolean(String key) { return get(key); }
    @Override
    public Boolean getBoolean(String key, Boolean defaultValue) { return get(key, defaultValue); }

    @Override
    public byte[] getBytes(String key) { return get(key); }
    @Override
    public byte[] getBytes(String key, byte[] defaultValue) { return get(key, defaultValue); }

    @Override
    public ByteBuffer getByteBuffer(String key) {
        byte[] bytes = getBytes(key);
        return bytes != null ? ByteBuffer.wrap(bytes) : null;
    }
    @Override
    public ByteBuffer getByteBuffer(String key, ByteBuffer defaultValue) {
        byte[] bytes = getBytes(key); // Get potential byte array
        return bytes != null ? ByteBuffer.wrap(bytes) : defaultValue;
    }


    @Override
    public NDArray getNDArray(String key) { return get(key); }
    @Override
    public NDArray getNDArray(String key, NDArray defaultValue) { return get(key, defaultValue); }

    @Override
    public Image getImage(String key) { return get(key); }
    @Override
    public Image getImage(String key, Image defaultValue) { return get(key, defaultValue); }

    @Override
    public Point getPoint(String key) { return get(key); }
    @Override
    public Point getPoint(String key, Point defaultValue) { return get(key, defaultValue); }

    @Override
    public BoundingBox getBoundingBox(String key) { return get(key); }
    @Override
    public BoundingBox getBoundingBox(String key, BoundingBox defaultValue) { return get(key, defaultValue); }


    @Override
    public Data getData(String key) { return get(key); }
    @Override
    public Data getData(String key, Data defaultValue) { return get(key, defaultValue); }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key, ValueType expectedListElementType) {
        JDataValueWrapper wrapper = values.get(key);
        if (wrapper == null || wrapper.getType() != ValueType.LIST || wrapper.getValue() == null) {
            return null;
        }
        ValueType actualListElementType = listElementTypes.get(key);
        if (actualListElementType != null && actualListElementType != expectedListElementType) {
            throw new ClassCastException("List element type mismatch for key '" + key +
                    "'. Expected: " + expectedListElementType + ", Found: " + actualListElementType);
        }
        // It's the caller's responsibility that T matches expectedListElementType
        return (List<T>) wrapper.getValue();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key, ValueType expectedListElementType, List<T> defaultValue) {
        JDataValueWrapper wrapper = values.get(key);
        if (wrapper == null || wrapper.getType() != ValueType.LIST || wrapper.getValue() == null) {
            return defaultValue;
        }
        ValueType actualListElementType = listElementTypes.get(key);
        if (actualListElementType != null && actualListElementType != expectedListElementType) {
            return defaultValue;
        }
        try {
            return (List<T>) wrapper.getValue();
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }


    @Override
    public Object remove(String key) {
        listElementTypes.remove(key);
        JDataValueWrapper wrapper = values.remove(key);
        return (wrapper != null) ? wrapper.getValue() : null;
    }

    @Override
    public void clear() {
        values.clear();
        listElementTypes.clear();
    }

    @Override
    public int size() {
        return values.size();
    }

    @Override
    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public void merge(Data other) {
        if (other == null) return;
        for (String key : other.keySet()) {
            // This relies on the generic put(String, Object) to correctly infer types
            // from the 'other' Data object when its raw values are retrieved.
            // Or, we could inspect other.type(key) and call specific put methods.
            this.put(key, other.get(key));
            if (other.type(key) == ValueType.LIST) {
                this.listElementTypes.put(key, other.listType(key));
            }
        }
    }

    @Override
    public Data dup() {
        // Creates a new JData with copies of the internal maps.
        // JDataValueWrappers themselves are shallow copied (value references).
        JData newJData = new JData(new LinkedHashMap<>(this.values), new HashMap<>(this.listElementTypes));
        // For truly independent primitive values, and references for complex ones.
        // If values inside JDataValueWrapper should be deeply copied for certain types,
        // that logic would need to be added here or in JDataValueWrapper.
        return newJData;
    }

    @Override
    public String toJson() throws JsonProcessingException {
        return ObjectMappers.getJsonMapper().writeValueAsString(this.toMap());
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JDataValueWrapper> entry : values.entrySet()) {
            JDataValueWrapper wrapper = entry.getValue();
            if (wrapper.getValue() instanceof Data) { // Handle nested Data
                map.put(entry.getKey(), ((Data) wrapper.getValue()).toMap());
            } else if (wrapper.getValue() instanceof List && wrapper.getType() == ValueType.LIST) {
                // Handle lists, especially lists of Data
                List<?> originalList = (List<?>) wrapper.getValue();
                ValueType listElType = listElementTypes.get(entry.getKey());
                if (listElType == ValueType.DATA) {
                    List<Map<String, Object>> mappedList = originalList.stream()
                            .filter(Data.class::isInstance)
                            .map(dataEl -> ((Data)dataEl).toMap())
                            .collect(Collectors.toList());
                    map.put(entry.getKey(), mappedList);
                } else {
                    // For other list types, assume direct serialization works or they are simple types
                    map.put(entry.getKey(), wrapper.getValue());
                }
            }
            else {
                map.put(entry.getKey(), wrapper.getValue());
            }
        }
        return map;
    }

    @Override
    public void write(OutputStream outputStream) throws IOException {
        ObjectMappers.getJsonMapper().writeValue(outputStream, this.toMap());
    }

    @Override
    public byte[] asBytes() throws IOException {
        return ObjectMappers.getJsonMapper().writeValueAsBytes(this.toMap());
    }

    @Override
    public String toString() {
        try {
            return toJson();
        } catch (JsonProcessingException e) {
            return "JData{Error converting to JSON: " + e.getMessage() + "}";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JData jData = (JData) o;
        // Comparing the underlying maps directly should work if JDataValueWrapper has good equals/hashCode
        return values.equals(jData.values) && listElementTypes.equals(jData.listElementTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, listElementTypes);
    }
}

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