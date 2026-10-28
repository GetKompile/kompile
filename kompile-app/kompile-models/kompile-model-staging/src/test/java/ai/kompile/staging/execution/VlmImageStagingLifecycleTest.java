package ai.kompile.staging.execution;

import ai.kompile.staging.web.dto.VlmGenerateRequest;
import org.eclipse.deeplearning4j.vlm.model.VisionLanguageModel;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VlmImageStagingLifecycleTest {
    @Test void generateDeletesStagingWhenPreprocessorFails() throws Exception { verifyFailure(true); }
    @Test void ocrDeletesStagingWhenPreprocessorFails() throws Exception { verifyFailure(false); }

    @Test void ocrDeletesStagingAfterSuccessfulPreprocessing() throws Exception {
        VlmExecutionService service = new VlmExecutionService();
        VisionLanguageModel model = mock(VisionLanguageModel.class);
        AtomicReference<File> staging = new AtomicReference<>();
        when(model.preprocessImage(any(File.class))).thenAnswer(call -> {
            staging.set(call.getArgument(0));
            assertTrue(staging.get().exists());
            return null; // no tensor/native runtime is needed to test file ownership
        });
        when(model.generate(any(), any())).thenAnswer(call -> {
            assertFalse(staging.get().exists(), "preprocessor no longer needs its input file");
            return "recognized";
        });
        configure(service, model);
        try {
            assertTrue(service.recognizeText(image()).isSuccess());
            assertFalse(staging.get().exists());
        } finally {
            service.shutdown();
        }
    }

    private void verifyFailure(boolean generate) throws Exception {
        VlmExecutionService service = new VlmExecutionService();
        VisionLanguageModel model = mock(VisionLanguageModel.class);
        AtomicReference<File> staging = new AtomicReference<>();
        when(model.preprocessImage(any(File.class))).thenAnswer(call -> {
            staging.set(call.getArgument(0));
            assertTrue(staging.get().isFile());
            throw new IOException("intentional preprocessing failure");
        });
        configure(service, model);
        try {
            if (generate) {
                assertFalse(service.generate(image(), new VlmGenerateRequest()).isSuccess());
            } else {
                assertFalse(service.recognizeText(image()).isSuccess());
            }
            assertNotNull(staging.get());
            assertFalse(staging.get().exists());
        } finally {
            service.shutdown();
        }
    }

    private static void configure(VlmExecutionService service, VisionLanguageModel model) throws Exception {
        Field modelField = VlmExecutionService.class.getDeclaredField("vlmModel");
        modelField.setAccessible(true);
        modelField.set(service, model);
        Field statusField = VlmExecutionService.class.getDeclaredField("modelStatus");
        statusField.setAccessible(true);
        statusField.set(service, "ready");
    }

    private static byte[] image() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes));
        return bytes.toByteArray();
    }
}
