package ai.kompile.ocr.models.pipeline;

import ai.kompile.ocr.OcrPipeline;
import ai.kompile.ocr.OcrPipelineConfig;
import ai.kompile.ocr.document.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PdfStagingLifecycleTest {
    @Test void defaultPipelineDeletesSuccessfulStaging() throws Exception { verify(false, false); }
    @Test void defaultPipelineDeletesFailedStaging() throws Exception { verify(false, true); }
    @Test void vlmPipelineDeletesSuccessfulStaging() throws Exception { verify(true, false); }
    @Test void vlmPipelineDeletesFailedStaging() throws Exception { verify(true, true); }

    private void verify(boolean vlm, boolean fail) throws Exception {
        byte[] content = "%PDF-test".getBytes(StandardCharsets.UTF_8);
        AtomicReference<File> staged = new AtomicReference<>();
        Consumer<File> processing = file -> {
            staged.set(file);
            assertTrue(file.isFile(), "staging must exist during synchronous processing");
            try {
                assertArrayEquals(content, Files.readAllBytes(file.toPath()));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            if (fail) throw new IllegalStateException("processing failed");
        };
        OcrPipeline pipeline = vlm ? new VlmDocumentPipeline() {
            @Override public List<ParsedDocument> processPdf(File file, OcrPipelineConfig config,
                    Consumer<PipelineProgress> progress) {
                processing.accept(file);
                return List.of();
            }
        } : new DefaultOcrPipeline(null) {
            @Override public List<ParsedDocument> processPdf(File file, OcrPipelineConfig config,
                    Consumer<PipelineProgress> progress) {
                processing.accept(file);
                return List.of();
            }
        };
        TrackingStream input = new TrackingStream(content);
        List<ParsedDocument> result = pipeline.processPdf(input, "test-document");
        assertEquals(fail ? 1 : 0, result.size());
        assertNotNull(staged.get());
        assertFalse(staged.get().exists(), "operation-owned PDF must be deleted before return");
        assertFalse(input.closed, "the caller still owns its InputStream");
        input.close();
    }

    private static final class TrackingStream extends ByteArrayInputStream {
        private boolean closed;
        TrackingStream(byte[] content) { super(content); }
        @Override public void close() throws java.io.IOException {
            closed = true;
            super.close();
        }
    }
}
