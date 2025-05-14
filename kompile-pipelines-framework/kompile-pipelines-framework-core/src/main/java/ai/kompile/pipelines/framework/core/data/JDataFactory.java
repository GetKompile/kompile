package ai.kompile.pipelines.framework.core.data;

import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.DataFactory;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Factory implementation for creating {@link JData} instances.
 * This class is typically registered as a service provider for the {@link DataFactory} interface.
 */
public class JDataFactory implements DataFactory {

    @Override
    public Data empty() {
        return JData.empty(); // Calls the static method on JData
    }

    @Override
    public Data fromJson(String jsonString) throws IOException {
        return JData.fromJson(jsonString);
    }

    @Override
    public Data fromJsonNode(JsonNode jsonNode) {
        return JData.fromJsonNode(jsonNode);
    }

    @Override
    public Data fromJson(File jsonFile) throws IOException {
        return JData.fromJson(jsonFile);
    }

    @Override
    public Data fromJson(InputStream inputStream) throws IOException {
        return JData.fromJson(inputStream);
    }

    @Override
    public Data fromMap(Map<String, Object> map) {
        return JData.fromMap(map);
    }

    @Override
    public Data singleton(String key, Object value) {
        return JData.singleton(key, value);
    }

    @Override
    public Data fromBytes(byte[] bytes) throws IOException {
        // JData implementation will treat these bytes as a JSON string.
        // If a binary format like Protobuf was used for Data, this method would be different.
        if (bytes == null) {
            return JData.empty();
        }
        return JData.fromJson(new String(bytes, StandardCharsets.UTF_8));
    }
}