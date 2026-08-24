package ai.kompile.pipeline.serving;

import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void errorsPreserveBootstrapStageCauseChainAndStack() throws Exception {
        ExceptionInInitializerError failure = new ExceptionInInitializerError(
                new IllegalStateException("ND4J backend unavailable"));

        PipelineRuntimeProtocol.Message decoded = PipelineRuntimeProtocol.decode(
                PipelineRuntimeProtocol.encode(PipelineRuntimeProtocol.error(
                        null, "pipeline-2", "BOOTSTRAP_NATIVE_RUNTIME", failure)));

        assertEquals(PipelineRuntimeProtocol.ERROR, decoded.type());
        assertEquals("BOOTSTRAP_NATIVE_RUNTIME", decoded.payload().get("failureStage"));
        assertEquals(ExceptionInInitializerError.class.getName(),
                decoded.payload().get("exceptionClass"));
        assertEquals("ND4J backend unavailable", decoded.error());
        List<?> chain = (List<?>) decoded.payload().get("exceptionChain");
        assertEquals(2, chain.size());
        assertEquals(IllegalStateException.class.getName(),
                ((Map<?, ?>) chain.get(1)).get("exceptionClass"));
        assertFalse(((List<?>) decoded.payload().get("stackTrace")).isEmpty());
    }

    @Test
    void progressRoundTripsPageAndTokenMetricsWithoutProtocolChanges() throws Exception {
        PipelineRuntimeProtocol.Message decoded = PipelineRuntimeProtocol.decode(
                PipelineRuntimeProtocol.encode(PipelineRuntimeProtocol.message(
                        PipelineRuntimeProtocol.PROGRESS, "request-progress", "vlm-document",
                        Map.of("phase", "VLM_EXTRACTION", "progressPercent", 50,
                                "currentPage", 2, "totalPages", 4,
                                "metrics", Map.of("generatedTokens", 128)))));

        assertEquals(PipelineRuntimeProtocol.VERSION, decoded.version());
        assertEquals(PipelineRuntimeProtocol.PROGRESS, decoded.type());
        assertEquals(2, decoded.payload().get("currentPage"));
        assertEquals(4, decoded.payload().get("totalPages"));
        assertEquals(128, ((Map<?, ?>) decoded.payload().get("metrics")).get("generatedTokens"));
    }

    @Test
    void nonProtocolLinesAreRejected() {
        assertThrows(Exception.class, () -> PipelineRuntimeProtocol.decode("ordinary output"));
    }
}
