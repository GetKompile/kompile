package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenCodeServeClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesOpaqueProviderAndModelIds() {
        OpenCodeServeClient.ModelReference reference =
                OpenCodeServeClient.parseModelReference("opencode-go/deepseek-v4-pro");

        assertEquals("opencode-go", reference.providerId());
        assertEquals("deepseek-v4-pro", reference.modelId());
        assertEquals("opencode-go/deepseek-v4-pro", reference.asWireValue());
    }

    @Test
    void rejectsModelIdsWithoutTheNativeProviderPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenCodeServeClient.parseModelReference("deepseek-v4-pro"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenCodeServeClient.parseModelReference("opencode-go/"));
    }

    @Test
    void extractsOnlyNativeTextEventsFromJsonOutput() throws Exception {
        String response = """
                {"type":"text","text":"one"}
                {"type":"reasoning","text":"secret"}
                {"type":"text","text":"two"}
                """;

        assertEquals("onetwo", OpenCodeServeClient.extractText(objectMapper, response));
    }

    @Test
    void extractsTextPartsFromNestedNativeEvents() throws Exception {
        String response = """
                {"parts":[
                  {"type":"reasoning","text":"secret"},
                  {"type":"text","text":"answer"}
                ]}
                """;

        assertEquals("answer", OpenCodeServeClient.extractText(objectMapper, response));
    }
}
