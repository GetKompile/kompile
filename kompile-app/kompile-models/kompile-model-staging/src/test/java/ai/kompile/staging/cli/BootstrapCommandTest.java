package ai.kompile.staging.cli;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void rawVlmOnnxIsAStagingInputNotARuntimeArtifact() throws Exception {
        Path decoder = Files.write(tempDir.resolve("decoder_model_merged.onnx"), new byte[]{1});
        ModelEntry entry = ModelEntry.builder()
                .modelId("vlm")
                .type(ModelType.VLM_PIPELINE)
                .modelFile(decoder.getFileName().toString())
                .build();

        assertFalse(BootstrapCommand.isRuntimeReady(entry, decoder));
    }

    @Test
    void completeVlmSdzBundleIsRuntimeReady() throws Exception {
        Path decoder = Files.write(tempDir.resolve("decoder.sdz"), new byte[]{1});
        Files.write(tempDir.resolve("vision_encoder.sdz"), new byte[]{2});
        ModelEntry entry = ModelEntry.builder()
                .modelId("vlm")
                .type(ModelType.VLM_PIPELINE)
                .modelFile(decoder.getFileName().toString())
                .build();

        assertTrue(BootstrapCommand.isRuntimeReady(entry, decoder));
    }

    @Test
    void decoderWithoutConvertedVisionEncoderIsNotRuntimeReady() throws Exception {
        Path decoder = Files.write(tempDir.resolve("decoder.sdz"), new byte[]{1});
        ModelEntry entry = ModelEntry.builder()
                .modelId("vlm")
                .type(ModelType.VLM_PIPELINE)
                .modelFile(decoder.getFileName().toString())
                .build();

        assertFalse(BootstrapCommand.isRuntimeReady(entry, decoder));
    }

    @Test
    void nonVlmRegularArtifactRetainsExistingReadinessContract() throws Exception {
        Path model = Files.write(tempDir.resolve("model.sdz"), new byte[]{1});
        ModelEntry entry = ModelEntry.builder()
                .modelId("dense")
                .type(ModelType.DENSE_ENCODER)
                .modelFile(model.getFileName().toString())
                .build();

        assertTrue(BootstrapCommand.isRuntimeReady(entry, model));
    }
}
