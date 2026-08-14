package ai.kompile.app.subprocess;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServingSubprocessArgsRoundTripTest {

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
                Boolean.TRUE);

        Path jsonFile = expected.writeToTempFile();
        try {
            assertEquals(expected, ServingSubprocessArgs.fromFile(jsonFile));
        } finally {
            Files.deleteIfExists(jsonFile);
        }
    }

    @Test
    void defaultsBindOnlyToLoopback() {
        assertEquals("127.0.0.1", ServingSubprocessArgs.defaults().host());
    }

    @Test
    void nativeImageRegistersServingArgumentsForJackson() throws Exception {
        String resource = "META-INF/native-image/ai.kompile/kompile-model-serving/reflect-config.json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertTrue(
                    metadata.contains("ai.kompile.app.subprocess.ServingSubprocessArgs"));
            org.junit.jupiter.api.Assertions.assertTrue(metadata.contains("\"<init>\""));
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
