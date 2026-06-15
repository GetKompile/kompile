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

package ai.kompile.pipelines.framework.api.data;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * A factory interface for creating instances of {@link Data}.
 * Implementations of this factory provide concrete Data objects (e.g., JData, ProtoData).
 * This allows the core framework to be independent of specific Data implementations,
 * typically discovered via {@link java.util.ServiceLoader}.
 */
public interface DataFactory {
    /**
     * Creates a new, empty {@link Data} instance.
     * @return An empty Data object.
     */
     Data empty();

    /**
     * Creates a {@link Data} instance from a JSON string.
     * @param jsonString The JSON string representation of the Data.
     * @return A new Data instance populated from the JSON string.
     * @throws IOException If an error occurs during JSON parsing.
     */
    Data fromJson(String jsonString) throws IOException;

    /**
     * Creates a {@link Data} instance from a Jackson {@link JsonNode}.
     * @param jsonNode The Jackson JsonNode representation of the Data.
     * @return A new Data instance populated from the JsonNode.
     */
    Data fromJsonNode(JsonNode jsonNode);

    /**
     * Creates a {@link Data} instance from a JSON file.
     * @param jsonFile The File object pointing to the JSON file.
     * @return A new Data instance populated from the JSON file.
     * @throws IOException If an error occurs during file reading or JSON parsing.
     */
    Data fromJson(File jsonFile) throws IOException;

    /**
     * Creates a {@link Data} instance from an InputStream containing JSON data.
     * @param inputStream The InputStream providing JSON data.
     * @return A new Data instance populated from the InputStream.
     * @throws IOException If an error occurs during stream reading or JSON parsing.
     */
    Data fromJson(InputStream inputStream) throws IOException;

    /**
     * Creates a {@link Data} instance from a {@link Map}.
     * The factory implementation will attempt to infer {@link ValueType}s for the map values.
     * @param map The map to convert.
     * @return A new Data instance populated from the map.
     */
    Data fromMap(Map<String, Object> map);

    /**
     * Creates a singleton {@link Data} object containing a single key-value pair.
     * The factory implementation will attempt to infer the {@link ValueType} of the value.
     * @param key The key for the single entry.
     * @param value The value for the single entry.
     * @return A new Data instance with one entry.
     */
    Data singleton(String key, Object value);

    /**
     * Creates a {@link Data} instance from a byte array.
     * The interpretation of these bytes (e.g., JSON, Protobuf) depends on the factory implementation.
     * @param bytes The byte array.
     * @return A new Data instance.
     * @throws IOException If an error occurs during deserialization.
     */
    Data fromBytes(byte[] bytes) throws IOException;
}