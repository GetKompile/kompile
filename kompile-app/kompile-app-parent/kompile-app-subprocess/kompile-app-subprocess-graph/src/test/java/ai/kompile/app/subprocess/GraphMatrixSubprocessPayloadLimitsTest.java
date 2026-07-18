package ai.kompile.app.subprocess;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
}
