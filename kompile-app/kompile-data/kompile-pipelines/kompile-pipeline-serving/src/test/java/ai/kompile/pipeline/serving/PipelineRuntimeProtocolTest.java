package ai.kompile.pipeline.serving;

import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineRuntimeProtocolTest {
    @Test
    void protocolRoundTripsVersionedMessages() throws Exception {
        PipelineRuntimeProtocol.Message original = PipelineRuntimeProtocol.message(
                PipelineRuntimeProtocol.EXECUTE, "request-1", "pipeline-1",
                Map.of("input", Map.of("text", "hello")));

        String encoded = PipelineRuntimeProtocol.encode(original);
        PipelineRuntimeProtocol.Message decoded = PipelineRuntimeProtocol.decode(encoded);

        assertTrue(encoded.startsWith(PipelineRuntimeProtocol.PREFIX));
        assertEquals(PipelineRuntimeProtocol.VERSION, decoded.version());
        assertEquals(PipelineRuntimeProtocol.EXECUTE, decoded.type());
        assertEquals("request-1", decoded.requestId());
        assertEquals("hello", ((Map<?, ?>) decoded.payload().get("input")).get("text"));
    }

    @Test
    void nonProtocolLinesAreRejected() {
        assertThrows(Exception.class, () -> PipelineRuntimeProtocol.decode("ordinary output"));
    }
}
