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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays; // For NDArray.length() default impl
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Optional;
import java.util.Set;

/**
 * Represents a generic data container used for passing information between pipeline steps.
 * It behaves like a map where keys are Strings and values can be of various types
 * defined by {@link ValueType}.
 *
 * This interface provides methods for putting and getting data of different types,
 * querying keys and types, and serialization/deserialization.
 *
 * Instances are typically created via a {@link DataFactory} implementation,
 * discovered using {@link ServiceLoader}.
 */
public interface Data extends Configuration {

    /**
     * Gets the type of the value associated with the given key.
     *
     * @param key The key.
     * @return The {@link ValueType} of the data, or null if the key doesn't exist or value is null.
     */
    ValueType type(String key);

    /**
     * If the value for the key is a LIST, returns the type of elements in that list.
     * @param key The key for the list.
     * @return The {@link ValueType} of the list elements, or null if the key doesn't exist,
     * is not a list, the list is empty, or the list element type is undetermined/mixed (though mixed types are discouraged).
     */
    ValueType listType(String key);

    /**
     * Returns a set of all keys present in this Data object.
     * @return A non-null, possibly empty, set of keys.
     */
    Set<String> keySet();

    /**
     * Checks if a key exists in this Data object.
     * @param key The key to check.
     * @return True if the key exists, false otherwise.
     */
    boolean has(String key);

    /**
     * Puts a generic Object, attempting to infer its {@link ValueType}.
     * Implementations should handle common types like String, Long, Double, Boolean, byte[],
     * ByteBuffer, NDArray, Image, Point, BoundingBox, Data, and List of these types.
     * If the value is null, it should store a representation of null for the key,
     * and {@link #type(String)} for that key might return null or a specific null type.
     * If the type cannot be inferred or is unsupported, an {@link IllegalArgumentException} might be thrown.
     *
     * @param key The key. Must not be null.
     * @param value The value to put. Can be null.
     */
    void put(String key, Object value);

    /**
     * Puts a String value. If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The String value.
     */
    void put(String key, String value);

    /**
     * Puts a Long value (INT64). If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The Long value.
     */
    void put(String key, Long value);

    /**
     * Puts an Integer value. Implementations will typically store this as {@link ValueType#INT64}.
     * If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The Integer value.
     */
    void put(String key, Integer value);

    /**
     * Puts a Double value. If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The Double value.
     */
    void put(String key, Double value);

    /**
     * Puts a Float value. Implementations will typically store this as {@link ValueType#DOUBLE}.
     * If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The Float value.
     */
    void put(String key, Float value);

    /**
     * Puts a Boolean value. If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The Boolean value.
     */
    void put(String key, Boolean value);

    /**
     * Puts a byte array value. If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The byte array value.
     */
    void put(String key, byte[] value);

    /**
     * Puts a ByteBuffer value. This is often a more efficient way to handle byte data.
     * If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The ByteBuffer value. The implementation might read its contents.
     */
    void put(String key, ByteBuffer value);

    /**
     * Puts an {@link NDArray} value. If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The NDArray value.
     */
    void put(String key, NDArray value);

    /**
     * Puts an {@link Image} value. If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The Image value.
     */
    void put(String key, Image value);

    /**
     * Puts a {@link Point} value. If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The Point value.
     */
    void put(String key, Point value);

    /**
     * Puts a {@link BoundingBox} value. If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The BoundingBox value.
     */
    void put(String key, BoundingBox value);

    /**
     * Puts a nested {@link Data} object. If value is null, it's equivalent to putting a null Object.
     * @param key The key.
     * @param value The nested Data object.
     */
    void put(String key, Data value);

    /**
     * Puts a List of a specific {@link ValueType}.
     * All elements in the list must be of the same concrete Java type
     * corresponding to the given listElementType.
     * If the list itself is null, it's equivalent to putting a null Object for the key.
     * Null elements within the list might be permissible depending on the implementation.
     *
     * @param key The key.
     * @param value The list of values.
     * @param listElementType The {@link ValueType} of the elements in the list. Cannot be null if value is not empty.
     * @param <T> The Java type of elements in the list.
     */
    <T> void putList(String key, List<T> value, ValueType listElementType);

    /**
     * Retrieves a value dynamically, attempting to cast to the requested type.
     *
     * @param key The key.
     * @param <T> The expected type of the value.
     * @return The value, or null if the key doesn't exist or the stored value is null.
     * @throws ClassCastException if the value exists and is not null, but cannot be cast to T.
     */
    <T> T get(String key);

    /**
     * Retrieves a value dynamically, returning a default value if the key is not found
     * or if the stored value is explicitly null. Attempts to cast to the requested type if found.
     *
     * @param key The key.
     * @param defaultValue The value to return if the key is not found or its stored value is null.
     * @param <T> The expected type of the value.
     * @return The value, or defaultValue.
     * @throws ClassCastException if the value exists and is not null, but cannot be cast to T (unless defaultValue is returned due to null).
     */
    <T> T get(String key, T defaultValue);


    String getString(String key);
    String getString(String key, String defaultValue);

    Long getInt64(String key);
    Long getInt64(String key, Long defaultValue);
    Integer getInt32(String key);
    Integer getInt32(String key, Integer defaultValue);


    Double getDouble(String key);
    Double getDouble(String key, Double defaultValue);
    Float getFloat(String key);
    Float getFloat(String key, Float defaultValue);


    Boolean getBoolean(String key);
    Boolean getBoolean(String key, Boolean defaultValue);

    byte[] getBytes(String key);
    byte[] getBytes(String key, byte[] defaultValue);

    ByteBuffer getByteBuffer(String key);
    ByteBuffer getByteBuffer(String key, ByteBuffer defaultValue);

    NDArray getNDArray(String key);
    NDArray getNDArray(String key, NDArray defaultValue);

    Image getImage(String key);
    Image getImage(String key, Image defaultValue);

    Point getPoint(String key);
    Point getPoint(String key, Point defaultValue);

    BoundingBox getBoundingBox(String key);
    BoundingBox getBoundingBox(String key, BoundingBox defaultValue);

    Data getData(String key); // For nested Data
    Data getData(String key, Data defaultValue);

    /**
     * Retrieves a list of values.
     * @param key The key for the list.
     * @param expectedListElementType The expected {@link ValueType} of elements within the list.
     * @param <T> The Java type corresponding to expectedListElementType.
     * @return The list, or null if the key doesn't exist, is not a list, or its stored value is null.
     * @throws ClassCastException if the item is a list but its elements cannot be cast to T.
     */
    <T> List<T> getList(String key, ValueType expectedListElementType);
    <T> List<T> getList(String key, ValueType expectedListElementType, List<T> defaultValue);

    /**
     * Removes a key and its associated value from this Data object.
     * @param key The key to remove.
     * @return The previous value (as raw Object from underlying storage, or a wrapped representation)
     * associated with key, or null if there was no mapping for key.
     */
    Object remove(String key);

    /**
     * Clears all key-value pairs from this Data object.
     */
    void clear();

    /**
     * Returns the number of key-value mappings in this Data object.
     * @return The number of key-value mappings.
     */
    @JsonIgnore // Calculated property, not part of direct serialization state
    int size();

    /**
     * Returns true if this Data object contains no key-value mappings.
     * @return true if this Data object contains no key-value mappings.
     */
    @JsonIgnore // Calculated property
    boolean isEmpty();

    /**
     * Merges another Data object into this one.
     * If keys conflict, the values from the {@code other} Data object will overwrite existing ones in this Data object.
     * This operation should modify the current Data instance.
     * @param other The Data object to merge. Must not be null.
     */
    void merge(Data other);

    /**
     * Creates a new Data object that is a shallow copy of this Data object.
     * Primitive types and their wrappers (String, Long, Double, Boolean, byte[]) are copied by value.
     * Complex types (NDArray, Image, Point, BoundingBox, nested Data, List) are typically copied by reference
     * (i.e., the reference to the complex object is copied, not the object itself cloned deeply).
     * The underlying map of keys to value wrappers is new.
     * @return A shallow copy of this Data object.
     */
    Data dup();

    /**
     * Converts this Data object to its JSON string representation.
     * @return JSON string.
     * @throws JsonProcessingException if JSON processing fails.
     */
    String toJson() throws JsonProcessingException;

    /**
     * Converts this Data object to a Map representation.
     * The values in the map should be the unwrapped Java objects (e.g., String, Long, List<String>,
     * Map<String,Object> for nested Data).
     * This can be useful for interop or inspection.
     * @return A non-null Map representation of the Data.
     */
    Map<String, Object> toMap();

    /**
     * Serializes this Data object to an OutputStream.
     * The format depends on the implementation (e.g., JData might serialize as JSON by default).
     * @param outputStream The stream to write to. Must not be null.
     * @throws IOException If an I/O error occurs.
     */
    void write(OutputStream outputStream) throws IOException;

    /**
     * Serializes this Data object to a byte array.
     * The format depends on the implementation.
     * @return Byte array representation.
     * @throws IOException If serialization fails.
     */
    byte[] asBytes() throws IOException;

    default void putIfAbsent(String title, String s) {
        putIfAbsent(title,s);
    }


    // --- Static Accessor for the Factory ---
    /**
     * Provides access to the configured {@link DataFactory} instance.
     * Uses {@link ServiceLoader} to find an implementation.
     */
    final class Factory {
        private Factory() {} // Non-instantiable

        private static class Holder {
            static final DataFactory INSTANCE = loadFactory();

            private static DataFactory loadFactory() {
                Optional<DataFactory> factoryOpt = ServiceLoader.load(DataFactory.class).findFirst();
                if (factoryOpt.isPresent()) {
                    return factoryOpt.get();
                } else {
                    // Fallback to direct loading if ServiceLoader fails (e.g. in certain environments)
                    // This fallback makes the API slightly less pure but can improve usability in some cases.
                    // It's preferable to ensure ServiceLoader is working correctly.
                    try {
                        Class<?> jdataFactoryClass = Class.forName("ai.kompile.pipelines.framework.core.data.JDataFactory");
                        return (DataFactory) jdataFactoryClass.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException | SecurityException e) {
                        // Catch more specific exceptions from reflection
                        throw new IllegalStateException(
                                "No DataFactory implementation found via ServiceLoader, " +
                                        "and fallback direct load of JDataFactory also failed. " +
                                        "Ensure a module (e.g., kompile-pipelines-framework-core) " +
                                        "provides an implementation and is on the classpath, and that " +
                                        "META-INF/services/ai.kompile.pipelines.framework.api.data.DataFactory is correctly configured.", e);
                    }
                }
            }
        }

        /**
         * Gets the singleton instance of the configured {@link DataFactory}.
         * @return The DataFactory instance.
         * @throws IllegalStateException if no DataFactory implementation is found.
         */
        public static DataFactory get() {
            return Holder.INSTANCE;
        }
    }

    // --- Static factory methods using the DataFactory ---
    /**
     * Creates a new, empty {@link Data} instance using the configured factory.
     * @return An empty Data object.
     */
    static Data empty() {
        return Factory.get().empty();
    }

    /**
     * Creates a {@link Data} instance from a JSON string using the configured factory.
     * @param jsonString The JSON string.
     * @return A new Data instance.
     * @throws IOException if JSON parsing fails.
     */
    static Data fromJson(String jsonString) throws IOException {
        return Factory.get().fromJson(jsonString);
    }

    /**
     * Creates a {@link Data} instance from a Jackson {@link JsonNode} using the configured factory.
     * @param jsonNode The Jackson JsonNode.
     * @return A new Data instance.
     */
    static Data fromJsonNode(JsonNode jsonNode) {
        return Factory.get().fromJsonNode(jsonNode);
    }

    /**
     * Creates a {@link Data} instance from a JSON file using the configured factory.
     * @param jsonFile The File object.
     * @return A new Data instance.
     * @throws IOException if file reading or JSON parsing fails.
     */
    static Data fromJson(File jsonFile) throws IOException {
        return Factory.get().fromJson(jsonFile);
    }

    /**
     * Creates a {@link Data} instance from an InputStream containing JSON data using the configured factory.
     * @param inputStream The InputStream.
     * @return A new Data instance.
     * @throws IOException if stream reading or JSON parsing fails.
     */
    static Data fromJson(InputStream inputStream) throws IOException {
        return Factory.get().fromJson(inputStream);
    }

    /**
     * Creates a {@link Data} instance from a {@link Map} using the configured factory.
     * @param map The map to convert.
     * @return A new Data instance.
     */
    static Data fromMap(Map<String, Object> map) {
        return Factory.get().fromMap(map);
    }

    /**
     * Creates a singleton {@link Data} object with one key-value pair using the configured factory.
     * @param key The key.
     * @param value The value.
     * @return A new Data instance.
     */
    static Data singleton(String key, Object value) {
        return Factory.get().singleton(key, value);
    }

    /**
     * Creates a {@link Data} instance from a byte array using the configured factory.
     * @param bytes The byte array.
     * @return A new Data instance.
     * @throws IOException If deserialization fails.
     */
    static Data fromBytes(byte[] bytes) throws IOException {
        return Factory.get().fromBytes(bytes);
    }
}