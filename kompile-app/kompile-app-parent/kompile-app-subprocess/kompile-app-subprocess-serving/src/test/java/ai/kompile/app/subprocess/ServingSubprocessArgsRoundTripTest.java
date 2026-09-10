package ai.kompile.app.subprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServingSubprocessArgsRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void productionJsonFileRoundTripPreservesEveryField() throws Exception {
        ServingSubprocessArgs expected = new ServingSubprocessArgs(
                18091,
                "127.0.0.1",
                "http://127.0.0.1:18090",
                "trace-model",
                "/tmp/trace-model.sdnb",
                "/tmp/tokenizer.json",
                "{\"maxThreads\":4}",
                80,
                85,
                90,
                1234L,
                75,
                80,
                85,
                70,
                72,
                82,
                92,
                64,
                0.25,
                10,
                Boolean.TRUE,
                Boolean.FALSE,
                Boolean.TRUE,
                "chat-template-x",
                "STATIC",
                4096,
                1024,
                Boolean.TRUE,
                384,
                Boolean.TRUE,
                268_435_456L,
                32,
                List.of(15_032_385_536L, 4_294_967_296L));

        Path jsonFile = expected.writeToTempFile();
        try {
            assertEquals(expected, ServingSubprocessArgs.fromFile(jsonFile));
        } finally {
            Files.deleteIfExists(jsonFile);
        }
    }

    @Test
    void defaultsBindOnlyToLoopback() {
        ServingSubprocessArgs defaults = ServingSubprocessArgs.defaults();
        assertEquals("127.0.0.1", defaults.host());
        assertNull(defaults.temperature(),
                "an absent caller override must let the loaded model family choose temperature");
        assertNull(defaults.topK(),
                "an absent caller override must let the loaded model family choose top-k");
        assertNull(defaults.prefixCacheEnabled());
        assertNull(defaults.prefixCacheMaxBytes());
        assertNull(defaults.prefixCacheBlockSize());
        assertNull(defaults.deviceMemoryLimitsBytes());
    }

    @Test
    void rejectsEmptyNonpositiveOrNullDeviceCapsAndKeepsValidCapsImmutable() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String limits : List.of("[]", "[0]", "[-1]", "[null]", "[1024,0]")) {
            assertThrows(IOException.class, () -> mapper.readValue(
                    "{\"deviceMemoryLimitsBytes\":" + limits + "}", ServingSubprocessArgs.class));
        }
        ServingSubprocessArgs parsed = mapper.readValue(
                "{\"deviceMemoryLimitsBytes\":[15032385536,4294967296]}", ServingSubprocessArgs.class);
        assertEquals(List.of(15_032_385_536L, 4_294_967_296L), parsed.deviceMemoryLimitsBytes());
        assertThrows(UnsupportedOperationException.class, () -> parsed.deviceMemoryLimitsBytes().set(0, 1L));
    }

    @Test
    void productionArgsRejectCoercedOrOutOfRangeDeviceCaps() throws Exception {
        Path file = tempDir.resolve("invalid-caps.json");
        for (String limits : List.of("[]", "[0]", "[-1]", "[null]", "[1024,0]",
                "[1.5]", "[1024.0]", "[1e3]", "[\"1024\"]", "[true]", "[{}]", "[[1024]]",
                "[9223372036854775808]", "[1024,1.5]", "[1024,\"4096\"]",
                "1024", "\"1024\"", "{}", "false")) {
            Files.writeString(file, "{\"deviceMemoryLimitsBytes\":" + limits + "}");
            assertThrows(IOException.class, () -> ServingSubprocessArgs.fromFile(file), limits);
        }
    }

    @Test
    void productionArgsPreserveExactIntegersDeviceOrderAndOptionalCaps() throws Exception {
        Path file = tempDir.resolve("valid-caps.json");
        Files.writeString(file,
                "{\"deviceMemoryLimitsBytes\":[9223372036854775807,1,9007199254740993]}");
        assertEquals(List.of(Long.MAX_VALUE, 1L, 9_007_199_254_740_993L),
                ServingSubprocessArgs.fromFile(file).deviceMemoryLimitsBytes());
        for (String json : List.of("{}", "{\"deviceMemoryLimitsBytes\":null}")) {
            Files.writeString(file, json);
            assertNull(ServingSubprocessArgs.fromFile(file).deviceMemoryLimitsBytes());
        }
    }

    @Test
    void productionArgsRejectNonObjectOrTrailingJson() throws Exception {
        Path file = tempDir.resolve("invalid-args.json");
        for (String json : List.of("", "null", "[]", "42", "{} {}")) {
            Files.writeString(file, json);
            assertThrows(IOException.class,
                    () -> ServingSubprocessMain.requireArgs(new String[]{file.toString()}), json);
        }
    }

    @Test
    void nativeImageRegistersServingArgumentsForJackson() throws Exception {
        String resource = "META-INF/native-image/ai.kompile/kompile-model-serving/reflect-config.json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            JsonNode registration = StreamSupport.stream(
                            new ObjectMapper().readTree(input).spliterator(), false)
                    .filter(entry -> ServingSubprocessArgs.class.getName()
                            .equals(entry.path("name").asText()))
                    .findFirst()
                    .orElseThrow();
            List<String> expectedConstructor = Arrays.stream(
                            ServingSubprocessArgs.class.getRecordComponents())
                    .map(RecordComponent::getType)
                    .map(Class::getName)
                    .toList();
            List<String> registeredConstructor = StreamSupport.stream(
                            registration.path("methods").spliterator(), false)
                    .filter(method -> "<init>".equals(method.path("name").asText()))
                    .findFirst()
                    .map(method -> StreamSupport.stream(
                                    method.path("parameterTypes").spliterator(), false)
                            .map(JsonNode::asText)
                            .toList())
                    .orElseThrow();

            assertEquals(expectedConstructor, registeredConstructor,
                    "native metadata must track the record's canonical constructor exactly");
        }
    }

    @Test
    void subprocessEntryRequiresExactlyOneValidArgsFile() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> ServingSubprocessMain.requireArgs(new String[0]));
        assertThrows(IllegalArgumentException.class,
                () -> ServingSubprocessMain.requireArgs(new String[]{"first.json", "second.json"}));

        Path missing = Files.createTempFile("serving-missing-", ".json");
        Files.delete(missing);
        assertThrows(IllegalArgumentException.class,
                () -> ServingSubprocessMain.requireArgs(new String[]{missing.toString()}));

        Path malformed = Files.createTempFile("serving-malformed-", ".json");
        try {
            Files.writeString(malformed, "{");
            assertThrows(IOException.class,
                    () -> ServingSubprocessMain.requireArgs(new String[]{malformed.toString()}));
        } finally {
            Files.deleteIfExists(malformed);
        }
    }
}
