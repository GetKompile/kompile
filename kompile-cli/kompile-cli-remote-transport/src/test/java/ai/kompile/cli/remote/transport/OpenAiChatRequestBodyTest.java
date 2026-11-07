package ai.kompile.cli.remote.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiChatRequestBodyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesWireShapeOrderAndAlreadyConvertedTools() throws Exception {
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "hello");
        ArrayNode tools = mapper.createArrayNode();
        tools.add(mapper.readTree("{\"type\":\"function\",\"function\":{\"name\":\"lookup\"}}"));
        ObjectNode request = OpenAiChatRequestBody.base(mapper, "remote-alias", messages);
        request.put("temperature", 0.5); // Host-owned policy between the shared body steps.
        OpenAiChatRequestBody.addStreamingOptions(mapper, request, tools);

        List<String> fields = new ArrayList<>();
        request.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("model", "messages", "stream", "temperature", "stream_options", "tools"), fields);
        assertEquals("remote-alias", request.path("model").asText());
        assertTrue(request.path("stream").asBoolean());
        assertEquals(mapper.readTree("{\"include_usage\":true}"), request.path("stream_options"));
        assertSame(messages, request.get("messages"));
        assertSame(tools, request.get("tools"));
        assertEquals(mapper.readTree("""
                {"model":"remote-alias","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"temperature":0.5,"stream_options":{"include_usage":true},
                 "tools":[{"type":"function","function":{"name":"lookup"}}]}
                """), request);
    }

    @Test
    void omitsAbsentOrEmptyToolsWithoutInventingProviderPolicy() {
        for (ArrayNode tools : new ArrayNode[]{null, mapper.createArrayNode()}) {
            ObjectNode request = OpenAiChatRequestBody.base(mapper, "model", mapper.createArrayNode());
            OpenAiChatRequestBody.addStreamingOptions(mapper, request, tools);
            assertEquals(4, request.size());
            assertFalse(request.has("tools"));
            assertFalse(request.has("max_tokens"));
            assertFalse(request.has("reasoning_effort"));
        }
    }
}
