package ai.kompile.app.subprocess;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VlmTestSubprocessArgs")
class VlmTestSubprocessArgsTest {

    @TempDir Path tempDir;

    @Nested @DisplayName("Builder")
    class BuilderTests {
        @Test void defaults() {
            var args = VlmTestSubprocessArgs.builder()
                    .taskId("t1").filePath("/f.pdf").modelId("gotocr2")
                    .callbackBaseUrl("http://x").build();

            assertEquals("t1", args.taskId());
            assertEquals("DOCTAGS", args.outputFormat());
            assertEquals(VlmTestSubprocessArgs.DEFAULT_MAX_NEW_TOKENS, args.maxNewTokens());
            assertEquals(VlmTestSubprocessArgs.DEFAULT_TEMPERATURE, args.temperature());
            assertEquals(VlmTestSubprocessArgs.DEFAULT_TOP_P, args.topP());
            assertEquals(VlmTestSubprocessArgs.DEFAULT_BEAM_SIZE, args.beamSize());
            assertFalse(args.doSample());
            assertEquals(VlmTestSubprocessArgs.DEFAULT_PDF_RENDER_DPI, args.pdfRenderDpi());
            assertEquals(VlmTestSubprocessArgs.DEFAULT_PAGE_BATCH_SIZE, args.pageBatchSize());
            assertEquals("STATIC", args.kvCacheStrategy());
            assertTrue(args.optimizerEnabled());
            assertTrue(args.optimizerFp16());
            assertTrue(args.tritonEnabled());
            assertFalse(args.debugDiagnostics());
        }

        @Test void customValues() {
            var args = VlmTestSubprocessArgs.builder()
                    .taskId("t").filePath("/f").modelId("m")
                    .outputFormat("MARKDOWN").maxNewTokens(2048)
                    .temperature(0.7).topP(0.9).beamSize(4).doSample(true)
                    .pdfRenderDpi(150).pageBatchSize(4)
                    .kvCacheStrategy("PAGED").maxKvLen(8192)
                    .optimizerEnabled(false).optimizerFp16(false)
                    .tritonEnabled(false).tritonTf32(true)
                    .debugDiagnostics(true).opTiming(true)
                    .speculativeTokens(3)
                    .callbackBaseUrl("http://x")
                    .options(Map.of("k", "v")).build();

            assertEquals("MARKDOWN", args.outputFormat());
            assertEquals(2048, args.maxNewTokens());
            assertEquals(0.7, args.temperature(), 0.001);
            assertEquals(4, args.beamSize());
            assertTrue(args.doSample());
            assertEquals("PAGED", args.kvCacheStrategy());
            assertFalse(args.optimizerEnabled());
            assertTrue(args.debugDiagnostics());
            assertEquals(3, args.speculativeTokens());
        }

        @Test void invalidDefaultsApplied() {
            var args = VlmTestSubprocessArgs.builder()
                    .taskId("t").filePath("/f").modelId("m")
                    .callbackBaseUrl("http://x")
                    .maxNewTokens(-1).temperature(-1).topP(-1)
                    .beamSize(-1).pdfRenderDpi(-1).pageBatchSize(-1)
                    .outputFormat("").kvCacheStrategy("")
                    .build();

            assertEquals(VlmTestSubprocessArgs.DEFAULT_MAX_NEW_TOKENS, args.maxNewTokens());
            assertEquals(VlmTestSubprocessArgs.DEFAULT_TEMPERATURE, args.temperature());
            assertEquals(VlmTestSubprocessArgs.DEFAULT_TOP_P, args.topP());
            assertEquals(VlmTestSubprocessArgs.DEFAULT_BEAM_SIZE, args.beamSize());
            assertEquals("DOCTAGS", args.outputFormat());
            assertEquals("STATIC", args.kvCacheStrategy());
        }
    }

    @Nested @DisplayName("Serialization")
    class Serialization {
        @Test void fileRoundTrip() throws IOException {
            var original = VlmTestSubprocessArgs.builder()
                    .taskId("rt1").filePath("/f.pdf").modelId("gotocr2")
                    .outputFormat("JSON").maxNewTokens(512)
                    .callbackBaseUrl("http://x").build();

            Path file = tempDir.resolve("vlm-args.json");
            original.toFile(file);
            assertTrue(Files.exists(file));

            var restored = VlmTestSubprocessArgs.fromFile(file);
            assertEquals(original.taskId(), restored.taskId());
            assertEquals(original.outputFormat(), restored.outputFormat());
            assertEquals(original.maxNewTokens(), restored.maxNewTokens());
        }
    }
}
