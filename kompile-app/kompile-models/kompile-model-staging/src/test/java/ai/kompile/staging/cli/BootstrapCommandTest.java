package ai.kompile.staging.cli;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.catalog.CatalogService;
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.staging.StagingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void scaleOutBootstrapConsumesSharedPinnedEncoderManifest() throws Exception {
        CatalogService catalogService = mock(CatalogService.class);
        when(catalogService.getModel("multilingual-e5-small")).thenReturn(Optional.empty());
        BootstrapCommand command = new BootstrapCommand(
                catalogService,
                mock(StagingService.class),
                mock(RegistryService.class),
                new ObjectMapper());
        new CommandLine(command).parseArgs("--model-id=multilingual-e5-small");

        DownloadRequest request = command.buildDownloadRequest();

        assertEquals("HUGGINGFACE", request.getSource());
        assertEquals("intfloat/multilingual-e5-small", request.getRepository());
        assertEquals("614241f622f53c4eeff9890bdc4f31cfecc418b3", request.getRevision());
        assertEquals("model.onnx", request.getFiles().get("model"));
        assertEquals("tokenizer.json", request.getFiles().get("tokenizer"));
        assertTrue(request.getTextAssetUrls().getModel().contains(
                "/resolve/614241f622f53c4eeff9890bdc4f31cfecc418b3/onnx/model.onnx"));
        assertEquals("ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665",
                request.getExpectedChecksums().get("model"));
        assertEquals(470_268_510L, request.getExpectedSizes().get("model"));
        assertEquals("987f7a67a38fa564c849bb5d277c52ab9088a84368fc0be31a354125aebb12a0",
                request.getExpectedChecksums().get("pooling_config"));
    }
}
