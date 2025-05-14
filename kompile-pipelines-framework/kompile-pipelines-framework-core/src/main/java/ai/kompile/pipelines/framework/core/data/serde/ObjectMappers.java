package ai.kompile.pipelines.framework.core.data.serde;

import ai.kompile.pipelines.framework.api.data.Data;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.util.Map;

/**
 * Provides pre-configured Jackson {@link ObjectMapper} instances for JSON and YAML serialization/deserialization.
 * Includes custom handling for the {@link Data} interface and other specific types.
 */

public class ObjectMappers {

    private static final ObjectMapper jsonMapper;
    private static final ObjectMapper yamlMapper;

    static {
        jsonMapper = createJsonMapper();
        yamlMapper = createYamlMapper();
    }

    private static ObjectMapper configureMapper(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);


        SimpleModule dataModule = new SimpleModule("DataHandlingModule");
        dataModule.addSerializer(Data.class, new DataJsonSerializer());
        dataModule.addDeserializer(Data.class, new DataJsonDeserializer());

        // Add (de)serializers for other interfaces/abstract classes if they are directly used
        // in serialization and need specific handling beyond @JsonTypeInfo.
        // For NDArray and Image, concrete implementations will likely handle their own serialization.
        // If not, custom serializers/deserializers might be needed here for the interfaces themselves.
        // Example (if NDArray needs specific interface-level serialization beyond concrete class handling):
        // dataModule.addSerializer(NDArray.class, new NDArraySerializer());
        // dataModule.addDeserializer(NDArray.class, new NDArrayDeserializer());

        mapper.registerModule(dataModule);
        return mapper;
    }

    private static ObjectMapper createJsonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        return configureMapper(mapper);
    }

    private static ObjectMapper createYamlMapper() {
        YAMLFactory factory = YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .build();
        ObjectMapper mapper = new ObjectMapper(factory);
        return configureMapper(mapper);
    }

    /**
     * Gets a pre-configured ObjectMapper for JSON processing.
     * @return The JSON ObjectMapper.
     */
    public static ObjectMapper getJsonMapper() {
        return jsonMapper;
    }

    /**
     * Gets a pre-configured ObjectMapper for YAML processing.
     * @return The YAML ObjectMapper.
     */
    public static ObjectMapper getYamlMapper() {
        return yamlMapper;
    }


    // --- Custom Serializer/Deserializer for Data interface ---
    // These ensure Data objects are handled correctly via their toMap/fromMap logic
    // when directly serializing/deserializing a 'Data' reference.
    // Concrete implementations like JData might have their own more detailed internal serialization.

    public static class DataJsonSerializer extends StdSerializer<Data> {
        public DataJsonSerializer() {
            this(null);
        }
        public DataJsonSerializer(Class<Data> t) {
            super(t);
        }

        @Override
        public void serialize(Data data, JsonGenerator gen, SerializerProvider provider) throws IOException {
            // Delegate to Data.toMap() and let Jackson serialize the map
            gen.writeObject(data.toMap());
        }
    }

    public static class DataJsonDeserializer extends StdDeserializer<Data> {
        public DataJsonDeserializer() {
            this(null);
        }
        public DataJsonDeserializer(Class<?> vc) {
            super(vc);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Data deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException, JsonProcessingException {
            // Deserialize as a generic map, then convert to Data using the factory
            Map<String, Object> map = jp.readValueAs(Map.class);
            return Data.Factory.get().fromMap(map);
        }
    }


}