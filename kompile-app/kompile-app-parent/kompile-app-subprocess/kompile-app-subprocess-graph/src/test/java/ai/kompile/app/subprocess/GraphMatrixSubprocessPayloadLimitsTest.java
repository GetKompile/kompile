package ai.kompile.app.subprocess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GraphMatrixSubprocessPayloadLimitsTest {

    @Test
    void boundedStreamRejectsDataPastLimitWithoutBufferingWholePayload() throws Exception {
        byte[] payload = new byte[1025];
        GraphMatrixSubprocessMain.BoundedInputStream input =
                new GraphMatrixSubprocessMain.BoundedInputStream(
                        new ByteArrayInputStream(payload), 1024);

        assertEquals(1024, input.readNBytes(1024).length);
        IOException error = assertThrows(IOException.class, input::read);
        assertTrue(error.getMessage().contains("1024"));
    }

    @Test
    void responseLimitMeasuresUtf8BytesNotJavaCharacters() {
        String multibyte = "犬".repeat(400);
        assertFalse(GraphMatrixSubprocessMain.utf8LengthExceeds(multibyte, 1200));
        assertTrue(GraphMatrixSubprocessMain.utf8LengthExceeds(multibyte, 1199));
    }

    @Test
    void megabyteScalePayloadIsRejectedAtConfiguredBoundary() throws Exception {
        int limit = 1024 * 1024;
        byte[] payload = "x".repeat(limit + 1).getBytes(StandardCharsets.UTF_8);
        GraphMatrixSubprocessMain.BoundedInputStream input =
                new GraphMatrixSubprocessMain.BoundedInputStream(
                        new ByteArrayInputStream(payload), limit);

        assertEquals(limit, input.readNBytes(limit).length);
        assertThrows(GraphMatrixSubprocessMain.PayloadTooLargeException.class, input::read);
    }

    @Test
    void anOversizedResultStopsSerializingAtTheCap() {
        AtomicInteger read = new AtomicInteger();
        List<String> huge = new AbstractList<>() {
            @Override
            public String get(int index) {
                read.incrementAndGet();
                return "x".repeat(100);
            }

            @Override
            public int size() {
                return 1_000_000;
            }
        };
        long previousCap = GraphMatrixSubprocessMain.responseByteCap;
        GraphMatrixSubprocessMain.responseByteCap = 16_000;
        try {
            assertThrows(GraphMatrixSubprocessMain.ResponseTooLargeException.class,
                    () -> GraphMatrixSubprocessMain.serializeResult("getAllNodes", huge, new ObjectMapper()));
        } finally {
            GraphMatrixSubprocessMain.responseByteCap = previousCap;
        }
        // A few hundred elements fill 16 KB; a tree copy reads all million before the cap applies.
        assertTrue(read.get() < 1_000, "read " + read.get() + " of 1,000,000 elements under a 16 KB cap");
    }

    @Test
    void aResultTheMapperCannotWriteIsAServerErrorNotABadArgument() {
        Exception error = assertThrows(Exception.class,
                () -> GraphMatrixSubprocessMain.serializeResult("getNode", new Object(), new ObjectMapper()));

        assertFalse(error instanceof IllegalArgumentException, error.toString());
        assertEquals("INTERNAL_ERROR", GraphMatrixSubprocessMain.rpcErrorCode(error));
    }
}
