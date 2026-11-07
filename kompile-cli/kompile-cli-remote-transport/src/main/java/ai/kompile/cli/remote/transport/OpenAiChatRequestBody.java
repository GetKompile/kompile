package ai.kompile.cli.remote.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Wire-body assembly only; provider policy and tool-schema conversion belong to the caller. */
public final class OpenAiChatRequestBody {
    private OpenAiChatRequestBody() {
    }

    /** Preserves the host's model/messages/stream insertion order and message-array identity. */
    public static ObjectNode base(ObjectMapper mapper, String model, ArrayNode messages) {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", model);
        request.set("messages", messages);
        request.put("stream", true);
        return request;
    }

    /** Call after provider-specific fields have been added; tools must already be in wire form. */
    public static void addStreamingOptions(ObjectMapper mapper, ObjectNode request, ArrayNode wireTools) {
        ObjectNode streamOptions = mapper.createObjectNode();
        streamOptions.put("include_usage", true);
        request.set("stream_options", streamOptions);
        if (wireTools != null && !wireTools.isEmpty()) {
            request.set("tools", wireTools);
        }
    }
}
